package com.travel.app.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.shared.config.SecurityConfig;
import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.user.dto.LoginRequest;
import com.travel.app.user.dto.VerifiedGoogleIdentity;
import com.travel.app.user.entity.UserEntity;
import com.travel.app.user.repository.UserRepository;
import com.travel.app.user.service.GoogleIdTokenVerifierService;
import com.travel.app.user.service.UserService;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 走真實 Spring Security filter chain 的 web-slice 整合測試。
 *
 * 與 AuthControllerTest（standaloneSetup，跳過 filter chain）互補：
 * - AuthControllerTest 守 controller 的業務邏輯
 * - 本測試守 SecurityConfig 的 CSRF / XSRF cookie / 認證入口配置
 *
 * 覆蓋 SPEC §19.7 的 CSRF 策略：
 *  - 所有 state-changing 端點（含 /api/auth/google 與 /api/auth/logout）強制 CSRF token，
 *    缺 token 一律 403。/api/auth/google 一併納管是為了擋 Login CSRF
 *  - GET 端點必須寫出 XSRF-TOKEN cookie 且 HttpOnly=false（前端 JS 要讀）
 *  - /api/auth/me 未登入回 401（HttpStatusEntryPoint）
 */
@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
@TestMethodOrder(OrderAnnotation.class)
// 為什麼要 @Order：Spring Security 的 `with(csrf())` 第一次被呼叫時，會把 ServletContext 裡的
// CsrfTokenRepository 替換成包裝過的 TestCsrfTokenRepository（內部用 HttpSessionCsrfTokenRepository）。
// 這個替換**會延續到同一個 test class 的後續 test**。因此 get_request_writes_xsrf_token_cookie 必須
// 跑在所有 with(csrf()) 之前，否則它測的會是 HttpSession-based 的行為，cookie 永遠不會被寫。
class AuthSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private GoogleIdTokenVerifierService verifierService;
    @MockBean private UserService userService;
    @MockBean private UserRepository userRepository;
    // WebConfig 建全站每日額度時要用；本測試不打 /generate，不必接上記憶體版
    @MockBean private ApiCallCounterRepository counterRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @Order(2)
    void post_auth_google_without_csrf_token_returns_403() throws Exception {
        // /api/auth/google **不在** CSRF ignore 名單：擋 Login CSRF（攻擊者用自己的 ID token
        // 騙受害者瀏覽器 POST，把受害者靜默登入到攻擊者帳號）。
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("good-token"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void post_auth_google_with_csrf_token_succeeds() throws Exception {
        when(verifierService.isEnabled()).thenReturn(true);
        when(verifierService.verify("good-token")).thenReturn(Optional.of(
                new VerifiedGoogleIdentity("sub-1", "a@example.com", "Alice", null)));
        when(userService.findOrCreate(any())).thenReturn(
                new UserEntity("sub-1", "a@example.com", "Alice", null));

        mockMvc.perform(post("/api/auth/google")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("good-token"))))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void post_auth_logout_without_csrf_token_returns_403() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void post_auth_logout_with_csrf_token_returns_204() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(1) // 必須跑在所有 with(csrf()) 之前 —— 詳見 class 上的註解
    void get_request_writes_xsrf_token_cookie_for_frontend_js() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                // 前端 axios / apiFetch 需要從 document.cookie 讀，必須 HttpOnly=false
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                // path=/ 確保 SPA 全域共用同一個 cookie
                .andExpect(cookie().path("XSRF-TOKEN", "/"));
    }

    @Test
    @Order(2)
    void me_returns_401_for_anonymous() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
