package com.circleguard.form.service;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.model.ValidationStatus;
import com.circleguard.form.repository.HealthSurveyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthSurveyServiceTest {

    @Mock
    private HealthSurveyRepository repository;
    @Mock
    private QuestionnaireService questionnaireService;
    @Mock
    private SymptomMapper symptomMapper;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private HealthSurveyService service;

    private final UUID anonymousId = UUID.randomUUID();

    private HealthSurvey survey() {
        return HealthSurvey.builder().anonymousId(anonymousId).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureEvent(String topic) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(topic), eq(anonymousId.toString()), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    void submit_withSymptoms_publishesEventToKafka() {
        HealthSurvey survey = survey();
        Questionnaire active = Questionnaire.builder().id(UUID.randomUUID()).build();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.of(active));
        when(symptomMapper.hasSymptoms(survey, active)).thenReturn(true);
        when(repository.save(survey)).thenReturn(survey);

        HealthSurvey result = service.submitSurvey(survey);

        assertThat(result).isSameAs(survey);
        Map<String, Object> event = captureEvent("survey.submitted");
        assertThat(event)
                .containsEntry("anonymousId", anonymousId)
                .containsEntry("hasSymptoms", true)
                .containsKey("timestamp");
    }

    @Test
    void submit_withSymptomsAndNoLegacyFields_fillsLegacyFlagsFromResponses() {
        HealthSurvey survey = survey();
        Questionnaire active = Questionnaire.builder().build();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.of(active));
        when(symptomMapper.hasSymptoms(survey, active)).thenReturn(true);
        when(repository.save(survey)).thenReturn(survey);

        service.submitSurvey(survey);

        assertThat(survey.getHasFever()).isTrue();
        assertThat(survey.getHasCough()).isTrue();
    }

    @Test
    void submit_withExplicitLegacyFields_keepsClientValues() {
        HealthSurvey survey = survey();
        survey.setHasFever(false);
        survey.setHasCough(true);
        Questionnaire active = Questionnaire.builder().build();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.of(active));
        when(symptomMapper.hasSymptoms(survey, active)).thenReturn(true);
        when(repository.save(survey)).thenReturn(survey);

        service.submitSurvey(survey);

        assertThat(survey.getHasFever()).isFalse();
        assertThat(survey.getHasCough()).isTrue();
    }

    @Test
    void submit_withoutActiveQuestionnaire_publishesNoSymptomsAndSkipsMapper() {
        HealthSurvey survey = survey();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.empty());
        when(repository.save(survey)).thenReturn(survey);

        service.submitSurvey(survey);

        verifyNoInteractions(symptomMapper);
        assertThat(survey.getHasFever()).isFalse();
        assertThat(captureEvent("survey.submitted")).containsEntry("hasSymptoms", false);
    }

    @Test
    void submit_withAttachment_marksSurveyPendingValidationBeforeSaving() {
        HealthSurvey survey = survey();
        survey.setAttachmentPath("abc_certificate.pdf");
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.empty());
        when(repository.save(any(HealthSurvey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submitSurvey(survey);

        ArgumentCaptor<HealthSurvey> saved = ArgumentCaptor.forClass(HealthSurvey.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValidationStatus()).isEqualTo(ValidationStatus.PENDING);
    }

    @Test
    void submit_withoutAttachment_leavesValidationStatusEmpty() {
        HealthSurvey survey = survey();
        when(questionnaireService.getActiveQuestionnaire()).thenReturn(Optional.empty());
        when(repository.save(survey)).thenReturn(survey);

        service.submitSurvey(survey);

        assertThat(survey.getValidationStatus()).isNull();
    }

    @Test
    void getPendingSurveys_queriesOnlyPendingSurveysWithAttachment() {
        List<HealthSurvey> pending = List.of(survey());
        when(repository.findByAttachmentPathIsNotNullAndValidationStatus(ValidationStatus.PENDING))
                .thenReturn(pending);

        assertThat(service.getPendingSurveys()).isEqualTo(pending);
    }

    @Test
    void validate_approved_savesDecisionAndPublishesCertificateValidated() {
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        HealthSurvey survey = survey();
        when(repository.findById(surveyId)).thenReturn(Optional.of(survey));

        service.validateSurvey(surveyId, ValidationStatus.APPROVED, adminId);

        verify(repository).save(survey);
        assertThat(survey.getValidationStatus()).isEqualTo(ValidationStatus.APPROVED);
        assertThat(survey.getValidatedBy()).isEqualTo(adminId);
        assertThat(captureEvent("certificate.validated"))
                .containsEntry("anonymousId", anonymousId)
                .containsEntry("status", "APPROVED")
                .containsEntry("adminId", adminId)
                .containsKey("timestamp");
    }

    @Test
    void validate_rejected_savesDecisionWithoutPublishingEvent() {
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        HealthSurvey survey = survey();
        when(repository.findById(surveyId)).thenReturn(Optional.of(survey));

        service.validateSurvey(surveyId, ValidationStatus.REJECTED, adminId);

        verify(repository).save(survey);
        assertThat(survey.getValidationStatus()).isEqualTo(ValidationStatus.REJECTED);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void validate_unknownSurvey_throwsAndSavesNothing() {
        UUID surveyId = UUID.randomUUID();
        when(repository.findById(surveyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateSurvey(surveyId, ValidationStatus.APPROVED, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Survey not found: " + surveyId);
        verify(repository, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
    }
}
