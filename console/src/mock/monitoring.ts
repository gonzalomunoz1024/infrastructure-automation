import { lifecycleOf, type Problem } from '@iaf/console-kit'
import { durationMs, isoDuration } from '../lib/chart'
import { partsOfWfrn, platformObserveOf, serviceOf, titleOf } from '../lib/descriptors'
import type { Alarm, AlarmComparison, AlarmState, MetricPoint, MetricSeries, Money, Recommendation, RecommendationKind, RecommendationStatus, RecommendationsSummary, ResourceMetrics, Utilisation } from '../lib/monitoring'
import type { Resource, ResourceType } from '../lib/types'
import { applyCommand, emptyConsoleState, idFrom, journaled, previewVerb, priceOf, resourceByWfrn, subjectFieldOf, typeOf, updateSchemaOf, verbEntryOf, wfrnOfResource, wfrnPatternOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { authorize, type Decision } from './directory'
import { accountOfResource } from './resources'

/*
 * The BFF's monitoring and rightsizing routes (hub console-bff.yaml, tags Monitoring and
 * Rightsizing; the drift thread's drift-bff-routes.md): a resource's metric series, alarms per
 * resource and per account, and the idle and oversized recommendations with apply and dismiss.
 * Writes are journaled through `journaled` in ./commands under the `alarm.` and
 * `recommendation.` kinds, so a reload replays them.
 *
 * The series are a pure function of the resource and the time: each resource has a profile from
 * its id (idle, oversized, steady or busy), and each series a level, a daily rhythm that peaks in
 * working hours (a batch machine's at night) and drops at weekends, and smooth noise, so two
 * reads of the same minute always agree. Alarms are evaluated when they are read, against the
 * average of their window; recommendations come from a daily pass over the last 14 days.
 */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** At most this many points a series: a longer range is answered at a coarser step. */
export const MAX_POINTS = 500
/** Metric reads an account may make a minute before the provider's observe budget answers 429. */
export const OBSERVE_BUDGET = 120
/** The longest range one read may cover. */
const MAX_RANGE = 31 * DAY
/** The steps a read falls back to when the asked one would carry more than MAX_POINTS. */
const STEP_LADDER = [1, 2, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1440].map((m) => m * MINUTE)

/** The daily pass runs at 05:00 UTC and reads the 14 days before it at 15-minute steps. */
const PASS_HOUR = 5
export const RIGHTSIZING_WINDOW = 14 * DAY
const UTILISATION_STEP = 15 * MINUTE
/** Fewer samples than three days' worth is too little to judge. */
const MIN_SAMPLES = (3 * DAY) / UTILISATION_STEP
export const IDLE_BELOW = 5
export const OVERSIZED_BELOW = 30
export const TARGET_BELOW = 60
/** The vm sizes a resize may propose, smallest first. */
export const VM_LADDER: readonly { cpu: number; memoryGb: number }[] = [
  { cpu: 2, memoryGb: 8 },
  { cpu: 4, memoryGb: 16 },
  { cpu: 8, memoryGb: 32 },
  { cpu: 16, memoryGb: 64 },
]

/* ----------------------------------------------------------------------------
   State
   ------------------------------------------------------------------------- */

export interface StoredAlarm {
  alarmId: string
  accountId: string
  wfrn: string
  type: string
  name?: string
  metric: string
  comparison: AlarmComparison
  threshold: number
  windowMinutes: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** What a person decided about a recommendation, with the recommendation as it stood then. */
export interface RecommendationDecision {
  kind: 'applied' | 'dismissed'
  recommendationId: string
  resourceId: string
  at: number
  by: string
  reason?: string
  until?: string
  jobId?: string
  snapshot: View
}

/** What these routes keep beside the dataset, made on first use. */
export interface MonitoringState {
  /** by `<accountId>/<alarmId>`: the web app picks alarm ids, so each account has its own */
  alarms: Record<string, StoredAlarm>
  /** by recommendation id */
  decisions: Record<string, RecommendationDecision>
  /** metric reads this minute, per account: the observe budget, never journaled */
  budget: { minute: number; used: Record<string, number> }
  budgetPerMinute: number
}

export function emptyMonitoringState(): MonitoringState {
  return { alarms: {}, decisions: {}, budget: { minute: 0, used: {} }, budgetPerMinute: OBSERVE_BUDGET }
}

/** The alarms the demo starts with: one firing, one without data, the rest quiet. */
const SEEDED_ALARMS: readonly (Omit<StoredAlarm, 'accountId' | 'wfrn' | 'type' | 'updatedAt'> & { resourceId: string })[] = [
  { alarmId: 'web-01-cpu-high', resourceId: 'vm-demo01', name: 'web-01 CPU above 90%', metric: 'cpuPercent', comparison: 'above', threshold: 90, windowMinutes: 10, createdBy: 'dana', createdAt: '2026-10-12T09:30:00.000Z' },
  { alarmId: 'api-01-memory-high', resourceId: 'vm-demo02', name: 'api-01 memory above 85%', metric: 'memoryPercent', comparison: 'above', threshold: 85, windowMinutes: 15, createdBy: 'dana', createdAt: '2026-10-13T10:00:00.000Z' },
  { alarmId: 'shop-01-doing-nothing', resourceId: 'vm-demo06', name: 'shop-01 doing nothing', metric: 'cpuPercent', comparison: 'below', threshold: 5, windowMinutes: 60, createdBy: 'mateo', createdAt: '2026-10-15T08:00:00.000Z' },
  { alarmId: 'cache-01-disk-full', resourceId: 'vm-demo05', name: 'cache-01 disk nearly full', metric: 'diskPercent', comparison: 'above', threshold: 90, windowMinutes: 30, createdBy: 'dana', createdAt: '2026-10-16T12:00:00.000Z' },
  { alarmId: 'vm-gen-004-memory', resourceId: 'vm-gen-004', name: 'batch-014 memory above 85%', metric: 'memoryPercent', comparison: 'above', threshold: 85, windowMinutes: 10, createdBy: 'mateo', createdAt: '2026-10-18T07:45:00.000Z' },
  { alarmId: 'orders-db-connections', resourceId: 'database-demo01', name: 'orders-db connections above 400', metric: 'connections', comparison: 'above', threshold: 400, windowMinutes: 5, createdBy: 'raj', createdAt: '2026-10-14T11:15:00.000Z' },
]

/** The state of these routes, made with the seeded alarms on first use. */
export function monitoringStateOf(data: MockData): MonitoringState {
  const st = (data.console ??= emptyConsoleState())
  if (st.monitoring) return st.monitoring
  const state = emptyMonitoringState()
  const accounts = data.directory.accounts.map((a) => a.accountId)
  for (const { resourceId, ...seed } of SEEDED_ALARMS) {
    const r = data.resources.find((x) => x.id === resourceId)
    const rt = r ? typeOf(data, r.type) : undefined
    if (!r || !rt) continue
    const accountId = accountOfResource(r, accounts)
    state.alarms[`${accountId}/${seed.alarmId}`] = { ...seed, accountId, wfrn: wfrnOfResource(data, { region: data.regionName }, rt, r), type: rt.name, updatedAt: seed.createdAt }
  }
  st.monitoring = state
  return state
}

/* ----------------------------------------------------------------------------
   The series: a profile per resource, a value per series and moment
   ------------------------------------------------------------------------- */

export type Profile = 'idle' | 'oversized' | 'steady' | 'busy'

/** The demo's named machines, so the story is the same every time: web-01 runs hot, web-02 is oversized, shop-01 and billing-db idle. */
const DEMO_PROFILES: Record<string, Profile> = {
  'vm-demo01': 'busy',
  'vm-demo02': 'steady',
  'vm-demo03': 'oversized',
  'vm-demo04': 'steady',
  'vm-demo05': 'steady',
  'vm-demo06': 'idle',
  'database-demo01': 'busy',
  'database-demo02': 'idle',
}

function seedOf(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619)
  return h >>> 0
}

function mix(h: number): number {
  h ^= h >>> 16
  h = Math.imul(h, 0x85ebca6b)
  h ^= h >>> 13
  h = Math.imul(h, 0xc2b2ae35)
  h ^= h >>> 16
  return h >>> 0
}

/** A number in [0, 1) drawn from a seed and a knot, the same every time. */
function unit(seed: number, k: number): number {
  return mix((seed ^ Math.imul(k | 0, 0x9e3779b1)) >>> 0) / 4294967296
}

/** Smooth noise in [-1, 1]: random knots `period` apart, eased between. */
function smooth(seed: number, t: number, period: number): number {
  const x = t / period
  const k = Math.floor(x)
  const f = x - k
  const a = unit(seed, k)
  const b = unit(seed, k + 1)
  return (a + (b - a) * f * f * (3 - 2 * f)) * 2 - 1
}

/** A resource's profile: the demo's named ones as the story needs, any other by its id (4 in 100 idle, 14 oversized, 10 busy). */
export function profileOf(id: string): Profile {
  if (DEMO_PROFILES[id]) return DEMO_PROFILES[id]
  const h = seedOf(`profile:${id}`) % 100
  return h < 4 ? 'idle' : h < 18 ? 'oversized' : h < 90 ? 'steady' : 'busy'
}

/** Base, daily swing and noise of each family of series, by profile: idle stays under 5, oversized under 30. */
const LEVELS: Record<Profile, Record<'cpu' | 'memory' | 'connections', [number, number, number]>> = {
  idle: { cpu: [1.2, 1, 0.5], memory: [2.4, 0.6, 0.4], connections: [0.4, 0.6, 0.3] },
  oversized: { cpu: [8, 8, 2.5], memory: [13, 5, 2], connections: [6, 6, 2] },
  steady: { cpu: [26, 20, 5], memory: [44, 10, 3.5], connections: [40, 35, 8] },
  busy: { cpu: [52, 26, 6], memory: [66, 12, 4], connections: [180, 160, 25] },
}

export interface SeriesModel {
  seed: number
  kind: 'percent' | 'count' | 'disk'
  base: number
  swing: number
  noise: number
  /** the hour of the day (UTC) the rhythm peaks at */
  peak: number
  /** when the resource was created: a disk fills from then */
  born: number
}

/** The series each type's metrics read answers, in order; a type the mock does not know reads CPU and memory. */
export const SERIES_OF: Record<string, readonly { name: string; unit: string }[]> = {
  vm: [
    { name: 'cpuPercent', unit: 'percent' },
    { name: 'memoryPercent', unit: 'percent' },
    { name: 'diskPercent', unit: 'percent' },
  ],
  database: [
    { name: 'cpuPercent', unit: 'percent' },
    { name: 'memoryPercent', unit: 'percent' },
    { name: 'connections', unit: 'count' },
  ],
}

export function seriesNamesOf(rt: Pick<ResourceType, 'name'>): readonly { name: string; unit: string }[] {
  return SERIES_OF[rt.name] ?? SERIES_OF.vm.slice(0, 2)
}

/** The model of one series of a resource, from its id: the same resource always draws the same line. */
export function modelOf(r: Pick<Resource, 'id' | 'name' | 'createdAt'>, series: string): SeriesModel {
  const profile = profileOf(r.id)
  const seed = seedOf(`${r.id}:${series}`)
  const vary = 0.85 + 0.3 * unit(seed, 1)
  // a batch or ETL machine works at night; everything else in working hours, between 09:00 and 16:00 UTC
  const peak = /batch|etl/i.test(r.name ?? '') ? 2 + unit(seed, 2) * 2 : 9 + Math.floor(unit(seed, 2) * 8)
  const born = Date.parse(r.createdAt)
  if (series === 'diskPercent') return { seed, kind: 'disk', base: 30 + 30 * unit(seed, 3), swing: 0, noise: 0.4, peak, born }
  const family = series === 'memoryPercent' ? 'memory' : series === 'connections' ? 'connections' : 'cpu'
  const [base, swing, noise] = LEVELS[profile][family]
  return { seed, kind: family === 'connections' ? 'count' : 'percent', base: base * vary, swing: swing * vary, noise, peak, born }
}

/** The value of a series at a moment: the level, the day's rhythm (lower at weekends) and smooth noise; a disk fills slowly. */
export function valueAt(m: SeriesModel, t: number): number {
  if (m.kind === 'disk') {
    const days = Math.max(0, (t - m.born) / DAY)
    return Math.min(97, Math.max(0, m.base + days * 0.25 + m.noise * smooth(m.seed, t, 6 * HOUR)))
  }
  const hour = (((t / HOUR) % 24) + 24) % 24
  const daily = ((1 + Math.cos((2 * Math.PI * (hour - m.peak)) / 24)) / 2) ** 1.6
  const dow = Math.floor(t / DAY + 4) % 7
  const week = dow === 0 || dow === 6 ? 0.55 : 1
  const v = m.base + m.swing * daily * week + m.noise * (0.7 * smooth(m.seed, t, 12 * MINUTE) + 0.3 * smooth(m.seed ^ 0x5bd1e995, t, 4 * HOUR))
  return m.kind === 'count' ? Math.max(0, v) : Math.min(100, Math.max(0.1, v))
}

/** When a resource reports: from its creation to its deletion; never while it is still being built. */
export function aliveOf(data: MockData, rt: ResourceType, r: Resource): { start: number; end: number } | null {
  const cls = lifecycleOf(r.status, rt.console?.lifecycle) ?? 'active'
  if (cls === 'pending' || data.console?.inFlight.some((f) => f.resourceId === r.id && f.effect === 'create')) return null
  return { start: Date.parse(r.createdAt), end: cls === 'deleted' ? Date.parse(r.updatedAt ?? r.createdAt) : Infinity }
}

/** A resize through rightsizing whose job succeeded: the same load on a smaller size reads higher from then on. */
interface SizeChange {
  at: number
  from: Record<string, unknown>
  to: Record<string, unknown>
}

function sizeChangesOf(data: MockData, r: Resource): SizeChange[] {
  const st = data.console?.monitoring
  if (!st) return []
  const out: SizeChange[] = []
  for (const d of Object.values(st.decisions)) {
    if (d.kind !== 'applied' || d.resourceId !== r.id || d.snapshot.action !== 'resize' || !d.jobId) continue
    const job = data.jobs.find((j) => j.id === d.jobId)
    if (job?.status === 'succeeded' && job.endedAt) out.push({ at: Date.parse(job.endedAt), from: d.snapshot.current ?? {}, to: d.snapshot.proposed ?? {} })
  }
  return out.sort((a, b) => a.at - b.at)
}

/** What a percent series is multiplied by at `t`: the capacity before each resize so far over the capacity after it. */
function factorAt(changes: readonly SizeChange[], series: string, t: number): number {
  const field = series === 'cpuPercent' ? 'cpu' : series === 'memoryPercent' ? 'memoryGb' : null
  if (!field) return 1
  let f = 1
  for (const c of changes) {
    if (c.at > t) break
    const before = Number(c.from[field])
    const after = Number(c.to[field])
    if (before > 0 && after > 0) f *= before / after
  }
  return f
}

function rounded(v: number, kind: SeriesModel['kind']): number {
  return kind === 'count' ? Math.round(v) : Math.round(Math.min(100, v) * 10) / 10
}

/**
 * The points of a series from `from` to `to`, one per `step` on round multiples of it: each the
 * average of the step that ends at it. The provider reports only while the resource is alive.
 */
export function seriesPoints(m: SeriesModel, alive: { start: number; end: number } | null, from: number, to: number, step: number, factor: (t: number) => number = () => 1): MetricPoint[] {
  if (!alive || !(step > 0)) return []
  const out: MetricPoint[] = []
  const n = step > MINUTE ? 3 : 1
  for (let at = Math.ceil(from / step) * step; at <= to; at += step) {
    if (at < alive.start || at > alive.end) continue
    let sum = 0
    for (let i = 0; i < n; i++) {
      const t = at - (step * (i + 0.5)) / n
      sum += valueAt(m, t) * factor(t)
    }
    out.push({ at: new Date(at).toISOString(), value: rounded(sum / n, m.kind) })
  }
  return out
}

/** The step a read is answered at: the asked one, coarsened along the ladder until no series carries more than MAX_POINTS. */
export function stepFor(span: number, asked: number): number {
  if (span / asked <= MAX_POINTS) return asked
  return STEP_LADDER.find((s) => s >= asked && span / s <= MAX_POINTS) ?? DAY
}

/** Every series of a resource over a window, as the metrics read answers it; nothing after `now`. */
export function seriesOf(data: MockData, rt: ResourceType, r: Resource, from: number, to: number, step: number, now = to): MetricSeries[] {
  const alive = aliveOf(data, rt, r)
  const changes = sizeChangesOf(data, r)
  const until = Math.min(to, now)
  return seriesNamesOf(rt).map(({ name, unit: u }) => ({ name, unit: u, points: seriesPoints(modelOf(r, name), alive, from, until, step, (t) => factorAt(changes, name, t)) }))
}

/* ----------------------------------------------------------------------------
   Alarms: evaluated when read, against the average of their window
   ------------------------------------------------------------------------- */

const STATE_RANK: Record<AlarmState, number> = { alarm: 0, insufficient: 1, ok: 2 }

/** Firing alarms first, then the ones without data, then the quiet ones; by name within each. */
export function byUrgency(a: Alarm, b: Alarm): number {
  return STATE_RANK[a.state] - STATE_RANK[b.state] || (a.name ?? a.alarmId).localeCompare(b.name ?? b.alarmId) || a.alarmId.localeCompare(b.alarmId)
}

/** How far back an alarm's `stateSince` is looked for: never before its last change, nor more than two weeks back. */
const ALARM_LOOKBACK = 14 * DAY
const evaluationCache = new Map<string, Pick<Alarm, 'state' | 'stateSince' | 'lastValue'>>()

/**
 * An alarm as the drift service's minute-by-minute evaluation leaves it at `now`: its state from
 * the window's average (no points in the window is not enough data), since when it has been in
 * that state, and the average. The answer holds for the minute, so it is kept until the next.
 */
export function evaluateAlarm(data: MockData, a: StoredAlarm, now: number): Alarm {
  const changed = Date.parse(a.updatedAt)
  const end = Math.floor(now / MINUTE) * MINUTE
  const base = { ...a, lastEvaluatedAt: new Date(Math.max(changed, end)).toISOString() }
  const rt = typeOf(data, a.type)
  const r = rt ? resourceByWfrn(data, rt, a.wfrn) : undefined
  const alive = rt && r ? aliveOf(data, rt, r) : null
  if (!rt || !r || !alive || !seriesNamesOf(rt).some((s) => s.name === a.metric)) return { ...base, state: 'insufficient', stateSince: a.updatedAt, lastValue: null }
  const changes = sizeChangesOf(data, r)
  const key = [a.accountId, a.alarmId, a.updatedAt, a.wfrn, a.metric, a.comparison, a.threshold, a.windowMinutes, end, alive.start, alive.end, ...changes.map((c) => c.at)].join('|')
  const hit = evaluationCache.get(key)
  if (hit) return { ...base, ...hit }
  const model = modelOf(r, a.metric)
  const since = Math.max(Math.floor(changed / MINUTE) * MINUTE, end - ALARM_LOOKBACK)
  const w = Math.max(1, a.windowMinutes)
  // a value a minute from one window before `since` up to `end`, with running sums for every window's average
  const start = since - w * MINUTE
  const count = Math.max(0, Math.round((end - start) / MINUTE)) + 1
  const sums = new Float64Array(count + 1)
  const seen = new Int32Array(count + 1)
  for (let i = 0; i < count; i++) {
    const t = start + i * MINUTE
    const live = t >= alive.start && t <= alive.end
    sums[i + 1] = sums[i] + (live ? valueAt(model, t) * factorAt(changes, a.metric, t) : 0)
    seen[i + 1] = seen[i] + (live ? 1 : 0)
  }
  const averageAt = (i: number): number | null => {
    const lo = Math.max(0, i - w + 1)
    const n = seen[i + 1] - seen[lo]
    return n > 0 ? (sums[i + 1] - sums[lo]) / n : null
  }
  const stateOf = (avg: number | null): AlarmState => (avg === null ? 'insufficient' : (a.comparison === 'above' ? avg > a.threshold : avg < a.threshold) ? 'alarm' : 'ok')
  const last = count - 1
  const avg = averageAt(last)
  const state = stateOf(avg)
  const firstIndex = Math.round((since - start) / MINUTE)
  let first = last
  while (first > firstIndex && stateOf(averageAt(first - 1)) === state) first--
  const judged = { state, stateSince: new Date(Math.max(changed, start + first * MINUTE)).toISOString(), lastValue: avg === null ? null : Math.round(avg * 10) / 10 }
  if (evaluationCache.size > 2000) evaluationCache.clear()
  evaluationCache.set(key, judged)
  return { ...base, ...judged }
}

/** The account's alarms, evaluated at `now`, firing first. */
export function alarmsOf(data: MockData, accountId: string, now: number): Alarm[] {
  const st = monitoringStateOf(data)
  return Object.values(st.alarms)
    .filter((a) => a.accountId === accountId)
    .map((a) => evaluateAlarm(data, a, now))
    .sort(byUrgency)
}

const ALARM_ID = /^[A-Za-z0-9_-]{1,64}$/

/** What is wrong with an alarm's body, field by field (JSON pointers); with `series`, the metric must be one of them. */
export function alarmProblems(body: Record<string, unknown>, series?: readonly string[]): Problem[] {
  const problems: Problem[] = []
  const p = (code: string, field: string, message: string) => problems.push({ level: 'error', code, field, message })
  if (typeof body.wfrn !== 'string' || !body.wfrn.startsWith('wfrn:')) p('required', '/wfrn', 'Name the resource the alarm watches by its wfrn')
  if (typeof body.metric !== 'string' || !body.metric) p('required', '/metric', 'Choose the series the alarm watches')
  else if (series && !series.includes(body.metric)) p('enum', '/metric', `The series is one of ${series.join(', ')}`)
  if (body.comparison !== 'above' && body.comparison !== 'below') p('enum', '/comparison', 'The alarm fires above or below its threshold')
  if (typeof body.threshold !== 'number' || !Number.isFinite(body.threshold)) p('type', '/threshold', 'The threshold is a number')
  if (body.windowMinutes !== undefined && (typeof body.windowMinutes !== 'number' || !Number.isInteger(body.windowMinutes) || body.windowMinutes < 1 || body.windowMinutes > 1440)) p('range', '/windowMinutes', 'The window is a whole number of minutes, from 1 to 1440')
  if (body.name !== undefined && (typeof body.name !== 'string' || body.name.length > 128)) p('maxLength', '/name', 'The name is up to 128 characters')
  return problems
}

/* ----------------------------------------------------------------------------
   Rightsizing: a daily pass over the last 14 days
   ------------------------------------------------------------------------- */

/** When the last daily pass ran: 05:00 UTC today, or yesterday before that. */
export function passOf(now: number): number {
  return Math.floor((now - PASS_HOUR * HOUR) / DAY) * DAY + PASS_HOUR * HOUR
}

function p95(values: number[]): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)]
}

