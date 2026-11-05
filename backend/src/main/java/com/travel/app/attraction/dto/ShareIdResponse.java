package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * /api/itinerary/save 的回應。
 * <p>
 * deleteToken 是明文 token，僅在此次 save 回應中露面一次；前端應存進 localStorage，
 * 之後刪除分享連結時透過 X-Delete-Token header 提交給後端驗證（伺服器只存 SHA-256 hash）。
 * 舊資料或不支援刪除的情境會回 null（{@link com.fasterxml.jackson.annotation.JsonInclude} 略過）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareIdResponse(String shareId, String deleteToken) {

    public ShareIdResponse(String shareId) {
        this(shareId, null);
    }
}
