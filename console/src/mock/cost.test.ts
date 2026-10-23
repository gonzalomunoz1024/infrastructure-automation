import { describe, expect, it } from 'vitest'
import { OTHER_KEY, budgetRulesOf, type BudgetStatus, type ChargebackReport, type ConsoleBudget, type CostReport, type PriceLists, type ResourceCost } from '../lib/cost'
import type { ConsolePreview, Denial } from '../lib/types'
import { APPROVAL_MS, RUN_MS, priceOf, typeOf } from './commands'
import { DAY_MS, accrued, chargebackCsvOf, sha256Hex, type Charge } from './cost'
import { buildMockData, type MockData } from './data'
import { everythingIn } from './directory'
import { createMockServer, csrfTokenOf, memorySessionStore, type MockResponse } from './server'

/*
 * The cost service in the mock BFF (WP 7.3, 9.3 and 9.9): one ledger computed from the resources
 * at the price list's prices, read by the Cost Explorer, a resource's page, budgets (with the
 * rules they add to a create's review), the chargeback finance receives and the executive home's
 * spend by team.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const VMG = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001'
const VOLUME = 'wfrn:wf:storage:global:123456789012:volume/volume-demo01'
const ORDERS_DB = 'wfrn:wf:data:global:210987654321:database/database-demo01'
const COMPUTE = '123456789012'
const enc = encodeURIComponent

interface Bff {
  data: MockData
  call: (method: string, url: string, body?: unknown, headers?: Record<string, string>) => MockResponse
  get: (url: string) => MockResponse
  tick: (ms: number) => void
  /** signs in again, as this person or another, on the same server and clock (a session lasts eight hours) */
  as: (person: string) => Bff
}

function bff(person: string, start = NOW): Bff {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const as = (who: string): Bff => {
    server.handle({
      method: 'GET',
      url: `/auth/login?login_hint=${who}`,
      headers: {},
    })
    const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) =>
      server.handle({
        method,
        url,
        headers: { 'x-csrf-token': csrfTokenOf(who), ...headers },
        body,
      })
    return {
      data,
      call,
      get: (url: string) => call('GET', url),
      tick: (ms: number) => void (now += ms),
      as,
    }
  }
  return as(person)
}

