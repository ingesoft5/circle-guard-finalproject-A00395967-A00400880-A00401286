package com.circleguard.identity.integration;

import com.circleguard.auth.client.IdentityClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración auth-service -> identity-service por HTTP (CG-42).
 *
 * <p>En el login, auth-service le pide a identity-service el identificador
 * anónimo del usuario ({@code POST /api/v1/identities/map}) y es ese id, nunca el
 * usuario real, el que viaja en el JWT. Esta prueba une las dos piezas reales:
 * <ul>
 *   <li>el cliente es {@link IdentityClient} de auth-service (código real, mismo
 *       RestTemplate y mismo contrato JSON que en producción),</li>
 *   <li>el servidor es identity-service completo (controlador, seguridad,
 *       cifrado de la bóveda y JPA) escuchando en un puerto HTTP real,</li>
 *   <li>los datos quedan en un PostgreSQL 16 real con el esquema creado por las
 *       migraciones Flyway del servicio.</li>
 * </ul>
 * Además verifica la promesa de privacidad del sistema: la identidad real no se
 * guarda en texto plano.
 *
 * <p>Puerto fijo 8083: hoy IdentityClient tiene la URL escrita en el código
 * (http://localhost:8083). Cuando se vuelva configurable, cambiar a RANDOM_PORT.
 * Solo se simula el KafkaTemplate de auditoría (Kafka no es parte de este flujo).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=8083")
class AuthToIdentityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void realPostgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // El application.yml de pruebas usa H2 + create-drop; aquí va el esquema real de Flyway.
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");   // el esquema lo crea Flyway
        r.add("spring.flyway.enabled", () -> "true");
        // El jar de auth-service (por IdentityClient) también trae db/migration en el
        // classpath; se fijan solo las migraciones de identity para no mezclarlas.
        r.add("spring.flyway.locations", () -> "filesystem:src/main/resources/db/migration");
    }

    @MockBean
    private KafkaTemplate<String, Object> auditEvents;

    @Autowired
    private JdbcTemplate jdbc;

    /** Cliente real de auth-service, igual que lo usa LoginController. */
    private final IdentityClient authIdentityClient = new IdentityClient();

    @Test
    @DisplayName("auth obtiene un id anónimo estable para el mismo usuario")
    void sameUser_alwaysGetsSameAnonymousId() {
        UUID first = authIdentityClient.getAnonymousId("ana.gomez@u.icesi.edu.co");
        UUID second = authIdentityClient.getAnonymousId("ana.gomez@u.icesi.edu.co");

        assertThat(first).isNotNull().isEqualTo(second);
        assertThat(mappingsFor(first)).isEqualTo(1);
    }

    @Test
    @DisplayName("usuarios distintos reciben ids anónimos distintos")
    void differentUsers_getDifferentAnonymousIds() {
        UUID ana = authIdentityClient.getAnonymousId("ana.ruiz@u.icesi.edu.co");
        UUID juan = authIdentityClient.getAnonymousId("juan.perez@u.icesi.edu.co");

        assertThat(ana).isNotEqualTo(juan);
    }

    @Test
    @DisplayName("la identidad real no queda en texto plano en la base de datos")
    void realIdentity_isNeverStoredInPlainText() {
        String realIdentity = "maria.lopez@u.icesi.edu.co";
        UUID anonymousId = authIdentityClient.getAnonymousId(realIdentity);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT real_identity_encrypted, identity_hash FROM identity_mappings WHERE anonymous_id = ?",
                anonymousId);

        String stored = new String((byte[]) row.get("real_identity_encrypted"), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain(realIdentity);
        assertThat((String) row.get("identity_hash")).doesNotContain(realIdentity).hasSizeLessThanOrEqualTo(64);

        List<String> anyColumnWithPlainText = jdbc.queryForList(
                "SELECT anonymous_id::text FROM identity_mappings WHERE identity_hash = ? OR salt = ?",
                String.class, realIdentity, realIdentity);
        assertThat(anyColumnWithPlainText).isEmpty();
    }

    private int mappingsFor(UUID anonymousId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM identity_mappings WHERE anonymous_id = ?", Integer.class, anonymousId);
        return n == null ? 0 : n;
    }
}
