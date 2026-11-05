package com.travel.app.attraction.controller;

import com.travel.app.attraction.dto.GenerateItineraryRequest;
import com.travel.app.attraction.dto.ShareIdResponse;
import com.travel.app.attraction.dto.TripPlan;
import com.travel.app.attraction.service.ItineraryService;
import com.travel.app.attraction.service.ItinerarySaveService;
import com.travel.app.shared.exception.AiGenerationException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/itinerary")
public class ItineraryController {

    private static final Logger log = LoggerFactory.getLogger(ItineraryController.class);

    private final ItineraryService itineraryService;
    private final ItinerarySaveService itinerarySaveService;

    public ItineraryController(ItineraryService itineraryService, ItinerarySaveService itinerarySaveService) {
        this.itineraryService = itineraryService;
        this.itinerarySaveService = itinerarySaveService;
    }

    /**
     * 生成行程。
     *
     * <p>每次呼叫記一條 {@code [itinerary-gen]} 統計 log（M3 可觀測性的最小版）——在這之前，
     * 成功的生成完全不留痕跡：`application.yml` 沒設 logging，root 吃預設 INFO，而
     * {@code GeminiItineraryService} 唯一的 INFO 是「重試後成功」，只在重試時才印。
     * 於是「昨天幾次生成、成功率多少」在正式站根本答不出來（2026-08-06 查證）。
     *
     * <p>失敗也要記：失敗同樣燒掉 Gemini 額度，而且 S3 的自動重試會讓那次呼叫變成兩次。
     * 這與 {@code GlobalExceptionHandler} 的 log.error 重複，但兩者定位不同——那邊是
     * 診斷用（帶 stack trace），這邊是統計用（同前綴，好 grep 好數）。
     *
     * <p>刻意不記 message 內容（使用者輸入可能含個資，同 {@code GeminiItineraryService}
     * 的既有約定），也不記 IP。要精確區分來源得等 M3 完整版。
     */
    @PostMapping("/generate")
    public TripPlan generate(@Valid @RequestBody GenerateItineraryRequest request,
                             Authentication authentication) {
        long startNanos = System.nanoTime();
        Long userId = currentUserId(authentication);
        try {
            TripPlan plan = itineraryService.generate(request.message(), request.transportMode());
            log.info("[itinerary-gen] ok days={} elapsedMs={} transport={} userId={}",
                    plan.days().size(), elapsedMs(startNanos),
                    request.transportMode(), userId);
            return plan;
        } catch (RuntimeException e) {
            String reason = (e instanceof AiGenerationException aiError)
                    ? aiError.getErrorCode()
                    : e.getClass().getSimpleName();
            log.info("[itinerary-gen] fail reason={} elapsedMs={} transport={} userId={}",
                    reason, elapsedMs(startNanos),
                    request.transportMode(), userId);
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 儲存行程。
     *
     * - 已登入：把 userId 寫進 itinerary.user_id；之後該行程會出現在使用者的「我的行程」
     * - 未登入：userId = null，行程仍可分享（透過 shareId 連結）但不屬於任何人
     */
    @PostMapping("/save")
    public ShareIdResponse save(@Valid @RequestBody TripPlan tripPlan, Authentication authentication) {
        Long userId = currentUserId(authentication);
        return itinerarySaveService.save(tripPlan, userId);
    }

    @GetMapping("/{shareId}")
    public TripPlan getByShareId(@PathVariable String shareId) {
        return itinerarySaveService.findByShareId(shareId);
    }

    /**
     * 刪除一筆已分享的行程。Token 透過 X-Delete-Token header 帶入（DELETE 不附 body 較標準），
     * 與 save 回應的明文 deleteToken 比對 SHA-256 hash。詳見 SPEC §16。
     */
    @DeleteMapping("/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String shareId,
                       @RequestHeader("X-Delete-Token") String deleteToken) {
        itinerarySaveService.deleteByShareId(shareId, deleteToken);
    }

    /**
     * 取當前 userId，未登入回 null（匿名儲存仍然合法）。
     * 跟 MyTripsController / AuthController 同邏輯；之後抽到 shared util。
     */
    private static Long currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) return id;
        return null;
    }
}
