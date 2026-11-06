// Empaquetamiento y publicación en Nexus (CG-55).
//
// - Construye la imagen de cada componente con el Dockerfile multi-stage de
//   Luis y la publica con la versión semver. Nunca se usa 'latest'.
// - 'docker login --password-stdin' evita que la contraseña aparezca en el
//   log o en 'ps'. Los scripts sh van entre comillas simples para que las
//   variables secretas las expanda el shell y no Groovy (Jenkins las enmascara).
// - Convención de Dockerfiles (confirmar con Luis):
//     services/<servicio>/Dockerfile  con contexto en la raíz del repo
//     mobile/Dockerfile               con contexto en mobile/

void call(List<String> components, String version) {
    cgConfig.requireSemver(version)

    withCredentials([usernamePassword(credentialsId: 'nexus-docker',
                                      usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        withEnv(["REGISTRY=${cgConfig.registry()}"]) {
            sh 'echo "$REG_PASS" | docker login "$REGISTRY" -u "$REG_USER" --password-stdin'
            try {
                components.each { component -> buildAndPush(component, version) }
            } finally {
                sh 'docker logout "$REGISTRY" || true'
            }
        }
    }
}

void buildAndPush(String component, String version) {
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
            docker push "$IMAGE"
        '''
    }
    echo "Publicada ${cgConfig.imageRef(component, version)}"
}