const report = (res: { status: number; body?: unknown }) => {
  expect(res.status).toBe(200)
  return res.body as CostReport
}
const body = <T>(res: { body?: unknown }) => res.body as T
const sum = (ns: number[]) => Math.round(ns.reduce((a, b) => a + b, 0) * 100) / 100
/** The platform's own sha256 (Web Crypto, as the browser checks a download). */
const webSha256 = async (text: string) => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))].map((b) => b.toString(16).padStart(2, '0')).join('')
/** The managed admin policy of an account, to add a statement to. */
const adminPolicy = (b: Bff, account: string) => b.data.directory.policies[account].find((p) => p.policyId === 'managed-account-admin')!

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

  it('adds a range up to the same cents by day and by month, the forecast’s month to date among them', () => {
    const dana = bff('dana')
    const byDay = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-24'))
    const byMonth = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-24&granularity=month'))
    expect(byMonth.series).toHaveLength(1)
    expect(byDay.total).toBe(byMonth.total)
    expect(byDay.total).toBe(byDay.forecast!.monthToDate)
    expect(sum(byDay.series.map((p) => p.total))).toBe(byDay.total)
  })

  it('groups by service, keeps the top groups and sums the rest into Other, point by point', () => {
    const dana = bff('dana')
    // Compute's priced resources are all machines, so its spend splits by their environment tag
    const all = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-23&groupBy=tag:environment'))
    expect(all.groups.length).toBeGreaterThan(1)
    expect(all.groups.map((g) => g.total)).toEqual([...all.groups.map((g) => g.total)].sort((a, b) => b - a))
    expect(sum(all.groups.map((g) => g.total))).toBeCloseTo(all.total, 1)
    const top = report(dana.get('/console/v1/cost?from=2026-10-01&to=2026-10-23&groupBy=tag:environment&top=1'))
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

  it('answers 400 for a range the cost service refuses, and a review within every budget gets no budget rule', () => {
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

describe('what prices a resource', () => {
  it('answers the price list in force and the two types it prices, at the create review’s prices', () => {
    const dana = bff('dana')
    const lists = body<PriceLists>(dana.get('/console/v1/cost/price-lists'))
    expect(lists.next).toBeNull()
    expect(lists.items).toHaveLength(1)
    expect(lists.items[0]).toMatchObject({ name: 'list-prices', currency: 'USD', version: expect.any(String) })
    expect(lists.items[0].prices).toEqual([
      { provider: 'vsphere', action: 'vm.create', by: 'cpu', monthly: { 2: 38.5, 4: 77, 8: 154, 16: 308 } },
      { provider: 'mongodb', action: 'cluster.create', by: 'tier', monthly: { M10: 57, M30: 210, M60: 520 } },
    ])
    expect(lists.bindings).toEqual([
      { type: 'vm', priceList: 'list-prices', provider: 'vsphere', action: 'vm.create', by: 'cpu' },
      { type: 'database', priceList: 'list-prices', provider: 'mongodb', action: 'cluster.create', by: 'tier' },
    ])
    // every listed price is what the create review quotes for that value
    for (const [i, binding] of lists.bindings.entries()) {
      const rt = typeOf(dana.data, binding.type)!
      for (const [value, monthly] of Object.entries(lists.items[0].prices[i].monthly as Record<string, number>)) expect(priceOf(rt, { [binding.by!]: binding.by === 'cpu' ? Number(value) : value })?.monthly).toBe(monthly)
    }
  })

  it('leaves the types no price covers out of the totals, and names them', () => {
    const r = report(bff('dana').get('/console/v1/cost?from=2026-10-01&to=2026-10-24&groupBy=type'))
    expect(r.unpriced).toEqual({ resources: 5, types: ['environment', 'image', 'volume'] })
    expect(r.groups.map((g) => g.key)).toEqual(['vm'])
  })

  it('answers one machine’s rate, its month so far and the rule behind it, from the same ledger', () => {
    const dana = bff('dana')
    const cost = body<ResourceCost>(dana.get(`/console/v1/r/vm/${enc(VMG)}/cost`))
    expect(cost).toMatchObject({
      wfrn: VMG,
      accountId: COMPUTE,
      type: 'vm',
      service: 'compute',
      state: 'active',
      charged: true,
      monthly: 77,
      currency: 'USD',
      source: 'list-prices',
      rule: { priceList: 'list-prices', provider: 'vsphere', action: 'vm.create', by: 'cpu', value: 4 },
      pricedFrom: 'desired',
    })
    expect(cost.since).toMatch(/^2026-/)
    expect(cost.history).toEqual([{ from: cost.since, monthly: 77 }])
    const mtd = report(dana.get(`/console/v1/cost?from=2026-10-01&to=2026-10-24&wfrn=${enc(VMG)}`))
    expect(cost.monthToDate).toBe(mtd.total)
  })

  it('says a volume is not priced, a deleted machine is no longer charged, and refuses or hides what it should', () => {
    const gonzalo = bff('gonzalo')
    expect(body<ResourceCost>(gonzalo.get(`/console/v1/r/volume/${enc(VOLUME)}/cost`))).toMatchObject({ charged: true, monthly: 0, monthToDate: 0, source: 'unpriced', history: [] })
    expect(body<ResourceCost>(gonzalo.get(`/console/v1/r/volume/${enc(VOLUME)}/cost`)).rule).toBeUndefined()

    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true }).status).toBe(202)
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    const gone = body<ResourceCost>(gonzalo.get(`/console/v1/r/vm/${enc(VMG)}/cost`))
    expect(gone).toMatchObject({ charged: false, monthly: 0, source: 'list-prices', state: 'deleted' })
    expect(gone.monthToDate).toBeGreaterThan(0)
    expect(gone.history?.[0].to).toBeDefined()

    // another account's resource is not found from this one; an unknown type is not found either
    expect(gonzalo.get(`/console/v1/r/database/${enc(ORDERS_DB)}/cost`).status).toBe(404)
    expect(gonzalo.get(`/console/v1/r/nothing/${enc(VMG)}/cost`).status).toBe(404)

    // a policy that denies cost:list in the account refuses it, naming the layer
    const dana = bff('dana')
    dana.data.directory.policies[COMPUTE].find((p) => p.policyId === 'managed-operator')!.statements.unshift({ sid: 'no-cost', effect: 'deny', actions: ['cost:list'], resources: [everythingIn(COMPUTE)] })
    const refused = dana.get(`/console/v1/r/vm/${enc(VMG)}/cost`)
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ layer: 'console-iam', action: 'cost:list' })
    for (const url of ['/console/v1/cost/price-lists', '/console/v1/cost?from=2026-10-01&to=2026-10-24']) expect(dana.get(url).status).toBe(403)
  })
})

