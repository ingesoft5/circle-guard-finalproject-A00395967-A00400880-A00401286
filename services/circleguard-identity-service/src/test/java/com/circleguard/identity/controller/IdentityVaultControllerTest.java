package com.circleguard.identity.controller;

import com.circleguard.identity.service.IdentityVaultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.annotation.Import;
import com.circleguard.identity.config.SecurityConfig;
import com.circleguard.identity.event.IdentityAccessEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@WebMvcTest(IdentityVaultController.class)
@Import(SecurityConfig.class)
class IdentityVaultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IdentityVaultService vaultService;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    @WithMockUser(authorities = "identity:lookup")
    void lookupIdentity_WithPermission_ReturnsRealIdentity() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId)).thenReturn("user@example.com");

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realIdentity").value("user@example.com"));

        // Verify Kafka event was emitted
        verify(kafkaTemplate).send(eq("audit.identity.accessed"), any());
    }

    @Test
    @WithMockUser(authorities = "other:permission")
    void lookupIdentity_WithoutPermission_Returns403() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId)).thenReturn("user@example.com");

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isForbidden());
    }

    @Test
    void lookupIdentity_Unauthenticated_Returns401() throws Exception {
        UUID anonymousId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "identity:lookup")
    void lookupIdentity_NotFound_Returns404ProblemDetail() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Identity not found"));

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Identity not found"));

        // Verify Kafka event was emitted even on failure
        verify(kafkaTemplate).send(eq("audit.identity.accessed"), any());
    }
    @Test
    void mapIdentity_withoutAuthentication_returnsAnonymousIdFromVault() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.getOrCreateAnonymousId("student@circleguard.edu")).thenReturn(anonymousId);

        mockMvc.perform(post("/api/v1/identities/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realIdentity\": \"student@circleguard.edu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousId").value(anonymousId.toString()));
    }

    @Test
    void registerVisitor_combinesVisitorDetailsIntoSingleVaultIdentity() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.getOrCreateAnonymousId(anyString())).thenReturn(anonymousId);

        mockMvc.perform(post("/api/v1/identities/visitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ana\", \"email\": \"ana@mail.com\", \"reason_for_visit\": \"Meeting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousId").value(anonymousId.toString()));

        verify(vaultService).getOrCreateAnonymousId("VISITOR|ana@mail.com|Ana|Meeting");
    }

    @Test
    @WithMockUser(username = "doctor-1", authorities = "identity:lookup")
    void lookupIdentity_success_auditEventCarriesUserIdAndSuccessStatus() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId)).thenReturn("user@example.com");

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isOk());

        IdentityAccessEvent event = captureAuditEvent();
        assertThat(event.getEventType()).isEqualTo("audit.identity.accessed");
        assertThat(event.getSource()).isEqualTo("circleguard-identity-service");
        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.getPayload().getAnonymousId()).isEqualTo(anonymousId);
        assertThat(event.getPayload().getRequestingUser()).isEqualTo("doctor-1");
        assertThat(event.getPayload().getAccessStatus()).isEqualTo("SUCCESS");
        assertThat(event.getMetadata().getVersion()).isEqualTo(1);
        assertThat(event.getMetadata().getCorrelationId()).isNotBlank();
    }

    @Test
    @WithMockUser(username = "doctor-1", authorities = "identity:lookup")
    void lookupIdentity_notFound_auditEventRecordsFailureStatus() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Identity not found"));

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isNotFound());

        assertThat(captureAuditEvent().getPayload().getAccessStatus()).isEqualTo("FAILURE_404 NOT_FOUND");
    }

    @Test
    @WithMockUser(authorities = "identity:lookup")
    void lookupIdentity_unexpectedError_stillEmitsAuditEventWithErrorStatus() {
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.resolveRealIdentity(anonymousId)).thenThrow(new IllegalStateException("vault down"));

        // The exception is not mapped to an HTTP status, so MockMvc rethrows it.
        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId)))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(captureAuditEvent().getPayload().getAccessStatus()).isEqualTo("ERROR");
    }

    @Test
    @WithMockUser(authorities = "other:permission")
    void lookupIdentity_withoutPermission_neverTouchesVaultNorAudits() throws Exception {
        UUID anonymousId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/identities/lookup/{id}", anonymousId))
                .andExpect(status().isForbidden());

        verify(vaultService, never()).resolveRealIdentity(any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    private IdentityAccessEvent captureAuditEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("audit.identity.accessed"), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(IdentityAccessEvent.class);
        return (IdentityAccessEvent) captor.getValue();
    }
}
