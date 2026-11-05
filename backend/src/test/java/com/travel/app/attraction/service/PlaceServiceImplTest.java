package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.PlacePhotoBytes;
import com.travel.app.attraction.dto.PlacePhotoResponse;
import com.travel.app.attraction.entity.PlacePhotoCacheEntity;
import com.travel.app.attraction.repository.PlacePhotoCacheRepository;
import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PlaceServiceImpl} 接上 Places 每月上限後的行為：被擋下時不發出連線，照片退回預設圖（或 null → 404 → 預設圖）。
 *
 * <p>照片兩支端點不用登入、名稱不驗證（SPEC §11.2 缺口 1），所以「被擋下時真的沒打出去」要用
 * {@code verifyNoInteractions(httpClient)} 證明，只看回傳值不夠。DB 快取與 HTTP 都是 mock，CI 不給任何 API key。
 *
 * <p>後半是同名查詢的合併（single-flight）：讓 mock 卡在查詢途中，製造「兩個請求同時在途」的情況。
 * 這裡直接 new 出 service、沒有 Spring，兩支端點各自的 {@code @Cacheable} 不生效 —— 測到的正是 service 自己的合併。
 */
class PlaceServiceImplTest {

    private static final String NAME = "審計新村";
    private static final String FALLBACK_IMAGE = "/mock-images/picture.svg";

    private final PlacePhotoCacheRepository cacheRepository = mock(PlacePhotoCacheRepository.class);
    private final CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    private final PlacesQuota placesQuota = mock(PlacesQuota.class);

    @Test
    void find_place_over_monthly_limit_falls_back_to_default_image_without_http_call() {
        when(cacheRepository.findById(NAME)).thenReturn(Optional.empty());
        when(placesQuota.tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE)).thenReturn(false);

        PlacePhotoResponse response = service("test-key").getPhoto(NAME);

