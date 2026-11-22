package com.circleguard.auth.client;

import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CG-99: Retry con backoff exponencial de auth -> identity, probado con el
 * proxy AOP real de Resilience4j y fallas transitorias inyectadas con
 * MockRestServiceServer. Usa la configuración de application.yml salvo la
 * espera base, que se reduce para que la prueba sea rápida.
 */
@SpringBootTest(classes = IdentityClient.class, properties = {
        "circleguard.identity-service.url=http://identity.test",
        "resilience4j.retry.instances.identity.max-attempts=3",
        "resilience4j.retry.instances.identity.wait-duration=50ms",
        "resilience4j.retry.instances.identity.enable-exponential-backoff=true",
        "resilience4j.retry.instances.identity.exponential-backoff-multiplier=2",
        "resilience4j.retry.instances.identity.retry-exceptions[0]=org.springframework.web.client.ResourceAccessException",
        "resilience4j.retry.instances.identity.retry-exceptions[1]=org.springframework.web.client.HttpServerErrorException$BadGateway",
        "resilience4j.retry.instances.identity.retry-exceptions[2]=org.springframework.web.client.HttpServerErrorException$ServiceUnavailable",
        "resilience4j.retry.instances.identity.retry-exceptions[3]=org.springframework.web.client.HttpServerErrorException$GatewayTimeout",
})
@ImportAutoConfiguration({AopAutoConfiguration.class, RetryAutoConfiguration.class})
class IdentityClientRetryTest {

    private static final String URL = "http://identity.test/api/v1/identities/map";

    @Autowired
    private IdentityClient client;

    @Autowired
    private RetryRegistry registry;

    private MockRestServiceServer identity;

    @BeforeEach
    void setUp() {
        IdentityClient target = AopTestUtils.getTargetObject(client);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(target, "restTemplate");
        identity = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void transientFailures_areRetriedWithGrowingWaitsUntilIdentityAnswers() {
        UUID anonymousId = UUID.randomUUID();
        // Falla transitoria inyectada: identity se reinicia (503) y luego hay un corte de red.
        identity.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        identity.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withException(new IOException("connection reset")));
        identity.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess("{\"anonymousId\":\"" + anonymousId + "\"}", MediaType.APPLICATION_JSON));

        long start = System.nanoTime();
        UUID result = client.getAnonymousId("jdoe");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEqualTo(anonymousId);
        // Backoff exponencial: 50 ms + 100 ms entre los tres intentos.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
        identity.verify();
        assertThat(registry.retry(IdentityClient.RETRY).getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void persistentOutage_givesUpAfterThreeAttempts() {
        identity.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getAnonymousId("jdoe"))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        identity.verify();
    }

    @Test
    void clientError_isNotRetried() {
        // Un 4xx no es transitorio: repetirlo daría lo mismo. Una sola petición.
        identity.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getAnonymousId("jdoe"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        identity.verify();
    }

    @Test
    void internalServerError_isNotRetried() {
        // Un 500 indica un error de identity, no un corte momentáneo.
        identity.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getAnonymousId("jdoe"))
                .isInstanceOf(HttpServerErrorException.InternalServerError.class);
        identity.verify();
    }
}
