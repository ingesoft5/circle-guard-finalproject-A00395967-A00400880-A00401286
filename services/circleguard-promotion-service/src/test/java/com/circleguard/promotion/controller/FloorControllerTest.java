package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.security.SecurityConfig;
import com.circleguard.promotion.service.AccessPointService;
import com.circleguard.promotion.service.FloorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FloorController.class)
@Import(SecurityConfig.class)
class FloorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FloorService floorService;
    @MockBean
    private AccessPointService accessPointService;

    private static final String AP_JSON = """
            {"macAddress":"AA:BB:CC:DD:EE:FF","coordinateX":1.5,"coordinateY":2.5,"name":"Lobby"}
            """;

    @Test
    @WithMockUser(authorities = "ADMIN")
    void addAccessPoint_asAdmin_registersApOnFloor() throws Exception {
        UUID floorId = UUID.randomUUID();
        Floor floor = Floor.builder().id(floorId).build();
        when(accessPointService.registerAccessPoint(floorId, "AA:BB:CC:DD:EE:FF", 1.5, 2.5, "Lobby"))
                .thenReturn(AccessPoint.builder().id(UUID.randomUUID()).floor(floor)
                        .macAddress("AA:BB:CC:DD:EE:FF").coordinateX(1.5).coordinateY(2.5).name("Lobby").build());

        mockMvc.perform(post("/api/v1/floors/{id}/access-points", floorId)
                        .contentType(MediaType.APPLICATION_JSON).content(AP_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorId").value(floorId.toString()))
                .andExpect(jsonPath("$.macAddress").value("AA:BB:CC:DD:EE:FF"))
                .andExpect(jsonPath("$.coordinateX").value(1.5));
    }

    @Test
    @WithMockUser(authorities = "STUDENT")
    void addAccessPoint_withoutAdminAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/floors/{id}/access-points", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(AP_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accessPointService);
    }

    @Test
    void getAccessPoints_returnsApsOfFloor() throws Exception {
        UUID floorId = UUID.randomUUID();
        when(accessPointService.getAccessPointsByFloor(floorId)).thenReturn(List.of(
                AccessPoint.builder().id(UUID.randomUUID()).macAddress("m1").name("A").build(),
                AccessPoint.builder().id(UUID.randomUUID()).macAddress("m2").name("B").build()));

        mockMvc.perform(get("/api/v1/floors/{id}/access-points", floorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].macAddress").value("m2"))
                .andExpect(jsonPath("$[0].floorId").value(nullValue()));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void updateFloor_asAdmin_passesFloorPlanUrlAndReturnsDto() throws Exception {
        UUID floorId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        when(floorService.updateFloor(floorId, 4, "Fourth", "http://plans/4.png"))
                .thenReturn(Floor.builder().id(floorId).building(Building.builder().id(buildingId).build())
                        .floorNumber(4).name("Fourth").floorPlanUrl("http://plans/4.png").build());

        mockMvc.perform(put("/api/v1/floors/{id}", floorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"floorNumber\":4,\"name\":\"Fourth\",\"floorPlanUrl\":\"http://plans/4.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildingId").value(buildingId.toString()))
                .andExpect(jsonPath("$.floorPlanUrl").value("http://plans/4.png"));
    }

    @Test
    void deleteFloor_delegatesToServiceAndReturns200() throws Exception {
        UUID floorId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/floors/{id}", floorId))
                .andExpect(status().isOk());

        verify(floorService).deleteFloor(floorId);
    }
}
