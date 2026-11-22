# Presentación Parte 2 · Esqueleto de diapositivas y guion (20 min)

> **Estado:** esqueleto (CG-113). Todo lo que depende de entregables de la Parte 2 que aún no están en `main` va marcado como **[pendiente]** y se completa con la evidencia real (capturas, enlaces a builds) antes de la sustentación.
> **Reparto de tiempos y personas:** propuesta de Luis para que el equipo la confirme. Los temas corresponden a los roles del proyecto: Darío (infraestructura y pruebas), Simón (pipeline), Luis (proceso y change management).

## 1. Resumen de tiempos

| # | Diapositiva | Quién | Min | Acumulado |
|---|---|---|:---:|:---:|
| 1 | Portada y objetivo | Luis | 1 | 1 |
| 2 | Qué cambió de la Parte 1 a la Parte 2 | Luis | 1 | 2 |
| 3 | **Respuesta a las observaciones del profesor** | Luis | 2 | 4 |
| 4 | Arquitectura en Kubernetes | Darío | 2 | 6 |
| 5 | Infraestructura con Terraform | Darío | 2 | 8 |
| 6 | Pipeline CI/CD (demo) | Simón | 3 | 11 |
| 7 | La app funcionando | Darío | 2 | 13 |
| 8 | Observabilidad: dashboards y alertas | Simón | 2 | 15 |
| 9 | Patrones de resiliencia y configuración | Simón y Luis | 1 | 16 |
| 10 | Rendimiento y seguridad (Locust, ZAP, Trivy) | Darío | 2 | 18 |
| 11 | Costos y lecciones aprendidas | Luis | 1.5 | 19.5 |
| 12 | Cierre y preguntas | Luis | 0.5 | 20 |

Totales por persona: Luis 6 min, Darío 8 min, Simón 6 min. (Ajustar si el equipo prefiere repartir distinto; la suma debe seguir en 20.)

---

## 2. Diapositivas y guion

### Diapositiva 1 · Portada y objetivo (Luis, 1 min)
- **Contenido:** CircleGuard, Parte 2: de Docker Compose en VMs a Kubernetes (k3s) con Terraform, pipeline con escaneo de seguridad, observabilidad y patrones de resiliencia. Nombres del equipo.
- **Guion:** "En la Parte 1 dejamos 8 microservicios y un cliente web corriendo en 4 VMs de Azure con scripts Bash y Jenkins. En la Parte 2 movimos todo a Kubernetes, creamos la infraestructura con Terraform y agregamos seguridad, monitoreo y resiliencia."
- **Evidencia:** `docs/infra/topologia.md` (estado de la Parte 1).

### Diapositiva 2 · De la Parte 1 a la Parte 2 (Luis, 1 min)
- **Contenido:** tabla de dos columnas (antes / ahora).

| | Parte 1 (en `main`) | Parte 2 |
|---|---|---|
| Infraestructura | Scripts Bash: `infra/azure/provision.sh`, `bootstrap-all.sh` | Terraform: **[pendiente]** enlazar `infra/terraform` |
| Ejecución | Docker Compose por ambiente (`deploy/docker-compose.yml`) | Kubernetes (k3s), chart de Helm: **[pendiente]** enlazar `deploy/helm` |
| Pipeline | Jenkinsfile con deploy por SSH | Deploy a Kubernetes, Trivy: **[pendiente]** |
| Observabilidad | no hay | **[pendiente]** |

- **Guion:** "Cada fila es un problema de la Parte 1 que Parte 2 resuelve; lo veremos persona por persona."

### Diapositiva 3 · Respuesta a las observaciones del profesor (Luis, 2 min)
Una sola diapositiva, dos columnas.

