package com.travel.app.attraction.service;

import com.travel.app.attraction.dto.DayPlan;
import com.travel.app.attraction.dto.RawDayPlan;
import com.travel.app.attraction.dto.RawTripPlan;
import com.travel.app.attraction.dto.TripPlan;
import com.travel.app.shared.exception.AiGenerationException;
import com.travel.app.shared.exception.GeminiDailyLimitExceededException;
import com.travel.app.shared.quota.PersistentCallCounter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GeminiItineraryService implements ItineraryService {

    private static final Logger log = LoggerFactory.getLogger(GeminiItineraryService.class);

    private final ChatClient chatClient;
    private final HotelService hotelService;
    private final ExecutorService hotelExecutor;
    private final Validator validator;
    private final PersistentCallCounter geminiCallCounter;

    public GeminiItineraryService(ChatClient.Builder chatClientBuilder,
                                   HotelService hotelService,
                                   ExecutorService hotelExecutor,
                                   Validator validator,
                                   @Qualifier("geminiCallCounter") PersistentCallCounter geminiCallCounter) {
        this.chatClient = chatClientBuilder.build();
        this.hotelService = hotelService;
        this.hotelExecutor = hotelExecutor;
        this.validator = validator;
        this.geminiCallCounter = geminiCallCounter;
    }

    @Override
    public TripPlan generate(String message, String transportMode) {
        BeanOutputConverter<RawTripPlan> converter = new BeanOutputConverter<>(RawTripPlan.class);
        String format = converter.getFormat();

        String transportInstruction = buildTransportInstruction(transportMode);

        String systemPrompt = """
                你是一位專業的台灣在地導遊與路徑優化專家。

                生成策略：
                - 根據使用者的自然語言需求（包含天數、地點、偏好），動態規劃對應天數的行程，並為行程下一個吸睛的 title 產出行程總標題。
                - 同時為整段行程生成一個 `summary` 行程簡介，1-2 句、控制在 80 字以內，描繪整體調性或亮點（例如：「走進台中西區的文青角落，沿著草悟道與審計新村慢慢吃慢慢逛。」）。
                - 每天請給一個 4-8 字的「當日主題」(theme)，用來概括當天行程氛圍，例如「西區散步日」、「海岸線兜風」、「老街美食日」。
                - 每天推薦適當數量的景點或餐廳，且必須符合「真實地理位置順序」，絕對避免不合邏輯的折返跑。
                - 每個 Activity 必須附上一個能反映該景點/餐廳特性的視覺 `emoji`（例如：夜市 🌃、咖啡店 ☕、海邊 🌊、博物館 🖼️、夜景 🌃、老街 🏮、溫泉 ♨️、書店 📚）。emoji 必須為單一字元的 emoji，不可填寫文字或表情符號組合。
                - 內容必須是繁體中文 (zh-TW)。

                【出發地規則】
                - 若使用者指定了出發地（例如「從彰化火車站出發」），該出發地僅作為路線規劃的起始參考點。
                - 出發地『絕對不可以』列為行程中的第一個 Activity。行程的第一筆 Activity 必須是真正的第一個景點或目的地。
                - 出發地到第一個景點的交通方式，請填入第一筆 Activity 前面不存在的 transport，亦即第一筆 Activity 本身的 transport 欄位填的是「離開第一個景點前往第二個景點」的方式。

                【交通方式限制】
                %s

                【極度重要】行程時間必須包含交通緩衝！
                - 每個活動的 transport 欄位記錄了「前往下一站」所需的交通時間（例如「步行 15 分鐘」、「開車 30 分鐘」）。
                - 下一個活動的開始時間 = 上一個活動的結束時間 + transport 欄位中的交通時間。
                - 範例：活動 A 在 11:00 結束，transport 為「步行 30 分鐘」，則活動 B 的開始時間必須是 11:30，絕對不可以是 11:00。
                - 行程之間嚴禁時間重疊或無縫接軌，每兩個活動之間必須保留真實的交通緩衝時間。

                格式要求：
                - 嚴格輸出符合 Data Contract 定義的 JSON。
                - 絕對不要包含任何 Markdown 標記（例如 ```json）或額外的問候語/廢話。
                - 請確保回覆第一個字元就是 '{'，最後一個字元就是 '}'，整段內容只能包含 JSON。
                - Activity 的 time 欄位格式必須嚴格遵守 HH:mm - HH:mm（24 小時制，例如 09:00 - 10:30），禁止使用其他格式。

                期望 JSON 格式：
                %s
                """.formatted(transportInstruction, format);

        String userPrompt = """
                使用者需求：
                %s
                """.formatted(message);

        // 呼叫 Gemini 並解析成 RawTripPlan；失敗（呼叫錯誤/超時或 JSON 解析失敗）自動重試一次。
        // enrichWithHotels 與 validateAgainstContract 刻意留在重試迴圈外：
        // 重試絕不會重打 Places API（保護免費額度），語意驗證也只在最終結果跑一次。
        // 每日呼叫上限已滿時 callAndParse 丟的是 GeminiDailyLimitExceededException（不是 AiGenerationException），
        // 下面的 catch 接不到 —— 第一次就被擋不會重試；重試那次被擋也直接往外丟。
        RawTripPlan raw;
        try {
            raw = callAndParse(systemPrompt, userPrompt, converter);
        } catch (AiGenerationException first) {
            log.warn("AI 生成第 1 次失敗（errorCode={}），自動重試一次", first.getErrorCode());
            raw = callAndParse(systemPrompt, userPrompt, converter);
            log.info("AI 生成於重試後成功");
        }

        TripPlan plan = enrichWithHotels(raw);
        validateAgainstContract(plan);
        return plan;
    }

    /**
     * 呼叫 Gemini 並把回應解析成 {@link RawTripPlan}。失敗一律以 {@link AiGenerationException}
     * 表達（errorCode：AI_TIMEOUT / AI_ERROR / AI_PARSE_ERROR），供 {@link #generate} 的重試邏輯辨識。
     * 本方法不含 hotel enrich 與 contract 驗證，因此重試時不會重複消耗 Places 額度。
     *
     * <p>每次進來都先向每日的 {@link PersistentCallCounter} 記一次呼叫；今天的上限已滿則丟
     * {@link GeminiDailyLimitExceededException}，不發出呼叫。
     */
    private RawTripPlan callAndParse(String systemPrompt, String userPrompt,
                                     BeanOutputConverter<RawTripPlan> converter) {
        // 每日呼叫上限：在「真的要發出去」之前記數，重試那一次也照記 ——
        // Google 計的是實際呼叫數，不是使用者的請求數。
        // 必須放在下面的 supplyAsync 之外：在裡面丟的例外會被包成 CompletionException，
        // 被下方的 catch 改寫成 AI_ERROR，generate() 就會對「額度沒了」再重試一次。
        if (!geminiCallCounter.tryAcquire()) {
            // 上限用盡是需要人介入的事件（調高上限或查是否被濫用），用 WARN 讓它在 Render log 裡看得見。
            // 不記 prompt —— 同本類別既有約定。
            log.warn("Gemini 每日呼叫上限已達（上限 {} 次/日），本次不發出呼叫", geminiCallCounter.limit());
            throw new GeminiDailyLimitExceededException(geminiCallCounter.limit());
        }

        // Gemini API 無原生 HTTP timeout 設定（Spring AI 1.1.0 Google GenAI 不支援 YAML 設定，
        // 底層 SDK 的 HttpOptions.timeout 有已知 bug）。
        // 改在 service 層加 orTimeout：60 秒後強制釋放 calling thread，
        // 防止 Gemini 慢回應時 servlet thread 被無限期佔用。
        String response;
        try {
            response = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content())
                    .orTimeout(60, TimeUnit.SECONDS)
                    .join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                throw new AiGenerationException("AI_TIMEOUT", "Gemini API 回應超時（60 秒）", cause);
            }
            throw new AiGenerationException("AI_ERROR", "Gemini API 呼叫失敗：" + cause.getMessage(), cause);
        }

        // 注意：不記錄 user prompt（含使用者個資輸入）及完整 Gemini 回應（可能含個資）。
        // 僅在 debug 層級記錄 response 長度供診斷用。
        log.debug("Gemini response length: {}", response == null ? 0 : response.length());

        String json = extractJsonObject(response);
        try {
            return converter.convert(json);
        } catch (Exception e) {
            String truncated = response == null ? null : response.substring(0, Math.min(800, response.length()));
            throw new AiGenerationException(
                    "AI_PARSE_ERROR",
                    "Failed to parse TripPlan JSON from Gemini response. Raw response (truncated): " + truncated,
                    e
            );
        }
    }

    /**
     * 用 Bean Validation 把 Gemini 生成的 TripPlan 對照 /save 端點的同一份 contract 跑一遍。
     * 失敗時拋 AI_PARSE_ERROR：與「JSON parse 失敗」共用前端錯誤路徑，
     * 避免使用者拿到看似成功的行程，按下儲存才在 /save 被 @Valid 退回 400 的窘境。
     */
    // package-private for unit testing
    void validateAgainstContract(TripPlan plan) {
        Set<ConstraintViolation<TripPlan>> violations = validator.validate(plan);
        if (violations.isEmpty()) {
            return;
        }
        String summary = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .limit(5)
                .collect(Collectors.joining("; "));
        throw new AiGenerationException(
                "AI_PARSE_ERROR",
                "Generated TripPlan failed Bean Validation: " + summary,
                null
        );
    }

    /**
     * 對兩天以上的行程，逐天根據「最後一個 activity」推薦附近飯店；最後一天不推薦（旅客通常離開）。
     * 各天平行查詢以縮短延遲。
     */
    private TripPlan enrichWithHotels(RawTripPlan raw) {
        List<RawDayPlan> days = raw.days() == null ? List.of() : raw.days();
        int total = days.size();

        if (total < 2) {
            return new TripPlan(raw.title(), raw.summary(), days.stream()
                    .map(d -> new DayPlan(d.day(), d.theme(), d.activities(), null))
                    .toList());
        }

        List<CompletableFuture<DayPlan>> futures = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            RawDayPlan rd = days.get(i);
            boolean isLastDay = (i == total - 1);
            String lastLocation = (rd.activities() == null || rd.activities().isEmpty())
                    ? null
                    : rd.activities().get(rd.activities().size() - 1).location();

            if (isLastDay || lastLocation == null) {
                futures.add(CompletableFuture.completedFuture(
                        new DayPlan(rd.day(), rd.theme(), rd.activities(), null)));
            } else {
                futures.add(CompletableFuture
                        .supplyAsync(() -> hotelService.findNearbyHotels(lastLocation), hotelExecutor)
                        .orTimeout(10, TimeUnit.SECONDS)
                        .thenApply(hotels -> new DayPlan(
                                rd.day(),
                                rd.theme(),
                                rd.activities(),
                                (hotels == null || hotels.isEmpty()) ? null : hotels
                        )));
            }
        }

        boolean placesUnavailable = false;
        List<DayPlan> enriched = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            try {
                enriched.add(futures.get(i).join());
            } catch (Exception e) {
                if (isPlacesUnavailable(e)) {
                    // 額度耗盡／授權失敗：詳細 status 與 error_message 已由 HotelServiceImpl 記過，
                    // 這裡只記受影響的天，並把旗標帶回 TripPlan 讓前端說得出「暫時查不到」。
                    placesUnavailable = true;
                    log.warn("Hotel enrichment skipped for day {} (Places unavailable)", days.get(i).day());
                } else {
                    log.warn("Hotel enrichment failed for day {}: {}", days.get(i).day(), e.getMessage());
                }
                // 該天回退為無飯店；不影響其他天，更不該讓整趟生成失敗
                RawDayPlan rd = days.get(i);
                enriched.add(new DayPlan(rd.day(), rd.theme(), rd.activities(), null));
            }
        }
        // 沒退化時給 null 而不是 false —— TripPlan 的 @JsonInclude(NON_NULL) 會整個略掉這個 key，
        // 正常回應的 JSON 形狀因此完全不變
        return new TripPlan(raw.title(), raw.summary(), enriched,
                placesUnavailable ? Boolean.TRUE : null);
    }

    /**
     * {@code CompletableFuture.join()} 會把原始例外包成 {@code CompletionException}，
     * 所以要沿著 cause 鏈找。迴圈同時防自我參照的 cause（理論上不該發生，但不值得為它 StackOverflow）。
     */
    private static boolean isPlacesUnavailable(Throwable t) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof PlacesUnavailableException) {
                return true;
            }
        }
        return false;
    }

    /**
     * transportMode 在 controller 層由 {@code @NotNull} + {@code @Pattern(walking|driving|transit)}
     * 把關，到這裡保證為三者之一。default 分支只是 defensive，正常路徑不會走到。
     */
    static String buildTransportInstruction(String transportMode) {
        String rule20min = """
                【20 分鐘規則】：
                - 無論使用者選擇何種主要交通方式，若兩個景點之間的步行時間『小於等於 20 分鐘』，\
                請一律優先安排步行（原因：找車位不便、捷運站間距離近，步行更實際）。
                - 若步行時間『超過 20 分鐘』，才切換回使用者指定的交通工具。
                """;

        return switch (transportMode) {
            case "walking" -> """
                    使用者偏好【步行】。所有景點之間僅能步行，單段步行時間請控制在 30 分鐘以內，\
                    transport 欄位只能填寫「步行 X 分鐘」。
                    【嚴格禁止】步行模式下，transport 欄位絕對不可以出現任何交通工具（火車、開車、公車、捷運等）。
                    """;
            case "driving" -> """
                    使用者偏好【開車/自駕】。所有景點之間的移動一律使用開車，transport 欄位填寫開車時間（含停車），\
                    例如「開車 15 分鐘」，可規劃距離較遠的景點。
                    【嚴格禁止】開車模式下，transport 欄位絕對不可以出現「火車」、「台鐵」、「高鐵」、「捷運」、「公車」等大眾運輸字眼。\
                    即使起點或終點是火車站，移動方式也必須是開車。
                    """ + rule20min;
            case "transit" -> """
                    使用者偏好【大眾交通工具】。跨區域移動請搭乘公車、捷運、台鐵或高鐵，\
                    transport 欄位填寫搭乘時間，例如「搭捷運 10 分鐘」或「搭公車 20 分鐘」，\
                    並優先選擇大眾運輸可到達的景點。
                    【嚴格禁止】大眾交通模式下，transport 欄位絕對不可以出現「開車」、「自駕」、「計程車」等私人車輛字眼。
                    """ + rule20min;
            default -> throw new IllegalArgumentException(
                    "Invalid transportMode: " + transportMode
                            + " (controller @Pattern should have caught this before reaching service)");
        };
    }

    /**
     * BeanOutputConverter 期望輸入是「純 JSON」。
     * 若模型在 JSON 外包了雜訊文字或 code fence，這裡會擷取真正的 JSON 區塊。
     */
    // package-private for unit testing
    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }

        String cleaned = text.trim();
        // Remove typical markdown code fences if present.
        cleaned = cleaned.replaceAll("^```json\\s*", "");
        cleaned = cleaned.replaceAll("^```\\s*", "");
        cleaned = cleaned.replaceAll("\\s*```$", "");

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}
