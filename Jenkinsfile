// Pipeline CI/CD de CircleGuard (Parte 1).
//
// - En un PR o en cualquier rama: solo CI (validar). Es el check obligatorio
//   que bloquea el merge en GitHub.
// - En main: CI + CD. Se publica una SNAPSHOT (vX.Y.Z-SNAPSHOT.N) escaneada
//   con Trivy, que recorre dev -> E2E -> stage. Solo si una persona aprueba,
//   esa MISMA imagen se promueve a vX.Y.Z (sin reconstruir), se crean el tag y
//   el release y se despliega a prod (CG-116).
//
// Las etapas son llamadas a la Shared Library (jenkins/vars, librería
// 'circleguard-lib'); este archivo solo describe el flujo.
// La infraestructura (IPs, registro) se lee de las variables globales de
// Jenkins: ver jenkins/README.md.

// La librería se carga de la MISMA rama que se construye: en un PR, su rama de
// origen; en main, main. Así un PR que agrega o cambia un paso de jenkins/vars
// se prueba con su propia versión (antes fallaba con "No such property"
// porque la librería venía siempre de main). Solo se descubren PRs del propio
// repositorio (no forks), así que el código cargado es del equipo.
library "circleguard-lib@${env.CHANGE_BRANCH ?: env.BRANCH_NAME}"

