package com.circleguard.promotion.service;

import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.BuildingRepository;
import com.circleguard.promotion.repository.jpa.FloorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuildingServiceTest {

    @Mock
    private BuildingRepository buildingRepository;
    @Mock
    private FloorRepository floorRepository;

    @InjectMocks
    private BuildingService buildingService;

    @Test
    void createBuilding_validData_savesBuildingWithGeolocation() {
        when(buildingRepository.save(any(Building.class))).thenAnswer(inv -> inv.getArgument(0));

        Building result = buildingService.createBuilding("Lab", "LAB", "Labs", 3.34, -76.53, "Calle 18");

        assertThat(result.getName()).isEqualTo("Lab");
        assertThat(result.getCode()).isEqualTo("LAB");
        assertThat(result.getDescription()).isEqualTo("Labs");
        assertThat(result.getLatitude()).isEqualTo(3.34);
        assertThat(result.getLongitude()).isEqualTo(-76.53);
        assertThat(result.getAddress()).isEqualTo("Calle 18");
        verify(buildingRepository).save(result);
    }

    @Test
    void getAllBuildings_delegatesToRepository() {
        List<Building> buildings = List.of(Building.builder().name("A").build(), Building.builder().name("B").build());
        when(buildingRepository.findAll()).thenReturn(buildings);

        assertThat(buildingService.getAllBuildings()).containsExactlyElementsOf(buildings);
    }

    @Test
    void updateBuilding_existingBuilding_overwritesAllFields() {
        UUID id = UUID.randomUUID();
        Building existing = Building.builder().id(id).name("Old").code("OLD").build();
        when(buildingRepository.findById(id)).thenReturn(Optional.of(existing));
        when(buildingRepository.save(existing)).thenReturn(existing);

        Building result = buildingService.updateBuilding(id, "New", "NEW", "desc", 1.0, 2.0, "addr");

        assertThat(result.getName()).isEqualTo("New");
        assertThat(result.getCode()).isEqualTo("NEW");
        assertThat(result.getDescription()).isEqualTo("desc");
        assertThat(result.getLatitude()).isEqualTo(1.0);
        assertThat(result.getLongitude()).isEqualTo(2.0);
        assertThat(result.getAddress()).isEqualTo("addr");
    }

    @Test
    void updateBuilding_unknownBuilding_throwsAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> buildingService.updateBuilding(id, "n", "c", "d", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Building not found");
        verify(buildingRepository, never()).save(any());
    }

    @Test
    void deleteBuilding_withoutFloors_deletesById() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of());

        buildingService.deleteBuilding(id);

        verify(buildingRepository).deleteById(id);
    }

    @Test
    void deleteBuilding_withFloors_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of(new Floor()));

        assertThatThrownBy(() -> buildingService.deleteBuilding(id))
                .hasMessage("Cannot delete building with existing floors");
        verify(buildingRepository, never()).deleteById(any());
    }
}
