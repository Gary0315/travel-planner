package com.travel.app.shared.config;

import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import io.github.bucket4j.Bandwidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.time.ZoneId;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /** 全站每日額度在資料表的 counter_name。改名等於換一個新計數器，當天已用的次數不會跟過來。 */
    static final String DAILY_QUOTA_COUNTER_NAME = "site.generate-requests";

    /**
     * 全站每日額度以台灣午夜換日 —— 與 Gemini 計數器（太平洋時間）刻意不同。
     * 這個額度是給人看的：對親友說「明天再試」就真的是明天，驗收也好對時間。
     */
    static final ZoneId DAILY_QUOTA_RESET_ZONE = ZoneId.of("Asia/Taipei");

    private final ApiCallCounterRepository counterRepository;

    public WebConfig(ApiCallCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    // 全站每日生成上限（不分 IP）。本機驗收時用環境變數改小，不必動 code
    @Value("${app.rate-limit.daily-quota:100}")
    private int dailyQuota;

    // client IP 判定要從 X-Forwarded-For 右邊跳過幾段基礎設施跳點（理由見 RateLimitInterceptor）。
    // 這裡的預設 0 = 前面沒有 proxy；正式站經 Cloudflare + Render 兩層，由 env 設 1
    @Value("${app.rate-limit.trusted-proxy-hops:0}")
    private int trustedProxyHops;

    // 重型端點：Gemini 計費，1 分鐘 5 次（burst 5，之後每分鐘補 1）
    @Bean
    public RateLimitInterceptor generateRateLimiter() {
        return new RateLimitInterceptor(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(1, Duration.ofMinutes(1))
                .build(), trustedProxyHops);
    }

    // 儲存端點：防止垃圾資料大量寫入 DB，1 分鐘 5 次
    @Bean
    public RateLimitInterceptor saveRateLimiter() {
        return new RateLimitInterceptor(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(1))
                .build(), trustedProxyHops);
    }

    // 全站每日額度：只掛 generate —— 它是 Gemini 計費與 Places enrich 的唯一入口。
    // 不掛全站是刻意的：照片查詢有多層快取、/trips 只查 DB，把它們算進同一個額度
    // 會在親友正常使用時就把桶用光，擋到的是自己人而不是濫用者。
    // 資料庫數不了時放行：這一層主要守 Gemini（免費方案，超過不收費），Places 另有自己的每月計數、數不了就擋，
    // 這裡再擋只會讓資料庫故障變成整個生成功能停擺（SPEC §11.1）。
    @Bean
    public DailyQuotaInterceptor dailyQuotaInterceptor() {
        log.info("全站每日額度 daily-quota={}（{} 午夜換日）", dailyQuota, DAILY_QUOTA_RESET_ZONE);
        return new DailyQuotaInterceptor(new PersistentCallCounter(DAILY_QUOTA_COUNTER_NAME, Period.DAILY,
                dailyQuota, DAILY_QUOTA_RESET_ZONE, OnDatabaseError.ALLOW, counterRepository));
    }

    // 前端崩潰回報：本專案唯一不需登入的公開寫入端點，比一般端點更嚴（1 分鐘 10 次）。
    // 一次崩潰只送一筆，正常使用永遠打不到這個上限；擋的是把它當 log 灌爆管道的人。
    @Bean
    public RateLimitInterceptor clientErrorRateLimiter() {
        return new RateLimitInterceptor(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build(), trustedProxyHops);
    }

    // 一般端點：Maps / DB 查詢，1 分鐘 40 次
    @Bean
    public RateLimitInterceptor generalRateLimiter() {
        return new RateLimitInterceptor(Bandwidth.builder()
                .capacity(40)
                .refillIntervally(40, Duration.ofMinutes(1))
                .build(), trustedProxyHops);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(generateRateLimiter())
                .addPathPatterns("/api/itinerary/generate");

        // 順序有意義：per-IP 先擋。同一個人狂點被 per-IP 擋下的請求不該吃掉每日額度，
        // 否則一個人就能用「每分鐘被擋 N 次」把全站額度洗光。
        registry.addInterceptor(dailyQuotaInterceptor())
                .addPathPatterns("/api/itinerary/generate");

        registry.addInterceptor(saveRateLimiter())
                .addPathPatterns("/api/itinerary/save");

        registry.addInterceptor(clientErrorRateLimiter())
                .addPathPatterns("/api/client-error");

        // /api/health 不限流：喚醒輪詢會持續打它，不可與照片等一般請求搶同一個 per-IP bucket
        registry.addInterceptor(generalRateLimiter())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/itinerary/generate", "/api/itinerary/save",
                        "/api/client-error", "/api/health");
    }
}
