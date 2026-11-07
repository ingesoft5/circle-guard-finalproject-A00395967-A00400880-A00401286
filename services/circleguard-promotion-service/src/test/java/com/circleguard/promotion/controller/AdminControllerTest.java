package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.jpa.SystemSettings;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import com.circleguard.promotion.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemSettingsRepository settingsRepository;

    private static SystemSettings existing() {
        return SystemSettings.builder().id(1L).unconfirmedFencingEnabled(false)
                .autoThresholdSeconds(1800L).mandatoryFenceDays(7).encounterWindowDays(10).build();
    }

    @Test
    void getSettings_existingRow_returnsIt() throws Exception {
        when(settingsRepository.getSettings()).thenReturn(Optional.of(existing()));

        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandatoryFenceDays").value(7))
                .andExpect(jsonPath("$.unconfirmedFencingEnabled").value(false));

        verify(settingsRepository, never()).save(any());
    }

    @Test
    void getSettings_noRow_persistsAndReturnsDefaults() throws Exception {
        when(settingsRepository.getSettings()).thenReturn(Optional.empty());
        when(settingsRepository.save(any(SystemSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unconfirmedFencingEnabled").value(true))
                .andExpect(jsonPath("$.autoThresholdSeconds").value(3600))
                .andExpect(jsonPath("$.mandatoryFenceDays").value(14))
                .andExpect(jsonPath("$.encounterWindowDays").value(14));

        verify(settingsRepository).save(any(SystemSettings.class));
    }

    @Test
    void updateSettings_partialPayload_updatesOnlyProvidedFields() throws Exception {
        SystemSettings current = existing();
        when(settingsRepository.getSettings()).thenReturn(Optional.of(current));

        mockMvc.perform(post("/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mandatoryFenceDays\":21}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandatoryFenceDays").value(21))
                .andExpect(jsonPath("$.encounterWindowDays").value(10))
                .andExpect(jsonPath("$.autoThresholdSeconds").value(1800));

        verify(settingsRepository).save(current);
    }

    @Test
    void updateSettings_fullPayload_overwritesAllFields() throws Exception {
        when(settingsRepository.getSettings()).thenReturn(Optional.of(existing()));

        mockMvc.perform(post("/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unconfirmedFencingEnabled\":true,\"autoThresholdSeconds\":600,"
                                + "\"mandatoryFenceDays\":5,\"encounterWindowDays\":3}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SystemSettings> saved = ArgumentCaptor.forClass(SystemSettings.class);
        verify(settingsRepository).save(saved.capture());
        assertThat(saved.getValue().getUnconfirmedFencingEnabled()).isTrue();
        assertThat(saved.getValue().getAutoThresholdSeconds()).isEqualTo(600L);
        assertThat(saved.getValue().getMandatoryFenceDays()).isEqualTo(5);
        assertThat(saved.getValue().getEncounterWindowDays()).isEqualTo(3);
    }

    @Test
    void toggleUnconfirmedFencing_setsFlagAndSaves() throws Exception {
        SystemSettings current = existing();
        when(settingsRepository.getSettings()).thenReturn(Optional.of(current));

        mockMvc.perform(post("/api/v1/admin/settings/toggle-unconfirmed-fencing").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unconfirmedFencingEnabled").value(true));

        verify(settingsRepository).save(current);
        assertThat(current.getUnconfirmedFencingEnabled()).isTrue();
    }
}
