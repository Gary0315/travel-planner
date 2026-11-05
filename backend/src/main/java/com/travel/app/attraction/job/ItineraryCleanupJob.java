package com.travel.app.attraction.job;

import com.travel.app.attraction.repository.ItineraryRepository;
import com.travel.app.attraction.repository.PlacePhotoCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定期清理過期資料：
 *  - itinerary：超過 retention-days 未被存取的紀錄
 *  - place_photo_cache：超過 place-photo-cache.retention-days 的快取
 *
 * 兩段清理刻意拆成各自獨立的 @Transactional method，
 * 避免 photo cache 清理失敗時連帶把已成功的 itinerary 清理 rollback。
 * 透過 @Lazy self-injection 讓 cleanup() 對 self 的呼叫經過 Spring AOP proxy，
 * 否則同 bean 內直接呼叫會繞過 @Transactional。
 */
@Component
public class ItineraryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ItineraryCleanupJob.class);

    private final ItineraryRepository repository;
    private final PlacePhotoCacheRepository photoCacheRepository;
    private final ItineraryCleanupJob self;
    private final long itineraryRetentionDays;
    private final long photoCacheRetentionDays;

    public ItineraryCleanupJob(
            ItineraryRepository repository,
            PlacePhotoCacheRepository photoCacheRepository,
            @Lazy ItineraryCleanupJob self,
            @Value("${app.itinerary.retention-days:90}") long itineraryRetentionDays,
            @Value("${app.place-photo-cache.retention-days:30}") long photoCacheRetentionDays) {
        this.repository = repository;
        this.photoCacheRepository = photoCacheRepository;
        this.self = self;
        this.itineraryRetentionDays = itineraryRetentionDays;
        this.photoCacheRetentionDays = photoCacheRetentionDays;
    }

    @Scheduled(cron = "${app.itinerary.cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        try {
            self.cleanupItineraries();
        } catch (Exception e) {
            log.error("Itinerary cleanup failed", e);
        }
        try {
            self.cleanupPhotoCache();
        } catch (Exception e) {
            log.error("Photo cache cleanup failed", e);
        }
    }

    @Transactional
    public void cleanupItineraries() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(itineraryRetentionDays);
        long deleted = repository.deleteByLastAccessedAtBefore(threshold);
        if (deleted > 0) {
            log.info("Itinerary cleanup: deleted {} record(s)", deleted);
        }
    }

    @Transactional
    public void cleanupPhotoCache() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(photoCacheRetentionDays);
        long deleted = photoCacheRepository.deleteByCreatedAtBefore(threshold);
        if (deleted > 0) {
            log.info("Photo cache cleanup: deleted {} record(s)", deleted);
        }
    }
}
