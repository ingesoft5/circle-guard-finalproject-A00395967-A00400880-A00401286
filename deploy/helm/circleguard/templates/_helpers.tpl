{{/* Etiquetas comunes. part-of y name las usan Prometheus (PodMonitor) y Loki. */}}
{{- define "cg.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: circleguard
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
circleguard.io/env: {{ .root.Values.global.env | quote }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end -}}

{{- define "cg.selector" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* Imagen propia: <registry>/circleguard/<nombre>:<tag>. Falla sin tag o con "latest". */}}
{{- define "cg.image" -}}
{{- $g := .root.Values.global -}}
{{- $reg := required "global.registry es obligatorio (p. ej. 4.255.240.15:8082)" $g.registry -}}
{{- $tag := required "global.imageTag es obligatorio (vX.Y.Z o vX.Y.Z-SNAPSHOT.N)" $g.imageTag -}}
{{- if eq $tag "latest" -}}{{- fail "global.imageTag no puede ser latest" -}}{{- end -}}
{{- if not (regexMatch "^v[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT\\.[0-9]+)?$" $tag) -}}
{{- fail (printf "global.imageTag %q no cumple vX.Y.Z[-SNAPSHOT.N]" $tag) -}}
{{- end -}}
{{- printf "%s/circleguard/%s:%s" $reg .name $tag -}}
{{- end -}}

{{/* initContainer que espera a que host:puerto acepte conexiones (equivale a depends_on). */}}
{{- define "cg.waitFor" -}}
{{- if .targets }}
initContainers:
  - name: wait-for-deps
    image: {{ .root.Values.waitImage }}
    command:
      - sh
      - -c
      - |
        for t in {{ join " " .targets }}; do
          until nc -z -w 2 "${t%:*}" "${t#*:}"; do echo "esperando $t"; sleep 3; done
        done
    securityContext:
      runAsNonRoot: true
      runAsUser: 65534
      {{- toYaml .root.Values.securityContext | nindent 6 }}
    resources:
      requests: { cpu: 10m, memory: 16Mi }
      limits: { cpu: 100m, memory: 32Mi }
{{- end }}
{{- end -}}
