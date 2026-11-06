// Pipeline CI/CD de CircleGuard (Parte 1).
//
// - En un PR o en cualquier rama: solo CI (validar). Es el check obligatorio
//   que bloquea el merge en GitHub.
// - En main: CI + CD (versionar, publicar, desplegar dev -> E2E -> stage ->
//   aprobación manual -> prod).
//
// Las etapas son llamadas a la Shared Library (jenkins/vars, librería
// 'circleguard-lib'); este archivo solo describe el flujo.
// La infraestructura (IPs, registro) se lee de las variables globales de
// Jenkins: ver jenkins/README.md.

@Library('circleguard-lib') _

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

                // ----------------------- Solo en main (CD) -----------------------

                stage('Versión') {
                    when { branch 'main' }
                    steps {
                        script {
                            env.VERSION = cgVersion()
                            if (env.VERSION) {
                                currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.VERSION}"
                            } else {
                                echo 'Sin cambios publicables: este build de main termina después de CI.'
                            }
                        }
                    }
                }

                stage('Empaquetar y publicar') {
                    when { expression { isRelease() } }
                    steps {
                        // En main se publican TODAS las imágenes con la nueva versión:
                        // una versión = un release completo del sistema, y el compose de
                        // cada ambiente usa la misma VERSION para todos los servicios.
                        script { cgDockerPublish(cgConfig.allComponents(), env.VERSION) }
                    }
                }

                stage('Tag + release notes') {
                    when { expression { isRelease() } }
                    steps { cgRelease(env.VERSION) }
                }

                stage('Deploy DEV') {
                    when { expression { isRelease() } }
                    steps { cgDeploy(env: 'dev', version: env.VERSION) }
                }

                stage('E2E sobre DEV') {
                    when { expression { isRelease() } }
                    steps { script { cgE2E("http://${cgConfig.host('dev')}:${cgConfig.webPort()}") } }
                }

                stage('Deploy STAGE') {
                    when { expression { isRelease() } }
                    // Misma imagen y misma versión que en dev (build once, deploy many).
                    steps { cgDeploy(env: 'stage', version: env.VERSION) }
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
                            message: "¿Desplegar ${env.VERSION} a PRODUCCIÓN?",
                            ok: 'Desplegar',
                            submitter: cgConfig.prodApprovers(),
                            submitterParameter: 'APPROVER'
                        )
                        echo "Despliegue a producción aprobado por: ${env.PROD_APPROVER}"
                    }
                }
            }
        }

        stage('Deploy PROD') {
            when { expression { isRelease() } }
            agent any
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
    return env.SERVICES ? env.SERVICES.split(',') as List : []
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