const utilisationCache = new Map<string, Utilisation>()

/** CPU and memory over a window at 15-minute steps: how many samples, the 95th percentiles and the highest values. */
export function utilisationOf(data: MockData, rt: ResourceType, r: Resource, from: number, to: number): Utilisation {
  const alive = aliveOf(data, rt, r)
  const changes = sizeChangesOf(data, r)
  const key = `${r.id}|${r.name}|${r.createdAt}|${from}|${to}|${alive ? `${alive.start}-${alive.end}` : 'none'}|${changes.map((c) => c.at).join(',')}`
  const hit = utilisationCache.get(key)
  if (hit) return hit
  const read = (series: string): number[] => {
    const out: number[] = []
    if (!alive) return out
    const model = modelOf(r, series)
    for (let t = Math.ceil(from / UTILISATION_STEP) * UTILISATION_STEP; t <= to; t += UTILISATION_STEP) if (t >= alive.start && t <= alive.end) out.push(Math.min(100, valueAt(model, t) * factorAt(changes, series, t)))
    return out
  }
  const cpu = read('cpuPercent')
  const memory = read('memoryPercent')
  const one = (v: number) => Math.round(v * 10) / 10
  const u: Utilisation = { from: new Date(from).toISOString(), to: new Date(to).toISOString(), samples: Math.min(cpu.length, memory.length) }
  if (cpu.length) Object.assign(u, { cpuP95: one(p95(cpu)), cpuMax: one(Math.max(...cpu)) })
  if (memory.length) Object.assign(u, { memoryP95: one(p95(memory)), memoryMax: one(Math.max(...memory)) })
  if (utilisationCache.size > 5000) utilisationCache.clear()
  utilisationCache.set(key, u)
  return u
}

