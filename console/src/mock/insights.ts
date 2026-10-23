import { capitalise, partsOfWfrn } from '../lib/descriptors'
import { DEFAULT_PERIOD, DEFAULT_SCOPE, isInsightsPeriod, isInsightsScope, periodDays, type Insights, type InsightsAccount, type InsightsPeriod, type InsightsScope } from '../lib/insights'
import type { JobSummary, Resource } from '../lib/types'
import { findingsOf, idFrom, liveRowOf, typeOf, type RouteAnswer, type RouteContext } from './commands'
import { DAY_MS, costAccounts, costCentreOf, dayOf, startOfDay } from './cost'
import type { MockData } from './data'
import { accountOf, authorize, everythingIn, type Decision } from './directory'
import { accountOfResource } from './resources'

/*
 * Phase 9 in the mock BFF: the executive home's numbers, `GET /console/v1/insights`, computed from
 * the dataset the other routes serve, so each number agrees with a page somewhere: resources are
 * the rows the lists show, requests are the platform's jobs (the samples and the console's own),
 * drift is the findings the resource pages show. The ticket baseline is a constant standing in for
 * the BFF's `console.insights.baseline`. Grouped counts (`/search:counts`) are not served here: the
 * executive home does not read them. Answers null for any path it does not serve.
 */

const HOUR_MS = 3_600_000
const WEEK_MS = 7 * DAY_MS
/** a request that ended without doing what it was asked */
const FAILED = new Set(['failed', 'rolled-back'])

/** The kinds of request the ticket baseline keeps a median for. */
export type RequestKind = 'create' | 'change' | 'delete'

/**
 * The ticket baseline, standing in for `console.insights.baseline`: a one-off export of today's
 * ticket queues, the median time from a ticket being opened to the work being done, by queue (the
 * team that owns the workflow) and kind of request. The data team's queue is not in the export, so
 * its requests have no baseline.
 */
export const TICKET_BASELINE: { source: string; medianMs: Record<string, Record<RequestKind, number>> } = {
  source: 'ticket export, September 2026',
  medianMs: {
    'team-compute': { create: 3 * DAY_MS, change: DAY_MS, delete: 2 * DAY_MS },
    'team-platform': { create: 2 * DAY_MS, change: 2 * DAY_MS, delete: 2 * DAY_MS },
  },
}

const iso = (t: number) => new Date(t).toISOString()

/** A period's half-open range in ms: the whole UTC days that end with today. */
export function periodRange(period: InsightsPeriod, now: number): { from: number; to: number } {
  const to = startOfDay(now) + DAY_MS
  return { from: to - periodDays(period) * DAY_MS, to }
}

/** Midnight (UTC) of the Monday a week starts on. */
export function weekStartOf(t: number): number {
  const day = startOfDay(t)
  return day - ((new Date(day).getUTCDay() + 6) % 7) * DAY_MS
}

/** The Mondays of the weeks a half-open range touches, oldest first. */
export function weeksOf(from: number, to: number): number[] {
  const out: number[] = []
  for (let w = weekStartOf(from); w < to; w += WEEK_MS) out.push(w)
  return out
}

/** The nearest-rank percentile of some values; 0 for none. */
export function percentile(values: readonly number[], p: number): number {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)]
}

/** What a request did: the verb the console recorded for it, else its workflow's verb in the catalog. */
export function kindOfJob(data: MockData, job: JobSummary): RequestKind {
  const verb = data.console?.jobs[job.id]?.verb ?? data.catalog.find((e) => e.workflow === job.workflow)?.verb ?? job.workflow.split('-')[0]
  return verb === 'create' ? 'create' : verb === 'delete' ? 'delete' : 'change'
}

/** The team whose queue would have taken the request as a ticket: the workflow's owner, else its type's. */
export function teamOfJob(data: MockData, job: JobSummary): string | undefined {
  const extra = data.console?.jobs[job.id]
  return data.catalog.find((e) => e.workflow === job.workflow)?.owner ?? (extra ? typeOf(data, extra.type)?.owner : undefined)
}

/** The account a request ran in: its own (P-04), its wfrn's, else its owning team's, the rule a sample resource's account follows. */
export function accountOfJob(data: MockData, job: JobSummary): string {
  const ids = data.directory.accounts.map((a) => a.accountId)
  return job.accountId ?? (job.wfrn ? partsOfWfrn(job.wfrn)?.account : undefined) ?? accountOfResource({ id: job.id, type: '', owner: teamOfJob(data, job) ?? '' } as Resource, ids)
}

/** The baseline's median for the request's queue and kind; none when the export does not cover the queue. */
export function baselineOf(data: MockData, job: JobSummary): number | undefined {
  return TICKET_BASELINE.medianMs[teamOfJob(data, job) ?? '']?.[kindOfJob(data, job)]
}

/** From the request to the work done: how long the job ran, approvals included. */
export function leadTimeOf(job: JobSummary): number {
  return job.durationMs ?? Math.max(0, Date.parse(job.endedAt ?? job.startedAt) - Date.parse(job.startedAt))
}

