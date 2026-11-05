package com.travel.app.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 啟動時印一行「現在跑的是哪一版」。
 *
 * <p><b>為什麼需要</b>（2026-07-30 踩過）：正式站部署了舊版而不自知，最後是靠「某行新加的
 * log 查不到」反推出來的。但那招不可靠——同一行 log 的文字在兩個 commit 之間沒變時，
 * 看到它只能證明「至少舊的那個 commit 上線了」，證不出新的。而 Spring Boot 自己印的
 * {@code v0.0.1-SNAPSHOT} 每一版都一樣，等於沒有版本資訊。
 *
 * <p>沒有這行時，每次「正式站行為跟預期不符」都得先花一輪去確認「是 code 寫錯，還是
 * 根本不是這一版」。會遇到的情境：部署失敗但舊實例還在跑、以為 push 了其實沒有、
 * build 吃到 cache。
 *
 * <p>值來自 Render 自動注入的環境變數（<a href="https://render.com/docs/environment-variables">官方文件</a>
 * 2026-07-30 查證）：{@code RENDER_GIT_COMMIT}、{@code RENDER_GIT_BRANCH}、
 * {@code RENDER_INSTANCE_ID}。本機開發時這些不存在，會印「本機」，這也是有用的資訊
 * （代表不是跑在 Render 上）。不需要 Maven plugin 或 build-info.properties。
 *
 * <p>用 {@code @PostConstruct} 而非 {@code ApplicationReadyEvent}：這樣後續啟動失敗時
 * 版本資訊也已經印出來了——啟動失敗恰好是最需要知道版本的時候。
 */
@Component
public class BuildInfoStartupLog {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoStartupLog.class);

    /** commit SHA 取前 7 碼即可，git 與 gh 都吃短 sha。 */
    private static final int SHORT_SHA_LENGTH = 7;
    private static final String LOCAL = "本機";

    private final Environment env;
    private final String commit;
    private final String branch;
    private final String instanceId;

    public BuildInfoStartupLog(Environment env,
                               @Value("${RENDER_GIT_COMMIT:}") String commit,
                               @Value("${RENDER_GIT_BRANCH:}") String branch,
                               @Value("${RENDER_INSTANCE_ID:}") String instanceId) {
        this.env = env;
        this.commit = commit;
        this.branch = branch;
        this.instanceId = instanceId;
    }

    @PostConstruct
    void logBuildInfo() {
        String[] profiles = env.getActiveProfiles();
        log.info("啟動版本：commit={} branch={} profile={} instance={}",
                shorten(commit),
                orLocal(branch),
                profiles.length == 0 ? "default" : String.join(",", profiles),
                orLocal(instanceId));
    }

    private String shorten(String sha) {
        if (sha == null || sha.isBlank()) {
            return LOCAL;
        }
        String trimmed = sha.trim();
        return trimmed.length() <= SHORT_SHA_LENGTH ? trimmed : trimmed.substring(0, SHORT_SHA_LENGTH);
    }

    private String orLocal(String value) {
        return value == null || value.isBlank() ? LOCAL : value.trim();
    }
}
