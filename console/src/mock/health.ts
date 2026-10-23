import type { HealthDay, HealthService, Incident, ServiceHealth, ServiceStatus, Slo, SloReading } from '../lib/health'
import type { RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * Service health as the BFF would read it from the platform's SLO telemetry (WP 9.20; no hub
 * contract yet, the route is the console's proposal in lib/health.ts). Every number is a
 * function of the service and the time, so two reads of the same minute agree: each service has
 * a level of good requests and of latency, with a small daily wobble. Search is degraded in the
 * demo (its index lags and its queries are slow since 52 minutes ago), and State had an incident
 * three days ago that is resolved. Anyone signed in reads it: it holds no account's data.
 */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

interface ServiceModel {
  serviceId: string
  title: string
  description: string
  availability: { objective: number; level: number }
  latency: { objective: number; thresholdMs: number; level: number; p95Ms: number }
  /** a bad stretch: since how long ago, the points of good requests each hour of it loses (failed, then slow), its latency, and the status it gives */
  trouble?: { sinceMs: number; availabilityLoss: number; latencyLoss: number; p95Ms: number; status: ServiceStatus }
}

const SERVICES: readonly ServiceModel[] = [
  { serviceId: 'platform', title: 'The platform', description: 'Runs the workflows that create, change and delete resources.', availability: { objective: 99.9, level: 99.97 }, latency: { objective: 99, thresholdMs: 500, level: 99.6, p95Ms: 210 } },
  { serviceId: 'console-bff', title: 'Console API', description: 'The console’s own back end: sign-in, sessions and every page’s reads and changes.', availability: { objective: 99.9, level: 99.98 }, latency: { objective: 99, thresholdMs: 300, level: 99.5, p95Ms: 120 } },
  { serviceId: 'identity', title: 'Identity', description: 'Who people are, their groups, roles and access requests.', availability: { objective: 99.95, level: 99.99 }, latency: { objective: 99, thresholdMs: 200, level: 99.7, p95Ms: 60 } },
  { serviceId: 'state', title: 'State', description: 'The record of every resource and request.', availability: { objective: 99.9, level: 99.95 }, latency: { objective: 99, thresholdMs: 250, level: 99.4, p95Ms: 95 } },
  { serviceId: 'drift', title: 'Drift', description: 'Compares what the providers report with what was asked for, every hour.', availability: { objective: 99.5, level: 99.9 }, latency: { objective: 95, thresholdMs: 1000, level: 98.8, p95Ms: 420 } },
  {
    serviceId: 'search',
    title: 'Search',
    description: 'The palette’s search and the lists’ free-text filter.',
    availability: { objective: 99.5, level: 99.92 },
    latency: { objective: 99, thresholdMs: 400, level: 99.4, p95Ms: 150 },
    trouble: { sinceMs: 52 * MINUTE, availabilityLoss: 2, latencyLoss: 25, p95Ms: 1350, status: 'degraded' },
  },
  { serviceId: 'cost', title: 'Cost', description: 'What each resource costs a month, and the rightsizing savings.', availability: { objective: 99.5, level: 99.9 }, latency: { objective: 95, thresholdMs: 800, level: 98.9, p95Ms: 310 } },
]

/** A wobble from -1 to 1 for a service and a day, the same every time. */
function wobble(seed: string, k: number): number {
  let h = 2166136261
  const s = `${seed}:${k}`
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619) >>> 0
  return (h % 2001) / 1000 - 1
}

const round = (v: number, digits = 3) => Number(v.toFixed(digits))

/** The share of good requests over [from, to): the level with its daily wobble, less the trouble's bad share while it lasts. */
function goodShare(m: ServiceModel, level: number, from: number, to: number, now: number, kind: 'availability' | 'latency'): number {
  const days: number[] = []
  for (let t = from; t < to; t += HOUR) {
    const day = Math.floor(t / DAY)
    let v = Math.min(100, level + wobble(`${m.serviceId}:${kind}`, day) * (100 - level) * 0.4)
    if (m.trouble && t + HOUR > now - m.trouble.sinceMs) {
      const bad = kind === 'availability' ? m.trouble.availabilityLoss : m.trouble.latencyLoss
      const part = Math.min(1, (t + HOUR - (now - m.trouble.sinceMs)) / HOUR)
      v -= bad * part
    }
    days.push(v)
  }
  return days.reduce((n, v) => n + v, 0) / Math.max(1, days.length)
}

