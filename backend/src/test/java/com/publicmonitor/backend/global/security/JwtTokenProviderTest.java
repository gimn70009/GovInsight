package com.publicmonitor.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.user.entity.Role;
import com.publicmonitor.backend.domain.user.entity.User;
import io.jsonwebtoken.Claims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    @Test
    void 사용자_정보로_서명된_토큰을_생성하고_검증한다() {
        JwtTokenProvider provider = new JwtTokenProvider(
                new JwtProperties(
                        "VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
                        Duration.ofHours(1)
                ),
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC)
        );
        User user = User.create("admin", "encoded-password", Role.ADMIN);

        String token = provider.createAccessToken(user);
        Claims claims = provider.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(Instant.parse("2026-08-07T01:00:00Z"));
    }
}
