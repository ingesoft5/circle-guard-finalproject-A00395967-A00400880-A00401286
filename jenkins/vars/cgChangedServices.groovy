// Ejecución selectiva por servicio (CG-51).
//
// Devuelve los componentes afectados por el cambio: nombres de microservicios
// y/o 'mobile'.
//   - En un PR se compara contra la rama destino (CHANGE_TARGET).
//   - En una rama (p. ej. main) se compara contra el último build exitoso.
//   - Si cambia un archivo global (Gradle raíz, Jenkinsfile, la librería) se
//     construye todo, porque ese cambio puede afectar a cualquier servicio.
//   - Si no hay contra qué comparar (primer build) también se construye todo.
//   - El parámetro BUILD_ALL fuerza el build completo.

List<String> call(Map args = [:]) {
    List<String> all = cgConfig.allComponents()

    if (args.get('buildAll', false)) {
        echo 'BUILD_ALL=true: se construyen todos los componentes.'
        return all
    }

    String base = resolveBase()
    if (!base) {
        echo 'No hay una referencia de comparación (primer build): se construyen todos los componentes.'
        return all
    }

    String diff = sh(script: "git diff --name-only ${base} HEAD", returnStdout: true).trim()
    List<String> files = diff ? diff.split('\n').collect { it.trim() } : []
    echo "Archivos cambiados respecto a ${base.take(10)}:\n  ${files ? files.join('\n  ') : '(ninguno)'}"

    if (files.any { isGlobal(it) }) {
        echo 'Cambio en un archivo global: se construyen todos los componentes.'
        return all
    }

    List<String> changed = cgConfig.backendServices().findAll { svc ->
        files.any { it.startsWith("services/${svc}/") }
    }
    if (files.any { it.startsWith('mobile/') }) {
        changed << 'mobile'
    }
    return changed
}

/** Commit contra el que se compara, o null si no hay uno confiable. */
String resolveBase() {
    if (env.CHANGE_TARGET) {
        String target = "origin/${env.CHANGE_TARGET}"
        if (sh(script: "git rev-parse --verify --quiet ${target}", returnStatus: true) == 0) {
            return sh(script: "git merge-base ${target} HEAD", returnStdout: true).trim()
        }
        echo "No se encontró ${target} en el clon."
        return null
    }

    String previous = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
    if (previous && sh(script: "git cat-file -e ${previous}^{commit}", returnStatus: true) == 0) {
        return previous
    }
    return null
}

boolean isGlobal(String file) {
    return file in ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
                    'gradlew', 'gradlew.bat', 'Jenkinsfile'] ||
           file.startsWith('gradle/') ||
           file.startsWith('jenkins/')
}
