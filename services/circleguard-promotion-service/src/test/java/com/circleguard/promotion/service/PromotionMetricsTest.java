package com.circleguard.promotion.service;

import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CG-93: cada cambio de estado aplicado suma a circleguard_promotions_total{status}
 * y los contactos alcanzados a circleguard_contacts_affected_total.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionMetricsTest {

    @Mock private UserNodeRepository userNodeRepository;
    @Mock private Neo4jClient neo4jClient;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private SystemSettingsRepository systemSettingsRepository;
    @Mock private CircleNodeRepository circleNodeRepository;
    @Mock private Neo4jClient.UnboundRunnableSpec querySpec;
    @Mock private Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> bindSpec;
    @Mock private Neo4jClient.RecordFetchSpec<Map<String, Object>> fetchSpec;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private HealthStatusService service;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        Metrics.addRegistry(meters);
        when(neo4jClient.query(anyString())).thenReturn(querySpec);
        when(querySpec.bind(any())).thenReturn((Neo4jClient.OngoingBindSpec) bindSpec);
        when(bindSpec.to(anyString())).thenReturn(querySpec);
        when(querySpec.fetch()).thenReturn(fetchSpec);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(circleNodeRepository.findNewlyFencedCircles(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(meters);
    }

    @Test
    void appliedStatusChange_countsThePromotionAndItsAffectedContacts() {
        when(fetchSpec.one()).thenReturn(Optional.of(Map.of(
                "sourceId", "u1",
                "affectedContacts", List.of(
                        Map.of("id", "c1", "status", "PROBABLE"),
                        Map.of("id", "c2", "status", "PROBABLE")))));

        service.updateStatus("u1", "CONFIRMED");

        assertThat(meters.get(HealthStatusService.PROMOTIONS_METRIC).tag("status", "CONFIRMED").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get(HealthStatusService.AFFECTED_METRIC).counter().count()).isEqualTo(2.0);
    }

    @Test
    void unknownUser_isNotCounted() {
        when(fetchSpec.one()).thenReturn(Optional.empty());

        service.updateStatus("ghost", "SUSPECT");

        // El registro global puede conservar el contador de otra prueba: se mira el conteo.
        var counter = meters.find(HealthStatusService.PROMOTIONS_METRIC).tag("status", "SUSPECT").counter();
        assertThat(counter == null ? 0.0 : counter.count()).isZero();
    }
}
