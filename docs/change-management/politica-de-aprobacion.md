# Política de Aprobación y Promoción de Cambios

La presente política define los criterios, roles, mecanismos de control y compuertas de calidad (*quality gates*) requeridos para autorizar y desplegar cambios en la plataforma **CircleGuard**. Su objetivo es garantizar la máxima disponibilidad, trazabilidad y seguridad operativa del sistema en el campus universitario.

---

## 1. Clasificación de Cambios y Matriz de Aprobación

Todo cambio propuesto debe clasificarse en una de las tres categorías estándar de gestión de cambios (ITIL / DevOps). Cada categoría establece un nivel diferenciado de rigor y mecanismos de aprobación:

| Tipo de cambio | Criterios y Alcance | Quién aprueba | Mecanismo de aprobación |
|---|---|---|---|
| **Estándar** | Cambios de bajo riesgo, pre-autorizados, rutinarios y completamente automatizados en pruebas.<br>• Documentación técnica (`docs/*`).<br>• Mantenimiento de dependencias menores (`chore/*`).<br>• Mejoras o adiciones a suites de pruebas (`test/*`).<br>• Correcciones cosméticas de estilo o interfaz que no alteran contratos de API. | 1 Revisor por pares (*Peer Reviewer*). | **Pull Request en GitHub:**<br>1. Revisión y aprobación formal (1 approval).<br>2. Pipeline de CI de Jenkins en estado exitoso (*green build*).<br>3. Merge a `main` despliega automáticamente a `dev` y `stage`. |
| **Normal** | Cambios con impacto funcional o estructural que requieren validación en entornos previos.<br>• Nuevas características y endpoints (`feat/*`).<br>• Modificaciones en microservicios núcleo (`auth`, `gateway`, `promotion`, `identity`).<br>• Cambios en esquemas de PostgreSQL o Neo4j.<br>• Actualizaciones mayores de arquitectura o empaquetamiento. | 1 Revisor por pares (en PR) **+** Comité de Aprobación para Producción (*CAB*). | **Proceso en dos compuertas:**<br>1. **GitHub:** 1 aprobación en PR + CI verde.<br>2. **Jenkins Pipeline:** tras el despliegue exitoso en `dev`, validación E2E y despliegue en `stage`, el pipeline se suspende en el paso interactivo `input(Aprobación PROD)`.<br>3. Solo los aprobadores autorizados (`luis`, `simon`, `dario`) pueden autorizar el paso a `prod`. Jenkins estampa el usuario en los logs y notificaciones. |
| **Emergencia** | Corrección inmediata ante fallos críticos que degradan el servicio en producción.<br>• Bloqueo en torniquetes de acceso físico (`gateway-service`).<br>• Vulnerabilidades de seguridad críticas o exposición de datos personales.<br>• Caída de la infraestructura de mensajería (Kafka/Redis) o bases de datos. | Aprobación verbal/chat de al menos 1 líder del equipo (`luis`, `simon` o `dario`) previa a la intervención; aprobación formal documentada posterior. | **Flujo acelerado:**<br>1. Creación urgente de rama `fix/CG-<n>-hotfix` desde `main` o ejecución de rollback.<br>2. PR con revisión expedita y CI verde.<br>3. Aprobación inmediata en el `input` de Jenkins.<br>4. Registro formal del RFC y análisis post-mortem (análisis de causa raíz) dentro de las 24 horas siguientes. |

---

## 2. Roles y Responsabilidades

### Aprobadores de Producción (`CG_PROD_APPROVERS`)
En la configuración global de Jenkins (`cgConfig.groovy`), la propiedad `CG_PROD_APPROVERS` restringe estrictamente quién tiene permisos de ejecución sobre la etapa `Aprobación PROD`:
- **Usuarios autorizados**: `luis`, `simon`, `dario`.
- **Registro de auditoría**: la variable de entorno `env.PROD_APPROVER` captura el nombre de usuario autenticado que presionó el botón de aprobación en Jenkins y lo vincula al artefacto de build y al mensaje de notificación en Discord/Slack/Email.
- **Ventana de decisión**: el paso de aprobación tiene un límite de tiempo (*timeout*) de 2 horas. Si ningún aprobador responde en dicho periodo, el build se aborta automáticamente sin alterar el ambiente de producción.

