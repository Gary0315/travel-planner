package com.travel.app.attraction.repository;

import com.travel.app.attraction.entity.PlacePhotoCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface PlacePhotoCacheRepository extends JpaRepository<PlacePhotoCacheEntity, String> {

    // 清理用：刪除 createdAt 早於指定時間的過期快取，回傳被刪除筆數
    long deleteByCreatedAtBefore(LocalDateTime threshold);
}
