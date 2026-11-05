package com.travel.app.shared.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine cache 設定，取代 Spring 預設的 ConcurrentMapCache（無 size limit、無 TTL）。
 * 每個 cache 依資料量分別設上限，避免 JVM Heap 無限成長。
 *
 * 各 cache 說明：
 *  - photoBytes  : byte[] 圖片，上限 300 筆。key 是「地點名@寬度」，同一地點的不同尺寸各佔一筆。
 *                  600px 每張約 100–600 KB（上限估算 ~180 MB 即以此為準）；
 *                  HotelMini 用的 200px 小得多，因此實際占用通常遠低於估算值
 *  - places      : PlacePhotoResponse（URL + 幾個字串），輕量，上限 1000 筆
 *  - hotelsNearby: List<Hotel>（每筆約數 KB），上限 500 筆
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache("photoBytes",
                Caffeine.newBuilder()
                        .maximumSize(300)
                        .expireAfterWrite(Duration.ofHours(1))
                        .build());

        manager.registerCustomCache("places",
                Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(Duration.ofHours(1))
                        .build());

        manager.registerCustomCache("hotelsNearby",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofHours(1))
                        .build());

        return manager;
    }
}
