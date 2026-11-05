package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.app.attraction.dto.PlacePhotoBytes;
import com.travel.app.attraction.dto.PlacePhotoResponse;
import com.travel.app.attraction.entity.PlacePhotoCacheEntity;
import com.travel.app.attraction.repository.PlacePhotoCacheRepository;
import com.travel.app.attraction.service.HtmlAttributionParser.Attribution;
import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PlaceServiceImpl implements PlaceService {

    private static final Logger log = LoggerFactory.getLogger(PlaceServiceImpl.class);

    private static final String FALLBACK_IMAGE = "/mock-images/picture.svg";

    private static final String FIND_PLACE_URL =
            "https://maps.googleapis.com/maps/api/place/findplacefromtext/json";

    private static final String PHOTO_URL =
            "https://maps.googleapis.com/maps/api/place/photo";

    private final RestClient restClient;
    private final String apiKey;
    private final PlacePhotoCacheRepository cacheRepository;
    // photo_reference 本身相對穩定，但仍保留 TTL 作為快取失效保險（例如店家換照片、reference 被輪替）
    // 與 ItineraryCleanupJob 共用 app.place-photo-cache.retention-days，避免兩處常數漂移
    private final int cacheExpireDays;
    private final PlacesQuota placesQuota;
    // 正在查詢中的名稱 → 那一次查詢的結果。照片兩支端點（拿署名、抓圖）對同一個地點幾乎同時到達，
    // 兩邊都查不到 DB 快取就會各打一次 Find Place（SPEC §11.2「追測」實測 ×2），所以後到的改成等先到的結果。
    // 只在查詢進行中存在、查完立刻移除 —— 這不是快取，照片參照的快取仍只有 DB 一處。
    private final ConcurrentMap<String, CompletableFuture<FetchResult>> inFlight = new ConcurrentHashMap<>();

    public PlaceServiceImpl(
            @Value("${google.maps.api-key:}") String apiKey,
            @Value("${app.place-photo-cache.retention-days:30}") int cacheExpireDays,
            PlacePhotoCacheRepository cacheRepository,
            CloseableHttpClient httpClient,
            PlacesQuota placesQuota) {
        this.apiKey = apiKey;
        this.cacheExpireDays = cacheExpireDays;
        this.cacheRepository = cacheRepository;
        this.placesQuota = placesQuota;
        // 使用共用 Apache HttpClient 5 連線池（超時、pool size 設定見 HttpClientConfig）。
        // Apache HttpClient 5 預設跟隨 302 redirect，/place/photo → lh3.googleusercontent.com 可正常取得 bytes。
        this.restClient = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    @Cacheable(value = "places", key = "#name", sync = true)
    public PlacePhotoResponse getPhoto(String name) {
        FetchResult ref = getOrFetchPhotoReference(name);
        if (ref.photoReference() == null) {
            return new PlacePhotoResponse(FALLBACK_IMAGE, ref.placeId(), null, null);
        }
        // 回傳「後端 proxy URL」而非 Google URL：
        //  1. 同 origin → 避免 Chrome ORB 擋 user-contributed 圖
        //  2. API key 不外洩
        //  3. 後端可加 Cache-Control，省 Place Photos 計費
        return new PlacePhotoResponse(buildProxyUrl(name), ref.placeId(),
                ref.attribution(), ref.attributionUrl());
    }

    @Override
    // 快取鍵必須含 maxWidth：同一個地點的 200px 與 600px 是兩張不同的圖，
    // 只用 name 當鍵會讓先被查的那個尺寸餵給另一個尺寸的使用者。
    @Cacheable(value = "photoBytes", key = "#name + '@' + #maxWidth", sync = true)
    public PlacePhotoBytes getPhotoBytes(String name, int maxWidth) {
        FetchResult ref = getOrFetchPhotoReference(name);
        if (ref.photoReference() == null) {
            return null;
        }
        // 沒有 key 的下載必定失敗：直接回 null（與發出去再失敗的結果相同），省下一次注定失敗、還會被計數的呼叫
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String url = buildGooglePhotoUrl(ref.photoReference(), maxWidth);
        // 圖檔本身不存 DB，記憶體快取沒命中就是一次計費呼叫。被拒就回 null → controller 回 404 → 前端換預設圖
        if (!placesQuota.tryAcquire(PlacesCallPoint.PHOTO_IMAGE)) {
            return null;
        }
        try {
            ResponseEntity<byte[]> entity = restClient.get()
                    .uri(url)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = entity.getBody();
            if (body == null || body.length == 0) {
                return null;
            }
            String ct = entity.getHeaders().getFirst("Content-Type");
            return new PlacePhotoBytes(body, ct != null ? ct : "image/jpeg");
        } catch (Exception e) {
            // 回傳行為不變（null → 預設圖），只是不再無聲：額度用完時 Place Photo 回 403，會在這裡留下紀錄
            log.warn("Places photo download failed: {}", describeFailure(e));
            return null;
        }
    }

    /**
     * 拿到 name 對應的 photo_reference：先查 DB 快取，沒命中或過期就打 Google Find Place 並寫回 DB。
     * getPhoto 跟 getPhotoBytes 共用這一份 photo_reference 來源。
     *
     * <p><b>同一個名稱同時只有一個請求在查（single-flight）</b>：後到的請求等先到的那一次的結果，不自己再查。
     * 兩支端點各自的 {@code @Cacheable(sync = true)} 只合併「同一支端點」的同名請求，跨端點、跨圖片寬度都合併不到。
     * 查 DB 那一步也包在裡面：先到的請求寫回 DB 之後才從 {@code inFlight} 移除，
     * 所以在它查完之後才到的請求一定讀得到 DB 那一列，不會在「剛移除、還沒讀 DB」的空檔又打一次。
     * 只在同一個 JVM 內有效；多個實例同時查，最壞是各打一次，寫回撞主鍵由 {@link #lookupOrFetch} 接住。
     */
    private FetchResult getOrFetchPhotoReference(String name) {
        CompletableFuture<FetchResult> mine = new CompletableFuture<>();
        CompletableFuture<FetchResult> running = inFlight.putIfAbsent(name, mine);
        if (running != null) {
            return awaitResult(running);
        }
        try {
            FetchResult result = lookupOrFetch(name);
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            // 等待中的請求要拿到同一個錯誤，不能永遠卡在 join()
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(name, mine);
        }
    }

    // join() 會把對方的錯包成 CompletionException；拆開原樣再丟，讓等待的請求跟先到的那個結果一致
    private static FetchResult awaitResult(CompletableFuture<FetchResult> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private FetchResult lookupOrFetch(String name) {
        PlacePhotoCacheEntity cached = cacheRepository.findById(name).orElse(null);
        if (cached != null && cached.getCreatedAt().isAfter(LocalDateTime.now().minusDays(cacheExpireDays))) {
            return new FetchResult(
                    cached.getPhotoReference(),
                    cached.getPlaceId(),
                    cached.getAttribution(),
                    cached.getAttributionUrl());
        }

        FetchResult result = fetchFromGoogle(name);
        if (result.photoReference() != null) {
            try {
                cacheRepository.save(new PlacePhotoCacheEntity(
                        name, result.photoReference(), result.placeId(),
                        result.attribution(), result.attributionUrl()));
            } catch (DataIntegrityViolationException e) {
                // 預期最常見原因：跨 JVM instance 並發 race，別人剛好搶先寫入同一 PK；
                // 但其他 constraint violation（例如未來 schema 加了 unique / NOT NULL 後欄位）也會落到這裡，
                // 屬於要被人看到的問題，不能完全沉默。本次仍可用 result 直接回應，不影響使用者請求。
                log.warn("place_photo_cache write failed for name='{}': {}", name, e.getMessage());
            }
        }
        return result;
    }

    private static String buildProxyUrl(String name) {
        return "/api/places/photo/binary?name=" + UriUtils.encode(name, StandardCharsets.UTF_8);
    }

    private String buildGooglePhotoUrl(String photoReference, int maxWidth) {
        return UriComponentsBuilder.fromHttpUrl(PHOTO_URL)
                .queryParam("maxwidth", maxWidth)
                .queryParam("photo_reference", photoReference)
                .queryParam("key", apiKey)
                .build(false)
                .toUriString();
    }

    private FetchResult fetchFromGoogle(String name) {
        if (apiKey == null || apiKey.isBlank()) {
            return FetchResult.empty();
        }
        try {
            String findUri = UriComponentsBuilder.fromHttpUrl(FIND_PLACE_URL)
                    .queryParam("input", name)
                    .queryParam("inputtype", "textquery")
                    .queryParam("fields", "photos,place_id")
                    .queryParam("language", "zh-TW")
                    .queryParam("key", apiKey)
                    .build(false)
                    .toUriString();

            // 上限已滿就不發出：回空結果 → 前端顯示預設圖。這條路很密，WARN 由計數器每月只印一次，這裡不重複
            if (!placesQuota.tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE)) {
                return FetchResult.empty();
            }
            JsonNode body = restClient.get()
                    .uri(findUri)
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null) return FetchResult.empty();
            logUnexpectedStatus(body);

            JsonNode candidates = body.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) return FetchResult.empty();

            JsonNode candidate = candidates.get(0);
            String placeId = candidate.path("place_id").asText(null);

            JsonNode photos = candidate.path("photos");
            if (!photos.isArray() || photos.isEmpty()) {
                return new FetchResult(null, placeId, null, null);
            }

            JsonNode firstPhoto = photos.get(0);
            String photoReference = firstPhoto.path("photo_reference").asText(null);
            if (photoReference == null || photoReference.isBlank()) {
                return new FetchResult(null, placeId, null, null);
            }

            Attribution attr = HtmlAttributionParser.fromPhotoNode(firstPhoto);
            return new FetchResult(photoReference, placeId, attr.name(), attr.url());

        } catch (Exception e) {
            log.warn("Places findplacefromtext (photo) failed: {}", describeFailure(e));
        }
        return FetchResult.empty();
    }

    /**
     * 與 {@link HotelServiceImpl} 的 checkStatus 讀同一個欄位（Places legacy 把錯誤放在 body 的 status，HTTP 一律 200），
     * 但這裡只記錄、不丟例外：照片拿不到本來就退回預設圖，行為不變 —— 只是額度用完、key 失效時，
     * log 從此講得出原因（SPEC §11.2 盤點出的缺口 2）。{@code ZERO_RESULTS} 是正常結果（真的查無此地），不記。
     */
    private static void logUnexpectedStatus(JsonNode body) {
        String status = body.path("status").asText("");
        if (status.isEmpty() || "OK".equals(status) || "ZERO_RESULTS".equals(status)) {
            return;
        }
        log.warn("Places findplacefromtext (photo) returned status={} {}",
                status, body.path("error_message").asText(""));
    }

    /**
     * 例外只記類別名（HTTP 錯誤再加狀態碼），不記 {@code e.getMessage()}：請求 URL 的 query string 帶著 key，
     * 例外訊息會不會夾帶 URL 由 Spring 的版本決定（6.2 會先截掉 {@code ?} 之後的部分，但那是實作細節、不是保證）。
     */
    private static String describeFailure(Exception e) {
        if (e instanceof RestClientResponseException responseException) {
            return e.getClass().getSimpleName() + "（HTTP " + responseException.getStatusCode().value() + "）";
        }
        return e.getClass().getSimpleName();
    }

    // 內部用的小 record：photo_reference 拿不到時 placeId 仍可能有值
    private record FetchResult(String photoReference, String placeId,
                                String attribution, String attributionUrl) {
        static FetchResult empty() { return new FetchResult(null, null, null, null); }
    }
}
