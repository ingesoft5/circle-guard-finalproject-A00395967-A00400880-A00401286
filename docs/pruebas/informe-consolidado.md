# Informe consolidado de pruebas (CG-111)

Fecha de redacción: 22/11/2026. Este informe reúne lo que **existe en el repo**
y los reportes del **build #3 de `main` en Jenkins** (artefactos de Trivy y ZAP),
y los interpreta. Lo que no tiene evidencia entregada se marca como
**pendiente de evidencia**; no se estima ni se supone. No incluye IPs ni
credenciales: los registros de imágenes se citan como `<registro>`.

## 1. Resumen

| Prueba | Estado de la evidencia | Veredicto |
|---|---|---|
| JaCoCo (cobertura unitaria) | Cifras del Sprint 1 en `docs/pruebas/cobertura.md` | Cumple ≥ 60 % en los 5 servicios núcleo (sin re-medir en Sprint 2) |
| SonarQube | Configuración en repo; **sin reporte** | Pendiente de evidencia |
| Locust (carga y estrés) | `tests/performance/results/` completo | Cumple el objetivo declarado; cuello de botella en auth-service |
| OWASP ZAP | Artefacto del build #3 de `main` | 1 alerta High, 2 Medium, 9 Low, 2 Info (sección 5) |
| Trivy | Artefactos del build #3 de `main` (9 imágenes + IaC) | 0 CRITICAL; 212 HIGH con arreglo disponible que no bloquean (sección 6) |
| E2E Playwright | `docs/pruebas/e2e.md` | Hallazgo del QR corregido (sección 7) |

## 2. JaCoCo: cobertura unitaria

Fuente: `docs/pruebas/cobertura.md` (medido con `./gradlew test jacocoTestReport`;
umbral impuesto por `jacocoTestCoverageVerification` en `build.gradle.kts`).

| Servicio | Cobertura de líneas | Líneas | Meta 60 % |
|---|---:|---:|---|
| auth | 98,1 % | 151/154 | ✅ |
| identity | 94,1 % | 80/85 | ✅ |
| promotion | 81,4 % | 583/716 | ✅ |
| form | 97,7 % | 85/87 | ✅ |
| gateway | 88,9 % | 16/18 | ✅ |

**Interpretación.**

- Las cifras superan con holgura la meta, pero se miden **con exclusiones**
  (`*Application*`, `config/**`, `dto/**`, `model/**`). La seguridad en `config/`
  no cuenta en el porcentaje; se cubre con `@WebMvcTest`, así que el número no
  dice nada sobre ella.
- gateway tiene solo 18 líneas medidas y 3 pruebas: 88,9 % son 16 líneas, no una
  señal fuerte.
- Las cifras son del Sprint 1. No hay evidencia de que se hayan vuelto a medir
  tras los cambios del Sprint 2 (resiliencia, toggles): **pendiente de evidencia**.
- Cobertura de líneas no es calidad de las pruebas. Hay que mirar también las
  ramas (no reportadas) y los servicios fuera del núcleo (notification,
  dashboard, file): **pendiente de evidencia**.

## 3. SonarQube

El pipeline ejecuta un proyecto por servicio (`circleguard-<servicio>`) con
quality gate bloqueante (`jenkins/vars/cgSonar.groovy`, `waitForQualityGate
abortPipeline: true`) y lee el XML de JaCoCo.

**Pendiente de evidencia:** estado del quality gate, bugs, vulnerabilidades,
*code smells* y duplicación por servicio. No se entregó captura del panel de
Sonar ni log de Jenkins, y no se puede afirmar que el gate pasó sin ellos.

## 4. Locust: rendimiento frente al objetivo

Fuente: `tests/performance/README.md` (objetivo definido antes de correr) y
`tests/performance/results/` (CSV, HTML, `stress-console.txt`, `stress-hpa.txt`,
`ANALISIS.md`). Ambiente `circleguard-dev`, imagen `v1.0.2-SNAPSHOT.0`, por el
Ingress público desde Colombia hacia westus2 (~125 ms de ida y vuelta).

| Corrida | Objetivo declarado | Resultado | ¿Cumple? |
|---|---|---|---|
| Normal, 30 usuarios, 3 min | p95 < 500 ms y errores < 1 % | p95 = 160 ms, 0 errores en 3.424 peticiones | ✅ |
| Estrés, 200 usuarios, 5 min | errores < 5 % y que el HPA escale | 0,55 % de errores (171 de **31.105** peticiones); gateway pasó de 1 a 2 réplicas | ✅ |

El total de 31.105 peticiones del estrés es la cifra corregida en el PR #83
(coincide con `stress-console.txt`).

### Por endpoint (estrés, de `stress-console.txt`)

| Endpoint | Peticiones | Fallos | Mediana | p95 |
|---|---:|---:|---:|---:|
| gateway: gate/validate | 24.199 | 0 | 130 ms | 150 ms |
| form: surveys | 5.708 | 0 | 140 ms | 160 ms |
| auth: qr/generate | 882 | 55 (6,24 %) | 140 ms | 60 s |
| auth: login | 316 | 116 (36,71 %) | 29 s | 60 s |

