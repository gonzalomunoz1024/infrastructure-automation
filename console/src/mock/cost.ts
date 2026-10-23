import { lifecycleOf, type Problem } from '@iaf/console-kit'
import {
  DEFAULT_THRESHOLDS,
  NO_TAG_KEY,
  OTHER_KEY,
  type BudgetState,
  type BudgetStatus,
  type ChargebackReport,
  type ChargebackRow,
  type ConsoleBudget,
  type CostAccount,
  type CostForecast,
  type CostGroup,
  type CostGroupBy,
  type CostPoint,
  type CostReport,
  type CostScope,
  type PriceLists,
  type PriceRule,
  type ResourceCost,
} from '../lib/cost'
import { capitalise, titleOf } from '../lib/descriptors'
import type { ResourceType } from '../lib/types'
import { idFrom, priceOf, resourceByWfrn, resourceRoutes, typeOf, wfrnOfResource, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { DIRECTORY_PARTITION, accountOf, authorize, membershipsOf, type Decision, type Directory, type DirectoryAccount } from './directory'
import { accountOfResource, consoleResourceOf } from './resources'

/*
 * Phase 9 in the mock BFF: cost, budgets and chargeback, as the cost service answers them through
 * the BFF (hub decision 25). The ledger is computed, not stored: a resource is charged while it is
 * active, updating or deleting, from its creation to its deletion (or now), at its list price now
 * (the platform's price list, the prices the create review quotes), accrued by the second at
 * `monthly / days in the month`. A type the price list does not price costs nothing and is counted
 * as unpriced. Every answer reads the same charges, so the Cost Explorer, a budget, the chargeback
 * and the executive home's spend by team agree to the cent.
 */

export const DAY_MS = 86_400_000
export const CURRENCY = 'USD'
/** The cost service's limits on one query: 366 days by day, 37 months by month. */
const MAX_DAYS = 366
const MAX_MONTHS = 37
const GROUP_BY = /^(service|type|account|region|costCentre|tag:[a-z0-9-]{1,32})$/
const TAG_PARAM = /^tag\.([a-z0-9-]{1,32})$/
const TAG_KEY = /^[a-z0-9-]{1,32}$/
const DATE = /^\d{4}-\d{2}-\d{2}$/
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/
/** A day closes 30 minutes after it ends, and a month with its last day: then it is final. */
const CLOSE_MS = 30 * 60_000

/** One resource's charge: its rate, and the span it is charged over. */
export interface Charge {
  wfrn: string
  name: string
  type: string
  service: string
  accountId: string
  region: string
  tags: Record<string, string>
  /** a month's rate now; 0 when no price covers it */
  monthly: number
  priced: boolean
  /** the price that priced it */
  rule?: PriceRule
  /** charged from, in ms */
  from: number
  /** charged until, exclusive, in ms: its deletion, or now */
  to: number
  /** charged now */
  live: boolean
}

/** What narrows a report: the cost service's filters. */
export interface CostFilter {
  service?: string
  type?: string
  region?: string
  wfrn?: string
  tags?: Record<string, string>
}

const pad = (n: number) => String(n).padStart(2, '0')
export const dayOf = (t: number) => {
  const d = new Date(t)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}
export const monthOf = (t: number) => dayOf(t).slice(0, 7)
export const startOfDay = (t: number) => Math.floor(t / DAY_MS) * DAY_MS
export const startOfMonth = (t: number) => {
  const d = new Date(t)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1)
}
export const startOfNextMonth = (t: number) => {
  const d = new Date(t)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)
}
export const daysInMonth = (t: number) => (startOfNextMonth(t) - startOfMonth(t)) / DAY_MS
export const cents = (n: number) => Math.round(n * 100) / 100
const iso = (t: number) => new Date(t).toISOString()
const usd = (n: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: CURRENCY }).format(n)

/** The finance cost centre an account is charged to: its own, else its owning team (the cost service's fallback). */
export function costCentreOf(account: DirectoryAccount | undefined): string {
  return account?.costCentre ?? account?.owningTeam ?? 'unassigned'
}

/* ----------------------------------------------------------------------------
   The price list: what prices a resource
   ------------------------------------------------------------------------- */

/** The platform's price list, as `list-prices.yaml` holds it at the content version the mock forks. */
const LIST_PRICES = 'list-prices'
const PRICE_LIST_VERSION = 'c9529d5'

/** The types the price list prices, and the attribute whose value picks the price (a type's `cost` block). */
const LISTED: readonly { type: string; provider: string; action: string; by: string; values: readonly (string | number)[] }[] = [
  { type: 'vm', provider: 'vsphere', action: 'vm.create', by: 'cpu', values: [2, 4, 8, 16] },
  { type: 'database', provider: 'mongodb', action: 'cluster.create', by: 'tier', values: ['M10', 'M30', 'M60'] },
]

/**
 * A resource's list price now, and the rule that set it; undefined when no binding or no value of
 * the price covers it. The amounts are the create review's (`priceOf`), so a review and the ledger
 * never disagree on a priced type; the review's own estimates for the other types are not prices.
 */
export function listPriceOf(rt: ResourceType, attributes: Record<string, unknown>): { monthly: number; rule: PriceRule } | undefined {
  const listed = LISTED.find((l) => l.type === rt.name)
  if (!listed) return undefined
  const value = attributes[listed.by]
  if (!listed.values.some((v) => String(v) === String(value))) return undefined
  const price = priceOf(rt, { [listed.by]: value })
  if (!price) return undefined
  return { monthly: price.monthly, rule: { priceList: LIST_PRICES, provider: listed.provider, action: listed.action, by: listed.by, value } }
}

/** `GET /console/v1/cost/price-lists`: the list in force, and which types it prices. */
export function priceListsOf(data: MockData): PriceLists {
  const listed = LISTED.flatMap((l) => {
    const rt = typeOf(data, l.type)
    return rt ? [{ ...l, rt }] : []
  })
  return {
    items: [
      {
        name: LIST_PRICES,
        title: 'Platform list prices',
        currency: CURRENCY,
        version: PRICE_LIST_VERSION,
        prices: listed.map((l) => ({ provider: l.provider, action: l.action, by: l.by, monthly: Object.fromEntries(l.values.map((v) => [String(v), priceOf(l.rt, { [l.by]: v })?.monthly ?? 0])) })),
      },
    ],
    next: null,
    bindings: listed.map((l) => ({ type: l.type, priceList: LIST_PRICES, provider: l.provider, action: l.action, by: l.by })),
  }
}

/* ----------------------------------------------------------------------------
   The ledger
   ------------------------------------------------------------------------- */

