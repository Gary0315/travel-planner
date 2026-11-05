package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DayPlan(
        @Min(1) @Max(30)
        int day,

        // 當日主題，例如「西區散步日」；舊資料可能沒有，允許 null
        @Size(max = 30, message = "theme 不可超過 30 字")
        String theme,

        @NotEmpty
        @Size(max = 50, message = "activities 不可超過 50 筆")
        @Valid
        List<Activity> activities,

        // hotels 允許為 null（最後一天不一定有住宿）
        @Size(max = 50, message = "hotels 不可超過 50 筆")
        @Valid
        List<Hotel> hotels
) {
}
