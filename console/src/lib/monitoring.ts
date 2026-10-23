import { get, page, qs, request, type ListParams } from './api'
import { idempotencyKey } from './bff'

/*
 * Monitoring and rightsizing through the BFF (hub console-bff.yaml, tags Monitoring and
 * Rightsizing; the drift thread's drift-bff-routes.md): a resource's metric series and alarms,
 * the account's alarms, and the idle and oversized recommendations with apply and dismiss. The
 * shapes mirror the contract's schemas; the words the pages use for them live here too, so the
 * Monitoring tab, the Alarms page and the Rightsizing page say the same thing the same way.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/* ----------------------------------------------------------------------------
   The contract's shapes
   ------------------------------------------------------------------------- */

export interface MetricPoint {
  at: string
  value: number | null
}

/** One series of a resource's metrics read, such as `cpuPercent`, with its unit. */
export interface MetricSeries {
  name: string
  unit?: string
  points: MetricPoint[]
}

/** `GET /console/v1/r/{type}/{wfrn}/metrics`: the series between `from` and `to`, one point per `step` (an ISO-8601 duration). */
export interface ResourceMetrics {
  wfrn: string
  from: string
  to: string
  step: string
  series: MetricSeries[]
  provider?: string
  action?: string
}

export type AlarmComparison = 'above' | 'below'
export type AlarmState = 'ok' | 'alarm' | 'insufficient'

/** The body of `PUT /console/v1/alarms/{alarmId}`. */
export interface AlarmInput {
  wfrn: string
  name?: string
  metric: string
  comparison: AlarmComparison
  threshold: number
  /** 1 to 1440, 10 when left out: the average over this window is compared */
  windowMinutes?: number
}

/** An alarm as the drift service evaluates it every minute. */
export interface Alarm extends AlarmInput {
  alarmId: string
  accountId: string
  type: string
  windowMinutes: number
  state: AlarmState
  stateSince?: string
  /** the window's average at the last evaluation */
  lastValue?: number | null
  lastEvaluatedAt?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
}

/** A resource's use over the rightsizing window: the 95th percentiles and the highest values, in percent. */
export interface Utilisation {
  from: string
  to: string
  samples: number
  cpuP95?: number
  cpuMax?: number
  memoryP95?: number
  memoryMax?: number
}

/** An amount per month. */
export interface Money {
  amount: number
  currency: string
  basis?: string
}

export type RecommendationKind = 'idle' | 'oversized'
export type RecommendationAction = 'delete' | 'resize'
export type RecommendationStatus = 'open' | 'applied' | 'dismissed' | 'superseded'

export interface Recommendation {
  recommendationId: string
  wfrn: string
  accountId: string
  region: string
  type: string
  name?: string
  kind: RecommendationKind
  action: RecommendationAction
  status: RecommendationStatus
  /** the size fields as they are, such as cpu and memoryGb */
  current?: Record<string, unknown>
  /** the size fields a resize sets; absent for a delete */
  proposed?: Record<string, unknown>
  utilisation: Utilisation
  monthlyCost?: Money
  monthlySaving?: Money
  /** the resize or delete job, once applied */
  jobId?: string
  decidedBy?: string
  reason?: string
  dismissedUntil?: string
  createdAt: string
  updatedAt: string
  /** what this person may do with it */
  can: ('apply' | 'dismiss')[]
}

export interface RecommendationsSummary {
  open: number
  byKind: Record<string, number>
  monthlySaving: Money
  lastPassAt?: string
}

/** What `:apply` answers: 202 with the job that resizes or deletes the resource. */
export interface RecommendationApplied {
  recommendationId: string
  jobId: string
}

export interface AlarmListParams extends ListParams {
  state?: AlarmState
  wfrn?: string
}

export interface RecommendationListParams extends ListParams {
  type?: string
  kind?: RecommendationKind
  status?: RecommendationStatus
  wfrn?: string
}

