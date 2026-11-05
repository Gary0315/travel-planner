package com.travel.app.shared.exception;

/**
 * AI 生成流程中發生的可識別錯誤（例如 JSON 解析失敗）。
 * errorCode 供前端做差異化提示；message 為 log 用的技術描述，不對外揭露。
 */
public class AiGenerationException extends RuntimeException {

    private final String errorCode;

    public AiGenerationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
