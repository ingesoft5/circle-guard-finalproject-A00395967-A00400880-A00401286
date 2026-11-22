package com.circleguard.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CG-93: cada validación del torniquete suma a circleguard_checkins_total con su resultado. */
class CheckinMetricsTest {

    private static final String SECRET = "my-super-secret-test-key-32-chars-long";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ValueOperations<String, String> valueOps;
    private QrValidationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Metrics.addRegistry(meters);
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(valueOps);
        service = new QrValidationService(redis);
        ReflectionTestUtils.setField(service, "qrSecret", SECRET);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meters);
    }

    @Test
    void countsGrantedDeniedAndInvalidCheckinsSeparately() {
        String healthy = UUID.randomUUID().toString();
        String confirmed = UUID.randomUUID().toString();
        Mockito.when(valueOps.get("user:status:" + healthy)).thenReturn("ACTIVE");
        Mockito.when(valueOps.get("user:status:" + confirmed)).thenReturn("CONFIRMED");

        service.validateToken(token(healthy));
        service.validateToken(token(healthy));
        service.validateToken(token(confirmed));
        service.validateToken("not-a-jwt");

        assertThat(count("granted")).isEqualTo(2.0);
        assertThat(count("denied")).isEqualTo(1.0);
        assertThat(count("invalid")).isEqualTo(1.0);
    }

    private double count(String result) {
        return meters.get(QrValidationService.CHECKINS_METRIC).tag("result", result).counter().count();
    }

    private static String token(String anonymousId) {
        return Jwts.builder()
                .setSubject(anonymousId)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }
}
