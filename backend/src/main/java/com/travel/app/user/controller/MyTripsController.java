package com.travel.app.user.controller;

import com.travel.app.attraction.dto.MyTripCardResponse;
import com.travel.app.attraction.service.ItinerarySaveService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 當前使用者的「我的行程」清單。
 *
 * - 未登入 → 401（前端 MyTripsPage 看到 401 就改顯示「請先登入」空狀態）
 * - 已登入 → 回傳該使用者所有 itinerary 的卡片 metadata（最新儲存在前）
 *
 * 路徑刻意用 /api/users/me/...，跟 /api/auth/me 一致語義（"me" = 當前使用者）。
 * 之後加「我的收藏 / 黑名單」等個人化清單時也走這個 prefix。
 */
@RestController
@RequestMapping("/api/users/me")
public class MyTripsController {

    private final ItinerarySaveService itinerarySaveService;

    public MyTripsController(ItinerarySaveService itinerarySaveService) {
        this.itinerarySaveService = itinerarySaveService;
    }

    @GetMapping("/trips")
    public List<MyTripCardResponse> listMyTrips(Authentication authentication) {
        Long userId = currentUserId(authentication);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return itinerarySaveService.listMine(userId);
    }

    /**
     * 從 Spring Security 的 Authentication 取出當前 userId。
     * 跟 AuthController.currentUserId 同邏輯 —— 為了不引入跨 controller 依賴，暫時複製一份；
     * 之後若有第三個地方用，再抽到 shared util。
     */
    private static Long currentUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) return id;
        return null;
    }
}
