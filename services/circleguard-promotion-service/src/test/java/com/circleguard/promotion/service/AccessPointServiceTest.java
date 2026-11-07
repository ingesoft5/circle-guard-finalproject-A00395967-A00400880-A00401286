package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
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
class AccessPointServiceTest {

    @Mock
    private AccessPointRepository accessPointRepository;
    @Mock
    private FloorRepository floorRepository;

    @InjectMocks
    private AccessPointService accessPointService;

    @Test
    void registerAccessPoint_existingFloor_savesAccessPointOnThatFloor() {
        UUID floorId = UUID.randomUUID();
        Floor floor = Floor.builder().id(floorId).floorNumber(1).build();
        when(floorRepository.findById(floorId)).thenReturn(Optional.of(floor));
        when(accessPointRepository.save(any(AccessPoint.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessPoint ap = accessPointService.registerAccessPoint(floorId, "00:11:22:33:44:55", 10.0, 20.0, "Lobby AP");

        assertThat(ap.getFloor()).isSameAs(floor);
        assertThat(ap.getMacAddress()).isEqualTo("00:11:22:33:44:55");
        assertThat(ap.getCoordinateX()).isEqualTo(10.0);
        assertThat(ap.getCoordinateY()).isEqualTo(20.0);
        assertThat(ap.getName()).isEqualTo("Lobby AP");
    }

    @Test
    void registerAccessPoint_unknownFloor_throwsAndDoesNotSave() {
        UUID floorId = UUID.randomUUID();
        when(floorRepository.findById(floorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessPointService.registerAccessPoint(floorId, "mac", 0.0, 0.0, "n"))
                .hasMessage("Floor not found");
        verify(accessPointRepository, never()).save(any());
    }

    @Test
    void getAccessPoint_unknownId_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(accessPointRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(accessPointService.getAccessPoint(id)).isEmpty();
    }

    @Test
    void getAccessPointsByFloor_returnsRepositoryResult() {
        UUID floorId = UUID.randomUUID();
        List<AccessPoint> aps = List.of(AccessPoint.builder().name("A").build());
        when(accessPointRepository.findByFloorId(floorId)).thenReturn(aps);

        assertThat(accessPointService.getAccessPointsByFloor(floorId)).isEqualTo(aps);
    }

    @Test
    void updateAccessPoint_existingAp_overwritesFieldsAndSaves() {
        UUID id = UUID.randomUUID();
        AccessPoint ap = AccessPoint.builder().id(id).macAddress("old").coordinateX(0.0).coordinateY(0.0).name("old").build();
        when(accessPointRepository.findById(id)).thenReturn(Optional.of(ap));
        when(accessPointRepository.save(ap)).thenReturn(ap);

        AccessPoint result = accessPointService.updateAccessPoint(id, "new", 7.0, 8.0, "renamed");

        assertThat(result.getMacAddress()).isEqualTo("new");
        assertThat(result.getCoordinateX()).isEqualTo(7.0);
        assertThat(result.getCoordinateY()).isEqualTo(8.0);
        assertThat(result.getName()).isEqualTo("renamed");
        verify(accessPointRepository).save(ap);
    }

    @Test
    void updateAccessPoint_unknownAp_throws() {
        UUID id = UUID.randomUUID();
        when(accessPointRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessPointService.updateAccessPoint(id, "m", 0.0, 0.0, "n"))
                .hasMessage("Access Point not found");
    }

    @Test
    void deleteAccessPoint_deletesById() {
        UUID id = UUID.randomUUID();

        accessPointService.deleteAccessPoint(id);

        verify(accessPointRepository).deleteById(id);
    }
}
