# Registro de cambios por ambiente (CG-105)

Este registro documenta qué versión llegó a cada ambiente, cuándo, quién la aprobó y con qué resultado. La fuente primaria es el historial de builds de Jenkins; las fechas de los tags de Git se usan como verificación cruzada.

> **Convención de hora:** las horas están en America/Bogota (UTC-5). Los tags de Git se guardan en UTC: `v1.1.0` = 2026-11-22 23:24 UTC (18:24 Bogotá) y `v1.2.0` = 2026-11-22 23:54 UTC (18:54 Bogotá). Coinciden con las horas de la tabla.

## 1. Cambios del 22 de noviembre de 2026

| Fecha y hora (Bogotá) | Versión | Ambiente | Build de Jenkins | Quién aprobó | Resultado | Nota |
|---|---|---|---|---|---|---|
| 2026-11-22, hora pendiente de evidencia (confirmar en Jenkins) | pendiente de evidencia (confirmar en Jenkins) | dev (E2E) → no llegó a prod | Build #1 de `main` | No aplica (no hubo aprobación de prod) | **Fallido** en E2E sobre dev: error CORS 403 | Se detuvo antes de prod. La corrección llegó en el PR #84 (`fix(k8s): treat same-host https origin as same-origin behind traefik`). |
| 2026-11-22, ~18:25 | v1.1.0 | prod | Build #2 de `main` | Darío (confirmar el registro en Jenkins) | **Exitoso**: 9/9 pods en ejecución y login con respuesta 200 | Tag Git `v1.1.0` creado a las 18:24 (hora de Bogotá). |
| 2026-11-22, ~18:58 | v1.2.0 | prod | Build #3 de `main` (con `BUILD_ALL`) | Confirmar en Jenkins | **Exitoso** (pendiente de evidencia del resultado final en Jenkins) | Tag Git `v1.2.0` creado a las 18:54 (hora de Bogotá). |
| 2026-11-22, ~20:00 | v1.1.0 (rollback desde v1.2.0) | prod | Rollback #2 (job `circleguard-rollback`) | Confirmar en Jenkins | **Exitoso**: prod volvió de v1.2.0 a v1.1.0 | Rollback ejecutado sobre el job de rollback del pipeline. |

## 2. Cómo leer esta tabla

- **El fallo de CORS es una compuerta que funcionó.** El build #1 se detuvo en la etapa E2E de dev, que es justo la barrera que debe impedir que un error llegue a producción. Sin esa etapa, el error de CORS habría llegado a los usuarios.
- **Prod solo recibe versiones aprobadas.** Los despliegues a prod (builds #2 y #3) deben pasar por la aprobación manual de Jenkins; para el build #3 el aprobador está pendiente de confirmar.
- **El rollback deja rastro.** El rollback #2 aparece como una fila propia, con su versión de destino (v1.1.0), para que la auditoría vea el estado anterior y el posterior.

## 3. Pendientes para cerrar el registro

- Completar las celdas marcadas como "confirmar en Jenkins" con el usuario que aprobó cada paso y la hora exacta de cada build.
- Agregar el enlace a cada build (copiar la URL desde Jenkins; no pegar direcciones internas con IP).
- Confirmar el resultado final del build #3 (v1.2.0) en Jenkins; el tag Git existe, pero el registro de Jenkins es la evidencia.

## 4. Referencias

- Convención de versiones y SNAPSHOT: [versionado-y-releases.md](versionado-y-releases.md).
- Plan de rollback y flujo de aprobación: [solicitud-de-cambio.md](solicitud-de-cambio.md) y [politica-de-aprobacion.md](politica-de-aprobacion.md).
- Corrección de CORS: PR #84, commit `2122b44`.
