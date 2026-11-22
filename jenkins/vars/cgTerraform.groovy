// Terraform gobernado por Jenkins (CG-89).
//
// Cada ambiente tiene su raíz de Terraform en <CG_TF_DIR>/<ambiente>
// (por defecto infra/terraform/envs/dev, stage y prod; el código es de Darío).
//
// - validate(): fmt -check + validate de todos los ambientes, SIN credenciales
//   ni backend (-backend=false). Corre en cualquier rama, también en PRs.
// - plan(env): init + plan con el plan guardado en un archivo. Devuelve true si
//   hay cambios (plan -detailed-exitcode = 2). El plan legible queda como
//   artefacto del build para que lo revise quien aprueba.
// - apply(env): aplica EXACTAMENTE el plan guardado (no vuelve a planear), así
//   lo que se aprueba es lo que se ejecuta.
//
// Credenciales de Azure (service principal) desde Jenkins, nunca del repo:
//   azure-sp (usuario = appId, contraseña = secret), azure-tenant-id y
//   azure-subscription-id (secret text). Terraform las lee de ARM_*.

String envDir(String envName) {
    cgConfig.requireEnv(envName)
    return "${cgConfig.terraformDir()}/${envName}"
}

/** true si el repo tiene Terraform para ese ambiente. */
boolean present(String envName) {
    return fileExists(envDir(envName))
}

void validate() {
    List<String> envs = ['dev', 'stage', 'prod'].findAll { present(it) }
    if (!envs) {
        echo "Terraform: no hay ${cgConfig.terraformDir()} en este commit; nada que validar."
        return
    }
    docker.image(cgConfig.terraformImage()).inside('--entrypoint=') {
        withEnv(["TF_ROOT=${cgConfig.terraformDir()}", 'TF_IN_AUTOMATION=1', "HOME=${pwd()}/.tf-home"]) {
            sh 'terraform fmt -check -recursive -diff "$(dirname "$TF_ROOT")"'
            envs.each { envName ->
                dir(envDir(envName)) {
                    sh 'terraform init -input=false -backend=false && terraform validate'
                }
            }
        }
    }
}

/** init + plan. Devuelve true si el plan tiene cambios. */
boolean plan(String envName) {
    int code = 0
    withTerraform(envName) {
        code = sh(returnStatus: true, script: '''
            terraform init -input=false
            terraform plan -input=false -lock-timeout=5m -out=tfplan -detailed-exitcode
        ''')
        if (code == 0 || code == 2) {
            sh 'terraform show -no-color tfplan > "tfplan-$TF_ENV.txt"'
        }
    }
    if (code == 1) {
        error "terraform plan falló en ${envName}."
    }
    String tfdir = envDir(envName)
    archiveArtifacts artifacts: "${tfdir}/tfplan-${envName}.txt"
    // El plan binario se guarda en el build para aplicarlo después en otro nodo.
    stash name: "tfplan-${envName}", includes: "${tfdir}/tfplan"
    echo code == 2 ? "Terraform ${envName}: el plan tiene cambios (ver tfplan-${envName}.txt)." : "Terraform ${envName}: sin cambios."
    return code == 2
}

/** Aplica el plan guardado por plan(envName). */
void apply(String envName) {
    unstash "tfplan-${envName}"
    withTerraform(envName) {
        sh '''
            terraform init -input=false
            terraform apply -input=false -lock-timeout=5m tfplan
        '''
    }
}

void withTerraform(String envName, Closure body) {
    withCredentials([
        usernamePassword(credentialsId: 'azure-sp', usernameVariable: 'ARM_CLIENT_ID', passwordVariable: 'ARM_CLIENT_SECRET'),
        string(credentialsId: 'azure-tenant-id', variable: 'ARM_TENANT_ID'),
        string(credentialsId: 'azure-subscription-id', variable: 'ARM_SUBSCRIPTION_ID'),
    ]) {
        docker.image(cgConfig.terraformImage()).inside('--entrypoint=') {
            withEnv(["TF_ENV=${envName}", 'TF_IN_AUTOMATION=1', "HOME=${pwd()}/.tf-home"]) {
                dir(envDir(envName)) {
                    body()
                }
            }
        }
    }
}
