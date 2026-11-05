package com.travel.app.shared.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 對外統一的通用錯誤訊息：絕不洩漏內部錯誤細節（避免曝露 stack trace、prompt 原文、SQL 等敏感資訊）
    private static final String GENERIC_ERROR_MESSAGE = "系統暫時無法處理，請稍後再試";

    // AI 生成失敗（service 層已自動重試一次後仍失敗）對外訊息：親友看得懂、可行動（再試一次）
    private static final String AI_ERROR_MESSAGE = "行程生成失敗，請再試一次";

    // 每日上限用盡的對外訊息與代碼：刻意與 DailyQuotaInterceptor（全站每日請求上限）一字不差。
    // 對使用者來說兩者是同一件事（今天不能再生成了）；而前端只認這個 errorCode（App.tsx），
    // 換一個新代碼會落到「請求太頻繁，請等待 60 秒」的倒數分支。
    private static final String DAILY_QUOTA_MESSAGE = "今天的行程生成次數已達上限，請明天再試";
    private static final String DAILY_QUOTA_ERROR_CODE = "DAILY_QUOTA_EXCEEDED";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        // ResponseStatusException 的 reason 是業務層「主動」設定的訊息（例如 "Itinerary not found"），
        // 對外揭露是 OK 的；通用 Exception 的訊息則不可外露
        Map<String, String> body = Map.of(
                "message", ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString()
        );
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /**
     * 沒對應 static resource 的 GET（例如瀏覽器自動找 /favicon.ico）：
     * 直接回 404，不印 ERROR log。Spring 預設會讓這類 exception 一路噴到 generic handler
     * 把整個 stack trace 印成 ERROR，污染 log 也誤導排查方向。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(AiGenerationException.class)
    public ResponseEntity<Map<String, String>> handleAiGeneration(AiGenerationException ex) {
        // 技術細節只進 log；前端拿到 errorCode 做差異化提示
        log.error("AI generation error [{}]", ex.getErrorCode(), ex);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("errorCode", ex.getErrorCode());
        body.put("message", AI_ERROR_MESSAGE);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(GeminiDailyLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleGeminiDailyLimit(GeminiDailyLimitExceededException ex) {
        // 不在這裡印 log：這是預期中的守門結果、不是故障，不需要 stack trace；
        // WARN 已在 GeminiItineraryService 拒絕的當下印過一次。
        // 刻意不帶 retryAfterSeconds：前端看到它會啟動秒數倒數，而這裡要等的是「到明天」。
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", DAILY_QUOTA_MESSAGE);
        body.put("errorCode", DAILY_QUOTA_ERROR_CODE);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        // 真正的錯誤詳情只進 log，方便排查；前端只看到通用訊息
        log.error("Unhandled exception", ex);
        Map<String, String> body = Map.of("message", GENERIC_ERROR_MESSAGE);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
