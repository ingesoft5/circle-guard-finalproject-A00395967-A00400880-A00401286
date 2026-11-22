# Resultados de carga (CG-88) · 21/11/2026

Ambiente `circleguard-dev`, imagen `v1.0.2-SNAPSHOT.0`, por el Ingress público
(HTTPS) desde Colombia hasta westus2. Una latencia base de ~125 ms es la ida y
vuelta de red; el tiempo de servidor es la diferencia.

## Objetivo (definido antes de correr) y resultado

| Corrida | Usuarios | Duración | Peticiones | p95 | Errores | Objetivo |
|---|---|---|---|---|---|---|
| Normal | 30 | 3 min | 3.424 (19 req/s) | **160 ms** | **0 %** | ✅ p95 < 500 ms, errores < 1 % |
| Estrés | 200 | 5 min | 31.033 (104 req/s) | **160 ms** | **0,55 %** | ✅ errores < 5 %; el HPA escaló |

## Por endpoint

| Endpoint | Normal: p50 / p95 / p99 | Estrés: p50 / p95 / p99 | Errores en estrés |
|---|---|---|---|
| `gateway: gate/validate` (portería) | 130 / 160 / 160 ms | 130 / 150 / 170 ms | 0 de 24.143 |
| `form: surveys` (cuestionario) | 140 / 160 / 430 ms | 140 / 160 / 280 ms | 0 de 5.693 |
| `auth: qr/generate` | 140 / 300 / 460 ms | 140 / 60 s / 60 s | 55 (504) |
| `auth: login` | 8,8 / 10 / 11 s | 29 / 60 / 60 s | 116 de 316 (59 × 401, 57 × 504) |

## HPA

`kubectl get hpa -w` durante el estrés ([`stress-hpa.txt`](stress-hpa.txt)):

| Hora | gateway-service CPU / objetivo | Réplicas |
|---|---|---|
| 23:03:41 | 4 % / 70 % | 1 |
| 23:05:47 | 112 % / 70 % | **2** (máximo de dev) |

Con 2 réplicas, la portería mantuvo p95 = 150 ms a 81 req/s, sin errores.

## Conclusiones

1. **Los dos escenarios críticos cumplen con holgura.** La validación de QR y
   el envío del cuestionario no se degradan ni con 200 usuarios: el p95 queda
   igual que en carga normal.
2. **auth-service es el cuello de botella.** El login concurrente pasa de 9 s
   (30 usuarios) a 29 s de mediana (200 usuarios), y un tercio de los logins
   falla por timeout (504 a los 60 s) o por un 401 cuando LDAP, Postgres o
   identity no responden a tiempo dentro de la cadena de autenticación. Es una
   sola réplica sin HPA, limitada a 1 vCPU, que hace hash de contraseña,
   consulta LDAP y llama a identity-service en cada login.
3. El HPA de gateway escaló a los ~2 min de empezar la carga, sin intervención
   manual (ventana de subida de 60 s configurada en el chart).

## Recomendaciones

| Acción | Efecto esperado | Dueño |
|---|---|---|
| HPA en auth-service (1-3 réplicas) y límite de CPU de 2 | Repartir el hash de contraseñas; quitar los 504 del login | Darío (chart) |
| Pool de conexiones LDAP y timeout explícito en IdentityClient | Que el login falle rápido en vez de colgarse 60 s | Simón (código; el Circuit Breaker de CG-98 aplica aquí) |
| Subir `maxReplicas` de gateway en prod (hoy 3) si crece la portería | Más margen en horas pico | Darío |

## Archivos

- `normal_*.csv`, `normal.html`, `stress_*.csv`, `stress.html`: exportes de Locust.
- `stress-hpa.txt`: estado del HPA cada 15 s durante el estrés.
- `stress-console.txt`: salida completa de la corrida.
