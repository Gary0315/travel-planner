package com.travel.app.shared.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 啟動版本 log 的行為測試。
 *
 * <p><b>為什麼一行 log 值得測</b>：它存在的唯一理由是「正式站行為不符預期時，先排除
 * 『根本不是這一版』」（見 {@link BuildInfoStartupLog} 的 why）。診斷用的輸出壞掉時
 * 不會有任何症狀——不會有測試變紅、不會有使用者抱怨，只會在下次查問題時發現查不到，
 * 而那正是最不想再多花一輪的時刻。同 {@code RateLimitInterceptorTest} 守 XFF 觀測 log
 * 的理由（SPEC §11）。
 *
 * <p>守的是三件會讓它失去用途的事：commit 有印出來、本機情境不會印出空白、
 * 以及 profile 印的是實際生效值（正式站是不是真的跑在 prod 上，是同一輪要問的問題）。
 */
class BuildInfoStartupLogTest {

    private void captureLogs(Consumer<ListAppender<ILoggingEvent>> body) {
        Logger logger = (Logger) LoggerFactory.getLogger(BuildInfoStartupLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.accept(appender);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private String logOnce(MockEnvironment env, String commit, String branch, String instanceId) {
        StringBuilder captured = new StringBuilder();
        captureLogs(appender -> {
            new BuildInfoStartupLog(env, commit, branch, instanceId).logBuildInfo();
            assertThat(appender.list).as("啟動一定要留下一行版本資訊").hasSize(1);
            captured.append(appender.list.get(0).getFormattedMessage());
        });
        return captured.toString();
    }

    @Test
    void logs_short_commit_branch_and_instance_on_render() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        String message = logOnce(env, "4cf6edb23c074340de1bf8d42a4e3c7c9a089b57", "main", "srv-abc-123");

        // 短 sha 才貼得進 git / gh 指令；印全長 40 碼只是佔位置
        assertThat(message)
                .contains("commit=4cf6edb")
                .doesNotContain("4cf6edb23")
                .contains("branch=main")
                .contains("profile=prod")
                .contains("instance=srv-abc-123");
    }

    @Test
    void marks_missing_render_variables_as_local() {
        // 本機沒有 RENDER_* 這些變數，Spring 會把 ${VAR:} 解成空字串
        String message = logOnce(new MockEnvironment(), "", "", "");

        // 空欄位會讓這行變成「commit= branch=」——看起來像壞掉，分不出是沒設還是取不到。
        // 明講「本機」本身就是資訊：代表這個 process 不是跑在 Render 上
        assertThat(message)
                .contains("commit=本機")
                .contains("branch=本機")
                .contains("instance=本機")
                .contains("profile=default");
    }
}
