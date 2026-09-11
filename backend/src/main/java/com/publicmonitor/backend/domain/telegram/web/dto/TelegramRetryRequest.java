package com.publicmonitor.backend.domain.telegram.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TelegramRetryRequest(
        @NotBlank @Size(max = 100) String expectedChatId,
        @NotNull @Min(0) Integer expectedAttemptCount
) {
}
