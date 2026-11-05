package com.travel.app.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Google 登入的 Spring Security 設定。詳見 SPEC §19.4 / §19.7。
 *
 * 設計重點：
 * - **權限模型刻意 permitAll**：本階段（auth endpoints）所有路徑都不在 Security filter 強制
 *   authenticated()；登入後的權限檢查（例如 /api/me/**）由各 controller 透過 Authentication 自行
 *   判斷。下一階段加 /api/me/** 時，再到此處用 .authenticated() 統一管控。
 * - **CSRF**：用 CookieCsrfTokenRepository.withHttpOnlyFalse 讓前端 JS 可讀 XSRF-TOKEN cookie，
 *   axios 攔截器把它塞回 X-XSRF-TOKEN header。**所有** state-changing 端點（含 /api/auth/google）
 *   都強制 CSRF token —— 含 google 是為了擋 Login CSRF（攻擊者用自己的 ID token 騙受害者瀏覽器
 *   POST，把受害者靜默登入到攻擊者帳號）。前端 AuthProvider mount 時的 GET /api/auth/me 會讓
 *   Spring Security 寫入 XSRF-TOKEN，登入按鈕僅在此後可見，沒有死鎖。
 * - **CORS**：銜接既有 WebConfig.addCorsMappings 的設定 — Spring Security 6 的 cors() 會透過
 *   HandlerMappingIntrospector 抓 WebMvc CORS 配置，不需重複定義。
 * - **Session**：IF_REQUIRED — login 時建立 session，其他端點按需建（與既有匿名流程不衝突）。
 * - **未登入回應**：401 而非預設的 403/redirect，前端依此判斷需要重新登入。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            // 與 server.servlet.session.cookie.secure 共用同一個 env var（SESSION_COOKIE_SECURE），
            // 保持 session cookie 與 XSRF-TOKEN cookie 的 Secure flag 同步：dev http 雙雙關閉、
            // prod https 雙雙打開。SPEC §19.7。
            @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // 預設 path 是 request context path；明確設成 "/" 確保 SPA 全域共用同一個 csrf cookie
        csrfTokenRepository.setCookiePath("/");
        // prod (HTTPS) 必須開啟 Secure flag，避免 XSRF-TOKEN 在 HTTP 連線中外漏
        csrfTokenRepository.setSecure(cookieSecure);

        // 6.x 預設的 CsrfTokenRequestAttributeHandler 會把 token 寫進 request attribute；
        // 顯式建立物件，方便日後若需要切到 XorCsrfTokenRequestAttributeHandler 做 BREACH 防護
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null); // 與 Angular / axios 預設行為相容

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        // CSRF 預設套用到所有 state-changing methods (POST/PUT/DELETE/PATCH)，
                        // 包含 /api/auth/google ——攻擊者雖然偽造不出 ID token 簽章，但可用自己的
                        // 合法 token 騙受害者瀏覽器去 POST，把受害者靜默登入到攻擊者帳號（Login CSRF）。
                        // 前端 apiFetch 在 POST 前先 ensureCsrfCookie，AuthProvider mount 時的
                        // GET /api/auth/me 配合下方 CsrfCookieEagerFilter 觸發 Spring Security 寫 cookie，
                        // 登入按鈕在此後才可見。SPEC §19.7。
                        //
                        // 例外（ignoringRequestMatchers）只給「天生不會被跨站偽造的」端點：
                        // - /api/places/**：純 GET（CSRF 對 GET 無意義），SameSite=Lax 也已防護
                        // - /h2-console/**：H2 console form 不會帶 X-XSRF-TOKEN（純 dev 工具）；
                        //   prod 由 H2ConsoleSafetyCheck 強制關閉
                        .ignoringRequestMatchers(
                                "/api/places/**",
                                "/h2-console/**"
                        ))
                // Spring Security 6 把 CSRF token 改成 deferred（lazy）：CsrfFilter 只把 supplier
                // 放進 request attribute，要等到下游真的呼叫 csrfToken.getToken() 才會
                // loadToken / generateToken / saveToken（後者才寫 XSRF-TOKEN cookie）。但 GET endpoint
                // 大多不會碰 token，導致 SPA 第一次 GET /api/auth/me 拿不到 cookie，下一個 POST
                // 直接死在 CSRF 驗證上。
                //
                // 加一個 OncePerRequestFilter，每個請求都強制 .getToken() 一次，把 deferred
                // 變 eager。這是 Spring Security 6 官方文件給 SPA 的標準解法。
                .addFilterAfter(new CsrfCookieEagerFilter(), CsrfFilter.class)
                // H2 console 用 <iframe> 顯示查詢結果。Spring Security 預設加
                // X-Frame-Options: DENY 會把 iframe 整個擋掉。改成 sameOrigin 讓自家 iframe 過關。
                .headers(headers -> headers.frameOptions(f -> f.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 不用內建的 form login / basic auth
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 我們有自己的 /api/auth/logout
                .authorizeHttpRequests(auth -> auth
                        // 所有路徑先 permitAll；個別端點若需登入由 controller 透過 Authentication 自行判斷。
                        // 下一階段加 /api/me/** 時改為 .requestMatchers("/api/me/**").authenticated()
                        .anyRequest().permitAll()
                );

        return http.build();
    }

    /**
     * Session-based context 持久化。AuthController 在 login 成功後呼叫 saveContext 顯式寫入。
     * Spring Security 6 不再有自動 save by filter — 必須顯式 save，這是設計上的取捨。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 把 deferred CSRF token 改成 eager —— 確保每個 request 都會觸發
     * CookieCsrfTokenRepository.saveToken()，前端 SPA 在第一個 GET 後就能讀到 XSRF-TOKEN cookie。
     *
     * 為什麼需要：Spring Security 6 預設讓 CsrfFilter 只放 deferred supplier 進 request attribute，
     * 要等到 controller 或下游 filter 真的呼叫 csrfToken.getToken() 才會 load / generate / save。
     * 但 SPA 第一個請求往往是 GET /api/auth/me（純讀），沒人會去碰 token，結果 cookie 永遠不會
     * 被寫進 response，下一個 POST 就無 token 可帶、CSRF 驗證 403。
     *
     * 影響面：每個 request 多一次 cookie write 的 overhead，可忽略；CSRF 驗證邏輯（ignore 路徑、
     * X-XSRF-TOKEN header 比對）不受影響。
     */
    private static final class CsrfCookieEagerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
            if (token != null) {
                token.getToken(); // 觸發 deferred load + cookie save
            }
            chain.doFilter(request, response);
        }
    }
}
