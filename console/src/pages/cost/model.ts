import type { ProgressTone, Tone } from '@iaf/ui'
import type { SeriesSlot, StackedBar, StackedSeries } from '@iaf/console-kit'
import type { QueryValue } from '../../app/router'
import { OTHER_KEY, type BudgetAlert, type BudgetState, type ChargebackRow, type CostGranularity, type CostGroupBy, type CostQuery, type CostReport, type CostScope } from '../../lib/cost'

/*
 * The arithmetic and the words of cost and budgets, kept out of the pages so each is tested on
 * its own: money in the report's currency, the date presets of the Cost Explorer in UTC days (the
 * cost service closes a day at midnight UTC), the explorer's whole state as the URL holds it, the
 * chart's series (at most seven named groups and Other, each keeping its colour while the filters
 * change), the chargeback's subtotals, and a budget's state and alerts in words.
 */

export const DAY_MS = 86_400_000

/* ---------------------------------------------------------------- money */

const moneyFormats = new Map<string, Intl.NumberFormat>()
const tickFormats = new Map<string, Intl.NumberFormat>()

/** An amount in its currency, two decimals: $14,365.71. */
export function money(amount: number, currency = 'USD'): string {
  let f = moneyFormats.get(currency)
  if (!f) {
    f = new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 })
    moneyFormats.set(currency, f)
  }
  // -0 would print as -$0.00
  return f.format(amount === 0 ? 0 : amount)
}

/** An axis tick: compact from a thousand up ($0, $500, $1.5K, $12K). */
export function moneyTick(amount: number, currency = 'USD'): string {
  let f = tickFormats.get(currency)
  if (!f) {
    f = new Intl.NumberFormat('en-US', { style: 'currency', currency, notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 1 })
    tickFormats.set(currency, f)
  }
  return f.format(amount)
}

/** A share of a total, one decimal: 42.5%; "0%" of nothing. */
export function share(part: number, total: number): string {
  if (!total) return '0%'
  return `${(Math.round((part / total) * 1000) / 10).toLocaleString('en-US')}%`
}

/* ---------------------------------------------------------------- days and months (UTC) */

/** `YYYY-MM-DD` of an instant, in UTC. */
export const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10)
/** Midnight UTC of a day. */
export const dayMs = (day: string) => Date.parse(`${day}T00:00:00Z`)
export const addDays = (day: string, n: number) => isoDay(dayMs(day) + n * DAY_MS)
/** The first day of the day's month, `months` later (or earlier, when negative). */
export function monthStart(day: string, months = 0): string {
  const d = new Date(dayMs(day))
  return isoDay(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + months, 1))
}
/** `YYYY-MM` of a day. */
export const monthOfDay = (day: string) => day.slice(0, 7)
const DAY_RE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/
/** A real calendar day as `YYYY-MM-DD`. */
export const isDay = (s: string | undefined): s is string => !!s && DAY_RE.test(s) && isoDay(dayMs(s)) === s

