// Compilación Gradle de los servicios indicados (código principal y de pruebas).
// Una sola invocación de Gradle para todos: es mucho más rápido que arrancar
// Gradle una vez por servicio.

void call(List<String> services) {
    if (!services) {
        echo 'No hay servicios backend que compilar.'
        return
    }
    String tasks = services.collect { ":services:${it}:classes :services:${it}:testClasses" }.join(' ')
    sh "./gradlew ${tasks} --no-daemon"
}
