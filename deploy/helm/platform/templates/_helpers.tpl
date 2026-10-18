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

{{/* SPRING_PROFILES_ACTIVE: backend.profiles, plus the profiles the postgres and audit values turn on */}}
{{- define "platform.profiles" -}}
{{- $profiles := .Values.backend.profiles | default list -}}
{{- $implied := list -}}
{{- if .Values.postgres.enabled -}}
{{- $implied = append $implied "postgres" -}}
{{- end -}}
{{- if .Values.audit.siem.enabled -}}
{{- $implied = append $implied "siem" -}}
{{- end -}}
{{- if eq (toString .Values.audit.worm.mode) "files" -}}
{{- $implied = append $implied "worm" -}}
{{- else if eq (toString .Values.audit.worm.mode) "s3" -}}
{{- $implied = append $implied "worm-s3" -}}
{{- else if .Values.audit.worm.mode -}}
{{- fail "audit.worm.mode is files, s3 or empty" -}}
{{- end -}}
{{- range $implied -}}
{{- if not (has . $profiles) -}}
{{- $profiles = append $profiles . -}}
{{- end -}}
{{- end -}}
{{- join "," $profiles -}}
{{- end -}}

{{/* An env var from a key of an existing Secret, nothing when no Secret is named: (dict "env" "NAME" "secret" .Values.x.ySecret) */}}
{{- define "platform.secretEnv" -}}
{{- if .secret.name -}}
- name: {{ .env }}
  valueFrom:
    secretKeyRef: { name: {{ .secret.name | quote }}, key: {{ .secret.key | quote }} }
{{- end -}}
{{- end -}}

{{/* The volume of the Temporal client TLS Secret (temporal.tls), mounted at /etc/temporal-tls; nothing without one */}}
{{- define "platform.temporalTlsVolume" -}}
{{- with .Values.temporal.tls -}}
{{- if .secretName -}}
- name: temporal-tls
  secret:
    secretName: {{ .secretName | quote }}
    defaultMode: 292  # 0444 on a read-only mount: a provider worker's pod has no fsGroup, and its image any non-root uid
    items:
      {{- if .caKey }}
      - { key: {{ .caKey | quote }}, path: ca.crt }
      {{- end }}
      {{- if .certKey }}
      - { key: {{ .certKey | quote }}, path: tls.crt }
      {{- end }}
      {{- if .keyKey }}
      - { key: {{ .keyKey | quote }}, path: tls.key }
      {{- end }}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* "true" when the workflows live in the clone of the content repository (content.repository with content.gitStore.enabled); refuses what the Git workflow store cannot run on */}}
{{- define "platform.gitStore" -}}
{{- $c := .Values.content -}}
{{- if and $c.repository $c.gitStore.enabled -}}
{{- if gt (int .Values.api.replicas) 1 -}}
{{- fail "content.gitStore keeps the workflows in one pod's clone of content.repository and pushes nothing, so another API pod would not see them: set api.replicas to 1, or content.gitStore.enabled to false to keep them in the database" -}}
{{- end -}}
{{- if .Values.worker.enabled -}}
{{- fail "content.gitStore keeps the workflows in one pod's clone of content.repository, which the interpreter's worker reads too: set worker.enabled to false (the API pod then runs the worker), or content.gitStore.enabled to false to keep them in the database" -}}
{{- end -}}
{{- $_ := required "content.gitStore.existingClaim names the volume that keeps the clone the workflows live in (content.gitStore with content.repository), or set content.gitStore.enabled to false" $c.gitStore.existingClaim -}}
true
{{- end -}}
{{- end -}}

{{/* The pod spec of a backend instance: (dict "ctx" $ "roles" "api" "component" "api" "settings" .Values.api) */}}
{{- define "platform.backendPod" -}}
{{- $ctx := .ctx -}}
{{- $v := $ctx.Values -}}
{{- $gitStore := include "platform.gitStore" $ctx -}}
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
          {{- if $gitStore }}
          # the Git workflow store's clone: made once, with the history its retired versions are
          # read from, then kept on the volume with the store's commits
          if [ -d /content/repo/.git ]; then
            echo "keeping the clone in /content/repo"
          else
            git clone --quiet --branch "$CONTENT_REF" "$CONTENT_REPOSITORY" /content/repo
          fi
          {{- else }}
          git clone --quiet --depth 1 --branch "$CONTENT_REF" "$CONTENT_REPOSITORY" /content/repo
          {{- end }}
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
        {{- with include "platform.secretEnv" (dict "env" "PLATFORM_AUDIT_SIGNINGKEY" "secret" $v.audit.signingKeySecret) }}
        {{- . | nindent 8 }}
        {{- end }}
        {{- with include "platform.secretEnv" (dict "env" "PLATFORM_TEMPORAL_APIKEY" "secret" $v.temporal.apiKeySecret) }}
        {{- . | nindent 8 }}
        {{- end }}
        {{- with include "platform.secretEnv" (dict "env" "PLATFORM_TEMPORAL_PAYLOADKEY" "secret" $v.temporal.payloadKeySecret) }}
        {{- . | nindent 8 }}
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
        {{- if $v.temporal.tls.secretName }}
        - { name: temporal-tls, mountPath: /etc/temporal-tls, readOnly: true }
        {{- end }}
        {{- if eq (toString $v.audit.worm.mode) "files" }}
        - { name: evidence, mountPath: {{ $v.audit.worm.files.dir | quote }} }
        {{- end }}
  volumes:
    - name: config
      configMap:
        name: {{ include "platform.fullname" $ctx }}-backend
    - name: content
      {{- if $gitStore }}
      persistentVolumeClaim:
        claimName: {{ $v.content.gitStore.existingClaim | quote }}
      {{- else }}
      emptyDir: {}
      {{- end }}
    - name: tmp
      emptyDir: {}
    {{- with include "platform.temporalTlsVolume" $ctx }}
    {{- . | nindent 4 }}
    {{- end }}
    {{- if eq (toString $v.audit.worm.mode) "files" }}
    - name: evidence
      persistentVolumeClaim:
        claimName: {{ required "audit.worm.files.existingClaim names the volume of the write-once evidence (audit.worm.mode files)" $v.audit.worm.files.existingClaim | quote }}
    {{- end }}
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