describe('chargeback', () => {
  it('answers a closed month as final, with the sha256 of the CSV finance received', async () => {
    const gonzalo = bff('gonzalo')
    const sept = body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization'))
    expect(sept).toMatchObject({ month: '2026-09', currency: 'USD', final: true, generatedAt: '2026-10-01T00:30:00.000Z' })
    expect([...new Set(sept.rows.map((r) => r.costCentre))]).toEqual(['CC-4100', 'CC-4200'])
    expect(sept.rows.find((r) => r.costCentre === 'CC-4200')).toMatchObject({ accountId: '210987654321', accountName: 'Data', service: 'data' })
    expect(sept.total).toBe(sum(sept.rows.map((r) => r.amount)))

    const csv = gonzalo.get('/console/v1/cost/chargeback.csv?month=2026-09&scope=organization')
    expect(csv.status).toBe(200)
    const text = body<string>(csv)
    expect(text).toBe(chargebackCsvOf(sept))
    expect(text.split('\r\n')[0]).toBe('month,cost_centre,account_id,account_name,service,tag_key,tag_value,amount,currency,final')
    expect(text.split('\r\n')[1]).toMatch(/^2026-09,CC-4100,123456789012,Compute,compute,,,\d+\.\d{2},USD,true$/)
    expect(text.endsWith('\r\n')).toBe(true)
    const sha = await webSha256(text)
    expect(sept.sha256).toBe(sha)
    expect(csv.headers).toMatchObject({ 'X-Content-Sha256': sha, 'Content-Disposition': 'attachment; filename="chargeback-2026-09-organization.csv"' })
    expect(csv.headers?.['Content-Type']).toMatch(/^text\/csv/)
  })

  it('keeps a final month as it was published, and a later change of rate does not restate it', () => {
    const gonzalo = bff('gonzalo')
    const first = body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization'))
    // the orders database moves to a dearer tier: the open month moves, the published one does not
    const db = gonzalo.data.resources.find((r) => r.id === 'database-demo01')!
    db.attributes = { ...db.attributes, tier: 'M60' }
    expect(body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization'))).toEqual(first)
    const fresh = bff('gonzalo')
    fresh.data.resources.find((r) => r.id === 'database-demo01')!.attributes!.tier = 'M60'
    expect(body<ChargebackReport>(fresh.get('/console/v1/cost/chargeback?month=2026-09&scope=organization')).sha256).not.toBe(first.sha256)
  })

  it('answers the open month as provisional, split by a tag on request, and the session’s account alone by default', () => {
    const gonzalo = bff('gonzalo')
    const oct = body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-10'))
    expect(oct).toMatchObject({ month: '2026-10', final: false, generatedAt: new Date(NOW).toISOString() })
    expect(oct.sha256).toBeUndefined()
    expect(new Set(oct.rows.map((r) => r.accountId))).toEqual(new Set([COMPUTE]))
    // the month so far is the Cost Explorer's month to date
    const mtd = report(gonzalo.get('/console/v1/cost?from=2026-10-01&to=2026-10-24'))
    expect(oct.total).toBe(mtd.total)

    const split = body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-10&tag=environment'))
    expect(split.rows.every((r) => r.tag?.key === 'environment')).toBe(true)
    expect(split.rows.map((r) => r.tag?.value)).toEqual(['dev', 'prod', 'test'])
    expect(split.total).toBeCloseTo(oct.total, 1)
    const csv = body<string>(gonzalo.get('/console/v1/cost/chargeback.csv?month=2026-10&tag=environment'))
    expect(csv.split('\r\n')[1]).toMatch(/^2026-10,CC-4100,123456789012,Compute,compute,environment,dev,\d+\.\d{2},USD,false$/)
  })

  it('is what cost:export allows, the JSON as the CSV, and refuses a month it cannot answer', () => {
    for (const person of ['dana', 'mia'])
      for (const url of ['/console/v1/cost/chargeback?month=2026-09', '/console/v1/cost/chargeback.csv?month=2026-09']) {
        const refused = bff(person).get(url)
        expect(refused.status).toBe(403)
        expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ layer: 'console-iam', action: 'cost:export' })
      }
    const gonzalo = bff('gonzalo')
    expect(gonzalo.get('/console/v1/cost/chargeback').status).toBe(400)
    expect(gonzalo.get('/console/v1/cost/chargeback?month=2026-13').status).toBe(400)
    expect(gonzalo.get('/console/v1/cost/chargeback?month=2026-11').status).toBe(400)
    expect(gonzalo.get('/console/v1/cost/chargeback?month=2026-10&tag=Not%20a%20key').status).toBe(400)
    expect(body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2025-10'))).toMatchObject({ final: true, rows: [], total: 0 })
  })

  it('leaves out of an organization’s chargeback the accounts where the export is denied, and names them', () => {
    const gonzalo = bff('gonzalo')
    const DATA = '210987654321'
    expect(body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization')).skippedAccounts).toEqual([])
    expect(body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09')).skippedAccounts).toBeUndefined()
    expect(gonzalo.get('/console/v1/cost/chargeback.csv?month=2026-09&scope=organization').headers?.['X-Console-Skipped-Accounts']).toBeUndefined()
    // cost is decided on everything of the cost service in the account
    const deny = (account: string) => adminPolicy(gonzalo, account).statements.unshift({ sid: 'no-export', effect: 'deny', actions: ['cost:export'], resources: [`wfrn:wf:cost::${account}:*`] })
    deny(DATA)
    const report = body<ChargebackReport>(gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization'))
    expect(report.skippedAccounts).toEqual([DATA])
    expect(new Set(report.rows.map((r) => r.accountId))).toEqual(new Set([COMPUTE]))
    const csv = gonzalo.get('/console/v1/cost/chargeback.csv?month=2026-09&scope=organization')
    expect(csv.headers?.['X-Console-Skipped-Accounts']).toBe(DATA)
    expect(body<string>(csv)).toBe(chargebackCsvOf(report))
    // with no account left the read is refused, not answered empty
    deny(COMPUTE)
    const none = gonzalo.get('/console/v1/cost/chargeback?month=2026-09&scope=organization')
    expect(none.status).toBe(403)
    expect(body<{ denied: Denial }>(none).denied).toMatchObject({ action: 'cost:export', policyId: 'managed-account-admin', statement: 'no-export' })
  })
})

describe('sha256', () => {
  it('matches the standard’s vectors and the platform’s own hash', async () => {
    expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1')
    for (const text of ['a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'é, ünïcode and €'.repeat(40), 'x'.repeat(10_000)]) expect(sha256Hex(text)).toBe(await webSha256(text))
  })
})

describe('budgets', () => {
  const list = (b: Bff) => body<{ items: ConsoleBudget[]; next: string | null }>(b.get('/console/v1/budgets'))
  const byName = (b: Bff, name: string) => list(b).items.find((x) => x.name === name)!
  const valid = { name: 'Shop web tier', amount: 2500, scope: { tag: { key: 'owner', value: 'team-shop' } }, thresholds: [50, 90, 100], blockCreates: false }

  it('lists the account’s budgets with this month’s status, read from the same ledger', () => {
    const gonzalo = bff('gonzalo')
    const page = list(gonzalo)
    expect(page.next).toBeNull()
    expect(page.items.map((b) => [b.name, b.status.state, b.status.alertsSent])).toEqual([
      ['Compute monthly', 'ok', [50]],
      ['Production', 'forecast-over', [80]],
      ['Test environments', 'over', [80, 100]],
    ])
    for (const b of page.items) {
      expect(b.budgetId).toMatch(/^bud_[0-9a-hjkmnp-tv-z]{26}$/)
      expect(b).toMatchObject({ currency: 'USD', version: 1, createdBy: 'gonzalo', can: { update: true, delete: true }, scope: { accountId: COMPUTE } })
      expect(b.status).toMatchObject({ period: '2026-10', amount: b.amount })
      expect(b.status.percent).toBeCloseTo((b.status.spent / b.amount) * 100, 1)
    }
    const prod = page.items[1]
    expect(prod).toMatchObject({ scope: { tag: { key: 'environment', value: 'prod' } }, blockCreates: true, thresholds: [80, 100] })
    const spent = report(gonzalo.get('/console/v1/cost?from=2026-10-01&to=2026-10-24&tag.environment=prod'))
    expect(prod.status.spent).toBeCloseTo(spent.forecast!.monthToDate!, 2)
    expect(prod.status.forecast).toBeCloseTo(spent.forecast!.monthEnd, 2)
    expect(prod.status.spent).toBeLessThan(prod.amount)
    expect(prod.status.forecast).toBeGreaterThan(prod.amount)

    // the Data account has its own; a viewer reads them all and may change none
    const ana = bff('ana')
    expect(list(ana).items.map((b) => [b.name, b.status.state])).toEqual([['Data platform', 'ok']])
    expect(list(bff('mia')).items.every((b) => !b.can.update && !b.can.delete)).toBe(true)
    expect(list(bff('dana')).items.every((b) => !b.can.update && !b.can.delete)).toBe(true)
  })

  it('reads one budget with its ETag and its months, newest first, and not another account’s', () => {
    const gonzalo = bff('gonzalo')
    const prod = byName(gonzalo, 'Production')
    const one = gonzalo.get(`/console/v1/budgets/${prod.budgetId}`)
    expect(one.status).toBe(200)
    expect(one.headers?.ETag).toBe('"1"')
    expect(body<ConsoleBudget>(one)).toEqual(prod)
    const periods = body<{ items: BudgetStatus[]; next: string | null }>(gonzalo.get(`/console/v1/budgets/${prod.budgetId}/periods`))
    expect(periods.items.map((p) => p.period)).toEqual(['2026-10', '2026-09'])
    const [oct, sept] = periods.items
    expect(oct).toEqual(prod.status)
    expect(sept.forecast).toBe(sept.spent)
    expect(sept.state).toBe('ok')

    expect(gonzalo.get('/console/v1/budgets/bud_00000000000000000000000000').status).toBe(404)
    expect(gonzalo.get('/console/v1/budgets/not-a-budget').status).toBe(404)
    expect(bff('mia').call('PUT', '/console/v1/budgets/not-a-budget', {}, { 'if-match': '1' }).status).toBe(404)
    const data = byName(bff('ana'), 'Data platform')
    expect(gonzalo.get(`/console/v1/budgets/${data.budgetId}`).status).toBe(404)
  })

  it('creates one for an admin only, checking the body field by field and replaying a retried key', () => {
    const mia = bff('mia')
    const refused = mia.call('POST', '/console/v1/budgets', valid, { 'idempotency-key': 'k1' })
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ layer: 'console-iam', action: 'budgets:create' })
    expect(bff('dana').call('POST', '/console/v1/budgets', valid).status).toBe(403)

    const gonzalo = bff('gonzalo')
    const bad = gonzalo.call('POST', '/console/v1/budgets', { name: '', amount: -5, thresholds: [80, 80, 2000], scope: { tag: { key: 'Not A Key', value: 'x' } } })
    expect(bad.status).toBe(400)
    expect(body<{ error: string; problems: { field: string }[] }>(bad).problems.map((p) => p.field)).toEqual(['/name', '/amount', '/thresholds', '/scope/tag/key'])
    expect(body<{ problems: { field: string }[] }>(gonzalo.call('POST', '/console/v1/budgets', { ...valid, thresholds: [1, 2, 3, 4, 5, 6] })).problems[0].field).toBe('/thresholds')
    expect(body<{ problems: { field: string }[] }>(gonzalo.call('POST', '/console/v1/budgets', { ...valid, name: 'production' })).problems[0]).toMatchObject({ field: '/name', code: 'duplicate' })
    expect(body<{ problems: { field: string }[] }>(gonzalo.call('POST', '/console/v1/budgets', { ...valid, scope: { accountId: '210987654321' } })).problems[0]).toMatchObject({ field: '/scope/accountId', code: 'scope.account' })

    const made = gonzalo.call('POST', '/console/v1/budgets', valid, { 'idempotency-key': 'k1' })
    expect(made.status).toBe(201)
    expect(made.headers?.ETag).toBe('"1"')
    const b = body<ConsoleBudget>(made)
    expect(b).toMatchObject({ name: 'Shop web tier', amount: 2500, thresholds: [50, 90, 100], blockCreates: false, version: 1, createdBy: 'gonzalo', scope: { accountId: COMPUTE, tag: { key: 'owner', value: 'team-shop' } } })
    // nothing carries owner=team-shop: nothing spent, nothing forecast
    expect(b.status).toMatchObject({ spent: 0, forecast: 0, percent: 0, state: 'ok', alertsSent: [] })

    const again = gonzalo.call('POST', '/console/v1/budgets', valid, { 'idempotency-key': 'k1' })
    expect(again.status).toBe(201)
    expect(again.headers?.['Idempotent-Replayed']).toBe('true')
    expect(body<ConsoleBudget>(again).budgetId).toBe(b.budgetId)
    expect(list(gonzalo).items.filter((x) => x.name === 'Shop web tier')).toHaveLength(1)
    const other = gonzalo.call('POST', '/console/v1/budgets', { ...valid, amount: 3000 }, { 'idempotency-key': 'k1' })
    expect(other.status).toBe(409)
    expect(body<{ error: string }>(other).error).toBe('idempotency.mismatch')

    // the defaults: the account as scope, alerts at 80 and 100, no block
    const plain = body<ConsoleBudget>(gonzalo.call('POST', '/console/v1/budgets', { name: 'Everything', amount: 30000 }))
    expect(plain).toMatchObject({ scope: { accountId: COMPUTE }, thresholds: [80, 100], blockCreates: false })
    expect(plain.scope.tag).toBeUndefined()
  })

  it('decides a budget on its own wfrn, the id’s underscore written as a hyphen', () => {
    const gonzalo = bff('gonzalo')
    const test = byName(gonzalo, 'Test environments')
    adminPolicy(gonzalo, COMPUTE).statements.unshift({ sid: 'keep-test', effect: 'deny', actions: ['budgets:delete'], resources: [`wfrn:wf:budgets::${COMPUTE}:budget/${test.budgetId.replace('_', '-')}`] })
    expect(list(gonzalo).items.map((b) => [b.name, b.can])).toEqual([
      ['Compute monthly', { update: true, delete: true }],
      ['Production', { update: true, delete: true }],
      ['Test environments', { update: true, delete: false }],
    ])
    const refused = gonzalo.call('DELETE', `/console/v1/budgets/${test.budgetId}`)
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ action: 'budgets:delete', statement: 'keep-test' })
  })

  it('changes one only at the version read, keeps a closed month’s amount, and deletes it', () => {
    const gonzalo = bff('gonzalo')
    const prod = byName(gonzalo, 'Production')
    const url = `/console/v1/budgets/${prod.budgetId}`
    const input = { name: 'Production', amount: 8000, thresholds: [80, 100], blockCreates: true }
    // If-Match is asked for before anything else, the person's access included
    for (const who of [gonzalo, bff('mia')]) {
      const unversioned = who.call('PUT', url, input)
      expect(unversioned.status).toBe(400)
      expect(body<{ problems: { field: string; code: string }[] }>(unversioned).problems).toMatchObject([{ field: 'If-Match', code: 'required' }])
    }
    const stale = gonzalo.call('PUT', url, input, { 'if-match': '"7"' })
    expect(stale.status).toBe(409)
    expect(body<{ error: string }>(stale).error).toBe('version.conflict')
    expect(bff('mia').call('PUT', url, input, { 'if-match': '1' }).status).toBe(403)
    expect(body<{ problems: { field: string }[] }>(gonzalo.call('PUT', url, { ...input, scope: { tag: { key: 'environment', value: 'dev' } } }, { 'if-match': '1' })).problems[0].field).toBe('/scope')

    const changed = gonzalo.call('PUT', url, input, { 'if-match': '"1"' })
    expect(changed.status).toBe(200)
    expect(changed.headers?.ETag).toBe('"2"')
    const now = body<ConsoleBudget>(changed)
    expect(now).toMatchObject({ amount: 8000, version: 2, scope: prod.scope, status: { amount: 8000, state: 'ok' } })
    expect(now.updatedAt).toBe(new Date(NOW).toISOString())
    // September was measured against the amount it had then
    const [oct, sept] = body<{ items: BudgetStatus[] }>(gonzalo.get(`${url}/periods`)).items
    expect(oct.amount).toBe(8000)
    expect(sept.amount).toBe(prod.amount)

    expect(gonzalo.call('DELETE', url, undefined, { 'if-match': '1' }).status).toBe(409)
    expect(bff('dana').call('DELETE', url).status).toBe(403)
    expect(gonzalo.call('DELETE', url, undefined, { 'if-match': '2' }).status).toBe(204)
    expect(gonzalo.get(url).status).toBe(404)
    expect(list(gonzalo).items.map((b) => b.name)).toEqual(['Compute monthly', 'Test environments'])
  })
})

describe('budgets in a create’s review', () => {
  const preview = (b: Bff, input: Record<string, unknown>) => body<ConsolePreview>(b.call('POST', '/console/v1/r/vm:preview', { input }))
  const prodVm = { name: 'web-90', image: 'image-demo01', size: 'small', environment: 'prod' }

  it('warns when the new machine takes a budget’s forecast over, naming the budget', () => {
    const dana = bff('dana')
    const prod = body<{ items: ConsoleBudget[] }>(dana.get('/console/v1/budgets')).items.find((b) => b.name === 'Production')!
    const review = preview(dana, prodVm)
    // production needs an approval anyway; the budget only warns while it is not over
    expect(review.policy.decision).toBe('needs-approval')
    const rules = budgetRulesOf(review.policy.rules)
    expect(rules).toEqual([{ id: 'budget.forecast-over', result: 'warn', budgetId: prod.budgetId, message: expect.stringMatching(/^The budget Production is forecast to be over: \$[\d,]+\.\d{2} of \$5,700\.00 by the end of 2026-10, with this one$/) }])
    // a dev machine is in no budget that is near its amount
    expect(budgetRulesOf(preview(dana, { ...prodVm, environment: 'dev', size: 'large' }).policy.rules)).toEqual([])
    // a test machine: its budget is over but does not block, so the review warns
    const test = budgetRulesOf(preview(dana, { ...prodVm, environment: 'test' }).policy.rules)
    expect(test.map((r) => [r.id, r.result])).toEqual([['budget.forecast-over', 'warn']])
  })

  it('refuses the create once a blocking budget is over, until an admin lifts the block', () => {
    let dana = bff('dana')
    const prod = body<{ items: ConsoleBudget[] }>(dana.get('/console/v1/budgets')).items.find((b) => b.name === 'Production')!
    // five days on (and signed in again), production has spent past its amount
    dana.tick(5 * DAY_MS)
    dana = dana.as('dana')
    const review = preview(dana, prodVm)
    expect(review.policy.decision).toBe('deny')
    const [rule] = budgetRulesOf(review.policy.rules)
    expect(rule).toMatchObject({ id: 'budget.exceeded', result: 'deny', budgetId: prod.budgetId })
    expect(rule.message).toMatch(/^The budget Production is over: \$[\d,]+\.\d{2} of \$5,700\.00 spent in 2026-10$/)
    // the create itself is refused by the budget, naming it
    const refused = dana.call('POST', '/console/v1/r/vm', { input: prodVm })
    expect(refused.status).toBe(409)
    expect(refused.body).toEqual({
      error: 'budget.exceeded',
      message: rule.message,
      problems: [{ level: 'error', code: 'budget.exceeded', message: rule.message }],
      detail: { budgets: [{ budgetId: prod.budgetId, name: 'Production', message: rule.message }] },
    })
    // a create no blocking budget covers goes on
    expect(dana.call('POST', '/console/v1/r/vm', { input: { ...prodVm, name: 'web-91', environment: 'dev' } }).status).toBe(202)

    const gonzalo = dana.as('gonzalo')
    const mine = body<{ items: ConsoleBudget[] }>(gonzalo.get('/console/v1/budgets')).items.find((b) => b.name === 'Production')!
    expect(mine.status).toMatchObject({ state: 'over', alertsSent: [80, 100] })
    const lifted = gonzalo.call('PUT', `/console/v1/budgets/${mine.budgetId}`, { name: 'Production', amount: mine.amount, thresholds: mine.thresholds, blockCreates: false }, { 'if-match': '1' })
    expect(lifted.status).toBe(200)
    expect(budgetRulesOf(preview(gonzalo, prodVm).policy.rules).map((r) => [r.id, r.result])).toEqual([['budget.forecast-over', 'warn']])
  })
})
