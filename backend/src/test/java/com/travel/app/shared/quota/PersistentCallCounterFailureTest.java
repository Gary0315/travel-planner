package com.travel.app.shared.quota;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PersistentCallCounter} 用真實資料庫難以重現的路徑 —— repository 換成 Mockito mock 的純單元測試：
 * 資料庫連不上（擋下／放行兩種設定）、兩個請求搶建同一列，以及只能從呼叫紀錄看出來的性質
 * （上限 0 不碰資料庫、到上限的 WARN 每期只印一次）。
 * 正常的計數行為（建列、到上限、換期、時區）由 {@link PersistentCallCounterTest} 對 H2 驗。
 */
class PersistentCallCounterFailureTest {

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    /** 太平洋時間 2026-10-14 10:00（PDT），本月 = 2026-10 */
    private static final Clock MID_OCTOBER = Clock.fixed(Instant.parse("2026-10-14T17:00:00Z"), ZoneOffset.UTC);
    private static final String PERIOD = "2026-10";

    private final ApiCallCounterRepository repository = mock(ApiCallCounterRepository.class);

    @Test
    void database_unreachable_fails_closed_without_throwing() {
        // 資料庫連不上時 Spring 丟的是 CannotCreateTransactionException —— 它不是 DataAccessException，
        // 只接 DataAccessException 的話會一路拋到 controller，旅館或照片請求變成 500
        databaseIsDown();

        assertThat(counter("test.db-down", 10).tryAcquire()).as("數不了就當作已滿").isFalse();
    }

    @Test
    void database_unreachable_lets_the_call_through_when_set_to_allow() {
        // 免費方案的 Gemini 與全站每日額度：超過額度不收費，資料庫故障不該把生成功能一起擋掉
        databaseIsDown();

        assertThat(allowingCounter("test.db-down-allow", 10).tryAcquire()).as("數不了照常放行").isTrue();
    }

