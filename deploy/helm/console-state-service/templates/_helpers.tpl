{{- define "console-state-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "console-state-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "console-state-service.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "console-state-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: console
app.kubernetes.io/component: state
{{- end -}}

{{- define "console-state-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "console-state-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "console-state-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "console-state-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "console-state-service.profiles" -}}
{{- $profiles := list "mongo" -}}
{{- if .Values.ingest.kafka.enabled -}}{{- $profiles = append $profiles "kafka" -}}{{- end -}}
{{- range .Values.extraProfiles -}}{{- $profiles = append $profiles . -}}{{- end -}}
{{- join "," $profiles -}}
{{- end -}}
