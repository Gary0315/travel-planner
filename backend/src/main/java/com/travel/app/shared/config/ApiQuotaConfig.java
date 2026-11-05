package com.travel.app.shared.config;

import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/**
 * 對外 API 的呼叫上限，計數都存資料庫（{@link PersistentCallCounter}）——
 * Gemini 每日一個；Places 四個呼叫點每月各一個，包成一個 {@link PlacesQuota}。
 *
 * <p>與 {@link WebConfig} 裡的 {@code DailyQuotaInterceptor} 是兩層不同的東西：
 * 那邊在入口數「進來的請求」，這裡的計數器放在 service 層發出呼叫的地方，數「打出去的呼叫」。
 */
@Configuration
public class ApiQuotaConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiQuotaConfig.class);

    /** Gemini 每日計數在資料表的 counter_name。改名等於換一個新計數器，當天已用的次數不會跟過來。 */
    static final String GEMINI_COUNTER_NAME = "gemini.itinerary";

    /**
     * Gemini 計數器以哪個時區的午夜換日。
     *
     * <p>這個計數器守的是 Google 那邊的每日額度，所以「一天」要對齊 Google 的額度窗口，
     * 而不是使用者所在的台灣時間：兩邊的窗口若錯開，Google 的一天會橫跨我們的兩天，
     * 最壞情況那一天內會放行接近兩倍上限的呼叫。
     *
     * <p>依據（2026-10-07 查閱 https://ai.google.dev/gemini-api/docs/rate-limits ）：
     * "Requests per day (RPD) quotas reset at midnight Pacific time."
     * 官方沒寫是否隨日光節約時間調整；用 {@code America/Los_Angeles}（會隨 DST 變動）是推論。
     * 換算成台灣時間是每天 15:00（夏令）或 16:00（冬令）歸零。
     */
    static final ZoneId GEMINI_QUOTA_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    /**
     * Places 計數器以哪個時區換月。
     *
     * <p>Places 的每月免費次數在每月 1 日太平洋時間午夜重置（SPEC §11.2，依據原文
     * "This free usage resets on the first day of each month, at midnight Pacific US time."）。
     * 值與 {@link #GEMINI_QUOTA_RESET_ZONE} 相同，但守的是另一份額度、出處也不同，刻意不共用常數 ——
     * 哪天其中一邊的規則變了，改它不會把另一邊一起改錯。
     */
    static final ZoneId PLACES_QUOTA_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    /**
     * Gemini 每日呼叫上限。資料庫數不了時<b>放行</b>：本專案的 Gemini key 用免費方案（2026-10-19 使用者確認），
     * 超過額度 Google 只會拒絕、不會收費，不值得讓資料庫故障把整個生成功能一起擋掉。
     * <b>哪天 Gemini key 所在的專案開了帳單，這裡要改成 {@link OnDatabaseError#DENY}</b>（SPEC §11.1）。
     */
    @Bean
    public PersistentCallCounter geminiCallCounter(
            @Value("${app.api-quota.gemini-daily-calls:200}") int dailyLimit,
            ApiCallCounterRepository repository) {
        // 啟動時印出實際生效的值：環境變數有沒有被吃到，不必等到上限真的被打到才知道
        log.info("Gemini 每日呼叫上限 gemini-daily-calls={}（{} 午夜換日）", dailyLimit, GEMINI_QUOTA_RESET_ZONE);
        return new PersistentCallCounter(GEMINI_COUNTER_NAME, Period.DAILY, dailyLimit, GEMINI_QUOTA_RESET_ZONE,
                OnDatabaseError.ALLOW, repository);
    }

    /**
     * Places 四個呼叫點的每月上限。{@code @Value} 的預設值必須與 application.yml 相同
     * （{@code ApiQuotaConfigTest} 守著），數字由來寫在 yml 的註解。
     * 資料庫數不了時<b>擋下</b>：Places 超過免費次數直接收費（SPEC §11.2）。
     */
    @Bean
    public PlacesQuota placesQuota(
            @Value("${app.api-quota.places-monthly.hotel-find-place:500}") int hotelFindPlace,
            @Value("${app.api-quota.places-monthly.hotel-nearby:800}") int hotelNearby,
            @Value("${app.api-quota.places-monthly.photo-find-place:4000}") int photoFindPlace,
            @Value("${app.api-quota.places-monthly.photo-image:800}") int photoImage,
            ApiCallCounterRepository repository) {
        // 與 Gemini 那行同一個目的：四個環境變數有沒有被吃到，啟動 log 就看得到
        log.info("Places 每月呼叫上限 hotel-find-place={} hotel-nearby={} photo-find-place={} photo-image={}"
                        + "（{} 每月 1 日午夜換月）",
                hotelFindPlace, hotelNearby, photoFindPlace, photoImage, PLACES_QUOTA_RESET_ZONE);

        Map<PlacesCallPoint, Integer> limits = new EnumMap<>(PlacesCallPoint.class);
        limits.put(PlacesCallPoint.HOTEL_FIND_PLACE, hotelFindPlace);
        limits.put(PlacesCallPoint.HOTEL_NEARBY, hotelNearby);
        limits.put(PlacesCallPoint.PHOTO_FIND_PLACE, photoFindPlace);
        limits.put(PlacesCallPoint.PHOTO_IMAGE, photoImage);

        Map<PlacesCallPoint, PersistentCallCounter> counters = new EnumMap<>(PlacesCallPoint.class);
        limits.forEach((point, limit) -> counters.put(point, new PersistentCallCounter(
                point.counterName(), Period.MONTHLY, limit, PLACES_QUOTA_RESET_ZONE, OnDatabaseError.DENY, repository)));
        return new PlacesQuota(counters);
    }
}
