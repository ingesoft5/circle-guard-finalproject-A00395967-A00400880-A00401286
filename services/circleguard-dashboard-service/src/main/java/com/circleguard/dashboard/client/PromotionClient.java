package com.circleguard.dashboard.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Cliente HTTP de dashboard hacia promotion-service.
 *
 * <p>Patrón Circuit Breaker (CG-98, Resilience4j, instancia {@code promotion}).
 * Si promotion falla o responde lento de forma repetida, el circuito se ABRE y
 * durante un tiempo las llamadas ya no salen por la red: van directo al
 * fallback. Así dashboard responde rápido con datos degradados en vez de
 * acumular hilos esperando a un servicio caído, y promotion tiene tiempo de
 * recuperarse. Los umbrales están en application.yml (configuración externa).
 */
@Component
@Slf4j
public class PromotionClient {

    static final String CIRCUIT = "promotion";

    private final RestTemplate restTemplate;

    @Value("${circleguard.promotion-service.url:http://localhost:8088}")
    private String promotionServiceUrl;

    public PromotionClient() {
        // Timeouts explícitos: sin ellos una llamada colgada nunca cuenta como
        // fallo ni como llamada lenta para el circuit breaker.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "healthStatsFallback")
    public Map<String, Object> getHealthStats() {
        return restTemplate.getForObject(
                promotionServiceUrl + "/api/v1/health-status/stats",
                Map.class
        );
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "departmentStatsFallback")
    public Map<String, Object> getHealthStatsByDepartment(String department) {
        return restTemplate.getForObject(
                promotionServiceUrl + "/api/v1/health-status/stats/department/" + department,
                Map.class
        );
    }

    // --- Fallbacks: misma firma + la excepción. Se usan tanto cuando la llamada
    // falla como cuando el circuito está abierto (CallNotPermittedException).

    Map<String, Object> healthStatsFallback(Throwable cause) {
        log.warn("promotion-service no disponible ({}): se responden estadísticas degradadas", cause.toString());
        return degraded(Map.of());
    }

    Map<String, Object> departmentStatsFallback(String department, Throwable cause) {
        log.warn("promotion-service no disponible ({}): estadísticas degradadas para {}", cause.toString(), department);
        return degraded(Map.of("department", department));
    }

    private static Map<String, Object> degraded(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>(extra);
        body.put("error", "Service unavailable");
        body.put("degraded", true);
        body.put("timestamp", new Date());
        return body;
    }
}
