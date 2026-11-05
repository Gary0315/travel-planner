package com.travel.app.attraction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

// 對外接收 /save 的 payload —— 加 size 上限避免使用者塞超大 JSON 灌爆 DB
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripPlan(
        @NotBlank
        @Size(max = 200, message = "title 長度不可超過 200")
        String title,

        // optional：行程整體簡介（1-2 句、≤ 80 字，由 Gemini 生成），舊資料可能為 null
        @Size(max = 500, message = "summary 長度不可超過 500")
        String summary,

        @NotEmpty
        @Size(max = 30, message = "days 不可超過 30 筆")
        @Valid
        List<DayPlan> days,

        // 本趟行程的旅館查詢整段沒查成（Places 額度耗盡或授權失敗）。前端據此顯示一句提示，
        // 而不是讓住宿區無聲消失 ——「查不到」跟「附近沒有」在畫面上長得一模一樣，
        // 使用者分不出來，看 log 的人也分不出來（2026-08-24 查證）。
        //
        // 放 TripPlan 而非 DayPlan：額度是全站每日的，一爆就是整趟每天都查不到，
        // per-day 標記是過度設計。
        //
        // READ_ONLY = 只出不進：/generate 回應帶得出去，/save 收到一律忽略。
        // 配上 record 上的 @JsonInclude(NON_NULL)，值為 null 時整個 key 不會出現在
        // 寫進 DB 的 JSON 裡 —— 存起來的行程之後打開分享頁，不該還掛著一句過期的提示。
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        Boolean hotelsUnavailable
) {
    public TripPlan(String title, String summary, List<DayPlan> days) {
        this(title, summary, days, null);
    }

    public TripPlan(String title, List<DayPlan> days) {
        this(title, null, days, null);
    }
}
