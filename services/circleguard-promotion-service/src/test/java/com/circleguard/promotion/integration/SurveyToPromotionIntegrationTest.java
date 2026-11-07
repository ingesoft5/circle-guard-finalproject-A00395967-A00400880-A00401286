package com.circleguard.promotion.integration;

import com.circleguard.promotion.repository.jpa.SystemSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Integración form-service -> Kafka -> promotion-service (CG-41).
 *
 * <p>Es el flujo de negocio central: un estudiante reporta síntomas en el
 * cuestionario, form-service publica {@code survey.submitted} y promotion-service
 * lo consume y lo promueve en el grafo de contactos.
 *
 * <p>Enfoque "contrato real sobre infraestructura real" (no se pueden levantar
 * dos aplicaciones Spring Boot en una JVM):
 * <ul>
 *   <li><b>Productor</b>: un KafkaTemplate configurado EXACTAMENTE como
 *       form-service (StringSerializer + JsonSerializer de Spring, ver su
 *       application.yml) que envía el MISMO evento que arma
 *       {@code HealthSurveyService.submitSurvey}: {@code Map.of(anonymousId (UUID),
 *       hasSymptoms, timestamp)} con la clave = anonymousId.</li>
 *   <li><b>Consumidor</b>: promotion-service real (SurveyListener ->
 *       HealthStatusService), con su propia configuración de deserialización.</li>
 *   <li><b>Infraestructura real</b>: Kafka, Neo4j, Redis y PostgreSQL con
 *       Testcontainers, en las mismas versiones mayores que docker-compose.</li>
 * </ul>
 * Sin mocks en la frontera: si form-service cambia el tópico, el formato del
 * evento o el serializador, o promotion cambia el deserializador, esta prueba falla.
 * Solo se simula SystemSettingsRepository (parámetros de administración en
 * PostgreSQL, que no son parte de este flujo) para usar los valores por defecto.
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class SurveyToPromotionIntegrationTest {

    private static final String TOPIC = "survey.submitted";
    private static final String STATUS_KEY_PREFIX = "user:status:";
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5.12").withAdminPassword("password");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.neo4j.uri", NEO4J::getBoltUrl);
        r.add("spring.neo4j.authentication.username", () -> "neo4j");
        r.add("spring.neo4j.authentication.password", () -> "password");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Productor con la configuración de form-service (application.yml de form). */
    private static KafkaTemplate<String, Object> formServiceProducer;

    @BeforeAll
    static void createFormServiceProducer() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        formServiceProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @AfterAll
    static void closeProducer() {
        formServiceProducer.destroy();
    }

    @MockBean
    private SystemSettingsRepository systemSettingsRepository;

    @Autowired
    private Neo4jClient neo4j;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanGraph() {
        when(systemSettingsRepository.getSettings()).thenReturn(Optional.empty());   // valores por defecto (14 días)
        neo4j.query("MATCH (n) DETACH DELETE n").run();
    }

    @Test
    @DisplayName("Encuesta con síntomas: el usuario pasa a SUSPECT y su contacto reciente a PROBABLE")
    void surveyWithSymptoms_promotesUserAndPropagatesToRecentContact() {
        String student = UUID.randomUUID().toString();
        String contact = UUID.randomUUID().toString();
        createUser(student, "ACTIVE");
        createUser(contact, "ACTIVE");
        encounter(student, contact, System.currentTimeMillis() - Duration.ofDays(2).toMillis());

        publishSurvey(student, true);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(statusInGraph(student)).isEqualTo("SUSPECT");
            assertThat(statusInGraph(contact)).isEqualTo("PROBABLE");
        });
        // El estado también queda en Redis, que es lo que consulta gateway-service en la portería.
        assertThat(redis.opsForValue().get(STATUS_KEY_PREFIX + student)).isEqualTo("SUSPECT");
        assertThat(redis.opsForValue().get(STATUS_KEY_PREFIX + contact)).isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("El cambio de estado sale a promotion.status.changed como lo lee notification-service")
    void surveyWithSymptoms_publishesStatusChangeForNotificationService() throws Exception {
        String student = UUID.randomUUID().toString();
        createUser(student, "ACTIVE");

        // Consumidor con la configuración de notification-service: StringDeserializer
        // y luego ObjectMapper.readTree (ExposureNotificationListener).
        try (KafkaConsumer<String, String> notification = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "notification-contract-" + student,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            notification.subscribe(List.of("promotion.status.changed"));

            publishSurvey(student, true);

            List<ConsumerRecord<String, String>> forStudent = new ArrayList<>();
            await().atMost(TIMEOUT).untilAsserted(() -> {
                notification.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (student.equals(r.key())) forStudent.add(r);
                });
                assertThat(forStudent).isNotEmpty();
            });

            JsonNode event = new ObjectMapper().readTree(forStudent.get(0).value());
            assertThat(event.path("anonymousId").asText()).isEqualTo(student);
            assertThat(event.path("status").asText()).isEqualTo("SUSPECT");
        }
        // Usuario SIN contactos: su estado también debe llegar a Redis, o el gateway
        // lo dejaría entrar al campus (antes de la corrección de la consulta no llegaba).
        assertThat(redis.opsForValue().get(STATUS_KEY_PREFIX + student)).isEqualTo("SUSPECT");
    }

    @Test
    @DisplayName("Encuentro fuera de la ventana de 14 días: el contacto no se promueve")
    void surveyWithSymptoms_doesNotPropagateToContactOutsideWindow() {
        String student = UUID.randomUUID().toString();
        String oldContact = UUID.randomUUID().toString();
        createUser(student, "ACTIVE");
        createUser(oldContact, "ACTIVE");
        encounter(student, oldContact, System.currentTimeMillis() - Duration.ofDays(20).toMillis());

        publishSurvey(student, true);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusInGraph(student)).isEqualTo("SUSPECT"));
        assertThat(statusInGraph(oldContact)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Encuesta sin síntomas: el estado no cambia")
    void surveyWithoutSymptoms_keepsUserActive() {
        String student = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        createUser(student, "ACTIVE");
        createUser(sentinel, "ACTIVE");

        publishSurvey(student, false);
        // Mensaje "centinela" enviado DESPUÉS. El tópico autocreado tiene una sola
        // partición, así que Kafka entrega en orden: cuando el centinela ya se
        // procesó, el evento sin síntomas también se consumió. Así se prueba un
        // "no cambió" sin depender de un sleep.
        publishSurvey(sentinel, true);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusInGraph(sentinel)).isEqualTo("SUSPECT"));
        assertThat(statusInGraph(student)).isEqualTo("ACTIVE");
    }

    // --- helpers -----------------------------------------------------------------

    /** El mismo evento y la misma clave que publica form-service (HealthSurveyService.submitSurvey). */
    private void publishSurvey(String anonymousId, boolean hasSymptoms) {
        Map<String, Object> event = Map.of(
                "anonymousId", UUID.fromString(anonymousId),
                "hasSymptoms", hasSymptoms,
                "timestamp", System.currentTimeMillis());
        formServiceProducer.send(TOPIC, anonymousId, event).join();
    }

    private void createUser(String id, String status) {
        neo4j.query("CREATE (:User {anonymousId: $id, status: $status})")
                .bindAll(Map.of("id", id, "status", status)).run();
    }

    private void encounter(String a, String b, long startTime) {
        neo4j.query("""
                MATCH (a:User {anonymousId: $a}), (b:User {anonymousId: $b})
                CREATE (a)-[:ENCOUNTERED {startTime: $t, isValid: true}]->(b)
                """).bindAll(Map.of("a", a, "b", b, "t", startTime)).run();
    }

    private String statusInGraph(String id) {
        return neo4j.query("MATCH (u:User {anonymousId: $id}) RETURN u.status AS status")
                .bind(id).to("id")
                .fetchAs(String.class).one().orElse(null);
    }
}
