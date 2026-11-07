// Pruebas unitarias + cobertura JaCoCo (CG-52).
//
// - Si una prueba falla, gradlew termina con código distinto de 0 y el build
//   queda en rojo. Con --continue se ejecutan las pruebas de todos los
//   servicios antes de fallar, para ver todos los errores en un solo build.
// - Los reportes se publican siempre (también cuando hay fallos).
// - En los servicios núcleo la cobertura < 60 % marca el build como UNSTABLE
//   (amarillo). El bloqueo duro lo hace el quality gate de SonarQube.

void call(List<String> services) {
    if (!services) {
        echo 'No hay servicios backend que probar.'
        return
    }

    String tasks = services.collect { ":services:${it}:test :services:${it}:jacocoTestReport" }.join(' ')
    try {
        sh "./gradlew ${tasks} --continue --no-daemon"
    } finally {
        junit testResults: services.collect { "services/${it}/build/test-results/test/*.xml" }.join(','),
              allowEmptyResults: true

        List<String> core = cgConfig.coreServices()
        services.each { svc ->
            Map options = [
                tools: [[parser: 'JACOCO', pattern: "services/${svc}/build/reports/jacoco/test/jacocoTestReport.xml"]],
                id: "coverage-${svc}",
                name: "Cobertura ${svc.replace('circleguard-', '')}",
                sourceDirectories: [[path: "services/${svc}/src/main/java"]],
                skipPublishingChecks: true,
            ]
            if (svc in core) {
                options.qualityGates = [[threshold: 60.0, metric: 'LINE', baseline: 'PROJECT', criticality: 'UNSTABLE']]
            }
            recordCoverage(options)
        }
    }
}
