package com.publicmonitor.backend.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenExpiration
) {
    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.jwt.secret 설정이 필요합니다.");
        }
        if (accessTokenExpiration == null || accessTokenExpiration.isNegative() || accessTokenExpiration.isZero()) {
            throw new IllegalArgumentException("app.jwt.access-token-expiration은 0보다 커야 합니다.");
        }
    }
}
