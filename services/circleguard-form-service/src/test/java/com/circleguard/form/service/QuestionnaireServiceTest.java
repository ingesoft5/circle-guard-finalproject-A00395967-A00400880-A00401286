package com.circleguard.form.service;

import com.circleguard.form.model.Question;
import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.repository.QuestionnaireRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionnaireServiceTest {

    @Mock
    private QuestionnaireRepository repository;

    @InjectMocks
    private QuestionnaireService service;

    private Questionnaire questionnaire(boolean active) {
        return Questionnaire.builder().id(UUID.randomUUID()).title("Daily check").version(1).isActive(active).build();
    }

    @Test
    void getAllQuestionnaires_returnsEveryStoredQuestionnaire() {
        List<Questionnaire> all = List.of(questionnaire(true), questionnaire(false));
        when(repository.findAll()).thenReturn(all);

        assertThat(service.getAllQuestionnaires()).containsExactlyElementsOf(all);
    }

    @Test
    void getActiveQuestionnaire_returnsLatestActiveVersion() {
        Questionnaire active = questionnaire(true);
        when(repository.findFirstByIsActiveTrueOrderByVersionDesc()).thenReturn(Optional.of(active));

        assertThat(service.getActiveQuestionnaire()).contains(active);
    }

    @Test
    void saveQuestionnaire_linksEveryQuestionBackToItsQuestionnaire() {
        Question q1 = Question.builder().text("Fever?").build();
        Question q2 = Question.builder().text("Cough?").build();
        Questionnaire questionnaire = questionnaire(false);
        questionnaire.setQuestions(List.of(q1, q2));
        when(repository.save(questionnaire)).thenReturn(questionnaire);

        Questionnaire saved = service.saveQuestionnaire(questionnaire);

        assertThat(saved).isSameAs(questionnaire);
        // Required for the JPA owning side (questions.questionnaire_id NOT NULL).
        assertThat(q1.getQuestionnaire()).isSameAs(questionnaire);
        assertThat(q2.getQuestionnaire()).isSameAs(questionnaire);
    }

    @Test
    void saveQuestionnaire_withoutQuestions_savesAsIs() {
        Questionnaire questionnaire = questionnaire(false);
        when(repository.save(questionnaire)).thenReturn(questionnaire);

        assertThat(service.saveQuestionnaire(questionnaire)).isSameAs(questionnaire);
        verify(repository).save(questionnaire);
    }

    @Test
    void activateQuestionnaire_deactivatesPreviousAndActivatesTarget() {
        Questionnaire previous = questionnaire(true);
        Questionnaire inactive = questionnaire(false);
        Questionnaire target = questionnaire(false);
        when(repository.findAll()).thenReturn(List.of(previous, inactive, target));
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));

        service.activateQuestionnaire(target.getId());

        assertThat(previous.getIsActive()).isFalse();
        assertThat(target.getIsActive()).isTrue();
        verify(repository).save(previous);
        verify(repository).save(target);
        // Already inactive questionnaires are not rewritten.
        verify(repository, never()).save(inactive);
    }

    @Test
    void activateQuestionnaire_unknownId_stillDeactivatesCurrentOne() {
        // Documents current behavior: an invalid id leaves the system without an active questionnaire.
        Questionnaire previous = questionnaire(true);
        UUID unknownId = UUID.randomUUID();
        when(repository.findAll()).thenReturn(List.of(previous));
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        service.activateQuestionnaire(unknownId);

        assertThat(previous.getIsActive()).isFalse();
        verify(repository).save(previous);
    }
}
