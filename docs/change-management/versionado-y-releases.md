# Versionado Semántico, Publicación de Releases y Plan de Rollback

En CircleGuard, el versionado del software, la publicación de imágenes en el registro de contenedores y la generación de notas de lanzamiento se encuentran 100 % automatizados mediante la integración de **Conventional Commits**, **git-cliff** y el **pipeline multibranch de Jenkins**.

---

## 1. Esquema de Versionado Semántico (SemVer)

CircleGuard implementa el estándar [Semantic Versioning 2.0.0](https://semver.org/) anteponiendo el prefijo `v`:

```text
vMAJOR.MINOR.PATCH
```

- **MAJOR**: Incremento ante cambios incompatibles hacia atrás (*breaking changes*), modificaciones drásticas en el protocolo de tokens QR o alteraciones en los contratos de API pública.
- **MINOR**: Incremento ante la adición de nuevas funcionalidades compatibles hacia atrás (ej. soporte para nuevos flujos de encuestas, nuevos dashboards).
- **PATCH**: Incremento ante correcciones de errores (*bug fixes*), mejoras de rendimiento o refactorizaciones internas sin alteración funcional.

### Reglas de consistencia:
- Si el repositorio no posee etiquetas de versión previas, el pipeline asigna automáticamente la versión inicial **`v1.0.0`** (definido en `jenkins/vars/cgVersion.groovy`).
- La función de validación `cgConfig.requireSemver(version)` exige que toda versión cumpla estrictamente la expresión regular `^v\d+\.\d+\.\d+$`. Cualquier formato no conforme detiene de inmediato el pipeline.

---

## 2. Inmutabilidad de Imágenes en Nexus (Prohibición de `latest`)

Todo componente construido se publica en el registro privado de artefactos Nexus (`CG_REGISTRY`). 

### Política de Contenedores Inmutables:
1. **Etiquetado determinista**: cada imagen se construye y etiqueta utilizando exclusivamente la versión SemVer del release:
   ```text
   ${CG_REGISTRY}/circleguard/${NOMBRE_COMPONENTE}:${VERSION}
   ```
   *Ejemplo real:* `10.0.1.20:8082/circleguard/promotion-service:v1.2.0`
2. **Prohibición estricta de `latest`**:
   - La etiqueta `latest` es mutable y oculta qué versión de código se encuentra ejecutando en un contenedor.
   - Rompe la reproducibilidad del entorno y anula la capacidad de auditoría post-mortem.
   - Impide realizar rollbacks deterministas, pues no es posible saber a qué versión apunta `latest` en un momento dado.
3. **Un release = Todo el sistema sincronizado**:
   - En cada release generado en `main`, el pipeline empaqueta y publica los 8 microservicios backend más el cliente web con el **mismo tag de versión**.
   - Los archivos `docker-compose` de cada ambiente leen la variable `VERSION` compartida, garantizando compatibilidad cruzada absoluta entre todos los microservicios.

---

## 3. Mapeo de Commits y Cálculo Automático de Versión

El cálculo del siguiente número de versión se delega a `git-cliff` mediante la configuración de `cliff.toml`, ejecutada desde `cgVersion.groovy`:

```bash
git cliff --bumped-version
```

### Tabla de resolución de bump:

| Tipo de Commit en `main` | Efecto en la Versión | Comportamiento del Pipeline |
|---|---|---|
| `feat` (ej. `feat(auth): ...`) | **MINOR** (`v1.0.0` → `v1.1.0`) | Se publica release: imágenes en Nexus, tag en GitHub, despliegue en dev/stage/prod. |
| `fix` (ej. `fix(gateway): ...`) | **PATCH** (`v1.0.0` → `v1.0.1`) | Se publica release: imágenes en Nexus, tag en GitHub, despliegue en dev/stage/prod. |
| `perf`, `refactor`, `build` | **PATCH** (`v1.0.0` → `v1.0.1`) | Se publica release: imágenes en Nexus, tag en GitHub, despliegue en dev/stage/prod. |
| Commit con `!` o nota `BREAKING CHANGE:` | **MAJOR** (`v1.0.0` → `v2.0.0`) | Se publica release mayor: imágenes en Nexus, tag en GitHub, despliegue en dev/stage/prod. |
| `docs`, `chore`, `ci`, `test`, `style` | **Sin release** (`skip = true`) | Si desde el último tag solo existen commits con estos tipos, `cgVersion()` devuelve una cadena vacía (`""`). El pipeline ejecuta la etapa de CI (pruebas y análisis SonarQube) pero **no publica imágenes, no crea tags ni altera los ambientes**. |

---

## 4. Generación y Publicación de Release Notes

La etapa `Tag + release notes` (`jenkins/vars/cgRelease.groovy`) se ejecuta únicamente **después** de que todas las imágenes han sido publicadas con éxito en Nexus. Esto evita que queden etiquetas en Git que apunten a imágenes inexistentes.

### Flujo de publicación:
1. **Creación del tag Git anotado**:
   ```bash
   git tag -a "$RELEASE_VERSION" -m "release $RELEASE_VERSION"
   ```
2. **Generación del documento de Release Notes**:
   Se ejecuta `git-cliff` para extraer únicamente los cambios del último lanzamiento:
   ```bash
   git cliff --latest --strip header --output RELEASE_NOTES.md
   ```
   El archivo generado categoriza los commits en:
   - 🚀 Funcionalidades (*Features*)
   - 🐛 Corrección de Errores (*Bug Fixes*)
   - ⚡ Rendimiento (*Performance*)
   - 🚜 Refactorización (*Refactor*)
   - 📦 Sistema de Compilación (*Build*)
   Cada elemento incluye su alcance (*scope*), descripción y enlace directo al commit en GitHub.
3. **Publicación en GitHub Releases**:
   El pipeline empuja el tag al repositorio remoto y realiza una petición HTTP a la API REST de GitHub creando el release oficial con el contenido de `RELEASE_NOTES.md`.
4. **Archivo de artefacto**:
   El archivo `RELEASE_NOTES.md` se archiva permanentemente en el build de Jenkins (`archiveArtifacts`).

---

## 5. Procedimiento de Rollback (Reversión Operativa)

Cuando se identifica un incidente en producción que compromete la operación normal y no puede resolverse inmediatamente con un hotfix, se activa el procedimiento de rollback mediante el pipeline dedicado `jenkins/Jenkinsfile.rollback`.

### Principio Fundamental del Rollback:
Dado que las imágenes Docker son inmutables y permanecen almacenadas en el registro Nexus, hacer rollback **no requiere recompilar código ni reejecutar pruebas**. Se trata de un redespliegue directo de una versión previa conocida y estable, reduciendo el RTO (*Recovery Time Objective*) a menos de 3 minutos.

### Pasos de ejecución del Rollback:
1. **Ejecución del Job en Jenkins**:
   - Navegar al job `circleguard-rollback` y presionar **Build with Parameters**.
   - Parámetros:
     - `TARGET_ENV`: Ambiente objetivo (`dev`, `stage` o `prod`).
     - `ROLLBACK_VERSION`: Versión anterior válida a la que se desea retornar (ej. `v1.0.0`).
     - `REASON`: Justificación obligatoria del rollback para fines de auditoría.
2. **Validación Automática**:
   - El pipeline verifica que `ROLLBACK_VERSION` cumpla el formato SemVer (`vX.Y.Z`).
   - Comprueba mediante `git ls-remote` que el tag existe en GitHub.
   - Valida que la versión actual del ambiente no sea ya igual a la versión solicitada.
3. **Compuerta de Aprobación (solo para `prod`)**:
   - Si `TARGET_ENV` es `prod`, el pipeline solicita aprobación interactiva con un timeout de 30 minutos, restringido a los usuarios de `CG_PROD_APPROVERS` (`luis`, `simon`, `dario`).
4. **Redespliegue vía SSH**:
   - Jenkins se conecta al servidor correspondiente e invoca `cgDeploy`, el cual actualiza el archivo de variables del ambiente con la versión previa y ejecuta:
     ```bash
     docker compose pull
     docker compose up -d --remove-orphans
     ```
5. **Verificación y Notificación**:
   - Se consulta la versión activa post-despliegue confirmando que coincide con `ROLLBACK_VERSION`.
   - Se envía una notificación prioritaria a los canales del equipo detallando el ambiente, versión anterior, versión restaurada, usuario aprobador y motivo.

### Consideración sobre Bases de Datos (Flyway):
Las herramientas de migración relacional como Flyway están diseñadas para avanzar hacia adelante. Por este motivo, el equipo sigue el principio de **Migraciones Expand/Contract**: nunca se eliminan columnas o tablas en la misma versión en la que se introducen cambios de código, garantizando que el esquema de base de datos continúe siendo compatible con la versión inmediatamente anterior durante un rollback.
