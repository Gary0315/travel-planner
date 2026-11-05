package com.travel.app.shared.exception;

/**
 * 今天的 Gemini 呼叫次數已達我們自訂的每日上限，這一次<b>沒有</b>發出呼叫。
 *
 * <p><b>刻意不繼承 {@link AiGenerationException}</b>：{@code GeminiItineraryService.generate}
 * 的自動重試只接那個型別。「額度沒了」重試一次不會變好，只會多一行誤導的重試 log，
 * 所以用獨立型別讓它直接穿過重試邏輯。
 *
 * <p>由 {@link GlobalExceptionHandler} 轉成 429 + {@code DAILY_QUOTA_EXCEEDED}。
 */
public class GeminiDailyLimitExceededException extends RuntimeException {

    public GeminiDailyLimitExceededException(int dailyLimit) {
        super("Gemini 每日呼叫上限已達（上限 " + dailyLimit + " 次/日）");
    }
}
