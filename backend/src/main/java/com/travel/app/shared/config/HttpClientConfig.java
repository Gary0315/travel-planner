package com.travel.app.shared.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共用 Apache HttpClient 5 連線池，供 PlaceServiceImpl 與 HotelServiceImpl 共享。
 *
 * SimpleClientHttpRequestFactory（HttpURLConnection）每次請求建立新 TCP 連線，
 * HTTPS 場景下 TLS handshake 約 150–300ms，高峰期每個景點照片都在付這個成本。
 * 改用 Apache HttpClient 5 的連線池：同一個 host 的 keep-alive 連線可被重用，
 * 後續請求省去 TCP + TLS 建立時間。
 *
 * 池子設定：
 *  - maxConnTotal=50：全域最大連線數（兩個 service 共用這個 pool）
 *  - maxConnPerRoute=20：每個目標 host（maps.googleapis.com）最多 20 條並發連線
 *  - evictIdle(30s)：關閉 30 秒未用的閒置連線，避免 NAT 超時後下次請求用到已死連線
 *  - connectTimeout=5s / responseTimeout=10s：同前一版設定保持一致
 *
 * CloseableHttpClient 實作 AutoCloseable；Spring 在 context 關閉時會自動呼叫 close()。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public CloseableHttpClient googleApiHttpClient() {
        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();

        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }
}
