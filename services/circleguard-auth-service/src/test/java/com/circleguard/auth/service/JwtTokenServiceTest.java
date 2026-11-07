package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-with-at-least-32-bytes";
    private static final long EXPIRATION_MS = 3_600_000L;

    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService(SECRET, EXPIRATION_MS);
    }

    private static Claims parse(String token, String secret) {
        return Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private static Authentication authWith(String... authorities) {
        return new UsernamePasswordAuthenticationToken("jdoe", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void generateToken_usesAnonymousIdAsSubjectNotUsername() {
        UUID anonymousId = UUID.randomUUID();

        Claims claims = parse(service.generateToken(anonymousId, authWith("ROLE_STUDENT")), SECRET);

        assertThat(claims.getSubject())
                .isEqualTo(anonymousId.toString())
                .isNotEqualTo("jdoe");
    }

    @Test
    void generateToken_includesPermissionsClaimWithRolesAndGranularPermissions() {
        Claims claims = parse(service.generateToken(UUID.randomUUID(),
                authWith("ROLE_HEALTH_CENTER", "alert:send", "dashboard:view")), SECRET);

        @SuppressWarnings("unchecked")
        List<String> permissions = claims.get("permissions", List.class);
        assertThat(permissions).containsExactlyInAnyOrder("ROLE_HEALTH_CENTER", "alert:send", "dashboard:view");
    }

    @Test
    void generateToken_withNoAuthorities_emitsEmptyPermissionsList() {
        Claims claims = parse(service.generateToken(UUID.randomUUID(), authWith()), SECRET);

        assertThat(claims.get("permissions", List.class)).isEmpty();
    }

    @Test
    void generateToken_setsExpirationAccordingToConfiguredLifetime() {
        Claims claims = parse(service.generateToken(UUID.randomUUID(), authWith("ROLE_STUDENT")), SECRET);

        long lifetimeMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        // iat/exp are serialized with second precision, so allow a 1s tolerance
        assertThat(lifetimeMs).isBetween(EXPIRATION_MS - 1000, EXPIRATION_MS + 1000);
    }

    @Test
    void generateToken_isRejectedWhenVerifiedWithDifferentKey() {
        String token = service.generateToken(UUID.randomUUID(), authWith("ROLE_STUDENT"));

        assertThatThrownBy(() -> parse(token, "another-secret-that-is-also-32-bytes-long!"))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void constructor_rejectsSecretShorterThan256Bits() {
        assertThatThrownBy(() -> new JwtTokenService("too-short", EXPIRATION_MS))
                .isInstanceOf(WeakKeyException.class);
    }
}
