package com.travel.app.attraction.service;

/**
 * Places 這次查不成，而且不是資料問題 —— 目前有三種 status：
 * <ul>
 *   <li>{@code OVER_QUERY_LIMIT}（額度耗盡）與 {@code REQUEST_DENIED}（key 無效 / API 未啟用）：Google 回的。</li>
 *   <li>{@code MONTHLY_LIMIT_REACHED}：程式內的每月呼叫上限已滿（或計數暫時無法確認），<b>根本沒有發出呼叫</b>，
 *       不是 Google 回的。上限見 {@code PlacesQuota} 與 SPEC §11.2。</li>
 * </ul>
 *
 * <p><b>為什麼用例外表達，而不是把狀態塞進回傳值</b>：
 * {@link HotelServiceImpl#findNearbyHotels} 掛著 {@code @Cacheable}，而 Spring 的 cache 抽象
 * 只在方法「正常回傳」時寫入快取，拋例外不寫。用例外順手解掉一個既有的洞 ——
 * 額度爆掉時回的空 list 會被鎖進 {@code hotelsNearby} 快取 1 小時（TTL 見 {@code CacheConfig}），
 * 就算額度中途恢復，同一個地點那一小時內還是回空的。
 *
 * <p>攔截點在 {@code GeminiItineraryService.enrichWithHotels}：它據此在 {@code TripPlan}
 * 掛上 {@code hotelsUnavailable} 旗標。本例外<b>不會</b>傳到 controller，也不該讓整趟生成失敗 ——
 * 查不到旅館時行程照樣要出得來。
 */
public class PlacesUnavailableException extends RuntimeException {

    private final String status;

    public PlacesUnavailableException(String status, String message) {
        super(message);
        this.status = status;
    }

    /** Google 回的原始 status（例如 {@code OVER_QUERY_LIMIT}），或程式自己的 {@code MONTHLY_LIMIT_REACHED}。 */
    public String getStatus() {
        return status;
    }
}
