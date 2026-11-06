// Análisis SonarQube + quality gate BLOQUEANTE (CG-53).
//
// - Un proyecto de Sonar por servicio (circleguard-<servicio>): un quality gate
//   por servicio y reportes fáciles de leer.
// - withSonarQubeEnv('sonar') inyecta la URL y el token configurados en
//   Manage Jenkins → System → SonarQube servers (credencial 'sonar-token').
// - waitForQualityGate abortPipeline: true detiene el build si el gate falla,
//   así que no se empaqueta ni se publica nada. SonarQube avisa el resultado
//   a Jenkins por el webhook http://<jenkins>/sonarqube-webhook/; sin ese
//   webhook el paso espera hasta el timeout.

void call(String service) {
    withSonarQubeEnv('sonar') {
        // El plugin de Sonar vive en el proyecto raíz; -PsonarService hace que
        // solo se analice este servicio (ver build.gradle.kts).
        sh "./gradlew sonar -PsonarService=${service} " +
           "-Dsonar.projectKey=circleguard-${service} " +
           "-Dsonar.projectName=${service} " +
           "--no-daemon"
    }
    timeout(time: 10, unit: 'MINUTES') {
        def gate = waitForQualityGate abortPipeline: true
        echo "Quality gate de ${service}: ${gate.status}"
    }
}
