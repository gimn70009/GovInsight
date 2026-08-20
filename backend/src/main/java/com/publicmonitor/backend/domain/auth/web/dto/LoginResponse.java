package com.publicmonitor.backend.domain.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT 로그인 응답")
public record LoginResponse(
        @Schema(description = "API 인증에 사용할 JWT 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,

        @Schema(description = "Authorization 헤더에 사용하는 토큰 유형", example = "Bearer")
        String tokenType,

        @Schema(description = "액세스 토큰의 남은 유효 시간(초)", example = "3600")
        long expiresIn
) {
}
