package com.travel.app.user.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 登入使用者。google_sub 是 Google ID Token 的 sub claim，作為正規 identifier
 * （不用 email：Google 允許使用者改 email，sub 永遠不變）。
 * 詳見 SPEC §19.2。
 */
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_google_sub", columnList = "google_sub", unique = true)
        }
)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_sub", nullable = false, unique = true, length = 64)
    private String googleSub;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "picture_url", length = 1000)
    private String pictureUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at", nullable = false)
    private LocalDateTime lastLoginAt;

    public UserEntity() {}

    public UserEntity(String googleSub, String email, String name, String pictureUrl) {
        this.googleSub = googleSub;
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public Long getId() { return id; }
    public String getGoogleSub() { return googleSub; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPictureUrl() { return pictureUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }

    /**
     * 回流 Google 的最新 profile（每次登入呼叫一次）；同步更新 last_login_at。
     */
    public void refreshFromGoogleProfile(String email, String name, String pictureUrl) {
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
        this.lastLoginAt = LocalDateTime.now();
    }
}
