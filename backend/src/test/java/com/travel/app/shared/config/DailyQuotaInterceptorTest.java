package com.travel.app.shared.config;

import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.InMemoryCounterRows;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DailyQuotaInterceptor} 的行為。計數器用正式的 {@link PersistentCallCounter}、
 * 換日時區用 {@link WebConfig#DAILY_QUOTA_RESET_ZONE}，資料表換成記憶體版（{@link InMemoryCounterRows}）。
 * 真實 SQL 的計數由 {@code PersistentCallCounterTest} 對 H2 驗。
 */
class DailyQuotaInterceptorTest {

    /** 台灣時間 2026-07-27 10:00，離當日午夜還有 14 小時 */
    private static final Instant MORNING = Instant.parse("2026-07-27T02:00:00Z");

    private final InMemoryCounterRows rows = new InMemoryCounterRows();

    private boolean call(DailyQuotaInterceptor interceptor, MockHttpServletResponse response) throws IOException {
        return interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/itinerary/generate"), response, new Object());
    }

    @Test
    void allows_requests_within_daily_quota() throws Exception {
        DailyQuotaInterceptor interceptor = interceptor(3, new MutableClock(MORNING));

        for (int i = 1; i <= 3; i++) {
            assertThat(call(interceptor, new MockHttpServletResponse()))
                    .as("第 %d 次應放行", i)
                    .isTrue();
        }
        assertThat(rows.used(WebConfig.DAILY_QUOTA_COUNTER_NAME, "2026-07-27")).as("記在台灣日期那一列").isEqualTo(3);
    }

    @Test
    void blocks_with_429_and_error_code_once_quota_is_exhausted() throws Exception {
        DailyQuotaInterceptor interceptor = interceptor(3, new MutableClock(MORNING));
        for (int i = 0; i < 3; i++) {
            call(interceptor, new MockHttpServletResponse());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean proceeded = call(interceptor, response);

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString())
                .contains("DAILY_QUOTA_EXCEEDED")
                .contains("今天的行程生成次數已達上限，請明天再試")
                // 帶 retryAfterSeconds 會讓前端啟動秒數倒數，而這裡要等的是「到明天」
                .doesNotContain("retryAfterSeconds");
    }

    @Test
    void quota_is_shared_across_all_clients_regardless_of_ip() throws Exception {
        DailyQuotaInterceptor interceptor = interceptor(2, new MutableClock(MORNING));

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/itinerary/generate");
        first.setRemoteAddr("1.1.1.1");
        first.addHeader("X-Forwarded-For", "9.9.9.9");
        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/itinerary/generate");
        second.setRemoteAddr("2.2.2.2");
        second.addHeader("X-Forwarded-For", "8.8.8.8");
        MockHttpServletRequest third = new MockHttpServletRequest("POST", "/api/itinerary/generate");
        third.setRemoteAddr("3.3.3.3");

        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(second, new MockHttpServletResponse(), new Object())).isTrue();
        // 換 IP、換 X-Forwarded-For 都不會拿到新額度 —— 這正是它擋得住 XFF 偽造的原因
        assertThat(interceptor.preHandle(third, new MockHttpServletResponse(), new Object())).isFalse();
    }

    @Test
    void quota_resets_at_taipei_midnight() throws Exception {
        MutableClock clock = new MutableClock(MORNING);
        DailyQuotaInterceptor interceptor = interceptor(2, clock);

        call(interceptor, new MockHttpServletResponse());
        call(interceptor, new MockHttpServletResponse());
        assertThat(call(interceptor, new MockHttpServletResponse()))
                .as("額度用盡後應被擋")
                .isFalse();

        // 同一天稍晚仍是擋著的（證明不是「消費後 N 小時」的滾動窗）
        clock.advance(Duration.ofHours(13));
        assertThat(call(interceptor, new MockHttpServletResponse()))
                .as("台灣時間 23:00 仍在同一天，應續擋")
                .isFalse();

        // 跨過台灣午夜 → 新的一天
        clock.advance(Duration.ofHours(2));
        assertThat(call(interceptor, new MockHttpServletResponse()))
                .as("跨日後應重置放行")
                .isTrue();
        assertThat(call(interceptor, new MockHttpServletResponse())).isTrue();
        assertThat(call(interceptor, new MockHttpServletResponse()))
                .as("重置後額度仍是 2，不是無限")
                .isFalse();
    }

    @Test
    void a_restarted_service_continues_from_todays_count() throws Exception {
        // 模擬 Render 休眠醒來：新的攔截器與計數器實例、同一份資料表 —— 今天已用的次數接得上
        call(interceptor(2, new MutableClock(MORNING)), new MockHttpServletResponse());
        call(interceptor(2, new MutableClock(MORNING)), new MockHttpServletResponse());

        assertThat(call(interceptor(2, new MutableClock(MORNING)), new MockHttpServletResponse()))
                .as("重啟後仍記得今天已用 2 次")
                .isFalse();
    }

    @Test
    void limit_zero_blocks_every_request() throws Exception {
        // 本機驗收用（DAILY_QUOTA=0）：舊版用 Bucket4j，capacity 必須為正，0 會在啟動時丟例外
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(call(interceptor(0, new MutableClock(MORNING)), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(rows.rowCount()).as("上限 0 不碰資料表").isZero();
    }

    @Test
    void database_unreachable_lets_requests_through() throws Exception {
        ApiCallCounterRepository downRepository = mock(ApiCallCounterRepository.class);
        when(downRepository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
        DailyQuotaInterceptor interceptor = new DailyQuotaInterceptor(new PersistentCallCounter(
                WebConfig.DAILY_QUOTA_COUNTER_NAME, Period.DAILY, 3, WebConfig.DAILY_QUOTA_RESET_ZONE,
                OnDatabaseError.ALLOW, downRepository, new MutableClock(MORNING)));

        assertThat(call(interceptor, new MockHttpServletResponse())).as("資料庫故障不擋生成").isTrue();
    }

    private DailyQuotaInterceptor interceptor(int dailyLimit, Clock clock) {
        return new DailyQuotaInterceptor(new PersistentCallCounter(
                WebConfig.DAILY_QUOTA_COUNTER_NAME, Period.DAILY, dailyLimit, WebConfig.DAILY_QUOTA_RESET_ZONE,
                OnDatabaseError.ALLOW, rows.repository(), clock));
    }

    /** 可推進的測試時鐘 —— 讓「跨日重置」用秒驗完，不必等到隔天。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return WebConfig.DAILY_QUOTA_RESET_ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