/** Every resource's charge in the accounts, filtered; the session's region names them the way the lists do. */
export function chargesOf(data: MockData, region: string, accountIds: readonly string[], now: number, filter: CostFilter = {}): Charge[] {
  const all = data.directory.accounts.map((a) => a.accountId)
  const wanted = new Set(accountIds)
  const types = new Map(data.resourceTypes.map((rt) => [rt.name, rt]))
  const out: Charge[] = []
  for (const r of data.resources) {
    const rt = types.get(r.type)
    if (!rt) continue
    const accountId = accountOfResource(r, all)
    if (!wanted.has(accountId)) continue
    const row = consoleResourceOf(r, data, {
      partition: data.partition,
      region,
      accountId,
    })
    const charge = chargeOf(rt, r.status, r.attributes ?? {}, Date.parse(r.createdAt), Date.parse(r.updatedAt ?? r.createdAt), now)
    const c: Charge = {
      wfrn: row.wfrn,
      name: row.name ?? r.id,
      type: rt.name,
      service: row.service,
      accountId,
      region,
      tags: data.console?.tags[row.wfrn] ?? row.tags,
      ...charge,
    }
    if (matches(c, filter)) out.push(c)
  }
  return out
}

function chargeOf(rt: ResourceType, status: string, attributes: Record<string, unknown>, created: number, changed: number, now: number): Pick<Charge, 'monthly' | 'priced' | 'rule' | 'from' | 'to' | 'live'> {
  const price = listPriceOf(rt, attributes)
  const priced = price ? { monthly: price.monthly, priced: true, rule: price.rule } : { monthly: 0, priced: false }
  const cls = lifecycleOf(status, rt.console?.lifecycle) ?? 'active'
  const from = Math.min(created, now)
  // never charged: still being created, or failed before it existed at the provider
  if (cls === 'pending' || cls === 'failed') return { ...priced, from, to: from, live: false }
  const live = cls !== 'deleted'
  const to = live ? now : Math.max(from, Math.min(changed, now))
  return { ...priced, from, to, live }
}

function matches(c: Charge, f: CostFilter): boolean {
  if (f.service && c.service !== f.service) return false
  if (f.type && c.type !== f.type) return false
  if (f.region && c.region !== f.region) return false
  if (f.wfrn && c.wfrn !== f.wfrn) return false
  for (const [k, v] of Object.entries(f.tags ?? {})) if (c.tags[k] !== v) return false
  return true
}

/** What a charge accrued over [a, b): by the second at a month's rate over the days in that month. */
export function accrued(c: Charge, a: number, b: number): number {
  if (!c.monthly) return 0
  let t = Math.max(a, c.from)
  const end = Math.min(b, c.to)
  let sum = 0
  while (t < end) {
    const stop = Math.min(end, startOfNextMonth(t))
    sum += (c.monthly / daysInMonth(t)) * ((stop - t) / DAY_MS)
    t = stop
  }
  return sum
}

/** What cost is decided on: everything of the cost service in the account, `wfrn:<partition>:cost::<account>:*` (region-less). */
export const costResource = (accountId: string) => `wfrn:${DIRECTORY_PARTITION}:cost::${accountId}:*`

/**
 * The accounts a cost read covers, by `scope`: the session's, or every membership where the action
 * (`cost:list`, `cost:export` for the chargeback) is allowed, naming the others. An organization
 * read with no account allowed is refused with the first refusal, as the BFF refuses it, rather
 * than answered empty.
 */
export function costAccounts(data: MockData, ctx: RouteContext, scope: CostScope, action = 'cost:list'): { accountIds: string[]; skipped: string[]; denied?: Decision } {
  const { userId, accountId } = ctx.session
  const decide = (id: string) => authorize(data.directory, { userId, accountId: id }, action, costResource(id))
  if (scope === 'account') {
    const d = decide(accountId)
    return d.allowed ? { accountIds: [accountId], skipped: [] } : { accountIds: [], skipped: [], denied: d }
  }
  const accountIds: string[] = []
  const skipped: string[] = []
  let first: Decision | undefined
  for (const m of membershipsOf(data.directory, userId)) {
    const d = decide(m.accountId)
    if (d.allowed) accountIds.push(m.accountId)
    else {
      skipped.push(m.accountId)
      first ??= d
    }
  }
  return accountIds.length || !first ? { accountIds, skipped } : { accountIds, skipped, denied: first }
}

export function refusedCost(decision: Decision, ctx: RouteContext, path: string): RouteAnswer {
  return {
    status: 403,
    body: {
      error: decision.denied?.reason ?? 'Forbidden',
      denied: decision.denied,
      correlationId: `mock-${idFrom(`${ctx.now}:${path}`, 8)}`,
    },
  }
}

export function badCost(param: string, message: string): RouteAnswer {
  return {
    status: 400,
    body: {
      error: 'validation.failed',
      detail: message,
      problems: [{ level: 'error', code: 'invalid', field: `/${param}`, message }],
    },
  }
}

interface ReportQuery {
  from: number
  to: number
  granularity: 'day' | 'month'
  groupBy?: CostGroupBy
  top: number
  scope: CostScope
  filter: CostFilter
}

/** `GET /console/v1/cost`'s parameters, checked as the cost service checks them. */
export function readReportQuery(params: URLSearchParams): ReportQuery | RouteAnswer {
  const fromRaw = params.get('from') ?? ''
  const toRaw = params.get('to') ?? ''
  if (!DATE.test(fromRaw) || Number.isNaN(Date.parse(fromRaw))) return badCost('from', 'from is a date, YYYY-MM-DD')
  if (!DATE.test(toRaw) || Number.isNaN(Date.parse(toRaw))) return badCost('to', 'to is a date, YYYY-MM-DD')
  const from = Date.parse(`${fromRaw}T00:00:00Z`)
  const to = Date.parse(`${toRaw}T00:00:00Z`)
  if (to <= from) return badCost('to', 'to comes after from: the range is half open')
  const granularity = params.get('granularity') ?? 'day'
  if (granularity !== 'day' && granularity !== 'month') return badCost('granularity', 'granularity is day or month')
  if (granularity === 'day' && (to - from) / DAY_MS > MAX_DAYS) return badCost('to', `At most ${MAX_DAYS} days by day`)
  const months = (new Date(to).getUTCFullYear() - new Date(from).getUTCFullYear()) * 12 + new Date(to).getUTCMonth() - new Date(from).getUTCMonth()
  if (granularity === 'month' && months > MAX_MONTHS) return badCost('to', `At most ${MAX_MONTHS} months by month`)
  const groupBy = params.get('groupBy') ?? undefined
  if (groupBy !== undefined && !GROUP_BY.test(groupBy)) return badCost('groupBy', 'groupBy is service, type, account, region, costCentre or tag:<key>')
  const top = params.has('top') ? Number(params.get('top')) : 10
  if (!Number.isInteger(top) || top < 1 || top > 50) return badCost('top', 'top is a whole number from 1 to 50')
  const scope = params.get('scope') ?? 'account'
  if (scope !== 'account' && scope !== 'organization') return badCost('scope', 'scope is account or organization')
  const tags: Record<string, string> = {}
  for (const [k, v] of params) {
    const m = TAG_PARAM.exec(k)
    if (m) tags[m[1]] = v
  }
  const opt = (k: string) => params.get(k) || undefined
  return {
    from,
    to,
    granularity,
    groupBy: groupBy as CostGroupBy | undefined,
    top,
    scope,
    filter: {
      service: opt('service'),
      type: opt('type'),
      region: opt('region'),
      wfrn: opt('wfrn'),
      tags,
    },
  }
}

