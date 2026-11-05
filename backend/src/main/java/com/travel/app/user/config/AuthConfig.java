package com.travel.app.user.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.Collections;

/**
 * Google 登入的相依物件建立。
 *
 * 把 {@link GoogleIdTokenVerifier} 拆成獨立 @Bean 而不是在 GoogleIdTokenVerifierService
 * 內 new 出來，理由：
 * - 讓 service 只剩一個 constructor，Spring 不會在「兩個 constructor」狀況下卡住
 * - 測試可直接 new service(mockVerifier)，不需 reflection 或包裝
 * - client-id 未設時回 null → Spring 註冊 null bean，service 用 @Nullable 接收
 */
@Configuration
public class AuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    @Bean
    @Nullable
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            @Value("${app.auth.google.client-id:}") String clientId) {
        String trimmed = (clientId == null) ? "" : clientId.trim();
        if (trimmed.isEmpty()) {
            log.warn("app.auth.google.client-id 未設定，Google 登入功能停用（AuthController 將回 503）");
            return null;
        }
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(trimmed))
                .build();
    }
}
