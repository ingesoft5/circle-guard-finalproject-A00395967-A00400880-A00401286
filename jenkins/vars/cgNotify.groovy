// Notificaciones a Discord (CG-60).
//
// La URL del webhook está en la credencial 'discord-webhook' (Secret text).
// El JSON se arma con JsonOutput y se envía desde un archivo, para no tener
// problemas de comillas ni de caracteres especiales en el mensaje.
//
// Debe llamarse dentro de un nodo (necesita 'sh'). Una falla al notificar
// nunca cambia el resultado del build: solo deja una advertencia en el log.
//
// Uso: cgNotify('failure' | 'unstable' | 'aborted' | 'released' | 'rollback')

void call(String status) {
    Map styles = [
        failure : [icon: '❌', title: 'Falló el pipeline'],
        unstable: [icon: '⚠️', title: 'Build inestable (pruebas o cobertura)'],
        aborted : [icon: '⛔', title: 'Pipeline abortado o despliegue rechazado'],
        released: [icon: '🚀', title: 'Nueva versión en producción'],
        rollback: [icon: '↩️', title: 'Rollback ejecutado'],
    ]
    Map style = styles.get(status, [icon: 'ℹ️', title: status])

    List<String> lines = [
        "${style.icon} **CircleGuard: ${style.title}**",
        "**Job:** ${env.JOB_NAME} #${env.BUILD_NUMBER}",
    ]
    if (env.BRANCH_NAME) lines << "**Rama:** ${env.BRANCH_NAME}"
    if (env.VERSION)     lines << "**Versión:** ${env.VERSION}"
    if (env.NOTIFY_DETAIL) lines << env.NOTIFY_DETAIL
    lines << env.BUILD_URL

    String payload = groovy.json.JsonOutput.toJson([username: 'CircleGuard Jenkins', content: lines.join('\n')])

    try {
        writeFile file: 'discord-payload.json', text: payload, encoding: 'UTF-8'
        withCredentials([string(credentialsId: 'discord-webhook', variable: 'DISCORD_WEBHOOK')]) {
            sh '''
                curl -fsS -H "Content-Type: application/json" \
                    -X POST "$DISCORD_WEBHOOK" --data-binary @discord-payload.json
            '''
        }
    } catch (err) {
        echo "ADVERTENCIA: no se pudo enviar la notificación a Discord: ${err}"
    }
}