const isAnswer = (v: unknown): v is RouteAnswer => !!v && typeof v === 'object' && 'status' in v

/** A charge's group: its key and what to show for it. */
function groupOf(data: MockData, c: Charge, groupBy: CostGroupBy): { key: string; label: string } {
  if (groupBy === 'service') return { key: c.service, label: capitalise(c.service) }
  if (groupBy === 'type') {
    const rt = typeOf(data, c.type)
    return { key: c.type, label: rt ? titleOf(rt) : c.type }
  }
  if (groupBy === 'account')
    return {
      key: c.accountId,
      label: accountOf(data.directory, c.accountId)?.name ?? c.accountId,
    }
  if (groupBy === 'region') return { key: c.region, label: c.region }
  if (groupBy === 'costCentre') {
    const cc = costCentreOf(accountOf(data.directory, c.accountId))
    return { key: cc, label: cc }
  }
  const tag = groupBy.slice('tag:'.length)
  const v = c.tags[tag]
  return v ? { key: v, label: v } : { key: NO_TAG_KEY, label: `No ${tag} tag` }
}

/** The periods of the range: days, or months clipped to it. */
function periodsOf(from: number, to: number, granularity: 'day' | 'month'): { start: number; end: number }[] {
  const out: { start: number; end: number }[] = []
  for (let t = from; t < to; ) {
    const end = Math.min(to, granularity === 'day' ? t + DAY_MS : startOfNextMonth(t))
    out.push({ start: granularity === 'day' ? t : startOfMonth(t), end })
    t = end
  }
  return out
}

/** The report over charges already filtered: the same arithmetic for every reader of the ledger. */
export function costReportOf(data: MockData, charges: readonly Charge[], q: Omit<ReportQuery, 'scope' | 'filter'>, now: number): CostReport {
  const periods = periodsOf(q.from, q.to, q.granularity)
  // each charge's group, then the top groups by their total over the range; the rest is __other
  const keyed = charges.map((c) => ({
    c,
    g: q.groupBy ? groupOf(data, c, q.groupBy) : undefined,
  }))
  const totals = new Map<string, { label: string; total: number }>()
  for (const { c, g } of keyed) {
    const v = g ? accrued(c, q.from, q.to) : 0
    if (!g || !v) continue
    const t = totals.get(g.key) ?? { label: g.label, total: 0 }
    t.total += v
    totals.set(g.key, t)
  }
  const ranked = [...totals.entries()].sort((a, b) => b[1].total - a[1].total || a[0].localeCompare(b[0]))
  const kept = new Set(ranked.slice(0, q.top).map(([k]) => k))
  const keyOf = (g: { key: string } | undefined) => (!g ? undefined : kept.has(g.key) ? g.key : OTHER_KEY)

  const closedThrough = startOfDay(now) - DAY_MS
  // a key's points are the steps of its rounded running total, so they add up to its rounded total
  // exactly: a month by day and by month agree to the cent, and so do a resource, a budget and the chargeback
  const running = new Map<string, number>()
  const step = (k: string, v: number) => {
    const before = running.get(k) ?? 0
    running.set(k, before + v)
    return cents(cents(before + v) - cents(before))
  }
  const series: CostPoint[] = periods.map(({ start, end }) => {
    const exact: Record<string, number> = {}
    let raw = 0
    for (const { c, g } of keyed) {
      const v = accrued(c, Math.max(start, q.from), end)
      if (!v) continue
      raw += v
      const k = keyOf(g)
      if (k) exact[k] = (exact[k] ?? 0) + v
    }
    const groups = Object.fromEntries(Object.entries(exact).map(([k, v]) => [k, step(k, v)]))
    const point: CostPoint = {
      start: dayOf(start),
      total: q.groupBy ? cents(Object.values(groups).reduce((a, b) => a + b, 0)) : step('\u0000', raw),
      groups,
    }
    if (start <= now && now < end) point.provisional = true
    return point
  })

  const groups: CostGroup[] = []
  if (q.groupBy) {
    for (const [k, t] of ranked.slice(0, q.top))
      groups.push({
        key: k,
        label: t.label,
        total: cents(series.reduce((a, p) => a + (p.groups[k] ?? 0), 0)),
      })
    if (ranked.length > q.top)
      groups.push({
        key: OTHER_KEY,
        label: 'Other',
        total: cents(series.reduce((a, p) => a + (p.groups[OTHER_KEY] ?? 0), 0)),
      })
  }

  const live = charges.filter((c) => c.live)
  const priced = live.filter((c) => c.priced)
  const unpriced = live.filter((c) => !c.priced)
  const report: CostReport = {
    currency: CURRENCY,
    from: dayOf(q.from),
    to: dayOf(q.to),
    granularity: q.granularity,
    ...(q.groupBy ? { groupBy: q.groupBy } : {}),
    total: cents(series.reduce((a, p) => a + p.total, 0)),
    groups,
    series,
    runRate: {
      monthly: cents(priced.reduce((a, c) => a + c.monthly, 0)),
      resources: priced.length,
    },
    asOf: new Date(now).toISOString(),
    closedThrough: dayOf(closedThrough),
    source: 'list-prices',
    unpriced: {
      resources: unpriced.length,
      ...(unpriced.length ? { types: [...new Set(unpriced.map((c) => c.type))].sort() } : {}),
    },
  }
  const forecast = forecastOf(charges, q.from, q.to, now)
  if (forecast) report.forecast = forecast
  if (q.groupBy === 'account' || q.groupBy === 'costCentre') {
    const ids = [...new Set(charges.map((c) => c.accountId))].sort()
    report.accounts = ids.map((id): CostAccount => {
      const a = accountOf(data.directory, id)
      return { accountId: id, name: a?.name, costCentre: costCentreOf(a) }
    })
  }
  return report
}

/**
 * The month-end forecast when the range holds today's month: month to date plus today's run rate
 * for the time left, banded by two standard deviations of the last 14 closed days' change.
 */
