package com.circleguard.promotion.service;

import com.circleguard.promotion.model.AccessPoint;
import com.circleguard.promotion.model.Building;
import com.circleguard.promotion.model.Floor;
import com.circleguard.promotion.repository.jpa.AccessPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocationResolutionServiceTest {

    private static final String AP_MAC = "AA:BB:CC:DD:EE:FF";
    private static final String DEVICE_MAC = "11:22:33:44:55:66";

    @Mock
    private AccessPointRepository accessPointRepository;
    @Mock
    private MacSessionRegistry sessionRegistry;
    @Mock
    private GraphService graphService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private LocationResolutionService service;

    private AccessPoint accessPoint;
    private UUID buildingId;
    private UUID floorId;

    @BeforeEach
    void setUp() {
        buildingId = UUID.randomUUID();
        floorId = UUID.randomUUID();
        Building building = Building.builder().id(buildingId).name("Main").build();
        Floor floor = Floor.builder().id(floorId).building(building).floorNumber(2).build();
        accessPoint = AccessPoint.builder().id(UUID.randomUUID()).macAddress(AP_MAC)
                .floor(floor).coordinateX(4.0).coordinateY(6.0).name("AP-2").build();
    }

    @Test
    void processSignal_unknownAccessPoint_ignoresSignal() {
        when(accessPointRepository.findByMacAddress(AP_MAC)).thenReturn(Optional.empty());

        service.processSignal(AP_MAC, DEVICE_MAC, -50.0);

        verifyNoInteractions(sessionRegistry, kafkaTemplate, graphService, redisTemplate);
    }

    @Test
    void processSignal_deviceWithoutSession_doesNotEmitEvent() {
        when(accessPointRepository.findByMacAddress(AP_MAC)).thenReturn(Optional.of(accessPoint));
        when(sessionRegistry.getAnonymousId(DEVICE_MAC)).thenReturn(null);

        service.processSignal(AP_MAC, DEVICE_MAC, -50.0);

        verifyNoInteractions(kafkaTemplate, graphService, redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processSignal_knownDevice_emitsAnonymizedProximityEvent() {
        when(accessPointRepository.findByMacAddress(AP_MAC)).thenReturn(Optional.of(accessPoint));
        when(sessionRegistry.getAnonymousId(DEVICE_MAC)).thenReturn("anon-1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(Set.of());

        service.processSignal(AP_MAC, DEVICE_MAC, -42.0);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("proximity.detected"), eq("anon-1"), event.capture());
        Map<String, Object> payload = (Map<String, Object>) event.getValue();
        assertThat(payload)
                .containsEntry("anonymousId", "anon-1")
                .containsEntry("buildingId", buildingId)
                .containsEntry("floorId", floorId)
                .containsEntry("rssi", -42.0)
                .containsEntry("coordinates", Map.of("x", 4.0, "y", 6.0))
                .containsKey("timestamp")
                .doesNotContainValue(DEVICE_MAC);
    }

    @Test
    void processSignal_otherUsersAtLocation_recordsEncountersWithOthersOnly() {
        String locationId = accessPoint.getId().toString();
        String key = "spatial:location:" + locationId;
        when(accessPointRepository.findByMacAddress(AP_MAC)).thenReturn(Optional.of(accessPoint));
        when(sessionRegistry.getAnonymousId(DEVICE_MAC)).thenReturn("anon-1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(key)).thenReturn(Set.of("anon-1", "anon-2", "anon-3"));

        service.processSignal(AP_MAC, DEVICE_MAC, -60.0);

        verify(graphService).recordEncounter("anon-1", "anon-2", locationId);
        verify(graphService).recordEncounter("anon-1", "anon-3", locationId);
        verify(graphService, never()).recordEncounter("anon-1", "anon-1", locationId);
        verify(setOperations).add(key, "anon-1");
        verify(redisTemplate).expire(key, Duration.ofMinutes(10));
        verify(graphService).detectAndFormCircles(locationId);
    }

    @Test
    void processSignal_emptyLocationSet_stillRegistersUserAndDetectsCircles() {
        String locationId = accessPoint.getId().toString();
        String key = "spatial:location:" + locationId;
        when(accessPointRepository.findByMacAddress(AP_MAC)).thenReturn(Optional.of(accessPoint));
        when(sessionRegistry.getAnonymousId(DEVICE_MAC)).thenReturn("anon-1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(key)).thenReturn(null);

        service.processSignal(AP_MAC, DEVICE_MAC, -60.0);

        verify(graphService, never()).recordEncounter(anyString(), anyString(), anyString());
        verify(setOperations).add(key, "anon-1");
        verify(graphService).detectAndFormCircles(locationId);
    }
}
