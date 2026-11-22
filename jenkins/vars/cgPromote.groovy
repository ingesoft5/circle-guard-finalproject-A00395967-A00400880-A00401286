// Promoción SNAPSHOT -> release sin reconstruir (CG-116).
//
// dev y stage prueban la imagen vX.Y.Z-SNAPSHOT.N. Cuando una persona aprueba
// producción, este paso copia ESA MISMA imagen en Nexus con la etiqueta de
// release vX.Y.Z. No se vuelve a compilar ni a construir nada: crane copia el
// manifiesto dentro del registro.
//
// Después compara los digests (sha256 del manifiesto) de origen y destino: si
// no son idénticos, lo que llegaría a prod no sería lo que se probó y el build
// falla. Nexus es ALLOW_ONCE, así que la etiqueta de release no se puede
// sobrescribir después.
//
// Uso: cgPromote(release: 'v1.2.0', snapshot: 'v1.2.0-SNAPSHOT.42')

void call(Map args) {
    String release = args.release
    String snapshot = args.snapshot
    cgConfig.requireRelease(release)
    cgConfig.requireSemver(snapshot)
    if (!snapshot.startsWith("${release}-SNAPSHOT.")) {
        error "La snapshot ${snapshot} no corresponde al release ${release}."
    }

    String registry = cgConfig.registry()
    List<String> lines = []
    withCredentials([usernamePassword(credentialsId: 'nexus-docker',
                                      usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
        // HOME en el workspace: crane guarda ahí la sesión y se borra con cleanWs.
        docker.image(cgConfig.craneImage()).inside('--entrypoint=') {
            withEnv(["REGISTRY=${registry}", "HOME=${pwd()}/.crane-home"]) {
                sh 'mkdir -p "$HOME" && echo "$REG_PASS" | crane auth login "$REGISTRY" -u "$REG_USER" --password-stdin --insecure'
                cgConfig.allComponents().each { component ->
                    String src = cgConfig.imageRef(component, snapshot)
                    String dst = cgConfig.imageRef(component, release)
                    withEnv(["SRC=${src}", "DST=${dst}"]) {
                        sh 'crane copy --insecure "$SRC" "$DST"'
                        String srcDigest = sh(script: 'crane digest --insecure "$SRC"', returnStdout: true).trim()
                        String dstDigest = sh(script: 'crane digest --insecure "$DST"', returnStdout: true).trim()
                        if (srcDigest != dstDigest) {
                            error "Promoción inválida de ${component}: ${srcDigest} (snapshot) != ${dstDigest} (release)."
                        }
                        lines << "${cgConfig.imageName(component)}: ${snapshot} -> ${release} (${srcDigest})"
                    }
                }
                sh 'rm -rf "$HOME"'
            }
        }
    }
    writeFile file: 'promotion.txt', text: lines.join('\n') + '\n'
    archiveArtifacts artifacts: 'promotion.txt'
    echo "Promovidas ${lines.size()} imágenes con el mismo digest:\n${lines.join('\n')}"
}