export function forecastOf(charges: readonly Charge[], from: number, to: number, now: number): CostForecast | undefined {
  const monthStart = startOfMonth(now)
  const monthEnd = startOfNextMonth(now)
  if (!(from < monthEnd && to > monthStart)) return undefined
  const monthToDate = charges.reduce((a, c) => a + accrued(c, monthStart, now), 0)
  const rate = charges.filter((c) => c.live).reduce((a, c) => a + c.monthly, 0)
  const daysLeft = (monthEnd - now) / DAY_MS
  const expected = monthToDate + (rate / daysInMonth(now)) * daysLeft
  const today = startOfDay(now)
  const days = Array.from({ length: 15 }, (_, i) => today - (15 - i) * DAY_MS).map((d) => charges.reduce((a, c) => a + accrued(c, d, d + DAY_MS), 0))
  const changes = days.slice(1).map((v, i) => v - days[i])
  const mean = changes.reduce((a, b) => a + b, 0) / changes.length
  const sd = Math.sqrt(changes.reduce((a, b) => a + (b - mean) ** 2, 0) / changes.length)
  const band = Math.max(2 * sd * daysLeft, expected * 0.02)
  return {
    month: monthOf(now),
    monthToDate: cents(monthToDate),
    monthEnd: cents(expected),
    low: cents(Math.max(monthToDate, expected - band)),
    high: cents(expected + band),
    method: 'run-rate',
  }
}

/* ----------------------------------------------------------------------------
   One resource's cost
   ------------------------------------------------------------------------- */

/** `GET /console/v1/r/{type}/{wfrn}/cost` for a charge: its rate now, this month so far, and the price behind it. */
export function resourceCostOf(c: Charge, state: string | undefined, now: number): ResourceCost {
  const charged = c.live && c.to > c.from
  return {
    wfrn: c.wfrn,
    accountId: c.accountId,
    type: c.type,
    service: c.service,
    ...(state ? { state } : {}),
    charged,
    monthly: charged ? c.monthly : 0,
    monthToDate: cents(accrued(c, startOfMonth(now), now)),
    currency: CURRENCY,
    ...(charged ? { since: iso(c.from) } : {}),
    source: c.priced ? 'list-prices' : 'unpriced',
    ...(c.rule ? { rule: c.rule, pricedFrom: 'desired' as const } : {}),
    // the mock prices a resource at its rate now for all its life: one rate, open while it is charged
    history: c.priced && c.to > c.from ? [{ from: iso(c.from), ...(c.live ? {} : { to: iso(c.to) }), monthly: c.monthly }] : [],
  }
}

/* ----------------------------------------------------------------------------
   Chargeback
   ------------------------------------------------------------------------- */

const CSV_COLUMNS = ['month', 'cost_centre', 'account_id', 'account_name', 'service', 'tag_key', 'tag_value', 'amount', 'currency', 'final']

interface ChargebackQuery {
  month: string
  start: number
  end: number
  scope: CostScope
  tag?: string
}

function readChargebackQuery(params: URLSearchParams, now: number): ChargebackQuery | RouteAnswer {
  const month = params.get('month') ?? ''
  const m = MONTH.exec(month)
  if (!m) return badCost('month', 'month is a month, YYYY-MM')
  const start = Date.UTC(Number(m[1]), Number(m[2]) - 1, 1)
  if (start > startOfMonth(now)) return badCost('month', 'month is at most the current month: a later one has no cost yet')
  const scope = params.get('scope') ?? 'account'
  if (scope !== 'account' && scope !== 'organization') return badCost('scope', 'scope is account or organization')
  const tag = params.get('tag') || undefined
  if (tag !== undefined && !TAG_KEY.test(tag)) return badCost('tag', 'tag is a tag key: 1 to 32 lower-case letters, digits and dashes')
  return { month, start, end: startOfNextMonth(start), scope, tag }
}

/** One CSV cell, RFC 4180: quoted when it holds a comma, a quote or a line break; text a spreadsheet would run as a formula is kept as text. */
function csvField(v: string): string {
  const safe = /^[=+\-@\t\r]/.test(v) ? `'${v}` : v
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

/** The CSV finance receives: a header row, then one line per row, CRLF line ends, UTF-8 without a byte order mark. */
export function chargebackCsvOf(report: Pick<ChargebackReport, 'month' | 'currency' | 'final' | 'rows'>): string {
  const lines = [CSV_COLUMNS.join(',')]
  for (const r of report.rows)
    lines.push([report.month, r.costCentre, r.accountId, r.accountName ?? '', r.service, r.tag?.key ?? '', r.tag?.value ?? '', r.amount.toFixed(2), report.currency, report.final ? 'true' : 'false'].map((v, i) => (i === 7 ? v : csvField(v))).join(','))
  return `${lines.join('\r\n')}\r\n`
}

/**
 * A month's chargeback of the accounts: by cost centre, account, service and, with `tag`, the
 * tag's value ('' for the resources without it). A month is final once its last day has closed;
 * its rows are kept as first answered, so a later change of a rate never restates it.
 */
export function chargebackOf(data: MockData, region: string, accountIds: readonly string[], q: ChargebackQuery, now: number): ChargebackReport {
  const final = now >= q.end + CLOSE_MS
  const key = `${q.month}|${[...accountIds].sort().join(',')}|${q.tag ?? ''}`
  const st = costStateOf(data)
  const kept = final ? st.published[key] : undefined
  if (kept) return kept

  const amounts = new Map<string, ChargebackRow & { raw: number }>()
  for (const accountId of accountIds) {
    const account = accountOf(data.directory, accountId)
    for (const c of chargesOf(data, region, [accountId], now)) {
      const v = accrued(c, q.start, Math.min(q.end, now))
      if (!v) continue
      const tag = q.tag ? { key: q.tag, value: c.tags[q.tag] ?? '' } : undefined
      const id = [costCentreOf(account), accountId, c.service, tag?.value ?? ''].join('\u0000')
      const row = amounts.get(id) ?? { costCentre: costCentreOf(account), accountId, accountName: account?.name, service: c.service, ...(tag ? { tag } : {}), amount: 0, raw: 0 }
      row.raw += v
      amounts.set(id, row)
    }
  }
  const rows: ChargebackRow[] = [...amounts.values()]
    .map(({ raw, ...row }) => ({ ...row, amount: cents(raw) }))
    .filter((r) => r.amount > 0)
    .sort((a, b) => a.costCentre.localeCompare(b.costCentre) || a.accountId.localeCompare(b.accountId) || a.service.localeCompare(b.service) || (a.tag?.value ?? '').localeCompare(b.tag?.value ?? ''))
  const report: ChargebackReport = {
    month: q.month,
    currency: CURRENCY,
    final,
    total: cents(rows.reduce((a, r) => a + r.amount, 0)),
    rows,
    generatedAt: iso(final ? q.end + CLOSE_MS : now),
  }
  if (final) {
    report.sha256 = sha256Hex(chargebackCsvOf(report))
    st.published[key] = report
  }
  return report
}

/* ----------------------------------------------------------------------------
   SHA-256 (FIPS 180-4), synchronous: the mock signs the CSV it publishes
   ------------------------------------------------------------------------- */

// prettier-ignore
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n))

