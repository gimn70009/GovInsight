package com.publicmonitor.backend.domain.email.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmailRetryRequest(
        @NotBlank @Size(max = 254) @jakarta.validation.constraints.Email String expectedAddress,
        @NotNull @Min(0) Integer expectedAttemptCount
) {
}
