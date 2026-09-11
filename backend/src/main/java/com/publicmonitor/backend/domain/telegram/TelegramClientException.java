package com.publicmonitor.backend.domain.telegram;

public class TelegramClientException extends RuntimeException {
    private final Integer statusCode;

    public TelegramClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }
    public TelegramClientException(String message, int statusCode) {
        super(message, null);
        this.statusCode = statusCode;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