pipeline {
    // Sin agente global: cada etapa pide el suyo. Así la espera de la
    // aprobación a producción NO ocupa un ejecutor.
    agent none

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        skipDefaultCheckout()
    }

    parameters {
        booleanParam(name: 'BUILD_ALL', defaultValue: false,
                     description: 'Construir y probar todos los servicios, no solo los modificados')
    }

    stages {
        stage('CI y entrega') {
            agent any
            stages {
                stage('Checkout') {
                    steps {
                        script {
                            def scmVars = checkout scm
                            env.GIT_COMMIT = scmVars.GIT_COMMIT
                            env.GIT_PREVIOUS_SUCCESSFUL_COMMIT = scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''
                        }
                    }
                }

                stage('Detectar cambios') {
                    steps {
                        script {
                            List<String> changed = cgChangedServices(buildAll: params.BUILD_ALL)
                            env.SERVICES = changed.findAll { it != 'mobile' }.join(',')
                            env.WEB_CHANGED = changed.contains('mobile').toString()
                            echo "Servicios backend: ${env.SERVICES ?: '(ninguno)'} | Cliente web: ${env.WEB_CHANGED}"
                            currentBuild.description = changed ? changed.collect { cgConfig.imageName(it) }.join(', ') : 'sin cambios de código'
                        }
                    }
                }

                stage('Compilar') {
                    when { expression { env.SERVICES } }
                    steps { script { cgBuild(services()) } }
                }

                stage('Pruebas unitarias + JaCoCo') {
                    when { expression { env.SERVICES } }
                    steps { script { cgUnitTest(services()) } }
                }

                stage('Cliente web (build)') {
                    when { expression { env.WEB_CHANGED == 'true' } }
                    steps { cgWebCheck() }
                }

                stage('SonarQube + Quality Gate') {
                    when { expression { env.SERVICES } }
                    steps { script { services().each { cgSonar(it) } } }
                }

                stage('Pruebas de integración') {
                    when { expression { env.SERVICES } }
                    steps { cgIntegrationTest() }
                }

                // Trivy sobre la infraestructura como código (CG-90). Bloquea en
                // cualquier rama: un PR con IaC insegura no se puede fusionar.
                stage('Trivy IaC') {
                    steps { script { cgTrivy.scanConfig(['infra/terraform', 'deploy/helm']) } }
                }

                // ----------------------- Solo en main (CD) -----------------------

                stage('Versión') {
                    when { branch 'main' }
                    steps {
                        script {
                            env.VERSION = cgVersion()
                            if (env.VERSION) {
                                // Candidata a release: lo que se prueba en dev y stage.
                                env.SNAPSHOT = cgConfig.snapshotOf(env.VERSION, env.BUILD_NUMBER)
                                currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.SNAPSHOT}"
                            } else {
                                echo 'Sin cambios publicables: este build de main termina después de CI.'
                            }
                        }
                    }
                }

                stage('Empaquetar, escanear y publicar') {
                    when { expression { isRelease() } }
                    steps {
                        // En main se publican TODAS las imágenes con la SNAPSHOT: una
                        // versión = un release completo del sistema. Trivy escanea cada
                        // una antes del push (CG-90).
                        script { cgDockerPublish(cgConfig.allComponents(), env.SNAPSHOT) }
                    }
                }

                stage('Deploy DEV') {
                    when { expression { isRelease() } }
                    steps { cgDeploy(env: 'dev', version: env.SNAPSHOT) }
                }

                stage('E2E sobre DEV') {
                    when { expression { isRelease() } }
                    // URL del web en dev: el Ingress del clúster (CG_DEV_URL) o la VM.
                    steps { script { cgE2E(cgConfig.webUrl('dev')) } }
                }

                // CG-92: escaneo pasivo de seguridad del web en dev. Una alerta FAIL
                // (tests/security-zap/zap-baseline.conf) frena la promoción.
                stage('OWASP ZAP sobre DEV') {
                    when { expression { isRelease() } }
                    steps { script { cgZap(cgConfig.webUrl('dev')) } }
                }

                stage('Deploy STAGE') {
                    when { expression { isRelease() } }
                    // Misma imagen y misma SNAPSHOT que en dev (build once, deploy many).
                    steps { cgDeploy(env: 'stage', version: env.SNAPSHOT) }
                }
            }
            post {
                always { cleanWs() }
            }
        }

        stage('Aprobación PROD') {
            when { expression { isRelease() } }
            // Sin agente: nadie ocupa un ejecutor mientras una persona decide.
            steps {
                timeout(time: 2, unit: 'HOURS') {
                    script {
                        env.PROD_APPROVER = input(
                            message: "¿Promover ${env.SNAPSHOT} como ${env.VERSION} y desplegar a PRODUCCIÓN?",
                            ok: 'Promover y desplegar',
                            submitter: cgConfig.prodApprovers(),
                            submitterParameter: 'APPROVER'
                        )
                        echo "Despliegue a producción aprobado por: ${env.PROD_APPROVER}"
                    }
                }
            }
        }

        stage('Producción') {
            // beforeAgent: evaluar el when ANTES de pedir un ejecutor. Sin esto,
            // cada PR esperaba un ejecutor libre solo para saltarse esta etapa.
            when {
                beforeAgent true
                expression { isRelease() }
            }
            agent any
            stages {
                // CG-116: la SNAPSHOT aprobada pasa a ser el release, mismo digest.
                stage('Promover a release') {
                    steps {
                        checkout scm
                        cgPromote(release: env.VERSION, snapshot: env.SNAPSHOT)
                        script { currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.VERSION}" }
                    }
                }

                // El tag y el release se crean solo después de aprobar y promover.
                stage('Tag + release notes') {
                    steps { cgRelease(env.VERSION) }
                }

                stage('Deploy PROD') {
                    steps {
                        cgDeploy(env: 'prod', version: env.VERSION)
                        script {
                            env.PROD_DEPLOYED = 'true'
                            env.NOTIFY_DETAIL = "**Aprobado por:** ${env.PROD_APPROVER}"
                        }
                    }
                }
            }
            post {
                always { cleanWs() }
            }
        }
    }

    post {
        failure  { notify('failure') }
        unstable { notify('unstable') }
        aborted  { notify('aborted') }
        success {
            script {
                if (env.PROD_DEPLOYED == 'true') {
                    notify('released')
                }
            }
        }
    }
}

/** Servicios backend detectados, como lista. */
List<String> services() {
    // tokenize (y no split + 'as List') para no requerir aprobación de script en el sandbox.
    return env.SERVICES ? env.SERVICES.tokenize(',') : []
}

/** true si este build de main produce un release (hay una versión nueva). */
boolean isRelease() {
    return env.BRANCH_NAME == 'main' && env.VERSION
}

/** Envía la notificación desde un nodo (el post global no tiene agente). */
void notify(String status) {
    node {
        cgNotify(status)
    }
}
