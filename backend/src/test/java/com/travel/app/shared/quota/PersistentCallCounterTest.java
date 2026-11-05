package com.travel.app.shared.quota;

import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PersistentCallCounter} 對真實資料庫（內嵌 H2 in-memory）的計數行為：建列、到上限、換期、時區邊界。
 *
 * <p><b>關掉測試交易</b>：{@code @DataJpaTest} 預設把整個測試包在一個最後會回滾的交易裡，
 * repository 的 {@code @Transactional} 會加入那個交易 —— 和正式環境「每次呼叫各自一個交易」的語意不同。
 * 所以類別上用 {@code NOT_SUPPORTED} 關掉；代價是資料真的會寫進去，每個測試結束要自己清。
 *
 * <p>不在這裡做並發測試：不超發靠的是資料庫列鎖與 PostgreSQL READ COMMITTED 的重新檢查（見 {@link PersistentCallCounter}），
 * H2 的鎖行為與 PostgreSQL 不完全相同，在 H2 上綠燈證明不了正式站。資料庫出錯與插入競態由
 * {@link PersistentCallCounterFailureTest} 用 mock 驗。
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersistentCallCounterTest {

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    /** 太平洋時間 2026-10-14 10:00（PDT，UTC-7） */
    private static final Instant MID_OCTOBER = Instant.parse("2026-10-14T17:00:00Z");

    @Autowired
    private ApiCallCounterRepository repository;

    @AfterEach
    void deleteAllRows() {
        repository.deleteAll();
    }

    @Test
    void first_call_of_the_month_creates_the_row_and_calls_are_allowed_up_to_the_limit() {
        PersistentCallCounter counter = monthlyAt("test.basic", 3, MID_OCTOBER);

        for (int i = 1; i <= 3; i++) {
            assertThat(counter.tryAcquire()).as("第 %d 次應放行", i).isTrue();
        }
        assertThat(counter.tryAcquire()).as("第 4 次超過上限，應被擋").isFalse();

        ApiCallCounterEntity row = row("test.basic", "2026-10");
        // 被擋下的那次沒有發出去，算進來數字就失真了 —— 資料庫的次數必須剛好等於上限
        assertThat(row.getUsedCount()).isEqualTo(3);
        assertThat(row.getUpdatedAt()).as("updated_at 用重置時區的當地時間，與 period_key 對得起來")
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void limit_zero_denies_every_call_and_never_creates_a_row() {
        // 本機驗收用的設定：上限 0 = 全部擋掉，不花任何額度，也不碰資料庫
        PersistentCallCounter counter = monthlyAt("test.zero", 0, MID_OCTOBER);

        assertThat(counter.tryAcquire()).isFalse();
        assertThat(counter.tryAcquire()).isFalse();
        assertThat(repository.count()).as("上限 0 不碰資料庫，連列都不建").isZero();
    }

    @Test
    void a_new_month_starts_from_zero_in_its_own_row() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-31T17:00:00Z")); // 太平洋時間 10/31 10:00
        PersistentCallCounter counter = counter("test.rollover", Period.MONTHLY, 2, LOS_ANGELES, clock);
        counter.tryAcquire();
        counter.tryAcquire();
        assertThat(counter.tryAcquire()).as("10 月的額度已用完").isFalse();

        clock.set(Instant.parse("2026-11-02T18:00:00Z")); // 太平洋時間 11/02 10:00（冬令，UTC-8）
        assertThat(counter.tryAcquire()).as("換月後應重新放行").isTrue();

        assertThat(usedCount("test.rollover", "2026-10")).as("10 月那列維持原樣，不會被覆寫").isEqualTo(2);
        assertThat(usedCount("test.rollover", "2026-11")).isEqualTo(1);
        assertThat(repository.count()).as("兩個月各一列").isEqualTo(2);
    }

    @Test
    void month_boundary_follows_pacific_time_not_utc_or_taipei() {
        // UTC 與台灣都已經是 11/01，但太平洋時間還是 10/31 23:30（夏令，UTC-7）—— Google 的「這個月」還沒結束
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T06:30:00Z"));
        PersistentCallCounter counter = counter("test.boundary", Period.MONTHLY, 5, LOS_ANGELES, clock);
        counter.tryAcquire();

        // 一小時後太平洋時間跨過 11/01 00:00 → 新的月份
        clock.set(Instant.parse("2026-11-01T07:30:00Z"));
        counter.tryAcquire();

        assertThat(usedCount("test.boundary", "2026-10")).as("太平洋時間 10/31 23:30 算 10 月").isEqualTo(1);
        assertThat(usedCount("test.boundary", "2026-11")).as("太平洋時間 11/01 00:30 算 11 月").isEqualTo(1);
    }

    @Test
    void a_daily_counter_keeps_one_row_per_day_keyed_by_the_date() {
        MutableClock clock = new MutableClock(MID_OCTOBER); // 太平洋時間 10/14 10:00
        PersistentCallCounter counter = counter("test.daily", Period.DAILY, 2, LOS_ANGELES, clock);
        counter.tryAcquire();
        counter.tryAcquire();
        assertThat(counter.tryAcquire()).as("當天的額度已用完").isFalse();

        clock.set(Instant.parse("2026-10-15T17:00:00Z")); // 太平洋時間 10/15 10:00
        assertThat(counter.tryAcquire()).as("隔天應重新放行").isTrue();

        assertThat(usedCount("test.daily", "2026-10-14")).as("前一天那列維持原樣").isEqualTo(2);
        assertThat(usedCount("test.daily", "2026-10-15")).isEqualTo(1);
        assertThat(repository.count()).as("兩天各一列").isEqualTo(2);
    }

    @Test
    void day_boundary_follows_each_counters_own_reset_zone() {
        // 同一個瞬間：太平洋時間 10/14 23:30（PDT），台灣已經是 10/15 14:30。
        // Gemini（太平洋時間換日）與全站每日額度（台灣換日）各看各的，兩邊的「今天」不一樣
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T06:30:00Z"), ZoneOffset.UTC);
        counter("test.day-pacific", Period.DAILY, 5, LOS_ANGELES, clock).tryAcquire();
        counter("test.day-taipei", Period.DAILY, 5, TAIPEI, clock).tryAcquire();

        assertThat(usedCount("test.day-pacific", "2026-10-14")).as("太平洋時間還是 10/14").isEqualTo(1);
        assertThat(usedCount("test.day-taipei", "2026-10-15")).as("台灣已經是 10/15").isEqualTo(1);
    }

    @Test
    void counters_with_different_names_do_not_share_counts() {
        PersistentCallCounter hotel = monthlyAt("test.hotel", 1, MID_OCTOBER);
        PersistentCallCounter photo = monthlyAt("test.photo", 1, MID_OCTOBER);

        assertThat(hotel.tryAcquire()).isTrue();
        assertThat(hotel.tryAcquire()).as("hotel 已滿").isFalse();
        assertThat(photo.tryAcquire()).as("hotel 滿了不影響 photo").isTrue();
    }

    @Test
    void a_fresh_instance_continues_from_the_count_in_the_database() {
        // 模擬 Render 休眠後醒來：新的計數器實例、同一個名稱 —— 次數接得上，正是存資料庫的理由
        monthlyAt("test.restart", 2, MID_OCTOBER).tryAcquire();
        monthlyAt("test.restart", 2, MID_OCTOBER).tryAcquire();

        assertThat(monthlyAt("test.restart", 2, MID_OCTOBER).tryAcquire())
                .as("重啟後仍記得本月已用 2 次").isFalse();
    }

    private PersistentCallCounter monthlyAt(String name, int limit, Instant now) {
        return counter(name, Period.MONTHLY, limit, LOS_ANGELES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private PersistentCallCounter counter(String name, Period period, int limit, ZoneId resetZone, Clock clock) {
        return new PersistentCallCounter(name, period, limit, resetZone, OnDatabaseError.DENY, repository, clock);
    }

    private ApiCallCounterEntity row(String name, String period) {
        return repository.findById(new ApiCallCounterId(name, period))
                .orElseThrow(() -> new AssertionError("api_call_counter 沒有 " + name + " / " + period + " 這一列"));
    }

    private int usedCount(String name, String period) {
        return row(name, period).getUsedCount();
    }
}