/** The write headers of a decision: one Idempotency-Key per form submission, kept across retries of the same body. */
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const monitoringApi = {
  /** without a window, the last 24 hours at 5-minute steps */
  metrics: (type: string, wfrn: string, window?: { from?: string; to?: string; step?: string }) => get<ResourceMetrics>(`${b(`/r/${enc(type)}/${enc(wfrn)}/metrics`)}${qs(window)}`),
  alarmsOf: (type: string, wfrn: string) => get<{ items: Alarm[] }>(b(`/r/${enc(type)}/${enc(wfrn)}/alarms`)),
  alarms: (params?: AlarmListParams) => page<Alarm>(b('/alarms'), params),
  /** 201 when new, 200 when it replaced the alarm of the same id: the web app picks the id, so a retried PUT is harmless */
  putAlarm: (alarmId: string, body: AlarmInput) => request<Alarm>('PUT', b(`/alarms/${enc(alarmId)}`), body),
  deleteAlarm: (alarmId: string) => request<void>('DELETE', b(`/alarms/${enc(alarmId)}`)),
  recommendations: (params?: RecommendationListParams) => page<Recommendation>(b('/recommendations'), params),
  summary: () => get<RecommendationsSummary>(b('/recommendations/summary')),
  recommendation: (id: string) => get<Recommendation>(b(`/recommendations/${enc(id)}`)),
  /** a delete needs `confirm: true` and a fresh sign-in (the API client follows a 403 step-up) */
  apply: (id: string, body: { confirm?: boolean; reason?: string }, key?: string) => request<RecommendationApplied>('POST', b(`/recommendations/${enc(id)}:apply`), body, keyed(key)),
  /** without `until`, for good */
  dismiss: (id: string, body: { reason?: string; until?: string }, key?: string) => request<Recommendation>('POST', b(`/recommendations/${enc(id)}:dismiss`), body, keyed(key)),
}

/* ----------------------------------------------------------------------------
   Ranges: what the Monitoring tab offers, and the window each one asks for
   ------------------------------------------------------------------------- */

export interface MetricRange {
  id: string
  label: string
  ms: number
  /** the step the tab asks for: about 60 to 300 points a chart */
  step: string
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE

export const RANGES: readonly MetricRange[] = [
  { id: '1h', label: '1 hour', ms: HOUR, step: 'PT1M' },
  { id: '3h', label: '3 hours', ms: 3 * HOUR, step: 'PT1M' },
  { id: '12h', label: '12 hours', ms: 12 * HOUR, step: 'PT5M' },
  { id: '1d', label: '1 day', ms: 24 * HOUR, step: 'PT5M' },
  { id: '7d', label: '7 days', ms: 7 * 24 * HOUR, step: 'PT1H' },
]

/** The contract's default: the last 24 hours. */
export const DEFAULT_RANGE = '1d'

export function rangeOf(id: string | undefined): MetricRange {
  return RANGES.find((r) => r.id === id) ?? RANGES.find((r) => r.id === DEFAULT_RANGE)!
}

/** The window a range asks for at `now`: up to the current minute, one point per the range's step. */
export function windowOf(range: MetricRange, now: number): { from: string; to: string; step: string } {
  const to = Math.floor(now / MINUTE) * MINUTE
  return { from: new Date(to - range.ms).toISOString(), to: new Date(to).toISOString(), step: range.step }
}

/* ----------------------------------------------------------------------------
   Words: series, units, values, alarms, sizes and money
   ------------------------------------------------------------------------- */

const SERIES_WORDS: Record<string, string> = {
  cpuPercent: 'CPU used',
  memoryPercent: 'Memory used',
  diskPercent: 'Disk used',
  connections: 'Open connections',
}

/** A series' name in words: `cpuPercent` is "CPU used"; an unknown name is split into words. */
export function seriesTitle(name: string): string {
  if (SERIES_WORDS[name]) return SERIES_WORDS[name]
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .trim()
    .toLowerCase()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : name
}

/** The unit as a word for a title: "percent", "count", or the unit as the read names it. */
export function unitWord(unit: string | undefined): string {
  if (!unit) return ''
  if (unit === 'percent' || unit === '%') return 'percent'
  if (unit === 'count') return 'count'
  return unit
}

/** The short suffix a value carries: "%" for percent, nothing for a count, else the unit after a space. */
export function unitSuffix(unit: string | undefined): string {
  if (!unit || unit === 'count') return ''
  if (unit === 'percent' || unit === '%') return '%'
  return ` ${unit}`
}

const ONE_DECIMAL = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1, minimumFractionDigits: 1 })
const WHOLE = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

/** A value with its unit: 42.1% for a percent, 1,204 for a count, 3.5 ms otherwise. */
export function formatMetric(value: number | null | undefined, unit: string | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return 'no value'
  if (unit === 'count') return WHOLE.format(value)
  return `${ONE_DECIMAL.format(value)}${unitSuffix(unit)}`
}

