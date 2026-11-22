// Despliegue a un ambiente (CG-56, CG-57, CG-61; Kubernetes en CG-91).
//
// Dos modos, según la variable global CG_DEPLOY_TARGET:
//
// 'helm' (por defecto, Parte 2): helm upgrade --install del chart de la app en
//   el namespace del ambiente (circleguard-dev, -stage, -prod), con la versión
//   como valor. --atomic --wait: si algún pod no queda listo (sondas de
//   Actuator) dentro del timeout, Helm deshace el cambio solo y el build queda
//   en rojo; el ambiente sigue con la versión anterior. El kubeconfig viene de
//   la credencial 'kubeconfig-circleguard' (archivo secreto), nunca del repo.
//
// 'compose' (Parte 1, respaldo): SSH + docker compose en las VMs. Se conserva
//   para poder volver a él cambiando una variable si el clúster falla.
//
// Uso: cgDeploy(env: 'dev', version: 'v1.1.0-SNAPSHOT.42')

void call(Map args) {
    String envName = args.env
    String version = args.version
    cgConfig.requireSemver(version)

    String before = currentVersion(envName)
    echo "Desplegando ${version} en ${envName} (${cgConfig.deployTarget()}). Versión actual: ${before ?: '(ninguna)'}"
    if (cgConfig.deployTarget() == 'helm') {
        helmDeploy(envName, version)
    } else {
        composeDeploy(envName, version)
    }
    echo "${envName}: ${before ?: '(ninguna)'} -> ${version}"
}

/** Versión desplegada actualmente en un ambiente ('' si no hay ninguna). */
String currentVersion(String envName) {
    return cgConfig.deployTarget() == 'helm' ? helmCurrentVersion(envName) : composeCurrentVersion(envName)
}

// ---------------------------------------------------------------- Helm (K8s)

void helmDeploy(String envName, String version) {
    String chart = cgConfig.helmChart()
    String valuesFile = "${chart}/values-${envName}.yaml"
    withHelm(envName) {
        withEnv(["CHART=${chart}", "VALUES=${fileExists(valuesFile) ? valuesFile : ''}",
                 "TAG_KEY=${cgConfig.helmTagKey()}", "REGISTRY_KEY=${cgConfig.helmRegistryKey()}",
                 "DEPLOY_VERSION=${version}", "DEPLOY_REGISTRY=${cgConfig.registry()}"]) {
            sh '''
                helm upgrade --install "$RELEASE" "$CHART" \
                    --namespace "$NAMESPACE" \
                    ${VALUES:+--values "$VALUES"} \
                    --set "$TAG_KEY=$DEPLOY_VERSION" \
                    --set "$REGISTRY_KEY=$DEPLOY_REGISTRY" \
                    --atomic --wait --timeout "$HELM_TIMEOUT"
                helm status "$RELEASE" --namespace "$NAMESPACE"
                kubectl get pods --namespace "$NAMESPACE" -o wide
            '''
        }
    }
}

String helmCurrentVersion(String envName) {
    String version = ''
    withHelm(envName) {
        String json = sh(returnStdout: true,
                         script: 'helm get values "$RELEASE" --namespace "$NAMESPACE" --all -o json 2>/dev/null || true').trim()
        if (json) {
            def node = readJSON(text: json)
            cgConfig.helmTagKey().tokenize('.').each { key -> node = node?.get(key) }
            version = node ? node.toString() : ''
        }
    }
    return version
}

/** Corre el cuerpo en el contenedor de Helm (trae kubectl) con el kubeconfig del clúster. */
void withHelm(String envName, Closure body) {
    withCredentials([file(credentialsId: 'kubeconfig-circleguard', variable: 'KUBECONFIG')]) {
        docker.image(cgConfig.helmImage()).inside('--entrypoint=') {
            withEnv(["RELEASE=${cgConfig.helmRelease()}", "NAMESPACE=${cgConfig.namespace(envName)}",
                     "HELM_TIMEOUT=${cgConfig.helmTimeout()}", "HOME=${pwd()}/.helm-home"]) {
                body()
            }
        }
    }
}

// ------------------------------------------------- Docker Compose por SSH (P1)

void composeDeploy(String envName, String version) {
    withDeployEnv(envName) {
        withEnv(["DEPLOY_VERSION=${version}", "DEPLOY_REGISTRY=${cgConfig.registry()}"]) {
            sh '''
                ssh -o StrictHostKeyChecking=accept-new "${DEPLOY_USER}@${DEPLOY_HOST}" "
                    set -e
                    cd '${DEPLOY_PATH}'
                    export VERSION='${DEPLOY_VERSION}'
                    export REGISTRY='${DEPLOY_REGISTRY}'
                    docker compose pull --quiet
                    docker compose up -d --remove-orphans --wait --wait-timeout 600
                    echo '${DEPLOY_VERSION}' > .deployed-version
                    docker compose ps
                "
            '''
        }
    }
}

String composeCurrentVersion(String envName) {
    String version = ''
    withDeployEnv(envName) {
        version = sh(
            script: '''ssh -o StrictHostKeyChecking=accept-new "${DEPLOY_USER}@${DEPLOY_HOST}" \
                       "cat '${DEPLOY_PATH}/.deployed-version' 2>/dev/null || true"''',
            returnStdout: true
        ).trim()
    }
    return version
}

void withDeployEnv(String envName, Closure body) {
    sshagent(credentials: ['deploy-ssh']) {
        withEnv([
            "DEPLOY_HOST=${cgConfig.host(envName)}",
            "DEPLOY_USER=${cgConfig.deployUser()}",
            "DEPLOY_PATH=${cgConfig.deployDir()}/${envName}",
        ]) {
            body()
        }
    }
}
