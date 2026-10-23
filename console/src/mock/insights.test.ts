import { describe, expect, it } from 'vitest'
import type { Insights } from '../lib/insights'
import type { Denial, JobSummary } from '../lib/types'
import { APPROVAL_MS, RUN_MS } from './commands'
import { buildMockData } from './data'
import { ADMIN_ROLE, everythingIn } from './directory'
import { TICKET_BASELINE, accountOfJob, kindOfJob, percentile, periodRange, teamOfJob, weekStartOf, weeksOf } from './insights'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The executive home's numbers in the mock BFF (WP 9.5): computed from the same resources, jobs
 * and findings the other pages read, over half-open UTC periods with weeks from Monday, for the
 * accounts where `insights:get` is allowed.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const DAY = 86_400_000
const COMPUTE = '123456789012'
const DATA = '210987654321'
/** generated machines nothing was built from, so they may be deleted; the second one drifted */
const VMG = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001'
const DRIFTED = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-017'

function bff(person: string, start = NOW) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person) }, body })
  const insights = (query = '') => {
    const res = call('GET', `/console/v1/insights${query}`)
    expect(res.status, JSON.stringify(res.body)).toBe(200)
    return res.body as Insights
  }
  return { data, call, insights, get: (url: string) => call('GET', url), tick: (ms: number) => void (now += ms) }
}

const enc = encodeURIComponent
const at = (s: string) => Date.parse(s)
const job = (data: ReturnType<typeof buildMockData>, id: string) => data.jobs.find((j) => j.id === id) as JobSummary

/** Denies `insights:get` to a role in the Compute account, the way an admin would write it. */
function hideInsights(data: ReturnType<typeof buildMockData>, roleId: string) {
  data.directory.policies[COMPUTE].push({
    policyId: 'no-insights',
    name: 'no-insights',
    builtIn: false,
    version: 1,
    statements: [{ sid: 'hide-insights', effect: 'deny', actions: ['insights:get'], resources: [everythingIn(COMPUTE)] }],
  })
  const role = data.directory.roles.find((r) => r.roleId === roleId)!
  role.policies = [...(role.policies ?? []), 'no-insights']
}

describe('the periods', () => {
  it('are the whole UTC days that end with today, half open', () => {
    expect(periodRange('7d', NOW)).toEqual({ from: at('2026-10-17T00:00:00Z'), to: at('2026-10-24T00:00:00Z') })
    expect(periodRange('30d', NOW)).toEqual({ from: at('2026-09-24T00:00:00Z'), to: at('2026-10-24T00:00:00Z') })
    expect(periodRange('90d', NOW)).toEqual({ from: at('2026-07-26T00:00:00Z'), to: at('2026-10-24T00:00:00Z') })
    // the last millisecond of a day is still that day; midnight starts the next period
    expect(periodRange('7d', at('2026-10-23T23:59:59.999Z')).from).toBe(at('2026-10-17T00:00:00Z'))
    expect(periodRange('7d', at('2026-10-24T00:00:00Z')).from).toBe(at('2026-10-18T00:00:00Z'))
  })

  it('count weeks from Monday in UTC, the first and last clipped to the period', () => {
    expect(weekStartOf(NOW)).toBe(at('2026-10-19T00:00:00Z'))
    expect(weekStartOf(at('2026-10-19T00:00:00Z'))).toBe(at('2026-10-19T00:00:00Z'))
    expect(weekStartOf(at('2026-10-18T23:59:59.999Z')), 'a Sunday belongs to the week before').toBe(at('2026-10-12T00:00:00Z'))
    const { from, to } = periodRange('7d', NOW)
    expect(weeksOf(from, to)).toEqual([at('2026-10-12T00:00:00Z'), at('2026-10-19T00:00:00Z')])
    expect(weeksOf(...(Object.values(periodRange('90d', NOW)) as [number, number]))).toHaveLength(14)
  })

  it('take nearest-rank percentiles, 0 for none', () => {
    expect(percentile([], 50)).toBe(0)
    expect(percentile([7], 90)).toBe(7)
    expect(percentile([4, 1, 3, 2], 50)).toBe(2)
    expect(percentile([4, 1, 3, 2], 90)).toBe(4)
  })
})