/** The SHA-256 of the text's UTF-8 bytes, as lower-case hex. */
export function sha256Hex(text: string): string {
  const bytes = new TextEncoder().encode(text)
  const padded = new Uint8Array(((bytes.length + 9 + 63) >> 6) << 6)
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  const bits = bytes.length * 8
  view.setUint32(padded.length - 8, Math.floor(bits / 0x1_0000_0000))
  view.setUint32(padded.length - 4, bits >>> 0)
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19])
  const w = new Uint32Array(64)
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let [a, b, c, d, e, f, g, k] = h
    for (let i = 0; i < 64; i++) {
      const t1 = (k + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) >>> 0
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      k = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    h[0] += a
    h[1] += b
    h[2] += c
    h[3] += d
    h[4] += e
    h[5] += f
    h[6] += g
    h[7] += k
  }
  return Array.from(h, (x) => x.toString(16).padStart(8, '0')).join('')
}

/* ----------------------------------------------------------------------------
   Budgets
   ------------------------------------------------------------------------- */

interface StoredBudget {
  budgetId: string
  accountId: string
  name: string
  tag?: { key: string; value: string }
  amount: number
  currency: string
  thresholds: number[]
  blockCreates: boolean
  version: number
  createdBy: string
  createdAt: string
  updatedAt?: string
  /** the amounts in force, oldest first: a closed month keeps the amount it had */
  amounts: { from: string; amount: number }[]
}

/** What the cost service keeps: the budgets, the Idempotency-Keys of their creates, the published months. */
interface CostState {
  budgets: StoredBudget[]
  /** the accounts whose demo budgets were laid down */
  seeded: string[]
  /** by `<account>:<user>:<Idempotency-Key>`: the body it carried and the budget it made */
  keys: Record<string, { body: string; budgetId: string }>
  /** the final months, as first answered */
  published: Record<string, ChargebackReport>
}

/** Kept on the directory, so the browser saves it with the rest of the account's state and a reload keeps it. */
function costStateOf(data: MockData): CostState {
  const dir = data.directory as Directory & { cost?: Partial<CostState> }
  const st = (dir.cost ??= {})
  st.budgets ??= []
  st.seeded ??= []
  st.keys ??= {}
  st.published ??= {}
  return st as CostState
}

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz'
const BUDGET_ID = /^bud_[0-9a-hjkmnp-tv-z]{26}$/

/** A budget id as the cost service mints them: `bud_` and 26 Crockford base32 characters (here from the seed's hash). */
function budgetIdOf(seed: string): string {
  const bits = [...sha256Hex(seed)].map((h) => parseInt(h, 16).toString(2).padStart(4, '0')).join('')
  return `bud_${Array.from({ length: 26 }, (_, i) => CROCKFORD[parseInt(bits.slice(i * 5, i * 5 + 5), 2)]).join('')}`
}

/**
 * What a budget action is decided on: `wfrn:<partition>:budgets::<account>:budget/<id>` (region-less,
 * like identity), the id's underscore written as a hyphen as a wfrn id takes none; the list and a
 * create on `budget/*`.
 */
const budgetResource = (accountId: string, budgetId = '*') => `wfrn:${DIRECTORY_PARTITION}:budgets::${accountId}:budget/${budgetId.toLowerCase().replace(/_/g, '-')}`

/** A round amount near `x`: two significant digits, up or down. */
function round2(x: number, dir: 'up' | 'down'): number {
  if (!(x > 0)) return 100
  const step = 10 ** Math.max(0, Math.floor(Math.log10(x)) - 1)
  return (dir === 'up' ? Math.ceil(x / step) : Math.max(1, Math.floor(x / step))) * step
}

/**
 * The demo's budgets, laid down the first time an account's budgets are read and sized from the
 * ledger at that moment, so they tell the same story whenever the demo runs: Compute's whole
 * account is on track, production is forecast over and blocks creates once over, test
 * environments are over, and Data is well within. Nothing here covers development, so a dev
 * machine's review has no budget rule.
 */
const SEEDS: readonly { accountId: string; name: string; tag?: { key: string; value: string }; thresholds: number[]; blockCreates: boolean; size: (spent: number, forecast: number) => number }[] = [
  { accountId: '123456789012', name: 'Compute monthly', thresholds: [50, 80, 100], blockCreates: false, size: (_s, f) => round2(f / 0.92, 'up') },
  { accountId: '123456789012', name: 'Production', tag: { key: 'environment', value: 'prod' }, thresholds: [80, 100], blockCreates: true, size: (s, f) => round2(s + (f - s) * 0.35, 'up') },
  { accountId: '123456789012', name: 'Test environments', tag: { key: 'environment', value: 'test' }, thresholds: [80, 100], blockCreates: false, size: (s) => round2(s * 0.95, 'down') },
  { accountId: '210987654321', name: 'Data platform', thresholds: [80, 100], blockCreates: false, size: (_s, f) => round2(f / 0.55, 'up') },
]

function seedBudgets(data: MockData, st: CostState, accountId: string, region: string, now: number) {
  if (st.seeded.includes(accountId)) return
  st.seeded.push(accountId)
  const monthStart = startOfMonth(now)
  // created a few days before this month began, by the account's admin
  const createdAt = iso(monthStart - 4 * DAY_MS + 9 * 3_600_000)
  for (const seed of SEEDS.filter((s) => s.accountId === accountId)) {
    const charges = chargesOf(data, region, [accountId], now, seed.tag ? { tags: { [seed.tag.key]: seed.tag.value } } : {})
    const spent = charges.reduce((a, c) => a + accrued(c, monthStart, now), 0)
    const forecast = forecastOf(charges, monthStart, startOfNextMonth(now), now)?.monthEnd ?? spent
    const amount = seed.size(spent, forecast)
    st.budgets.push({
      budgetId: budgetIdOf(`${accountId}:${seed.name}`),
      accountId,
      name: seed.name,
      ...(seed.tag ? { tag: seed.tag } : {}),
      amount,
      currency: CURRENCY,
      thresholds: seed.thresholds,
      blockCreates: seed.blockCreates,
      version: 1,
      createdBy: 'gonzalo',
      createdAt,
      amounts: [{ from: createdAt, amount }],
    })
  }
}

/** An account's budgets, by name; the demo's are laid down on the first read. */
function budgetsOf(data: MockData, accountId: string, region: string, now: number): StoredBudget[] {
  const st = costStateOf(data)
  seedBudgets(data, st, accountId, region, now)
  return st.budgets.filter((b) => b.accountId === accountId).sort((a, b) => a.name.localeCompare(b.name) || a.budgetId.localeCompare(b.budgetId))
}

