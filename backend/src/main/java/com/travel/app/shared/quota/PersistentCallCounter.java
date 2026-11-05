package com.travel.app.shared.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 呼叫次數上限，計數存在資料庫 —— 一個實例 = 一個計數器名稱 + 一種期間（每日／每月）+ 一個上限。
 * 放在發出呼叫（或放行請求）的那一行前面，數的是實際打出去的次數。
 *
 * <p><b>為什麼存資料庫</b>：正式站在 Render 免費方案，閒置 15 分鐘就休眠、醒來記憶體歸零。
 * 記憶體計數每醒一次就重新從 0 數，「每日上限」實際變成「每個清醒週期的上限」，按月的更撐不了一個月。
 *
 * <p><b>期間界線看 {@code resetZone}</b>：要對齊對方額度重置的時區，不是使用者所在的時區
 * （Google 的 Gemini 每日額度、Places 每月免費次數都在太平洋時間午夜重置，SPEC §11.1、§11.2）。
 *
 * <p><b>為什麼用一句 UPDATE 同時判斷並加一</b>：若寫成「先 SELECT 讀出次數 → 在 Java 裡比上限 → 再寫回」，
 * 兩個請求同時進來會讀到同一個數字、兩邊都以為還有額度而放行。改成
 * {@code UPDATE ... SET used = used + 1 WHERE used < 上限} 一句話，比較和加一在資料庫裡一次完成：
 * 同一列的兩個 UPDATE 由列鎖排隊，PostgreSQL（READ COMMITTED）下第二個拿到鎖後，會用前一個寫入的新值
 * 重新檢查 WHERE，所以放行次數不會超過上限。多個 JVM 同時跑也一樣成立，本類別因此不需要 synchronized。
 *
 * <p><b>資料庫數不了的時候</b>（連不上、或任何非預期的例外）由 {@link OnDatabaseError} 決定：
 * 超過額度會直接收費的（Places）擋下，寧可這段時間少顯示旅館與照片，也不要在數不了的情況下照打；
 * 超過額度只會被對方拒絕、不收費的（免費方案的 Gemini）放行，不讓資料庫故障把整個生成功能一起拖垮。
 */
public class PersistentCallCounter {

    private static final Logger log = LoggerFactory.getLogger(PersistentCallCounter.class);

    /** 計數的期間：決定 period_key 的格式，以及下一期從哪天開始。 */
    public enum Period {

        /** 一天一列，period_key 例：{@code 2026-10-19} */
        DAILY("本日", "同一天") {
            @Override
            String key(LocalDate date) {
                return date.toString();
            }

            @Override
            LocalDate nextStart(LocalDate date) {
                return date.plusDays(1);
            }
        },

        /** 一個月一列，period_key 例：{@code 2026-10} */
        MONTHLY("本月", "同一個月") {
            @Override
            String key(LocalDate date) {
                return YearMonth.from(date).toString();
            }

            @Override
            LocalDate nextStart(LocalDate date) {
                return YearMonth.from(date).plusMonths(1).atDay(1);
            }
        };

        // 只給 log 用的字樣
        private final String current;
        private final String same;

        Period(String current, String same) {
            this.current = current;
            this.same = same;
        }

        /** {@code date}（resetZone 的當地日期）所在期間的 period_key。字典序就是時間順序，人工查表也看得懂。 */
        abstract String key(LocalDate date);

        /** {@code date} 所在期間的下一期第一天。 */
        abstract LocalDate nextStart(LocalDate date);
    }

    /** 資料庫數不了的那一次要怎麼辦。 */
    public enum OnDatabaseError {

        /** 擋下（fail-closed）：超過額度就直接收費的 API 用這個，例如 Places。 */
        DENY,

        /** 放行、不計數（fail-open）：超過額度只會被對方拒絕、不會收費的 API 用這個，例如免費方案的 Gemini。 */
        ALLOW
    }

    private final String name;
    private final Period period;
    private final int limit;
    private final ZoneId resetZone;
    private final OnDatabaseError onDatabaseError;
    private final ApiCallCounterRepository repository;
    private final Clock clock;

    // 已確認額滿的期間（period_key）。同一期內次數只增不減（沒有任何程式會減），確認滿了之後，這一期剩下的呼叫直接擋、不再問資料庫 ——
    // 到上限後照片請求仍很密，每次都查只是讓資料庫白跑。同一個記號也讓「已達上限」的 WARN 每期只印一次。
    // 期中要放寬上限：改環境變數讓服務重啟即可，新實例沒有這個記號，會重新問資料庫。
    private final AtomicReference<String> exhaustedPeriod = new AtomicReference<>();

