import { get } from './api'

/*
 * Service health through the BFF (WP 9.20): each service the console depends on, the platform
 * first, with its objectives (SLOs) for availability and latency over the last 24 hours and 7
 * days, a day-by-day availability, its status now, and the incidents open on it. The hub has no
 * contract for it yet; the console proposes `GET /console/v1/health`, read from the platform's SLO
 * telemetry by the BFF, so the browser never calls a service's own health endpoint.
 */

export type ServiceStatus = 'operational' | 'degraded' | 'outage'
export type SloKind = 'availability' | 'latency'
export type SloWindow = '24h' | '7d'

export interface SloReading {
  /** the share of good requests in the window, in percent */
  value: number
  /** the 95th percentile of the latency in the window, for a latency objective */
  p95Ms?: number
  met: boolean
  /** the error budget the window has left, as a share from 0 to 1 */
  budgetLeft: number
}

export interface Slo {
  kind: SloKind
  /** the share of requests that must be good, in percent, such as 99.9 */
  objective: number
  /** a latency objective: a request is good when it answers within this */
  thresholdMs?: number
  windows: Record<SloWindow, SloReading>
}

export interface HealthDay {
  /** the UTC day, YYYY-MM-DD */
  date: string
  availability: number
}

export interface HealthService {
  serviceId: string
  title: string
  description: string
  status: ServiceStatus
  slos: Slo[]
  /** the last 7 days, oldest first */
  days: HealthDay[]
}

export type IncidentStatus = 'investigating' | 'identified' | 'monitoring' | 'resolved'

export interface IncidentUpdate {
  at: string
  status: IncidentStatus
  text: string
}

export interface Incident {
  incidentId: string
  serviceId: string
  title: string
  impact: 'minor' | 'major'
  status: IncidentStatus
  startedAt: string
  resolvedAt?: string
  /** newest first */
  updates: IncidentUpdate[]
  /** where the team writes more, such as its runbook or status page */
  link?: string
}

export interface ServiceHealth {
  checkedAt: string
  services: HealthService[]
  /** open ones first, then those resolved in the last 7 days */
  incidents: Incident[]
}

export const healthApi = {
  get: () => get<ServiceHealth>('/console/v1/health'),
}

/* ----------------------------------------------------------------------------
   Words
   ------------------------------------------------------------------------- */

/** A status as StatusBadge spells it (the colour in the dot), and the word people read. */
export const STATUS_WORDS: Record<ServiceStatus, { badge: string; label: string }> = {
  operational: { badge: 'up', label: 'Operational' },
  degraded: { badge: 'degraded', label: 'Degraded' },
  outage: { badge: 'down', label: 'Outage' },
}

export const INCIDENT_WORDS: Record<IncidentStatus, string> = {
  investigating: 'Investigating',
  identified: 'Cause found',
  monitoring: 'Fix out, watching',
  resolved: 'Resolved',
}

/** The footer's word for the platform: up, degraded or down. */
export function platformWord(status: ServiceStatus | undefined): string {
  if (status === 'operational') return 'up'
  if (status === 'degraded') return 'degraded'
  if (status === 'outage') return 'down'
  return 'unknown'
}

/** A percentage the way an objective reads: 99.9%, 99.95%, 100%. */
export function percentText(v: number): string {
  if (!Number.isFinite(v)) return 'not measured'
  const digits = v >= 99.99 && v < 100 ? 3 : v >= 99 ? 2 : 1
  return `${Number(v.toFixed(digits))}%`
}

/** What an objective promises, in words. */
export function objectiveWords(slo: Slo): string {
  if (slo.kind === 'latency') return `${percentText(slo.objective)} of requests answer within ${slo.thresholdMs ?? 0} ms`
  return `${percentText(slo.objective)} of requests succeed`
}

/** One window of an objective in a sentence: "99.95% in the last 24 hours, met, 50% of the error budget left". */
export function readingWords(slo: Slo, window: SloWindow): string {
  const r = slo.windows[window]
  const span = window === '24h' ? 'the last 24 hours' : 'the last 7 days'
  const p95 = slo.kind === 'latency' && r.p95Ms !== undefined ? `, 95th percentile ${Math.round(r.p95Ms)} ms` : ''
  return `${percentText(r.value)} in ${span}${p95}: ${r.met ? 'met' : 'missed'}, ${budgetText(r.budgetLeft)}`
}

export function budgetText(left: number): string {
  if (!Number.isFinite(left) || left <= 0) return 'no error budget left'
  return `${Math.round(Math.min(1, left) * 100)}% of the error budget left`
}

/** The services that are not operational, in a sentence for the top of the page. */
export function summaryWords(services: readonly HealthService[]): string {
  const bad = services.filter((s) => s.status !== 'operational')
  if (!bad.length) return 'Every service is operational.'
  const names = bad.map((s) => `${s.title} (${STATUS_WORDS[s.status].label.toLowerCase()})`)
  const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return `${bad.length === 1 ? 'One service is' : `${bad.length} services are`} not fully working: ${list}. The others are operational.`
}

/**
 * A link from the data, only when it is safe to follow: an https URL, or a path on this origin.
 * Anything else (javascript:, data:, http:, a protocol-relative `//host`) is dropped.
 */
export function safeHref(link: string | undefined | null): string | null {
  if (!link || typeof link !== 'string') return null
  const s = link.trim()
  if (s.startsWith('/') && !s.startsWith('//') && !s.startsWith('/\\')) return s
  try {
    const u = new URL(s)
    return u.protocol === 'https:' && !u.username && !u.password ? u.href : null
  } catch {
    return null
  }
}
