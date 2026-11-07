package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.security.SecurityConfig;
import com.circleguard.promotion.service.AccessPointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccessPointController.class)
@Import(SecurityConfig.class)
class AccessPointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccessPointService accessPointService;

    @Test
    void getAccessPoint_existing_returnsDto() throws Exception {
        UUID id = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        when(accessPointService.getAccessPoint(id)).thenReturn(Optional.of(
                AccessPoint.builder().id(id).floor(Floor.builder().id(floorId).build())
                        .macAddress("AA").coordinateX(1.0).coordinateY(2.0).name("AP").build()));

        mockMvc.perform(get("/api/v1/access-points/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.floorId").value(floorId.toString()))
                .andExpect(jsonPath("$.coordinateY").value(2.0));
    }

    @Test
    void getAccessPoint_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(accessPointService.getAccessPoint(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/access-points/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void updateAccessPoint_asAdmin_returnsUpdatedDto() throws Exception {
        UUID id = UUID.randomUUID();
        when(accessPointService.updateAccessPoint(id, "BB", 3.0, 4.0, "Renamed"))
                .thenReturn(AccessPoint.builder().id(id).macAddress("BB").coordinateX(3.0).coordinateY(4.0).name("Renamed").build());

        mockMvc.perform(put("/api/v1/access-points/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"macAddress\":\"BB\",\"coordinateX\":3.0,\"coordinateY\":4.0,\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.macAddress").value("BB"))
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void deleteAccessPoint_asAdmin_deletesAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/access-points/{id}", id))
                .andExpect(status().isOk());

        verify(accessPointService).deleteAccessPoint(id);
    }

    @Test
    @WithMockUser(authorities = "STUDENT")
    void deleteAccessPoint_withoutAdminAuthority_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/access-points/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        verify(accessPointService, never()).deleteAccessPoint(any());
    }
}