/** The amount in force in a month: the last one set before the month ended. */
function amountIn(b: StoredBudget, end: number): number {
  let amount = b.amounts[0]?.amount ?? b.amount
  for (const a of b.amounts) if (Date.parse(a.from) < end) amount = a.amount
  return amount
}

/** How a budget does in the month holding `at`: spent, the forecast (spent, once the month closed), the state and the alerts it sent. */
function budgetStatusOf(data: MockData, b: StoredBudget, region: string, at: number, now: number): BudgetStatus & { exact: { spent: number; forecast: number } } {
  const start = startOfMonth(at)
  const end = startOfNextMonth(at)
  const charges = chargesOf(data, region, [b.accountId], now, b.tag ? { tags: { [b.tag.key]: b.tag.value } } : {})
  const spent = charges.reduce((a, c) => a + accrued(c, start, Math.min(end, now)), 0)
  const forecast = now < end ? (forecastOf(charges, start, end, now)?.monthEnd ?? spent) : spent
  const amount = now < end ? b.amount : amountIn(b, end)
  const state: BudgetState = spent >= amount ? 'over' : forecast > amount ? 'forecast-over' : 'ok'
  const pct = (v: number) => Math.round((v / amount) * 1000) / 10
  return {
    period: monthOf(start),
    amount,
    spent: cents(spent),
    forecast: cents(forecast),
    percent: pct(spent),
    forecastPercent: pct(forecast),
    state,
    // spend only grows within a month, so the thresholds were crossed lowest first
    alertsSent: [...b.thresholds].sort((x, y) => x - y).filter((t) => spent * 100 >= t * amount),
    asOf: iso(now),
    exact: { spent, forecast },
  }
}

function budgetBody(data: MockData, b: StoredBudget, ctx: RouteContext): ConsoleBudget {
  const { exact: _exact, ...status } = budgetStatusOf(data, b, ctx.session.region, ctx.now, ctx.now)
  const may = (action: string) => authorize(data.directory, { userId: ctx.session.userId, accountId: b.accountId }, action, budgetResource(b.accountId, b.budgetId)).allowed
  return {
    budgetId: b.budgetId,
    name: b.name,
    scope: { accountId: b.accountId, ...(b.tag ? { tag: b.tag } : {}) },
    amount: b.amount,
    currency: b.currency,
    thresholds: b.thresholds,
    blockCreates: b.blockCreates,
    version: b.version,
    createdBy: b.createdBy,
    createdAt: b.createdAt,
    ...(b.updatedAt ? { updatedAt: b.updatedAt } : {}),
    status,
    can: { update: may('budgets:update'), delete: may('budgets:delete') },
  }
}

/** The month of every period of a budget, newest first: from this month back to the one it was created in. */
function budgetPeriods(b: StoredBudget, now: number): number[] {
  const out: number[] = []
  const first = startOfMonth(Math.min(Date.parse(b.createdAt), now))
  for (let t = startOfMonth(now); t >= first; t = startOfMonth(t - 1)) out.push(t)
  return out
}

interface BudgetFields {
  name: string
  amount: number
  thresholds: number[]
  blockCreates: boolean
  tag?: { key: string; value: string }
}

/** A budget's body checked as the cost service checks it; the problems name the fields as JSON pointers. */
function readBudgetInput(body: Record<string, unknown>, ctx: RouteContext, others: readonly StoredBudget[], existing?: StoredBudget): BudgetFields | RouteAnswer {
  const problems: Problem[] = []
  const bad = (field: string, message: string, code = 'invalid') => problems.push({ level: 'error', code, field, message })
  const name = typeof body.name === 'string' ? body.name.trim() : ''
  if (!name || name.length > 64) bad('/name', 'A name of 1 to 64 characters', name ? 'invalid' : 'required')
  else if (others.some((b) => b.name.toLowerCase() === name.toLowerCase() && b.budgetId !== existing?.budgetId)) bad('/name', `The account has a budget named ${name} already`, 'duplicate')
  const amount = body.amount
  if (typeof amount !== 'number' || !Number.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) bad('/amount', 'A monthly amount above 0 and at most 1,000,000,000', amount === undefined ? 'required' : 'invalid')
  if (body.currency !== undefined && body.currency !== CURRENCY) bad('/currency', `Budgets are in ${CURRENCY}, the price lists' currency`)
  let thresholds = [...DEFAULT_THRESHOLDS]
  if (body.thresholds !== undefined) {
    const t = body.thresholds
    if (!Array.isArray(t) || t.length < 1 || t.length > 5) bad('/thresholds', 'One to five thresholds')
    else if (t.some((x) => typeof x !== 'number' || !Number.isInteger(x) || x < 1 || x > 1000)) bad('/thresholds', 'Each threshold is a whole percentage from 1 to 1000')
    else if (new Set(t).size !== t.length) bad('/thresholds', 'Each threshold once')
    else thresholds = [...(t as number[])].sort((a, b) => a - b)
  }
  if (body.blockCreates !== undefined && typeof body.blockCreates !== 'boolean') bad('/blockCreates', 'blockCreates is true or false')
  let tag: BudgetFields['tag']
  const scope = body.scope
  if (existing) {
    // the scope never changes: a budget for another scope is a new budget
    if (scope !== undefined) {
      const s = scope as { accountId?: unknown; tag?: { key?: unknown; value?: unknown } } | null
      const same = !!s && typeof s === 'object' && (s.accountId === undefined || s.accountId === existing.accountId) && (s.tag?.key ?? null) === (existing.tag?.key ?? null) && (s.tag?.value ?? null) === (existing.tag?.value ?? null)
      if (!same) bad('/scope', 'The scope never changes; create another budget for another scope')
    }
    tag = existing.tag
  } else if (scope !== undefined) {
    if (!scope || typeof scope !== 'object' || Array.isArray(scope)) bad('/scope', 'scope is an object')
    else {
      const s = scope as { accountId?: unknown; tag?: unknown }
      if (s.accountId !== undefined && s.accountId !== ctx.session.accountId) bad('/scope/accountId', `A budget is created in the session's account, ${ctx.session.accountId}`, 'scope.account')
      if (s.tag !== undefined) {
        const t = s.tag as { key?: unknown; value?: unknown } | null
        const key = typeof t?.key === 'string' ? t.key : ''
        const value = typeof t?.value === 'string' ? t.value.trim() : ''
        if (!TAG_KEY.test(key)) bad('/scope/tag/key', 'A tag key: 1 to 32 lower-case letters, digits and dashes')
        if (!value || value.length > 256) bad('/scope/tag/value', 'A tag value of 1 to 256 characters')
        if (TAG_KEY.test(key) && value && value.length <= 256) tag = { key, value }
      }
    }
  }
  if (problems.length) return { status: 400, body: { error: 'validation.failed', detail: problems.map((p) => p.message).join('; '), problems } }
  return { name, amount: cents(amount as number), thresholds, blockCreates: body.blockCreates === true, ...(tag ? { tag } : {}) }
}

