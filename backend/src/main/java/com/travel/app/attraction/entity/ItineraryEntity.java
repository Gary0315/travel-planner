package com.travel.app.attraction.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "itinerary",
        indexes = {
                // 「我的行程」列表查詢用：WHERE user_id = ? ORDER BY created_at DESC
                @Index(name = "idx_itinerary_user_id_created_at", columnList = "user_id, created_at")
        }
)
public class ItineraryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String shareId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 最近一次被讀取的時間 —— 用來支援「N 天未存取就清除」的排程
    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;

    // SHA-256(deleteToken)：建立 share 時隨機產一組明文 token 給使用者保存，
    // DB 只存 hash —— 即使 DB 外洩，攻擊者也無法用既有 token 刪別人的行程。
    // nullable 為了相容尚未升級此欄位的舊資料（legacy row 視為不可刪除）。
    @Column(name = "delete_token_hash", length = 64)
    private String deleteTokenHash;

    // 擁有者使用者 id（nullable —— 匿名儲存的 trip 不綁人）。
    // 登入後儲存 → ItineraryController.save 從 SecurityContext 取出，
    // 給 /api/users/me/trips 端點作 owner 過濾。
    @Column(name = "user_id")
    private Long userId;

    public ItineraryEntity() {}

    public ItineraryEntity(String shareId, String content) {
        this(shareId, content, null, null);
    }

    public ItineraryEntity(String shareId, String content, String deleteTokenHash) {
        this(shareId, content, deleteTokenHash, null);
    }

    public ItineraryEntity(String shareId, String content, String deleteTokenHash, Long userId) {
        this.shareId = shareId;
        this.content = content;
        this.deleteTokenHash = deleteTokenHash;
        this.userId = userId;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.lastAccessedAt = now;
    }

    public Long getId() { return id; }
    public String getShareId() { return shareId; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public String getDeleteTokenHash() { return deleteTokenHash; }
    public Long getUserId() { return userId; }

    public void touchAccessTime() {
        this.lastAccessedAt = LocalDateTime.now();
    }
}