### Interpretación

1. **El "cumple" es real pero el agregado engaña.** El p95 global de 160 ms y el
   0,55 % de errores salen de que el 78 % del tráfico (24.199 de 31.105) es
   `gate/validate`, que es rápido. Login y generación de QR tienen un p95 de 60 s
   (el timeout) y concentran **todos** los 171 errores. Un objetivo global
   esconde un servicio roto; el objetivo debió medirse por endpoint.
2. **Los dos escenarios críticos del campus sí están sanos.** Validar QR y enviar
   el cuestionario mantienen p95 ≈ 150-160 ms con 200 usuarios, igual que con 30.
3. **auth-service es el cuello de botella.** El login pasa de 8,6 s de mediana
   (30 usuarios) a 29 s (200). Un tercio de los logins falla (59 × 401 y
   57 × 504). Es una sola réplica sin HPA; la hipótesis de `ANALISIS.md` (hash de
   contraseña, LDAP e identity en cada login) es plausible pero **no está
   demostrada con perfilado**: pendiente de evidencia.
4. **El HPA funcionó donde existe.** gateway subió de 4 % a 112 % de CPU y de 1 a
   2 réplicas (máximo de dev) en ~2 min (`stress-hpa.txt`). Con 2 réplicas
   sostuvo 81 req/s sin errores. form-service llegó a 184 % de CPU en el mismo
   registro; no se verificó aquí si escaló: pendiente de evidencia.
5. **Límites de la medición.** Es dev (no prod), con un solo generador de carga
   desde otro país, y la latencia base de red (~125 ms) domina los tiempos de los
   endpoints sanos. No dice nada sobre prod.

Nota: `ANALISIS.md` da mediana de login normal de 8,8 s y `normal_stats.csv` dice
8,6 s; este informe usa 8,6 s (el CSV). Conviene alinear `ANALISIS.md`.

## 5. OWASP ZAP

Fuente: artefactos del **build #3 de `main` en Jenkins** (`zap-baseline.html`,
`zap-baseline.json`, `zap-baseline.conf`). Es un *baseline* (escaneo pasivo) sobre
la aplicación web desplegada en dev. Las reglas FAIL/WARN/IGNORE están en
`zap-baseline.conf`; ese archivo no está en el árbol actual del repo
(`tests/security-zap/` no existe en esta rama): ver mejora 3.

| Riesgo (confianza) | Alerta | Instancias |
|---|---|---:|
| **High** (Medium) | nginx Version with Known Vulnerabilities | 1 |
| **Medium** (High) | Content Security Policy (CSP) Header Not Set | 5 |
| **Medium** (Medium) | Missing Anti-clickjacking Header | 5 |
| Low | Cross-Origin-Embedder / Opener / Resource-Policy ausentes o inválidas | 3 + 3 + 3 |
| Low | Permissions Policy Header Not Set | 5 |
| Low | Server Leaks Version Information via "Server" header | 5 |
| Low | Strict-Transport-Security Header Not Set | 5 |
| Low | X-Content-Type-Options Header Missing | 5 |
| Low | Timestamp Disclosure - Unix | 5 |
| Low | Dangerous JS Functions | 1 |
| Info | Re-examine Cache-control Directives; Storable and Cacheable Content | 5 + 5 |

Total: 14 tipos de alerta (1 High, 2 Medium, 9 Low, 2 Info).

**Interpretación.**

- Casi todo es **endurecimiento de cabeceras HTTP** que se corrige en una sola
  línea de configuración de nginx (CSP, anti-clickjacking, HSTS,
  X-Content-Type-Options, `server_tokens off`). No hay inyecciones, cookies
  inseguras ni fugas de datos en el reporte, pero un baseline pasivo **no
  puede** detectarlas: ausencia de hallazgos no es ausencia de vulnerabilidades.
- El único **High** es "nginx con vulnerabilidades conocidas": ZAP lo deduce de
  la versión que el servidor anuncia en la cabecera `Server`. Es una detección
  por versión, no una explotación confirmada (confianza Media). Se resuelve con
  una imagen de nginx actualizada y ocultando la versión.
- HSTS ausente es esperable mientras no haya TLS; el `.conf` indica que TLS queda
  como otra historia (E2.6).
- "Dangerous JS Functions" (1 instancia, confianza Baja) requiere revisión manual
  del bundle antes de darla por válida o descartarla: pendiente de evidencia.
- Sin evidencia de si el pipeline marcó el build como fallido o aprobado por
  estas alertas (qué ID de regla corresponde a cada una no se verificó):
  pendiente de evidencia.

## 6. Trivy

