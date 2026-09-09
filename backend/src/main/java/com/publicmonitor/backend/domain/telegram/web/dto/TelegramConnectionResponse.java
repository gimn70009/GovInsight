package com.publicmonitor.backend.domain.telegram.web.dto;

import java.time.LocalDateTime;

public record TelegramConnectionResponse(
        boolean botConnected, boolean chatConnected, String botName, String botUsername,
        String chatTitle, String chatType, String message, LocalDateTime checkedAt
) {
}
