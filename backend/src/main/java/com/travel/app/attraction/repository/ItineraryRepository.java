package com.travel.app.attraction.repository;

import com.travel.app.attraction.entity.ItineraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<ItineraryEntity, Long> {
    Optional<ItineraryEntity> findByShareId(String shareId);

    // 排程清理用：刪掉 lastAccessedAt 早於指定時間的所有紀錄，回傳被刪除的筆數
    long deleteByLastAccessedAtBefore(LocalDateTime threshold);

    // 「我的行程」列表查詢：給定 userId，回最新儲存的在前
    List<ItineraryEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