/** The size fields a recommendation speaks of: a vm's CPUs and memory, a database's tier. */
export function sizeOf(attributes: Record<string, unknown> | undefined): Record<string, unknown> {
  const a = attributes ?? {}
  if (typeof a.cpu === 'number' && typeof a.memoryGb === 'number') return { cpu: a.cpu, memoryGb: a.memoryGb }
  if (typeof a.tier === 'string') return { tier: a.tier }
  return {}
}

/**
 * The rules of the pass: idle when the 95th percentiles of CPU and memory are both under 5%
 * (delete it); oversized when both are under 30% (resize to the smallest vm size, no larger than
 * the machine in either, that keeps both under 60%, when that costs less); otherwise nothing.
 * Fewer than three days of samples is nothing.
 */
export function judge(rt: ResourceType, u: Utilisation, current: Record<string, unknown>): { kind: RecommendationKind; action: 'delete' | 'resize'; proposed?: Record<string, unknown> } | null {
  if (u.samples < MIN_SAMPLES || u.cpuP95 === undefined || u.memoryP95 === undefined) return null
  const { cpuP95, memoryP95 } = u
  if (cpuP95 < IDLE_BELOW && memoryP95 < IDLE_BELOW) return { kind: 'idle', action: 'delete' }
  if (cpuP95 >= OVERSIZED_BELOW || memoryP95 >= OVERSIZED_BELOW) return null
  const cpu = Number(current.cpu)
  const memoryGb = Number(current.memoryGb)
  if (!(cpu > 0 && memoryGb > 0)) return null
  // a resize only shrinks: a rung with more of either than the machine has now is no saving to offer
  const fits = VM_LADDER.find((s) => s.cpu <= cpu && s.memoryGb <= memoryGb && (cpuP95 * cpu) / s.cpu < TARGET_BELOW && (memoryP95 * memoryGb) / s.memoryGb < TARGET_BELOW)
  if (!fits || (fits.cpu === cpu && fits.memoryGb === memoryGb)) return null
  const now = priceOf(rt, current)?.monthly ?? 0
  const then = priceOf(rt, fits)?.monthly ?? now
  if (then >= now) return null
  return { kind: 'oversized', action: 'resize', proposed: { ...fits } }
}

