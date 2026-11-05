package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.app.attraction.dto.Hotel;
import com.travel.app.attraction.service.HtmlAttributionParser.Attribution;
import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HotelServiceImpl implements HotelService {

    private static final Logger log = LoggerFactory.getLogger(HotelServiceImpl.class);

    private static final String FIND_PLACE_URL =
            "https://maps.googleapis.com/maps/api/place/findplacefromtext/json";
    private static final String NEARBY_URL =
            "https://maps.googleapis.com/maps/api/place/nearbysearch/json";

    private static final double MIN_RATING = 4.2;
    private static final int MIN_REVIEWS = 50;
    private static final int[] RADII_METERS = {2000, 5000, 10000};
    // 至少湊滿 MIN_QUALIFIED 間達標飯店才停止擴大半徑；最終最多回傳 TOP_N 間
    private static final int MIN_QUALIFIED = 3;
    private static final int TOP_N = 8;

    private final RestClient restClient;
    private final String apiKey;
    private final PlacesQuota placesQuota;

    public HotelServiceImpl(
            @Value("${google.maps.api-key:}") String apiKey,
            CloseableHttpClient httpClient,
            PlacesQuota placesQuota) {
        this.apiKey = apiKey;
        this.placesQuota = placesQuota;
        // 使用共用 Apache HttpClient 5 連線池（超時、pool size 設定見 HttpClientConfig）。
        this.restClient = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    @Cacheable(value = "hotelsNearby", key = "#locationName", sync = true)
    public List<Hotel> findNearbyHotels(String locationName) {
        if (apiKey == null || apiKey.isBlank() || locationName == null || locationName.isBlank()) {
            return List.of();
        }

        try {
            double[] coords = findCoordinates(locationName);
            if (coords == null) {
                // 走到這裡現在真的只剩「查無此地」一種可能 —— 額度／授權失敗已由
                // checkStatus 在上面拋掉。在加 checkStatus 之前這行會在額度爆掉時照印，
                // 是 Render log 裡唯一的線索，而且指向錯的方向（2026-08-24 查證）。
                log.info("Hotel search: Places returned no candidates for '{}'", locationName);
                return List.of();
            }

            List<Hotel> largestBatch = List.of();
            for (int radius : RADII_METERS) {
                List<Hotel> all = searchHotelsNearby(coords[0], coords[1], radius);
                largestBatch = all;
                List<Hotel> qualified = all.stream()
                        .filter(h -> h.rating() != null && h.rating() >= MIN_RATING)
                        .filter(h -> h.userRatingsTotal() != null && h.userRatingsTotal() >= MIN_REVIEWS)
                        .sorted(Comparator.comparing(Hotel::rating, Comparator.reverseOrder()))
                        .toList();
                if (qualified.size() >= MIN_QUALIFIED) {
                    return qualified.stream().limit(TOP_N).toList();
                }
            }

            // Fallback：最大半徑內評論數達門檻的飯店中，挑評分最高的前 TOP_N 間
            return largestBatch.stream()
                    .filter(h -> h.rating() != null
                            && h.userRatingsTotal() != null
                            && h.userRatingsTotal() >= MIN_REVIEWS)
                    .sorted(Comparator.comparing(Hotel::rating, Comparator.reverseOrder()))
                    .limit(TOP_N)
                    .toList();
        } catch (PlacesUnavailableException e) {
            // 刻意往外拋、不吞掉，兩個理由：
            //  ① @Cacheable 只在正常回傳時寫快取 —— 拋例外就不會把「額度爆掉的空結果」
            //     鎖進 hotelsNearby 快取一小時（TTL 見 CacheConfig）
            //  ② GeminiItineraryService 據此在 TripPlan 掛 hotelsUnavailable 旗標，
            //     讓前端顯示提示，而不是讓住宿區無聲消失
            log.warn("Hotel search unavailable for '{}': {}", locationName, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Hotel search failed for '{}': {}", locationName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Places (legacy) 把錯誤放在 body 的 {@code status} 裡，<b>HTTP 一律 200</b>。
     *
     * <p>2026-08-24 用無效 key 實測確認回應長相：
     * <pre>
     * HTTP 200
     * {"error_message":"The provided API key is invalid.","html_attributions":[],
     *  "results":[],"status":"REQUEST_DENIED"}
     * </pre>
     * 在這之前 code 只讀 {@code results}/{@code candidates}，於是額度耗盡會靜默退化成
     * 「查無結果」，而 Google 其實已經把真正原因寫在 {@code error_message} 送過來了。
     *
     * <p>{@code ZERO_RESULTS} 是正常結果不是失敗（附近真的沒旅館），照舊回空 list 並允許快取。
     */
    private static void checkStatus(JsonNode body, String endpoint) {
        String status = body.path("status").asText("");
        if (status.isEmpty() || "OK".equals(status) || "ZERO_RESULTS".equals(status)) {
            return;
        }

        String detail = body.path("error_message").asText("");
        if ("OVER_QUERY_LIMIT".equals(status) || "REQUEST_DENIED".equals(status)) {
            throw new PlacesUnavailableException(status,
                    "Places " + endpoint + " 回 " + status + (detail.isBlank() ? "" : "：" + detail));
        }

        // INVALID_REQUEST / UNKNOWN_ERROR：這次查不到，但不是額度問題，維持既有的「回空結果」行為。
        // 至少現在 log 講的是真話，不必再靠猜。
        log.warn("Places {} returned status={} {}", endpoint, status, detail);
    }

    /**
     * 本月上限已滿（或計數器暫時數不了）就不發出呼叫，改丟 {@link PlacesUnavailableException}，
     * 與 Google 回 {@code OVER_QUERY_LIMIT} 走同一條降級路：不寫快取、行程照樣產出、前端顯示「住宿建議暫時查不到」。
     * status 用程式自己的 {@code MONTHLY_LIMIT_REACHED}，log 才分得出是哪一層擋下的。
     */
    private void acquireOrThrow(PlacesCallPoint point, String endpoint) {
        if (!placesQuota.tryAcquire(point)) {
            throw new PlacesUnavailableException("MONTHLY_LIMIT_REACHED",
                    "Places " + endpoint + " 本月呼叫上限已滿（或計數暫時無法確認），本次不發出呼叫");
        }
    }

    private double[] findCoordinates(String name) {
        String uri = UriComponentsBuilder.fromHttpUrl(FIND_PLACE_URL)
                .queryParam("input", name)
                .queryParam("inputtype", "textquery")
                .queryParam("fields", "geometry")
                .queryParam("language", "zh-TW")
                .queryParam("key", apiKey)
                .build(false)
                .toUriString();

        acquireOrThrow(PlacesCallPoint.HOTEL_FIND_PLACE, "findplacefromtext");
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null) return null;
        checkStatus(body, "findplacefromtext");
        JsonNode candidates = body.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        JsonNode loc = candidates.get(0).path("geometry").path("location");
        if (!loc.has("lat") || !loc.has("lng")) return null;
        return new double[]{loc.path("lat").asDouble(), loc.path("lng").asDouble()};
    }

    private List<Hotel> searchHotelsNearby(double lat, double lng, int radius) {
        String uri = UriComponentsBuilder.fromHttpUrl(NEARBY_URL)
                .queryParam("location", lat + "," + lng)
                .queryParam("radius", radius)
                .queryParam("type", "lodging")
                .queryParam("language", "zh-TW")
                .queryParam("key", apiKey)
                .build(false)
                .toUriString();

        // 半徑每擴大一圈都是一次計費呼叫，所以每次都要先問過上限
        acquireOrThrow(PlacesCallPoint.HOTEL_NEARBY, "nearbysearch");
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null) return List.of();
        checkStatus(body, "nearbysearch");
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) return List.of();

        List<Hotel> hotels = new ArrayList<>();
        for (JsonNode r : results) {
            String name = r.path("name").asText(null);
            if (name == null) continue;
            Double rating = r.has("rating") ? r.path("rating").asDouble() : null;
            Integer total = r.has("user_ratings_total") ? r.path("user_ratings_total").asInt() : null;
            String address = r.path("vicinity").asText(null);
            String placeId = r.path("place_id").asText(null);

            // photos[0].html_attributions[0] → 第一張縮圖的著作權標示（合規 Google ToS）
            Attribution attr = Attribution.EMPTY;
            JsonNode photos = r.path("photos");
            if (photos.isArray() && !photos.isEmpty()) {
                attr = HtmlAttributionParser.fromPhotoNode(photos.get(0));
            }

            hotels.add(new Hotel(name, rating, total, address, placeId, attr.name(), attr.url()));
        }
        return hotels;
    }
}