        assertThat(response.imageUrl()).isEqualTo(FALLBACK_IMAGE);
        verifyNoInteractions(httpClient);
        // 被擋下的「沒結果」不能寫進 DB 快取，否則額度恢復後 30 天內都還是預設圖
        verify(cacheRepository, never()).save(any(PlacePhotoCacheEntity.class));
    }

    @Test
    void photo_download_over_monthly_limit_returns_null_without_http_call() {
        // DB 快取命中且未過期 → 不必查 Find Place，直接走到下載圖檔那一步
        when(cacheRepository.findById(NAME)).thenReturn(Optional.of(cachedReference()));
        when(placesQuota.tryAcquire(PlacesCallPoint.PHOTO_IMAGE)).thenReturn(false);

        assertThat(service("test-key").getPhotoBytes(NAME, 600)).isNull();

        verifyNoInteractions(httpClient);
        verify(placesQuota, never()).tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE);
    }

    @Test
    void blank_api_key_never_counts_against_the_quota() {
        PlaceServiceImpl service = service("");

        // DB 快取沒命中 → 查 Find Place 前的 key 檢查就返回
        when(cacheRepository.findById("沒有快取的地點")).thenReturn(Optional.empty());
        assertThat(service.getPhoto("沒有快取的地點").imageUrl()).isEqualTo(FALLBACK_IMAGE);

        // DB 快取命中 → 下載圖檔前的 key 檢查就返回
        when(cacheRepository.findById(NAME)).thenReturn(Optional.of(cachedReference()));
        assertThat(service.getPhotoBytes(NAME, 600)).isNull();

        // 沒有 key 的呼叫注定失敗，不該佔用額度
        verifyNoInteractions(placesQuota, httpClient);
    }

    @Test
    void concurrent_photo_and_binary_requests_for_the_same_name_query_find_place_once() throws Exception {
        when(cacheRepository.findById(NAME)).thenReturn(Optional.empty());
        CountDownLatch firstInFindPlace = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        // 先到的請求卡在「準備打 Find Place」那一步；回 false = 不發出 HTTP，結果是預設圖
        when(placesQuota.tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE)).thenAnswer(invocation -> {
            firstInFindPlace.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return false;
        });
        PlaceServiceImpl service = service("test-key");

        // 重現 SPEC §11.2「追測」：拿署名與抓圖兩支端點對同一個地點同時到達
        Request<PlacePhotoResponse> metadata = Request.start(() -> service.getPhoto(NAME));
        assertThat(firstInFindPlace.await(5, TimeUnit.SECONDS)).isTrue();
        Request<PlacePhotoBytes> image = Request.start(() -> service.getPhotoBytes(NAME, 600));
        image.awaitParked();
        releaseFirst.countDown();

        assertThat(metadata.await().imageUrl()).isEqualTo(FALLBACK_IMAGE);
        assertThat(image.await()).isNull();
        // 合併前這裡是 2 次：兩個請求都查不到 DB 快取，各打一次
        verify(placesQuota, times(1)).tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE);
        verify(cacheRepository, times(1)).findById(NAME);
        verifyNoInteractions(httpClient);
    }

    @Test
    void waiting_request_gets_the_same_error_when_the_first_lookup_fails() throws Exception {
        CountDownLatch firstInLookup = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("db down");
        // 先到的請求卡在查 DB 快取，放行後丟錯
        when(cacheRepository.findById(NAME)).thenAnswer(invocation -> {
            firstInLookup.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            throw dbDown;
        });
        PlaceServiceImpl service = service("test-key");

        Request<PlacePhotoResponse> first = Request.start(() -> service.getPhoto(NAME));
        assertThat(firstInLookup.await(5, TimeUnit.SECONDS)).isTrue();
        Request<PlacePhotoBytes> waiting = Request.start(() -> service.getPhotoBytes(NAME, 600));
        waiting.awaitParked();
        releaseFirst.countDown();

        // 兩個請求拿到同一個錯（不是包過的 CompletionException），等待的那個也不會永遠卡住
        assertThatThrownBy(first::await).isInstanceOf(ExecutionException.class).hasCauseReference(dbDown);
        assertThatThrownBy(waiting::await).isInstanceOf(ExecutionException.class).hasCauseReference(dbDown);
        verify(cacheRepository, times(1)).findById(NAME);
    }

    @Test
    void finished_lookup_is_removed_so_later_requests_look_up_again() {
        when(cacheRepository.findById(NAME)).thenReturn(Optional.empty());
        when(placesQuota.tryAcquire(PlacesCallPoint.PHOTO_FIND_PLACE)).thenReturn(false);
        PlaceServiceImpl service = service("test-key");

        service.getPhoto(NAME);
        service.getPhoto(NAME);

        // 合併只在查詢途中有效，查完就移除：之後的請求自己查（正常會在 DB 命中），不會沿用上一次的結果
        verify(cacheRepository, times(2)).findById(NAME);
    }

    private PlaceServiceImpl service(String apiKey) {
        return new PlaceServiceImpl(apiKey, 30, cacheRepository, httpClient, placesQuota);
    }

    // createdAt 由建構子設為現在，在 30 天 TTL 內 → 視為有效快取
    private static PlacePhotoCacheEntity cachedReference() {
        return new PlacePhotoCacheEntity(NAME, "photo-ref-123", "place-id-1", null, null);
    }

    /** 在自己的執行緒跑一個請求，模擬兩個 HTTP 請求各佔一條 Tomcat 執行緒。 */
    private record Request<T>(Thread thread, FutureTask<T> result) {

        static <R> Request<R> start(Callable<R> call) {
            FutureTask<R> task = new FutureTask<>(call);
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            thread.start();
            return new Request<>(thread, task);
        }

        T await() throws Exception {
            return result.get(5, TimeUnit.SECONDS);
        }

        // 等這個請求停下來（合併後停在 join()，合併前停在卡住的 mock 裡）才放行先到的那個；
        // 不等的話，可能只是兩個請求剛好錯開，測不到「同時在途」
        void awaitParked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("後到的請求 5 秒內沒有停下來等待");
                }
                Thread.sleep(10);
            }
        }
    }
}
