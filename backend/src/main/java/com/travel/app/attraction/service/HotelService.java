package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.Hotel;

import java.util.List;

public interface HotelService {
    /**
     * 找出指定地點附近的推薦飯店（最多 8 間，TOP_N）。
     * 半徑 2km → 5km → 10km 逐步擴大，至少湊滿 3 間（MIN_QUALIFIED）達 4.2★且 50 則以上評論的飯店才停止；
     * 三段半徑都湊不滿時，退而取最大半徑內 50 則以上評論的飯店中評分最高的前 8 間。
     * 查無 / 一般 API 失敗時回傳空 list。詳見 SPEC §13.1。
     *
     * @throws PlacesUnavailableException Places 回 {@code OVER_QUERY_LIMIT} 或 {@code REQUEST_DENIED}
     *         時拋出（額度耗盡／授權失敗）；程式內的每月上限已滿（{@code MONTHLY_LIMIT_REACHED}，根本沒發出呼叫）
     *         也走同一個例外。這一種<b>不</b>退化成空 list —— 呼叫端要據此讓使用者
     *         知道「查不到」與「附近沒有」是兩回事，同時避免空結果被寫進快取。
     */
    List<Hotel> findNearbyHotels(String locationName);
}
