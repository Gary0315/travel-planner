package com.travel.app.shared.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 輕量健康檢查（PLAN S2 前置）：前端載入時輪詢此端點，判斷後端是否已從 cold start 醒來。
 * 刻意不碰 DB 與外部 API——它只回答「Spring 起來了沒」，必須零成本、永不失敗。
 */
@RestController
public class HealthController {

    record HealthResponse(String status) {}

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}
