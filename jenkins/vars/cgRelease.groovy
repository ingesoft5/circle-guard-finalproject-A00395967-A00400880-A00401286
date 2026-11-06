// Tag de Git + release notes (CG-59, release notes de E1.7).
//
// Se ejecuta DESPUÉS de publicar las imágenes: si la publicación falla, no
// queda un tag que apunte a una versión que no existe en el registro.
//
// 1. Crea el tag anotado vX.Y.Z y lo empuja a GitHub.
// 2. Genera RELEASE_NOTES.md con git-cliff (commits desde el tag anterior).
// 3. Crea el GitHub Release con esas notas y archiva el archivo en el build.
//
// El push del tag NO dispara otro build porque el job multibranch no descubre
// tags (así se evita un bucle).

void call(String version) {
    cgConfig.requireSemver(version)

    withCredentials([usernamePassword(credentialsId: 'github-pat',
                                      usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        withEnv(["RELEASE_VERSION=${version}", "REPO_SLUG=${cgConfig.repoSlug()}"]) {
            sh '''
                git -c user.name="CircleGuard Jenkins" -c user.email="jenkins@circleguard.local" \
                    tag -a "$RELEASE_VERSION" -m "release $RELEASE_VERSION"

                git cliff --latest --strip header --output RELEASE_NOTES.md

                git push --quiet "https://${GH_USER}:${GH_TOKEN}@github.com/${REPO_SLUG}.git" \
                    "refs/tags/${RELEASE_VERSION}"

                jq -n --arg tag "$RELEASE_VERSION" --rawfile body RELEASE_NOTES.md \
                    '{tag_name: $tag, name: $tag, body: $body}' > github-release.json
                curl -fsS -X POST \
                    -H "Authorization: Bearer ${GH_TOKEN}" \
                    -H "Accept: application/vnd.github+json" \
                    "https://api.github.com/repos/${REPO_SLUG}/releases" \
                    -d @github-release.json > /dev/null
            '''
        }
    }
    archiveArtifacts artifacts: 'RELEASE_NOTES.md', fingerprint: true
    echo "Tag ${version} creado y GitHub Release publicado."
}
