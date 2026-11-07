// Validación del cliente web (Expo) en CI: instala dependencias y genera el
// bundle web con 'expo export'. Es el mismo paso que hace el Dockerfile del
// cliente, así un PR que rompe el build web se detecta antes de empaquetar.

void call() {
    docker.image(env.CG_NODE_IMAGE ?: 'node:22-bookworm-slim').inside {
        dir('mobile') {
            withEnv(["HOME=${pwd()}", "npm_config_cache=${pwd()}/.npm", 'CI=1', 'EXPO_NO_TELEMETRY=1']) {
                sh 'npm ci --no-audit --no-fund'
                sh 'npx expo export --platform web --output-dir dist'
            }
        }
    }
}
