package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 「我的行程」列表卡片用的 metadata DTO。
 *
 * 不回傳完整 TripPlan —— 列表頁只需要卡片渲染的最小欄位；點進去 detail 才會抓
 * GET /api/itinerary/{shareId} 拿完整內容。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyTripCardResponse(
        String shareId,
        String title,
        String summary,
        int days,
        int places,
        String coverEmoji,
        LocalDateTime savedAt
) {
}
