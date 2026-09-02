package com.publicmonitor.backend.domain.telegram;

public class TelegramClientException extends RuntimeException {

    public TelegramClientException(String message, Throwable cause) {
        super(message, cause);
    }
}