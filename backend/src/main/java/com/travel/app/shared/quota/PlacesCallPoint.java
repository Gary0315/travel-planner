package com.travel.app.shared.quota;

/**
 * Google Places 的四個對外呼叫點 —— 每個呼叫點各自一個每月計數器（SPEC §11.2）。
 *
 * <p>為什麼按呼叫點分開數，而不是整支 API 合計一個數字：
 * <ul>
 *   <li>Google 的免費次數按計費項目分開算，而且差很多（Find Place 5,000 次、Nearby Search 實質 1,000 次、
 *       Place Photo 1,000 次），合計成一個數字守不住其中任何一項。</li>
 *   <li>同一個計費項目也分開：照片的兩支端點不用登入、名稱也不驗證，分開數，照片查詢才吃不掉旅館查座標的份額。</li>
 * </ul>
 *
 * <p>每個呼叫點的上限設定鍵是 {@code app.api-quota.places-monthly.*}，預設值與數字由來見 application.yml。
 */
public enum PlacesCallPoint {

    /** {@code HotelServiceImpl#findCoordinates}：用地名查旅館搜尋的中心座標（Find Place，fields=geometry）。 */
    HOTEL_FIND_PLACE("places.hotel-find-place"),

    /** {@code HotelServiceImpl#searchHotelsNearby}：查附近的旅館（Nearby Search）；半徑每擴大一圈就是一次呼叫。 */
    HOTEL_NEARBY("places.hotel-nearby"),

    /** {@code PlaceServiceImpl#fetchFromGoogle}：查景點／旅館照片的參照（Find Place，fields=photos,place_id）。 */
    PHOTO_FIND_PLACE("places.photo-find-place"),

    /** {@code PlaceServiceImpl#getPhotoBytes}：下載照片圖檔（Place Photo）。 */
    PHOTO_IMAGE("places.photo-image");

    private final String counterName;

    PlacesCallPoint(String counterName) {
        this.counterName = counterName;
    }

    /** 資料表 {@code api_call_counter.counter_name} 的值。改名等於換一個新計數器，本月已用的次數不會跟過來。 */
    public String counterName() {
        return counterName;
    }
}
