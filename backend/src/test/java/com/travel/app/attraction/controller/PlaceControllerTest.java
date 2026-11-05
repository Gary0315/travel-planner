package com.travel.app.attraction.controller;

import com.travel.app.attraction.dto.PlacePhotoBytes;
import com.travel.app.attraction.dto.PlacePhotoResponse;
import com.travel.app.attraction.service.PlaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PlaceController} 的縮圖寬度白名單。
 *
 * <p><b>這是額度防護的邊界，不只是參數驗證。</b>每個相異的 {@code w} 值都會產生一次獨立的
 * Google Place Photos 計費呼叫、也是一筆獨立快取，若開放任意值，任何人都能用
 * {@code ?w=1}、{@code ?w=2}、{@code ?w=3}… 無上限地燒掉免費額度。用測試釘住，避免日後
 * 有人為了「多支援一個尺寸」順手改成接受任意數字。
 *
 * <p>用 standaloneSetup（同 {@code ClientErrorControllerTest} / {@code HealthControllerTest}）：
 * 測的是 controller 自己的把關，不需要真的 context，也不會碰到 Google。
 */
class PlaceControllerTest {

    /** 記錄真正傳到 service 的寬度，用來證明「被擋下的請求沒有走到下游」。 */
    private final List<Integer> requestedWidths = new ArrayList<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestedWidths.clear();
        PlaceService fake = new PlaceService() {
            @Override
            public PlacePhotoResponse getPhoto(String name) {
                return new PlacePhotoResponse("/api/places/photo/binary?name=x", null, null, null);
            }

            @Override
            public PlacePhotoBytes getPhotoBytes(String name, int maxWidth) {
                requestedWidths.add(maxWidth);
                return new PlacePhotoBytes(new byte[]{1, 2, 3}, "image/jpeg");
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(new PlaceController(fake)).build();
    }

    @Test
    void allowed_widths_pass_through() throws Exception {
        mockMvc.perform(get("/api/places/photo/binary").param("name", "審計新村").param("w", "200"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/places/photo/binary").param("name", "審計新村").param("w", "600"))
                .andExpect(status().isOk());

        assertThat(requestedWidths).containsExactly(200, 600);
    }

    @Test
    void missing_width_defaults_to_600() throws Exception {
        mockMvc.perform(get("/api/places/photo/binary").param("name", "審計新村"))
                .andExpect(status().isOk());

        // 沒帶 w 的舊網址行為必須不變，否則所有既有的瀏覽器與 Cloudflare 邊緣快取會一次失效
        assertThat(requestedWidths).containsExactly(600);
    }

    @Test
    void width_outside_whitelist_is_rejected_and_never_reaches_service() throws Exception {
        for (String w : List.of("1", "199", "601", "10000", "-1", "0")) {
            mockMvc.perform(get("/api/places/photo/binary").param("name", "審計新村").param("w", w))
                    .andExpect(status().isBadRequest());
        }

        // 關鍵斷言：被擋下的請求「一次都沒有」走到 service。
        // 只驗回應是 400 並不足以證明沒燒到額度 —— 有可能先呼叫了 Google 才回錯誤碼。
        assertThat(requestedWidths).isEmpty();
    }
}