/** A JSON body with its keys in order, so the same body always reads the same. */
function canonical(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(',')}]`
  if (v && typeof v === 'object')
    return `{${Object.keys(v)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${canonical((v as Record<string, unknown>)[k])}`)
      .join(',')}}`
  return JSON.stringify(v ?? null)
}

/** The version an If-Match names: `3`, `"3"` or `W/"3"`. */
function versionOf(header: string | undefined): number | undefined {
  if (!header) return undefined
  const n = Number(header.replace(/^W\//, '').replace(/"/g, ''))
  return Number.isInteger(n) ? n : NaN
}

function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { status: 200, body: { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null } }
}

const notFoundBudget = (budgetId: string): RouteAnswer => ({ status: 404, body: { error: 'budget.not-found', detail: `No budget ${budgetId} in this account` } })

/** The tags a resource made from these attributes would carry, as the list derives them. */
function tagsOfNew(attributes: Record<string, unknown>): Record<string, string> {
  const tags: Record<string, string> = { visibility: 'team' }
  if (typeof attributes.owner === 'string') tags.owner = attributes.owner
  if (typeof attributes.environment === 'string') tags.environment = attributes.environment
  const raw = attributes.tags
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) for (const [k, v] of Object.entries(raw)) if (typeof v === 'string') tags[k] = v
  return tags
}

/** A rule of a create's review, as the preview's policy carries it; a budget's names the budget. */
type PolicyRule = { id: string; result: string; message: string; budgetId?: string }

/**
 * The rules a create's preview gets from the account's budgets, as `checkBudgets` decides them: a
 * budget that blocks creates and is over this month, and whose scope covers the new resource, is a
 * `deny` rule `budget.exceeded`; a budget whose forecast with the new resource is over the amount
 * is a `warn` rule `budget.forecast-over`. The new resource adds its list price for the rest of
 * the month (nothing when no price covers it).
 */
export function budgetRules(data: MockData, s: RouteContext['session'], rt: ResourceType, attributes: Record<string, unknown>, now: number): PolicyRule[] {
  const budgets = budgetsOf(data, s.accountId, s.region, now)
  if (!budgets.length) return []
  const tags = tagsOfNew(attributes)
  const monthly = listPriceOf(rt, attributes)?.monthly ?? 0
  const added = (monthly * (startOfNextMonth(now) - now)) / (daysInMonth(now) * DAY_MS)
  const rules: PolicyRule[] = []
  for (const b of budgets) {
    if (b.tag && tags[b.tag.key] !== b.tag.value) continue
    const st = budgetStatusOf(data, b, s.region, now, now)
    if (b.blockCreates && st.exact.spent >= st.amount)
      rules.push({ id: 'budget.exceeded', result: 'deny', message: `The budget ${b.name} is over: ${usd(st.spent)} of ${usd(st.amount)} spent in ${st.period}`, budgetId: b.budgetId })
    else if (st.exact.forecast + added > st.amount)
      rules.push({ id: 'budget.forecast-over', result: 'warn', message: `The budget ${b.name} is forecast to be over: ${usd(cents(st.exact.forecast + added))} of ${usd(st.amount)} by the end of ${st.period}${added ? ', with this one' : ''}`, budgetId: b.budgetId })
  }
  return rules
}

/**
 * A create's answer as the BFF gives it when a budget refuses: the resource routes answer a deny
 * of the review as the platform's `policy.denied`, but the BFF asks the budgets before it starts
 * the workflow and answers 409 `budget.exceeded`, naming the budgets that refuse in `detail.budgets`.
 */
function budgetRefusal(data: MockData, answer: RouteAnswer | null): RouteAnswer | null {
  const body = answer?.body as { error?: string; policy?: { rules?: PolicyRule[] } } | undefined
  if (answer?.status !== 409 || body?.error !== 'policy.denied') return answer
  const denying = (body.policy?.rules ?? []).filter((r): r is PolicyRule & { budgetId: string } => r.id === 'budget.exceeded' && r.result === 'deny' && typeof r.budgetId === 'string')
  if (!denying.length) return answer
  const { budgets: kept } = costStateOf(data)
  const budgets = denying.map((r) => ({ budgetId: r.budgetId, name: kept.find((b) => b.budgetId === r.budgetId)?.name, message: r.message }))
  return {
    status: 409,
    body: {
      error: 'budget.exceeded',
      message: budgets[0].message,
      problems: budgets.map((b) => ({ level: 'error', code: 'budget.exceeded', message: b.message })),
      detail: { budgets },
    },
  }
}

function budgetRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const can = (action: string, budgetId?: string) => authorize(data.directory, { userId: s.userId, accountId: s.accountId }, action, budgetResource(s.accountId, budgetId))
  const st = costStateOf(data)
  const etag = (b: StoredBudget) => ({ ETag: `"${b.version}"` })

  if (path === '/console/v1/budgets') {
    if (method === 'GET') {
      const d = can('budgets:list')
      if (!d.allowed) return refusedCost(d, ctx, path)
      return pageOf(
        budgetsOf(data, s.accountId, s.region, ctx.now).map((b) => budgetBody(data, b, ctx)),
        params,
      )
    }
    if (method === 'POST') {
      const d = can('budgets:create')
      if (!d.allowed) return refusedCost(d, ctx, path)
      const others = budgetsOf(data, s.accountId, s.region, ctx.now)
      const key = ctx.headers['idempotency-key']
      const keyId = key ? `${s.accountId}:${s.userId}:${key}` : undefined
      const sent = canonical(ctx.body)
      const prior = keyId ? st.keys[keyId] : undefined
      if (prior) {
        if (prior.body !== sent) return { status: 409, body: { error: 'idempotency.mismatch', detail: 'This Idempotency-Key was sent before with another body' } }
        const made = others.find((b) => b.budgetId === prior.budgetId)
        if (made) return { status: 201, body: budgetBody(data, made, ctx), headers: { ...etag(made), 'Idempotent-Replayed': 'true' } }
      }
      const input = readBudgetInput(ctx.body, ctx, others)
      if (isAnswer(input)) return input
      const at = iso(ctx.now)
      const b: StoredBudget = {
        budgetId: budgetIdOf(`${s.accountId}:${input.name}:${ctx.now}:${st.budgets.length}`),
        accountId: s.accountId,
        name: input.name,
        ...(input.tag ? { tag: input.tag } : {}),
        amount: input.amount,
        currency: CURRENCY,
        thresholds: input.thresholds,
        blockCreates: input.blockCreates,
        version: 1,
        createdBy: s.userId,
        createdAt: at,
        amounts: [{ from: at, amount: input.amount }],
      }
      st.budgets.push(b)
      if (keyId) st.keys[keyId] = { body: sent, budgetId: b.budgetId }
      return { status: 201, body: budgetBody(data, b, ctx), headers: etag(b) }
    }
    return { status: 405, body: { error: `${method} is not allowed on the budgets` } }
  }

  const m = /^\/console\/v1\/budgets\/([^/]+)(\/periods)?$/.exec(path)
  if (!m) return null
  const budgetId = decodeURIComponent(m[1])
  // a change names the version it read before anything else is asked
  const version = versionOf(ctx.headers['if-match'])
  if (method === 'PUT' && !m[2] && version === undefined) {
    const message = 'If-Match is required: the ETag the budget was read with'
    return { status: 400, body: { error: 'validation.failed', detail: message, problems: [{ level: 'error', code: 'required', field: 'If-Match', message }] } }
  }
  // an id of another shape names no budget: not found before the policies are asked
  if (!BUDGET_ID.test(budgetId)) return notFoundBudget(budgetId)
  const b = budgetsOf(data, s.accountId, s.region, ctx.now).find((x) => x.budgetId === budgetId)

  if (m[2]) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on a budget's periods` } }
    const d = can('budgets:get', budgetId)
    if (!d.allowed) return refusedCost(d, ctx, path)
    if (!b) return notFoundBudget(budgetId)
    return pageOf(
      budgetPeriods(b, ctx.now).map((t) => {
        const { exact: _exact, ...status } = budgetStatusOf(data, b, s.region, t, ctx.now)
        return status
      }),
      params,
    )
  }

  if (method === 'GET') {
    const d = can('budgets:get', budgetId)
    if (!d.allowed) return refusedCost(d, ctx, path)
    if (!b) return notFoundBudget(budgetId)
    return { status: 200, body: budgetBody(data, b, ctx), headers: etag(b) }
  }
  if (method === 'PUT') {
    const d = can('budgets:update', budgetId)
    if (!d.allowed) return refusedCost(d, ctx, path)
    if (!b) return notFoundBudget(budgetId)
    if (version !== b.version) return { status: 409, body: { error: 'version.conflict', detail: `Someone changed the budget first: it is at version ${b.version} now` } }
    const input = readBudgetInput(ctx.body, ctx, budgetsOf(data, s.accountId, s.region, ctx.now), b)
    if (isAnswer(input)) return input
    const at = iso(ctx.now)
    if (input.amount !== b.amount) b.amounts.push({ from: at, amount: input.amount })
    Object.assign(b, { name: input.name, amount: input.amount, thresholds: input.thresholds, blockCreates: input.blockCreates, version: b.version + 1, updatedAt: at })
    return { status: 200, body: budgetBody(data, b, ctx), headers: etag(b) }
  }
  if (method === 'DELETE') {
    const d = can('budgets:delete', budgetId)
    if (!d.allowed) return refusedCost(d, ctx, path)
    if (!b) return notFoundBudget(budgetId)
    if (version !== undefined && version !== b.version) return { status: 409, body: { error: 'version.conflict', detail: `Someone changed the budget first: it is at version ${b.version} now` } }
    st.budgets = st.budgets.filter((x) => x !== b)
    return { status: 204 }
  }
  return { status: 405, body: { error: `${method} is not allowed on a budget` } }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

