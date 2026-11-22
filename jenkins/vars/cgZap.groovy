// OWASP ZAP baseline contra el cliente web de un ambiente (CG-92).
//
// - Baseline = spider + escaneo pasivo: no ataca la aplicación, solo revisa lo
//   que responde (encabezados, cookies, fugas de información, librerías JS).
// - Las reglas están versionadas en tests/security-zap/zap-baseline.conf:
//   FAIL bloquea (la versión no pasa a stage), WARN solo queda en el reporte e
//   IGNORE no aplica. Cambiar un nivel es un cambio revisable en un PR.
// - Corre en la imagen oficial de ZAP con versión fija. zap-baseline.py exige
//   la carpeta /zap/wrk para leer las reglas y escribir los reportes; se enlaza
//   a una carpeta del workspace (el usuario de Jenkins y el de ZAP son uid 1000).
// - Los reportes (HTML y JSON) quedan como artefactos del build.
//
// Uso: cgZap('http://dev.circleguard.example')

void call(String targetUrl) {
    String dir = 'zap-reports'
    sh "mkdir -p ${dir} && cp tests/security-zap/zap-baseline.conf ${dir}/"
    int status = 0
    docker.image(cgConfig.zapImage()).inside('--entrypoint=') {
        withEnv(["TARGET=${targetUrl}", "WRK=${pwd()}/${dir}", 'HOME=/home/zap']) {
            // -I: los WARN no cambian el código de salida; solo FAIL devuelve 1.
            status = sh(returnStatus: true, script: '''
                ln -sfn "$WRK" /zap/wrk
                zap-baseline.py -t "$TARGET" -c zap-baseline.conf \
                    -r zap-baseline.html -J zap-baseline.json -I
            ''')
        }
    }
    archiveArtifacts artifacts: "${dir}/zap-baseline.*", allowEmptyArchive: true
    if (status == 1) {
        error "ZAP encontró alertas de nivel FAIL en ${targetUrl}: la versión no se promueve. Ver zap-reports/zap-baseline.html"
    }
    if (status != 0) {
        error "ZAP no pudo completar el escaneo de ${targetUrl} (código ${status})."
    }
    echo "ZAP baseline sin alertas FAIL en ${targetUrl} (los WARN están en el reporte)."
}
