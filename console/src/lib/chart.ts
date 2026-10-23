/*
 * The pure parts of the Monitoring tab's line charts: scales, the value and time ticks, the
 * summary every chart states in words (latest, average, highest), the point nearest the
 * pointer or the keyboard, and where a series breaks for missing points. The SVG drawing
 * (pages/resource/MetricChart.tsx) only turns these into marks.
 */

export interface ChartPoint {
  /** milliseconds since the epoch */
  t: number
  v: number
}

/** A linear map from a domain onto a range, with its inverse. */
export function linearScale(d0: number, d1: number, r0: number, r1: number): { (x: number): number; invert: (y: number) => number } {
  const span = d1 - d0 || 1
  const scale = (x: number) => r0 + ((x - d0) / span) * (r1 - r0)
  scale.invert = (y: number) => d0 + ((y - r0) / ((r1 - r0) || 1)) * span
  return scale
}

/** A round step near range/count: 1, 2, 2.5 or 5 times a power of ten. */
export function niceStep(range: number, count: number): number {
  const rough = Math.abs(range) / Math.max(1, count)
  if (!Number.isFinite(rough) || rough === 0) return 1
  const pow = 10 ** Math.floor(Math.log10(rough))
  const n = rough / pow
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * pow
}

/** Round ticks from `min` to at least `max`, about `count` of them; the first at or below min, the last at or above max. */
export function valueTicks(min: number, max: number, count = 4): number[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return []
  if (max <= min) max = min + 1
  const step = niceStep(max - min, count)
  const start = Math.floor(min / step) * step
  const end = Math.ceil(max / step) * step
  const out: number[] = []
  for (let v = start; v <= end + step * 1e-6; v += step) out.push(Number(v.toFixed(10)))
  return out
}

/**
 * The value axis of a series: from zero up to a round number past the highest value and every
 * threshold drawn on it, so a threshold line is always on the plot. A percent axis stops at 100
 * unless something lies above it.
 */
export function valueDomain(values: readonly number[], opts: { unit?: string; include?: readonly number[]; ticks?: number } = {}): { min: number; max: number; ticks: number[] } {
  const finite = [...values, ...(opts.include ?? [])].filter((v) => Number.isFinite(v))
  const lo = Math.min(0, ...finite)
  let hi = finite.length ? Math.max(...finite) : 0
  const percent = opts.unit === 'percent' || opts.unit === '%'
  if (hi <= lo) hi = percent ? 100 : lo + 1
  // a little headroom, so the line never runs along the top edge
  const ticks = valueTicks(lo, hi * 1.05, opts.ticks ?? 4)
  const max = ticks[ticks.length - 1] ?? hi
  if (percent && max > 100 && hi <= 100) return { min: lo, max: 100, ticks: valueTicks(lo, 100, opts.ticks ?? 4) }
  return { min: ticks[0] ?? lo, max, ticks }
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** An ISO-8601 duration in milliseconds (`PT5M`, `PT1H`, `P1D`), or null when it is not one. */
export function durationMs(iso: string | undefined | null): number | null {
  const m = iso ? /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso) : null
  if (!m || iso === 'P' || iso === 'PT') return null
  const [, d, h, min, s] = m.map((x) => Number(x ?? 0))
  return ((d * 24 + h) * 60 + min) * MINUTE + s * 1000
}

/** Milliseconds as the shortest ISO-8601 duration: 300000 is `PT5M`, a day `P1D`. */
export function isoDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  const days = Math.floor(s / 86_400)
  const rest = s % 86_400
  const h = Math.floor(rest / 3600)
  const m = Math.floor((rest % 3600) / 60)
  const sec = rest % 60
  const time = `${h ? `${h}H` : ''}${m ? `${m}M` : ''}${sec ? `${sec}S` : ''}`
  const out = `P${days ? `${days}D` : ''}${time ? `T${time}` : ''}`
  return out === 'P' ? 'PT0S' : out
}

/** The intervals a time axis may tick at, finest first. */
export const TIME_STEPS: readonly number[] = [5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 7 * DAY]

/**
 * Ticks for a time axis between `from` and `to`: the finest interval that gives at most
 * `max` ticks, each on a round local time (`offsetMinutes` east of UTC, as the browser's
 * zone is: a day starts at local midnight).
 */