function reading(objective: number, value: number, p95Ms?: number): SloReading {
  const allowed = 100 - objective
  const spent = 100 - value
  return { value: round(value), ...(p95Ms !== undefined ? { p95Ms: Math.round(p95Ms) } : {}), met: value >= objective, budgetLeft: round(Math.max(0, 1 - spent / allowed), 2) }
}

function serviceOf(m: ServiceModel, now: number): HealthService {
  const hour = Math.floor(now / HOUR) * HOUR
  const inTrouble = !!m.trouble
  const window = (ms: number) => ({ from: hour - ms + HOUR, to: hour + HOUR })
  const avail = (ms: number) => goodShare(m, m.availability.level, window(ms).from, window(ms).to, now, 'availability')
  const fast = (ms: number) => goodShare(m, m.latency.level, window(ms).from, window(ms).to, now, 'latency')
  const p95 = (ms: number) => (inTrouble && ms === DAY ? m.trouble!.p95Ms : m.latency.p95Ms * (1 + wobble(`${m.serviceId}:p95`, Math.floor(now / DAY)) * 0.1))
  const slos: Slo[] = [
    { kind: 'availability', objective: m.availability.objective, windows: { '24h': reading(m.availability.objective, avail(DAY)), '7d': reading(m.availability.objective, avail(7 * DAY)) } },
    { kind: 'latency', objective: m.latency.objective, thresholdMs: m.latency.thresholdMs, windows: { '24h': reading(m.latency.objective, fast(DAY), p95(DAY)), '7d': reading(m.latency.objective, fast(7 * DAY), p95(7 * DAY) * (inTrouble ? 1.4 : 1)) } },
  ]
  const today = Math.floor(now / DAY) * DAY
  const days: HealthDay[] = []
  for (let i = 6; i >= 0; i--) {
    const start = today - i * DAY
    days.push({ date: new Date(start).toISOString().slice(0, 10), availability: round(goodShare(m, m.availability.level, start, Math.min(start + DAY, hour + HOUR), now, 'availability')) })
  }
  return { serviceId: m.serviceId, title: m.title, description: m.description, status: m.trouble?.status ?? 'operational', slos, days }
}

function incidentsOf(now: number): Incident[] {
  const minute = Math.floor(now / MINUTE) * MINUTE
  const started = minute - 52 * MINUTE
  const iso = (t: number) => new Date(t).toISOString()
  const stateStart = Math.floor(now / DAY) * DAY - 3 * DAY + 14 * HOUR
  return [
    {
      incidentId: 'inc-search-lag',
      serviceId: 'search',
      title: 'Search results lag behind and answer slowly',
      impact: 'minor',
      status: 'identified',
      startedAt: iso(started),
      updates: [
        { at: iso(started + 31 * MINUTE), status: 'identified', text: 'The search index is catching up after a large import. New and changed resources take up to 10 minutes to show in search, and searches take a second or more. Lists, resource pages and changes are not affected.' },
        { at: iso(started), status: 'investigating', text: 'Searches are slower than usual. We are looking into it.' },
      ],
      link: 'https://status.example.com/incidents/inc-search-lag',
    },
    {
      incidentId: 'inc-state-writes',
      serviceId: 'state',
      title: 'Some changes waited before starting',
      impact: 'minor',
      status: 'resolved',
      startedAt: iso(stateStart),
      resolvedAt: iso(stateStart + 25 * MINUTE),
      updates: [
        { at: iso(stateStart + 25 * MINUTE), status: 'resolved', text: 'A database failover made new requests wait up to 2 minutes before they started. Every request went on by itself; nothing was lost.' },
        { at: iso(stateStart + 4 * MINUTE), status: 'investigating', text: 'New requests are waiting before they start.' },
      ],
    },
  ]
}

/** The health of every service at `now`. */
export function healthOf(now: number): ServiceHealth {
  return { checkedAt: new Date(Math.floor(now / MINUTE) * MINUTE).toISOString(), services: SERVICES.map((m) => serviceOf(m, now)), incidents: incidentsOf(now) }
}

/** Answers `GET /console/v1/health`, or null for any other path. */
export function healthRoutes(_data: MockData, ctx: RouteContext, path: string, _params: URLSearchParams, method: string): RouteAnswer | null {
  if (path !== '/console/v1/health') return null
  if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  return { status: 200, body: healthOf(ctx.now) }
}
