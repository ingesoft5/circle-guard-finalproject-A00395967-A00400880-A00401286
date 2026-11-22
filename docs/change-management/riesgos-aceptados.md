# Registro de Riesgos Aceptados (excepciones de seguridad)

Este registro documenta las vulnerabilidades que el equipo **decide aceptar temporalmente** en lugar de corregirlas en el sprint. Cada excepción es un cambio normal según la [Política de Aprobación](politica-de-aprobacion.md) (sección 4, numeral 4) y se aprueba en el PR que la agrega. La excepción vive en `.trivyignore` con el mismo ID; este registro es la fuente de los motivos, controles y fechas.

---

## 1. Criterios de aceptación

Una vulnerabilidad solo se acepta si cumple todo lo siguiente:

1. Se corrige **solo** actualizando una dependencia cuyo cambio está fuera del alcance del sprint actual.
2. Existe al menos un **control compensatorio** verificable en el repo (red, configuración o exposición).
3. Tiene **fecha de revisión** y responsable. Ninguna excepción es permanente.

---

## 2. Excepciones vigentes

| ID | Componente afectado | Severidad | Motivo | Controles que mitigan | Responsable | Propuesta / aprobación | Revisión | Aprobó |
|---|---|---|---|---|---|---|---|---|
| CVE-2026-47884 | `spring-webmvc` 6.1.5 (Spring Boot 3.2.4) | CRITICAL | Trivy reporta la corrección solo en Spring Framework 7.0.9, que exige migrar a Spring Boot 3.5 o 4. Cambio mayor, fuera del Sprint 2. | Servicios internos sin puerto publicado; Actuator no expuesto por el proxy público (nginx, CG-118). | Equipo | Propuesta 2026-11-20 (Simón); aprobación: 2026-11-22 (Luis Cadena) | 2026-12-20 | Luis Cadena |
| CVE-2026-47892 | `spring-webflux` 6.1.5 (Spring Boot 3.2.4) | CRITICAL | Trivy reporta la corrección solo en Spring Framework 7.0.9, que exige migrar a Spring Boot 3.5 o 4. Cambio mayor, fuera del Sprint 2. | Servicios internos sin puerto publicado; Actuator no expuesto por el proxy público (nginx, CG-118). | Equipo | Propuesta 2026-11-20 (Simón); aprobación: 2026-11-22 (Luis Cadena) | 2026-12-20 | Luis Cadena |
| CVE-2026-22732 | `spring-security-web` 6.2.x (Spring Boot 3.2.4) | CRITICAL | Trivy reporta la corrección en Spring Security 6.5.9 y 7.0.4, que exigen migrar de Spring Boot 3.2. Cambio mayor, fuera del Sprint 2. | Servicios internos sin puerto publicado; Actuator no expuesto por el proxy público (nginx, CG-118). | Equipo | Propuesta 2026-11-20 (Simón); aprobación: 2026-11-22 (Luis Cadena) | 2026-12-20 | Luis Cadena |

> Nota: los controles reducen la exposición, pero **no corrigen** la vulnerabilidad. La excepción se cierra solo con la actualización (sección 4).

---

## 3. Evidencia de los controles

| Control | Dónde está | Estado de la evidencia |
|---|---|---|
| Servicios internos sin exposición pública: solo `web` publica un puerto (`${WEB_PORT:-80}:8080`). | `deploy/docker-compose.yml`, línea 330 (rama `origin/main`). | Verificado en el repo. |
| Actuator no expuesto por el proxy público: nginx responde 404 a `/svc/<servicio>/actuator`. | `mobile/nginx/default.conf`, líneas 38-40 (rama `origin/main`). | Verificado en el repo. Los servicios siguen exponiendo `/actuator` dentro de la red de contenedores (healthchecks en `deploy/docker-compose.yml`). |
| Versión de Spring Boot afectada (3.2.4). | `build.gradle.kts`, líneas 2, 33-34 y `services/circleguard-identity-service/build.gradle.kts`, líneas 10-11. | Verificado en el repo. |
| Versión de Spring Framework afectada (6.1.5) y corrección (7.0.9). | `.trivyignore` (rama `origin/main`, commit `55aba92`), líneas 17-21. Lo redactó Simón según el reporte de Trivy. | Evidencia parcial: confirmar con `./gradlew dependencies` (**pendiente de evidencia**). |
| Identificadores CVE, severidad y enlace al reporte de Trivy. | `.trivyignore` (líneas 19-21, `origin/main`). | Los IDs están en el repo. **Pendiente de evidencia:** enlace al build de Jenkins donde Trivy los reporta. |

---

## 4. Condiciones de cierre

La excepción se cierra cuando se cumpla una de estas dos condiciones:

1. Se actualiza Spring Boot a 3.5 o 4 (o la versión que incluya la corrección), se elimina la entrada de `.trivyignore` y el build de Trivy queda en verde.
2. La revisión del 2026-12-20 decide renovar la excepción, con nueva fecha y motivo escrito.

Si una excepción vence sin revisión, el pipeline no debe considerarse aprobado para producción hasta que se resuelva.

---

## 5. Historial

| Fecha | Cambio | Quién |
|---|---|---|
| 2026-11-20 | Simón propone en `.trivyignore` la excepción de los 3 CVE CRITICAL de Spring (pendiente de aprobación en el PR). | Simón |
| 2026-11-22 | Se documenta el registro en `docs/change-management/riesgos-aceptados.md` con controles, responsable y revisión. Aprobación: Luis Cadena. | Equipo (Luis Cadena) |
