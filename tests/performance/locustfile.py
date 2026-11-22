"""Pruebas de carga de CircleGuard con Locust (CG-88).

Dos escenarios, por la misma frontera que usa el navegador (el web y su proxy
/svc/<servicio>/), como los E2E:

  QrGateUser       portería: el usuario pide su QR a auth-service y la
                   portería lo valida en gateway-service (POST /gate/validate).
  SurveyUser       el usuario envía el cuestionario de salud a form-service
                   (POST /surveys), que publica el evento en Kafka.

Objetivo, definido ANTES de correr (ver README.md):
  p95 < 500 ms y errores < 1 % en carga normal.

Credenciales: usuarios sembrados para pruebas por la migración V2/V3 de
auth-service (públicos en el repo). Se pueden cambiar con LOCUST_USER/LOCUST_PASS.
"""

import os
import time
from datetime import date

from locust import HttpUser, between, events, task

USER = os.getenv("LOCUST_USER", "health_user")
PASSWORD = os.getenv("LOCUST_PASS", "password")
QR_TTL_SECONDS = 45  # auth-service emite QR de 60 s; se renueva antes de que venza.


class LoggedInUser(HttpUser):
    abstract = True

    token, anonymous_id, next_login = None, None, 0.0

    def on_start(self):
        self.login()

    def login(self):
        """Inicia sesión. Si falla, no reintenta antes de 10 s (sin martillar auth-service)."""
        self.next_login = time.time() + 10
        with self.client.post(
            "/svc/auth/api/v1/auth/login",
            json={"username": USER, "password": PASSWORD},
            name="auth: login",
            catch_response=True,
        ) as r:
            if r.status_code != 200 or "token" not in r.text:
                r.failure(f"login {r.status_code}")
                return False
            body = r.json()
            self.token, self.anonymous_id = body["token"], body["anonymousId"]
            return True

    def logged_in(self):
        """True si hay sesión; sin sesión, reintenta el login cuando toca y salta la tarea."""
        if self.token:
            return True
        if time.time() >= self.next_login:
            return self.login()
        return False

    @property
    def auth(self):
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}


class QrGateUser(LoggedInUser):
    """Validación de acceso con QR -> gateway (portería del campus)."""

    weight = 3
    wait_time = between(0.5, 2)
    qr_token, qr_issued = None, 0.0

    def _qr(self):
        if self.qr_token and time.time() - self.qr_issued < QR_TTL_SECONDS:
            return self.qr_token
        r = self.client.get("/svc/auth/api/v1/auth/qr/generate", headers=self.auth, name="auth: qr/generate")
        if r.status_code == 200:
            self.qr_token, self.qr_issued = r.json()["qrToken"], time.time()
        return self.qr_token

    @task
    def validate_at_gate(self):
        if not self.logged_in():
            return
        token = self._qr()
        if not token:
            return
        with self.client.post(
            "/svc/gateway/api/v1/gate/validate", json={"token": token}, name="gateway: gate/validate", catch_response=True
        ) as r:
            # Un usuario sano con QR vigente debe pasar: cualquier otra cosa es un error funcional.
            if r.status_code != 200 or not r.json().get("valid"):
                r.failure(f"portería rechazó un QR válido: {r.status_code} {r.text[:80]}")


class SurveyUser(LoggedInUser):
    """Envío del cuestionario de salud -> form-service (y Kafka)."""

    weight = 1
    wait_time = between(1, 3)

    @task
    def submit_survey(self):
        if not self.logged_in():
            return
        survey = {
            "anonymousId": self.anonymous_id,
            "hasFever": False,
            "hasCough": False,
            "otherSymptoms": "",
            "exposureDate": date.today().isoformat(),
            "responses": {"q1": "no", "q2": "no"},
        }
        with self.client.post(
            "/svc/form/api/v1/surveys", json=survey, headers=self.auth, name="form: surveys", catch_response=True
        ) as r:
            if r.status_code != 200 or "id" not in r.text:
                r.failure(f"encuesta no guardada: {r.status_code}")


@events.quitting.add_listener
def check_objective(environment, **_):
    """Marca la corrida como fallida si no cumple el objetivo (código de salida 1)."""
    stats = environment.stats.total
    if not stats.num_requests:
        return
    p95 = stats.get_response_time_percentile(0.95)
    fail_ratio = stats.fail_ratio
    ok = p95 < 500 and fail_ratio < 0.01
    print(f"\nObjetivo p95<500 ms y errores<1 %: p95={p95:.0f} ms, errores={fail_ratio:.2%} -> {'CUMPLE' if ok else 'NO CUMPLE'}")
    if not ok and os.getenv("LOCUST_ENFORCE", "1") == "1":
        environment.process_exit_code = 1
