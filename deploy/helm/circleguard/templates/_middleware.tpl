{{/*
  StatefulSet + Service de un componente de middleware.
  Entrada: dict root, name, cfg (values.middleware.<x>), ports (lista name/port),
  env (lista YAML), volumes (lista name/path), y opcionales command, probe, extraVolumes.
*/}}
{{- define "cg.middleware" -}}
{{- $root := .root }}
{{- $labels := dict "root" $root "name" .name "component" "middleware" }}
apiVersion: v1
kind: Service
metadata:
  name: {{ .name }}
  labels:
    {{- include "cg.labels" $labels | nindent 4 }}
spec:
  selector:
    {{- include "cg.selector" $labels | nindent 4 }}
  ports:
    {{- range .ports }}
    - name: {{ .name }}
      port: {{ .port }}
      targetPort: {{ .name }}
    {{- end }}
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .name }}
  labels:
    {{- include "cg.labels" $labels | nindent 4 }}
spec:
  serviceName: {{ .name }}
  replicas: 1
  selector:
    matchLabels:
      {{- include "cg.selector" $labels | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "cg.labels" $labels | nindent 8 }}
    spec:
      enableServiceLinks: false
      automountServiceAccountToken: false
      # Las imágenes oficiales arrancan como root y bajan de usuario (gosu): cumplen
      # Pod Security "baseline"; seccomp por defecto como capa adicional.
      securityContext:
        seccompProfile: { type: RuntimeDefault }
      {{- with .initContainers }}
      initContainers:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ .name }}
          image: {{ .cfg.image }}
          imagePullPolicy: IfNotPresent
          {{- with .command }}
          command:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            {{- range .ports }}
            - name: {{ .name }}
              containerPort: {{ .port }}
            {{- end }}
          {{- with .env }}
          env:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- with .probe }}
          readinessProbe:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          resources:
            {{- toYaml .cfg.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
          volumeMounts:
            {{- range .volumes }}
            - name: {{ .name }}
              mountPath: {{ .path }}
              {{- with .subPath }}
              subPath: {{ . }}
              {{- end }}
            {{- end }}
            {{- with .extraMounts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
      {{- with .extraVolumes }}
      volumes:
        {{- toYaml . | nindent 8 }}
      {{- end }}
  # PVC por volumen (local-path de k3s). Se conservan si se borra el StatefulSet.
  persistentVolumeClaimRetentionPolicy:
    whenDeleted: Retain
    whenScaled: Retain
  volumeClaimTemplates:
    {{- range .claims }}
    - metadata:
        name: {{ . }}
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: {{ $.cfg.storage }}
    {{- end }}
{{- end -}}
