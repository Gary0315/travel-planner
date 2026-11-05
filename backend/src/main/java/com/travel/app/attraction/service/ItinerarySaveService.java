package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.MyTripCardResponse;
import com.travel.app.attraction.dto.ShareIdResponse;
import com.travel.app.attraction.dto.TripPlan;

import java.util.List;

public interface ItinerarySaveService {
    /**
     * 儲存行程；回傳的 ShareIdResponse 含 shareId 與一次性的 deleteToken。
     * 呼叫端負責把 deleteToken 在 response 中露出給使用者保存。
     *
     * @param userId 擁有者使用者 id；未登入時傳 null（匿名儲存，仍可分享但不會出現在「我的行程」）
     */
    ShareIdResponse save(TripPlan tripPlan, Long userId);

    TripPlan findByShareId(String shareId);

    /**
     * 憑 deleteToken 刪除指定 shareId 的行程。
     * 找不到 shareId → 404；token 不合或舊資料沒有 token → 403。
     */
    void deleteByShareId(String shareId, String deleteToken);

    /**
     * 列出某位使用者所有已儲存的行程 metadata（卡片用）。最新儲存的在前。
     */
    List<MyTripCardResponse> listMine(Long userId);
}