/** The numbers for some accounts over one period, as the BFF computes them. */
export function insightsOf(data: MockData, session: RouteContext['session'], accountIds: readonly string[], period: InsightsPeriod, scope: InsightsScope, now: number): Insights {
  const { from, to } = periodRange(period, now)
  const covered = new Set(accountIds)
  const ids = data.directory.accounts.map((a) => a.accountId)
  const inPeriod = (t: number) => t >= from && t < to && t <= now

  // resources: the rows the lists show, live in every state but deleted; a tombstone was live until its deletion
  let resources = 0
  let atStart = 0
  let found = 0
  let open = 0
  const byService = new Map<string, number>()
  for (const r of data.resources) {
    if (!covered.has(accountOfResource(r, ids))) continue
    const row = liveRowOf(data, session, r, now)
    const created = Date.parse(r.createdAt)
    if (row.state !== 'deleted') {
      resources++
      byService.set(row.service, (byService.get(row.service) ?? 0) + 1)
      if (created < from) atStart++
    } else if (created < from && Date.parse(r.updatedAt ?? r.createdAt) > from) atStart++
    const rt = typeOf(data, r.type)
    for (const f of rt ? findingsOf(data, session, rt, r, now) : []) {
      open++
      if (inPeriod(Date.parse(f.openedAt))) found++
    }
  }

  // requests: the jobs started in the period in the accounts, a batch's children counted in their batch
  const requests = data.jobs.filter((j) => !j.parentId && inPeriod(Date.parse(j.startedAt)) && covered.has(accountOfJob(data, j)))
  const selfServed = requests.filter((j) => j.status === 'succeeded')
  const leads = selfServed.map(leadTimeOf)
  // the requests the ticket export covers: each one's ticket median, and the time it saved against it
  const compared = selfServed.flatMap((j, i) => {
    const baseline = baselineOf(data, j)
    return baseline === undefined ? [] : [{ baseline, saved: Math.max(0, baseline - leads[i]) }]
  })
  const medians = compared.map((c) => c.baseline)
  const baseline = compared.length ? { baselineP50Ms: percentile(medians, 50), baselineSource: TICKET_BASELINE.source } : {}
  const saved = compared.length ? { hoursSaved: Math.round((compared.reduce((sum, c) => sum + c.saved, 0) / HOUR_MS) * 10) / 10 } : {}

  const accounts: InsightsAccount[] = accountIds.map((id) => {
    const a = accountOf(data.directory, id)
    return { accountId: id, name: a?.name ?? id, owningTeam: a?.owningTeam, costCentre: costCentreOf(a) }
  })

  return {
    period,
    from: iso(from),
    to: iso(to),
    asOf: iso(now),
    scope,
    accounts,
    managed: {
      resources,
      atStart,
      byService: [...byService.entries()].map(([service, n]) => ({ service, title: capitalise(service), resources: n })).sort((a, b) => b.resources - a.resources || a.title.localeCompare(b.title)),
    },
    requests: { total: requests.length, selfServed: selfServed.length, failed: requests.filter((j) => FAILED.has(j.status)).length },
    leadTime: { p50Ms: percentile(leads, 50), p90Ms: percentile(leads, 90), ...baseline },
    ...saved,
    // the mock's findings never close: it has no revert, accept or rescan that changes what a resource reports
    drift: { found, fixed: 0, open },
    adoption: weeksOf(from, to).map((w) => {
      const week = requests.filter((j) => Date.parse(j.startedAt) >= w && Date.parse(j.startedAt) < w + WEEK_MS)
      return { weekStart: dayOf(w), activeUsers: new Set(week.map((j) => j.requester)).size, requests: week.length, selfServed: week.filter((j) => j.status === 'succeeded').length }
    }),
  }
}

function refused(decision: Decision, ctx: RouteContext): RouteAnswer {
  return { status: 403, body: { error: decision.denied?.reason ?? 'Forbidden', denied: decision.denied, correlationId: `mock-${idFrom(`${ctx.now}:insights`, 8)}` } }
}

function invalid(param: string, message: string): RouteAnswer {
  return { status: 400, body: { error: 'validation.failed', detail: message, problems: [{ level: 'error', code: 'enum', field: `/${param}`, message }] } }
}

export function insightRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (path !== '/console/v1/insights') return null
  if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on the insights` } }
  const period = params.get('period') ?? DEFAULT_PERIOD
  if (!isInsightsPeriod(period)) return invalid('period', 'The period is 7d, 30d or 90d')
  const scope = params.get('scope') ?? DEFAULT_SCOPE
  if (!isInsightsScope(scope)) return invalid('scope', 'The scope is account or organization')
  // insights:get on each account; a console service, so the platform's role never decides it
  const { accountIds, denied } = costAccounts(data, ctx, scope, 'insights:get')
  if (denied) return refused(denied, ctx)
  if (!accountIds.length) return refused(authorize(data.directory, ctx.session, 'insights:get', everythingIn(ctx.session.accountId)), ctx)
  return { status: 200, body: insightsOf(data, ctx.session, accountIds, period, scope, ctx.now) }
}
