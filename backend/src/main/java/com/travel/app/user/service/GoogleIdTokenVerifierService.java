package com.travel.app.user.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.travel.app.user.dto.VerifiedGoogleIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 包裝 google-api-client 的 GoogleIdTokenVerifier。
 *
 * 驗證重點（由 GoogleIdTokenVerifier 內部一併處理）：
 * - 簽章（Google JWK，verifier 內部 cache，不會每次都打 Google）
 * - iss = accounts.google.com 或 https://accounts.google.com
 * - aud = 設定中的 client-id
 * - exp 未過期
 *
 * verifier 由 {@link com.travel.app.user.config.AuthConfig} 建立並注入；
 * client-id 為空時 AuthConfig 回 null → 此 service 進入 disabled 模式，
 * isEnabled() = false，AuthController 會回 503。
 *
 * 詳見 SPEC §19.3。
 */
@Service
public class GoogleIdTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierService.class);

    @Nullable
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenVerifierService(@Nullable GoogleIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    public boolean isEnabled() {
        return verifier != null;
    }

    /**
     * 驗證 ID token，回 identity；失敗（含 disabled）回 empty。
     * 不主動 throw —— 呼叫方（AuthController）依 empty 統一回 401。
     */
    public Optional<VerifiedGoogleIdentity> verify(String idTokenString) {
        if (!isEnabled() || idTokenString == null || idTokenString.isBlank()) {
            return Optional.empty();
        }
        try {
            GoogleIdToken token = verifier.verify(idTokenString);
            if (token == null) {
                // verifier 內部認為 invalid（signature / aud / iss / exp 不對），回 null
                return Optional.empty();
            }
            GoogleIdToken.Payload payload = token.getPayload();
            return Optional.of(new VerifiedGoogleIdentity(
                    payload.getSubject(),
                    payload.getEmail(),
                    (String) payload.get("name"),
                    (String) payload.get("picture")
            ));
        } catch (Exception e) {
            // 網路 / JSON parse / 其他例外都當作驗證失敗
            String msg = e.getMessage() == null ? "" : e.getMessage();
            log.warn("Google ID token verification failed: {}", msg.length() > 200 ? msg.substring(0, 200) : msg);
            return Optional.empty();
        }
    }
}