export function costRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (path === '/console/v1/cost' && method === 'GET') {
    const q = readReportQuery(params)
    if (isAnswer(q)) return q
    const { accountIds, skipped, denied } = costAccounts(data, ctx, q.scope)
    if (denied) return refusedCost(denied, ctx, path)
    const charges = chargesOf(data, ctx.session.region, accountIds, ctx.now, q.filter)
    const report = costReportOf(data, charges, q, ctx.now)
    return {
      status: 200,
      body: q.scope === 'organization' ? { ...report, skippedAccounts: skipped } : report,
    }
  }

  if (path === '/console/v1/cost/price-lists' && method === 'GET') {
    const { denied } = costAccounts(data, ctx, 'account')
    if (denied) return refusedCost(denied, ctx, path)
    return { status: 200, body: priceListsOf(data) }
  }

  // the chargeback, JSON and CSV alike, is what cost:export allows: an organization's leaves out
  // the accounts where it is denied and names them (skippedAccounts, or a header on the CSV)
  if ((path === '/console/v1/cost/chargeback' || path === '/console/v1/cost/chargeback.csv') && method === 'GET') {
    const q = readChargebackQuery(params, ctx.now)
    if (isAnswer(q)) return q
    const { accountIds, skipped, denied } = costAccounts(data, ctx, q.scope, 'cost:export')
    if (denied) return refusedCost(denied, ctx, path)
    const report = chargebackOf(data, ctx.session.region, accountIds, q, ctx.now)
    const organization = q.scope === 'organization'
    if (!path.endsWith('.csv')) return { status: 200, body: organization ? { ...report, skippedAccounts: skipped } : report }
    const text = chargebackCsvOf(report)
    const name = `chargeback-${q.month}-${organization ? 'organization' : ctx.session.accountId}${q.tag ? `-by-${q.tag}` : ''}.csv`
    return {
      status: 200,
      body: text,
      headers: {
        'Content-Type': 'text/csv; charset=utf-8',
        'Content-Disposition': `attachment; filename="${name}"`,
        'X-Content-Sha256': report.sha256 ?? sha256Hex(text),
        ...(organization && skipped.length ? { 'X-Console-Skipped-Accounts': skipped.join(',') } : {}),
      },
    }
  }

  // a create a budget refuses: the BFF asks the account's budgets before it starts the workflow and
  // answers its own 409, so the create runs through the resource routes and a budget's deny is reworded
  if (/^\/console\/v1\/r\/[a-z0-9-]{1,64}$/.test(path) && method === 'POST') return budgetRefusal(data, resourceRoutes(data, ctx, path, params, method))

  const m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)\/cost$/.exec(path)
  if (m && method === 'GET') {
    const rt = typeOf(data, m[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
    const wfrnParam = decodeURIComponent(m[2])
    const r = resourceByWfrn(data, rt, wfrnParam)
    const accounts = data.directory.accounts.map((a) => a.accountId)
    const gone = { status: 404, body: { error: 'resource.not-found', detail: `No ${rt.name} lives at ${wfrnParam} in this account` } }
    if (!r || accountOfResource(r, accounts) !== ctx.session.accountId) return gone
    const wfrn = wfrnOfResource(data, ctx.session, rt, r)
    const d = authorize(data.directory, { userId: ctx.session.userId, accountId: ctx.session.accountId }, 'cost:list', wfrn)
    if (!d.allowed) return refusedCost(d, ctx, path)
    const [c] = chargesOf(data, ctx.session.region, [ctx.session.accountId], ctx.now, { wfrn })
    if (!c) return gone
    return { status: 200, body: resourceCostOf(c, lifecycleOf(r.status, rt.console?.lifecycle) ?? r.status, ctx.now) }
  }

  return budgetRoutes(data, ctx, path, params, method)
}
