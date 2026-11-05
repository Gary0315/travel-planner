package com.travel.app.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ClientErrorController} 的行為守則。
 *
 * <p>用 standaloneSetup（同 HealthControllerTest）而非 @SpringBootTest：這個 controller 沒有
 * 任何依賴，測的是它自己的字串處理與回應碼，不需要真的 context。
 *
 * <p>斷言對象刻意是**印出來的 log 內容**（ListAppender，同 RateLimitInterceptorTest），不是回應
 * ——這個端點的產出就是那行 log，只驗 204 等於什麼都沒驗。
 */
class ClientErrorControllerTest {

    /**
     * U+2028 LINE SEPARATOR / U+2029 PARAGRAPH SEPARATOR。
     * 用字元碼組出來而不是直接打在原始碼裡：這兩個字元不可見，寫進檔案會被編碼轉換或編輯器
     * 悄悄換掉，測試就變成什麼都沒驗還一直亮綠燈。
     */
    private static final String LINE_SEP = String.valueOf((char) 0x2028);
    private static final String PARA_SEP = String.valueOf((char) 0x2029);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientErrorController()).build();
    }

    /**
     * 本測試類最重要的一案：log injection（CWE-117）。
     *
     * <p>這個端點是專案唯一不需登入、任何人都能寫入的入口，內容 100% 來自外部。若換行沒被剝掉，
     * 攻擊者送一段帶 {@code \n} 的字串就能在 Render log 裡**憑空捏造出一行 [itinerary-gen]**，
     * 而 log 是純文字流、事後無法分辨哪行是 Spring 印的。那份資料正是 2026-08-11 定位白屏與
     * 計算「非自己生成次數」的依據（PLAN M3），被污染等於觀測能力歸零。
     */
    @Test
    void log_injection_cannot_forge_extra_lines() throws Exception {
        String forged = "沒事\n2026-08-12T07:00:00.000Z  INFO 1 --- [travel-planner-backend] "
                + "c.t.a.a.controller.ItineraryController   : [itinerary-gen] ok days=3 userId=null";

        captureLogs(appender -> {
            perform(json(forged));

            // 一次回報只能產生一筆 log event，而且那筆裡不能有換行——
            // 兩者缺一，攻擊者就能讓 log 檔案多出他自己寫的一行
            assertThat(appender.list).hasSize(1);
            String logged = appender.list.get(0).getFormattedMessage();
            assertThat(logged).doesNotContain("\n").doesNotContain("\r");
            // 內容仍在（只是換行被換成空白），不是整段被丟掉——診斷線索不能因為防護而消失
            assertThat(logged).contains("沒事").contains("[itinerary-gen]");
            // 關鍵：偽造那段跟我們的前綴黏在同一行，不會被誤讀成獨立的一筆生成紀錄
            assertThat(logged).startsWith("[ui-crash]");
        });
    }

    /** \n 之外的第二條偽造路徑：Unicode 行分隔符在部分 log 檢視器裡同樣會斷行 */
    @Test
    void unicode_line_separators_are_neutralised() throws Exception {
        captureLogs(appender -> {
            perform(json("前段" + LINE_SEP + "後段" + PARA_SEP + "結尾"));

            String logged = appender.list.get(0).getFormattedMessage();
            assertThat(logged).doesNotContain(LINE_SEP).doesNotContain(PARA_SEP);
            assertThat(logged).contains("前段").contains("結尾");
        });
    }

    /** 單一請求不該塞爆 log：message 記到 300 字為止 */
    @Test
    void over_long_message_is_truncated() throws Exception {
        captureLogs(appender -> {
            perform(json("x".repeat(400)));

            String logged = appender.list.get(0).getFormattedMessage();
            assertThat(logged).contains("…");
            assertThat(logged).contains("x".repeat(300));
            assertThat(logged).doesNotContain("x".repeat(301));
        });
    }

    /** 空欄位統一成 "-"，讓每行欄位數固定、grep 時不會因為缺格而錯位 */
    @Test
    void blank_fields_become_dash() throws Exception {
        captureLogs(appender -> {
            perform("{\"code\":\"\",\"scope\":null,\"message\":\"   \"}");

            String logged = appender.list.get(0).getFormattedMessage();
            assertThat(logged).contains("code=-").contains("scope=-").contains("message=-");
        });
    }

    /**
     * 204 是契約的一部分：前端用 res.ok 決定要顯示錯誤代碼還是退回顯示 error.message
     * （回報沒送達時代碼是廢的）。改成回 200 + body 會讓那個降級判斷悄悄失準。
     */
    @Test
    void report_returns_204_no_content() throws Exception {
        mockMvc.perform(post("/api/client-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Cannot read properties of null (reading 'time')")))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    /** MockMvc 的 checked exception 在 lambda 裡很吵，包一層 */
    private void perform(String body) {
        try {
            mockMvc.perform(post("/api/client-error")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());
        } catch (Exception e) {
            throw new AssertionError("回報請求本身失敗了", e);
        }
    }

    private static String json(String message) {
        String escaped = message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"code\":\"UI-TEST-0001\",\"scope\":\"results\",\"path\":\"/\","
                + "\"component\":\"DetailPanel\",\"message\":\"" + escaped + "\"}";
    }

    /** 同 RateLimitInterceptorTest：掛一個假 appender 把 log 抓成物件來斷言，用完拆掉 */
    private void captureLogs(Consumer<ListAppender<ILoggingEvent>> body) {
        Logger logger = (Logger) LoggerFactory.getLogger(ClientErrorController.class);
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
}
