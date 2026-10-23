{{- define "console-cost-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "console-cost-service.fullname" -}}
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

{{- define "console-cost-service.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "console-cost-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: console
app.kubernetes.io/component: cost
{{- end -}}

{{- define "console-cost-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "console-cost-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "console-cost-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "console-cost-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "console-cost-service.profiles" -}}
{{- $profiles := list "mongo" -}}
{{- if and .Values.ingest.enabled .Values.ingest.kafka.enabled -}}{{- $profiles = append $profiles "kafka" -}}{{- end -}}
{{- range .Values.extraProfiles -}}{{- $profiles = append $profiles . -}}{{- end -}}
{{- join "," $profiles -}}
{{- end -}}
