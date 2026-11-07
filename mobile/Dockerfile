# Imagen del cliente web de CircleGuard. El contexto de build es mobile/:
#   docker build -f mobile/Dockerfile --build-arg VERSION=vX.Y.Z -t <registro>/circleguard/web:vX.Y.Z mobile

# Etapa 1: genera el bundle web estático con Expo.
# Misma imagen de Node que usa jenkins/vars/cgWebCheck.groovy en CI.
FROM node:22-bookworm-slim AS build

WORKDIR /app

ENV CI=1 \
    EXPO_NO_TELEMETRY=1

# Las dependencias van en su propia capa: se reutilizan si solo cambia el código.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# Rutas relativas al mismo origen: la imagen no lleva la URL de ningún ambiente,
# por eso la misma imagen se despliega en dev, stage y prod. nginx hace el enrutamiento.
ENV EXPO_PUBLIC_AUTH_BASE_URL=/svc/auth \
    EXPO_PUBLIC_DASHBOARD_BASE_URL=/svc/dashboard \
    EXPO_PUBLIC_FILE_BASE_URL=/svc/file \
    EXPO_PUBLIC_FORM_BASE_URL=/svc/form \
    EXPO_PUBLIC_GATEWAY_BASE_URL=/svc/gateway \
    EXPO_PUBLIC_IDENTITY_BASE_URL=/svc/identity \
    EXPO_PUBLIC_NOTIFICATION_BASE_URL=/svc/notification \
    EXPO_PUBLIC_PROMOTION_BASE_URL=/svc/promotion

COPY . .
RUN npx expo export --platform web --output-dir dist

# Etapa 2: solo nginx y los archivos estáticos; ni Node ni node_modules llegan a producción.
# nginx-unprivileged corre con un usuario sin privilegios y escucha en 8080.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

ARG VERSION=dev

# source y url reemplazan los heredados de la imagen base (apuntan al repo de nginx).
# revision la agrega el pipeline con --label (jenkins/vars/cgDockerPublish.groovy).
LABEL org.opencontainers.image.title="circleguard-web" \
      org.opencontainers.image.description="Cliente web de CircleGuard servido por nginx" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286" \
      org.opencontainers.image.url="https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286"

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
