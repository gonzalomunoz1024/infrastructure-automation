import { dayLabel, formatNumber, plural } from '@iaf/ui'
import { OTHER_KEY, type CostReport } from '../../lib/cost'
import { DEFAULT_PERIOD, DEFAULT_SCOPE, isInsightsPeriod, isInsightsScope, type Insights, type InsightsPeriod, type InsightsScope } from '../../lib/insights'

/*
 * The executive home's words (WP 9.5): how a period, a scope, a lead time, a share, a change or an
 * amount reads on the page and on the home's card, how the page's query names its view, and how
 * the cost report's cost centres become the rows of spend by team. Pure, so the tests read them
 * the way people do.
 */

export const PERIOD_LABEL: Record<InsightsPeriod, string> = { '7d': '7 days', '30d': '30 days', '90d': '90 days' }
export const SCOPE_LABEL: Record<InsightsScope, string> = { account: 'This account', organization: 'All my accounts' }

export interface InsightsView {
  period: InsightsPeriod
  scope: InsightsScope
}

/** The view the page's query asks for; the contract's defaults stand in for anything missing or unknown. */
export function viewOf(query: Record<string, string | undefined>): InsightsView {
  return { period: isInsightsPeriod(query.period) ? query.period : DEFAULT_PERIOD, scope: isInsightsScope(query.scope) ? query.scope : DEFAULT_SCOPE }
}

/** "the last 30 days in all your accounts", "the last 7 days in Compute": what the numbers on screen cover. */
export function viewWords(view: InsightsView, accountName: string): string {
  return `the last ${PERIOD_LABEL[view.period]} in ${view.scope === 'account' ? accountName : 'all your accounts'}`
}

/** When the numbers were computed, in UTC as the periods are cut: "23 Oct, 10:00 UTC". */
export function asOfWords(iso: string): string {
  return `${new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' }).format(new Date(iso))} UTC`
}

/** A forecast's month, `YYYY-MM`: "October". */
export function monthWords(month: string): string {
  return new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' }).format(new Date(`${month}-01T00:00:00Z`))
}

/** A day of a period, in UTC as the periods are cut: "24 Sept". */
export function dayWords(iso: string): string {
  return dayLabel(iso, { timeZone: 'UTC', now: iso })
}

/** A duration in the largest unit that reads well: "8 s", "12 min", "3 h", "2 days", "1.5 days". */
export function durationWords(ms: number): string {
  const s = Math.round(Math.max(0, ms) / 1000)
  if (s < 60) return `${s} s`
  const min = Math.round(ms / 60_000)
  if (min < 60) return `${min} min`
  const h = Math.round(ms / 3_600_000)
  if (h < 24) return `${h} h`
  const days = Math.round(ms / 8_640_000) / 10
  return `${formatNumber(days)} ${days === 1 ? 'day' : 'days'}`
}

/** A share as a whole percentage that never reads as all or none when it is neither: "92%", "99%", "<1%". */
export function shareWords(part: number, whole: number): string {
  if (!whole) return '—'
  const pct = Math.round((part / whole) * 100)
  if (pct === 100 && part < whole) return '99%'
  if (pct === 0 && part > 0) return '<1%'
  return `${pct}%`
}

/** How the count moved since the period began: "+223 since 24 Sept", "−3 since 17 Oct", "No change since 17 Oct". */
export function changeWords(now: number, atStart: number, from: string): string {
  const d = now - atStart
  const since = `since ${dayWords(from)}`
  return d === 0 ? `No change ${since}` : `${d > 0 ? '+' : '−'}${formatNumber(Math.abs(d))} ${since}`
}

/** "1,933 hours", "1 hour", "0.5 hours". */
export function hoursWords(hours: number): string {
  const n = hours >= 10 ? Math.round(hours) : Math.round(hours * 10) / 10
  return `${formatNumber(n)} ${n === 1 ? 'hour' : 'hours'}`
}

