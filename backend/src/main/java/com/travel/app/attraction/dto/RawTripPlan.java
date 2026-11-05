package com.travel.app.attraction.dto;

import java.util.List;

/**
 * Gemini 結構化輸出用的內部 DTO。
 * 與對外的 {@link TripPlan} 同形狀，但不包含 hotels，避免 Gemini 嘗試自行產生（會幻覺評分）。
 */
public record RawTripPlan(
        String title,
        String summary,
        List<RawDayPlan> days
) {
}
