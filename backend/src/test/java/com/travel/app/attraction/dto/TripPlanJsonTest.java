package com.travel.app.attraction.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住 {@code TripPlan.hotelsUnavailable} 的「只出不進、不落 DB」契約。
 *
 * <p>這個旗標是給 /generate 回應用的即時狀態（本次查詢時 Places 額度耗盡／授權失敗），
 * <b>不是行程本身的屬性</b>。{@code ItinerarySaveServiceImpl.save()} 是直接
 * {@code objectMapper.writeValueAsString(tripPlan)} 把整包存進 DB，所以只要旗標混進去，
 * 之後任何人打開那個分享連結都會看到一句永遠過期的提示。
 *
 * <p>擋住它的是 {@code @JsonProperty(access = READ_ONLY)} ＋ record 上的
 * {@code @JsonInclude(NON_NULL)}。兩者都是宣告式的、很容易在改 DTO 時被順手拿掉，
 * 所以用測試釘住行為而不是只靠註解。
 *
 * <p>這裡用裸的 {@code ObjectMapper} 而非 Spring 配置好的那顆：兩者在本測試關心的行為上一致，
 * 差別只在 Boot 預設關掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}（更寬鬆，不會讓本測試假性通過）。
 * 換成 slice test 要載入 context，對這個純序列化契約不划算。
 */
class TripPlanJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 帶旗標的 /save payload —— 這是真的做得到的事：/save 收的就是 @RequestBody TripPlan。 */
    private static final String INCOMING_WITH_FLAG = """
            {
              "title": "台中兩天一夜",
              "days": [
                {"day": 1, "activities": [{"time": "09:00 - 10:30", "location": "審計新村"}]}
              ],
              "hotelsUnavailable": true
            }
            """;

    private static TripPlan planWith(Boolean hotelsUnavailable) {
        return new TripPlan("台中兩天一夜", "測試用行程", List.of(
                new DayPlan(1, "西區散步日",
                        List.of(new Activity("09:00 - 10:30", "審計新村", null, null, "景點")),
                        null)
        ), hotelsUnavailable);
    }

    @Test
    void flag_is_serialized_when_true() throws Exception {
        String json = mapper.writeValueAsString(planWith(Boolean.TRUE));

        assertThat(json).contains("\"hotelsUnavailable\":true");
    }

    @Test
    void flag_key_is_absent_when_null() throws Exception {
        String json = mapper.writeValueAsString(planWith(null));

        // 沒退化時 JSON 形狀要跟加這個欄位之前完全一樣
        assertThat(json).doesNotContain("hotelsUnavailable");
    }

    @Test
    void flag_is_ignored_when_deserializing() throws Exception {
        TripPlan parsed = mapper.readValue(INCOMING_WITH_FLAG, TripPlan.class);

        assertThat(parsed.hotelsUnavailable()).isNull();
        // 其餘欄位照常吃進來，確認上面那條不是因為整包解析失敗
        assertThat(parsed.title()).isEqualTo("台中兩天一夜");
        assertThat(parsed.days()).hasSize(1);
    }

    @Test
    void flag_never_reaches_stored_content() throws Exception {
        // 這兩行就是 ItinerarySaveServiceImpl.save() 實際做的事：收 payload → 寫成字串進 DB
        TripPlan received = mapper.readValue(INCOMING_WITH_FLAG, TripPlan.class);
        String stored = mapper.writeValueAsString(received);

        assertThat(stored).doesNotContain("hotelsUnavailable");
    }
}
