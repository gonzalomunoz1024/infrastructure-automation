import { get, page, qs, request, type QueryParams } from './api'
import { BFF_BASE, idempotencyKey } from './bff'

/*
 * Cost, budgets and chargeback (WP 7.3, 9.3 and 9.9) as the BFF serves them from the cost service
 * (hub decision 25): what resources cost by the platform's price lists, grouped and over time with
 * a month-end forecast; monthly budgets per account or one tag within it, with alerts and an
 * optional block on creates; and a month's chargeback by cost centre, final once the month closes.
 * Amounts are decimal numbers in the report's or the budget's currency (USD for every price list
 * today), and every one is an estimate from list prices, never an invoice.
 */

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

export type CostGranularity = 'day' | 'month'
/** What a report's groups are: service, type, account, region, costCentre, or `tag:<key>`. */
export type CostGroupBy = 'service' | 'type' | 'account' | 'region' | 'costCentre' | `tag:${string}`
/** The session's account, or every account the person may read cost in (`cost:list` on each). */
export type CostScope = 'account' | 'organization'

/** The group of the resources without the grouped tag (labelled "No <key> tag"). */
export const NO_TAG_KEY = '__none'
/** The groups past `top`, summed into one (labelled "Other"). */
export const OTHER_KEY = '__other'
/** The thresholds a budget alerts at when none are given, in percent of its amount. */
export const DEFAULT_THRESHOLDS = [80, 100]

export interface CostQuery {
  /** the first day, `YYYY-MM-DD` */
  from: string
  /** the day after the last, `YYYY-MM-DD`: the range is half open */
  to: string
  granularity?: CostGranularity
  groupBy?: CostGroupBy
  /** how many groups to keep before the rest are summed into `__other`; 10 by default, at most 50 */
  top?: number
  scope?: CostScope
  service?: string
  type?: string
  region?: string
  wfrn?: string
  /** sent as `tag.<key>=<value>` */
  tags?: Record<string, string>
}

export interface CostGroup {
  /** the value grouped by, `__none` or `__other` */
  key: string
  /** what to show: an account's name, "No <key> tag", "Other" */
  label: string
  total: number
}

export interface CostPoint {
  /** the day, or the first of the month */
  start: string
  total: number
  /** the point's amount per group key */
  groups: Record<string, number>
  /** the point holds today, which is still open */
  provisional?: boolean
}

export interface CostForecast {
  /** `YYYY-MM` */
  month?: string
  monthToDate?: number
  /** the month's expected total */
  monthEnd: number
  low: number
  high: number
  /** month to date plus the run rate for the days left, banded by the last 14 closed days */
  method: 'run-rate'
}

export interface CostAccount {
  accountId: string
  name?: string
  costCentre?: string
}

export interface CostReport {
  currency: string
  from: string
  to: string
  granularity: CostGranularity
  groupBy?: string
  total: number
  /** largest first; empty without groupBy */
  groups: CostGroup[]
  series: CostPoint[]
  /** when the range holds the current month */
  forecast?: CostForecast
  /** what the selected resources cost a month at today's rates */
  runRate: { monthly: number; resources?: number }
  /** the newest change the ledger has applied */
  asOf: string
  /** the last closed day; later points are provisional */
  closedThrough?: string
  source: 'list-prices'
  /** charged resources the filters select that no price covers, today */
  unpriced: { resources: number; types?: string[] }
  /** with groupBy account or costCentre: the accounts named, with their names and cost centres */
  accounts?: CostAccount[]
  /** scope=organization: memberships left out because cost:list is denied there */
  skippedAccounts?: string[]
}

/** Which price priced a resource. */
export interface PriceRule {
  priceList: string
  provider: string
  action: string
  /** the attribute whose value picked the price, when the price depends on one */
  by?: string
  value?: unknown
}

/** `GET /console/v1/r/{type}/{wfrn}/cost`: one resource's price. */
export interface ResourceCost {
  wfrn: string
  accountId: string
  type: string
  service: string
  state?: string
  /** whether it is charged now (it exists at the provider) */
  charged: boolean
  /** its monthly rate now; 0 when not charged or unpriced */
  monthly: number
  monthToDate: number
  currency: string
  /** when the current rate started */
  since?: string
  source: 'list-prices' | 'unpriced'
  rule?: PriceRule
  /** whether the priced attribute came from the provider's last observation or the desired attributes */
  pricedFrom?: 'observed' | 'desired'
  /** the rates of the last 13 months, newest first */
  history?: { from: string; to?: string; monthly: number }[]
}

export interface PriceList {
  name: string
  title?: string
  currency: string
  /** the platform content version it was read at */
  version: string
  /** a flat monthly price, or a price per value of `by` */
  prices: {
    provider: string
    action: string
    by?: string
    monthly: number | Record<string, number>
  }[]
}

/** A type's `cost` block: which price prices its resources. */
export interface PriceBinding {
  type: string
  priceList: string
  provider: string
  action: string
  by?: string
}

export interface PriceLists {
  items: PriceList[]
  next: string | null
  bindings: PriceBinding[]
}

export interface BudgetScope {
  accountId: string
  /** only resources with this tag value count */
  tag?: { key: string; value: string }
}

