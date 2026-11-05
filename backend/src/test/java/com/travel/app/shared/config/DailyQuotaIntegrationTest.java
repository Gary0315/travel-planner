package com.travel.app.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.attraction.controller.ItineraryController;
import com.travel.app.attraction.dto.GenerateItineraryRequest;
import com.travel.app.attraction.dto.TripPlan;
import com.travel.app.attraction.service.ItineraryService;
import com.travel.app.attraction.service.ItinerarySaveService;
import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.InMemoryCounterRows;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證每日額度攔截器**真的被掛在 /api/itinerary/generate 上**，走完整 Spring MVC 鏈路。
 *
 * <p>與 {@link DailyQuotaInterceptorTest} 互補：
 * <ul>
 *   <li>單元測試守攔截器自身的行為（額度、跨日重置、不分 IP、資料庫故障放行）</li>
 *   <li>本測試守 {@link WebConfig} 的接線 —— addPathPatterns 路徑正確、
 *       {@code app.rate-limit.daily-quota} 設定值真的被注入、計數器的名稱／期間／時區／故障處理接對。
 *       這些寫錯時單元測試照樣全綠，但線上完全不會生效。</li>
 * </ul>
 *
 * <p>資料表是 {@code @MockBean} 接上記憶體版（{@link InMemoryCounterRows}）：{@code @WebMvcTest} 不起 JPA。
 * 上限是否真的傳進 SQL，由記憶體版照著 {@code maxCount} 參數判斷 —— 傳錯值，第 4 次就不會被擋。
 *
 * <p><b>刻意只有一個會送請求的測試方法</b>：同一個 test class 共用 Spring context，也就共用同一個
 * 攔截器與它的計數器，而計數器確認額滿後會在記憶體記下「今天已滿」。第二個送請求的方法跑起來時
 * 會直接被擋，得到誤導性的失敗。各種額度情境交給單元測試涵蓋。
 */
@WebMvcTest(controllers = ItineraryController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.rate-limit.daily-quota=3")
class DailyQuotaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DailyQuotaInterceptor dailyQuotaInterceptor;

    @MockBean private ItineraryService itineraryService;
    @MockBean private ItinerarySaveService itinerarySaveService;
    @MockBean private ApiCallCounterRepository counterRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void backCounterRowsInMemory() {
        new InMemoryCounterRows(counterRepository);
    }

    @Test
    void fourth_generate_request_is_blocked_by_daily_quota() throws Exception {
        when(itineraryService.generate(anyString(), anyString()))
                .thenReturn(new TripPlan("測試行程", List.of()));

        String body = objectMapper.writeValueAsString(
                new GenerateItineraryRequest("台南三天兩夜", "driving"));

        // 額度 3 —— 前三次應正常通過（per-IP 桶 capacity 5，這裡不會先被它擋下）
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/itinerary/generate")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        // 第 4 次：額度用盡，且必須帶 errorCode —— 前端靠它切換到「請明天再試」的文案分支，
        // 沒有它會落到「請求太頻繁，請等待 N 秒」並啟動無意義的倒數。
        mockMvc.perform(post("/api/itinerary/generate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("DAILY_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("今天的行程生成次數已達上限，請明天再試"))
                // 不可有 retryAfterSeconds：它會讓前端啟動秒數倒數
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());
    }

    @Test
    void the_interceptor_counts_daily_in_taipei_time_and_lets_requests_through_when_the_database_is_down() {
        // 只讀設定、不送請求，不受上面那個方法吃掉的額度影響
        PersistentCallCounter counter = dailyQuotaInterceptor.counter();

        // 名稱就是資料表的 counter_name：改名等於換一個新計數器、當天已用的次數歸零，所以一起釘住
        assertThat(counter.name()).isEqualTo("site.generate-requests");
        assertThat(counter.period()).isEqualTo(Period.DAILY);
        assertThat(counter.limit()).as("app.rate-limit.daily-quota 有被注入").isEqualTo(3);
        assertThat(counter.resetZone()).hasToString("Asia/Taipei");
        assertThat(counter.onDatabaseError()).isEqualTo(OnDatabaseError.ALLOW);
    }
}
