// Empaquetamiento, escaneo y publicación en Nexus (CG-55, CG-90, CG-116).
//
// - Construye la imagen de cada componente con el Dockerfile multi-stage de
//   Luis y la publica con la versión indicada. En main es una SNAPSHOT
//   (vX.Y.Z-SNAPSHOT.N); el release vX.Y.Z se crea después, al promover la
//   misma imagen (cgPromote). Nunca se usa 'latest'.
// - Antes de publicar, Trivy escanea la imagen: si tiene vulnerabilidades del
//   umbral, el build falla y la imagen NO llega al registro (CG-90).
// - 'docker login --password-stdin' evita que la contraseña aparezca en el
//   log o en 'ps'. Los scripts sh van entre comillas simples para que las
//   variables secretas las expanda el shell y no Groovy (Jenkins las enmascara).
// - Convención de Dockerfiles:
//     services/<servicio>/Dockerfile  con contexto en la raíz del repo
//     mobile/Dockerfile               con contexto en mobile/

void call(List<String> components, String version) {
    cgConfig.requireSemver(version)

    // 1. Construir y escanear todo. Si una imagen no pasa, no se publica ninguna.
    components.each { component -> build(component, version) }
    components.each { component ->
        cgTrivy.scanImage(cgConfig.imageRef(component, version), "image-${cgConfig.imageName(component)}")
    }
    cgTrivy.archiveReports()

    // 2. Publicar.
    withCredentials([usernamePassword(credentialsId: 'nexus-docker',
                                      usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        withEnv(["REGISTRY=${cgConfig.registry()}"]) {
            sh 'echo "$REG_PASS" | docker login "$REGISTRY" -u "$REG_USER" --password-stdin'
            try {
                components.each { component ->
                    withEnv(["IMAGE=${cgConfig.imageRef(component, version)}"]) {
                        sh 'docker push "$IMAGE"'
                    }
                    echo "Publicada ${cgConfig.imageRef(component, version)}"
                }
            } finally {
                sh 'docker logout "$REGISTRY" || true'
            }
        }
    }
}

void build(String component, String version) {
    boolean web = component == 'mobile'
    withEnv([
        "IMAGE=${cgConfig.imageRef(component, version)}",
        "DOCKERFILE=${web ? 'mobile/Dockerfile' : "services/${component}/Dockerfile"}",
        "CONTEXT=${web ? 'mobile' : '.'}",
        "VERSION=${version}",
    ]) {
        sh '''
            docker build -f "$DOCKERFILE" \
                --build-arg VERSION="$VERSION" \
                --label org.opencontainers.image.version="$VERSION" \
                --label org.opencontainers.image.revision="$(git rev-parse HEAD)" \
                -t "$IMAGE" "$CONTEXT"
        '''
    }
}
