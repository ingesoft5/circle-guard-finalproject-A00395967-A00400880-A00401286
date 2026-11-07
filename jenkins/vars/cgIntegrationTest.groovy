// Pruebas de integración entre servicios (CG-54).
//
// Corre la tarea Gradle 'integrationTest' (pruebas con @Tag("integration"),
// que usan Testcontainers). Se ejecutan todas, no solo las del servicio
// cambiado: una prueba de integración cruza servicios (p. ej. auth -> gateway),
// así que un cambio en auth puede romper una prueba que vive en gateway.
//
// Testcontainers necesita Docker en el agente. Si falla con
// "Could not find a valid Docker environment", el agente no ve el socket.

void call() {
    try {
        sh './gradlew integrationTest --continue --no-daemon'
    } finally {
        junit testResults: 'services/*/build/test-results/integrationTest/*.xml', allowEmptyResults: true
    }
}
