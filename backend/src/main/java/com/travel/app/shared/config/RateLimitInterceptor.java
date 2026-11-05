package com.travel.app.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** IPv6 文字表示的最大長度（含 IPv4-mapped 形式）。超過就不可能是合法位址，當作垃圾丟掉。 */
    private static final int MAX_IP_LENGTH = 45;

    /** 沒有 X-Forwarded-For 時，用來診斷「平台是不是改用別的 header 傳真實來源」。只檢查存在性，不讀值。 */
    private static final List<String> IP_HEADER_CANDIDATES = List.of(
            "X-Real-IP", "CF-Connecting-IP", "True-Client-IP", "Fly-Client-IP", "Forwarded");

    // Caffeine 取代 ConcurrentHashMap：expireAfterAccess 讓閒置 IP 的 Bucket 自動被驅逐，
    // 防止掃描器掃過留下數千個永不清理的 Bucket 吃掉 Heap。
    // 顯式 <String, Bucket> 泛型給 javac 推斷用，否則 .build() 會推成 Cache<Object, Object>。
    private final ConcurrentMap<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .<String, Bucket>build()
            .asMap();
    private final Bandwidth bandwidth;
    private final int trustedProxyHops;
    private final AtomicBoolean shapeLogged = new AtomicBoolean(false);
    private final AtomicBoolean hopsMismatchLogged = new AtomicBoolean(false);

    public RateLimitInterceptor(Bandwidth bandwidth, int trustedProxyHops) {
        this.bandwidth = bandwidth;
        this.trustedProxyHops = trustedProxyHops;
    }

    private Bucket newBucket() {
        return Bucket.builder().addLimit(bandwidth).build();
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        String ip = getClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, k -> newBucket());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }

        // 無條件進位到整秒，讓前端倒數更直觀
        long waitSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(waitSeconds));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                String.format("{\"message\": \"請求太頻繁，請稍後再試\", \"retryAfterSeconds\": %d}", waitSeconds)
        );
        return false;
    }

    /**
     * 解析 client IP —— <b>從右往左</b>數，不是取第一個值。
     *
     * <p>原因：Render（與 AWS / GCP 的 LB 同樣行為）收到請求時，只會把真實來源
     * <b>append</b> 到 X-Forwarded-For 尾端，<b>不會清掉</b>使用者自己塞的內容。
     * 攻擊者送 {@code X-Forwarded-For: 9.9.9.9}，服務端實際看到的是
     * {@code "9.9.9.9, <真實 IP>"}。取第一個值等於直接採信攻擊者寫的那格——
     * 換一個假 IP 就換一個新 bucket，per-IP 限流完全繞得過去。
     * 偽造值只能被推到左邊，可信的那格永遠在右邊。
     *
     * <p>{@code trustedProxyHops} = 從右邊要跳過幾段「基礎設施自己加的」尾段。
     * <b>正式站實測拓樸（2026-07-30）</b>：{@code 使用者 → Cloudflare（橘雲代理）→ Render LB → app}。
     * Cloudflare 把使用者 IP 補進 XFF，Render 再把它看到的對端（= Cloudflare 的 egress IP）
     * append 到尾端，所以 XFF 恆為 2 段、<b>最右邊那格是 Cloudflare egress</b>。正式站因此設 1；
     * 本檔預設 0 是給「前面沒有 proxy」的本機／裸機情境。
     *
     * <p>設太小的後果最痛：取到 Cloudflare egress IP，而 CF 的 egress 池很小，等於所有使用者
     * 塌進同一個 bucket——per-IP 限流退化成全站限流，擋到的是自己人，而且沒有別的機制補救。
     * 設太大只會讓 per-IP 可被繞過，損害上限仍由不看 header 的全站每日額度承擔，
     * 所以不確定時往大的那邊靠。做成設定值是因為跳數取決於拓樸（換 CDN、關掉橘雲都會變），
     * 校準時不必改 code 重新部署。詳見 SPEC §11。
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            // 這條路徑也要記 log：診斷工具在「什麼都沒有」時保持安靜，
            // 會讓「沒看到 log」同時代表「沒部署」和「沒有這個 header」兩件事，等於問不出答案
            logNoXffOnce(request);
            return request.getRemoteAddr();
        }

        String[] hops = xff.split(",");
        logShapeOnce(hops.length);

        int index = hops.length - 1 - trustedProxyHops;
        if (index < 0) {
            // 段數比預期少（例如本機直連卻設了 hops）——寧可退回 remoteAddr，
            // 也不要拿一個位置錯誤的值當 bucket key。但這條路徑會讓 per-IP 退化成
            // 全站限流，不能靜默通過，見下方 logHopsMismatchOnce
            logHopsMismatchOnce(hops.length);
            return request.getRemoteAddr();
        }

        String candidate = hops[index].trim();
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) {
            return request.getRemoteAddr();
        }
        return candidate;
    }

    /**
     * 每個 interceptor 實例只記一次 X-Forwarded-For 的「段數」與當前 trustedProxyHops 設定值。
     *
     * <p><b>不是一次性的校準鷹架，別因為「校準完了」就拆掉</b>——校準只是它的第一個用途。
     * 它長期做兩件事：①印出當前生效的 trustedProxyHops，這是唯一能確認 env var 真的進到
     * 執行中實例的方法（2026-07-30 設定 hops=1 時就是靠它確認）；②偵測拓樸漂移——例如
     * 把 Cloudflare 橘雲關掉，XFF 會變 1 段，而 hops=1 會讓 index 算成 -1、fallback 到
     * remoteAddr，per-IP 限流再次失效；這行印出的「1 段（hops=1）」讓那個狀態看得出來。
     * 成本是每個 process 生命週期最多 3 行（三個 interceptor 實例各一次），不是每個 request。
     *
     * <p>漂移那條路徑另外有 {@link #logHopsMismatchOnce} 直接出 WARN——本行是 INFO，
     * 要人主動去讀才看得到；WARN 才會在翻 log 找別的問題時撞見。兩者不重複：
     * 這行講「現在是什麼形狀」，那行講「形狀跟設定不合、限流已經退化」。
     *
     * <p>刻意只記段數、不記位址值：依 SPEC §11 log 政策，來源位址屬個資，不寫進 INFO log。
     *
     * <p>校準方法：<b>先從 CDN / DNS 設定確認自己前面有幾層 proxy（K），設 hops = K − 1</b>；
     * 這行 log 的段數只用來交叉驗證（段數應等於 K）與確認設定值真的生效。
     *
     * <p><b>段數不能單獨拿來判定</b>：段數 2 可能是「兩層基礎設施」，也可能是
     * 「一層基礎設施 + 客戶端自帶一個偽造值」，這兩種情況要設的值剛好相反。2026-07-30 的
     * 校準是靠「確認 Cloudflare 橘雲開著」才收斂到 hops=1，光看這行 log 收斂不了——
     * 診斷輸出光是「有講話」不夠，要能收斂到唯一結論。
     */
    private void logShapeOnce(int hopCount) {
        if (shapeLogged.compareAndSet(false, true)) {
            log.info("X-Forwarded-For 首次觀測：{} 段（設定生效確認；目前 trusted-proxy-hops={}）",
                    hopCount, trustedProxyHops);
        }
    }

    /**
     * XFF 段數少於 trustedProxyHops 要求時的警告，每個實例只印一次。
     *
     * <p><b>為什麼這條路徑不能靜默</b>：fallback 的 remoteAddr 在 Render 上是內部 router 的
     * 位址，<b>對所有請求都是同一個值</b>——走到這裡等於全站使用者共用一個 bucket，per-IP
     * 限流退化成全站限流。它是設定與拓樸不相符的信號，而不是正常的防呆退路：
     * <ul>
     *   <li>Cloudflare 橘雲被關掉或改成 DNS-only → XFF 從 2 段變 1 段</li>
     *   <li>請求直接打 Render 的 origin 網址繞過 CDN（那個網址不是秘密）</li>
     *   <li>CDN 換掉或平台改了 XFF 行為</li>
     * </ul>
     * 第一項是自己會做的動作，而且做的時候不會想到它會弄壞限流。沒有這行 WARN，
     * 你只會在親友莫名吃到 429 時才發現。
     *
     * <p>用 once-only 而非每次都印：繞過 CDN 直打 origin 的流量會一直走這條路，
     * 每個請求印一行會刷爆 log。這是設定警報，不是流量指標。
     */
    private void logHopsMismatchOnce(int hopCount) {
        if (hopsMismatchLogged.compareAndSet(false, true)) {
            log.warn("X-Forwarded-For 只有 {} 段，但 trusted-proxy-hops={} 要求跳過更多段"
                            + " → 已 fallback 到 remoteAddr。remoteAddr 在 Render 上對所有請求相同，"
                            + "等於全站使用者共用同一個 bucket、per-IP 限流已退化為全站限流。"
                            + "請檢查 TRUSTED_PROXY_HOPS 是否還符合目前拓樸"
                            + "（Cloudflare 橘雲是否被關掉？請求是否繞過 CDN 直打 Render origin？）。詳見 SPEC §11",
                    hopCount, trustedProxyHops);
        }
    }

    /**
     * 沒有 X-Forwarded-For 時的首次觀測。除了說明退回 remoteAddr，
     * 一併列出「還有哪些常見的來源 IP header 存在」——只印 <b>header 名稱</b>不印值，
     * 遵守 SPEC §11 log 政策。若平台改用別的 header 傳真實來源，這行會直接指出來。
     */
    private void logNoXffOnce(HttpServletRequest request) {
        if (!shapeLogged.compareAndSet(false, true)) {
            return;
        }
        String present = IP_HEADER_CANDIDATES.stream()
                .filter(name -> request.getHeader(name) != null)
                .collect(Collectors.joining(", "));
        log.info("X-Forwarded-For 首次觀測：沒有這個 header，改用 remoteAddr 分桶"
                        + "（trusted-proxy-hops={}；其他存在的來源 header：{}）",
                trustedProxyHops, present.isEmpty() ? "無" : present);
    }
}
