# Registro de uso de IA generativa (IAG)

**Nivel de uso declarado:** Nivel 3, colaboración con IAG (política del curso).
Cada integrante registra aquí qué le entregó a la IA, qué le pidió, qué obtuvo y qué revisó o cambió.

---

## Simón Reyes: pipeline CI/CD (Jenkins)

**Herramienta:** Claude Code (modelo Claude Opus 5.5), sesión del 6 de noviembre de 2026.

### Contenido propio entregado a la IA

- El enunciado del proyecto (`Proyecto_Final_CircleGuard.pdf`).
- El repositorio del equipo (rama `main` y ramas abiertas).
- La guía de rol "Pipeline CI/CD con Jenkins" con mis work items de Jira (CG-43, CG-44, CG-48 a CG-61, CG-64).
- La convención del equipo: Conventional Commits con descripciones en inglés.

### Prompts empleados (resumen)

1. Leer el enunciado y el repositorio, e indicar qué partes tiene la Parte 1 y cuáles me corresponden.
2. Explicar de dónde salían las responsabilidades de los demás integrantes y los nombres de las ramas.
3. Ordenar mis tareas: cuáles se pueden hacer hoy sin depender de nadie y cuáles dependen de los servidores, Dockerfiles o compose.
4. Separar qué puede escribir la IA y qué debo configurar yo (Jenkins, GitHub, SonarQube, Discord).
5. Escribir todo el código que no depende de mí, solo con commits locales (sin push), para revisarlo juntos.

### Qué produjo la IA

| Entregable | Archivos |
|---|---|
| Shared Library y Jenkinsfile (CG-50 a CG-60) | `Jenkinsfile`, `jenkins/vars/*.groovy` |
| Job de rollback (CG-61) | `jenkins/Jenkinsfile.rollback` |
| Imagen de Jenkins con plugins (CG-48) | `jenkins/local/`, `jenkins/README.md` |
| JaCoCo, Sonar y tarea `integrationTest` en Gradle | `build.gradle.kts` |
| Prueba de integración QR auth → gateway (CG-43) | `services/circleguard-gateway-service/src/test/.../QrAuthGatewayIntegrationTest.java` |
| Base de Playwright (CG-44) | `tests/e2e-playwright/` |
| Diagrama del pipeline (CG-64) | `docs/arquitectura/pipeline.md` |

### Revisión crítica y cambios respecto a la guía

- **El wrapper de Gradle no estaba en el repositorio** (`*.jar` en `.gitignore`), así que `./gradlew` fallaba en cualquier clon limpio. Se regeneró y se agregó una excepción.
- **`./gradlew :services:<svc>:sonar` no existe**: el plugin de SonarQube solo crea la tarea en el proyecto raíz. Se verificó localmente y se cambió por `./gradlew sonar -PsonarService=<svc>`, que omite los demás módulos.
- **`agent none` en la etapa de aprobación no basta** si el pipeline tiene `agent any` global: el ejecutor seguiría ocupado. Se dejó el pipeline sin agente global y cada etapa pide el suyo.
- **La prueba del QR depende de auth sin sus dependencias transitivas**: si se traían, Spring Security entraba al classpath de gateway y rompía su `@WebMvcTest`.
- **Las IPs no se escriben en el repositorio**: se leen de variables globales de Jenkins (`CG_*`).
- La prueba de integración se ejecutó localmente con Docker (8 casos en verde contra Redis real).

### Pendiente de verificar en el servidor

El quality gate, la publicación en Nexus, los despliegues, los E2E y las notificaciones se validan cuando estén el servidor de herramientas (Darío) y los Dockerfiles y compose (Luis).