export function timeTicks(from: number, to: number, max = 5, offsetMinutes = 0): number[] {
  if (!(to > from)) return []
  const span = to - from
  const step = TIME_STEPS.find((s) => span / s <= max) ?? TIME_STEPS[TIME_STEPS.length - 1]
  const offset = offsetMinutes * MINUTE
  const first = Math.ceil((from + offset) / step) * step - offset
  const out: number[] = []
  for (let t = first; t <= to; t += step) out.push(t)
  return out
}

const formatters = new Map<string, Intl.DateTimeFormat>()

/** The parts of a moment in `timeZone` (the browser's by default), from one formatter kept per zone. */
function partsOf(t: number, timeZone?: string): Record<string, string> {
  const key = timeZone ?? ''
  let f = formatters.get(key)
  if (!f) {
    f = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone })
    formatters.set(key, f)
  }
  const out: Record<string, string> = {}
  for (const p of f.formatToParts(t)) out[p.type] = p.value
  return out
}

/** How a tick reads: the time of day for a day or less, the weekday and date beyond (in `timeZone`, the browser's by default). */
export function timeLabel(t: number, span: number, timeZone?: string): string {
  const p = partsOf(t, timeZone)
  return span <= 36 * HOUR ? `${p.hour}:${p.minute}` : `${p.weekday} ${p.day}`
}

/** A point's time for a readout or a table row: "Fri 23 Oct, 14:05", spelt the same by every browser. */
export function pointLabel(t: number, timeZone?: string): string {
  const p = partsOf(t, timeZone)
  return `${p.weekday} ${p.day} ${p.month}, ${p.hour}:${p.minute}`
}

export interface SeriesSummary {
  count: number
  latest?: ChartPoint
  average?: number
  highest?: ChartPoint
  lowest?: ChartPoint
}

/** What every chart says in words: the latest point, the average and the highest (the first of equals). */
export function summarise(points: readonly ChartPoint[]): SeriesSummary {
  const finite = points.filter((p) => Number.isFinite(p.v))
  if (finite.length === 0) return { count: 0 }
  let sum = 0
  let highest = finite[0]
  let lowest = finite[0]
  for (const p of finite) {
    sum += p.v
    if (p.v > highest.v) highest = p
    if (p.v < lowest.v) lowest = p
  }
  return { count: finite.length, latest: finite[finite.length - 1], average: sum / finite.length, highest, lowest }
}

/** The index of the point nearest `t` in points sorted by time; -1 when there are none. */
export function nearestIndex(points: readonly ChartPoint[], t: number): number {
  if (points.length === 0) return -1
  let lo = 0
  let hi = points.length - 1
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (points[mid].t < t) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(points[lo - 1].t - t) <= Math.abs(points[lo].t - t)) return lo - 1
  return lo
}

/** The runs a line is drawn in: a gap longer than one and a half steps (the provider missed points) breaks it. */
export function runsOf(points: readonly ChartPoint[], stepMs: number): ChartPoint[][] {
  const runs: ChartPoint[][] = []
  let run: ChartPoint[] = []
  for (const p of points) {
    if (!Number.isFinite(p.v)) {
      if (run.length) runs.push(run)
      run = []
      continue
    }
    const last = run[run.length - 1]
    if (last && stepMs > 0 && p.t - last.t > stepMs * 1.5) {
      runs.push(run)
      run = []
    }
    run.push(p)
  }
  if (run.length) runs.push(run)
  return runs
}

/** An SVG path through a run of points. */
export function pathOf(run: readonly ChartPoint[], x: (t: number) => number, y: (v: number) => number): string {
  return run.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join(' ')
}

/** The keyboard's next point: arrows step one, Page keys a tenth of the points, Home and End the ends; null for any other key. */
export function nextIndex(key: string, index: number, count: number): number | null {
  if (count === 0) return null
  const page = Math.max(1, Math.round(count / 10))
  const clamp = (i: number) => Math.max(0, Math.min(count - 1, i))
  switch (key) {
    case 'ArrowLeft':
    case 'ArrowDown':
      return clamp(index - 1)
    case 'ArrowRight':
    case 'ArrowUp':
      return clamp(index + 1)
    case 'PageDown':
      return clamp(index - page)
    case 'PageUp':
      return clamp(index + page)
    case 'Home':
      return 0
    case 'End':
      return count - 1
    default:
      return null
  }
}
