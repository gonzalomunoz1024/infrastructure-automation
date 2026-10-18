{{/* The release's name for the platform's objects, at most 63 characters. */}}
{{- define "platform.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "platform.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: {{ include "platform.fullname" . }}
{{- end -}}

{{/* Selector labels of one component: (dict "ctx" $ "component" "api") */}}
{{- define "platform.selector" -}}
app.kubernetes.io/name: {{ .ctx.Chart.Name }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "platform.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "platform.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* An image reference: (dict "ctx" $ "image" .Values.backend.image); image.registry prefixes a bare repository name. */}}
{{- define "platform.image" -}}
{{- $repo := .image.repository -}}
{{- if and .ctx.Values.image.registry (not (contains "/" $repo)) -}}
{{- $repo = printf "%s/%s" (trimSuffix "/" .ctx.Values.image.registry) $repo -}}
{{- end -}}
{{- printf "%s:%s" $repo (toString (default .ctx.Chart.AppVersion .image.tag)) -}}
{{- end -}}

{{/* SPRING_PROFILES_ACTIVE */}}
{{- define "platform.profiles" -}}
{{- $profiles := .Values.backend.profiles | default list -}}
{{- if and .Values.postgres.enabled (not (has "postgres" $profiles)) -}}
{{- $profiles = append $profiles "postgres" -}}
{{- end -}}
{{- join "," $profiles -}}
{{- end -}}

{{/* The pod spec of a backend instance: (dict "ctx" $ "roles" "api" "component" "api" "settings" .Values.api) */}}
{{- define "platform.backendPod" -}}
{{- $ctx := .ctx -}}
{{- $v := $ctx.Values -}}
metadata:
  labels:
    {{- include "platform.labels" $ctx | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
  annotations:
    checksum/config: {{ include (print $ctx.Template.BasePath "/configmap.yaml") $ctx | sha256sum }}
    {{- with $v.backend.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- if $v.vaultAgent.enabled }}
    vault.hashicorp.com/agent-inject: "true"
    vault.hashicorp.com/role: {{ $v.vaultAgent.role | quote }}
    {{- with $v.vaultAgent.annotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- end }}
spec:
  serviceAccountName: {{ include "platform.serviceAccountName" $ctx }}
  {{- with $v.imagePullSecrets }}
  imagePullSecrets:
    {{- range . }}
    - name: {{ . }}
    {{- end }}
  {{- end }}
  terminationGracePeriodSeconds: {{ .settings.terminationGracePeriodSeconds }}
  securityContext:
    {{- toYaml (merge (deepCopy $v.podSecurityContext) (dict "runAsUser" 10001 "runAsGroup" 10001 "fsGroup" 10001)) | nindent 4 }}
  {{- if $v.content.repository }}
  initContainers:
    - name: content
      image: {{ include "platform.image" (dict "ctx" $ctx "image" $v.content.gitImage) }}
      imagePullPolicy: {{ $v.image.pullPolicy }}
      securityContext:
        {{- toYaml $v.containerSecurityContext | nindent 8 }}
      env:
        - name: HOME
          value: /tmp
        - name: CONTENT_REPOSITORY
          value: {{ $v.content.repository | quote }}
        - name: CONTENT_REF
          value: {{ $v.content.ref | quote }}
        {{- if $v.content.credentialsSecret.name }}
        - name: GIT_USERNAME
          valueFrom:
            secretKeyRef: { name: {{ $v.content.credentialsSecret.name | quote }}, key: {{ $v.content.credentialsSecret.usernameKey | quote }} }
        - name: GIT_PASSWORD
          valueFrom:
            secretKeyRef: { name: {{ $v.content.credentialsSecret.name | quote }}, key: {{ $v.content.credentialsSecret.passwordKey | quote }} }
        {{- end }}
      command: ["/bin/sh", "-ec"]
      args:
        - |
          if [ -n "${GIT_PASSWORD:-}" ]; then
            git config --global credential.helper '!f() { echo "username=${GIT_USERNAME}"; echo "password=${GIT_PASSWORD}"; }; f'
          fi
          git clone --quiet --depth 1 --branch "$CONTENT_REF" "$CONTENT_REPOSITORY" /content/repo
      volumeMounts:
        - { name: content, mountPath: /content }
        - { name: tmp, mountPath: /tmp }
  {{- end }}
  containers:
    - name: backend
      image: {{ include "platform.image" (dict "ctx" $ctx "image" $v.backend.image) }}
      imagePullPolicy: {{ $v.image.pullPolicy }}
      securityContext:
        {{- toYaml $v.containerSecurityContext | nindent 8 }}
      ports:
        - { name: http, containerPort: 8080, protocol: TCP }
      env:
        - name: PLATFORM_ROLES
          value: {{ .roles | quote }}
        - name: SPRING_PROFILES_ACTIVE
          value: {{ include "platform.profiles" $ctx | quote }}
        - name: SPRING_CONFIG_ADDITIONAL_LOCATION
          value: "optional:file:/etc/platform/"
        {{- if and $v.vaultAgent.enabled $v.vaultAgent.configFile }}
        - name: SPRING_CONFIG_IMPORT
          value: {{ printf "optional:file:/vault/secrets/%s" $v.vaultAgent.configFile | quote }}
        {{- end }}
        - name: JAVA_TOOL_OPTIONS
          value: {{ $v.backend.javaToolOptions | quote }}
        {{- if $v.postgres.enabled }}
        - name: SPRING_DATASOURCE_URL
          value: {{ $v.postgres.url | quote }}
        - name: SPRING_DATASOURCE_USERNAME
          value: {{ $v.postgres.username | quote }}
        {{- if $v.postgres.passwordSecret.name }}
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef: { name: {{ $v.postgres.passwordSecret.name | quote }}, key: {{ $v.postgres.passwordSecret.key | quote }} }
        {{- end }}
        {{- end }}
        {{- range $v.secrets.env }}
        - name: {{ .name }}
          valueFrom:
            secretKeyRef: { name: {{ .secretName | quote }}, key: {{ .key | quote }} }
        {{- end }}
        {{- with $v.backend.env }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- with $v.secrets.envFrom }}
      envFrom:
        {{- range . }}
        - secretRef: { name: {{ . | quote }} }
        {{- end }}
      {{- end }}
      startupProbe:
        httpGet: { path: /actuator/health/liveness, port: http }
        periodSeconds: 5
        failureThreshold: 36
      livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: http }
        periodSeconds: 10
        failureThreshold: 3
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: http }
        periodSeconds: 5
        failureThreshold: 3
      resources:
        {{- toYaml .settings.resources | nindent 8 }}
      volumeMounts:
        - { name: config, mountPath: /etc/platform, readOnly: true }
        - { name: content, mountPath: /content }
        - { name: tmp, mountPath: /tmp }
  volumes:
    - name: config
      configMap:
        name: {{ include "platform.fullname" $ctx }}-backend
    - name: content
      emptyDir: {}
    - name: tmp
      emptyDir: {}
  {{- with .settings.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .settings.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .settings.affinity }}
  affinity:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
