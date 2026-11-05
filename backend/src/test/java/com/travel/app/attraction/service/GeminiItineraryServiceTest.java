package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.Activity;
import com.travel.app.attraction.dto.DayPlan;
import com.travel.app.attraction.dto.TripPlan;
import com.travel.app.shared.exception.AiGenerationException;
import com.travel.app.shared.exception.GeminiDailyLimitExceededException;
import com.travel.app.shared.quota.InMemoryCounterRows;
import com.travel.app.shared.quota.PersistentCallCounter;
import com.travel.app.shared.quota.PersistentCallCounter.OnDatabaseError;
import com.travel.app.shared.quota.PersistentCallCounter.Period;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiItineraryServiceTest {

    private static final String COUNTER_NAME = "gemini.test";
    private static final String TODAY = "2026-10-07";

    // 資料表用記憶體版；固定時鐘：計數器以日期換日，用真實時鐘的話，剛好跨午夜跑測試會讓次數莫名歸零
    private static PersistentCallCounter callCounterWithLimit(int dailyLimit, InMemoryCounterRows rows) {
        return new PersistentCallCounter(COUNTER_NAME, Period.DAILY, dailyLimit, ZoneOffset.UTC,
                OnDatabaseError.ALLOW, rows.repository(),
                Clock.fixed(Instant.parse("2026-10-07T17:00:00Z"), ZoneOffset.UTC));
    }

    // 不關心每日呼叫上限的測試用：給一個用不完的計數器，讓它不干擾被測行為
    private static PersistentCallCounter roomyCallCounter() {
        return callCounterWithLimit(Integer.MAX_VALUE, new InMemoryCounterRows());
    }

    @Nested
    class ExtractJsonObject {

        @Test
        void returnsNullForNullInput() {
            assertNull(GeminiItineraryService.extractJsonObject(null));
        }

        @Test
        void passesPureJsonThrough() {
            assertEquals("{\"a\":1}", GeminiItineraryService.extractJsonObject("{\"a\":1}"));
        }

        @Test
        void stripsJsonCodeFence() {
            String input = "```json\n{\"a\":1}\n```";
            assertEquals("{\"a\":1}", GeminiItineraryService.extractJsonObject(input));
        }

        @Test
        void stripsBareCodeFence() {
            String input = "```\n{\"a\":1}\n```";
            assertEquals("{\"a\":1}", GeminiItineraryService.extractJsonObject(input));
        }

        @Test
        void extractsJsonFromSurroundingNoise() {
            String input = "Sure, here is the JSON: {\"title\":\"X\"} Hope it helps!";
            assertEquals("{\"title\":\"X\"}", GeminiItineraryService.extractJsonObject(input));
        }

        @Test
        void preservesNestedBracesContent() {
            // 用 lastIndexOf('}') 找最後一個 } → 巢狀內容會被完整保留
            String input = "{\"a\":{\"b\":1},\"c\":[1,2]}";
            assertEquals(input, GeminiItineraryService.extractJsonObject(input));
        }

        @Test
        void trimsLeadingAndTrailingWhitespace() {
            assertEquals("{\"a\":1}",
                    GeminiItineraryService.extractJsonObject("   \n {\"a\":1}   \n  "));
        }

        @Test
        void returnsCleanedWhenNoJsonBraces() {
            // 找不到 { 與 } 配對時退回 cleaned 字串，讓上層 converter.convert 自己拋例外
            assertEquals("no json here", GeminiItineraryService.extractJsonObject("no json here"));
        }
    }

    @Nested
    class ValidateAgainstContract {

        private static ValidatorFactory factory;
        private static Validator validator;

        @BeforeAll
        static void setUp() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void tearDown() {
            if (factory != null) factory.close();
        }

        private GeminiItineraryService serviceWithValidator() {
            // ChatClient.Builder.build() 在 constructor 被呼叫但回傳的 chatClient 不會被 validateAgainstContract 用到，
            // 所以 mock 回 null 即可。其他依賴傳 null 是安全的（驗證路徑不會碰到）。
            ChatClient.Builder builderMock = mock(ChatClient.Builder.class);
            return new GeminiItineraryService(builderMock, null, null, validator, roomyCallCounter());
        }

        @Test
        void passesWhenTripPlanIsValid() {
            assertDoesNotThrow(() -> serviceWithValidator().validateAgainstContract(validPlan()));
        }

        @Test
        void throwsAiParseErrorWhenActivityTimeInvalid() {
            TripPlan plan = new TripPlan("台北一日遊", "summary", List.of(
                    new DayPlan(1, "西區散步日", List.of(
                            // 全形波浪號 → 不符 @Pattern
                            new Activity("9:00～11:00", "中正紀念堂", "拍照", "步行 5 分鐘", "景點", "🏛️")
                    ), null)
            ));

            AiGenerationException ex = assertThrows(AiGenerationException.class,
                    () -> serviceWithValidator().validateAgainstContract(plan));

            assertEquals("AI_PARSE_ERROR", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("time"),
                    "violation message should mention failed field: " + ex.getMessage());
        }

        @Test
        void throwsAiParseErrorWhenDaysEmpty() {
            TripPlan plan = new TripPlan("台北行", "summary", List.of());

            AiGenerationException ex = assertThrows(AiGenerationException.class,
                    () -> serviceWithValidator().validateAgainstContract(plan));

            assertEquals("AI_PARSE_ERROR", ex.getErrorCode());
        }

        @Test
        void throwsAiParseErrorWhenTitleBlank() {
            TripPlan plan = new TripPlan("", "summary", List.of(validDay()));

            AiGenerationException ex = assertThrows(AiGenerationException.class,
                    () -> serviceWithValidator().validateAgainstContract(plan));

            assertEquals("AI_PARSE_ERROR", ex.getErrorCode());
        }

        @Test
        void throwsAiParseErrorWhenActivityLocationBlank() {
            TripPlan plan = new TripPlan("台北行", null, List.of(
                    new DayPlan(1, null, List.of(
                            new Activity("09:00 - 10:00", "", "desc", "步行 5 分鐘", "景點", "🏛️")
                    ), null)
            ));

            assertThrows(AiGenerationException.class,
                    () -> serviceWithValidator().validateAgainstContract(plan));
        }

        @Test
        void messageCapsViolationsAtFive() {
            // 7 個 bad activity → 應該只列出前 5 筆
            List<Activity> bad = List.of(
                    new Activity("bad1", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad2", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad3", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad4", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad5", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad6", "Loc", "d", "步行 5 分鐘", "景點", "🏛️"),
                    new Activity("bad7", "Loc", "d", "步行 5 分鐘", "景點", "🏛️")
            );
            TripPlan plan = new TripPlan("台北行", null, List.of(
                    new DayPlan(1, null, bad, null)
            ));

            AiGenerationException ex = assertThrows(AiGenerationException.class,
                    () -> serviceWithValidator().validateAgainstContract(plan));

            // 訊息使用 "; " 串接最多 5 筆 → 分隔符數量 ≤ 4
            long separators = ex.getMessage().chars().filter(c -> c == ';').count();
            assertTrue(separators <= 4,
                    "expected at most 5 violations (≤4 separators), got: " + ex.getMessage());
        }

        private static TripPlan validPlan() {
            return new TripPlan("台北兩日遊", "輕鬆 city walk", List.of(
                    validDay(),
                    new DayPlan(2, "海岸線兜風", List.of(
                            new Activity("09:00 - 11:00", "淡水老街", "吃阿給", "步行 5 分鐘", "美食", "🍜")
                    ), null)
            ));
        }

        private static DayPlan validDay() {
            return new DayPlan(1, "西區散步日", List.of(
                    new Activity("09:00 - 10:30", "中正紀念堂", "歷史巡禮", "步行 5 分鐘", "景點", "🏛️")
            ), null);
        }
    }

    @Nested
    class BuildTransportInstruction {

        @Test
        void walkingDoesNotInclude20MinuteRule() {
            String result = GeminiItineraryService.buildTransportInstruction("walking");

            assertTrue(result.contains("步行"));
            assertTrue(result.contains("嚴格禁止"));
            assertFalse(result.contains("20 分鐘規則"),
                    "walking should not include 20-minute rule, got: " + result);
        }

        @Test
        void drivingIncludes20MinuteRule() {
            String result = GeminiItineraryService.buildTransportInstruction("driving");

            assertTrue(result.contains("開車"));
            assertTrue(result.contains("20 分鐘規則"));
        }

        @Test
        void transitIncludes20MinuteRule() {
            String result = GeminiItineraryService.buildTransportInstruction("transit");

            assertTrue(result.contains("公車") || result.contains("捷運"));
            assertTrue(result.contains("20 分鐘規則"));
        }

        @Test
        void throwsForInvalidMode() {
            // null fallback / default 已從 service 移除：controller 層 @NotNull + @Pattern 應該擋掉非法輸入
            assertThrows(IllegalArgumentException.class,
                    () -> GeminiItineraryService.buildTransportInstruction("invalid"));
        }

        @Test
        void throwsForNullMode() {
            // switch 在 null 上會拋 NullPointerException — 視為 defensive 行為
            assertThrows(NullPointerException.class,
                    () -> GeminiItineraryService.buildTransportInstruction(null));
        }
    }

    @Nested
    class RetryOnce {

        private static ValidatorFactory factory;
        private static Validator validator;

        @BeforeAll
        static void setUp() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void tearDown() {
            if (factory != null) factory.close();
        }

        // 合法的「一天」行程 JSON：enrichWithHotels 在 total < 2 會短路，不會呼叫 hotelService，
        // 因此建構 service 時 hotelService/hotelExecutor 傳 null 也安全。
        private static final String ONE_DAY_JSON = """
                {"title":"台北一日遊","summary":"輕鬆走",
                 "days":[{"day":1,"theme":"西區散步日","activities":[
                   {"time":"09:00 - 10:30","location":"中正紀念堂","description":"歷史巡禮",
                    "transport":"步行 5 分鐘","category":"景點","emoji":"🏛️"}]}]}
                """;

        // 用 deep stub 撐起 chatClient.prompt().system(..).user(..).call().content() 這條 fluent 鏈，
        // 對終端 content() 依序回傳 contentSequence（可為要丟出的 Throwable 或要回傳的字串）。
        private GeminiItineraryService serviceWith(Object first, Object second) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
            when(builder.build()).thenReturn(client);

            var stub = when(client.prompt().system(anyString()).user(anyString()).call().content());
            stub = applyStep(stub, first);
            applyStep(stub, second);

            return new GeminiItineraryService(builder, null, null, validator, roomyCallCounter());
        }

        private org.mockito.stubbing.OngoingStubbing<String> applyStep(
                org.mockito.stubbing.OngoingStubbing<String> stub, Object step) {
            return (step instanceof Throwable t) ? stub.thenThrow(t) : stub.thenReturn((String) step);
        }

        @Test
        void retriesOnceThenSucceeds() {
            // 第 1 次呼叫丟例外、第 2 次回合法 JSON → generate() 應在重試後成功
            GeminiItineraryService service =
                    serviceWith(new RuntimeException("Gemini 暫時掛掉"), ONE_DAY_JSON);

            TripPlan plan = service.generate("台北一日遊", "driving");

            assertNotNull(plan);
            assertEquals("台北一日遊", plan.title());
        }

        @Test
        void throwsAfterSecondFailure() {
            // 兩次都失敗 → 拋 AiGenerationException（證明只重試一次、不無限重試）
            GeminiItineraryService service =
                    serviceWith(new RuntimeException("first"), new RuntimeException("second"));

            AiGenerationException ex = assertThrows(AiGenerationException.class,
                    () -> service.generate("台北一日遊", "driving"));
            assertEquals("AI_ERROR", ex.getErrorCode());
        }
    }

    @Nested
    class DailyCallLimit {

        private static ValidatorFactory factory;
        private static Validator validator;

        @BeforeAll
        static void setUp() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void tearDown() {
            if (factory != null) factory.close();
        }

        // content() 每被呼叫一次就 +1 —— 那代表「真的打了一次 Gemini」。
        // 每個測試方法都是新的 instance，所以不必手動歸零。
        private final AtomicInteger geminiCalls = new AtomicInteger();
        // 計數器記在這裡：讀它的次數，就是計數器實際寫進資料表的次數
        private final InMemoryCounterRows rows = new InMemoryCounterRows();

        private PersistentCallCounter callCounterWithLimit(int dailyLimit) {
            return GeminiItineraryServiceTest.callCounterWithLimit(dailyLimit, rows);
        }

        // 與 RetryOnce.serviceWith 一樣用 deep stub 撐起 fluent 鏈；
        // contentSequence 依序是每次 content() 要丟出的 Throwable 或要回傳的字串。
        private GeminiItineraryService serviceWith(PersistentCallCounter counter, Object... contentSequence) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
            when(builder.build()).thenReturn(client);

            when(client.prompt().system(anyString()).user(anyString()).call().content())
                    .thenAnswer(invocation -> {
                        Object step = contentSequence[geminiCalls.getAndIncrement()];
                        if (step instanceof Throwable t) {
                            throw t;
                        }
                        return step;
                    });

            return new GeminiItineraryService(builder, null, null, validator, counter);
        }

        @Test
        void blockedCallNeverReachesGeminiAndIsNotRetried() {
            // 上限 0：第一次就被擋。重點不只是「丟了對的例外」，而是「一次都沒打到 Gemini」——
            // 只驗例外型別的話，先打出去再丟例外也會過，但額度已經燒掉了。
            GeminiItineraryService service = serviceWith(callCounterWithLimit(0));

            assertThrows(GeminiDailyLimitExceededException.class,
                    () -> service.generate("台北一日遊", "driving"));

            assertEquals(0, geminiCalls.get());
        }

        @Test
        void successfulFirstAttemptCountsOneCall() {
            GeminiItineraryService service = serviceWith(callCounterWithLimit(5), RetryOnce.ONE_DAY_JSON);

            service.generate("台北一日遊", "driving");

            assertEquals(1, rows.used(COUNTER_NAME, TODAY));
            assertEquals(1, geminiCalls.get());
        }

        @Test
        void retryAttemptCountsAsSecondCall() {
            // 這就是計數要放在呼叫點、不能放在 interceptor 的原因：
            // 使用者只送了一次請求，Gemini 卻被打了兩次。
            GeminiItineraryService service = serviceWith(
                    callCounterWithLimit(5), new RuntimeException("Gemini 暫時掛掉"), RetryOnce.ONE_DAY_JSON);

            service.generate("台北一日遊", "driving");

            assertEquals(2, rows.used(COUNTER_NAME, TODAY));
            assertEquals(2, geminiCalls.get());
        }

        @Test
        void retryIsBlockedWhenFirstAttemptUsedTheLastCall() {
            // 上限 1：第一次用掉最後一格且失敗 → 重試那一次被擋。
            // 對外是「今天已達上限」而不是「生成失敗」；Gemini 只被打了一次。
            GeminiItineraryService service =
                    serviceWith(callCounterWithLimit(1), new RuntimeException("Gemini 暫時掛掉"));

            assertThrows(GeminiDailyLimitExceededException.class,
                    () -> service.generate("台北一日遊", "driving"));

            assertEquals(1, geminiCalls.get());
        }
    }
}
