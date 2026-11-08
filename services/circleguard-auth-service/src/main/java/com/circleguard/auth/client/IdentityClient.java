package com.circleguard.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class IdentityClient {
    private static final String MAP_PATH = "/api/v1/identities/map";

    // In a real microservice, this would use Feign or WebClient
    private final RestTemplate restTemplate = new RestTemplate();
    private final String identityMapUrl;

    // La URL base se lee de la configuración (en compose: CIRCLEGUARD_IDENTITYSERVICE_URL).
    public IdentityClient(@Value("${circleguard.identity-service.url:http://localhost:8083}") String identityServiceUrl) {
        this.identityMapUrl = identityServiceUrl + MAP_PATH;
    }

    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map response = restTemplate.postForObject(identityMapUrl, request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}
