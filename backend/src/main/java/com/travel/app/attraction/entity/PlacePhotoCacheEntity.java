package com.travel.app.attraction.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "place_photo_cache")
public class PlacePhotoCacheEntity {

    @Id
    @Column(length = 255)
    private String name;

    // 只快取 photo_reference，不存解析後的 CDN URL（後者帶簽章、幾天就失效）
    @Column(name = "photo_reference", columnDefinition = "TEXT", nullable = false)
    private String photoReference;

    @Column(length = 255)
    private String placeId;

    // Google Places ToS 要求 user-contributed 圖片必須清楚標示原作者；舊資料可能為 null
    @Column(length = 500)
    private String attribution;

    @Column(name = "attribution_url", length = 1000)
    private String attributionUrl;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public PlacePhotoCacheEntity() {}

    public PlacePhotoCacheEntity(String name, String photoReference, String placeId,
                                  String attribution, String attributionUrl) {
        this.name = name;
        this.photoReference = photoReference;
        this.placeId = placeId;
        this.attribution = attribution;
        this.attributionUrl = attributionUrl;
        this.createdAt = LocalDateTime.now();
    }

    public String getName() { return name; }
    public String getPhotoReference() { return photoReference; }
    public String getPlaceId() { return placeId; }
    public String getAttribution() { return attribution; }
    public String getAttributionUrl() { return attributionUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
