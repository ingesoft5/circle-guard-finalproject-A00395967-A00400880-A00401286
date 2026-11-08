package com.circleguard.form.controller;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.ValidationStatus;
import com.circleguard.form.service.HealthSurveyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CertificateValidationController.class)
class CertificateValidationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthSurveyService surveyService;

    @Test
    void getPending_returnsSurveysAwaitingValidation() throws Exception {
        HealthSurvey pending = HealthSurvey.builder()
                .id(UUID.randomUUID())
                .anonymousId(UUID.randomUUID())
                .attachmentPath("abc_certificate.pdf")
                .validationStatus(ValidationStatus.PENDING)
                .build();
        when(surveyService.getPendingSurveys()).thenReturn(List.of(pending));

        mockMvc.perform(get("/api/v1/certificates/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attachmentPath").value("abc_certificate.pdf"))
                .andExpect(jsonPath("$[0].validationStatus").value("PENDING"));
    }

    @Test
    void validate_passesSurveyStatusAndAdminToService() throws Exception {
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/certificates/{id}/validate", surveyId)
                        .param("status", "APPROVED")
                        .param("adminId", adminId.toString()))
                .andExpect(status().isOk());

        verify(surveyService).validateSurvey(surveyId, ValidationStatus.APPROVED, adminId);
    }

    @Test
    void validate_unknownStatus_returnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/certificates/{id}/validate", UUID.randomUUID())
                        .param("status", "MAYBE")
                        .param("adminId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());

        verify(surveyService, never()).validateSurvey(any(), any(), any());
    }
}
