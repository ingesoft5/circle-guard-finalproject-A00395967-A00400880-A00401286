// Escaneo de seguridad con Trivy (CG-90).
//
// - scanImage: escanea una imagen recién construida, ANTES de publicarla. Si
//   tiene vulnerabilidades del umbral (cgConfig.trivySeverity(), por defecto
//   CRITICAL) con arreglo disponible, el build falla y la imagen no llega a
//   Nexus.
// - scanConfig: escanea la infraestructura como código (Terraform y charts de
//   Helm) con las mismas reglas de severidad.
// - Los reportes (tabla legible + JSON) quedan como artefactos del build.
// - --ignore-unfixed: una CVE sin versión corregida no se puede arreglar
//   actualizando; bloquear por ella dejaría el pipeline rojo sin salida. Esas
//   aparecen igual en el reporte. Las excepciones aceptadas van en .trivyignore
//   con su justificación.
// - Trivy corre en su contenedor oficial con versión fija; usa el socket de
//   Docker del host para leer la imagen local sin exportarla.

/** Escanea una imagen local. Devuelve normalmente o detiene el build. */
void scanImage(String image, String reportName) {
    String reports = 'trivy-reports'
    sh "mkdir -p ${reports} .trivy-cache"
    String gid = sh(script: 'stat -c %g /var/run/docker.sock', returnStdout: true).trim()
    int status = 0
    docker.image(cgConfig.trivyImage()).inside("--entrypoint= -v /var/run/docker.sock:/var/run/docker.sock --group-add ${gid}") {
        withEnv(["TRIVY_IMAGE_REF=${image}", "REPORT=${reports}/${reportName}",
                 "SEVERITY=${cgConfig.trivySeverity()}", 'TRIVY_CACHE_DIR=.trivy-cache']) {
            // Reporte completo (todas las severidades) para el informe; no bloquea.
            sh '''
                trivy image --quiet --scanners vuln --format json --output "$REPORT.json" "$TRIVY_IMAGE_REF"
                trivy image --quiet --scanners vuln --format table --output "$REPORT.txt" "$TRIVY_IMAGE_REF"
            '''
            // Puerta: solo el umbral y solo lo que tiene arreglo.
            status = sh(returnStatus: true, script: '''
                trivy image --quiet --skip-db-update --scanners vuln --severity "$SEVERITY" --ignore-unfixed \
                    --ignorefile .trivyignore --exit-code 1 --format table "$TRIVY_IMAGE_REF"
            ''')
        }
    }
    if (status != 0) {
        archiveReports()
        error "Trivy bloqueó ${image}: tiene vulnerabilidades ${cgConfig.trivySeverity()} con arreglo disponible. Reporte en trivy-reports/${reportName}.txt"
    }
}

/** Escanea las carpetas de IaC que existan (Terraform, Helm). */
void scanConfig(List<String> paths) {
    List<String> present = paths.findAll { fileExists(it) }
    if (!present) {
        echo "Trivy IaC: no hay ${paths.join(' ni ')} en este commit; nada que escanear."
        return
    }
    String reports = 'trivy-reports'
    sh "mkdir -p ${reports} .trivy-cache"
    int status = 0
    docker.image(cgConfig.trivyImage()).inside('--entrypoint=') {
        present.each { path ->
            String name = 'iac-' + path.replaceAll('[^A-Za-z0-9]+', '-')
            withEnv(["SCAN_PATH=${path}", "REPORT=${reports}/${name}",
                     "SEVERITY=${cgConfig.trivySeverity()}", 'TRIVY_CACHE_DIR=.trivy-cache']) {
                sh 'trivy config --quiet --format table --output "$REPORT.txt" "$SCAN_PATH"'
                status += sh(returnStatus: true, script: '''
                    trivy config --quiet --severity "$SEVERITY" --ignorefile .trivyignore --exit-code 1 "$SCAN_PATH"
                ''')
            }
        }
    }
    archiveReports()
    if (status != 0) {
        error "Trivy bloqueó la infraestructura como código: hay hallazgos ${cgConfig.trivySeverity()}. Ver trivy-reports/iac-*.txt"
    }
}

void archiveReports() {
    archiveArtifacts artifacts: 'trivy-reports/**', allowEmptyArchive: true
}
