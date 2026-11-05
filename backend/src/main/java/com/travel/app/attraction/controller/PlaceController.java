package com.travel.app.attraction.controller;

import com.travel.app.attraction.dto.PlacePhotoBytes;
import com.travel.app.attraction.dto.PlacePhotoResponse;
import com.travel.app.attraction.service.PlaceService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping("/photo")
    public PlacePhotoResponse getPhoto(@RequestParam String name) {
        return placeService.getPhoto(name);
    }

    /**
     * 允許索取的縮圖寬度白名單。
     *
     * <p><b>不可改成接受任意值。</b>每個相異的寬度都是一次獨立的 Google Place Photos
     * 計費呼叫、也是一筆獨立快取，開放任意值等於任何人都能用
     * {@code ?w=1}、{@code ?w=2}、{@code ?w=3}… 無上限地燒掉免費額度。
     *
     * <p>200 給 {@code HotelMini} 的 72px 縮圖（2x DPR 之下仍有餘裕）；
     * 600 給 {@code Polaroid} 與點開放大的 lightbox，同時是省略 {@code w} 時的預設值
     * （舊網址與已快取的連結因此行為不變）。
     */
    private static final Set<Integer> ALLOWED_WIDTHS = Set.of(200, 600);

    // 後端 proxy 圖片 bytes。直接給瀏覽器當 <img src>。
    // 同 origin → 不會被 Chrome ORB 擋；附 Cache-Control 讓瀏覽器自己 cache，省 Place Photos 計費。
    @GetMapping("/photo/binary")
    public ResponseEntity<byte[]> getPhotoBinary(
            @RequestParam String name,
            @RequestParam(name = "w", defaultValue = "600") int width) {
        if (!ALLOWED_WIDTHS.contains(width)) {
            // 刻意回 400 而非默默夾到預設值：夾值會讓「打錯的網址」看起來正常運作，
            // 之後沒人會發現白名單被繞過的嘗試。400 不帶 Cache-Control，
            // 因此 Cloudflare 的 Edge TTL（bypass if not present）也不會快取它。
            return ResponseEntity.badRequest().build();
        }

        PlacePhotoBytes photo = placeService.getPhotoBytes(name, width);
        if (photo == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(photo.contentType());
        } catch (Exception e) {
            mediaType = MediaType.IMAGE_JPEG;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(photo.bytes());
    }
}
