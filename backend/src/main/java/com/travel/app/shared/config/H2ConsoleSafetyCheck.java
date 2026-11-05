package com.travel.app.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 啟動時阻擋「prod profile 但 H2 console 仍開啟」的危險組合。
 *
 * SecurityConfig 已將 /h2-console/** 列入 CSRF ignore 並把 frameOptions 從 DENY 鬆成
 * sameOrigin —— 這兩項在 dev 是必要的便利性，但 prod 開啟意味著：
 *  - CSRF 攻擊面（DB 管理介面對任意 state-changing 請求開放）
 *  - 自家 iframe 限制鬆綁
 * 必須在啟動階段就攔下來，避免 prod 漏設 H2_CONSOLE=false 一路裸奔。
 *
 * 模式對齊 {@link AuthStartupCheck}。
 */
@Component
public class H2ConsoleSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(H2ConsoleSafetyCheck.class);
    private static final String PROD_PROFILE = "prod";

    private final Environment env;
    private final boolean h2ConsoleEnabled;

    public H2ConsoleSafetyCheck(Environment env,
                                @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.env = env;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @PostConstruct
    void verify() {
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains(PROD_PROFILE);
        if (isProd && h2ConsoleEnabled) {
            throw new IllegalStateException(
                    "Refusing to start: SPRING_PROFILES_ACTIVE=prod 但 spring.h2.console.enabled=true。"
                            + " H2 console 在 prod 開啟會讓 DB 管理介面暴露於 CSRF / iframe 攻擊。"
                            + " 請設定環境變數 H2_CONSOLE=false。");
        }
        if (h2ConsoleEnabled) {
            log.warn("H2 console 已啟用，僅供本機開發使用；prod 必須關閉（H2_CONSOLE=false）");
        }
    }
}