    @Test
    void a_database_failure_blocks_only_that_call_not_the_rest_of_the_month() {
        // 數不了只擋這一次：不能因為一次連線失敗就把整個月記成「已滿」，資料庫恢復後要馬上接著數
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))
                .thenReturn(1);
        PersistentCallCounter counter = counter("test.db-recovers", 10);

        assertThat(counter.tryAcquire()).as("資料庫出錯的這次擋下").isFalse();
        assertThat(counter.tryAcquire()).as("資料庫恢復後立刻放行").isTrue();
    }

    @Test
    void allow_mode_still_denies_once_the_period_is_full() {
        // 「放行」只管資料庫數不了的時候；資料庫說滿了，照樣擋
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any())).thenReturn(0);
        when(repository.existsById(any())).thenReturn(true);

        assertThat(allowingCounter("test.allow-full", 10).tryAcquire()).isFalse();
    }

    @Test
    void limit_zero_denies_even_when_set_to_allow() {
        // 上限 0 是驗收手段，不能因為設成「放行」就漏掉
        PersistentCallCounter counter = allowingCounter("test.allow-zero", 0);

        assertThat(counter.tryAcquire()).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void once_the_month_is_confirmed_full_later_calls_skip_the_database_until_the_next_month() {
        // 同一個月內次數只增不減：確認滿了之後，本月剩下的呼叫直接擋，不再讓資料庫白跑；換月才重新問
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T17:00:00Z")); // 太平洋時間 10/14 10:00
        when(repository.incrementIfBelowLimit(eq("test.full-flag"), eq("2026-10"), eq(10), any())).thenReturn(0);
        when(repository.existsById(new ApiCallCounterId("test.full-flag", "2026-10"))).thenReturn(true);
        when(repository.incrementIfBelowLimit(eq("test.full-flag"), eq("2026-11"), eq(10), any())).thenReturn(1);
        PersistentCallCounter counter = new PersistentCallCounter(
                "test.full-flag", Period.MONTHLY, 10, LOS_ANGELES, OnDatabaseError.DENY, repository, clock);

        assertThat(counter.tryAcquire()).as("第一次被擋：問過資料庫才確認已滿").isFalse();
        assertThat(counter.tryAcquire()).as("同一個月再呼叫：直接擋").isFalse();
        assertThat(counter.tryAcquire()).isFalse();
        // 只有確認額滿的那一次查了資料庫（UPDATE → existsById → UPDATE），後面兩次完全沒碰
        verify(repository, times(2)).incrementIfBelowLimit(eq("test.full-flag"), eq("2026-10"), eq(10), any());
        verify(repository, times(1)).existsById(any());

        clock.set(Instant.parse("2026-11-02T18:00:00Z")); // 太平洋時間 11/02 10:00
        assertThat(counter.tryAcquire()).as("換月後重新問資料庫、放行").isTrue();
    }

    @Test
    void losing_the_insert_race_still_counts_this_call() {
        // 兩個請求同時遇到「本月第一次」：兩邊都 UPDATE 不到列、都去 INSERT，後到的撞主鍵。
        // 撞主鍵代表列已經有了 —— 後到的那個要照樣去 UPDATE 加一，而不是被當成失敗擋掉
        when(repository.incrementIfBelowLimit(eq("test.race"), eq(PERIOD), eq(10), any()))
                .thenReturn(0)
                .thenReturn(1);
        when(repository.existsById(new ApiCallCounterId("test.race", PERIOD))).thenReturn(false);
        when(repository.insertZeroRow(eq("test.race"), eq(PERIOD), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThat(counter("test.race", 10).tryAcquire()).isTrue();
        verify(repository, times(2)).incrementIfBelowLimit(eq("test.race"), eq(PERIOD), eq(10), any());
    }

    @Test
    void row_created_by_another_request_right_after_the_first_update_is_not_treated_as_full() {
        // 第一次 UPDATE 時本月的列還不存在；緊接著別的請求把它建好了，所以 existsById 看到「列存在」。
        // 這時並沒有滿 —— 要再 UPDATE 一次把這次記上，而不是錯擋
        when(repository.incrementIfBelowLimit(eq("test.just-created"), eq(PERIOD), eq(10), any()))
                .thenReturn(0)
                .thenReturn(1);
        when(repository.existsById(new ApiCallCounterId("test.just-created", PERIOD))).thenReturn(true);

        assertThat(counter("test.just-created", 10).tryAcquire()).isTrue();
        verify(repository, never()).insertZeroRow(anyString(), anyString(), any());
    }

    @Test
    void existing_row_that_cannot_be_incremented_means_the_month_is_full() {
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any())).thenReturn(0);
        when(repository.existsById(any())).thenReturn(true);

        assertThat(counter("test.full", 10).tryAcquire()).isFalse();
        verify(repository, never()).insertZeroRow(anyString(), anyString(), any());
    }

    @Test
    void limit_zero_never_touches_the_repository() {
        PersistentCallCounter counter = counter("test.zero", 0);

        counter.tryAcquire();
        counter.tryAcquire();

        // 連 SELECT 都沒有：上限 0 時資料庫掛掉也不會多出一行連線錯誤
        verifyNoInteractions(repository);
    }

    @Test
    void limit_reached_warning_is_logged_once_per_month() {
        // 照片請求很密：到上限後若每次被擋都印一行，同一句話會把其他 log 洗掉
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any())).thenReturn(0);
        when(repository.existsById(any())).thenReturn(true);

        List<ILoggingEvent> warnings = warningsWhile(() -> {
            PersistentCallCounter counter = counter("test.warn-once", 10);
            for (int i = 0; i < 5; i++) {
                counter.tryAcquire();
            }
        });

        assertThat(warnings).as("同一個月被擋 5 次，只警告 1 次").hasSize(1);
        // 訊息要講得出是哪個計數器、哪個月、什麼時候恢復
        assertThat(warnings.get(0).getFormattedMessage()).contains("test.warn-once", "本月", PERIOD, "2026-11-01");
    }

    @Test
    void daily_limit_warning_names_the_day_and_when_it_resets() {
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any())).thenReturn(0);
        when(repository.existsById(any())).thenReturn(true);

        List<ILoggingEvent> warnings = warningsWhile(() -> {
            PersistentCallCounter counter = new PersistentCallCounter(
                    "test.warn-daily", Period.DAILY, 10, LOS_ANGELES, OnDatabaseError.ALLOW, repository, MID_OCTOBER);
            counter.tryAcquire();
            counter.tryAcquire();
        });

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("test.warn-daily", "本日", "2026-10-14", "2026-10-15");
    }

    @Test
    void database_error_warning_says_whether_the_call_was_let_through() {
        // 看 Render log 時要分得出「這次被擋了」還是「這次沒數到但放行了」
        databaseIsDown();

        List<ILoggingEvent> warnings = warningsWhile(() -> {
            counter("test.warn-deny", 10).tryAcquire();
            allowingCounter("test.warn-allow", 10).tryAcquire();
        });

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).getFormattedMessage()).contains("test.warn-deny", "不發出呼叫");
        assertThat(warnings.get(1).getFormattedMessage()).contains("test.warn-allow", "照常放行");
    }

    private void databaseIsDown() {
        when(repository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
    }

    private PersistentCallCounter counter(String name, int monthlyLimit) {
        return new PersistentCallCounter(
                name, Period.MONTHLY, monthlyLimit, LOS_ANGELES, OnDatabaseError.DENY, repository, MID_OCTOBER);
    }

    private PersistentCallCounter allowingCounter(String name, int monthlyLimit) {
        return new PersistentCallCounter(
                name, Period.MONTHLY, monthlyLimit, LOS_ANGELES, OnDatabaseError.ALLOW, repository, MID_OCTOBER);
    }

    private static List<ILoggingEvent> warningsWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PersistentCallCounter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
    }
}