---

## 3. Flujo de Promoción de Ambientes: dev → stage → prod

CircleGuard implementa el principio de **Artefacto Inmutable**: una vez compilado y empaquetado un release en `main`, las mismas imágenes Docker generadas en Nexus se promueven sucesivamente a través de los tres ambientes.

```text
  [ PR merge a main ]
          │
          ▼
    [ CI: Build & Tests ]
          │
          ▼
   [ Docker Publish a Nexus ] (vX.Y.Z)
          │
          ▼
   ┌───────────────────────┐
   │ 1. Ambiente DEV       │ ──► Despliegue automático
   │    Host: CG_DEV_HOST  │ ──► Ejecución de pruebas E2E (Playwright)
   └───────────────────────┘
          │ (E2E exitoso)
          ▼
   ┌───────────────────────┐
   │ 2. Ambiente STAGE     │ ──► Despliegue automático
   │    Host: CG_STAGE_HOST│ ──► Smoke testing y verificación pre-prod
   └───────────────────────┘
          │
          ▼
   ┌───────────────────────┐
   │ Compuerta de Control  │ ──► timeout: 2 horas
   │ stage('Aprobación')   │ ──► submitter: luis, simon, dario
   └───────────────────────┘
          │ (Aprobado)
          ▼
   ┌───────────────────────┐
   │ 3. Ambiente PROD      │ ──► Despliegue supervisado
   │    Host: CG_PROD_HOST │ ──► Notificación de release exitoso
   └───────────────────────┘
```

### Características de cada ambiente:

1. **Ambiente DEV (`dev`)**:
   - **Objetivo**: Integración rápida y ejecución de pruebas automatizadas sobre infraestructura real.
   - **Mecanismo de despliegue**: Despliegue continuo automático inmediatamente después de la publicación de imágenes en Nexus (`cgDeploy(env: 'dev', version: env.VERSION)`).
   - **Validación obligatoria**: Tras el despliegue, el pipeline invoca la suite de pruebas End-to-End con Playwright (`cgE2E`). Si alguna prueba E2E falla, el pipeline se detiene de inmediato y no avanza a staging.

2. **Ambiente STAGE (`stage`)**:
   - **Objetivo**: Réplica fiel de producción (*Pre-producción*). Permite validación visual de la interfaz web, pruebas de rendimiento y pruebas de humo por parte de los líderes funcionales.
   - **Mecanismo de despliegue**: Se despliega automáticamente una vez dev supera las pruebas E2E, utilizando exactamente las mismas referencias de imágenes inmutables.
   - **Condición previa**: Sirve como base empírica para que los aprobadores autoricen el paso a producción.

3. **Ambiente PROD (`prod`)**:
   - **Objetivo**: Entorno activo que atiende a los usuarios finales del campus (estudiantes, profesores, guardias de seguridad en torniquetes).
   - **Mecanismo de despliegue**: Despliegue condicionado a la aprobación humana en Jenkins. Ejecuta `cgDeploy(env: 'prod', version: env.VERSION)` conectándose vía SSH al host `CG_PROD_HOST` y aplicando `docker-compose`.
   - **Monitoreo post-despliegue**: Verificación inmediata de disponibilidad mediante endpoints `/actuator/health` en cada microservicio.

---

## 4. Auditoría y Registro de Cambios

1. **Trazabilidad en Git**: Cada cambio en producción proviene de un commit atómico en `main` generado por squash merge, con referencia al número de PR y al issue de seguimiento (`CG-<n>`).
2. **Trazabilidad en Jenkins**: Cada ejecución conserva:
   - Número de compilación y tag de versión (p. ej. `#42 v1.2.0`).
   - Identificador del commit git (`GIT_COMMIT`).
   - Usuario que aprobó el pase a producción (`PROD_APPROVER`).
   - Archivo de notas de lanzamiento (`RELEASE_NOTES.md`).
3. **Registro de Notificaciones**: Al completarse el despliegue en producción, se despacha una notificación automática indicando: versión desplegada, componentes actualizados y responsable de la aprobación.
