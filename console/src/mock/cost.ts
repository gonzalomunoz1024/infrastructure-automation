import { lifecycleOf } from '@iaf/console-kit'
import { NO_TAG_KEY, OTHER_KEY, type CostAccount, type CostForecast, type CostGroup, type CostGroupBy, type CostPoint, type CostReport, type CostScope } from '../lib/cost'
import { capitalise, titleOf } from '../lib/descriptors'
import type { ResourceType } from '../lib/types'
import { idFrom, priceOf, typeOf, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { accountOf, authorize, everythingIn, membershipsOf, type Decision, type DirectoryAccount } from './directory'
import { accountOfResource, consoleResourceOf } from './resources'

/*
 * Phase 9 in the mock BFF: cost, budgets and chargeback, as the cost service answers them through
 * the BFF (hub decision 25). The ledger is computed, not stored: a resource is charged while it is
 * active, updating or deleting, from its creation to its deletion (or now), at its price now
 * (`priceOf`, the list prices the create review quotes), accrued by the second at
 * `monthly / days in the month`. Every answer reads the same charges, so the Cost Explorer, a
 * budget and the executive home's spend by team agree to the cent.
 */

export const DAY_MS = 86_400_000
export const CURRENCY = 'USD'
/** The cost service's limits on one query: 366 days by day, 37 months by month. */
const MAX_DAYS = 366
const MAX_MONTHS = 37
const GROUP_BY = /^(service|type|account|region|costCentre|tag:[a-z0-9-]{1,32})$/
const TAG_PARAM = /^tag\.([a-z0-9-]{1,32})$/
const DATE = /^\d{4}-\d{2}-\d{2}$/

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

/** The finance cost centre an account is charged to: its own, else its owning team (the cost service's fallback). */
export function costCentreOf(account: DirectoryAccount | undefined): string {
  return account?.costCentre ?? account?.owningTeam ?? 'unassigned'
}

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

function chargeOf(rt: ResourceType, status: string, attributes: Record<string, unknown>, created: number, changed: number, now: number): Pick<Charge, 'monthly' | 'priced' | 'from' | 'to' | 'live'> {
  const price = priceOf(rt, attributes)
  const cls = lifecycleOf(status, rt.console?.lifecycle) ?? 'active'
  const from = Math.min(created, now)
  // never charged: still being created, or failed before it existed at the provider
  if (cls === 'pending' || cls === 'failed')
    return {
      monthly: price?.monthly ?? 0,
      priced: !!price,
      from,
      to: from,
      live: false,
    }
  const live = cls !== 'deleted'
  const to = live ? now : Math.max(from, Math.min(changed, now))
  return { monthly: price?.monthly ?? 0, priced: !!price, from, to, live }
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

/** The accounts a cost read covers, by `scope`: the session's, or every membership where `cost:list` is allowed. */
export function costAccounts(data: MockData, ctx: RouteContext, scope: CostScope, action = 'cost:list'): { accountIds: string[]; skipped: string[]; denied?: Decision } {
  const { userId, accountId } = ctx.session
  const decide = (id: string) => authorize(data.directory, { userId, accountId: id }, action, everythingIn(id))
  if (scope === 'account') {
    const d = decide(accountId)
    return d.allowed ? { accountIds: [accountId], skipped: [] } : { accountIds: [], skipped: [], denied: d }
  }
  const accountIds: string[] = []
  const skipped: string[] = []
  for (const m of membershipsOf(data.directory, userId)) (decide(m.accountId).allowed ? accountIds : skipped).push(m.accountId)
  return { accountIds, skipped }
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
  const series: CostPoint[] = periods.map(({ start, end }) => {
    const groups: Record<string, number> = {}
    let raw = 0
    for (const { c, g } of keyed) {
      const v = accrued(c, Math.max(start, q.from), end)
      if (!v) continue
      raw += v
      const k = keyOf(g)
      if (k) groups[k] = (groups[k] ?? 0) + v
    }
    for (const k of Object.keys(groups)) groups[k] = cents(groups[k])
    const point: CostPoint = {
      start: dayOf(start),
      total: q.groupBy ? cents(Object.values(groups).reduce((a, b) => a + b, 0)) : cents(raw),
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

/** The rules a create's preview gets from the account's budgets (`budget.exceeded`, `budget.forecast-over`); none yet. */
export function budgetRules(
  _data: MockData,
  _s: RouteContext['session'],
  _rt: ResourceType,
  _attributes: Record<string, unknown>,
  _now: number,
): { id: string; result: string; message: string; budgetId?: string }[] {
  return []
}

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
  return null
}
