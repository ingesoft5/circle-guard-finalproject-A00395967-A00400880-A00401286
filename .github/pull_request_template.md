## Clave de Jira
<!-- Especifique la clave del issue en Jira asociado a este cambio (ejemplo: CG-12) -->
Clave: `CG-`

---

## Tipo de cambio
<!--
Consulte la clasificación, criterios y flujo de aprobación en:
docs/change-management/politica-de-aprobacion.md
-->
- [ ] **Estándar:** Cambio rutinario y pre-aprobado de bajo riesgo (documentación en `docs/*`, mantenimiento menor en `chore/*`, suites de prueba en `test/*` o correcciones cosméticas sin impacto en APIs). Requiere 1 revisión por pares y CI en verde; despliegue automático a dev y stage tras merge a `main`.
- [ ] **Normal:** Cambio con impacto funcional o estructural (nuevas funcionalidades en `feat/*`, cambios en microservicios núcleo, esquema de bases de datos o arquitectura). Requiere 1 revisión en PR, CI verde y compuerta de aprobación manual en Jenkins (`stage('Aprobación')`) para pase a producción.
- [ ] **Emergencia:** Corrección expedita (*hotfix*) ante fallos críticos que degradan la operación en producción (bloqueo en torniquetes, vulnerabilidades severas o caída de middleware). Flujo acelerado con RFC y análisis post-mortem regularizado en 24 horas.

---

> [!IMPORTANT]
> **Convención obligatoria para el título del Pull Request:**
> El título del PR debe redactarse en **inglés**, en **modo imperativo** y siguiendo estrictamente la especificación [Conventional Commits](https://www.conventionalcommits.org/), por ejemplo:
> `feat(web): add anonymous symptom reporting view` o `fix(auth): correct token clock skew`.
>
> **Por qué es crítico:**
> Este repositorio aplica **Squash and Merge** hacia `main`. El título de este Pull Request se convertirá automáticamente en el mensaje del commit final en `main`, el cual es analizado por `git-cliff` para calcular el siguiente incremento de versión semántica (SemVer) y alimentar el archivo `CHANGELOG.md`.

---

## Descripción del cambio
<!-- Resuma brevemente el propósito de este PR, qué problema resuelve y cómo fue abordado -->
- 

---

## Checklist de calidad y seguridad
Antes de solicitar revisión, verifique el cumplimiento de los siguientes requisitos:

- [ ] **Pruebas automatizadas:** Se agregaron o actualizaron pruebas unitarias o de integración relevantes, y todas las pruebas pasan exitosamente en local (`./gradlew test` o `npm test`) y en el pipeline de Jenkins.
- [ ] **Sin secretos expuestos:** No se incluyen contraseñas, tokens, API keys ni credenciales reales en duro (se emplean variables de entorno `${VARIABLE}` y valores evidentes de ejemplo en archivos `.env.example`).
- [ ] **Sin etiquetas `latest`:** Ningún `Dockerfile`, compose ni script hace uso de la etiqueta mutable `latest` para imágenes Docker; se emplean versiones inmutables o la variable `${VERSION}`.
- [ ] **Documentación actualizada:** Se actualizaron los documentos técnicos pertinentes en `docs/` (arquitectura, servicios, endpoints o guías operativas) en caso de cambios estructurales o de configuración.
- [ ] **Revisión por pares lista:** El código cumple con las convenciones del proyecto y se encuentra listo para la revisión de al menos un integrante del equipo.
