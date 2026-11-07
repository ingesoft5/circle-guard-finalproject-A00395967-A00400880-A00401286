// Versionado semántico automático (CG-59).
//
// Lee los Conventional Commits desde el último tag con git-cliff
// (configuración en cliff.toml):
//   fix:  -> PATCH    feat: -> MINOR    feat!: o BREAKING CHANGE -> MAJOR
//
// Devuelve:
//   - 'v1.0.0' si el repositorio todavía no tiene tags (primer release).
//   - la siguiente versión (p. ej. 'v1.1.0') si hay commits publicables.
//   - '' si desde el último tag solo hubo commits que no generan release
//     (docs, chore, ci, test...). En ese caso el pipeline no publica ni
//     despliega: es solo un build de CI.

String call() {
    fetchTags()

    String last = sh(script: "git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true",
                     returnStdout: true).trim()
    if (!last) {
        echo 'El repositorio no tiene tags de release: primera versión v1.0.0.'
        return 'v1.0.0'
    }

    String next = sh(script: 'git cliff --bumped-version', returnStdout: true).trim()
    if (next && !next.startsWith('v')) {
        next = "v${next}"
    }
    if (!next || next == last) {
        echo "No hay commits que generen un release desde ${last} (solo docs/chore/ci/test)."
        return ''
    }
    echo "Última versión: ${last} -> nueva versión: ${next}"
    return next
}

/** Trae los tags de GitHub: el checkout del multibranch no los trae. */
void fetchTags() {
    withCredentials([usernamePassword(credentialsId: 'github-pat',
                                      usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        withEnv(["REPO_SLUG=${cgConfig.repoSlug()}"]) {
            sh '''
                git fetch --quiet --force "https://${GH_USER}:${GH_TOKEN}@github.com/${REPO_SLUG}.git" \
                    '+refs/tags/*:refs/tags/*'
            '''
        }
    }
}
