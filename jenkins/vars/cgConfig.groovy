// Configuración central del pipeline de CircleGuard.
//
// Todo lo que depende de la infraestructura (IPs, registro, usuario SSH) NO está
// escrito en el repositorio: se lee de las variables globales de Jenkins
// (Manage Jenkins → System → Global properties → Environment variables).
// Así, si Darío cambia una IP, no hace falta un commit; y el Jenkinsfile
// principal y el job de rollback comparten exactamente la misma configuración.

/** Repositorio en GitHub (owner/nombre). */
String repoSlug() {
    return 'ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286'
}

/** Los 8 microservicios Gradle (subproyectos :services:<nombre>). */
List<String> backendServices() {
    return [
        'circleguard-auth-service',
        'circleguard-identity-service',
        'circleguard-promotion-service',
        'circleguard-form-service',
        'circleguard-gateway-service',
        'circleguard-notification-service',
        'circleguard-file-service',
        'circleguard-dashboard-service',
    ]
}

/** Servicios núcleo: meta de cobertura >= 60 % (E1.6). */
List<String> coreServices() {
    return [
        'circleguard-auth-service',
        'circleguard-identity-service',
        'circleguard-promotion-service',
        'circleguard-form-service',
        'circleguard-gateway-service',
    ]
}

/** Todo lo que el pipeline empaqueta: los 8 servicios + el cliente web. */
List<String> allComponents() {
    return backendServices() + ['mobile']
}

/** Nombre de la imagen en el registro: circleguard-auth-service -> auth-service, mobile -> web. */
String imageName(String component) {
    return component == 'mobile' ? 'web' : component.replace('circleguard-', '')
}

/** Referencia completa de una imagen versionada. Nunca 'latest'. */
String imageRef(String component, String version) {
    requireSemver(version)
    return "${registry()}/${imageNamespace()}/${imageName(component)}:${version}"
}

String registry()       { return required('CG_REGISTRY') }              // p. ej. 10.0.1.20:8082
String imageNamespace() { return optional('CG_IMAGE_NAMESPACE', 'circleguard') }
String deployUser()     { return optional('CG_DEPLOY_USER', 'deploy') }
String deployDir()      { return optional('CG_DEPLOY_DIR', '/opt/circleguard') }
String webPort()        { return optional('CG_WEB_PORT', '80') }
String prodApprovers()  { return optional('CG_PROD_APPROVERS', 'luis,simon,dario') }

/** IP o DNS del servidor de un ambiente (dev, stage o prod). */
String host(String envName) {
    requireEnv(envName)
    return required("CG_${envName.toUpperCase()}_HOST")
}

/** Falla si el ambiente no es dev, stage o prod. */
void requireEnv(String envName) {
    if (!(envName in ['dev', 'stage', 'prod'])) {
        error "Ambiente desconocido: '${envName}'. Use dev, stage o prod."
    }
}

/**
 * Falla el build si la versión no es una etiqueta válida (CG-116):
 *   - release:  vX.Y.Z               (la que llega a prod)
 *   - snapshot: vX.Y.Z-SNAPSHOT.N    (la que se prueba en dev y stage; N = build)
 * Nunca 'latest'.
 */
void requireSemver(String version) {
    if (!(version ==~ /v\d+\.\d+\.\d+(-SNAPSHOT\.\d+)?/)) {
        error "Versión inválida '${version}': se espera vMAJOR.MINOR.PATCH o vMAJOR.MINOR.PATCH-SNAPSHOT.N (nunca 'latest')."
    }
}

/** Como requireSemver, pero solo acepta releases (vX.Y.Z): prod, tags y rollback. */
void requireRelease(String version) {
    if (!(version ==~ /v\d+\.\d+\.\d+/)) {
        error "Versión inválida '${version}': aquí solo se acepta un release vMAJOR.MINOR.PATCH."
    }
}

/** Etiqueta SNAPSHOT de un release candidato: v1.2.0 + build 42 -> v1.2.0-SNAPSHOT.42. */
String snapshotOf(String release, def buildNumber) {
    requireRelease(release)
    return "${release}-SNAPSHOT.${buildNumber}"
}

// ---- Escaneo de seguridad con Trivy (CG-90) ----
// Umbral que BLOQUEA el pipeline. Se puede subir a 'HIGH,CRITICAL' con la
// variable global CG_TRIVY_SEVERITY sin tocar el código.
String trivySeverity() { return optional('CG_TRIVY_SEVERITY', 'CRITICAL') }
// Imagen de Trivy fija (nunca latest) para que el resultado sea reproducible.
String trivyImage()    { return optional('CG_TRIVY_IMAGE', 'aquasec/trivy:0.58.1') }

// ---- Despliegue a Kubernetes con Helm (CG-91) ----
// 'helm' (Kubernetes, Parte 2) o 'compose' (SSH a las VMs, Parte 1, respaldo).
String deployTarget() {
    String target = optional('CG_DEPLOY_TARGET', 'helm')
    if (!(target in ['helm', 'compose'])) {
        error "CG_DEPLOY_TARGET='${target}' no es válido: use helm o compose."
    }
    return target
}
/** Namespace del ambiente en el clúster: circleguard-dev, -stage, -prod. */
String namespace(String envName) {
    requireEnv(envName)
    return "${optional('CG_K8S_NAMESPACE_PREFIX', 'circleguard-')}${envName}"
}
String helmChart()       { return optional('CG_HELM_CHART', 'deploy/helm/circleguard') }
String helmRelease()     { return optional('CG_HELM_RELEASE', 'circleguard') }
// Claves del values.yaml del chart donde van la versión y el registro.
String helmTagKey()      { return optional('CG_HELM_TAG_KEY', 'global.imageTag') }
String helmRegistryKey() { return optional('CG_HELM_REGISTRY_KEY', 'global.registry') }
String helmTimeout()     { return optional('CG_HELM_TIMEOUT', '10m') }
String helmImage()       { return optional('CG_HELM_IMAGE', 'dtzar/helm-kubectl:3.16.4') }

/** URL pública del cliente web de un ambiente (E2E y ZAP). */
String webUrl(String envName) {
    String url = optional("CG_${envName.toUpperCase()}_URL", '')
    return url ?: "http://${host(envName)}:${webPort()}"
}

// ---- Terraform (CG-89) ----
String terraformDir()   { return optional('CG_TF_DIR', 'infra/terraform/envs') }
String terraformImage() { return optional('CG_TERRAFORM_IMAGE', 'hashicorp/terraform:1.16.7') }

// ---- OWASP ZAP baseline (CG-92) ----
String zapImage()      { return optional('CG_ZAP_IMAGE', 'zaproxy/zap-stable:2.17.0') }

// ---- Promoción de imágenes sin reconstruir (CG-116) ----
String craneImage()    { return optional('CG_CRANE_IMAGE', 'gcr.io/go-containerregistry/crane/debug:v0.20.3') }

String required(String name) {
    String value = env.getProperty(name)
    if (!value?.trim()) {
        error "Falta la variable global ${name} en Jenkins (Manage Jenkins → System → Global properties)."
    }
    return value.trim()
}

String optional(String name, String defaultValue) {
    String value = env.getProperty(name)
    return value?.trim() ? value.trim() : defaultValue
}
