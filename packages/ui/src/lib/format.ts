/**
 * Formatting helpers for what the platform shows everywhere: when something
 * happened, how long it took, how much, how big. Pure, locale-pinned (en) so
 * the same input renders the same string in every browser and in tests.
 */
export type DateInput = string | number | Date

const MINUTE = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000
const SKEW_MS = 5_000

function toMs(input: DateInput): number {
  if (input instanceof Date) return input.getTime()
  if (typeof input === 'number') return input
  return new Date(input).getTime()
}

/** "just now", "3m ago", "2h ago", "5d ago", "in 10m"; older than a month falls back to the short date. */
export function relativeTime(input: DateInput, now: DateInput = Date.now()): string {
  const t = toMs(input)
  const n = toMs(now)
  if (Number.isNaN(t)) return '—'
  const diff = n - t
  const abs = Math.abs(diff)
  const past = diff >= 0
  const fmt = (v: number, unit: string) => (past ? `${v}${unit} ago` : `in ${v}${unit}`)
  // a few seconds ahead is the server's clock running ahead of this one, not the future
  if (abs < 45_000) return past || abs < SKEW_MS ? 'just now' : 'in a moment'
  if (abs < HOUR) return fmt(Math.max(1, Math.round(abs / MINUTE)), 'm')
  if (abs < DAY) return fmt(Math.round(abs / HOUR), 'h')
  if (abs < 7 * DAY) return fmt(Math.round(abs / DAY), 'd')
  if (abs < 30 * DAY) return fmt(Math.round(abs / (7 * DAY)), 'w')
  return shortDate(t, { now: n })
}

/** "340ms", "1.2s", "45s", "1m 12s", "2h 5m", "3d 4h". */
export function duration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return '—'
  const value = Math.max(0, ms)
  if (value < 1000) return `${Math.round(value)}ms`
  const seconds = value / 1000
  if (seconds < 60) return `${seconds < 10 ? seconds.toFixed(1).replace(/\.0$/, '') : Math.round(seconds)}s`
  const total = Math.round(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  if (m < 60) return s ? `${m}m ${s}s` : `${m}m`
  const h = Math.floor(m / 60)
  const rm = m % 60
  if (h < 24) return rm ? `${h}h ${rm}m` : `${h}h`
  const d = Math.floor(h / 24)
  const rh = h % 24
  return rh ? `${d}d ${rh}h` : `${d}d`
}

export interface NumberOptions {
  /** maximum fraction digits (default: 0 for integers, 1–2 for fractions) */
  digits?: number
  /** "1.2K", "3.4M" */
  compact?: boolean
  locale?: string
}

/** Grouped number: 12,345 · 0.42 · compact 1.2K. */
export function formatNumber(n: number | null | undefined, opts: NumberOptions = {}): string {
  if (n == null || !Number.isFinite(n)) return '—'
  const { digits, compact = false, locale = 'en-US' } = opts
  const maximumFractionDigits =
    digits ?? (compact ? 1 : Number.isInteger(n) ? 0 : Math.abs(n) < 10 ? 2 : 1)
  return new Intl.NumberFormat(locale, {
    maximumFractionDigits,
    notation: compact ? 'compact' : 'standard',
  }).format(n)
}

/** 99.5 → "99.5%"; with `ratio`, 0.995 → "99.5%". */
export function percent(value: number | null | undefined, opts: { digits?: number; ratio?: boolean } = {}): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const v = opts.ratio ? value * 100 : value
  const digits = opts.digits ?? (Number.isInteger(v) ? 0 : 1)
  return `${v.toFixed(digits)}%`
}

/** 1536 → "1.5 KB" (binary units). */
export function bytes(n: number | null | undefined, digits = 1): string {
  if (n == null || !Number.isFinite(n)) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let v = Math.abs(n)
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  const s = i === 0 ? String(Math.round(v)) : v.toFixed(digits).replace(/\.0+$/, '')
  return `${n < 0 ? '-' : ''}${s} ${units[i]}`
}

export interface ShortDateOptions {
  now?: DateInput
  timeZone?: string
  locale?: string
}

/** Today → "14:05"; this year → "17 Oct, 14:05"; otherwise "17 Oct 2025". */
export function shortDate(input: DateInput, opts: ShortDateOptions = {}): string {
  const t = toMs(input)
  if (Number.isNaN(t)) return '—'
  const d = new Date(t)
  const now = new Date(toMs(opts.now ?? Date.now()))
  const locale = opts.locale ?? 'en-GB'
  const timeZone = opts.timeZone
  const fmt = (date: Date, o: Intl.DateTimeFormatOptions) =>
    new Intl.DateTimeFormat(locale, { ...o, timeZone }).format(date)
  const dayOf = (date: Date) => fmt(date, { year: 'numeric', month: 'short', day: 'numeric' })
  const time = fmt(d, { hour: '2-digit', minute: '2-digit', hour12: false })
  if (dayOf(d) === dayOf(now)) return time
  if (fmt(d, { year: 'numeric' }) === fmt(now, { year: 'numeric' })) {
    return `${fmt(d, { day: 'numeric', month: 'short' })}, ${time}`
  }
  return fmt(d, { day: 'numeric', month: 'short', year: 'numeric' })
}

/** A day on an axis: this year → "17 Oct"; otherwise "17 Oct 2025". */
export function dayLabel(input: DateInput, opts: ShortDateOptions = {}): string {
  const t = toMs(input)
  if (Number.isNaN(t)) return '—'
  const locale = opts.locale ?? 'en-GB'
  const fmt = (date: Date, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale, { ...o, timeZone: opts.timeZone }).format(date)
  const d = new Date(t)
  const sameYear = fmt(d, { year: 'numeric' }) === fmt(new Date(toMs(opts.now ?? Date.now())), { year: 'numeric' })
  return fmt(d, sameYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' })
}

/** Full date and time: "17 Oct 2026, 14:05:33". */
export function longDate(input: DateInput, opts: Omit<ShortDateOptions, 'now'> = {}): string {
  const t = toMs(input)
  if (Number.isNaN(t)) return '—'
  return new Intl.DateTimeFormat(opts.locale ?? 'en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: opts.timeZone,
  }).format(new Date(t))
}

/** 2026-10-17 (UTC calendar date). */
export function isoDate(input: DateInput): string {
  const t = toMs(input)
  if (Number.isNaN(t)) return '—'
  return new Date(t).toISOString().slice(0, 10)
}

/** "job_01HXK…9F2C" — keeps both ends of a long identifier. */
export function truncateMiddle(s: string, max = 20): string {
  if (s.length <= max) return s
  const keep = Math.max(2, max - 1)
  const head = Math.ceil(keep / 2)
  const tail = Math.floor(keep / 2)
  return `${s.slice(0, head)}…${s.slice(s.length - tail)}`
}

/** plural(3, 'job') → "3 jobs"; plural(1, 'entry', 'entries') → "1 entry". */
export function plural(n: number, singular: string, pluralForm?: string): string {
  const word = n === 1 ? singular : (pluralForm ?? `${singular}s`)
  return `${formatNumber(n)} ${word}`
}
