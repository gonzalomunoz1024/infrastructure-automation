import { get, page, qs, request, type ListParams } from './api'
import { idempotencyKey } from './bff'

/*
 * Compliance scorecards (WP 9.6, hub decision 26): rule packs scored per account by the drift
 * service, through the BFF's /console/v1/scorecards routes (contracts/openapi/console-bff.yaml on
 * console-root--stack, tag Scorecards): the account's scorecard by pack, rule and severity with
 * its daily trend, the packs with their one-click fixes, the organization's heat map, the
 * resources failing a rule, a rule's fix for one of them, and a resource's own evaluations. A
 * score is 100 × Σ weight × passed ÷ Σ weight × (passed + failed), critical 10, warning 3, info 1,
 * and null while nothing is scored. A fix is never automatic: a person starts it, and a workflow
 * fix is a request like any other.
 */

export type Severity = 'info' | 'warning' | 'critical'
export type RuleStatus = 'pass' | 'fail' | 'error'

export interface Counts {
  passed: number
  failed: number
}

export interface RuleScore extends Counts {
  id: string
  title: string
  severity: Severity
  score: number | null
  remediable: boolean
}

export interface PackScore extends Counts {
  name: string
  title: string
  version: number
  score: number | null
  rules: RuleScore[]
}

export interface Scorecard extends Counts {
  accountId: string
  score: number | null
  resources: number
  failingResources?: number
  bySeverity: Record<Severity, Counts>
  packs: PackScore[]
  trend: { day: string; score: number | null }[]
  evaluatedAt?: string
}

export interface RemediationInput {
  name: string
  title: string
  type: 'string' | 'integer' | 'number' | 'boolean'
  enum?: unknown[]
  /** may be a `{{ $resource.<path> }}` template, filled per resource */
  default?: unknown
  required: boolean
}

export interface RemediationSpec {
  title: string
  kind: 'workflow' | 'tags'
  verb?: string
  workflow?: string
  inputs: RemediationInput[]
}

export interface RuleSummary {
  id: string
  title: string
  description?: string
  severity: Severity
  types: string[]
  remediation?: RemediationSpec
}

export interface RulePackSummary {
  name: string
  title: string
  description?: string
  version: number
  owner?: string
  rules: RuleSummary[]
}

export interface HeatmapRow {
  accountId: string
  accountName?: string
  score: number | null
  failed: number
  packs: Record<string, number | null>
}

export interface ScorecardHeatmap {
  packs: { name: string; title: string }[]
  rows: HeatmapRow[]
}

export interface Evaluation {
  wfrn: string
  accountId: string
  region: string
  type: string
  name?: string
  pack: string
  ruleId: string
  severity: Severity
  status: RuleStatus
  message?: string
  since: string
  evaluatedAt: string
}

export interface Remediation {
  wfrn: string
  pack: string
  ruleId: string
  kind: 'workflow' | 'tags'
  jobId?: string
  workflow?: string
  tags?: Record<string, string>
}

export interface RuleResult {
  pack: string
  ruleId: string
  title: string
  severity: Severity
  status: RuleStatus
  message?: string
  since: string
  remediable: boolean
}

export interface ResourceCompliance extends Counts {
  wfrn: string
  accountId: string
  region: string
  type: string
  evaluatedAt?: string
  results: RuleResult[]
}

const BASE = '/console/v1'
const enc = encodeURIComponent

export const scorecardsApi = {
  scorecard: (days?: number) => get<Scorecard>(`${BASE}/scorecards${qs({ days })}`),
  packs: () => page<RulePackSummary>(`${BASE}/scorecards/packs`),
  heatmap: () => get<ScorecardHeatmap>(`${BASE}/scorecards/heatmap`),
  failing: (pack: string, ruleId: string, params?: ListParams & { type?: string }) => page<Evaluation>(`${BASE}/scorecards/packs/${enc(pack)}/rules/${enc(ruleId)}/failing`, params),
  /** 202 with the job for a workflow fix, 200 with the tags for a tags fix */
  remediate: (pack: string, ruleId: string, body: { wfrn: string; input?: Record<string, unknown>; reason?: string }, key?: string) =>
    request<Remediation>('POST', `${BASE}/scorecards/packs/${enc(pack)}/rules/${enc(ruleId)}:remediate`, body, { headers: { 'Idempotency-Key': key ?? idempotencyKey() } }),
  resource: (type: string, wfrn: string) => get<ResourceCompliance>(`${BASE}/r/${enc(type)}/${enc(wfrn)}/compliance`),
}

/* ----------------------------------------------------------------------------
   The words
   ------------------------------------------------------------------------- */

export const WEIGHTS: Record<Severity, number> = { critical: 10, warning: 3, info: 1 }

export const SEVERITY_WORDS: Record<Severity, { label: string; tone: 'critical' | 'warning' | 'neutral' }> = {
  critical: { label: 'Critical', tone: 'critical' },
  warning: { label: 'Warning', tone: 'warning' },
  info: { label: 'Info', tone: 'neutral' },
}

export const STATUS_WORDS: Record<RuleStatus, { label: string; status: string }> = {
  pass: { label: 'Passes', status: 'succeeded' },
  fail: { label: 'Fails', status: 'failed' },
  error: { label: 'Not judged', status: 'queued' },
}

/** The score as the contract weighs it; null when nothing was judged. */
export function scoreOf(results: readonly { severity: Severity; status: RuleStatus }[]): number | null {
  let total = 0
  let passed = 0
  for (const r of results) {
    if (r.status === 'error') continue
    total += WEIGHTS[r.severity]
    if (r.status === 'pass') passed += WEIGHTS[r.severity]
  }
  return total === 0 ? null : Math.round((100 * passed) / total)
}

/** A score's band, for its words and its cell in the heat map. */
export function bandOf(score: number | null | undefined): { label: string; tone: 'healthy' | 'warning' | 'critical' | 'neutral' } {
  if (score === null || score === undefined) return { label: 'Nothing applies', tone: 'neutral' }
  if (score >= 90) return { label: 'Good', tone: 'healthy' }
  if (score >= 70) return { label: 'Needs work', tone: 'warning' }
  return { label: 'Poor', tone: 'critical' }
}

export function scoreText(score: number | null | undefined): string {
  return score === null || score === undefined ? 'No score' : `${score} of 100`
}

/** A default that names the resource (`{{ $resource.environment }}`), filled from its attributes and tags. */
export function fillDefault(value: unknown, resource: { attributes?: Record<string, unknown>; tags?: Record<string, string> } | undefined): unknown {
  if (typeof value !== 'string') return value
  const m = /^\{\{\s*\$resource\.([A-Za-z0-9_.-]+)\s*\}\}$/.exec(value)
  if (!m) return value
  const key = m[1].replace(/^attributes\./, '').replace(/^tags\./, '')
  return resource?.attributes?.[key] ?? resource?.tags?.[key] ?? ''
}
