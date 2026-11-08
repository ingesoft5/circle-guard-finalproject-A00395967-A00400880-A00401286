package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
import com.circleguard.promotion.repository.jpa.BuildingRepository;
import com.circleguard.promotion.repository.jpa.FloorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class SpatialServiceTest {

    @Mock
    private BuildingRepository buildingRepository;
    @Mock
    private FloorRepository floorRepository;
    @Mock
    private AccessPointRepository accessPointRepository;

    @InjectMocks
    private SpatialService spatialService;

    @Test
    void createBuilding_validData_savesBuildingWithGivenFields() {
        when(buildingRepository.save(any(Building.class))).thenAnswer(inv -> inv.getArgument(0));

        Building result = spatialService.createBuilding("Library", "LIB", "Main library");

        assertThat(result.getName()).isEqualTo("Library");
        assertThat(result.getCode()).isEqualTo("LIB");
        assertThat(result.getDescription()).isEqualTo("Main library");
        verify(buildingRepository).save(result);
    }

    @Test
    void addFloor_existingBuilding_savesFloorLinkedToBuilding() {
        UUID buildingId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).name("Library").build();
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(building));
        when(floorRepository.save(any(Floor.class))).thenAnswer(inv -> inv.getArgument(0));

        Floor floor = spatialService.addFloor(buildingId, 2, "Second");

        assertThat(floor.getBuilding()).isSameAs(building);
        assertThat(floor.getFloorNumber()).isEqualTo(2);
        assertThat(floor.getName()).isEqualTo("Second");
    }

    @Test
    void addFloor_unknownBuilding_throwsAndDoesNotSave() {
        UUID buildingId = UUID.randomUUID();
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spatialService.addFloor(buildingId, 1, "Ground"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Building not found");
        verify(floorRepository, never()).save(any());
    }

    @Test
    void getAllBuildings_delegatesToRepository() {
        List<Building> buildings = List.of(Building.builder().name("A").build());
        when(buildingRepository.findAll()).thenReturn(buildings);

        assertThat(spatialService.getAllBuildings()).isEqualTo(buildings);
    }

    @Test
    void getFloorsByBuilding_returnsFloorsOfBuilding() {
        UUID buildingId = UUID.randomUUID();
        List<Floor> floors = List.of(Floor.builder().floorNumber(1).build());
        when(floorRepository.findByBuildingId(buildingId)).thenReturn(floors);

        assertThat(spatialService.getFloorsByBuilding(buildingId)).isEqualTo(floors);
    }

    @Test
    void updateBuilding_existingBuilding_overwritesFieldsAndSaves() {
        UUID id = UUID.randomUUID();
        Building existing = Building.builder().id(id).name("Old").code("OLD").description("old").build();
        when(buildingRepository.findById(id)).thenReturn(Optional.of(existing));
        when(buildingRepository.save(existing)).thenReturn(existing);

        Building result = spatialService.updateBuilding(id, "New", "NEW", "new desc");

        assertThat(result.getName()).isEqualTo("New");
        assertThat(result.getCode()).isEqualTo("NEW");
        assertThat(result.getDescription()).isEqualTo("new desc");
    }

    @Test
    void updateBuilding_unknownBuilding_throws() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spatialService.updateBuilding(id, "n", "c", "d"))
                .hasMessage("Building not found");
    }

    @Test
    void deleteBuilding_withoutFloors_deletesById() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of());

        spatialService.deleteBuilding(id);

        verify(buildingRepository).deleteById(id);
    }

    @Test
    void deleteBuilding_withFloors_throwsAndKeepsBuilding() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findByBuildingId(id)).thenReturn(List.of(new Floor()));

        assertThatThrownBy(() -> spatialService.deleteBuilding(id))
                .hasMessage("Cannot delete building with existing floors");
        verify(buildingRepository, never()).deleteById(any());
    }

    @Test
    void updateFloor_existingFloor_overwritesNumberAndName() {
        UUID id = UUID.randomUUID();
        Floor floor = Floor.builder().id(id).floorNumber(1).name("Old").build();
        when(floorRepository.findById(id)).thenReturn(Optional.of(floor));
        when(floorRepository.save(floor)).thenReturn(floor);

        Floor result = spatialService.updateFloor(id, 3, "Third");

        assertThat(result.getFloorNumber()).isEqualTo(3);
        assertThat(result.getName()).isEqualTo("Third");
    }

    @Test
    void updateFloor_unknownFloor_throws() {
        UUID id = UUID.randomUUID();
        when(floorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spatialService.updateFloor(id, 1, "x"))
                .hasMessage("Floor not found");
    }

    @Test
    void deleteFloor_withoutAccessPoints_deletesById() {
        UUID id = UUID.randomUUID();
        when(accessPointRepository.findByFloorId(id)).thenReturn(List.of());

        spatialService.deleteFloor(id);

        verify(floorRepository).deleteById(id);
    }

    @Test
    void deleteFloor_withAccessPoints_throwsAndKeepsFloor() {
        UUID id = UUID.randomUUID();
        when(accessPointRepository.findByFloorId(id)).thenReturn(List.of(new AccessPoint()));

        assertThatThrownBy(() -> spatialService.deleteFloor(id))
                .hasMessage("Cannot delete floor with existing access points");
        verify(floorRepository, never()).deleteById(any());
    }

    @Test
    void registerAccessPoint_existingFloor_savesAccessPointWithCoordinates() {
        UUID floorId = UUID.randomUUID();
        Floor floor = Floor.builder().id(floorId).build();
        when(floorRepository.findById(floorId)).thenReturn(Optional.of(floor));
        when(accessPointRepository.save(any(AccessPoint.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessPoint ap = spatialService.registerAccessPoint(floorId, "AA:BB", 1.5, 2.5, "AP-1");

        ArgumentCaptor<AccessPoint> captor = ArgumentCaptor.forClass(AccessPoint.class);
        verify(accessPointRepository).save(captor.capture());
        assertThat(captor.getValue().getFloor()).isSameAs(floor);
        assertThat(ap.getMacAddress()).isEqualTo("AA:BB");
        assertThat(ap.getCoordinateX()).isEqualTo(1.5);
        assertThat(ap.getCoordinateY()).isEqualTo(2.5);
        assertThat(ap.getName()).isEqualTo("AP-1");
    }

    @Test
    void registerAccessPoint_unknownFloor_throws() {
        UUID floorId = UUID.randomUUID();
        when(floorRepository.findById(floorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spatialService.registerAccessPoint(floorId, "AA", 0.0, 0.0, "x"))
                .hasMessage("Floor not found");
        verify(accessPointRepository, never()).save(any());
    }

    @Test
    void getAccessPoint_delegatesToRepository() {
        UUID id = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder().id(id).build();
        when(accessPointRepository.findById(id)).thenReturn(Optional.of(ap));

        assertThat(spatialService.getAccessPoint(id)).contains(ap);
    }

    @Test
    void getAccessPointsByFloor_delegatesToRepository() {
        UUID floorId = UUID.randomUUID();
        List<AccessPoint> aps = List.of(new AccessPoint());
        when(accessPointRepository.findByFloorId(floorId)).thenReturn(aps);

        assertThat(spatialService.getAccessPointsByFloor(floorId)).isEqualTo(aps);
    }

    @Test
    void updateAccessPoint_existingAp_overwritesFields() {
        UUID id = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder().id(id).macAddress("OLD").coordinateX(0.0).coordinateY(0.0).name("old").build();
        when(accessPointRepository.findById(id)).thenReturn(Optional.of(ap));
        when(accessPointRepository.save(ap)).thenReturn(ap);

        AccessPoint result = spatialService.updateAccessPoint(id, "NEW", 4.0, 5.0, "new");

        assertThat(result.getMacAddress()).isEqualTo("NEW");
        assertThat(result.getCoordinateX()).isEqualTo(4.0);
        assertThat(result.getCoordinateY()).isEqualTo(5.0);
        assertThat(result.getName()).isEqualTo("new");
    }

    @Test
    void updateAccessPoint_unknownAp_throws() {
        UUID id = UUID.randomUUID();
        when(accessPointRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spatialService.updateAccessPoint(id, "m", 0.0, 0.0, "n"))
                .hasMessage("Access Point not found");
    }

    @Test
    void deleteAccessPoint_deletesById() {
        UUID id = UUID.randomUUID();

        spatialService.deleteAccessPoint(id);

        verify(accessPointRepository).deleteById(id);
    }
}
