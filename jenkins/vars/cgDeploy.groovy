// Despliegue a un ambiente por SSH + Docker Compose (CG-56, CG-57, CG-61).
//
// En el servidor del ambiente, dentro de <CG_DEPLOY_DIR>/<ambiente>, está el
// docker-compose.yml de Luis, que usa ${REGISTRY}/circleguard/<x>:${VERSION}.
// El pipeline exporta VERSION y también REGISTRY (desde CG_REGISTRY): así el
// registro del que se descarga es el mismo en el que Jenkins publicó, aunque
// el .env del servidor no lo defina. Las variables del shell tienen prioridad
// sobre el .env en Docker Compose.
//
// - 'docker compose up --wait' espera a que todos los contenedores queden
//   healthy (healthchecks del compose). Si alguno no lo logra en 5 minutos,
//   el comando falla y el build queda en rojo.
// - La versión desplegada se guarda en .deployed-version en el servidor: así
//   el job de rollback puede mostrar el "antes" y el "después".
// - La llave SSH está en la credencial 'deploy-ssh'; nunca en el repositorio.
//
// Uso: cgDeploy(env: 'dev', version: 'v1.1.0')

void call(Map args) {
    String envName = args.env
    String version = args.version
    cgConfig.requireSemver(version)
    String host = args.host ?: cgConfig.host(envName)

    echo "Desplegando ${version} en ${envName} (${host})"
    String before = currentVersion(envName, host)
    echo "Versión actual en ${envName}: ${before ?: '(ninguna)'}"

    withDeployEnv(envName, host) {
        withEnv(["DEPLOY_VERSION=${version}", "DEPLOY_REGISTRY=${cgConfig.registry()}"]) {
            sh '''
                ssh -o StrictHostKeyChecking=accept-new "${DEPLOY_USER}@${DEPLOY_HOST}" "
                    set -e
                    cd '${DEPLOY_PATH}'
                    export VERSION='${DEPLOY_VERSION}'
                    export REGISTRY='${DEPLOY_REGISTRY}'
                    docker compose pull --quiet
                    docker compose up -d --remove-orphans --wait --wait-timeout 300
                    echo '${DEPLOY_VERSION}' > .deployed-version
                    docker compose ps
                "
            '''
        }
    }
    echo "${envName}: ${before ?: '(ninguna)'} -> ${version}"
}

/** Versión desplegada actualmente en un ambiente ('' si no hay ninguna). */
String currentVersion(String envName, String host = null) {
    String target = host ?: cgConfig.host(envName)
    String version = ''
    withDeployEnv(envName, target) {
        version = sh(
            script: '''ssh -o StrictHostKeyChecking=accept-new "${DEPLOY_USER}@${DEPLOY_HOST}" \
                       "cat '${DEPLOY_PATH}/.deployed-version' 2>/dev/null || true"''',
            returnStdout: true
        ).trim()
    }
    return version
}

void withDeployEnv(String envName, String host, Closure body) {
    sshagent(credentials: ['deploy-ssh']) {
        withEnv([
            "DEPLOY_HOST=${host}",
            "DEPLOY_USER=${cgConfig.deployUser()}",
            "DEPLOY_PATH=${cgConfig.deployDir()}/${envName}",
        ]) {
            body()
        }
    }
}