export interface BudgetInput {
  name: string
  /** on create only; the account is always the session's */
  scope?: BudgetScope
  /** a month's amount */
  amount: number
  currency?: string
  /** percentages of the amount that alert, at most once each a month; 80 and 100 by default */
  thresholds?: number[]
  /** refuse creates in the budget's scope while it is over (a `budget.exceeded` preview rule) */
  blockCreates?: boolean
}

export type BudgetState = 'ok' | 'forecast-over' | 'over'

export interface BudgetStatus {
  /** `YYYY-MM` */
  period: string
  /** the amount in force for the period */
  amount: number
  spent: number
  /** the month's expected total; equal to spent for a closed month */
  forecast: number
  /** spent over amount, in percent */
  percent: number
  forecastPercent?: number
  state: BudgetState
  /** the thresholds crossed this period, in the order they were crossed */
  alertsSent: number[]
  asOf?: string
}

export interface Budget {
  budgetId: string
  name: string
  scope: BudgetScope
  amount: number
  currency: string
  thresholds: number[]
  blockCreates: boolean
  version: number
  createdBy?: string
  createdAt: string
  updatedAt?: string
  status: BudgetStatus
}

/** A budget as the console reads it: what the person may do with it. */
export interface ConsoleBudget extends Budget {
  can: { update: boolean; delete: boolean }
}

/** The live stream's `budget.alert` data (the cost service's BudgetThresholdCrossed). */
export interface BudgetAlert {
  budgetId: string
  name: string
  threshold: number
  percent: number
  period: string
}

export interface ChargebackRow {
  costCentre: string
  accountId: string
  accountName?: string
  service: string
  /** the tag value the row is split by, with `tag=<key>` */
  tag?: { key: string; value: string }
  amount: number
}

export interface ChargebackReport {
  /** `YYYY-MM` */
  month: string
  currency: string
  /** the month is closed and was published to finance */
  final: boolean
  total: number
  /** by cost centre, account, service and tag value */
  rows: ChargebackRow[]
  generatedAt: string
  /** a final month's: the sha256 of the published CSV of every account */
  sha256?: string
  /** scope=organization: memberships left out because cost:export is denied there (the CSV names them in X-Console-Skipped-Accounts) */
  skippedAccounts?: string[]
}

export interface ChargebackQuery {
  /** `YYYY-MM` */
  month: string
  scope?: CostScope
  /** a tag key to split each row by */
  tag?: string
}

/** A rule a budget adds to a create's review: a block while it is over, a warning when the create takes it over. */
export interface BudgetRule {
  id: 'budget.exceeded' | 'budget.forecast-over'
  result: 'deny' | 'warn'
  message: string
  budgetId: string
}

/** The budget rules among a review's policy rules (the preview carries the budget in `budgetId`). */
export function budgetRulesOf(rules: readonly { id: string; result: string; message: string }[] | undefined): BudgetRule[] {
  return (rules ?? []).filter((r): r is BudgetRule => (r.id === 'budget.exceeded' || r.id === 'budget.forecast-over') && typeof (r as { budgetId?: unknown }).budgetId === 'string')
}

/** The query string of a cost report: the tags as `tag.<key>`. */
export function costParams(q: CostQuery): QueryParams {
  const { tags, ...rest } = q
  const out: QueryParams = { ...rest }
  for (const [k, v] of Object.entries(tags ?? {})) out[`tag.${k}`] = v
  return out
}

const chargebackParams = (q: ChargebackQuery): QueryParams => ({
  month: q.month,
  scope: q.scope,
  tag: q.tag,
})

export const costApi = {
  report: (q: CostQuery) => get<CostReport>(`${b('/cost')}${qs(costParams(q))}`),
  resource: (type: string, wfrn: string) => get<ResourceCost>(b(`/r/${enc(type)}/${enc(wfrn)}/cost`)),
  priceLists: () => get<PriceLists>(b('/cost/price-lists')),
  /** a month's rows as finance receives them; needs `cost:export`, like the CSV */
  chargeback: (q: ChargebackQuery) => get<ChargebackReport>(`${b('/cost/chargeback')}${qs(chargebackParams(q))}`),
  /** the CSV finance receives, as text (UTF-8, no byte order mark); needs `cost:export` on every account it covers */
  chargebackCsv: (q: ChargebackQuery) => request<string>('GET', `${b('/cost/chargeback.csv')}${qs(chargebackParams(q))}`, undefined, { headers: { Accept: 'text/csv' } }),
  budgets: {
    list: () => page<ConsoleBudget>(b('/budgets')),
    get: (budgetId: string) => get<ConsoleBudget>(b(`/budgets/${enc(budgetId)}`)),
    /** how the budget did month by month, newest first */
    periods: (budgetId: string) => page<BudgetStatus>(b(`/budgets/${enc(budgetId)}/periods`)),
    /** `key`: one Idempotency-Key per form submission, kept across retries of the same body */
    create: (body: BudgetInput, key?: string) =>
      request<ConsoleBudget>('POST', b('/budgets'), body, {
        headers: { 'Idempotency-Key': key ?? idempotencyKey() },
      }),
    update: (budgetId: string, body: BudgetInput, version: number) =>
      request<ConsoleBudget>('PUT', b(`/budgets/${enc(budgetId)}`), body, {
        headers: { 'If-Match': String(version) },
      }),
    remove: (budgetId: string, version: number) =>
      request<void>('DELETE', b(`/budgets/${enc(budgetId)}`), undefined, {
        headers: { 'If-Match': String(version) },
      }),
  },
}
