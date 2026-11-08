package com.circleguard.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

    private static final String STATUS_KEY_PREFIX = "user:status:";

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
            
            if (status != null && DENIED_STATUSES.contains(status)) {
                return new ValidationResult(false, "RED", "Access Denied: Health Risk Detected");
            }

            return new ValidationResult(true, "GREEN", "Welcome to Campus");
            
        } catch (Exception e) {
            return new ValidationResult(false, "RED", "Invalid or Expired Token");
        }
    }

    public record ValidationResult(boolean valid, String status, String message) {}
}
