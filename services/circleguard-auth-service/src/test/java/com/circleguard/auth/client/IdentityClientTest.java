package com.circleguard.auth.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityClientTest {

    private static final String URL = "http://localhost:8083/api/v1/identities/map";

    private IdentityClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new IdentityClient();
        // IdentityClient builds its own RestTemplate; bind a mock server to it so no real HTTP is made.
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void getAnonymousId_postsRealIdentityAndParsesReturnedUuid() {
        UUID anonymousId = UUID.randomUUID();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"realIdentity\":\"jdoe\"}"))
                .andRespond(withSuccess("{\"anonymousId\":\"" + anonymousId + "\"}", MediaType.APPLICATION_JSON));

        UUID result = client.getAnonymousId("jdoe");

        assertThat(result).isEqualTo(anonymousId);
        server.verify();
    }

    @Test
    void getAnonymousId_identityServiceError_propagatesException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getAnonymousId("jdoe"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void getAnonymousId_invalidUuidInResponse_throwsIllegalArgument() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"anonymousId\":\"not-a-uuid\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getAnonymousId("jdoe"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