function money(amount: number): Money {
  return { amount: Math.round(amount * 100) / 100, currency: 'USD', basis: 'list-prices' }
}

type View = Omit<Recommendation, 'can'>

/**
 * The account's recommendations at `now`: the last pass's (a dismissal laid over one until its
 * date), the applied ones as they stood when applied, and the dismissed ones whose resource no
 * longer qualifies (superseded). A resource applied since the last pass waits for the next one,
 * which may open a fresh recommendation for it.
 */
export function recommendationsOf(data: MockData, accountId: string, now: number): View[] {
  const st = monitoringStateOf(data)
  const accounts = data.directory.accounts.map((a) => a.accountId)
  const pass = passOf(now)
  const from = pass - RIGHTSIZING_WINDOW
  const decisions = Object.values(st.decisions)
  const out: View[] = []
  const seen = new Set<string>()
  for (const r of data.resources) {
    const rt = typeOf(data, r.type)
    if (!rt || !platformObserveOf(rt).metrics || accountOfResource(r, accounts) !== accountId || (lifecycleOf(r.status, rt.console?.lifecycle) ?? 'active') === 'deleted') continue
    const applied = decisions.filter((d) => d.kind === 'applied' && d.resourceId === r.id)
    if (applied.some((d) => d.at >= pass)) continue
    const u = utilisationOf(data, rt, r, from, pass)
    const current = sizeOf(r.attributes)
    const verdict = judge(rt, u, current)
    if (!verdict) continue
    const recommendationId = `rec_${idFrom(`${r.id}:${applied.length}`, 10)}`
    const cost = priceOf(rt, r.attributes ?? {})?.monthly ?? 0
    const after = verdict.proposed ? (priceOf(rt, { ...r.attributes, ...verdict.proposed })?.monthly ?? cost) : 0
    const opened = Math.min(pass, Math.max(Date.parse(r.createdAt) + 3 * DAY, pass - (seedOf(`opened:${r.id}`) % 4) * DAY))
    const view: View = {
      recommendationId,
      wfrn: wfrnOfResource(data, { region: data.regionName }, rt, r),
      accountId,
      region: data.regionName,
      type: rt.name,
      name: r.name ?? r.id,
      kind: verdict.kind,
      action: verdict.action,
      status: 'open',
      current,
      ...(verdict.proposed ? { proposed: verdict.proposed } : {}),
      utilisation: u,
      monthlyCost: money(cost),
      monthlySaving: money(cost - after),
      createdAt: new Date(opened).toISOString(),
      updatedAt: new Date(pass).toISOString(),
    }
    const decided = st.decisions[recommendationId]
    if (decided?.kind === 'dismissed' && (!decided.until || Date.parse(decided.until) > now)) Object.assign(view, { status: 'dismissed', decidedBy: decided.by, reason: decided.reason, dismissedUntil: decided.until, updatedAt: new Date(decided.at).toISOString() })
    seen.add(recommendationId)
    out.push(view)
  }
  for (const d of decisions) {
    if (seen.has(d.recommendationId) || d.snapshot.accountId !== accountId) continue
    const kept = { ...d.snapshot, decidedBy: d.by, reason: d.reason, updatedAt: new Date(d.at).toISOString() }
    out.push(d.kind === 'applied' ? { ...kept, status: 'applied', jobId: d.jobId } : { ...kept, status: 'superseded', dismissedUntil: d.until })
  }
  return out.sort((a, b) => (b.monthlySaving?.amount ?? 0) - (a.monthlySaving?.amount ?? 0) || (a.name ?? '').localeCompare(b.name ?? '') || a.recommendationId.localeCompare(b.recommendationId))
}

