package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Activity(
        // 格式：H:mm - H:mm 或 HH:mm - HH:mm，允許 en-dash（–）
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}$",
                 message = "time 格式須為 HH:mm - HH:mm（例如 09:00 - 10:30）")
        String time,
        @NotBlank @Size(max = 200) String location,
        @Size(max = 1000)          String description,
        @Size(max = 100)           String transport,
        @Size(max = 50)            String category,
        // optional：AI 或 mock 給的視覺 emoji；前端 fallback 是類別預設 emoji
        @Size(max = 16)            String emoji
) {
    public Activity(String time, String location, String description, String transport, String category) {
        this(time, location, description, transport, category, null);
    }
}
