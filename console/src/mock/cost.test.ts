import { describe, expect, it } from 'vitest'
import { OTHER_KEY, type CostReport } from '../lib/cost'
import type { ConsolePreview } from '../lib/types'
import { APPROVAL_MS, RUN_MS } from './commands'
import { accrued, type Charge } from './cost'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The cost service's report in the mock BFF (WP 7.3): one ledger computed from the resources,
 * read by the Cost Explorer, budgets and the executive home's spend by team.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const VMG = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001'

function bff(person: string, start = NOW) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({
    method: 'GET',
    url: `/auth/login?login_hint=${person}`,
    headers: {},
  })
  const call = (method: string, url: string, body?: unknown) =>
    server.handle({
      method,
      url,
      headers: { 'x-csrf-token': csrfTokenOf(person) },
      body,
    })
  return {
    data,
    call,
    get: (url: string) => call('GET', url),
    tick: (ms: number) => void (now += ms),
  }
}

const report = (res: { status: number; body?: unknown }) => {
  expect(res.status).toBe(200)
  return res.body as CostReport
}
const sum = (ns: number[]) => Math.round(ns.reduce((a, b) => a + b, 0) * 100) / 100

describe('accruing a charge', () => {
  const charge = (monthly: number, from: string, to: string): Charge => ({
    wfrn: 'w',
    name: 'n',
    type: 'vm',
    service: 'compute',
    accountId: 'a',
    region: 'global',
    tags: {},
    monthly,
    priced: true,
    from: Date.parse(from),
    to: Date.parse(to),
    live: false,
  })

  it('accrues by the second at the month’s rate over the days in that month', () => {
    // 31 a month in October (31 days) is 1 a day; in September (30 days) 31 / 30 a day
    expect(accrued(charge(31, '2026-10-01T00:00:00Z', '2026-10-11T00:00:00Z'), Date.parse('2026-10-01T00:00:00Z'), Date.parse('2026-11-01T00:00:00Z'))).toBeCloseTo(10)
    expect(accrued(charge(31, '2026-10-05T12:00:00Z', '2026-10-06T00:00:00Z'), Date.parse('2026-10-05T00:00:00Z'), Date.parse('2026-10-06T00:00:00Z'))).toBeCloseTo(0.5)
    expect(accrued(charge(30, '2026-09-30T00:00:00Z', '2026-10-02T00:00:00Z'), 0, Number.MAX_SAFE_INTEGER)).toBeCloseTo(1 + 30 / 31 / 1)
    expect(
      accrued(
        {
          ...charge(0, '2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z'),
          priced: false,
        },
        0,
        Number.MAX_SAFE_INTEGER,
      ),
    ).toBe(0)
  })
})

