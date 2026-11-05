package com.travel.app.shared.config;

import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 守「設定值真的會進到計數器」這條接線。
 *
 * <p>設定鍵打錯時不會有任何錯誤：{@code @Value} 會安靜地吃預設值，所有單元測試照樣全綠，
 * 但正式站設的環境變數完全不生效。這類問題只能靠測試或正式站 log 發現，所以三段都守：
 * yml 的鍵 → {@code @Value} 的鍵 → bean 拿到的值。計數器的名稱、期間、換期時區、
 * 資料庫數不了時擋下還是放行，也一起釘住 —— 這些接反了一樣不會有任何錯誤。
 */
class ApiQuotaConfigTest {

    private static final String KEY = "app.api-quota.gemini-daily-calls";
    private static final String PLACES_PREFIX = "app.api-quota.places-monthly.";

    // 計數器需要 ApiCallCounterRepository。這裡只驗設定接線，給一個 mock 就好
    // （計數器建構時不碰資料庫；上限 0 的案例順便證明連呼叫時也不碰）
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ApiQuotaConfig.class)
            .withBean(ApiCallCounterRepository.class, () -> mock(ApiCallCounterRepository.class));

    @Test
    void gemini_counter_uses_the_configured_daily_limit() {
        contextRunner.withPropertyValues(KEY + "=3").run(context -> {
            PersistentCallCounter counter = context.getBean("geminiCallCounter", PersistentCallCounter.class);
            assertThat(counter.limit()).isEqualTo(3);
        });
    }

    @Test
    void gemini_counter_is_daily_in_pacific_time_and_lets_calls_through_when_the_database_is_down() {
        contextRunner.run(context -> {
            PersistentCallCounter counter = context.getBean("geminiCallCounter", PersistentCallCounter.class);
            // 名稱就是資料表的 counter_name：改名等於換一個新計數器、當天已用的次數歸零
            assertThat(counter.name()).isEqualTo("gemini.itinerary");
            assertThat(counter.period()).isEqualTo(Period.DAILY);
            assertThat(counter.resetZone()).hasToString("America/Los_Angeles");
            // 免費方案的 Gemini 超過額度不收費（SPEC §11.1）；哪天開了帳單，這一行要跟著改成 DENY
            assertThat(counter.onDatabaseError()).isEqualTo(OnDatabaseError.ALLOW);
        });
    }

    @Test
    void gemini_counter_accepts_zero_as_block_everything() {
        // 本機驗收用的設定（GEMINI_DAILY_CALLS=0）：服務必須照常啟動，而不是在建 bean 時丟例外
        contextRunner.withPropertyValues(KEY + "=0").run(context -> {
            assertThat(context).hasNotFailed();
            PersistentCallCounter counter = context.getBean("geminiCallCounter", PersistentCallCounter.class);
            assertThat(counter.tryAcquire()).isFalse();
            verifyNoInteractions(context.getBean(ApiCallCounterRepository.class));
        });
    }

    @Test
    void gemini_counter_falls_back_to_200_when_the_property_is_absent() {
        contextRunner.run(context -> {
            PersistentCallCounter counter = context.getBean("geminiCallCounter", PersistentCallCounter.class);
            assertThat(counter.limit()).isEqualTo(200);
        });
    }

    @Test
    void application_yml_backs_the_same_key_with_the_env_var() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        Object raw = sources.stream()
                .map(source -> source.getProperty(KEY))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        assertThat(raw)
                .as("application.yml 必須有 %s，且由環境變數 GEMINI_DAILY_CALLS 控制", KEY)
                .hasToString("${GEMINI_DAILY_CALLS:200}");
    }

    @Test
    void places_counters_fall_back_to_the_documented_defaults_when_properties_are_absent() {
        contextRunner.run(context -> {
            PlacesQuota quota = context.getBean(PlacesQuota.class);
            assertPlacesCounter(quota, PlacesCallPoint.HOTEL_FIND_PLACE, "places.hotel-find-place", 500);
            assertPlacesCounter(quota, PlacesCallPoint.HOTEL_NEARBY, "places.hotel-nearby", 800);
            assertPlacesCounter(quota, PlacesCallPoint.PHOTO_FIND_PLACE, "places.photo-find-place", 4000);
            assertPlacesCounter(quota, PlacesCallPoint.PHOTO_IMAGE, "places.photo-image", 800);
        });
    }

    @Test
    void places_counters_use_the_configured_monthly_limits() {
        // 四個鍵各給不同的值：任兩個 @Value 的鍵接反、或接到同一個鍵，這裡都會紅燈
        contextRunner.withPropertyValues(
                PLACES_PREFIX + "hotel-find-place=11",
                PLACES_PREFIX + "hotel-nearby=22",
                PLACES_PREFIX + "photo-find-place=33",
                PLACES_PREFIX + "photo-image=44").run(context -> {
            PlacesQuota quota = context.getBean(PlacesQuota.class);
            assertPlacesCounter(quota, PlacesCallPoint.HOTEL_FIND_PLACE, "places.hotel-find-place", 11);
            assertPlacesCounter(quota, PlacesCallPoint.HOTEL_NEARBY, "places.hotel-nearby", 22);
            assertPlacesCounter(quota, PlacesCallPoint.PHOTO_FIND_PLACE, "places.photo-find-place", 33);
            assertPlacesCounter(quota, PlacesCallPoint.PHOTO_IMAGE, "places.photo-image", 44);
        });
    }

    @Test
    void places_counters_accept_zero_as_block_everything() {
        // 本機驗收用的設定（四個 PLACES_MONTHLY_*=0）：服務照常啟動，每個呼叫點都擋下，而且不碰資料庫
        contextRunner.withPropertyValues(
                PLACES_PREFIX + "hotel-find-place=0",
                PLACES_PREFIX + "hotel-nearby=0",
                PLACES_PREFIX + "photo-find-place=0",
                PLACES_PREFIX + "photo-image=0").run(context -> {
            assertThat(context).hasNotFailed();
            PlacesQuota quota = context.getBean(PlacesQuota.class);
            for (PlacesCallPoint point : PlacesCallPoint.values()) {
                assertThat(quota.tryAcquire(point)).as("%s 應被擋下", point).isFalse();
            }
            verifyNoInteractions(context.getBean(ApiCallCounterRepository.class));
        });
    }

    @Test
    void application_yml_backs_the_places_keys_with_env_vars() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        // 預設值必須與 ApiQuotaConfig 的 @Value 預設相同（上面 fall_back 那個測試釘住 @Value 那一側）
        assertYamlValue(sources, PLACES_PREFIX + "hotel-find-place", "${PLACES_MONTHLY_HOTEL_FIND_PLACE:500}");
        assertYamlValue(sources, PLACES_PREFIX + "hotel-nearby", "${PLACES_MONTHLY_HOTEL_NEARBY:800}");
        assertYamlValue(sources, PLACES_PREFIX + "photo-find-place", "${PLACES_MONTHLY_PHOTO_FIND_PLACE:4000}");
        assertYamlValue(sources, PLACES_PREFIX + "photo-image", "${PLACES_MONTHLY_PHOTO_IMAGE:800}");
    }

    private static void assertPlacesCounter(PlacesQuota quota, PlacesCallPoint point,
                                            String expectedName, int expectedLimit) {
        PersistentCallCounter counter = quota.counter(point);
        // 名稱就是資料表的 counter_name：改名等於換一個新計數器、本月已用的次數歸零，所以一起釘住
        assertThat(counter.name()).as("%s 的計數器名稱", point).isEqualTo(expectedName);
        assertThat(counter.limit()).as("%s 的每月上限", point).isEqualTo(expectedLimit);
        assertThat(counter.period()).as("%s 按月計", point).isEqualTo(Period.MONTHLY);
        assertThat(counter.resetZone()).as("%s 以太平洋時間換月", point).hasToString("America/Los_Angeles");
        // Places 超過免費次數直接收費：數不了就擋
        assertThat(counter.onDatabaseError()).as("%s 數不了就擋", point).isEqualTo(OnDatabaseError.DENY);
    }

    private static void assertYamlValue(List<PropertySource<?>> sources, String key, String expected) {
        Object raw = sources.stream()
                .map(source -> source.getProperty(key))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        assertThat(raw)
                .as("application.yml 必須有 %s，且由對應的環境變數控制", key)
                .hasToString(expected);
    }
}
