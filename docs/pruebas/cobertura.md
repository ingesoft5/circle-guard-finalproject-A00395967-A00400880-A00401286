# Cobertura de pruebas unitarias (CG-35 a CG-40)

Meta de E1.6: **≥ 60 % de líneas** en los 5 servicios núcleo (auth, identity,
promotion, form y gateway). Se mide con JaCoCo y **la impone el build**:
`./gradlew check` falla si un servicio núcleo baja de 60 %
(`jacocoTestCoverageVerification` en `build.gradle.kts`). Jenkins publica el XML
(`build/reports/jacoco/test/jacocoTestReport.xml`) y SonarQube lo lee.

## Antes y después

Medido con `./gradlew test jacocoTestReport` (solo pruebas unitarias; las de
integración corren aparte en `integrationTest`).

| Servicio | Antes | Después | Líneas cubiertas | Pruebas unitarias |
|---|---:|---:|---:|---:|
| auth | 33,8 % | **98,1 %** | 151 / 154 | 43 |
| identity | 45,9 % | **94,1 %** | 80 / 85 | 20 |
| promotion | 26,4 % | **81,4 %** | 583 / 716 | 109 |
| form | 25,3 % | **97,7 %** | 85 / 87 | 37 |
| gateway | 88,9 % | **88,9 %** | 16 / 18 | 3 |

"Antes" ya aplica las mismas exclusiones. Sin exclusiones, la línea base de
Simón era auth 29,1 %, gateway 81,0 %, y identity, promotion y form sin reporte,
porque sus pruebas fallaban.

## Exclusiones (y por qué)

| Patrón | Qué es | Justificación |
|---|---|---|
| `**/*Application*` | clase `main` de Spring Boot | solo arranca el contexto |
| `**/config/**` | beans de configuración y seguridad | cableado de Spring, sin reglas de negocio; la seguridad se prueba en los `@WebMvcTest` con `@Import(SecurityConfig.class)` |
| `**/dto/**` | objetos de transferencia | datos sin comportamiento |
| `**/model/**` | entidades JPA y nodos Neo4j | getters y setters generados por Lombok |

Todo lo demás se mide: servicios, controladores, listeners de Kafka, filtros JWT
y clientes HTTP.

## Estrategia

- **Servicios:** JUnit 5 + Mockito (`@Mock` para repositorios, `Neo4jClient`,
  `KafkaTemplate` y Redis; `@InjectMocks` para el servicio). Son rápidas y
  cubren la lógica de negocio: firma y claims del JWT, expiración del QR, cadena
  dual LDAP → usuarios locales, bóveda de identidad (hash y cifrado), publicación
  de eventos y máquina de estados.
- **Controladores:** `@WebMvcTest` + `MockMvc` + `@MockBean`, con la seguridad
  real importada: códigos HTTP, validación y permisos (`@WithMockUser`).
- **Comportamiento, no líneas:** cada prueba verifica un resultado (valor, JSON,
  estado HTTP, evento publicado con su tópico y su payload, o una excepción
  esperada).

## Pruebas que estaban rotas en `main` (PR #12)

| Servicio | Causa | Arreglo |
|---|---|---|
| identity (4) | el `application.yml` de pruebas no tenía `jwt.secret` → el filtro recibía el texto `${jwt.secret}` (104 bits) | secreto de prueba ≥ 32 bytes |
| promotion (2) | la prueba daba la autoridad `HEALTH_CENTER`, pero el controlador pide `hasRole` (`ROLE_HEALTH_CENTER`, que es lo que trae el JWT real) | `@WithMockUser(roles = ...)` |
| promotion (3 clases) | usan Neo4j con Testcontainers (necesitan Docker) | `@Tag("integration")` |
| form (1) | `@SpringBootTest` levantaba Flyway contra un PostgreSQL real | `@WebMvcTest` |

## Cómo reproducir

```bash
./gradlew test jacocoTestReport          # reportes en services/*/build/reports/jacoco/test/html/index.html
./gradlew check -x integrationTest       # incluye la verificación de 60 %
```
