# Patrones de resiliencia (CG-98 y CG-99)

Dos patrones de Resilience4j sobre llamadas HTTP reales entre servicios. Los
umbrales viven en el `application.yml` de cada servicio (configuración
externa), no en el código, y Resilience4j publica sus métricas en Micrometer
(`resilience4j_circuitbreaker_state`, `resilience4j_retry_calls_total`).

| Patrón | Dónde | Qué protege | Prueba |
|---|---|---|---|
| Circuit Breaker | dashboard → promotion (`PromotionClient`) | Que dashboard se quede esperando a un promotion caído | `PromotionClientCircuitBreakerTest` |
| Retry con backoff exponencial | auth → identity (`IdentityClient`) | Que un corte momentáneo de identity tumbe el login | `IdentityClientRetryTest` |

## 1. Circuit Breaker: dashboard → promotion

**Problema.** Las estadísticas del tablero salen de promotion. Si promotion se
cae o responde lento, cada petición al tablero se queda esperando, ocupa un
hilo de dashboard y le sigue mandando tráfico a un servicio que ya no puede
responder.

**Solución.** Resilience4j cuenta el resultado de las últimas 10 llamadas. Si
al menos la mitad falla o tarda más de 2 s, el circuito se **abre** durante
30 s. Mientras está abierto, las llamadas no salen a la red: van directo al
*fallback*, que responde con estadísticas degradadas
(`"degraded": true, "error": "Service unavailable"`). Pasados los 30 s deja
pasar 2 llamadas de prueba (*half-open*): si salen bien, el circuito se cierra.
Además, el cliente tiene timeouts (2 s de conexión y 3 s de lectura), porque
sin ellos una llamada colgada nunca cuenta como fallo.

**Por qué aquí y no en auth → identity.** El tablero es una vista de solo
lectura y una respuesta degradada es aceptable: el usuario ve "sin datos" un
momento. En el login no hay *fallback* posible: no se puede inventar un ID
anónimo. Un circuito abierto ahí bloquearía todos los inicios de sesión, y para
ese caso el patrón correcto es el Retry (sección 2).

**Trade-offs.**
- Durante 30 s el tablero muestra datos degradados aunque promotion ya se haya
  recuperado.
- Un umbral mal calibrado puede abrir el circuito por unos pocos errores
  aislados; por eso se exige un mínimo de 5 llamadas antes de evaluar.

**Prueba (falla inyectada).** `PromotionClientCircuitBreakerTest` usa el proxy
real de Resilience4j y simula promotion con `MockRestServiceServer`:
1. promotion responde 500 cuatro veces: el circuito pasa a **OPEN** y cada
   respuesta es el fallback;
2. la 5.ª llamada **no llega a promotion** (el mock no la espera) y la métrica
   de llamadas no permitidas sube a 1;
3. con promotion sano, el circuito sigue **CLOSED** y los datos llegan intactos.

## 2. Retry con backoff exponencial: auth → identity

**Problema.** Para iniciar sesión, auth le pide a identity el ID anónimo del
usuario. Si identity se está reiniciando (por ejemplo, durante un despliegue)
o hay un corte de red momentáneo, el login falla aunque un segundo después
funcionaría.

**Solución.** Hasta 3 intentos, esperando 200 ms y luego 400 ms (backoff
exponencial: cada espera duplica la anterior, para no saturar a un servicio que
se está recuperando). Solo se reintenta ante fallos **transitorios**:

| Se reintenta | No se reintenta |
|---|---|
| Error de red o timeout (`ResourceAccessException`) | 4xx: la petición está mal y repetirla da lo mismo |
| 502, 503 y 504 (servicio reiniciándose o detrás de un proxy) | 500: es un error de identity, no un corte momentáneo |

**Por qué se puede reintentar: la operación es idempotente.**
`POST /api/v1/identities/map` hace "buscar o crear" por el hash de la
identidad, que tiene un **índice único** (`idx_identity_hash`). Si el primer
intento sí llegó a identity pero se perdió la respuesta, el reintento encuentra
el registro y devuelve el **mismo** ID anónimo; nunca crea uno duplicado.

**Por qué no se reintenta lo que no es idempotente.** Si una operación crea
algo nuevo en cada llamada, un reintento después de una respuesta perdida la
ejecuta dos veces. Por ejemplo, reenviar el cuestionario de salud (form-service)
registraría dos encuestas y publicaría dos eventos en Kafka, y promotion
podría escalar el estado del estudiante dos veces. Esas operaciones necesitan
primero una clave de idempotencia; sin ella, no llevan Retry.

**Trade-offs.**
- En el peor caso el login tarda unos 600 ms más antes de fallar.
- Si identity está realmente caído, los reintentos solo retrasan el error; por
  eso son pocos (3) y con espera corta.

**Prueba (fallas transitorias inyectadas).** `IdentityClientRetryTest`:
1. identity responde 503, luego hay un corte de red y luego responde bien: el
   login obtiene el ID al **tercer intento** y las esperas crecen (50 ms + 100 ms
   en la prueba);
2. identity responde 503 siempre: se rinde después de **3 intentos**;
3. un 400 y un 500 se intentan **una sola vez**.
