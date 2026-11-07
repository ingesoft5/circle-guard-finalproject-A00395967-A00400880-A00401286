# Imagen de circleguard-file-service (puerto 8085).
# El contexto de build es la RAÍZ del repo:
#   docker build -f services/circleguard-file-service/Dockerfile --build-arg VERSION=v0.0.1 .

# ---------------------------------------------------------------------------
# Etapa 0: distribución de Gradle.
# El repo no versiona gradle/wrapper/gradle-wrapper.jar (lo ignora *.jar en
# .gitignore), así que ./gradlew no puede ejecutarse dentro del contenedor.
# Se toma Gradle 8.14 (misma versión que gradle-wrapper.properties) de la
# imagen oficial y se copia a la etapa de build.
# ---------------------------------------------------------------------------
FROM gradle:8.14-jdk21 AS gradle-dist

# ---------------------------------------------------------------------------
# Etapa 1: compilación del jar ejecutable (bootJar)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
COPY --from=gradle-dist /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"
WORKDIR /workspace

# 1) Solo los archivos de build: esta capa se reutiliza mientras no cambien
#    las dependencias. settings.gradle.kts incluye los 8 subproyectos, por eso
#    se copian los 8 build.gradle.kts (Gradle exige que cada directorio exista).
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY services/circleguard-file-service/build.gradle.kts         services/circleguard-file-service/
COPY services/circleguard-identity-service/build.gradle.kts     services/circleguard-identity-service/
COPY services/circleguard-promotion-service/build.gradle.kts    services/circleguard-promotion-service/
COPY services/circleguard-notification-service/build.gradle.kts services/circleguard-notification-service/
COPY services/circleguard-form-service/build.gradle.kts         services/circleguard-form-service/
COPY services/circleguard-file-service/build.gradle.kts         services/circleguard-file-service/
COPY services/circleguard-gateway-service/build.gradle.kts      services/circleguard-gateway-service/
COPY services/circleguard-dashboard-service/build.gradle.kts    services/circleguard-dashboard-service/

# 2) Código fuente solo del servicio que se construye.
COPY services/circleguard-file-service/src services/circleguard-file-service/src

# 3) bootJar genera el jar ejecutable y además un *-plain.jar (sin dependencias);
#    se copia únicamente el ejecutable a /workspace/app.jar.
RUN gradle --no-daemon --console=plain :services:circleguard-file-service:bootJar -x test \
 && find services/circleguard-file-service/build/libs -name '*.jar' ! -name '*-plain.jar' \
        -exec cp {} /workspace/app.jar \; \
 && test -s /workspace/app.jar

# ---------------------------------------------------------------------------
# Etapa 2: runtime mínimo y sin root
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG VERSION=dev
LABEL org.opencontainers.image.title="circleguard-file-service" \
      org.opencontainers.image.version="${VERSION}"

# Usuario/grupo sin privilegios con UID/GID 10001 (wget viene incluido en BusyBox
# y lo usará el healthcheck de docker-compose).
RUN addgroup -g 10001 -S app && adduser -u 10001 -S -G app -H -h /app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