**Observación 1: el tamaño de los PR**
- **Problema:** un PR de ~20 000 líneas no se puede revisar de verdad: se aprueba sin leer.
- **Respuesta:** regla automática en GitHub Actions (`.github/workflows/pr-size.yml`, CG-114): etiqueta `size/S|M|L|XL`, falla si el PR pasa de 400 líneas (sin archivos generados), salvo etiqueta `size/exception` más sección `## Justificación de tamaño`. **[pendiente]**: confirmar que esté fusionado en `main` y que `pr-size` sea check obligatorio en el ruleset "Proteger main".
- **Regla y cómo dividir:** `docs/agil/branching.md` (sección "Tamaño de los PR", CG-114) y la plantilla `.github/pull_request_template.md`. **[pendiente]** de fusión en `main`.
- **Evidencia a mostrar:** captura de un PR de prueba bloqueado por `pr-size` **[pendiente]**; métrica de tamaño de PR del Sprint 2 frente al Sprint 1 **[pendiente]** (`docs/agil/sprint-2.md`).

**Observación 2: SNAPSHOT frente a release**
- **Problema:** en el Sprint 1 el tag `v1.0.0` se creó en Git antes de desplegar y probar; los E2E la detuvieron en dev y nunca llegó a producción (`docs/agil/sprint-1.md`, `IAG_REGISTRO.md`).
- **Respuesta:** convención `vX.Y.Z-SNAPSHOT.<build>` (sin validar, solo dev y stage) y `vX.Y.Z` (release). La release es **la misma imagen (mismo digest)** re-etiquetada tras la aprobación de prod; el tag de Git y el GitHub Release se crean solo ahí. Si se rechaza la aprobación, no hay release.
- **Dónde está:** `docs/change-management/versionado-y-releases.md` sección 2.1 (CG-115); validación en `cgConfig.requireSemver`. **[pendiente]**: la rama de CG-115 aún no está fusionada en `main` y el pipeline de Simón (CG-116) no tiene evidencia; mostrar un build de Jenkins con SNAPSHOT → release **[pendiente]**.

- **Guion:** "El profesor señaló dos problemas. Uno, PR gigantes que no se pueden revisar: ahora una regla automática los bloquea en 400 líneas y obliga a justificar las excepciones. Dos, versiones que parecían listas sin estarlo: ahora una versión sin validar se llama SNAPSHOT con número de build, y la release es la misma imagen ya probada, solo con otro nombre."
- **Si preguntan por qué `-SNAPSHOT.<build>` y no `-SNAPSHOT`:** un tag fijo es mutable y Nexus está en `ALLOW_ONCE`; con el número cada imagen es única y lo probado en stage es exactamente lo que llega a prod (documentado en la sección 2.1).
- **Si preguntan por qué 400 líneas:** la eficacia de una revisión cae pasadas unas 200 a 400 líneas; el límite es una convención del equipo, documentada en `docs/agil/branching.md` **[pendiente]** de fusión.

### Diapositiva 4 · Arquitectura en Kubernetes (Darío, 2 min)
- **Contenido:** diagrama de `docs/arquitectura/` (namespaces `circleguard-dev|stage|prod`, Ingress, 8 servicios + web, middleware: Postgres, Neo4j, Kafka, Redis, etc., y observabilidad). **[pendiente]**: diagrama de Kubernetes (CG-107) aún no existe en `main`; el actual (`docs/arquitectura/arquitectura.md`) describe la Parte 1.
- **Guion:** recorrer una petición: navegador → Ingress → web/gateway → servicio → base de datos/Kafka. Mencionar *database-per-service* y arquitectura por eventos (ver `docs/patrones.md`, CG-100 **[pendiente]**).

### Diapositiva 5 · Infraestructura con Terraform (Darío, 2 min)
- **Contenido:** red, clúster, registro, almacenamiento; comandos `terraform plan/apply`. **[pendiente]**: `infra/terraform` no está en `main`.
- **Guion:** por qué IaC (reproducible, versionada); restricciones de la suscripción (Azure for Students: 6 vCPU por región, capacidad `Bsv2`, ver `docs/infra/topologia.md`).

### Diapositiva 6 · Pipeline CI/CD, demo en vivo (Simón, 3 min)
- **Contenido:** diagrama de `docs/arquitectura/pipeline.md` actualizado: CI (compilar, JaCoCo ≥ 60 %, Sonar bloqueante, integración) y CD (versión → SNAPSHOT → deploy dev → E2E → stage → aprobación → release → prod). Trivy y deploy a Kubernetes: **[pendiente]**.
- **Demo:** un push a `main` con un cambio pequeño; mostrar etapas, el SNAPSHOT en Nexus, la aprobación manual y la promoción **[pendiente]** de guion final cuando H3 esté listo.
- **Plan B si falla la demo:** capturas/video del build **[pendiente]**.