describe('the numbers', () => {
  it('cover every account the person may read insights in, by default over the last 30 days', () => {
    const gonzalo = bff('gonzalo')
    const n = gonzalo.insights()
    expect(n).toMatchObject({ period: '30d', scope: 'organization', from: '2026-09-24T00:00:00.000Z', to: '2026-10-24T00:00:00.000Z', asOf: '2026-10-23T10:00:00.000Z' })
    expect(n.accounts).toEqual([
      { accountId: COMPUTE, name: 'Compute', owningTeam: 'team-compute', costCentre: 'CC-4100' },
      { accountId: DATA, name: 'Data', owningTeam: 'team-data', costCentre: 'CC-4200' },
    ])
    // every resource the lists show but the 30 retired machines; all of them arrived inside the period
    expect(n.managed).toEqual({
      resources: 223,
      atStart: 0,
      byService: [
        { service: 'compute', title: 'Compute', resources: 216 },
        { service: 'platform', title: 'Platform', resources: 5 },
        { service: 'data', title: 'Data', resources: 2 },
      ],
    })
    // the 50 jobs; not self-served: the batch still running, the request that needs attention, the upgrade waiting on approval, the failed create
    expect(n.requests).toEqual({ total: 50, selfServed: 46, failed: 1 })
    expect(n.leadTime).toEqual({ p50Ms: 709_880, p90Ms: 2_091_093, baselineP50Ms: 2 * DAY, baselineSource: TICKET_BASELINE.source })
    expect(n.hoursSaved).toBe(1933.2)
    // vm-demo02 and the 18 live generated machines numbered …7; nothing closes a finding in the mock
    expect(n.drift).toEqual({ found: 19, fixed: 0, open: 19 })
    expect(n.adoption.map((w) => w.weekStart)).toEqual(['2026-09-21', '2026-09-28', '2026-10-05', '2026-10-12', '2026-10-19'])
    expect(n.adoption.slice(-2)).toEqual([
      { weekStart: '2026-10-12', activeUsers: 3, requests: 5, selfServed: 5 },
      { weekStart: '2026-10-19', activeUsers: 4, requests: 45, selfServed: 41 },
    ])
  })

  it('narrow to the session’s account', () => {
    const n = bff('gonzalo').insights('?scope=account')
    expect(n.scope).toBe('account')
    expect(n.accounts.map((a) => a.accountId)).toEqual([COMPUTE])
    expect(n.managed.resources).toBe(221)
    expect(n.managed.byService?.map((s) => s.service)).toEqual(['compute', 'platform'])
    expect(n.requests).toEqual({ total: 47, selfServed: 44, failed: 1 })
    expect(n.leadTime).toMatchObject({ p50Ms: 739_406, p90Ms: 2_091_093, baselineP50Ms: 2 * DAY })
    // 1,944 hours of tickets for the 44 requests, less the 10.8 hours they took
    expect(n.hoursSaved).toBe(1933.2)
  })

  it('compare with no baseline where the ticket export has no queue for the requests', () => {
    const n = bff('raj').insights()
    expect(n.accounts.map((a) => a.name)).toEqual(['Data'])
    expect(n.requests).toEqual({ total: 3, selfServed: 2, failed: 0 })
    expect(n.leadTime).toEqual({ p50Ms: 5158, p90Ms: 414_311 })
    expect(n).not.toHaveProperty('hoursSaved')
    expect(n.drift).toEqual({ found: 0, fixed: 0, open: 0 })
  })

  it('read the resources live at the start of the period, a deleted one until its deletion', () => {
    const gonzalo = bff('gonzalo')
    expect(gonzalo.insights('?period=7d&scope=account').managed).toMatchObject({ resources: 221, atStart: 221 })
    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true }).status).toBe(202)
    expect(gonzalo.insights('?period=7d&scope=account').managed.resources, 'a machine being deleted is still managed').toBe(221)
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    expect(gonzalo.insights('?period=7d&scope=account').managed).toMatchObject({ resources: 220, atStart: 221 })
  })

  it('count only what started inside the period: its first instant in, the one before out', () => {
    const gonzalo = bff('gonzalo')
    const { from } = periodRange('7d', NOW)
    job(gonzalo.data, 'job_r283w5kkhv').startedAt = new Date(from).toISOString()
    job(gonzalo.data, 'job_frr4z6eg77').startedAt = new Date(from - 1).toISOString()
    const n = gonzalo.insights('?period=7d')
    expect(n.requests.total).toBe(49)
    // Saturday 17 October counts in the week of Monday the 12th
    expect(n.adoption[0]).toEqual({ weekStart: '2026-10-12', activeUsers: 3, requests: 6, selfServed: 6 })
    expect(gonzalo.insights('?period=30d').requests.total).toBe(50)
  })

  it('count the console’s own requests: a create, a failure, and a delete that is not a fix', () => {
    const dana = bff('dana')
    const before = dana.insights()
    expect(dana.call('POST', '/console/v1/r/vm', { input: { name: 'web-77', image: 'image-demo01', size: 'medium', environment: 'dev' } }).status).toBe(202)
    // the mock mints a request's ids from who sent it and when: a second later, a second request
    dana.tick(1000)
    expect(dana.call('POST', '/console/v1/r/vm', { input: { name: 'fail-01', image: 'image-demo01', size: 'small' } }).status).toBe(202)
    const running = dana.insights()
    expect(running.requests).toEqual({ total: before.requests.total + 2, selfServed: before.requests.selfServed, failed: before.requests.failed })
    expect(running.managed.resources).toBe(before.managed.resources + 2)
    dana.tick(RUN_MS + 1)
    const settled = dana.insights()
    expect(settled.requests).toEqual({ total: before.requests.total + 2, selfServed: before.requests.selfServed + 1, failed: before.requests.failed + 1 })
    // the new machine waited 8 seconds against a three-day ticket
    expect(settled.hoursSaved).toBeCloseTo(before.hoursSaved! + (3 * DAY - RUN_MS) / 3_600_000, 0)
    expect(settled.adoption.at(-1)).toMatchObject({ activeUsers: before.adoption.at(-1)!.activeUsers + 1 })

    const gonzalo = bff('gonzalo')
    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(DRIFTED)}:delete`, { input: { reason: 'drifted beyond repair' }, confirm: true }).status).toBe(202)
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    expect(gonzalo.insights().drift).toEqual({ found: 18, fixed: 0, open: 18 })
  })

  it('find each request’s account, queue and kind from the console’s record or the catalog', () => {
    const { data } = bff('gonzalo')
    expect(accountOfJob(data, job(data, 'job_by9xks73mp'))).toBe(DATA)
    expect(accountOfJob(data, job(data, 'job_8vhxiwrwvq'))).toBe(COMPUTE)
    expect(accountOfJob(data, { ...job(data, 'job_by9xks73mp'), accountId: COMPUTE })).toBe(COMPUTE)
    expect(teamOfJob(data, job(data, 'job_7irf7hpqs7'))).toBe('team-platform')
    expect([kindOfJob(data, job(data, 'job_jb5f7xi43n')), kindOfJob(data, job(data, 'job_acw2sjmtpk')), kindOfJob(data, job(data, 'job_8vhxiwrwvq'))]).toEqual(['create', 'delete', 'change'])
  })
})

describe('who may read them', () => {
  it('lets a viewer read the accounts they belong to', () => {
    const n = bff('mia').insights()
    expect(n.accounts.map((a) => a.name)).toEqual(['Compute'])
    expect(n.requests.total).toBe(47)
  })

  it('leaves out an account whose policies deny insights:get, and refuses it named, with the statement', () => {
    const gonzalo = bff('gonzalo')
    hideInsights(gonzalo.data, ADMIN_ROLE)
    expect(gonzalo.insights().accounts.map((a) => a.accountId)).toEqual([DATA])
    const refused = gonzalo.get('/console/v1/insights?scope=account')
    expect(refused.status).toBe(403)
    expect((refused.body as { denied: Denial }).denied).toMatchObject({ layer: 'console-iam', action: 'insights:get', policyId: 'no-insights', statement: 'hide-insights' })
  })

  it('refuses a person who may read insights in none of their accounts', () => {
    const mia = bff('mia')
    hideInsights(mia.data, 'viewer')
    const refused = mia.get('/console/v1/insights')
    expect(refused.status).toBe(403)
    expect(refused.body).toMatchObject({ denied: { layer: 'console-iam', statement: 'hide-insights' }, correlationId: expect.stringMatching(/^mock-/) })
  })

  it('never asks the platform: insights are the console’s own', () => {
    const gonzalo = bff('gonzalo')
    for (const a of gonzalo.data.directory.accounts) a.platformScopes = []
    expect(gonzalo.insights().accounts).toHaveLength(2)
  })

  it('checks the period and the scope, and answers only GET', () => {
    const gonzalo = bff('gonzalo')
    expect(gonzalo.get('/console/v1/insights?period=1y')).toMatchObject({ status: 400, body: { error: 'validation.failed', problems: [{ field: '/period' }] } })
    expect(gonzalo.get('/console/v1/insights?scope=everything')).toMatchObject({ status: 400, body: { problems: [{ field: '/scope' }] } })
    expect(gonzalo.call('POST', '/console/v1/insights', {}).status).toBe(405)
  })
})
