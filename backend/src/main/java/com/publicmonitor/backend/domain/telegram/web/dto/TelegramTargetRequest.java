package com.publicmonitor.backend.domain.telegram.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TelegramTargetRequest(@NotBlank @Size(max = 100) String expectedChatId) {
}