### Diapositiva 7 · La app funcionando (Darío, 2 min)
- **Contenido:** el cliente web sobre el Ingress, un flujo completo (encuesta de salud → evento → cambio de estado → QR). **[pendiente]**: URL y captura del ambiente en Kubernetes.
- **Guion:** mostrar el flujo y `kubectl get pods -n circleguard-dev` **[pendiente]** (solo lectura).

### Diapositiva 8 · Dashboards y alertas (Simón, 2 min)
- **Contenido:** dashboards de Grafana, métricas, alertas. **[pendiente]**: no hay manifiestos de observabilidad en `main`; completar con capturas y la lista de alertas reales.

### Diapositiva 9 · Patrones (Simón y Luis, 1 min)
- **Contenido:** Circuit Breaker y Retry (Simón) y Feature Toggle `circleguard.features.<nombre>.enabled` (Luis); patrones existentes verificados en código. **[pendiente]**: `docs/patrones.md`, la elección del toggle y las pruebas aún no están en `main`.
- **Guion:** para cada patrón: dónde está, qué problema resuelve y su *trade-off*.

### Diapositiva 10 · Rendimiento y seguridad (Darío, 2 min)
- **Contenido:** Locust frente al objetivo declarado, ZAP, Trivy, JaCoCo y Sonar; qué se encontró y qué se aceptó como riesgo. **[pendiente]**: informe consolidado (CG-111). Lo que sí existe: cobertura mínima 60 % en servicios núcleo (`docs/pruebas/cobertura.md`) y E2E (`docs/pruebas/e2e.md`).
- **Guion:** interpretar, no solo mostrar números: ¿se cumplió el objetivo? ¿qué se mejoraría?

### Diapositiva 11 · Costos y lecciones (Luis, 1.5 min)
- **Costos:** estimado frente a real (CG-108, `docs/infra/costos.md`) **[pendiente]**.
- **Lecciones:** partir de lo ya documentado en `docs/agil/sprint-1.md` sección 3.3 (probar cada control con un caso que deba fallar; ambientes efímeros; no dejar pasos manuales) y comparar con el Sprint 2 **[pendiente]** (retro del domingo en `docs/agil/sprint-2.md`).
- **Métricas Sprint 1 como base de comparación:** 36 PRs fusionados; pipeline de PR de 15 a 19 min con servicios (`docs/agil/sprint-1.md` sección 4).

### Diapositiva 12 · Cierre (Luis, 0.5 min)
- Resumen en una frase por persona y apertura a preguntas.

---

## 3. Preguntas probables y quién responde

| Pregunta | Responde | Dónde está la respuesta |
|---|---|---|
| ¿Cómo evitan que se publique una versión sin validar? | Simón / Luis | `versionado-y-releases.md` §2.1 |
| ¿Cómo se hace rollback? | Simón / Luis | `jenkins/Jenkinsfile.rollback`, `docs/change-management/rollback.md` **[pendiente]** |
| ¿Por qué estos límites de PR? | Luis | `docs/agil/branching.md` **[pendiente]** de fusión |
| ¿Cuánto costó la infraestructura? | Luis | `docs/infra/costos.md` **[pendiente]** |
| ¿Qué pasa si cae un servicio? | Simón | patrones de resiliencia **[pendiente]** |
| ¿Cómo se reconstruye todo desde cero? | Darío | README de la raíz y `docs/manual-operaciones.md` **[pendiente]** |

## 4. Lista de verificación antes de presentar

1. Reemplazar cada **[pendiente]** por evidencia real o eliminar la afirmación.
2. Confirmar el reparto de tiempos con Simón y Darío y ensayar una vez con cronómetro (límite 20 min).
3. Tener capturas de respaldo de la demo de CI/CD y de los dashboards.
4. Verificar que ninguna diapositiva muestre secretos, IP sensibles o tokens.
5. Abrir las pestañas necesarias (Jenkins, Grafana, GitHub, Jira) antes de empezar.
