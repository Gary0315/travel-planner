package com.travel.app.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.shared.exception.GlobalExceptionHandler;
import com.travel.app.user.dto.LoginRequest;
import com.travel.app.user.dto.VerifiedGoogleIdentity;
import com.travel.app.user.entity.UserEntity;
import com.travel.app.user.repository.UserRepository;
import com.travel.app.user.service.GoogleIdTokenVerifierService;
import com.travel.app.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private GoogleIdTokenVerifierService verifierService;
    private UserService userService;
    private UserRepository userRepository;
    private SecurityContextRepository securityContextRepository;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        verifierService = mock(GoogleIdTokenVerifierService.class);
        userService = mock(UserService.class);
        userRepository = mock(UserRepository.class);
        securityContextRepository = mock(SecurityContextRepository.class);

        AuthController controller = new AuthController(
                verifierService, userService, userRepository, securityContextRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void login_returns_503_when_verifier_disabled() throws Exception {
        when(verifierService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("any-token"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Login not enabled"));
    }

    @Test
    void login_returns_401_when_token_invalid() throws Exception {
        when(verifierService.isEnabled()).thenReturn(true);
        when(verifierService.verify("bad-token")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("bad-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Google ID token"));
    }

    @Test
    void login_returns_user_and_persists_security_context_on_success() throws Exception {
        when(verifierService.isEnabled()).thenReturn(true);
        when(verifierService.verify("good-token")).thenReturn(Optional.of(
                new VerifiedGoogleIdentity("sub-1", "a@example.com", "Alice", "https://pic")));

        UserEntity user = userWithId(42L, "sub-1", "a@example.com", "Alice", "https://pic");
        when(userService.findOrCreate(any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("good-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("a@example.com"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.pictureUrl").value("https://pic"));

        // 認證狀態必須被 saveContext 持久化（否則之後 GET /api/auth/me 拿不到 user）
        verify(securityContextRepository).saveContext(any(), any(), any());
    }

    @Test
    void me_returns_401_when_no_authentication() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));
    }

    @Test
    void me_returns_401_when_principal_not_a_user_id() throws Exception {
        // 例如 anonymous filter 設的 "anonymousUser" String principal
        Authentication anonymous = new UsernamePasswordAuthenticationToken(
                "anonymousUser", null, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        mockMvc.perform(get("/api/auth/me").principal(anonymous))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns_401_when_authentication_is_marked_not_authenticated() throws Exception {
        // 即便 principal 是 Long、也帶 ROLE_USER，只要 isAuthenticated()=false 就視為未登入。
        // 對齊 currentUserId() 的「auth == null || !auth.isAuthenticated()」短路條件。
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                7L, null); // 3-arg constructor 才會自動 setAuthenticated(true)；2-arg 預設 false
        // 雙保險：明確 setAuthenticated(false) 表達意圖
        token.setAuthenticated(false);

        mockMvc.perform(get("/api/auth/me").principal(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));
    }

    @Test
    void me_returns_current_user_when_authenticated() throws Exception {
        UserEntity user = userWithId(7L, "sub-7", "bob@example.com", "Bob", null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        Authentication auth = new UsernamePasswordAuthenticationToken(
                7L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(get("/api/auth/me").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.name").value("Bob"));
    }

    @Test
    void me_returns_401_when_userId_no_longer_exists_in_db() throws Exception {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        Authentication auth = new UsernamePasswordAuthenticationToken(
                999L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(get("/api/auth/me").principal(auth))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("User no longer exists"));
    }

    @Test
    void logout_returns_204_even_without_session() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void login_with_blank_token_returns_400() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_retries_findOrCreate_on_unique_constraint_race() throws Exception {
        // 並發首次登入：兩個請求對同一個 google_sub 同時走 find-then-insert，第二個撞 UNIQUE。
        // 控制器要 catch 一次 DataIntegrityViolationException 並重試（第二次走 update 分支必成功）。
        when(verifierService.isEnabled()).thenReturn(true);
        when(verifierService.verify("good-token")).thenReturn(Optional.of(
                new VerifiedGoogleIdentity("sub-race", "race@example.com", "Race", null)));

        UserEntity user = userWithId(99L, "sub-race", "race@example.com", "Race", null);
        when(userService.findOrCreate(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate google_sub"))
                .thenReturn(user);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("good-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99));

        verify(userService, times(2)).findOrCreate(any());
    }

    /** 用 reflection 強塞 id，省得改 entity 為了測試開公開 setter。 */
    private static UserEntity userWithId(Long id, String sub, String email, String name, String picture) {
        UserEntity u = new UserEntity(sub, email, name, picture);
        try {
            var field = UserEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
