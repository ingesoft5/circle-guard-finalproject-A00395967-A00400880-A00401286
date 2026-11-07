// Pruebas E2E con Playwright contra un ambiente desplegado (CG-57).
//
// - Corre dentro de la imagen oficial de Playwright. Su versión se lee del
//   package.json, porque la imagen y @playwright/test deben coincidir.
// - Publica el reporte JUnit y el reporte HTML de Playwright en el build,
//   también cuando las pruebas fallan.
// - Si fallan, el pipeline se detiene: stage NO recibe la versión.
//
// Uso: cgE2E('http://10.0.1.30:80')

void call(String baseUrl) {
    dir('tests/e2e-playwright') {
        String version = playwrightVersion()
        echo "E2E contra ${baseUrl} con Playwright ${version}"
        try {
            docker.image("mcr.microsoft.com/playwright:v${version}-jammy").inside('--ipc=host') {
                withEnv(["BASE_URL=${baseUrl}", 'CI=true',
                         "HOME=${pwd()}", "npm_config_cache=${pwd()}/.npm"]) {
                    sh 'npm ci --no-audit --no-fund'
                    sh 'npx playwright test'
                }
            }
        } finally {
            junit testResults: 'results/junit.xml', allowEmptyResults: true
            publishHTML(target: [
                reportDir: 'playwright-report',
                reportFiles: 'index.html',
                reportName: 'Playwright',
                keepAll: true,
                alwaysLinkToLastBuild: true,
                allowMissing: true,
            ])
        }
    }
}

/** Versión exacta de @playwright/test declarada en package.json. */
String playwrightVersion() {
    def pkg = readJSON file: 'package.json'
    String declared = pkg.devDependencies?.get('@playwright/test') ?: pkg.dependencies?.get('@playwright/test')
    if (!declared) {
        error 'tests/e2e-playwright/package.json no declara @playwright/test.'
    }
    return declared.replaceAll(/^[\^~=]/, '')
}
