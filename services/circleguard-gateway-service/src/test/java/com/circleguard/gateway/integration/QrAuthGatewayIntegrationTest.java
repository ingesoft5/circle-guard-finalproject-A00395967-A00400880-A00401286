package com.circleguard.gateway.integration;

import com.circleguard.auth.service.QrTokenService;
import com.circleguard.gateway.service.QrValidationService;
import com.circleguard.gateway.service.QrValidationService.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración QR auth-service -> gateway-service con un Redis real (CG-43).
 *
 * <p>Flujo de ingreso al campus: auth-service emite un QR firmado y de vida corta;
 * gateway-service lo valida en la portería y consulta en Redis el estado de salud
 * del usuario anónimo. Esta prueba une las dos piezas reales:
 * <ul>
 *   <li>el token lo genera el {@link QrTokenService} de auth-service (código real),</li>
 *   <li>lo valida el {@link QrValidationService} de gateway-service (bean real de Spring),</li>
 *   <li>el estado de salud vive en un Redis 7.2 levantado con Testcontainers.</li>
 * </ul>
 * No hay mocks en el punto de integración: si cambia el formato o la firma del token
 * en auth, o la clave de estado en Redis, esta prueba falla.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = QrAuthGatewayIntegrationTest.TestConfig.class,
                webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QrAuthGatewayIntegrationTest {

    /** Clave compartida entre auth y gateway (HS256 exige al menos 32 bytes). */
    private static final String SHARED_QR_SECRET = "integration-test-qr-secret-0123456789abcdef";
    private static final String STATUS_KEY_PREFIX = "user:status:";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisAndSecret(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("qr.secret", () -> SHARED_QR_SECRET);
    }

    /** Contexto mínimo: solo Redis y el servicio de validación del gateway. */
    @Configuration
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import(QrValidationService.class)
    static class TestConfig {
    }

    @Autowired
    private QrValidationService gateway;

    @Autowired
    private StringRedisTemplate redis;

    /** Emisor de QR de auth-service con la misma clave y 60 s de vida. */
    private final QrTokenService auth = new QrTokenService(SHARED_QR_SECRET, 60_000);

    @Test
    @DisplayName("QR emitido por auth para un usuario sano: el gateway permite el ingreso")
    void healthyUserIsAllowed() {
        UUID user = UUID.randomUUID();
        setStatus(user, "CLEAR");

        ValidationResult result = gateway.validateToken(auth.generateQrToken(user));

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("Usuario sin estado en Redis: el gateway permite el ingreso")
    void userWithoutStatusIsAllowed() {
        ValidationResult result = gateway.validateToken(auth.generateQrToken(UUID.randomUUID()));

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("Usuario contagiado en Redis: el gateway niega el ingreso")
    void contagiedUserIsDenied() {
        UUID user = UUID.randomUUID();
        setStatus(user, "CONTAGIED");

        ValidationResult result = gateway.validateToken(auth.generateQrToken(user));

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
        assertThat(result.message()).contains("Health Risk");
    }

    @Test
    @DisplayName("Usuario con riesgo potencial en Redis: el gateway niega el ingreso")
    void potentialUserIsDenied() {
        UUID user = UUID.randomUUID();
        setStatus(user, "POTENTIAL");

        ValidationResult result = gateway.validateToken(auth.generateQrToken(user));

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    @Test
    @DisplayName("Un cambio de estado en Redis se refleja de inmediato con el mismo QR")
    void statusChangeInRedisIsSeenImmediately() {
        UUID user = UUID.randomUUID();
        String token = auth.generateQrToken(user);

        setStatus(user, "CLEAR");
        assertThat(gateway.validateToken(token).valid()).isTrue();

        setStatus(user, "CONTAGIED");
        assertThat(gateway.validateToken(token).valid()).isFalse();
    }

    @Test
    @DisplayName("QR expirado: el gateway lo rechaza")
    void expiredTokenIsRejected() {
        QrTokenService expiredIssuer = new QrTokenService(SHARED_QR_SECRET, -60_000);

        ValidationResult result = gateway.validateToken(expiredIssuer.generateQrToken(UUID.randomUUID()));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Invalid or Expired Token");
    }

    @Test
    @DisplayName("QR alterado (otro usuario con la firma original): el gateway lo rechaza")
    void tamperedTokenIsRejected() {
        String[] original = auth.generateQrToken(UUID.randomUUID()).split("\\.");
        String[] other = auth.generateQrToken(UUID.randomUUID()).split("\\.");
        String tampered = original[0] + "." + other[1] + "." + original[2];

        ValidationResult result = gateway.validateToken(tampered);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("Invalid or Expired Token");
    }

    @Test
    @DisplayName("QR firmado con otra clave: el gateway lo rechaza")
    void tokenSignedWithAnotherSecretIsRejected() {
        QrTokenService foreignIssuer = new QrTokenService("another-secret-that-is-not-shared-0123456789", 60_000);

        ValidationResult result = gateway.validateToken(foreignIssuer.generateQrToken(UUID.randomUUID()));

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    private void setStatus(UUID user, String status) {
        redis.opsForValue().set(STATUS_KEY_PREFIX + user, status);
    }
}