/** Why the console cannot carry a recommendation out, or null when it can: a resize needs an update that sets every proposed field, a delete a delete workflow. */
export function notApplicable(data: MockData, rt: ResourceType, v: Pick<View, 'action' | 'proposed'>): string | null {
  if (v.action === 'delete') return verbEntryOf(data, rt, 'delete') ? null : `No workflow deletes ${rt.plural}; the provider publishes none in its catalog`
  const mutable = updateSchemaOf(rt).properties ?? {}
  const fields = Object.keys(v.proposed ?? {})
  const missing = fields.filter((k) => !(k in mutable))
  if (fields.length === 0) return 'The recommendation proposes no size'
  return missing.length ? `The update workflow of ${rt.plural} does not set ${missing.join(' and ')}` : null
}

/* ----------------------------------------------------------------------------
   Commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

/** Applies a journaled `alarm.*` or `recommendation.*` command, when it runs and when the journal replays it. */
export function applyMonitoringCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = monitoringStateOf(data)
  const body = (cmd.body && typeof cmd.body === 'object' ? cmd.body : {}) as Record<string, unknown>
  const at = new Date(cmd.at).toISOString()
  switch (cmd.kind) {
    case 'alarm.put': {
      const alarmId = String(body.alarmId ?? '')
      const input = (body.input && typeof body.input === 'object' ? body.input : {}) as Record<string, unknown>
      const key = `${cmd.accountId}/${alarmId}`
      const before = st.alarms[key]
      const name = typeof input.name === 'string' ? input.name.trim() : ''
      st.alarms[key] = {
        alarmId,
        accountId: cmd.accountId,
        wfrn: String(input.wfrn),
        type: cmd.type,
        ...(name ? { name } : {}),
        metric: String(input.metric),
        comparison: input.comparison === 'below' ? 'below' : 'above',
        threshold: Number(input.threshold),
        windowMinutes: typeof input.windowMinutes === 'number' ? input.windowMinutes : 10,
        createdBy: before?.createdBy ?? cmd.userId,
        createdAt: before?.createdAt ?? at,
        updatedAt: at,
      }
      return { status: before ? 200 : 201, body: evaluateAlarm(data, st.alarms[key], cmd.at) }
    }
    case 'alarm.delete': {
      const key = `${cmd.accountId}/${String(body.alarmId ?? '')}`
      if (!st.alarms[key]) return { status: 404, body: { error: 'alarm.not-found' } }
      delete st.alarms[key]
      return { status: 204 }
    }
    case 'recommendation.apply': {
      const id = String(body.recommendationId ?? '')
      const v = recommendationsOf(data, cmd.accountId, cmd.at).find((x) => x.recommendationId === id && x.status === 'open')
      const rt = typeOf(data, cmd.type)
      const r = rt && v ? resourceByWfrn(data, rt, v.wfrn) : undefined
      if (!v || !rt || !r) return { status: 409, body: { error: 'recommendation.closed' } }
      const requestId = cmd.ids.requestId ?? `creq_${idFrom(`${cmd.userId}:${cmd.at}:${id}`, 10)}`
      const jobId = cmd.ids.jobId ?? `job_${idFrom(requestId, 10)}`
      cmd.ids = { ...cmd.ids, requestId, jobId }
      const given = typeof body.reason === 'string' && body.reason.trim() ? body.reason.trim() : undefined
      const reason = given ?? (v.action === 'delete' ? 'Idle for 14 days: rightsizing recommends deleting it' : `Oversized for 14 days: rightsizing recommends ${sizeLine(v.proposed)}`)
      const labels = { 'rightsizing-recommendation': id }
      const entry = v.action === 'delete' ? verbEntryOf(data, rt, 'delete') : undefined
      const subject = entry ? subjectFieldOf(entry, rt) : undefined
      const common = { at: cmd.at, userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: rt.name, wfrn: v.wfrn, ids: { requestId, jobId } }
      const inner: ConsoleCommand =
        v.action === 'resize'
          ? { ...common, kind: 'update', body: { input: { ...v.proposed }, reason, labels } }
          : { ...common, kind: 'verb', verb: 'delete', body: { input: { ...(subject ? { [subject]: r.id } : {}), reason }, confirm: true, reason, labels } }
      const started = applyCommand(data, inner)
      if (started.status >= 400) return started
      st.decisions[id] = { kind: 'applied', recommendationId: id, resourceId: r.id, at: cmd.at, by: cmd.userId, reason: given, jobId, snapshot: v }
      return { status: 202, body: { recommendationId: id, jobId } }
    }
    case 'recommendation.dismiss': {
      const id = String(body.recommendationId ?? '')
      const v = recommendationsOf(data, cmd.accountId, cmd.at).find((x) => x.recommendationId === id && x.status === 'open')
      const rt = typeOf(data, cmd.type)
      const r = rt && v ? resourceByWfrn(data, rt, v.wfrn) : undefined
      if (!v || !r) return { status: 409, body: { error: 'recommendation.closed' } }
      const reason = typeof body.reason === 'string' && body.reason.trim() ? body.reason.trim() : undefined
      const until = typeof body.until === 'string' ? new Date(Date.parse(body.until)).toISOString() : undefined
      st.decisions[id] = { kind: 'dismissed', recommendationId: id, resourceId: r.id, at: cmd.at, by: cmd.userId, reason, until, snapshot: v }
      const dismissed: Recommendation = { ...v, status: 'dismissed', decidedBy: cmd.userId, reason, dismissedUntil: until, updatedAt: at, can: [] }
      return { status: 200, body: dismissed }
    }
    default:
      return { status: 404, body: { error: `No command ${cmd.kind}` } }
  }
}

