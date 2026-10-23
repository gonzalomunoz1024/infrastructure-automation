{{- define "console-bff.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "console-bff.fullname" -}}
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

{{- define "console-bff.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
app.kubernetes.io/name: {{ include "console-bff.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: customer-console
{{- end -}}

{{- define "console-bff.selectorLabels" -}}
app.kubernetes.io/name: {{ include "console-bff.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "console-bff.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "console-bff.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
The service-token settings (hub decision 23) as Spring JSON: the cluster's service-account issuer,
read through the projected kube-api volume, and each client with its audiences and service account.
*/}}
{{- define "console-bff.serviceTokens" -}}
{{- $st := .Values.config.serviceTokens -}}
{{- $clients := dict -}}
{{- range $id, $c := $st.clients -}}
{{- $client := dict "audiences" $c.audiences "service-account" ($c.serviceAccount | default (printf "%s:%s" $.Release.Namespace $id)) -}}
{{- with $c.jwks }}{{ $_ := set $client "jwks" . }}{{ end -}}
{{- with $c.jwksUri }}{{ $_ := set $client "jwks-uri" . }}{{ end -}}
{{- $_ := set $clients $id $client -}}
{{- end -}}
{{- $trust := dict "clients" $clients -}}
{{- if $st.kubernetes.enabled -}}
{{- $_ := set $trust "kubernetes" (dict "issuer" $st.kubernetes.issuer "jwks-uri" $st.kubernetes.jwksUri "ca-file" "/var/run/secrets/console/kube-api/ca.crt" "token-file" "/var/run/secrets/console/kube-api/token") -}}
{{- end -}}
{{- dict "console" (dict "trust" $trust) | toJson -}}
{{- end -}}
