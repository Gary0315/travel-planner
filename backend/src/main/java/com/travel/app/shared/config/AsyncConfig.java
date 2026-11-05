package com.travel.app.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hotel enrichment 專用執行緒池。
 *
 * 從 GeminiItineraryService 的 static 欄位移出，改為 Spring bean：
 *  - Spring context 關閉時自動呼叫 shutdown()，確保 graceful shutdown
 *  - daemon=true：SIGTERM 後不阻擋 JVM exit
 *  - 固定 20 條 platform thread：支援約 10 並發 /generate × 4 天查詢，
 *    搭配 orTimeout(10s) 限制最壞情況排隊時間
 */
@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService hotelExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(20, r -> {
            Thread t = new Thread(r, "hotel-enrich-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
