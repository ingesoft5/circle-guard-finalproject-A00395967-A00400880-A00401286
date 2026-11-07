# Imagen de Jenkins para CircleGuard (CG-48).
#
# Agrega a la imagen oficial lo que necesita el pipeline:
#   - Docker CLI + compose + buildx: docker build/push, Testcontainers y los
#     agentes Docker (Playwright, Node). El daemon es el del host (socket montado).
#   - git-cliff: versionado semántico y release notes.
#   - jq: armar el JSON del GitHub Release.
#   - openssh-client: despliegue por SSH a dev, stage y prod.
#   - Los plugins de plugins.txt.
#
# Sirve para el Jenkins local (docker compose en esta carpeta) y Darío puede
# reutilizarla en el servidor de herramientas.
FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg jq openssh-client \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin docker-buildx-plugin \
 && rm -rf /var/lib/apt/lists/*

ARG GIT_CLIFF_VERSION=2.6.1
RUN curl -fsSL "https://github.com/orhun/git-cliff/releases/download/v${GIT_CLIFF_VERSION}/git-cliff-${GIT_CLIFF_VERSION}-x86_64-unknown-linux-gnu.tar.gz" \
      | tar -xz -C /tmp \
 && install -m 0755 "/tmp/git-cliff-${GIT_CLIFF_VERSION}/git-cliff" /usr/local/bin/git-cliff \
 && rm -rf /tmp/git-cliff-* \
 && git-cliff --version

USER jenkins

COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
