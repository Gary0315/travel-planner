package com.travel.app.shared.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * 前端崩潰回報。前端 ErrorBoundary 接到 render 例外時 POST 一筆，後端記一行 {@code [ui-crash]}。
 *
 * <p><b>為什麼需要</b>（2026-08-11）：正式站出現白畫面，但前端的錯誤從頭到尾只活在使用者那台
 * 瀏覽器的 console 裡，Render log 一個字都看不到。那次能定位純粹因為是開發者自己踩到；
 * 換成親友在手機上炸，最好的情況是他截圖回報，最壞的情況是他默默關掉頁面。這與 08-06
 * 「昨天幾次生成答不出來」是同一類問題，解法也同一套：讓它在 log 裡留下可數的一行。
 *
 * <p><b>這是本專案第一個不需登入的公開寫入端點</b>，所以防護是設計的一部分而非事後補丁：
 * <ul>
 *   <li><b>限流</b>：{@code WebConfig.clientErrorRateLimiter} per-IP 10 次/分。一次崩潰只送一筆，
 *       正常使用打不到；擋的是拿它當 log 灌爆管道的人。</li>
 *   <li><b>Log injection</b>：收進來的字串一律剝掉控制字元（含 {@code \n}、{@code \r} 與
 *       Unicode 行分隔符）再截斷。少了這道，攻擊者送一段帶換行的字串就能在 log 裡憑空捏造出
 *       一行 {@code [itinerary-gen] ok ...}，污染掉我們賴以判斷用量的資料。</li>
 *   <li><b>不收使用者輸入</b>：欄位是白名單（code/scope/path/component/message），沒有一格裝得下
 *       自然語言需求。與 {@code GeminiItineraryService}、{@code ItineraryController} 既有約定一致
 *       ——那句話裡可能有日期、出發地、家庭成員。同理不記 IP。</li>
 *   <li><b>回應永遠是 204</b>：不回錯誤內容，避免這個端點被當成探測工具。</li>
 * </ul>
 *
 * <p><b>資料可信度</b>：任何人都能 POST，所以這裡的數字可被灌水。它是診斷線索，不是統計事實
 * ——之後若拿 {@code [ui-crash]} 的筆數做判斷，要記得這件事。
 */
@RestController
public class ClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(ClientErrorController.class);

    /** 控制字元 + Unicode 行分隔符：全部換成空白，杜絕偽造 log 行 */
    private static final Pattern UNSAFE_FOR_LOG = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");

    private static final int MAX_LOGGED_MESSAGE = 300;
    private static final int MAX_LOGGED_FIELD = 120;

    /**
     * @Size 的上限刻意寬於前端的截斷值：前端送來的本來就該是短的，這裡只是防超大 body。
     * 設太緊會讓正常回報被擋成 400 —— 回報失敗等於線索消失，比收到一段稍長的字串糟糕得多。
     */
    record ClientErrorRequest(
            @Size(max = 64) String code,
            @Size(max = 64) String scope,
            @Size(max = 500) String path,
            @Size(max = 2000) String message,
            @Size(max = 1000) String component
    ) {}

    @PostMapping("/api/client-error")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody ClientErrorRequest request, Authentication authentication) {
        // WARN 而非 INFO：它代表使用者真的看到了壞掉的畫面，翻 log 時該跳出來
        log.warn("[ui-crash] code={} scope={} path={} userId={} component={} message={}",
                sanitize(request.code(), MAX_LOGGED_FIELD),
                sanitize(request.scope(), MAX_LOGGED_FIELD),
                sanitize(request.path(), MAX_LOGGED_FIELD),
                currentUserId(authentication),
                sanitize(request.component(), MAX_LOGGED_FIELD),
                sanitize(request.message(), MAX_LOGGED_MESSAGE));
    }

    /** 剝掉會偽造 log 行的字元後截斷；空值統一成 "-"，讓每行欄位數固定、好 grep */
    private static String sanitize(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) return "-";
        String cleaned = UNSAFE_FOR_LOG.matcher(raw).replaceAll(" ").trim();
        if (cleaned.isEmpty()) return "-";
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "…";
    }

    /** 同 ItineraryController：未登入回 null（匿名回報仍然合法，多數回報都會是匿名的） */
    private static Long currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) return id;
        return null;
    }
}