describe('the cost report', () => {
  it('sums the account’s charges by day, with the run rate, the unpriced and a month-end forecast', () => {
    const dana = bff('dana')
    const r = report(dana.get('/console/v1/cost?from=2026-09-24&to=2026-10-24'))
    expect(r).toMatchObject({
      currency: 'USD',
      from: '2026-09-24',
      to: '2026-10-24',
      granularity: 'day',
      source: 'list-prices',
      groups: [],
      closedThrough: '2026-10-22',
    })
    expect(r.series).toHaveLength(30)
    expect(r.total).toBeGreaterThan(0)
    expect(r.total).toBeCloseTo(sum(r.series.map((p) => p.total)), 2)
    // today is still open; the days before it are closed
    expect(r.series.filter((p) => p.provisional).map((p) => p.start)).toEqual(['2026-10-23'])
    // today holds the ten hours since midnight, so far
    const [yesterday, today] = r.series.slice(-2)
    expect(today.start).toBe('2026-10-23')
    expect(today.total).toBeGreaterThan(0)
    expect(today.total).toBeLessThan(yesterday.total)
    expect(r.runRate.monthly).toBeGreaterThan(0)
    expect(r.runRate.resources).toBeGreaterThan(100)
    expect(r.unpriced.resources).toBeGreaterThanOrEqual(0)
    expect(r.forecast).toMatchObject({ month: '2026-10', method: 'run-rate' })
    const f = r.forecast!
    expect(f.monthEnd).toBeGreaterThan(f.monthToDate!)
    expect(f.low).toBeLessThanOrEqual(f.monthEnd)
    expect(f.high).toBeGreaterThanOrEqual(f.monthEnd)
    expect(r.accounts).toBeUndefined()
  })

  it('groups by service, keeps the top groups and sums the rest into Other, point by point', () => {
    const dana = bff('dana')
    const all = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-23&groupBy=type'))
    expect(all.groups.length).toBeGreaterThan(1)
    expect(all.groups.map((g) => g.total)).toEqual([...all.groups.map((g) => g.total)].sort((a, b) => b - a))
    expect(sum(all.groups.map((g) => g.total))).toBeCloseTo(all.total, 1)
    const top = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-23&groupBy=type&top=1'))
    expect(top.groups).toHaveLength(2)
    expect(top.groups[0].key).toBe(all.groups[0].key)
    expect(top.groups[1]).toMatchObject({ key: OTHER_KEY, label: 'Other' })
    for (const p of top.series) expect(sum(Object.values(p.groups))).toBeCloseTo(p.total, 2)
  })

  it('groups by a tag, naming the resources without it', () => {
    const r = report(bff('dana').get('/console/v1/cost?from=2026-10-01&to=2026-10-23&groupBy=tag:environment'))
    expect(r.groups.length).toBeGreaterThan(0)
    for (const g of r.groups) if (g.key === '__none') expect(g.label).toBe('No environment tag')
  })

  it('reads every account the person may read cost in, by cost centre, for the executive home', () => {
    const gonzalo = bff('gonzalo')
    const org = report(gonzalo.get('/console/v1/cost?scope=organization&from=2026-10-01&to=2026-11-01&granularity=month&groupBy=costCentre'))
    expect(org.series).toHaveLength(1)
    expect(org.groups.map((g) => g.key).sort()).toEqual(['CC-4100', 'CC-4200'])
    expect(org.accounts).toEqual([
      { accountId: '123456789012', name: 'Compute', costCentre: 'CC-4100' },
      { accountId: '210987654321', name: 'Data', costCentre: 'CC-4200' },
    ])
    expect((org as CostReport & { skippedAccounts: string[] }).skippedAccounts).toEqual([])
    // the session's account alone is the Compute share of it
    const mine = report(gonzalo.get('/console/v1/cost?from=2026-10-01&to=2026-11-01&granularity=month'))
    expect(mine.total).toBeCloseTo(org.groups.find((g) => g.key === 'CC-4100')!.total, 2)
  })

  it('stops charging a machine once it is deleted', () => {
    const gonzalo = bff('gonzalo')
    const today = () => report(gonzalo.get(`/console/v1/cost?from=2026-10-23&to=2026-10-24&wfrn=${encodeURIComponent(VMG)}`))
    expect(today().runRate).toEqual({ monthly: 77, resources: 1 })
    expect(gonzalo.call('POST', `/console/v1/r/vm/${encodeURIComponent(VMG)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true }).status).toBe(202)
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    expect(gonzalo.get(`/console/v1/r/vm/${encodeURIComponent(VMG)}`).status).toBe(410)
    const gone = today()
    expect(gone.runRate).toEqual({ monthly: 0, resources: 0 })
    expect(gone.total).toBeGreaterThan(0)
    // two hours on, nothing more has accrued
    gonzalo.tick(2 * 3600_000)
    expect(today().total).toBe(gone.total)
  })

  it('answers 400 for a range the cost service refuses, and the budget hook adds no rule yet', () => {
    const dana = bff('dana')
    expect(dana.get('/console/v1/cost?to=2026-10-24').status).toBe(400)
    expect(dana.get('/console/v1/cost?from=2026-10-24&to=2026-10-01').status).toBe(400)
    expect(dana.get('/console/v1/cost?from=2025-01-01&to=2026-10-24').status).toBe(400)
    expect(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-24&groupBy=owner').status).toBe(400)
    expect(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-24&top=0').status).toBe(400)
    const preview = dana.call('POST', '/console/v1/r/vm:preview', {
      input: { name: 'web-78', image: 'image-demo01', size: 'small' },
    })
    expect((preview.body as ConsolePreview).policy.rules?.some((r) => r.id.startsWith('budget.'))).toBe(false)
  })
})
