package com.circleguard.promotion.service;

import com.circleguard.promotion.exception.FenceException;
import com.circleguard.promotion.model.graph.CircleNode;
import com.circleguard.promotion.model.graph.UserNode;
import com.circleguard.promotion.model.jpa.SystemSettings;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests (no Spring context, no Neo4j/Redis/Kafka) for the propagation,
 * cache-sync and fence-window rules of {@link HealthStatusService}.
 * The Neo4jClient fluent API is stubbed so every bind()/to() returns the same spec.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HealthStatusServicePropagationTest {

    @Mock
    private UserNodeRepository userNodeRepository;
    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private CircleNodeRepository circleNodeRepository;

    @Mock
    private Neo4jClient.UnboundRunnableSpec querySpec;
    @Mock
    private Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> bindSpec;
    @Mock
    private Neo4jClient.RecordFetchSpec<Map<String, Object>> fetchSpec;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private HealthStatusService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUpNeo4jChain() {
        when(neo4jClient.query(anyString())).thenReturn(querySpec);
        when(querySpec.bind(any())).thenReturn((Neo4jClient.OngoingBindSpec) bindSpec);
        when(bindSpec.to(anyString())).thenReturn(querySpec);
        when(querySpec.fetch()).thenReturn(fetchSpec);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(circleNodeRepository.findNewlyFencedCircles(anyString())).thenReturn(List.of());
    }

    private static SystemSettings settings(int fenceDays) {
        return SystemSettings.builder().encounterWindowDays(14).mandatoryFenceDays(fenceDays)
                .unconfirmedFencingEnabled(true).autoThresholdSeconds(3600L).build();
    }

    private static List<Map<String, String>> contacts(int n, String status) {
        List<Map<String, String>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(Map.of("id", "c" + i, "status", status));
        }
        return list;
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateStatus_confirmed_cachesAffectedContactsAndBroadcastsPriorityAlert() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of(
                "sourceId", "u1",
                "affectedContacts", List.of(Map.of("id", "c1", "status", "SUSPECT")))));

        service.updateStatus("u1", "CONFIRMED");

        ArgumentCaptor<Map<String, String>> cache = ArgumentCaptor.forClass(Map.class);
        verify(valueOps).multiSet(cache.capture());
        assertThat(cache.getValue())
                .containsEntry("user:status:u1", "CONFIRMED")
                .containsEntry("user:status:c1", "SUSPECT");

        verify(kafkaTemplate).send(eq("promotion.status.changed"), eq("u1"), any());
        ArgumentCaptor<Object> alert = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("alert.priority"), eq("u1"), alert.capture());
        assertThat((Map<String, Object>) alert.getValue())
                .containsEntry("eventType", "CONFIRMED_CASE")
                .containsEntry("affectedCount", 1);
    }

    @Test
    void updateStatus_bindsIdStatusAndEncounterWindowThreshold() {
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(settings(14)));
        when(fetchSpec.one()).thenReturn(Optional.empty());

        long before = System.currentTimeMillis();
        service.updateStatus("u1", "SUSPECT");

        verify(querySpec).bind("u1");
        verify(querySpec).bind("SUSPECT");
        ArgumentCaptor<Object> bound = ArgumentCaptor.forClass(Object.class);
        verify(querySpec, times(3)).bind(bound.capture());
        long threshold = (Long) bound.getAllValues().get(2);
        long fourteenDaysMs = 14L * 24 * 60 * 60 * 1000;
        assertThat(threshold).isBetween(before - fourteenDaysMs - 1000, before - fourteenDaysMs + 1000);
    }

    @Test
    void updateStatus_noGraphResult_doesNotTouchCacheOrKafka() {
        when(fetchSpec.one()).thenReturn(Optional.empty());

        service.updateStatus("ghost", "SUSPECT");

        verifyNoInteractions(kafkaTemplate);
        verify(valueOps, never()).multiSet(anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateStatus_suspectWithMoreThanTwentyContacts_emitsLargeOutbreakAlert() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of("affectedContacts", contacts(21, "PROBABLE"))));

        service.updateStatus("u1", "SUSPECT");

        ArgumentCaptor<Object> alert = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("alert.priority"), eq("u1"), alert.capture());
        assertThat((Map<String, Object>) alert.getValue())
                .containsEntry("eventType", "LARGE_OUTBREAK")
                .containsEntry("affectedCount", 21);
    }

    @Test
    void updateStatus_suspectWithFewContacts_doesNotEmitPriorityAlert() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of("affectedContacts", contacts(3, "PROBABLE"))));

        service.updateStatus("u1", "SUSPECT");

        verify(kafkaTemplate).send(eq("promotion.status.changed"), eq("u1"), any());
        verify(kafkaTemplate, never()).send(eq("alert.priority"), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateStatus_moreThan2000CacheEntries_writesRedisInBatches() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of("affectedContacts", contacts(2500, "SUSPECT"))));
        // The service reuses (and clears) one map per batch, so record the size at call time
        List<Integer> batchSizes = new ArrayList<>();
        doAnswer(inv -> batchSizes.add(((Map<String, String>) inv.getArgument(0)).size()))
                .when(valueOps).multiSet(anyMap());

        service.updateStatus("u1", "CONFIRMED");

        // 2500 contacts + the source user = 2501 keys, split as 2000 + 501
        assertThat(batchSizes).containsExactly(2000, 501);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateStatus_circleBecomesFullyFenced_broadcastsCircleFencedEvent() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of("affectedContacts", List.of())));
        CircleNode circle = CircleNode.builder().id(42L).name("Lab").locationId("room-1").build();
        when(circleNodeRepository.findNewlyFencedCircles("u1")).thenReturn(List.of(circle));

        service.updateStatus("u1", "PROBABLE");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("circle.fenced"), eq("42"), payload.capture());
        assertThat((Map<String, Object>) payload.getValue())
                .containsEntry("circleId", "42")
                .containsEntry("locationId", "room-1")
                .containsEntry("name", "Lab");
    }

    @Test
    void updateStatus_activeInsideFenceWindow_throwsFenceExceptionAndSkipsGraph() {
        long twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        when(userNodeRepository.findById("u1")).thenReturn(Optional.of(
                UserNode.builder().anonymousId("u1").status("SUSPECT").statusUpdatedAt(twoDaysAgo).build()));
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(settings(14)));

        assertThatThrownBy(() -> service.updateStatus("u1", "ACTIVE"))
                .isInstanceOf(FenceException.class)
                .hasMessageContaining("mandatory fence window");
        verify(neo4jClient, never()).query(anyString());
    }

    @Test
    void updateStatus_activeWithAdminOverride_skipsFenceCheck() {
        when(fetchSpec.one()).thenReturn(Optional.empty());

        service.updateStatus("u1", "ACTIVE", true);

        verify(userNodeRepository, never()).findById(anyString());
        verify(neo4jClient).query(anyString());
    }

    @Test
    void getCachedStatus_readsStatusKeyFromRedis() {
        when(valueOps.get("user:status:u1")).thenReturn("PROBABLE");

        assertThat(service.getCachedStatus("u1")).isEqualTo("PROBABLE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveStatus_releasesSourceAndBothHopsInCacheAndBroadcastsActive() {
        when(fetchSpec.one()).thenReturn(
                Optional.of(Map.of("releasedIds", List.of("l1a", "l1b"))),
                Optional.of(Map.of("releasedIds", List.of("l2a"))));

        service.resolveStatus("u1", true);

        verify(querySpec).run();
        verify(querySpec).bind(List.of("l1a", "l1b"));
        ArgumentCaptor<Map<String, String>> cache = ArgumentCaptor.forClass(Map.class);
        verify(valueOps).multiSet(cache.capture());
        assertThat(cache.getValue()).containsOnly(
                Map.entry("user:status:u1", "ACTIVE"),
                Map.entry("user:status:l1a", "ACTIVE"),
                Map.entry("user:status:l1b", "ACTIVE"),
                Map.entry("user:status:l2a", "ACTIVE"));
        verify(kafkaTemplate).send(eq("promotion.status.changed"), eq("u1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveStatus_fenceWindowElapsed_resolvesOnlySourceWhenNoNeighbors() {
        long twentyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20);
        when(userNodeRepository.findById("u1")).thenReturn(Optional.of(
                UserNode.builder().anonymousId("u1").status("PROBABLE").statusUpdatedAt(twentyDaysAgo).build()));
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.of(settings(14)));
        when(fetchSpec.one()).thenReturn(Optional.empty());

        service.resolveStatus("u1");

        ArgumentCaptor<Map<String, String>> cache = ArgumentCaptor.forClass(Map.class);
        verify(valueOps).multiSet(cache.capture());
        assertThat(cache.getValue()).containsOnly(Map.entry("user:status:u1", "ACTIVE"));
    }

    @Test
    void resolveStatus_fencedUserWithoutSettings_throwsIllegalState() {
        when(userNodeRepository.findById("u1")).thenReturn(Optional.of(
                UserNode.builder().anonymousId("u1").status("SUSPECT")
                        .statusUpdatedAt(System.currentTimeMillis()).build()));
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveStatus("u1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("System Settings not initialized");
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void promoteToRecovered_marksUserRecoveredWithThirtyDayImmunity() {
        when(fetchSpec.one()).thenReturn(Optional.empty());

        service.promoteToRecovered("u1");

        verify(neo4jClient).query("MATCH (u:User {anonymousId: $id}) SET u.status = 'RECOVERED'");
        verify(valueOps).set("user:status:u1", "RECOVERED");
        verify(redisTemplate).expire("user:status:u1", Duration.ofDays(30));
    }
}