    /**
     * @param name            計數器名稱，也就是資料表的 counter_name（例如 {@code places.hotel-nearby}）
     * @param period          每日或每月
     * @param limit           每期上限；0 表示全部拒絕，而且完全不碰資料庫
     * @param resetZone       以哪個時區的午夜換期 —— 要對齊「對方」額度的重置時區
     * @param onDatabaseError 資料庫數不了的那一次要擋下還是放行
     */
    public PersistentCallCounter(String name, Period period, int limit, ZoneId resetZone,
                                 OnDatabaseError onDatabaseError, ApiCallCounterRepository repository) {
        this(name, period, limit, resetZone, onDatabaseError, repository, Clock.systemUTC());
    }

    // 測試用：注入可控時鐘，才驗得了換期與時區邊界。
    // 換期只看 clock 的 instant 換算到 resetZone 的日期，不依賴 clock 自身的時區設定。
    public PersistentCallCounter(String name, Period period, int limit, ZoneId resetZone,
                                 OnDatabaseError onDatabaseError, ApiCallCounterRepository repository, Clock clock) {
        if (limit < 0) {
            throw new IllegalArgumentException("上限不可為負數：" + name + "=" + limit);
        }
        this.name = Objects.requireNonNull(name, "name");
        this.period = Objects.requireNonNull(period, "period");
        this.limit = limit;
        this.resetZone = Objects.requireNonNull(resetZone, "resetZone");
        this.onDatabaseError = Objects.requireNonNull(onDatabaseError, "onDatabaseError");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 要發出一次呼叫之前先呼叫本方法。回傳 true 表示可以發出（正常情況下已記上一次）；
     * false 表示這一期的上限已滿（或資料庫數不了且設為 {@link OnDatabaseError#DENY}），呼叫端不應發出。
     * 被拒絕的嘗試不記數。
     */
    public boolean tryAcquire() {
        ZonedDateTime now = clock.instant().atZone(resetZone);
        LocalDate today = now.toLocalDate();
        String periodKey = period.key(today);

        if (limit <= 0) {
            // 「上限設 0」是驗收手段：保證一次都不發出，也不在資料表留下任何一列
            markExhausted(periodKey, today);
            return false;
        }
        if (periodKey.equals(exhaustedPeriod.get())) {
            return false;
        }

        try {
            if (acquireInDatabase(periodKey, now.toLocalDateTime())) {
                return true;
            }
            markExhausted(periodKey, today);
            return false;
        } catch (RuntimeException e) {
            // 接 RuntimeException 而不是 DataAccessException：資料庫連不上時 Spring 丟的是
            // CannotCreateTransactionException，它不屬於 DataAccessException。
            // 這裡刻意不記「已滿」：數不了只影響這一次，資料庫恢復後要能馬上接著數。
            // 只記例外類別名 —— 這裡要回答的是「數不了」，不是重現 SQL。
            boolean allow = onDatabaseError == OnDatabaseError.ALLOW;
            log.warn("呼叫計數器 {} 無法存取資料庫（{}），本次{}",
                    name, e.getClass().getSimpleName(), allow ? "照常放行、不計數" : "視同已達上限、不發出呼叫");
            return allow;
        }
    }

    public String name() {
        return name;
    }

    public Period period() {
        return period;
    }

    public int limit() {
        return limit;
    }

    public ZoneId resetZone() {
        return resetZone;
    }

    public OnDatabaseError onDatabaseError() {
        return onDatabaseError;
    }

    private boolean acquireInDatabase(String periodKey, LocalDateTime now) {
        if (repository.incrementIfBelowLimit(name, periodKey, limit, now) == 1) {
            return true;
        }
        // UPDATE 沒改到任何一列只有兩種原因：這一期的列還不存在，或已達上限
        if (!repository.existsById(new ApiCallCounterId(name, periodKey))) {
            // 這一期第一次：先建一列 0
            try {
                repository.insertZeroRow(name, periodKey, now);
            } catch (DataIntegrityViolationException rowAlreadyCreated) {
                // 撞主鍵 = 別的請求（或別的實例）同時先建好了這一列，結果一樣，沿用它即可。
                // Hibernate 會在 log 印一行 SQL 錯誤，這是預期內的：每期最多發生在第一次呼叫撞在一起的那一瞬間。
            }
        }
        // 不論列是剛建的還是原本就在，都再走同一句 UPDATE —— 「比上限再加一」永遠只有那一句 SQL 這一條路。
        // 列原本就在也要再試一次：第一次 UPDATE 與 existsById 之間，可能剛好有別的請求建好了這一期的第一列，
        // 這時並沒有滿，直接回 false 會錯擋一次。真的滿了，這句照樣回 0（只有確認額滿的那一次多這一句，之後由 exhaustedPeriod 直接擋）。
        return repository.incrementIfBelowLimit(name, periodKey, limit, now) == 1;
    }

    private void markExhausted(String periodKey, LocalDate today) {
        if (!periodKey.equals(exhaustedPeriod.getAndSet(periodKey))) {
            log.warn("呼叫計數器 {} {}已達上限（{}，上限 {} 次），{} 00:00（{}）前不再發出呼叫；{}只提示這一次",
                    name, period.current, periodKey, limit, period.nextStart(today), resetZone, period.same);
        }
    }
}
