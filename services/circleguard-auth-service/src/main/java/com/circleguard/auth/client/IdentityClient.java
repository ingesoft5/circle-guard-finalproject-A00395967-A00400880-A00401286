package com.circleguard.auth.client;

import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

/**
 * Cliente HTTP de auth hacia identity-service.
 *
 * <p>Patrón Retry con backoff exponencial (CG-99, Resilience4j, instancia
 * {@code identity}). El login depende de esta llamada; un reinicio de identity
 * o un fallo de red momentáneo no debería tumbar el inicio de sesión, así que
 * se reintenta solo ante fallos transitorios (I/O, 502, 503 y 504). Se puede
 * reintentar porque la operación es idempotente: identity hace "buscar o
 * crear" por el hash de la identidad (índice único), así que repetir la
 * petición siempre devuelve el mismo ID anónimo. Detalle en
 * docs/patrones/resiliencia.md.
 */
@Component
public class IdentityClient {
    static final String RETRY = "identity";
    private static final String MAP_PATH = "/api/v1/identities/map";

    private final RestTemplate restTemplate;
    private final String identityMapUrl;

    // La URL base se lee de la configuración (en compose: CIRCLEGUARD_IDENTITYSERVICE_URL).
    public IdentityClient(@Value("${circleguard.identity-service.url:http://localhost:8083}") String identityServiceUrl) {
        this.identityMapUrl = identityServiceUrl + MAP_PATH;
        // Timeouts: una llamada colgada se convierte en un fallo de I/O que el
        // retry puede manejar, en vez de bloquear el login indefinidamente.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Retry(name = RETRY)
    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map response = restTemplate.postForObject(identityMapUrl, request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}
