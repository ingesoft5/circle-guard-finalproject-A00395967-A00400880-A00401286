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
    if (!(envName in ['dev', 'stage', 'prod'])) {
        error "Ambiente desconocido: '${envName}'. Use dev, stage o prod."
    }
    return required("CG_${envName.toUpperCase()}_HOST")
}

/** Falla el build si la versión no es semver con prefijo v (vX.Y.Z). */
void requireSemver(String version) {
    if (!(version ==~ /v\d+\.\d+\.\d+/)) {
        error "Versión inválida '${version}': se espera vMAJOR.MINOR.PATCH (nunca 'latest')."
    }
}

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