Fuente: artefactos `trivy-reports/*.txt|json` del **build #3 de `main` en
Jenkins**, imágenes con tag `v1.2.0-SNAPSHOT.3` (SNAPSHOT: aún sin promover).
`jenkins/vars/cgTrivy.groovy` bloquea solo con `CRITICAL` que tenga arreglo
(`--ignore-unfixed`) y respeta `.trivyignore`.

| Imagen | CRITICAL | HIGH (con arreglo) | MEDIUM | LOW |
|---|---:|---:|---:|---:|
| auth-service | 0 | 19 (19) | 29 | 9 |
| identity-service | 0 | 24 (22) | 37 | 10 |
| promotion-service | 0 | 24 (22) | 41 | 10 |
| gateway-service | 0 | 16 (16) | 28 | 9 |
| form-service | 0 | 23 (21) | 39 | 10 |
| notification-service | 0 | 26 (24) | 48 | 12 |
| dashboard-service | 0 | 17 (17) | 27 | 9 |
| file-service | 0 | 16 (16) | 25 | 9 |
| web (nginx/Alpine) | 0 | 55 (55) | 83 | 96 (+2 UNKNOWN) |
| **Total** | **0** | **220 (212)** | 357 | 174 |

IaC: `iac-deploy-helm.txt` está vacío (sin hallazgos) y `iac-infra-terraform.txt`
reporta 3 hallazgos **LOW** en `modules/secrets/main.tf`, regla AVD-AZU-0017
("el secreto debería tener fecha de expiración").

**Interpretación.**

- **El gate funciona:** 0 CRITICAL en las 9 imágenes, consistente con el
  `.trivyignore` (3 CVE de Spring aceptadas, sin arreglo en Spring Boot 3.2:
  `CVE-2026-47884`, `CVE-2026-47892`, `CVE-2026-22732`; revisión 20/12/2026).
- **Pero el umbral solo mira CRITICAL.** Hay 212 HIGH con arreglo disponible que
  pasan sin bloquear. En los servicios Java vienen de dependencias (jackson-databind,
  spring-webmvc, jackson-core, entre otras); en `web`, de paquetes de Alpine
  (curl/libcurl, libexpat, libuuid, pcre2). Esto debe quedar explícito en la
  sustentación: es una decisión de umbral, no ausencia de riesgo.
- La imagen **web** concentra el mayor volumen (55 HIGH, 96 LOW) y casi todo se
  arregla actualizando la imagen base de Alpine/nginx: es el mismo origen del
  High de ZAP (versión de nginx).
- Los 3 LOW de Terraform no son urgentes; son higiene de secretos (expiración).

## 7. E2E (contexto)

`docs/pruebas/e2e.md` registra que el E2E "QR vigente permite el ingreso" fallaba
porque `qr.expiration: 300` estaba en milisegundos (300 ms). **Corregido** (PR
#24): en `main`, `qr.expiration` vale `300000` ms (5 minutos) en
`circleguard-auth-service` y `circleguard-gateway-service`
(`src/main/resources/application.yml`, con comentario de unidades).

## 8. Mejoras propuestas

| # | Mejora | Evidencia que la motiva | Dueño sugerido |
|---|---|---|---|
| 1 | **Escalar y proteger el login:** HPA en auth-service (1-3 réplicas), más CPU, timeout explícito y Circuit Breaker hacia identity/LDAP para fallar rápido en lugar de colgarse 60 s | 36,71 % de fallos y mediana de 29 s en login bajo 200 usuarios (sección 4) | Darío (chart) y Simón (código) |
| 2 | **Objetivos de rendimiento por endpoint y compuerta en el pipeline:** exigir p95 y errores por endpoint, no solo agregados; correr Locust normal en cada release a stage | El agregado de 160 ms ocultó que login y QR rompen (sección 4) | Simón |
| 3 | **Endurecer web y subir el umbral de seguridad:** actualizar la imagen base de nginx/Alpine, agregar cabeceras (CSP, X-Frame-Options, X-Content-Type-Options, `server_tokens off`), subir el umbral de Trivy a `HIGH` con excepciones documentadas, versionar `zap-baseline.conf` en el repo y planear la migración a Spring Boot 3.5 antes del 20/12/2026 | 1 High y 2 Medium en ZAP; 212 HIGH con arreglo que no bloquean; 55 HIGH en `web` (secciones 5 y 6) | Simón y Darío |
| 4 | **Medir ramas y servicios fuera del núcleo** y volver a correr JaCoCo/Sonar en el estado final | Cobertura de Sprint 1, con exclusiones; gateway con 18 líneas (sección 2) | Equipo |

## 9. Lista de evidencia pendiente

1. Reporte o captura de Sonar (quality gate por servicio).
2. Reporte de JaCoCo del estado final del Sprint 2.
3. Resultado del gate de ZAP en el build #3 (aprobó o falló) y revisión manual de
   "Dangerous JS Functions".
4. Perfilado de auth-service que confirme la causa de la lentitud del login.
5. Si form-service escaló en el estrés.
6. Versionar `zap-baseline.conf` en el repo (hoy solo está como artefacto).
