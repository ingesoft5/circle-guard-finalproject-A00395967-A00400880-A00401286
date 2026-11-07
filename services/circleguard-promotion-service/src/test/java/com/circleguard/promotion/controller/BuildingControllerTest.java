package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.security.SecurityConfig;
import com.circleguard.promotion.service.BuildingService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BuildingController.class)
@Import(SecurityConfig.class)
class BuildingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BuildingService buildingService;
    @MockBean
    private FloorService floorService;

    private static final String BUILDING_JSON = """
            {"name":"Library","code":"LIB","description":"Main","latitude":3.37,"longitude":-76.53,"address":"Calle 5"}
            """;

    @Test
    @WithMockUser(authorities = "ADMIN")
    void createBuilding_asAdmin_returnsCreatedBuildingDto() throws Exception {
        UUID id = UUID.randomUUID();
        when(buildingService.createBuilding("Library", "LIB", "Main", 3.37, -76.53, "Calle 5"))
                .thenReturn(Building.builder().id(id).name("Library").code("LIB").description("Main")
                        .latitude(3.37).longitude(-76.53).address("Calle 5").build());

        mockMvc.perform(post("/api/v1/buildings").contentType(MediaType.APPLICATION_JSON).content(BUILDING_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.code").value("LIB"))
                .andExpect(jsonPath("$.latitude").value(3.37))
                .andExpect(jsonPath("$.floors").value(nullValue()));
    }

    @Test
    @WithMockUser(authorities = "STUDENT")
    void createBuilding_withoutAdminAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/buildings").contentType(MediaType.APPLICATION_JSON).content(BUILDING_JSON))
                .andExpect(status().isForbidden());

        verifyNoInteractions(buildingService);
    }

    @Test
    void listBuildings_returnsBuildingsWithNestedFloors() throws Exception {
        UUID buildingId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).name("Library").code("LIB").build();
        Floor floor = Floor.builder().id(UUID.randomUUID()).building(building).floorNumber(1).name("Ground").build();
        building.setFloors(List.of(floor));
        when(buildingService.getAllBuildings()).thenReturn(List.of(building));

        mockMvc.perform(get("/api/v1/buildings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Library"))
                .andExpect(jsonPath("$[0].floors", hasSize(1)))
                .andExpect(jsonPath("$[0].floors[0].buildingId").value(buildingId.toString()))
                .andExpect(jsonPath("$[0].floors[0].floorNumber").value(1));
    }

    @Test
    void getFloors_returnsFloorDtosOfBuilding() throws Exception {
        UUID buildingId = UUID.randomUUID();
        Floor floor = Floor.builder().id(UUID.randomUUID()).floorNumber(2).name("Second")
                .floorPlanUrl("http://plans/2.png").build();
        when(floorService.getFloorsByBuilding(buildingId)).thenReturn(List.of(floor));

        mockMvc.perform(get("/api/v1/buildings/{id}/floors", buildingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Second"))
                .andExpect(jsonPath("$[0].floorPlanUrl").value("http://plans/2.png"))
                .andExpect(jsonPath("$[0].buildingId").value(nullValue()));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void addFloor_asAdmin_delegatesToFloorService() throws Exception {
        UUID buildingId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).build();
        when(floorService.addFloor(buildingId, 3, "Third"))
                .thenReturn(Floor.builder().id(UUID.randomUUID()).building(building).floorNumber(3).name("Third").build());

        mockMvc.perform(post("/api/v1/buildings/{id}/floors", buildingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"floorNumber\":3,\"name\":\"Third\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorNumber").value(3))
                .andExpect(jsonPath("$.buildingId").value(buildingId.toString()));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void updateBuilding_asAdmin_returnsUpdatedDto() throws Exception {
        UUID id = UUID.randomUUID();
        when(buildingService.updateBuilding(id, "Library", "LIB", "Main", 3.37, -76.53, "Calle 5"))
                .thenReturn(Building.builder().id(id).name("Library").code("LIB").address("Calle 5").build());

        mockMvc.perform(put("/api/v1/buildings/{id}", id).contentType(MediaType.APPLICATION_JSON).content(BUILDING_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Calle 5"));
    }

    @Test
    @WithMockUser(authorities = "STUDENT")
    void updateBuilding_withoutAdminAuthority_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/buildings/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(BUILDING_JSON))
                .andExpect(status().isForbidden());

        verify(buildingService, never()).updateBuilding(any(), anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void deleteBuilding_delegatesToServiceAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/buildings/{id}", id))
                .andExpect(status().isOk());

        verify(buildingService).deleteBuilding(id);
    }
}
