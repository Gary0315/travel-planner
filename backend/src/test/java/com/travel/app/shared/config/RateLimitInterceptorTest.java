package com.travel.app.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bucket4j.Bandwidth;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-IP 限流的 client IP 判定測試。
 *
 * <p>核心要守的行為：X-Forwarded-For <b>從右往左</b>數。Render（AWS / GCP 同理）
 * 只把真實來源 append 到尾端、不清掉使用者自己塞的內容，所以最左邊那格是攻擊者
 * 可以任意寫的。取第一個值 = 每送一個假 IP 就換一個新 bucket，限流形同虛設。
 *
 * <p>沒有直接呼叫 getClientIp（private）——改用行為驗證：容量設 1，
 * 看第二個請求有沒有跟第一個共用同一個 bucket，就知道 IP 判成了哪個值。
 */
class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor(int capacity, int trustedProxyHops) {
        return new RateLimitInterceptor(
                Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, Duration.ofMinutes(1))
                        .build(),
                trustedProxyHops);
    }

    private MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/itinerary/generate");
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    private boolean call(RateLimitInterceptor interceptor, MockHttpServletRequest request) throws IOException {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void forged_xff_prefix_cannot_win_a_fresh_bucket() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);

        // 同一個攻擊者換不同偽造前綴，Render 追加的真實來源都是 5.5.5.5
        boolean first = call(interceptor, request("10.0.0.1", "9.9.9.9, 5.5.5.5"));
        boolean second = call(interceptor, request("10.0.0.1", "7.7.7.7, 5.5.5.5"));

        assertThat(first).as("第一次應放行").isTrue();
        // 這行是整個修正的重點：舊實作取第一個值，會把 9.9.9.9 / 7.7.7.7 當成兩個人而放行
        assertThat(second).as("換偽造前綴不該拿到新 bucket").isFalse();
    }

    @Test
    void different_real_clients_still_get_separate_buckets() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);

        boolean first = call(interceptor, request("10.0.0.1", "9.9.9.9, 5.5.5.5"));
        boolean second = call(interceptor, request("10.0.0.1", "9.9.9.9, 6.6.6.6"));

        // 反向保險：別為了防偽造就把所有人塌成同一桶，那會擋到自己人
        assertThat(first).isTrue();
        assertThat(second).as("不同真實來源必須各自有 bucket").isTrue();
    }

    @Test
    void trusted_proxy_hops_skips_platform_internal_hop() throws Exception {
        // 平台在真實來源後面又墊了一跳內部 IP（10.0.0.9）→ 設 hops=1 跳過它
        RateLimitInterceptor interceptor = interceptor(1, 1);

        boolean first = call(interceptor, request("10.0.0.1", "9.9.9.9, 5.5.5.5, 10.0.0.9"));
        boolean second = call(interceptor, request("10.0.0.1", "7.7.7.7, 5.5.5.5, 10.0.0.9"));
        boolean other = call(interceptor, request("10.0.0.1", "7.7.7.7, 6.6.6.6, 10.0.0.9"));

        assertThat(first).isTrue();
        assertThat(second).as("hops=1 時仍應解析到 5.5.5.5").isFalse();
        assertThat(other).as("另一個真實來源不受影響").isTrue();
    }

    @Test
    void falls_back_to_remote_addr_without_xff_header() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);

        boolean first = call(interceptor, request("1.1.1.1", null));
        boolean sameAddr = call(interceptor, request("1.1.1.1", null));
        boolean otherAddr = call(interceptor, request("2.2.2.2", null));

        assertThat(first).isTrue();
        assertThat(sameAddr).as("無 XFF 時以 remoteAddr 分桶").isFalse();
        assertThat(otherAddr).isTrue();
    }

    @Test
    void falls_back_to_remote_addr_when_xff_is_unusable() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);

        // 空白值、以及設了 hops 卻只有一段（段數比預期少）都不該拿位置錯誤的值當 key
        boolean blank = call(interceptor, request("1.1.1.1", "   "));
        boolean blankAgain = call(interceptor, request("1.1.1.1", "  ,  "));

        assertThat(blank).isTrue();
        assertThat(blankAgain).as("空白 XFF 應退回同一個 remoteAddr bucket").isFalse();
    }

    @Test
    void falls_back_to_remote_addr_when_hops_exceed_chain_length() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 2);

        boolean first = call(interceptor, request("1.1.1.1", "5.5.5.5"));
        boolean second = call(interceptor, request("1.1.1.1", "6.6.6.6"));

        assertThat(first).isTrue();
        assertThat(second).as("段數不足時退回 remoteAddr，兩次共用同一桶").isFalse();
    }

    @Test
    void oversized_xff_value_falls_back_to_remote_addr() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);
        String tooLong = "x".repeat(46);

        boolean first = call(interceptor, request("1.1.1.1", tooLong));
        boolean second = call(interceptor, request("1.1.1.1", "y".repeat(200)));

        assertThat(first).isTrue();
        // 超過 IPv6 文字長度上限的值一律丟掉，避免任意長字串被當成 bucket key
        assertThat(second).as("過長的值退回 remoteAddr").isFalse();
    }

    /**
     * 首次觀測 log 一定要印得出來 —— 兩條路徑都要。
     *
     * <p>踩過的坑（2026-07-30）：log 原本寫在「XFF 存在」的分支之後，沒有 header 時會在前面
     * 早退、一個字都不印。結果正式站查不到 log 時，無法分辨是「新版沒部署」還是「平台沒送這個
     * header」——診斷工具在沒東西時保持安靜，就問不出答案。
     *
     * <p>這兩案守的不只是「當初校準能不能做」：那行 log 長期負責印出當前生效的
     * trustedProxyHops（確認 env var 真的進到執行中實例）與偵測拓樸漂移，詳見
     * {@code RateLimitInterceptor#logShapeOnce} 的說明。所以它被刪掉時，這裡要亮紅燈。
     */
    private void captureLogs(Consumer<ListAppender<ILoggingEvent>> body) {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.accept(appender);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logs_first_observation_when_xff_is_present() {
        captureLogs(appender -> {
            RateLimitInterceptor interceptor = interceptor(1, 0);
            try {
                call(interceptor, request("10.0.0.1", "9.9.9.9, 5.5.5.5"));
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("首次觀測：2 段"));
        });
    }

    @Test
    void logs_first_observation_even_when_xff_is_absent() {
        captureLogs(appender -> {
            RateLimitInterceptor interceptor = interceptor(1, 0);
            try {
                call(interceptor, request("10.0.0.1", null));
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            // 沒有 header 也必須留下痕跡，否則「查不到 log」會有兩種解讀
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("沒有這個 header"));
        });
    }

    /**
     * 段數不足時要出聲。這條路徑的 fallback（remoteAddr）在 Render 上對所有請求都是
     * 同一個值，等於 per-IP 限流退化成全站限流——靜默通過的話，只會在親友莫名吃到
     * 429 時才發現。觸發情境見 {@code RateLimitInterceptor#logHopsMismatchOnce}。
     */
    @Test
    void warns_when_hops_exceed_chain_length() {
        captureLogs(appender -> {
            RateLimitInterceptor interceptor = interceptor(5, 1);
            try {
                // hops=1 但只有 1 段：正式站關掉 Cloudflare 橘雲就是這個形狀
                call(interceptor, request("10.0.0.1", "5.5.5.5"));
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("只有 1 段")
                            .contains("退化為全站限流"));
        });
    }

    @Test
    void hops_mismatch_warning_is_logged_only_once() {
        captureLogs(appender -> {
            RateLimitInterceptor interceptor = interceptor(5, 1);
            try {
                for (int i = 0; i < 5; i++) {
                    call(interceptor, request("10.0.0.1", "5.5.5.5"));
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            // 繞過 CDN 直打 origin 的流量會一直走這條路，每個請求印一行會刷爆 log
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .as("設定警報只印一次，不是流量指標")
                    .hasSize(1);
        });
    }

    @Test
    void blocks_with_429_and_retry_after_once_bucket_is_empty() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 0);
        call(interceptor, request("1.1.1.1", null));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceeded = interceptor.preHandle(request("1.1.1.1", null), response, new Object());

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getContentAsString())
                .contains("請求太頻繁，請稍後再試")
                // 前端靠 retryAfterSeconds 啟動秒數倒數（與每日額度的文案分支區分）
                .contains("retryAfterSeconds");
    }
}