const fmt = (opts: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-US', { ...opts, timeZone: 'UTC' })
const SHORT_DAY = fmt({ month: 'short', day: 'numeric' })
const LONG_DAY = fmt({ weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })
const DAY_YEAR = fmt({ month: 'short', day: 'numeric', year: 'numeric' })
const SHORT_MONTH = fmt({ month: 'short' })
const MONTH_YEAR = fmt({ month: 'short', year: 'numeric' })
const LONG_MONTH = fmt({ month: 'long', year: 'numeric' })

/** "Oct 3" */
export const dayLabel = (day: string) => SHORT_DAY.format(dayMs(day))
/** "Friday, October 3, 2026" */
export const longDayLabel = (day: string) => LONG_DAY.format(dayMs(day))
/** "Oct 3, 2026" */
export const dayYearLabel = (day: string) => DAY_YEAR.format(dayMs(day))
/** "October 2026", from `YYYY-MM` or a day in it */
export const monthLabel = (month: string) => LONG_MONTH.format(dayMs(`${month.slice(0, 7)}-01`))
/** "Oct", or "Jan 2026" for a January, so a year's columns say where the year turns */
export function shortMonthLabel(month: string): string {
  const at = dayMs(`${month.slice(0, 7)}-01`)
  return month.slice(5, 7) === '01' ? MONTH_YEAR.format(at) : SHORT_MONTH.format(at)
}

/** A half-open range in words, its last day included: "Oct 1 to Oct 23, 2026". */
export function rangeLabel(from: string, to: string): string {
  const last = addDays(to, -1)
  if (last === from) return dayYearLabel(from)
  return from.slice(0, 4) === last.slice(0, 4) ? `${dayLabel(from)} to ${dayYearLabel(last)}` : `${dayYearLabel(from)} to ${dayYearLabel(last)}`
}

/* ---------------------------------------------------------------- the explorer's state */

export type RangePreset = 'mtd' | 'last-month' | '30d' | '3m' | '12m' | 'custom'

/** The ranges people reach for, as the AWS console offers them, then a custom one. */
export const RANGE_PRESETS: { value: RangePreset; label: string }[] = [
  { value: 'mtd', label: 'Month to date' },
  { value: 'last-month', label: 'Last month' },
  { value: '30d', label: 'Last 30 days' },
  { value: '3m', label: 'Last 3 months' },
  { value: '12m', label: 'Last 12 months' },
  { value: 'custom', label: 'Custom' },
]

/** What the cost service takes by day: at most 366 days. */
export const MAX_DAYS = 366
/** What a custom range draws by day before it switches to months by default. */
const DAILY_UP_TO = 62

export interface DateRange {
  /** the first day */
  from: string
  /** the day after the last: the range is half open */
  to: string
  /** what the range is drawn by unless the person picks otherwise */
  granularity: CostGranularity
}

/**
 * A preset's days on `today` (UTC). The ranges that hold today run to tomorrow, so today's
 * open day is in them; a custom range's `to` is its last day, included.
 */
export function rangeOf(preset: RangePreset, today: string, custom?: { from?: string; to?: string }): DateRange {
  const tomorrow = addDays(today, 1)
  switch (preset) {
    case 'last-month':
      return { from: monthStart(today, -1), to: monthStart(today), granularity: 'day' }
    case '30d':
      return { from: addDays(today, -29), to: tomorrow, granularity: 'day' }
    case '3m':
      return { from: monthStart(today, -2), to: tomorrow, granularity: 'month' }
    case '12m':
      return { from: monthStart(today, -11), to: tomorrow, granularity: 'month' }
    case 'custom': {
      if (isDay(custom?.from) && isDay(custom?.to) && custom.from <= custom.to && custom.from <= today) {
        const to = addDays(custom.to < today ? custom.to : today, 1)
        const days = (dayMs(to) - dayMs(custom.from)) / DAY_MS
        return { from: custom.from, to, granularity: days <= DAILY_UP_TO ? 'day' : 'month' }
      }
      return rangeOf('mtd', today)
    }
    default:
      return { from: monthStart(today), to: tomorrow, granularity: 'day' }
  }
}

/** The groupings of the Cost Explorer; a tag key groups by that tag's values. */
export type ExplorerGroup = CostGroupBy | 'none'

export const GROUP_DIMENSIONS: { value: Exclude<ExplorerGroup, `tag:${string}`>; label: string }[] = [
  { value: 'service', label: 'Service' },
  { value: 'type', label: 'Resource type' },
  { value: 'account', label: 'Account' },
  { value: 'region', label: 'Region' },
  { value: 'costCentre', label: 'Cost centre' },
  { value: 'none', label: 'No grouping' },
]

/** The tag keys the platform's resources carry, offered for grouping; the URL may name another. */
export const TAG_KEYS = ['environment', 'owner', 'visibility']

const TAG_KEY = /^[a-z0-9-]{1,32}$/

/** What a grouping is called: "Service", "Tag: environment". */
export function groupLabel(g: ExplorerGroup): string {
  if (g.startsWith('tag:')) return `Tag: ${g.slice(4)}`
  return GROUP_DIMENSIONS.find((d) => d.value === g)?.label ?? g
}

/** A grouping's noun in a sentence: "service", "environment tag". */
export function groupNoun(g: ExplorerGroup): string {
  if (g.startsWith('tag:')) return `${g.slice(4)} tag`
  if (g === 'costCentre') return 'cost centre'
  if (g === 'type') return 'resource type'
  return g
}

export interface ExplorerState {
  range: RangePreset
  /** a custom range's first and last days */
  from?: string
  to?: string
  /** what the person picked; the range's own when not set */
  granularity?: CostGranularity
  groupBy: ExplorerGroup
  service?: string
  type?: string
  tag?: { key: string; value: string }
  scope: CostScope
  /** one resource, when the explorer was opened from its page */
  wfrn?: string
  view: 'chart' | 'table'
}

export const DEFAULT_EXPLORER: ExplorerState = { range: 'mtd', groupBy: 'service', scope: 'account', view: 'chart' }

/** `environment=prod` → the tag; anything else → nothing. */
export function parseTag(text: string | undefined): { key: string; value: string } | undefined {
  if (!text) return undefined
  const at = text.indexOf('=')
  if (at < 1) return undefined
  const key = text.slice(0, at).trim().toLowerCase()
  const value = text.slice(at + 1).trim()
  return TAG_KEY.test(key) && value && value.length <= 256 ? { key, value } : undefined
}

/** The explorer's state from the URL's query, every unknown or malformed value at its default. */
export function explorerStateOf(query: Record<string, string>): ExplorerState {
  const range = RANGE_PRESETS.some((p) => p.value === query.range) ? (query.range as RangePreset) : DEFAULT_EXPLORER.range
  const g = query.groupBy
  const groupBy: ExplorerGroup = g && (GROUP_DIMENSIONS.some((d) => d.value === g) || (g.startsWith('tag:') && TAG_KEY.test(g.slice(4)))) ? (g as ExplorerGroup) : DEFAULT_EXPLORER.groupBy
  const state: ExplorerState = {
    range,
    groupBy,
    scope: query.scope === 'organization' ? 'organization' : 'account',
    view: query.view === 'table' ? 'table' : 'chart',
  }
  if (range === 'custom') {
    if (isDay(query.from)) state.from = query.from
    if (isDay(query.to)) state.to = query.to
  }
  if (query.granularity === 'day' || query.granularity === 'month') state.granularity = query.granularity
  if (query.service) state.service = query.service
  if (query.type) state.type = query.type
  const tag = parseTag(query.tag)
  if (tag) state.tag = tag
  if (query.wfrn) state.wfrn = query.wfrn
  return state
}

/** The state as the URL's query, leaving out what is at its default. */
export function explorerQueryOf(s: ExplorerState): Record<string, QueryValue> {
  return {
    range: s.range === DEFAULT_EXPLORER.range ? undefined : s.range,
    from: s.range === 'custom' ? s.from : undefined,
    to: s.range === 'custom' ? s.to : undefined,
    granularity: s.granularity,
    groupBy: s.groupBy === DEFAULT_EXPLORER.groupBy ? undefined : s.groupBy,
    service: s.service,
    type: s.type,
    tag: s.tag ? `${s.tag.key}=${s.tag.value}` : undefined,
    scope: s.scope === 'account' ? undefined : s.scope,
    wfrn: s.wfrn,
    view: s.view === 'chart' ? undefined : s.view,
  }
}

/** How many groups the chart names before the rest fold into Other: one hue each, never a ninth. */
export const NAMED_GROUPS = 7

/** The range and the granularity the state asks for on `today`, kept inside what the cost service draws by day. */
export function explorerRange(s: ExplorerState, today: string): DateRange {
  const r = rangeOf(s.range, today, { from: s.from, to: s.to })
  const days = (dayMs(r.to) - dayMs(r.from)) / DAY_MS
  const granularity = s.granularity === 'day' && days > MAX_DAYS ? 'month' : (s.granularity ?? r.granularity)
  return { ...r, granularity }
}

/** The cost service's query for the state on `today`. */
export function costQueryOf(s: ExplorerState, today: string): CostQuery {
  const r = explorerRange(s, today)
  return {
    from: r.from,
    to: r.to,
    granularity: r.granularity,
    ...(s.groupBy === 'none' ? {} : { groupBy: s.groupBy, top: NAMED_GROUPS }),
    scope: s.scope,
    service: s.service,
    type: s.type,
    wfrn: s.wfrn,
    ...(s.tag ? { tags: { [s.tag.key]: s.tag.value } } : {}),
  }
}

/* ---------------------------------------------------------------- the chart */

/** The key of the one series a report without grouping draws. */
export const TOTAL_KEY = '__total'

/**
 * The slots of a chart's groups, keeping each group's colour from the last answer: a filter that
 * changes which groups show never repaints the ones that stay. A new group takes the lowest slot
 * nobody holds now, preferring one no earlier group held either; the folded rest is never in it.
 */
export function stickySlots(previous: Readonly<Record<string, number>>, keys: readonly string[], size = 8): Record<string, number> {
  const next: Record<string, number> = {}
  const taken = new Set<number>()
  for (const k of keys) {
    const s = previous[k]
    if (s && !taken.has(s)) {
      next[k] = s
      taken.add(s)
    }
  }
  const remembered = new Set(Object.values(previous))
  for (const k of keys) {
    if (next[k]) continue
    let slot = 0
    for (let s = 1; s <= size && !slot; s++) if (!taken.has(s) && !remembered.has(s)) slot = s
    for (let s = 1; s <= size && !slot; s++) if (!taken.has(s)) slot = s
    if (!slot) continue
    next[k] = slot
    taken.add(slot)
  }
  // the groups that left keep their colour for when they come back, unless it was given away
  const out = { ...next }
  for (const [k, s] of Object.entries(previous)) if (!(k in out) && !taken.has(s)) out[k] = s
  return out
}

/** The keys of a report's named groups, in the report's order (largest first), without Other. */
export function namedKeys(report: Pick<CostReport, 'groups'>): string[] {
  return report.groups.filter((g) => g.key !== OTHER_KEY).map((g) => g.key)
}

export interface ChartModel {
  /** the stack, bottom first: the largest group at the base, Other on top */
  series: StackedSeries[]
  bars: StackedBar[]
}

/** A report as the stacked chart draws it, each group in its slot. */
export function chartModelOf(report: CostReport, slots: Readonly<Record<string, number>>): ChartModel {
  const byMonth = report.granularity === 'month'
  const bars = report.series.map((p): StackedBar => {
    const values = report.groups.length ? p.groups : { [TOTAL_KEY]: p.total }
    return {
      key: p.start,
      label: byMonth ? shortMonthLabel(p.start) : dayLabel(p.start),
      title: byMonth ? monthLabel(p.start) : longDayLabel(p.start),
      values,
      total: p.total,
      provisional: p.provisional,
    }
  })
  if (!report.groups.length) return { series: [{ key: TOTAL_KEY, label: 'Cost', slot: 1 }], bars }
  const series = report.groups.map((g): StackedSeries => ({ key: g.key, label: g.label, slot: g.key === OTHER_KEY ? 'other' : (slots[g.key] ?? 'other') }))
  return { series, bars }
}

/** The slot a group's swatch takes in the table: the chart's, or the folded rest's. */
export function slotOf(key: string, slots: Readonly<Record<string, number>>): SeriesSlot {
  return key === OTHER_KEY ? 'other' : (slots[key] ?? 'other')
}

/** The explorer's CSV: one row per period, a column per group, the total, the currency and whether the period is still open. */
export function explorerCsvRows(report: CostReport, model: ChartModel): unknown[][] {
  const head = [report.granularity === 'month' ? 'Month' : 'Day', ...(report.groups.length ? model.series.map((s) => s.label) : []), 'Total', 'Currency', 'Provisional']
  const rows = report.series.map((p) => [
    report.granularity === 'month' ? p.start.slice(0, 7) : p.start,
    ...(report.groups.length ? model.series.map((s) => (p.groups[s.key] ?? 0).toFixed(2)) : []),
    p.total.toFixed(2),
    report.currency,
    p.provisional ? 'yes' : 'no',
  ])
  const totals = ['Total', ...(report.groups.length ? model.series.map((s) => (report.groups.find((g) => g.key === s.key)?.total ?? 0).toFixed(2)) : []), report.total.toFixed(2), report.currency, '']
  return [head, ...rows, totals]
}

/** What the explorer's file is called: `cost-2026-10-01-to-2026-10-23-by-service.csv`. */
export function explorerFileName(report: Pick<CostReport, 'from' | 'to' | 'groupBy'>): string {
  const by = report.groupBy ? `-by-${report.groupBy.replace(/^tag:/, 'tag-')}` : ''
  return `cost-${report.from}-to-${addDays(report.to, -1)}${by}.csv`.toLowerCase()
}

/* ---------------------------------------------------------------- budgets */

export const BUDGET_STATE: Record<BudgetState, { label: string; tone: Tone; bar: ProgressTone; sentence: string }> = {
  ok: { label: 'OK', tone: 'healthy', bar: 'accent', sentence: 'Spending is on course to stay within the amount.' },
  'forecast-over': { label: 'Forecast over', tone: 'warning', bar: 'warning', sentence: 'At this rate the month ends over the amount.' },
  over: { label: 'Over', tone: 'critical', bar: 'critical', sentence: 'This month has spent more than the amount.' },
}

/** A budget's scope in words: "Every resource in Compute", "Resources tagged environment = prod". */
export function scopeText(scope: { accountId: string; tag?: { key: string; value: string } }, accountName?: string): string {
  return scope.tag ? `Resources tagged ${scope.tag.key} = ${scope.tag.value}` : `Every resource in ${accountName ?? scope.accountId}`
}

/** "50%, 80% and 100%" */
export function thresholdsText(thresholds: readonly number[]): string {
  const t = thresholds.map((v) => `${v}%`)
  return t.length <= 1 ? (t[0] ?? 'none') : `${t.slice(0, -1).join(', ')} and ${t[t.length - 1]}`
}

/** One live alert in words: "Production crossed 80% of its amount for October 2026, at 83.4%." */
export function alertText(a: BudgetAlert): string {
  return `${a.name} crossed ${a.threshold}% of its amount for ${monthLabel(a.period)}, at ${a.percent.toLocaleString('en-US')}%.`
}

/** The problems a budget form checks before it is sent: at most five whole percentages from 1 to 1000, each once. */
export function thresholdProblem(values: readonly string[]): string | null {
  const filled = values.map((v) => v.trim()).filter(Boolean)
  if (!filled.length) return 'Add at least one threshold.'
  if (filled.length > 5) return 'At most five thresholds.'
  if (filled.some((v) => !/^\d+$/.test(v) || Number(v) < 1 || Number(v) > 1000)) return 'Each threshold is a whole percentage from 1 to 1000.'
  if (new Set(filled.map(Number)).size !== filled.length) return 'Each threshold once.'
  return null
}

/** The amount a person typed ("1,250.5", "$900") as a number, or null. */
export function parseAmount(text: string): number | null {
  const clean = text.replace(/[$,\s]/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null
  const v = Number(clean)
  return v > 0 && v <= 1_000_000_000 ? v : null
}

/* ---------------------------------------------------------------- chargeback */

export interface ChargebackAccount {
  accountId: string
  accountName?: string
  total: number
  rows: ChargebackRow[]
}

export interface ChargebackCentre {
  costCentre: string
  total: number
  accounts: ChargebackAccount[]
}

const cents = (v: number) => Math.round(v * 100) / 100

/** The rows by cost centre, then account, with their subtotals, in the order the report sorts them. */
export function chargebackGroupsOf(rows: readonly ChargebackRow[]): ChargebackCentre[] {
  const centres: ChargebackCentre[] = []
  for (const r of rows) {
    let c = centres.find((x) => x.costCentre === r.costCentre)
    if (!c) {
      c = { costCentre: r.costCentre, total: 0, accounts: [] }
      centres.push(c)
    }
    let a = c.accounts.find((x) => x.accountId === r.accountId)
    if (!a) {
      a = { accountId: r.accountId, accountName: r.accountName, total: 0, rows: [] }
      c.accounts.push(a)
    }
    a.rows.push(r)
    a.total = cents(a.total + r.amount)
    c.total = cents(c.total + r.amount)
  }
  return centres
}

/** The months a chargeback can be read for: this one, still provisional, and the twelve before it. */
export function chargebackMonths(today: string, count = 13): { value: string; label: string }[] {
  return Array.from({ length: count }, (_, i) => {
    const m = monthOfDay(monthStart(today, -i))
    return { value: m, label: i === 0 ? `${monthLabel(m)} (so far)` : monthLabel(m) }
  })
}

/** The chargeback file's name, as the BFF's Content-Disposition gives it. */
export function chargebackFileName(month: string, scope: CostScope, accountId: string, tag?: string): string {
  return `chargeback-${month}-${scope === 'organization' ? 'organization' : accountId}${tag ? `-by-${tag}` : ''}.csv`
}

/** The SHA-256 of text's UTF-8 bytes, lower-case hex, as the browser computes it. */
export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Offers text as a file, byte for byte: UTF-8 with no byte order mark, so its sha256 is the published one. */
export function saveText(fileName: string, text: string, type = 'text/csv;charset=utf-8'): void {
  const url = URL.createObjectURL(new Blob([new TextEncoder().encode(text)], { type }))
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1_000)
}
