package com.circleguard.dashboard.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CG-98: el circuit breaker de dashboard -> promotion se prueba con el proxy
 * AOP real de Resilience4j (no con mocks del breaker). promotion-service se
 * simula con MockRestServiceServer: devolviendo 500 se inyecta su caída.
 */
@SpringBootTest(classes = PromotionClient.class, properties = {
        "circleguard.promotion-service.url=http://promotion.test",
        // Ventana pequeña para que la prueba sea corta; el resto como en producción.
        "resilience4j.circuitbreaker.instances.promotion.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.promotion.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.promotion.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.promotion.wait-duration-in-open-state=60s",
})
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
class PromotionClientCircuitBreakerTest {

    private static final String STATS_URL = "http://promotion.test/api/v1/health-status/stats";

    @Autowired
    private PromotionClient client;

    @Autowired
    private CircuitBreakerRegistry registry;

    private MockRestServiceServer promotion;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = registry.circuitBreaker(PromotionClient.CIRCUIT);
        breaker.reset();
        PromotionClient target = AopTestUtils.getTargetObject(client);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(target, "restTemplate");
        promotion = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void healthyPromotion_returnsItsStatsAndCircuitStaysClosed() {
        promotion.expect(requestTo(STATS_URL))
                .andRespond(withSuccess("{\"totalUsers\":120}", MediaType.APPLICATION_JSON));

        Map<String, Object> stats = client.getHealthStats();

        assertThat(stats).containsEntry("totalUsers", 120).doesNotContainKey("degraded");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        promotion.verify();
    }

    @Test
    void promotionDown_opensCircuitAndStopsCallingIt() {
        // Caída inyectada: promotion responde 500 exactamente 4 veces.
        promotion.expect(ExpectedCount.times(4), requestTo(STATS_URL)).andRespond(withServerError());

        for (int i = 0; i < 4; i++) {
            Map<String, Object> stats = client.getHealthStats();
            assertThat(stats).containsEntry("degraded", true).containsEntry("error", "Service unavailable");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Con el circuito abierto la llamada no sale a la red (el mock no espera
        // una 5.a petición: si llegara, verify() fallaría) y responde el fallback.
        Map<String, Object> whileOpen = client.getHealthStats();

        assertThat(whileOpen).containsEntry("degraded", true);
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
        promotion.verify();
    }

    @Test
    void departmentFallback_keepsTheRequestedDepartment() {
        breaker.transitionToOpenState();

        Map<String, Object> stats = client.getHealthStatsByDepartment("ingenieria");

        assertThat(stats).containsEntry("department", "ingenieria").containsEntry("degraded", true);
    }
}
