package com.circleguard.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.Key;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QrValidationService {
    private final StringRedisTemplate redisTemplate;

    @Value("${qr.secret}")
    private String qrSecret;

    // Feature Toggle: modo estricto del torniquete. Apagado por defecto (valor seguro:
    // comportamiento original). Se sobrescribe con CIRCLEGUARD_FEATURES_STRICT_GATE_ENABLED.
    @Value("${circleguard.features.strict-gate.enabled:false}")
    private boolean strictGateEnabled;

    private static final String STATUS_KEY_PREFIX = "user:status:";

    // Métrica de negocio (CG-93): ingresos al campus por resultado. En Prometheus
    // queda como circleguard_checkins_total{result="granted|denied|invalid"}.
    static final String CHECKINS_METRIC = "circleguard.checkins";

    // Estados que niegan el ingreso. promotion-service escribe en Redis
    // SUSPECT, PROBABLE y CONFIRMED (CONTAGIED y POTENTIAL se mantienen por
    // compatibilidad con datos anteriores). ACTIVE y RECOVERED permiten el ingreso.
    private static final Set<String> DENIED_STATUSES =
            Set.of("SUSPECT", "PROBABLE", "CONFIRMED", "CONTAGIED", "POTENTIAL");

    public ValidationResult validateToken(String token) {
        try {
            Key key = Keys.hmacShaKeyFor(qrSecret.getBytes());
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String anonymousId = claims.getSubject();
            
            // Check Redis for current Health Status
            String status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + anonymousId);
            
            // Modo estricto: sin estado en Redis no se puede confirmar que el usuario esté sano.
            if (status == null && strictGateEnabled) {
                countCheckin("denied");
                return new ValidationResult(false, "RED", "Access Denied: Status unavailable");
            }

            if (status != null && DENIED_STATUSES.contains(status)) {
                countCheckin("denied");
                return new ValidationResult(false, "RED", "Access Denied: Health Risk Detected");
            }

            countCheckin("granted");
            return new ValidationResult(true, "GREEN", "Welcome to Campus");
            
        } catch (Exception e) {
            countCheckin("invalid");
            return new ValidationResult(false, "RED", "Invalid or Expired Token");
        }
    }

    private static void countCheckin(String result) {
        // Registro global de Micrometer: Spring Boot le agrega el de Prometheus.
        Metrics.counter(CHECKINS_METRIC, "result", result).increment();
    }

    public record ValidationResult(boolean valid, String status, String message) {}
}
