# Pruebas de carga con Locust (CG-88)

## Objetivo (definido antes de correr)

| Métrica | Carga normal | Estrés |
|---|---|---|
| Latencia p95 (todas las peticiones) | **< 500 ms** | se registra; se espera que suba |
| Tasa de errores | **< 1 %** | **< 5 %** (degradación sin caída) |
| Escalado | HPA estable (1 réplica) | **el HPA de gateway sube réplicas** |

Por qué: la portería es el punto crítico del campus. Validar un QR por encima
de medio segundo ya se nota en una fila, y un error deja a alguien sano en la
puerta. El estrés busca el punto de quiebre y comprobar que el HPA reacciona.

## Escenarios (`locustfile.py`)

| Usuario | Peso | Flujo |
|---|---|---|
| `QrGateUser` | 3 | login → `GET /svc/auth/.../qr/generate` (cada 45 s) → `POST /svc/gateway/api/v1/gate/validate` |
| `SurveyUser` | 1 | login → `POST /svc/form/api/v1/surveys` (form-service guarda y publica en Kafka) |

Se prueba por el Ingress público (HTTPS, Traefik → web/nginx → servicio),
la misma ruta que usan el navegador y los E2E. Usuarios: los de prueba
sembrados por la migración de auth-service (`health_user`).

## Cómo correr

```bash
python3 -m venv .venv && .venv/bin/pip install -r tests/performance/requirements.txt
H=https://dev.40-64-120-3.sslip.io

# Carga normal: 30 usuarios, 3 min
.venv/bin/locust -f tests/performance/locustfile.py -H $H --headless -u 30 -r 5 -t 3m \
  --csv tests/performance/results/normal --html tests/performance/results/normal.html

# Estrés: 200 usuarios, 5 min (sin exigir el objetivo de carga normal)
LOCUST_ENFORCE=0 .venv/bin/locust -f tests/performance/locustfile.py -H $H --headless -u 200 -r 10 -t 5m \
  --csv tests/performance/results/stress --html tests/performance/results/stress.html

# En otra terminal, evidencia del HPA
kubectl get hpa -n circleguard-dev -w
```

La corrida termina con `CUMPLE` o `NO CUMPLE` y, en carga normal, con código de
salida 1 si no cumple. Así se puede usar como compuerta en el pipeline.

Resultados y análisis: [`results/`](results/) y [`results/ANALISIS.md`](results/ANALISIS.md).