/** A value in words for a screen reader: "42.1 percent", "1,204". */
export function speakMetric(value: number | null | undefined, unit: string | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return 'no value'
  if (unit === 'count') return WHOLE.format(value)
  const word = unitWord(unit)
  return `${ONE_DECIMAL.format(value)}${word ? ` ${word}` : ''}`
}

export const ALARM_STATE_WORDS: Record<AlarmState, { tone: 'healthy' | 'critical' | 'neutral'; text: string }> = {
  ok: { tone: 'healthy', text: 'OK' },
  alarm: { tone: 'critical', text: 'In alarm' },
  insufficient: { tone: 'neutral', text: 'Not enough data' },
}

/** What an alarm watches, in words: "CPU used above 80% on average over 10 minutes". */
export function alarmCondition(a: Pick<AlarmInput, 'metric' | 'comparison' | 'threshold' | 'windowMinutes'>, unit?: string): string {
  const minutes = a.windowMinutes ?? 10
  return `${seriesTitle(a.metric)} ${a.comparison} ${formatThreshold(a.threshold, unit ?? unitOfMetric(a.metric))} on average over ${minutes === 1 ? '1 minute' : `${minutes} minutes`}`
}

/** An alarm's name, or its condition when it has none. */
export function alarmName(a: Pick<Alarm, 'name' | 'metric' | 'comparison' | 'threshold'>): string {
  return a.name?.trim() || `${seriesTitle(a.metric)} ${a.comparison} ${formatThreshold(a.threshold, unitOfMetric(a.metric))}`
}

/** A threshold with its unit, as a person typed it: 80%, 72.5%, 400. */
export function formatThreshold(threshold: number, unit: string | undefined): string {
  if (unit === 'count') return WHOLE.format(threshold)
  return `${Number.isInteger(threshold) ? WHOLE.format(threshold) : ONE_DECIMAL.format(threshold)}${unitSuffix(unit)}`
}

/** The unit a series name implies when no read is at hand: `…Percent` is percent, connections a count. */
export function unitOfMetric(name: string): string | undefined {
  if (/percent$/i.test(name)) return 'percent'
  if (name === 'connections' || /count$/i.test(name)) return 'count'
  return undefined
}

/** A fresh alarm id the BFF accepts (`^[A-Za-z0-9_-]{1,64}$`), taken when the form is first sent. */
export function newAlarmId(): string {
  return `al-${idempotencyKey().replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40)}`
}

/** The size fields in words: "4 CPUs and 16 GB of memory", "tier M30". */
export function sizeWords(size: Record<string, unknown> | undefined): string {
  if (!size) return 'not known'
  const parts: string[] = []
  if (typeof size.cpu === 'number') parts.push(`${size.cpu} ${size.cpu === 1 ? 'CPU' : 'CPUs'}`)
  if (typeof size.memoryGb === 'number') parts.push(`${size.memoryGb} GB of memory`)
  if (typeof size.tier === 'string') parts.push(`tier ${size.tier}`)
  for (const [k, v] of Object.entries(size)) if (!['cpu', 'memoryGb', 'tier'].includes(k) && (typeof v === 'string' || typeof v === 'number')) parts.push(`${k} ${v}`)
  return parts.length ? parts.join(' and ') : 'not known'
}

/** An amount per month: "$38.50", in its currency. */
export function formatMoney(m: Money | undefined): string {
  if (!m || !Number.isFinite(m.amount)) return 'not known'
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: m.currency || 'USD' }).format(m.amount)
  } catch {
    return `${m.amount.toFixed(2)} ${m.currency}`
  }
}

/** A percentile in words for a cell: "3.2%", or "not measured". */
export function formatPercent(v: number | undefined): string {
  return v === undefined || !Number.isFinite(v) ? 'not measured' : `${ONE_DECIMAL.format(v)}%`
}

export const KIND_WORDS: Record<RecommendationKind, { tone: 'warning' | 'neutral'; text: string; action: string }> = {
  idle: { tone: 'warning', text: 'Idle', action: 'Delete it' },
  oversized: { tone: 'neutral', text: 'Oversized', action: 'Resize it' },
}

export const STATUS_WORDS: Record<RecommendationStatus, string> = {
  open: 'Open',
  applied: 'Applied',
  dismissed: 'Dismissed',
  superseded: 'Superseded',
}
