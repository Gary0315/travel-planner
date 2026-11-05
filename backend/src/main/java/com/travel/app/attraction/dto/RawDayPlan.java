package com.travel.app.attraction.dto;

import java.util.List;

public record RawDayPlan(
        int day,
        String theme,
        List<Activity> activities
) {
}
