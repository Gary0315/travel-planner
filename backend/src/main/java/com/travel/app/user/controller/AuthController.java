package com.travel.app.user.controller;

import com.travel.app.user.dto.CurrentUserResponse;
import com.travel.app.user.dto.LoginRequest;
import com.travel.app.user.dto.VerifiedGoogleIdentity;
import com.travel.app.user.entity.UserEntity;
import com.travel.app.user.repository.UserRepository;
import com.travel.app.user.service.GoogleIdTokenVerifierService;
import com.travel.app.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
// SecurityContextHolder 仍保留：logout 用 clearContext() 把當前 request thread-local 清掉
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Google 登入相關端點。詳見 SPEC §19.5。
 *
 * - POST /api/auth/google：驗 ID Token → findOrCreate user → 寫 SecurityContext + session
 * - POST /api/auth/logout：清 SecurityContext + session.invalidate()
 * - GET  /api/auth/me   ：回傳當前 user；未登入 401
 *
 * 認證狀態靠 Spring Security 的 SecurityContext，透過 HttpSessionSecurityContextRepository
 * 持久化到 HttpSession，再由 Spring Session JDBC 持久化到 DB（跨重啟存活）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final GoogleIdTokenVerifierService verifierService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(GoogleIdTokenVerifierService verifierService,
                          UserService userService,
                          UserRepository userRepository,
                          SecurityContextRepository securityContextRepository) {
        this.verifierService = verifierService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/google")
    public CurrentUserResponse loginWithGoogle(@Valid @RequestBody LoginRequest body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        if (!verifierService.isEnabled()) {
            // 沒設定 client-id：dev 階段刻意關閉登入；前端應 hide 登入按鈕。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Login not enabled");
        }

        VerifiedGoogleIdentity identity = verifierService.verify(body.idToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token"));

        // 並發首次登入競態：兩個請求對同一個 google_sub 同時走 find-then-insert 分支時，
        // 第二個會撞 UNIQUE 約束。一次重試後必然走 update 分支成功（row 已存在）。
        UserEntity user;
        try {
            user = userService.findOrCreate(identity);
        } catch (DataIntegrityViolationException e) {
            log.warn("findOrCreate hit unique-constraint race, retrying once");
            user = userService.findOrCreate(identity);
        }
        log.info("User login: userId={}", user.getId()); // 不記 email / sub（PII，見 SPEC §19.7）

        // Session fixation 防護：若請求已帶有 anonymous session id（pre-login 攻擊植入），
        // 在升級為已認證 session 之前換新 id。Spring Security 的 sessionFixation().changeSessionId()
        // 只在走 AuthenticationManager 流程時自動觸發；本 controller 手動 setContext 不會觸發，
        // 必須顯式呼叫。詳見 SPEC §19.7（session fixation 修補）。
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        // 寫入 SecurityContext 並由 SecurityContextRepository 持久化到 session。
        // SecurityContextHolder.setContext 只影響當前 thread-local，request 結束就會被清掉；
        // 真正讓下次請求看到登入態的是 saveContext（寫到 HttpSession，再由 Spring Session JDBC
        // 寫進 DB）。本支 endpoint 不在當前 request 內再用 SecurityContextHolder 讀 auth，
        // 所以 thread-local 設定可略。
        Authentication auth = new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        securityContextRepository.saveContext(context, request, response);

        return CurrentUserResponse.from(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        // 不論是否有 session 都當作成功（idempotent）
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        Long userId = currentUserId(authentication);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findById(userId)
                .map(CurrentUserResponse::from)
                .orElseThrow(() -> {
                    // user_id 在 session 但 DB 已被刪 — 罕見情境，視為認證失效
                    log.warn("Session refers to userId={} which no longer exists", userId);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists");
                });
    }

    private Long currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) return id;
        // 匿名 user 的 principal 是 "anonymousUser" String — 視為未登入
        return null;
    }
}
