package com.travel.app.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 啟動時阻擋「prod profile 但 app.auth.google.client-id 未設定」的危險組合。
 *
 * 沒有 client-id 時 GoogleIdTokenVerifierService 會自動 disable，AuthController 回 503。
 * dev 階段這是刻意行為，但 prod 漏設會讓所有使用者無法登入卻完全沒有 server log alert，
 * 必須在啟動階段就攔下來。
 *
 * 模式對齊 DatabaseSafetyCheck（SPEC §10）。詳見 SPEC §19.9。
 */
@Component
public class AuthStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AuthStartupCheck.class);
    private static final String PROD_PROFILE = "prod";

    private final Environment env;
    private final String clientId;

    public AuthStartupCheck(Environment env,
                            @Value("${app.auth.google.client-id:}") String clientId) {
        this.env = env;
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    @PostConstruct
    void verify() {
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains(PROD_PROFILE);
        boolean isMissing = clientId.isEmpty();

        if (isProd && isMissing) {
            throw new IllegalStateException(
                    "Refusing to start: SPRING_PROFILES_ACTIVE=prod 但 app.auth.google.client-id 未設定。"
                            + " 請設定 GOOGLE_OAUTH_CLIENT_ID 環境變數（Google Cloud Console 建立的 OAuth Client ID），"
                            + " 否則所有使用者登入會持續 503。");
        }
        if (isMissing) {
            log.warn("app.auth.google.client-id 未設定，Google 登入端點將回 503。"
                    + " 若要啟用登入請設定 GOOGLE_OAUTH_CLIENT_ID 環境變數。");
        }
    }
}