/** "Compute", "Compute and Data", "Compute, Data and Storage". */
export function listWords(names: readonly string[]): string {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** An amount in the report's currency, to the cent. */
export function money(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
}

/** A chart's scale mark: the amount rounded to what an axis needs, "$12K". */
export function moneyTick(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(amount)
}

/** The memberships the numbers leave out: accounts the person belongs to that the answer does not cover. */
export function leftOut<A extends { accountId: string }>(memberships: readonly A[], covered: readonly { accountId: string }[]): A[] {
  const ids = new Set(covered.map((a) => a.accountId))
  return memberships.filter((m) => !ids.has(m.accountId))
}

/** The headline's words for one answer: each tile's value and the quiet line under it. */
export interface Headline {
  managed: { value: string; hint: string }
  selfServed: { value: string; hint: string }
  leadTime: { value: string; hint: string }
  hoursSaved: { value: string; hint: string; needsBaseline: boolean }
  /** the open findings go in a dot of their own; `note` follows it */
  drift: { value: string; open: number; openWords: string; note: string }
}

export function headlineOf(n: Insights): Headline {
  const { requests, leadTime, drift } = n
  const baseline = leadTime.baselineP50Ms
  return {
    managed: { value: formatNumber(n.managed.resources), hint: changeWords(n.managed.resources, n.managed.atStart, n.from) },
    selfServed: requests.total
      ? { value: shareWords(requests.selfServed, requests.total), hint: `${formatNumber(requests.selfServed)} of ${formatNumber(requests.total)} requests` }
      : { value: 'No requests', hint: 'None started in the period' },
    leadTime: !requests.selfServed
      ? { value: 'None yet', hint: 'No self-served request in the period' }
      : { value: durationWords(leadTime.p50Ms), hint: baseline !== undefined ? `against ${durationWords(baseline)} by ticket` : `No ticket baseline; 90% within ${durationWords(leadTime.p90Ms)}` },
    hoursSaved:
      n.hoursSaved !== undefined
        ? { value: hoursWords(n.hoursSaved), hint: leadTime.baselineSource ? `Against the ${leadTime.baselineSource}` : 'Against the ticket baseline', needsBaseline: false }
        : {
            value: 'Needs a baseline',
            hint: requests.selfServed ? 'No ticket export covers these requests, so there is nothing to compare with' : 'No self-served request to compare with a ticket',
            needsBaseline: true,
          },
    drift: driftOf(drift),
  }
}

function driftOf(drift: Insights['drift']): Headline['drift'] {
  const openWords = drift.open ? `${formatNumber(drift.open)} open now` : 'Nothing open now'
  const fixedIn = drift.meanTimeToFixMs !== undefined ? `fixed in ${durationWords(drift.meanTimeToFixMs)} on average` : drift.found && !drift.fixed ? 'none fixed yet' : ''
  if (!drift.found && !drift.fixed) return { value: 'None found', open: drift.open, openWords, note: '' }
  // a finding fixed in the period may have been found before it: then "3 of 2" would not read
  if (drift.fixed > drift.found) return { value: `${formatNumber(drift.fixed)} fixed`, open: drift.open, openWords, note: [`${formatNumber(drift.found)} found`, fixedIn].filter(Boolean).join(', ') }
  return { value: `${formatNumber(drift.fixed)} of ${formatNumber(drift.found)}`, open: drift.open, openWords, note: fixedIn }
}

/** One row of spend by team: a cost centre, the accounts charged to it and the teams that own them. */
export interface SpendRow {
  key: string
  label: string
  accounts: string[]
  teams: string[]
  amount: number
  /** of the report's total, 0 to 1 */
  share: number
}

/** The report's cost centres as rows, largest first as the report ranks them; the accounts beyond its top fall under Other. */
export function spendRows(report: CostReport, teamOf: (accountId: string) => string | undefined): SpendRow[] {
  const named = new Set(report.groups.map((g) => g.key))
  const accounts = report.accounts ?? []
  return report.groups.map((g) => {
    const mine = accounts.filter((a) => (g.key === OTHER_KEY ? !named.has(a.costCentre ?? '') : a.costCentre === g.key))
    const teams = [...new Set(mine.map((a) => teamOf(a.accountId)).filter((t): t is string => !!t))]
    return { key: g.key, label: g.label, accounts: mine.map((a) => a.name ?? a.accountId), teams, amount: g.total, share: report.total ? g.total / report.total : 0 }
  })
}

/** What spend by team says under its table: the month's forecast, resources no price covers, accounts the report left out. */
export function spendNotes(report: CostReport, nameOf: (accountId: string) => string): string[] {
  const cur = report.currency
  const f = report.forecast
  const unpriced = report.unpriced.resources
  const skipped = report.skippedAccounts ?? []
  return [
    f &&
      `${f.month ? monthWords(f.month) : 'This month'} so far: ${money(f.monthToDate ?? 0, cur)}. Expected by the month’s end: ${money(f.monthEnd, cur)}, between ${money(f.low, cur)} and ${money(f.high, cur)}.`,
    unpriced > 0 && `${plural(unpriced, 'resource')} with no list price ${unpriced === 1 ? 'is' : 'are'} not counted.`,
    skipped.length > 0 && `Left out: ${listWords(skipped.map(nameOf))}, where your role may not read cost.`,
  ].filter((n): n is string => !!n)
}
