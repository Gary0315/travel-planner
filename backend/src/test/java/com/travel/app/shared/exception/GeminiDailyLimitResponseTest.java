package com.travel.app.shared.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.attraction.controller.ItineraryController;
import com.travel.app.attraction.dto.GenerateItineraryRequest;
import com.travel.app.attraction.service.ItineraryService;
import com.travel.app.attraction.service.ItinerarySaveService;
import com.travel.app.shared.config.SecurityConfig;
import com.travel.app.shared.quota.ApiCallCounterRepository;
import com.travel.app.shared.quota.InMemoryCounterRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證「Gemini 每日呼叫上限已達」走完整 Spring MVC 鏈路後，前端實際收到的回應。
 *
 * <p>守的是 {@link GlobalExceptionHandler} 的接線：新例外若沒有被專屬 handler 接到，
 * 會落進通用的 {@code Exception} handler 變成 500 —— 單元測試照樣全綠，但前端會顯示
 * 「系統暫時無法處理」而不是「今天已達上限」。
 *
 * <p>回應必須與 {@code DailyQuotaInterceptor}（全站每日請求上限）一字不差：前端只認
 * {@code DAILY_QUOTA_EXCEEDED} 這個 errorCode，認得才不會啟動 60 秒倒數鎖按鈕。
 */
@WebMvcTest(controllers = ItineraryController.class)
@Import(SecurityConfig.class)
class GeminiDailyLimitResponseTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ItineraryService itineraryService;
    @MockBean private ItinerarySaveService itinerarySaveService;
    // WebConfig 的全站每日額度要用它；接上記憶體版讓入口那一層正常放行，請求才走得到 service
    @MockBean private ApiCallCounterRepository counterRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void backCounterRowsInMemory() {
        new InMemoryCounterRows(counterRepository);
    }

    @Test
    void gemini_daily_limit_maps_to_429_with_the_error_code_the_frontend_recognises() throws Exception {
        when(itineraryService.generate(anyString(), anyString()))
                .thenThrow(new GeminiDailyLimitExceededException(200));

        String body = objectMapper.writeValueAsString(
                new GenerateItineraryRequest("台南三天兩夜", "driving"));

        mockMvc.perform(post("/api/itinerary/generate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("DAILY_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("今天的行程生成次數已達上限，請明天再試"))
                // 不可有 retryAfterSeconds：它會讓前端啟動秒數倒數
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist());

        // 這個 429 必須來自 service 丟出的例外，而不是入口的 DailyQuotaInterceptor ——
        // 兩者的回應長得一樣，不驗這一行就分不出測到的是哪一條路
        verify(itineraryService).generate(anyString(), anyString());
    }
}
