package com.publicmonitor.backend.domain.auth.web.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
