import { get, qs } from './api'
import { BFF_BASE } from './bff'
import type { ResourceState } from './types'

/*
 * The executive home's numbers (WP 9.5): `GET /console/v1/insights`, which the BFF computes from
 * the state service's records and the platform's jobs (hub decision 27), and grouped counts across
 * the person's accounts, `GET /console/v1/search:counts`, the search indexer's counts passed
 * through. Spend is the cost service's (lib/cost.ts, `groupBy=costCentre`).
 */

const b = (path: string) => `${BFF_BASE}${path}`

export type InsightsPeriod = '7d' | '30d' | '90d'
/** the session's account, or every account the person may read insights in */
export type InsightsScope = 'account' | 'organization'

/** The periods the executive home offers, shortest first. */
export const INSIGHTS_PERIODS: readonly InsightsPeriod[] = ['7d', '30d', '90d']
/** The contract's defaults: the last 30 days, every account the person may read insights in. */
export const DEFAULT_PERIOD: InsightsPeriod = '30d'
export const DEFAULT_SCOPE: InsightsScope = 'organization'

/** How many days a period spans. */
export function periodDays(period: InsightsPeriod): number {
  return period === '7d' ? 7 : period === '30d' ? 30 : 90
}

export function isInsightsPeriod(v: unknown): v is InsightsPeriod {
  return v === '7d' || v === '30d' || v === '90d'
}

export function isInsightsScope(v: unknown): v is InsightsScope {
  return v === 'account' || v === 'organization'
}

export interface InsightsAccount {
  accountId: string
  name: string
  owningTeam?: string
  costCentre?: string
}

export interface Insights {
  period: InsightsPeriod
  from: string
  to: string
  asOf: string
  scope: InsightsScope
  /** the accounts the numbers cover */
  accounts: InsightsAccount[]
  managed: {
    /** live resources now; tombstones left out */
    resources: number
    /** live resources at the start of the period */
    atStart: number
    /** largest first */
    byService?: { service: string; title?: string; resources: number }[]
  }
  requests: {
    /** creates, verbs, updates and deletes started in the period */
    total: number
    /** ended succeeded with no person stepping in */
    selfServed: number
    failed: number
  }
  /** request to running, over the self-served requests, against today's tickets */
  leadTime: {
    p50Ms: number
    p90Ms: number
    baselineP50Ms?: number
    baselineSource?: string
  }
  /** absent without a ticket baseline */
  hoursSaved?: number
  drift: {
    found: number
    fixed: number
    open: number
    meanTimeToFixMs?: number
  }
  /** oldest first, weeks starting on Monday (UTC) */
  adoption: { weekStart: string; activeUsers: number; requests: number; selfServed: number }[]
}

export type CountsGroupBy = 'type' | 'service' | 'state' | 'region' | 'account' | `tag:${string}`

export interface CountGroup {
  key: string
  total: number
  byState?: Partial<Record<ResourceState, number>>
  drift?: number
  /** a type group's service */
  service?: string
}

export interface GroupedCounts {
  total: number
  byState?: Partial<Record<ResourceState, number>>
  drift?: number
  /** a count stopped at the cap: show "1,000+" */
  cappedAt?: number
  /** at most 100, largest first */
  groups: CountGroup[]
  asOf?: string
}

export interface CountsQuery {
  groupBy: CountsGroupBy
  scope?: InsightsScope
  q?: string
  type?: string
  state?: ResourceState
  drift?: boolean
}

/** The query key of one view of the numbers: the home's widget and the page share it, so one answers the other. */
export function insightsKey(userId: string, accountId: string, period: InsightsPeriod, scope: InsightsScope): readonly string[] {
  return ['insights', userId, accountId, period, scope]
}

export const insightsApi = {
  get: (params?: { period?: InsightsPeriod; scope?: InsightsScope }) => get<Insights>(`${b('/insights')}${qs(params)}`),
  counts: (params: CountsQuery) => get<GroupedCounts>(`${b('/search:counts')}${qs({ ...params })}`),
}