function sizeLine(size: Record<string, unknown> | undefined): string {
  return Object.entries(size ?? {})
    .map(([k, v]) => `${k} ${String(v)}`)
    .join(', ')
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { status: 200, body: { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null }, headers: { 'X-Total-Count': String(items.length) } }
}

function invalid(problems: Problem[]): RouteAnswer {
  return { status: 400, body: { error: 'validation.failed', detail: `${problems.length === 1 ? 'One field needs' : `${problems.length} fields need`} attention`, problems } }
}

const notAllowed = (method: string, path: string): RouteAnswer => ({ status: 405, body: { error: `${method} is not allowed on ${path}` } })

/** Answers the metrics, alarms and recommendations paths, or null for any other path. */
export function monitoringRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const now = ctx.now
  const accounts = data.directory.accounts.map((a) => a.accountId)
  const can = (action: string, resource: string) => authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  const refused = (d: Decision): RouteAnswer => ({ status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(`${now}:${path}`, 8)}` } })
  const listable = () => new Map(data.resourceTypes.map((rt) => [rt.name, can(`${serviceOf(rt)}:list`, wfrnPatternOf(data, s, rt)).allowed]))
  const command = (kind: `alarm.${string}` | `recommendation.${string}`, type: string, wfrn: string, body: Record<string, unknown>) => journaled(data, ctx, { at: now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type, wfrn, body, ids: {} })

  /** the resource a wfrn names in this account, of the type its type segment says (and `{type}`, when the path names one) */
  const resolve = (typeName: string | undefined, wfrn: string): { rt: ResourceType; r: Resource; wfrn: string } | RouteAnswer => {
    const parts = partsOfWfrn(wfrn)
    const rt = typeOf(data, typeName ?? parts?.type ?? '')
    if (!rt) return { status: 404, body: { error: 'resource.not-found', detail: `No resource type ${typeName ?? parts?.type ?? ''}` } }
    const r = parts?.type === rt.name ? resourceByWfrn(data, rt, wfrn) : undefined
    if (!r || accountOfResource(r, accounts) !== s.accountId) return { status: 404, body: { error: 'resource.not-found', detail: `Nothing lives at ${wfrn} in this account` } }
    return { rt, r, wfrn: wfrnOfResource(data, s, rt, r) }
  }
  const isAnswer = (x: object): x is RouteAnswer => 'status' in x

  /* a resource's metrics and its alarms */
  let m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)\/(metrics|alarms)$/.exec(path)
  if (m) {
    if (method !== 'GET') return notAllowed(method, path)
    const found = resolve(m[1], decodeURIComponent(m[2]))
    if (isAnswer(found)) return found
    const { rt, r, wfrn } = found
    const service = serviceOf(rt)
    if (m[3] === 'alarms') {
      const d = can(`${service}:get`, wfrn)
      if (!d.allowed) return refused(d)
      return { status: 200, body: { items: alarmsOf(data, s.accountId, now).filter((a) => a.wfrn === wfrn) } }
    }
    const d = can(`${service}:observe`, wfrn)
    if (!d.allowed) return refused(d)
    if (!platformObserveOf(rt).metrics) return { status: 404, body: { error: 'metrics.notSupported', detail: `${titleOf(rt)} have no metrics read: their provider publishes none` } }
    const problems: Problem[] = []
    const toParam = params.get('to')
    const fromParam = params.get('from')
    const to = toParam ? Date.parse(toParam) : now
    const from = fromParam ? Date.parse(fromParam) : to - DAY
    if (Number.isNaN(to)) problems.push({ level: 'error', code: 'format', field: '/to', message: 'to is a date and time (ISO 8601)' })
    if (fromParam && Number.isNaN(from)) problems.push({ level: 'error', code: 'format', field: '/from', message: 'from is a date and time (ISO 8601)' })
    if (!problems.length && from >= to) problems.push({ level: 'error', code: 'range', field: '/from', message: 'from comes before to' })
    if (!problems.length && to - from > MAX_RANGE) problems.push({ level: 'error', code: 'range', field: '/from', message: 'One read covers at most 31 days' })
    const stepParam = params.get('step')
    const asked = stepParam ? durationMs(stepParam) : to - from <= DAY ? 5 * MINUTE : HOUR
    if (asked === null || asked < MINUTE) problems.push({ level: 'error', code: 'format', field: '/step', message: 'step is an ISO-8601 duration of at least PT1M' })
    if (problems.length || asked === null) return invalid(problems)
    // the provider's observe budget: so many reads a minute for the account
    const st = monitoringStateOf(data)
    const minute = Math.floor(now / MINUTE)
    if (st.budget.minute !== minute) st.budget = { minute, used: {} }
    st.budget.used[s.accountId] = (st.budget.used[s.accountId] ?? 0) + 1
    if (st.budget.used[s.accountId] > st.budgetPerMinute) {
      const retry = Math.max(1, Math.ceil(((minute + 1) * MINUTE - now) / 1000))
      return { status: 429, headers: { 'Retry-After': String(retry) }, body: { error: 'observe.budget', detail: `The provider's observe budget for this account is used up for this minute; it refills in ${retry} seconds`, retryAfter: retry } }
    }
    const step = stepFor(to - from, asked)
    const answer: ResourceMetrics = { wfrn, from: new Date(from).toISOString(), to: new Date(to).toISOString(), step: isoDuration(step), series: seriesOf(data, rt, r, from, to, step, now), provider: rt.observe?.provider, action: rt.observe?.metrics?.action }
    return { status: 200, body: answer }
  }

  /* the account's alarms */
  if (path === '/console/v1/alarms') {
    if (method !== 'GET') return notAllowed(method, path)
    const state = params.get('state')
    if (state && !['ok', 'alarm', 'insufficient'].includes(state)) return invalid([{ level: 'error', code: 'enum', field: '/state', message: 'state is ok, alarm or insufficient' }])
    const wfrn = params.get('wfrn')
    const mayList = listable()
    return pageOf(
      alarmsOf(data, s.accountId, now).filter((a) => mayList.get(a.type) && (!state || a.state === state) && (!wfrn || a.wfrn === wfrn)),
      params,
    )
  }
  m = /^\/console\/v1\/alarms\/([^/:]+)$/.exec(path)
  if (m) {
    const alarmId = decodeURIComponent(m[1])
    if (method !== 'PUT' && method !== 'DELETE') return notAllowed(method, path)
    if (!ALARM_ID.test(alarmId)) return invalid([{ level: 'error', code: 'pattern', field: '/alarmId', message: 'An alarm id is 1 to 64 letters, digits, dashes and underscores' }])
    const existing = monitoringStateOf(data).alarms[`${s.accountId}/${alarmId}`]
    const serviceOfType = (name: string) => {
      const t = typeOf(data, name)
      return t ? serviceOf(t) : 'platform'
    }
    if (method === 'DELETE') {
      if (!existing) return { status: 404, body: { error: 'alarm.not-found', detail: `No alarm ${alarmId} in this account` } }
      const d = can(`${serviceOfType(existing.type)}:update`, existing.wfrn)
      if (!d.allowed) return refused(d)
      return command('alarm.delete', existing.type, existing.wfrn, { alarmId })
    }
    const body = ctx.body ?? {}
    if (typeof body.wfrn !== 'string' || !body.wfrn.startsWith('wfrn:')) return invalid(alarmProblems(body))
    const found = resolve(undefined, body.wfrn)
    if (isAnswer(found)) return found
    const { rt, wfrn } = found
    const d = can(`${serviceOf(rt)}:update`, wfrn)
    if (!d.allowed) return refused(d)
    if (existing && existing.wfrn !== wfrn) {
      const d2 = can(`${serviceOfType(existing.type)}:update`, existing.wfrn)
      if (!d2.allowed) return refused(d2)
    }
    if (!platformObserveOf(rt).metrics) return { status: 404, body: { error: 'metrics.notSupported', detail: `${titleOf(rt)} have no metrics read, so there is nothing to watch` } }
    const problems = alarmProblems(body, seriesNamesOf(rt).map((x) => x.name))
    if (problems.length) return invalid(problems)
    const input = { wfrn, metric: body.metric, comparison: body.comparison, threshold: body.threshold, windowMinutes: body.windowMinutes ?? 10, ...(typeof body.name === 'string' ? { name: body.name } : {}) }
    return command('alarm.put', rt.name, wfrn, { alarmId, input })
  }

  /* recommendations */
  const withCan = (v: View): Recommendation => {
    const rt = typeOf(data, v.type)
    if (!rt || v.status !== 'open') return { ...v, can: [] }
    const service = serviceOf(rt)
    const out: Recommendation['can'] = []
    if (!notApplicable(data, rt, v) && can(`${service}:${v.action === 'delete' ? 'delete' : 'update'}`, v.wfrn).allowed) out.push('apply')
    if (can(`${service}:update`, v.wfrn).allowed) out.push('dismiss')
    return { ...v, can: out }
  }
  if (path === '/console/v1/recommendations') {
    if (method !== 'GET') return notAllowed(method, path)
    const problems: Problem[] = []
    const kind = params.get('kind')
    const status = params.get('status') || 'open'
    if (kind && kind !== 'idle' && kind !== 'oversized') problems.push({ level: 'error', code: 'enum', field: '/kind', message: 'kind is idle or oversized' })
    if (!['open', 'applied', 'dismissed', 'superseded'].includes(status)) problems.push({ level: 'error', code: 'enum', field: '/status', message: 'status is open, applied, dismissed or superseded' })
    if (problems.length) return invalid(problems)
    const type = params.get('type')
    const wfrn = params.get('wfrn')
    const mayList = listable()
    return pageOf(
      recommendationsOf(data, s.accountId, now)
        .filter((v) => mayList.get(v.type) && v.status === (status as RecommendationStatus) && (!kind || v.kind === kind) && (!type || v.type === type) && (!wfrn || v.wfrn === wfrn))
        .map(withCan),
      params,
    )
  }
  if (path === '/console/v1/recommendations/summary') {
    if (method !== 'GET') return notAllowed(method, path)
    const mayList = listable()
    const open = recommendationsOf(data, s.accountId, now).filter((v) => v.status === 'open' && mayList.get(v.type))
    const summary: RecommendationsSummary = {
      open: open.length,
      byKind: { idle: open.filter((v) => v.kind === 'idle').length, oversized: open.filter((v) => v.kind === 'oversized').length },
      monthlySaving: money(open.reduce((n, v) => n + (v.monthlySaving?.amount ?? 0), 0)),
      lastPassAt: new Date(passOf(now)).toISOString(),
    }
    return { status: 200, body: summary }
  }
  m = /^\/console\/v1\/recommendations\/([^/:]+)(?::(apply|dismiss))?$/.exec(path)
  if (m) {
    const id = decodeURIComponent(m[1])
    const v = recommendationsOf(data, s.accountId, now).find((x) => x.recommendationId === id)
    const rt = v ? typeOf(data, v.type) : undefined
    if (!v || !rt) return { status: 404, body: { error: 'recommendation.not-found', detail: `No recommendation ${id} in this account` } }
    const service = serviceOf(rt)
    if (!m[2]) {
      if (method !== 'GET') return notAllowed(method, path)
      const d = can(`${service}:get`, v.wfrn)
      if (!d.allowed) return refused(d)
      return { status: 200, body: withCan(v) }
    }
    if (method !== 'POST') return notAllowed(method, path)
    const body = ctx.body ?? {}
    const decide = m[2] === 'apply' ? `${service}:${v.action === 'delete' ? 'delete' : 'update'}` : `${service}:update`
    const d = can(decide, v.wfrn)
    if (!d.allowed) return refused(d)
    // a retry of a submission answers what the first one did, before the state it changed is judged
    const key = ctx.headers['idempotency-key']
    if (key && data.console?.replies[key]) return command(m[2] === 'apply' ? 'recommendation.apply' : 'recommendation.dismiss', rt.name, v.wfrn, { recommendationId: id })
    const closed = (): RouteAnswer => ({ status: 409, body: { error: 'recommendation.closed', detail: `This recommendation is ${v.status} already${v.jobId ? `, through request ${v.jobId}` : ''}`, status: v.status, jobId: v.jobId } })
    const tooLong = body.reason !== undefined && (typeof body.reason !== 'string' || body.reason.length > 1000)
    if (m[2] === 'dismiss') {
      const problems: Problem[] = []
      if (tooLong) problems.push({ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is up to 1000 characters' })
      if (body.until !== undefined && (typeof body.until !== 'string' || Number.isNaN(Date.parse(body.until)) || Date.parse(body.until) <= now)) problems.push({ level: 'error', code: 'format', field: '/until', message: 'until is a date and time in the future (ISO 8601)' })
      if (problems.length) return invalid(problems)
      if (v.status !== 'open') return closed()
      return command('recommendation.dismiss', rt.name, v.wfrn, { recommendationId: id, ...(typeof body.reason === 'string' ? { reason: body.reason } : {}), ...(typeof body.until === 'string' ? { until: body.until } : {}) })
    }
    if (tooLong) return invalid([{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is up to 1000 characters' }])
    if (v.status !== 'open') return closed()
    const why = notApplicable(data, rt, v)
    if (why) return { status: 409, body: { error: 'recommendation.notApplicable', detail: why } }
    const r = resourceByWfrn(data, rt, v.wfrn)
    if (!r) return { status: 404, body: { error: 'resource.not-found', detail: `Nothing lives at ${v.wfrn} any more` } }
    if (v.action === 'delete') {
      if (body.confirm !== true) return { status: 400, body: { error: 'confirm.required', detail: 'Deleting the resource is destructive: send confirm: true to run it', problems: [{ level: 'error', code: 'confirm.required', field: '/confirm', message: 'Deleting the resource is destructive: confirm it by typing its name' }] } }
      const judged = previewVerb(data, s, rt, r, 'delete', verbEntryOf(data, rt, 'delete'), {}, now)
      const denied = judged.policy.rules?.filter((x) => x.result === 'deny') ?? []
      if (denied.length) return { status: 409, body: { error: 'recommendation.notApplicable', detail: denied.map((x) => x.message).join('; ') } }
    }
    if (data.console?.inFlight.some((f) => f.resourceId === r.id)) return { status: 409, body: { error: 'recommendation.changing', detail: 'A request is changing this resource now; apply the recommendation once it has finished' } }
    return command('recommendation.apply', rt.name, v.wfrn, { recommendationId: id, ...(typeof body.reason === 'string' ? { reason: body.reason } : {}), ...(body.confirm === true ? { confirm: true } : {}) })
  }

  return null
}
