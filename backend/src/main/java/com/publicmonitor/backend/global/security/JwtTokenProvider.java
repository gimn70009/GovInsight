package com.publicmonitor.backend.global.security;

import com.publicmonitor.backend.domain.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

public class JwtTokenProvider {

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.clock = clock;
    }

    // 토큰 생성
    public String createAccessToken(User user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenExpiration());

        return Jwts.builder()
                .subject(user.getLoginId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    // 서명과 만료 여부 검증
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpirationSeconds() {
        return properties.accessTokenExpiration().toSeconds();
    }
}
