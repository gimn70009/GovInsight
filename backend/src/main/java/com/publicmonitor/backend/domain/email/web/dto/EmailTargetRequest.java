package com.publicmonitor.backend.domain.email.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailTargetRequest(@NotBlank @Size(max = 254) @jakarta.validation.constraints.Email String expectedAddress) {
}
