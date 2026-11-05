package com.travel.app.attraction.service;

import com.travel.app.shared.quota.PlacesCallPoint;
import com.travel.app.shared.quota.PlacesQuota;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link HotelServiceImpl} 接上 Places 每月上限後的行為：被擋下時「真的沒有發出連線」，並走既有的降級路徑
 * （丟 {@link PlacesUnavailableException} → 不寫快取、TripPlan 掛 hotelsUnavailable）。
 *
 * <p>HTTP 層換成 mock 的 {@link CloseableHttpClient}：Spring 6.2 的 {@code HttpComponentsClientHttpRequest}
 * 實際只呼叫 {@code executeOpen(HttpHost, ClassicHttpRequest, HttpContext)} 這一個方法（HttpHost 傳 null），
 * 所以數這個方法被呼叫幾次，就是數「打出去幾次」。CI 不給任何 API key、也不連外網。
 */
class HotelServiceImplTest {

    // Find Place 的成功回應：給得出座標，才走得到後面的 Nearby Search
    private static final String FIND_PLACE_OK = """
            {"status":"OK","candidates":[{"geometry":{"location":{"lat":25.03,"lng":121.56}}}]}
            """;

    private final CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
    private final PlacesQuota placesQuota = mock(PlacesQuota.class);

    @Test
    void find_place_over_monthly_limit_throws_without_any_http_call() {
        when(placesQuota.tryAcquire(PlacesCallPoint.HOTEL_FIND_PLACE)).thenReturn(false);
        HotelServiceImpl service = new HotelServiceImpl("test-key", httpClient, placesQuota);

        PlacesUnavailableException e = assertThrows(PlacesUnavailableException.class,
                () -> service.findNearbyHotels("台北101"));

        // 丟例外（而不是回空 list）才會讓前端顯示「暫時查不到」，也不會把空結果鎖進快取
        assertThat(e.getStatus()).isEqualTo("MONTHLY_LIMIT_REACHED");
        verifyNoInteractions(httpClient);
    }

    @Test
    void nearby_over_monthly_limit_stops_right_after_the_find_place_call() throws Exception {
        when(placesQuota.tryAcquire(PlacesCallPoint.HOTEL_FIND_PLACE)).thenReturn(true);
        when(placesQuota.tryAcquire(PlacesCallPoint.HOTEL_NEARBY)).thenReturn(false);
        when(httpClient.executeOpen(any(), any(), any())).thenReturn(jsonResponse(FIND_PLACE_OK));
        HotelServiceImpl service = new HotelServiceImpl("test-key", httpClient, placesQuota);

        PlacesUnavailableException e = assertThrows(PlacesUnavailableException.class,
                () -> service.findNearbyHotels("台北101"));

        assertThat(e.getStatus()).isEqualTo("MONTHLY_LIMIT_REACHED");
        // 只有 Find Place 那一次真的打出去；Nearby Search 在發出之前就被擋下
        ArgumentCaptor<ClassicHttpRequest> request = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(httpClient).executeOpen(any(), request.capture(), any());
        verifyNoMoreInteractions(httpClient);
        assertThat(request.getValue().getRequestUri()).contains("/findplacefromtext/");
    }

    @Test
    void blank_api_key_returns_empty_without_counting_or_calling() {
        HotelServiceImpl service = new HotelServiceImpl("", httpClient, placesQuota);

        assertThat(service.findNearbyHotels("台北101")).isEmpty();
        // 沒有 key 根本不會發出呼叫，不該佔用額度
        verifyNoInteractions(placesQuota, httpClient);
    }

    private static ClassicHttpResponse jsonResponse(String json) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        // RestClient 依 Content-Type header 挑 converter；不帶的話會被當成 octet-stream，Jackson 不接手
        response.addHeader("Content-Type", "application/json");
        response.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        return response;
    }
}
