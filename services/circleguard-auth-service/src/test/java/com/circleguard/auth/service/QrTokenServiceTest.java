package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QrTokenServiceTest {

    private static final String SECRET = "unit-test-qr-secret-with-at-least-32-bytes";

    private static Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    @Test
    void generateQrToken_subjectIsAnonymousId() {
        QrTokenService service = new QrTokenService(SECRET, 60_000L);
        UUID anonymousId = UUID.randomUUID();

        Claims claims = parse(service.generateQrToken(anonymousId));

        assertThat(claims.getSubject()).isEqualTo(anonymousId.toString());
    }

    @Test
    void generateQrToken_carriesNoPermissionsClaim() {
        QrTokenService service = new QrTokenService(SECRET, 60_000L);

        Claims claims = parse(service.generateQrToken(UUID.randomUUID()));

        assertThat(claims).doesNotContainKey("permissions");
    }

    @Test
    void generateQrToken_expiresAfterConfiguredLifetime() {
        QrTokenService service = new QrTokenService(SECRET, 60_000L);

        Claims claims = parse(service.generateQrToken(UUID.randomUUID()));

        long lifetimeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(lifetimeMs).isBetween(59_000L, 61_000L);
    }

    @Test
    void generateQrToken_expiredTokenIsRejectedOnParse() {
        QrTokenService service = new QrTokenService(SECRET, -1_000L);

        String token = service.generateQrToken(UUID.randomUUID());

        assertThatThrownBy(() -> parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void generateQrToken_producesDistinctTokensForDifferentUsers() {
        QrTokenService service = new QrTokenService(SECRET, 60_000L);

        assertThat(service.generateQrToken(UUID.randomUUID()))
                .isNotEqualTo(service.generateQrToken(UUID.randomUUID()));
    }
}
