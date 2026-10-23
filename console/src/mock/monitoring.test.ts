import { describe, expect, it } from 'vitest'
import type { Alarm, Recommendation, RecommendationApplied, RecommendationsSummary, ResourceMetrics } from '../lib/monitoring'
import type { BffJobDetail, ConsoleResourceDetail, Denial, Page, Problem } from '../lib/types'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { APPROVAL_MS, RUN_MS, typeOf, wfrnOfResource } from './commands'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { evaluateAlarm, judge, modelOf, monitoringStateOf, notApplicable, passOf, profileOf, seriesPoints, stepFor, valueAt, type StoredAlarm } from './monitoring'

/*
 * Monitoring and rightsizing in the mock: the series are deterministic, bounded and at the asked
 * step; the observe permission and budget; alarms evaluated when read and changed through PUT
 * and DELETE; the idle and oversized rules; apply starting the type's workflow and marking the
 * recommendation applied at once; dismiss for a while or for good; and the journal's replay.
 */

const T0 = Date.parse('2026-10-23T10:00:00Z')
const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const COMPUTE = '123456789012'
const DATA = '210987654321'
const vm = (id: string) => `wfrn:wf:compute:global:${COMPUTE}:vm/${id}`
const WEB_01 = vm('vm-demo01')
const WEB_02 = vm('vm-demo03')
const SHOP_01 = vm('vm-demo06')
const BUILDING = vm('vm-gen-004')

function bff(person: string, start = T0) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const login = () => server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  login()
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body })
  return {
    data,
    call,
    get: (url: string) => call('GET', url),
    /** moves the clock, signing in again past the session's eight hours */
    tick: (ms: number) => {
      now += ms
      if (ms > HOUR) login()
    },
    now: () => now,
  }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const enc = encodeURIComponent
const metricsUrl = (wfrn: string, q = '', type = 'vm') => `/console/v1/r/${type}/${enc(wfrn)}/metrics${q}`
const seriesNamed = (m: ResourceMetrics, name: string) => m.series.find((s) => s.name === name)!
const values = (m: ResourceMetrics, name: string) => seriesNamed(m, name).points.map((p) => p.value as number)

describe('the metric series', () => {
  it('answers the last 24 hours at 5-minute steps when nothing is asked, the same on every read', () => {
    const gonzalo = bff('gonzalo')
    const res = gonzalo.get(metricsUrl(WEB_01))
    expect(res.status).toBe(200)
    const m = body<ResourceMetrics>(res)
    expect(m).toMatchObject({ wfrn: WEB_01, from: '2026-10-22T10:00:00.000Z', to: '2026-10-23T10:00:00.000Z', step: 'PT5M', provider: 'vsphere', action: 'vm.metrics' })
    expect(m.series.map((s) => [s.name, s.unit])).toEqual([
      ['cpuPercent', 'percent'],
      ['memoryPercent', 'percent'],
      ['diskPercent', 'percent'],
    ])
    expect(seriesNamed(m, 'cpuPercent').points).toHaveLength(289)
    // the same minute reads the same, from another session and another dataset alike
    expect(body<ResourceMetrics>(bff('dana').get(metricsUrl(WEB_01)))).toEqual(m)
  })

  it('places points on round multiples of the step, one hour apart beyond a day, and never after now', () => {
    const gonzalo = bff('gonzalo')
    const m = body<ResourceMetrics>(gonzalo.get(metricsUrl(WEB_01, `?from=${enc('2026-10-20T10:07:00Z')}&to=${enc('2026-10-23T10:00:00Z')}`)))
    expect(m.step).toBe('PT1H')
    const at = seriesNamed(m, 'cpuPercent').points.map((p) => Date.parse(p.at))
    expect(at[0]).toBe(Date.parse('2026-10-20T11:00:00Z'))
    expect(at.every((t, i) => i === 0 || t - at[i - 1] === HOUR)).toBe(true)
    const ahead = body<ResourceMetrics>(gonzalo.get(metricsUrl(WEB_01, `?from=${enc('2026-10-23T09:00:00Z')}&to=${enc('2026-10-23T11:00:00Z')}&step=PT1M`)))
    const points = seriesNamed(ahead, 'cpuPercent').points
    expect(points).toHaveLength(61)
    expect(points[points.length - 1].at).toBe('2026-10-23T10:00:00.000Z')
    expect(ahead.to).toBe('2026-10-23T11:00:00.000Z')
  })

  it('coarsens a fine step so no series carries more than 500 points', () => {
    const gonzalo = bff('gonzalo')
    const m = body<ResourceMetrics>(gonzalo.get(metricsUrl(WEB_01, `?from=${enc('2026-10-16T10:00:00Z')}&to=${enc('2026-10-23T10:00:00Z')}&step=PT1M`)))
    expect(m.step).toBe('PT30M')
    expect(seriesNamed(m, 'cpuPercent').points.length).toBeLessThanOrEqual(500)
    expect(stepFor(DAY, MINUTE)).toBe(5 * MINUTE)
    expect(stepFor(HOUR, MINUTE)).toBe(MINUTE)
    expect(stepFor(31 * DAY, MINUTE)).toBe(2 * HOUR)
  })

  it('keeps percentages between 0 and 100 and counts whole, with a daily rhythm', () => {
    for (const id of ['vm-demo01', 'vm-demo06', 'vm-gen-010', 'vm-gen-033']) {
      for (const series of ['cpuPercent', 'memoryPercent', 'diskPercent']) {
        const pts = seriesPoints(modelOf({ id, name: id, createdAt: '2026-09-01T00:00:00Z' }, series), { start: 0, end: Infinity }, T0 - 7 * DAY, T0, 5 * MINUTE)
        expect(pts.every((p) => typeof p.value === 'number' && p.value >= 0 && p.value <= 100)).toBe(true)
      }
    }
    const connections = seriesPoints(modelOf({ id: 'database-demo01', name: 'orders-db', createdAt: '2026-09-01T00:00:00Z' }, 'connections'), { start: 0, end: Infinity }, T0 - DAY, T0, 5 * MINUTE)
    expect(connections.every((p) => Number.isInteger(p.value) && (p.value as number) >= 0)).toBe(true)
    // a busy web machine works harder at 13:00 UTC on a weekday than at 03:00
    const web = modelOf({ id: 'vm-demo01', name: 'web-01', createdAt: '2026-09-01T00:00:00Z' }, 'cpuPercent')
    expect(valueAt(web, Date.parse('2026-10-22T13:00:00Z'))).toBeGreaterThan(valueAt(web, Date.parse('2026-10-22T03:00:00Z')) + 10)
    // and less on a Saturday than on the Thursday before
    expect(valueAt(web, Date.parse('2026-10-24T13:00:00Z'))).toBeLessThan(valueAt(web, Date.parse('2026-10-22T13:00:00Z')))
  })

  it('draws an idle machine under 5% and an oversized one under 30%, and nothing for one still being built', () => {
    const gonzalo = bff('gonzalo')
    const idle = body<ResourceMetrics>(gonzalo.get(metricsUrl(SHOP_01)))
    expect(Math.max(...values(idle, 'cpuPercent'))).toBeLessThan(5)
    expect(Math.max(...values(idle, 'memoryPercent'))).toBeLessThan(5)
    const oversized = body<ResourceMetrics>(gonzalo.get(metricsUrl(WEB_02)))
    expect(Math.max(...values(oversized, 'cpuPercent'))).toBeLessThan(30)
    expect(profileOf('vm-demo03')).toBe('oversized')
    const building = body<ResourceMetrics>(gonzalo.get(metricsUrl(BUILDING)))
    expect(building.series.every((s) => s.points.length === 0)).toBe(true)
  })

  it('reads a database’s CPU, memory and connections in its own account', () => {
    const raj = bff('raj')
    const db = wfrnOfResource(raj.data, { region: 'global' }, typeOf(raj.data, 'database')!, raj.data.resources.find((r) => r.id === 'database-demo01')!)
    expect(db).toBe(`wfrn:wf:data:global:${DATA}:database/database-demo01`)
    const m = body<ResourceMetrics>(raj.get(metricsUrl(db, '', 'database')))
    expect(m.series.map((s) => s.name)).toEqual(['cpuPercent', 'memoryPercent', 'connections'])
    expect(m.series[2].unit).toBe('count')
    // another account's machine is not there for them
    expect(raj.get(metricsUrl(WEB_01)).status).toBe(404)
  })

  it('refuses a bad window with the fields named', () => {
    const gonzalo = bff('gonzalo')
    const fields = (q: string) => body<{ problems: Problem[] }>(gonzalo.get(metricsUrl(WEB_01, q))).problems.map((p) => p.field)
    expect(gonzalo.get(metricsUrl(WEB_01, '?step=PT30S')).status).toBe(400)
    expect(fields('?step=PT30S')).toEqual(['/step'])
    expect(fields('?step=five')).toEqual(['/step'])
    expect(fields(`?from=${enc('2026-10-23T10:00:00Z')}&to=${enc('2026-10-23T09:00:00Z')}`)).toEqual(['/from'])
    expect(fields(`?from=${enc('2026-09-01T00:00:00Z')}&to=${enc('2026-10-23T00:00:00Z')}`)).toEqual(['/from'])
    expect(fields('?to=yesterday')).toEqual(['/to'])
  })

  it('answers 404 for a type without a metrics read, a wfrn of another type, or nothing there', () => {
    const gonzalo = bff('gonzalo')
    const volume = gonzalo.data.resources.find((r) => r.id === 'volume-demo01')!
    const volumeWfrn = wfrnOfResource(gonzalo.data, { region: 'global' }, typeOf(gonzalo.data, 'volume')!, volume)
    const res = gonzalo.get(metricsUrl(volumeWfrn, '', 'volume'))
    expect(res.status).toBe(404)
    expect(body<{ error: string }>(res).error).toBe('metrics.notSupported')
    expect(gonzalo.get(metricsUrl(WEB_01, '', 'database')).status).toBe(404)
    expect(gonzalo.get(metricsUrl(vm('vm-nothing'))).status).toBe(404)
  })

  it('needs observe on the wfrn, which every managed role grants', () => {
    const mia = bff('mia')
    expect(mia.get(metricsUrl(WEB_01)).status).toBe(200)
    const viewer = mia.data.directory.policies[COMPUTE].find((p) => p.policyId === 'managed-viewer')!
    viewer.statements = viewer.statements.map((s) => ({ ...s, actions: s.actions?.filter((a) => a !== '*:observe') }))
    const res = mia.get(metricsUrl(WEB_01))
    expect(res.status).toBe(403)
    expect(body<{ denied: Denial }>(res).denied).toMatchObject({ layer: 'console-iam', action: 'compute:observe' })
  })

  it('answers 429 with Retry-After once the account’s observe budget for the minute is used', () => {
    const gonzalo = bff('gonzalo', T0 + 15_000)
    monitoringStateOf(gonzalo.data).budgetPerMinute = 2
    expect(gonzalo.get(metricsUrl(WEB_01)).status).toBe(200)
    expect(gonzalo.get(metricsUrl(WEB_02)).status).toBe(200)
    const over = gonzalo.get(metricsUrl(WEB_01))
    expect(over.status).toBe(429)
    expect(over.headers?.['Retry-After']).toBe('45')
    expect(body<{ retryAfter: number }>(over).retryAfter).toBe(45)
    gonzalo.tick(45_000)
    expect(gonzalo.get(metricsUrl(WEB_01)).status).toBe(200)
  })
})

describe('alarms', () => {
  it('lists the account’s alarms firing first, then without data, then quiet, by name', () => {
    const gonzalo = bff('gonzalo')
    const res = gonzalo.get('/console/v1/alarms')
    expect(res.status).toBe(200)
    const items = body<Page<Alarm>>(res).items
    expect(items.map((a) => [a.alarmId, a.state])).toEqual([
      ['shop-01-doing-nothing', 'alarm'],
      ['vm-gen-004-memory', 'insufficient'],
      ['api-01-memory-high', 'ok'],
      ['cache-01-disk-full', 'ok'],
      ['web-01-cpu-high', 'ok'],
    ])
    expect(items[0]).toMatchObject({ accountId: COMPUTE, type: 'vm', wfrn: SHOP_01, metric: 'cpuPercent', comparison: 'below', threshold: 5, windowMinutes: 60, createdBy: 'mateo', lastEvaluatedAt: '2026-10-23T10:00:00.000Z' })
    expect(items[0].lastValue).toBeLessThan(5)
    // quiet since it was made: nothing in its fortnight crossed the line
    expect(items.find((a) => a.alarmId === 'web-01-cpu-high')?.stateSince).toBe('2026-10-12T09:30:00.000Z')
    expect(items.find((a) => a.alarmId === 'vm-gen-004-memory')?.lastValue).toBeNull()
    expect(body<Page<Alarm>>(gonzalo.get('/console/v1/alarms?state=alarm')).items.map((a) => a.alarmId)).toEqual(['shop-01-doing-nothing'])
    expect(body<Page<Alarm>>(gonzalo.get(`/console/v1/alarms?wfrn=${enc(WEB_01)}`)).items.map((a) => a.alarmId)).toEqual(['web-01-cpu-high'])
    expect(gonzalo.get('/console/v1/alarms?state=red').status).toBe(400)
    const page1 = body<Page<Alarm>>(gonzalo.get('/console/v1/alarms?limit=2'))
    expect(page1.items).toHaveLength(2)
    expect(body<Page<Alarm>>(gonzalo.get(`/console/v1/alarms?limit=2&after=${page1.next}`)).items[0].alarmId).toBe('api-01-memory-high')
    // the other account has its own
    expect(body<Page<Alarm>>(bff('raj').get('/console/v1/alarms')).items.map((a) => a.alarmId)).toEqual(['orders-db-connections'])
  })

  it('answers a resource’s alarms, and leaves out the types a person may not list', () => {
    const mia = bff('mia')
    expect(body<{ items: Alarm[] }>(mia.get(`/console/v1/r/vm/${enc(SHOP_01)}/alarms`)).items.map((a) => a.alarmId)).toEqual(['shop-01-doing-nothing'])
    expect(body<{ items: Alarm[] }>(mia.get(`/console/v1/r/vm/${enc(WEB_02)}/alarms`)).items).toEqual([])
    const viewer = mia.data.directory.policies[COMPUTE].find((p) => p.policyId === 'managed-viewer')!
    viewer.statements = viewer.statements.map((s) => ({ ...s, actions: s.actions?.filter((a) => a !== '*:list') }))
    expect(body<Page<Alarm>>(mia.get('/console/v1/alarms')).items).toEqual([])
  })

  it('creates an alarm with the id the web app picked, evaluates it at once, and replaces it on a second PUT', () => {
    const dana = bff('dana')
    const created = dana.call('PUT', '/console/v1/alarms/al-web-busy', { wfrn: WEB_01, metric: 'cpuPercent', comparison: 'above', threshold: 40, windowMinutes: 5, name: 'web-01 busy' })
    expect(created.status).toBe(201)
    const alarm = body<Alarm>(created)
    expect(alarm).toMatchObject({ alarmId: 'al-web-busy', accountId: COMPUTE, type: 'vm', wfrn: WEB_01, name: 'web-01 busy', state: 'alarm', stateSince: '2026-10-23T10:00:00.000Z', createdBy: 'dana', createdAt: '2026-10-23T10:00:00.000Z' })
    expect(alarm.lastValue).toBeGreaterThan(40)
    expect(body<Page<Alarm>>(dana.get('/console/v1/alarms?state=alarm')).items.map((a) => a.alarmId)).toEqual(['shop-01-doing-nothing', 'al-web-busy'])

    dana.tick(5 * MINUTE)
    const replaced = dana.call('PUT', '/console/v1/alarms/al-web-busy', { wfrn: WEB_01, metric: 'cpuPercent', comparison: 'above', threshold: 99 })
    expect(replaced.status).toBe(200)
    expect(body<Alarm>(replaced)).toMatchObject({ state: 'ok', threshold: 99, windowMinutes: 10, createdAt: '2026-10-23T10:00:00.000Z', updatedAt: '2026-10-23T10:05:00.000Z', stateSince: '2026-10-23T10:05:00.000Z' })
    expect(body<Alarm>(replaced).name).toBeUndefined()
  })

  it('validates the body field by field, against the type’s series', () => {
    const dana = bff('dana')
    const fields = (alarmId: string, b: unknown) => body<{ problems: Problem[] }>(dana.call('PUT', `/console/v1/alarms/${alarmId}`, b)).problems.map((p) => p.field)
    expect(fields('a1', { wfrn: WEB_01 })).toEqual(['/metric', '/comparison', '/threshold'])
    expect(fields('a1', { wfrn: WEB_01, metric: 'connections', comparison: 'above', threshold: 1 })).toEqual(['/metric'])
    expect(fields('a1', { wfrn: WEB_01, metric: 'cpuPercent', comparison: 'over', threshold: '80', windowMinutes: 0 })).toEqual(['/comparison', '/threshold', '/windowMinutes'])
    expect(fields('a1', { metric: 'cpuPercent' })).toContain('/wfrn')
    expect(fields('bad%20id', { wfrn: WEB_01, metric: 'cpuPercent', comparison: 'above', threshold: 1 })).toEqual(['/alarmId'])
    const volume = dana.data.resources.find((r) => r.id === 'volume-demo01')!
    const volumeWfrn = wfrnOfResource(dana.data, { region: 'global' }, typeOf(dana.data, 'volume')!, volume)
    expect(body<{ error: string }>(dana.call('PUT', '/console/v1/alarms/a2', { wfrn: volumeWfrn, metric: 'cpuPercent', comparison: 'above', threshold: 1 })).error).toBe('metrics.notSupported')
    expect(dana.call('PUT', '/console/v1/alarms/a3', { wfrn: vm('vm-nothing'), metric: 'cpuPercent', comparison: 'above', threshold: 1 }).status).toBe(404)
  })

  it('needs update on the wfrn to change or delete one; a viewer is refused with the reason', () => {
    const mia = bff('mia')
    const put = mia.call('PUT', '/console/v1/alarms/al-mine', { wfrn: WEB_01, metric: 'cpuPercent', comparison: 'above', threshold: 80 })
    expect(put.status).toBe(403)
    expect(body<{ denied: Denial }>(put).denied).toMatchObject({ action: 'compute:update' })
    expect(mia.call('DELETE', '/console/v1/alarms/web-01-cpu-high').status).toBe(403)

    const dana = bff('dana')
    expect(dana.call('DELETE', '/console/v1/alarms/web-01-cpu-high').status).toBe(204)
    expect(dana.call('DELETE', '/console/v1/alarms/web-01-cpu-high').status).toBe(404)
    expect(body<Page<Alarm>>(dana.get('/console/v1/alarms')).items.map((a) => a.alarmId)).not.toContain('web-01-cpu-high')
    expect(dana.call('POST', '/console/v1/alarms/web-01-cpu-high', {}).status).toBe(405)
  })

  it('evaluates the window’s average, with no points not enough data, and when the state began', () => {
    const data = buildMockData()
    const base: StoredAlarm = { alarmId: 'x', accountId: COMPUTE, wfrn: WEB_01, type: 'vm', metric: 'cpuPercent', comparison: 'above', threshold: 0, windowMinutes: 10, createdBy: 'dana', createdAt: '2026-10-20T00:00:00.000Z', updatedAt: '2026-10-20T00:00:00.000Z' }
    expect(evaluateAlarm(data, base, T0)).toMatchObject({ state: 'alarm', stateSince: '2026-10-20T00:00:00.000Z' })
    expect(evaluateAlarm(data, { ...base, comparison: 'below' }, T0).state).toBe('ok')
    expect(evaluateAlarm(data, { ...base, wfrn: BUILDING }, T0)).toMatchObject({ state: 'insufficient', lastValue: null })
    expect(evaluateAlarm(data, { ...base, metric: 'connections' }, T0).state).toBe('insufficient')
    // a threshold the daily rhythm crosses: the state began at a minute between the change and now
    const avg = evaluateAlarm(data, base, T0).lastValue as number
    const crossing = evaluateAlarm(data, { ...base, threshold: avg - 3 }, T0)
    expect(crossing.state).toBe('alarm')
    expect(Date.parse(crossing.stateSince!)).toBeGreaterThan(Date.parse(base.updatedAt))
    expect(Date.parse(crossing.stateSince!) % MINUTE).toBe(0)
    // evaluating twice in the same minute agrees
    expect(evaluateAlarm(data, { ...base, threshold: avg - 3 }, T0 + 30_000)).toMatchObject({ state: crossing.state, stateSince: crossing.stateSince })
  })

  it('keeps alarms across a reload through the journal', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) }
    const dana = bff('dana')
    dana.call('PUT', '/console/v1/alarms/al-kept', { wfrn: WEB_02, metric: 'memoryPercent', comparison: 'above', threshold: 50 })
    dana.call('DELETE', '/console/v1/alarms/cache-01-disk-full')
    saveConsole(dana.data, storage)
    const fresh = buildMockData()
    expect(restoreConsole(fresh, storage, T0 + MINUTE)).toBe(true)
    const kept = monitoringStateOf(fresh).alarms
    expect(kept[`${COMPUTE}/al-kept`]).toMatchObject({ wfrn: WEB_02, threshold: 50, createdBy: 'dana', createdAt: '2026-10-23T10:00:00.000Z' })
    expect(kept[`${COMPUTE}/cache-01-disk-full`]).toBeUndefined()
  })
})

describe('the rightsizing rules', () => {
  const rt = () => typeOf(buildMockData(), 'vm')!
  const u = (cpuP95: number, memoryP95: number, samples = 1344) => ({ from: '', to: '', samples, cpuP95, memoryP95 })

  it('calls a resource idle when both 95th percentiles are under 5%, and recommends deleting it', () => {
    expect(judge(rt(), u(3, 4.9), { cpu: 4, memoryGb: 16 })).toEqual({ kind: 'idle', action: 'delete' })
    expect(judge(rt(), u(3, 5), { cpu: 4, memoryGb: 16 })?.kind).not.toBe('idle')
  })

  it('resizes an oversized one to the smallest size that keeps both under 60%, never larger than it is', () => {
    expect(judge(rt(), u(20, 25), { cpu: 4, memoryGb: 16 })).toEqual({ kind: 'oversized', action: 'resize', proposed: { cpu: 2, memoryGb: 8 } })
    expect(judge(rt(), u(25, 29), { cpu: 8, memoryGb: 32 })).toEqual({ kind: 'oversized', action: 'resize', proposed: { cpu: 4, memoryGb: 16 } })
    expect(judge(rt(), u(16, 18), { cpu: 16, memoryGb: 64 })?.proposed).toEqual({ cpu: 8, memoryGb: 32 })
    expect(judge(rt(), u(10, 12), { cpu: 16, memoryGb: 64 })?.proposed).toEqual({ cpu: 4, memoryGb: 16 })
    // already the smallest, or nothing smaller in both: no saving to offer
    expect(judge(rt(), u(20, 20), { cpu: 2, memoryGb: 8 })).toBeNull()
    expect(judge(rt(), u(15, 15), { cpu: 16, memoryGb: 4 })).toBeNull()
  })

  it('leaves alone a busy one, one with too few samples, and a database that is only quiet', () => {
    expect(judge(rt(), u(31, 10), { cpu: 4, memoryGb: 16 })).toBeNull()
    expect(judge(rt(), u(2, 2, 100), { cpu: 4, memoryGb: 16 })).toBeNull()
    const db = typeOf(buildMockData(), 'database')!
    expect(judge(db, u(20, 20), { tier: 'M30' })).toBeNull()
    expect(judge(db, u(2, 2), { tier: 'M10' })).toEqual({ kind: 'idle', action: 'delete' })
  })

  it('runs the daily pass at 05:00 UTC', () => {
    expect(new Date(passOf(T0)).toISOString()).toBe('2026-10-23T05:00:00.000Z')
    expect(new Date(passOf(Date.parse('2026-10-23T04:59:00Z'))).toISOString()).toBe('2026-10-22T05:00:00.000Z')
  })

  it('says why a recommendation cannot be carried out', () => {
    const data = buildMockData()
    expect(notApplicable(data, typeOf(data, 'vm')!, { action: 'resize', proposed: { cpu: 2, memoryGb: 8 } })).toBeNull()
    expect(notApplicable(data, typeOf(data, 'vm')!, { action: 'delete' })).toBeNull()
    expect(notApplicable(data, typeOf(data, 'database')!, { action: 'delete' })).toBe('No workflow deletes databases; the provider publishes none in its catalog')
    expect(notApplicable(data, typeOf(data, 'vm')!, { action: 'resize', proposed: { disks: 2 } })).toBe('The update workflow of vms does not set disks')
  })
})

describe('recommendations through the BFF', () => {
  const open = (b: ReturnType<typeof bff>, q = '') => body<Page<Recommendation>>(b.get(`/console/v1/recommendations?limit=200${q}`)).items
  const named = (items: Recommendation[], name: string) => items.find((r) => r.name === name)!

  it('lists the open ones, the largest saving first, with sizes, percentiles, costs and what the person may do', () => {
    const gonzalo = bff('gonzalo')
    const items = open(gonzalo)
    expect(items.length).toBeGreaterThan(10)
    expect(items.every((r, i) => i === 0 || (r.monthlySaving?.amount ?? 0) <= (items[i - 1].monthlySaving?.amount ?? 0))).toBe(true)
    expect(items.every((r) => r.status === 'open' && r.accountId === COMPUTE && r.region === 'global')).toBe(true)
    const web02 = named(items, 'web-02')
    expect(web02).toMatchObject({ wfrn: WEB_02, type: 'vm', kind: 'oversized', action: 'resize', current: { cpu: 4, memoryGb: 16 }, proposed: { cpu: 2, memoryGb: 8 }, monthlyCost: { amount: 77, currency: 'USD' }, monthlySaving: { amount: 38.5, currency: 'USD' }, can: ['apply', 'dismiss'] })
    expect(web02.recommendationId).toMatch(/^rec_[a-z0-9]{10}$/)
    expect(web02.utilisation).toMatchObject({ from: '2026-10-09T05:00:00.000Z', to: '2026-10-23T05:00:00.000Z' })
    expect(web02.utilisation.cpuP95).toBeLessThan(30)
    expect(web02.utilisation.samples).toBeGreaterThan(1000)
    const shop01 = named(items, 'shop-01')
    expect(shop01).toMatchObject({ wfrn: SHOP_01, kind: 'idle', action: 'delete', monthlySaving: { amount: 38.5 } })
    expect(shop01.proposed).toBeUndefined()
    expect(shop01.utilisation.cpuP95).toBeLessThan(5)
    expect(shop01.utilisation.memoryP95).toBeLessThan(5)
    // the busy machine and the ones still being built are not there
    expect(items.some((r) => r.name === 'web-01' || r.wfrn === BUILDING)).toBe(false)
    expect(open(gonzalo, '&kind=idle').every((r) => r.kind === 'idle')).toBe(true)
    expect(open(gonzalo, '&type=database')).toEqual([])
    expect(gonzalo.get('/console/v1/recommendations?kind=big').status).toBe(400)
    expect(gonzalo.get('/console/v1/recommendations?status=closed').status).toBe(400)
    expect(body<Recommendation>(gonzalo.get(`/console/v1/recommendations/${web02.recommendationId}`))).toEqual(web02)
    expect(gonzalo.get('/console/v1/recommendations/rec_nothing').status).toBe(404)
  })

  it('offers an operator resizes but no deletes, and a viewer neither', () => {
    const dana = open(bff('dana'))
    expect(named(dana, 'web-02').can).toEqual(['apply', 'dismiss'])
    expect(named(dana, 'shop-01').can).toEqual(['dismiss'])
    expect(open(bff('mia')).every((r) => r.can.length === 0)).toBe(true)
    // a database has no delete workflow: nobody can apply its idle recommendation, an admin may still dismiss it
    const raj = open(bff('raj'))
    expect(raj.map((r) => [r.name, r.kind, r.action, r.can])).toEqual([['billing-db', 'idle', 'delete', ['dismiss']]])
  })

  it('sums the open ones with the time of the last pass', () => {
    const gonzalo = bff('gonzalo')
    const items = open(gonzalo)
    const summary = body<RecommendationsSummary>(gonzalo.get('/console/v1/recommendations/summary'))
    expect(summary.open).toBe(items.length)
    expect(summary.byKind.idle + summary.byKind.oversized).toBe(items.length)
    expect(summary.monthlySaving.amount).toBeCloseTo(items.reduce((n, r) => n + (r.monthlySaving?.amount ?? 0), 0), 2)
    expect(summary.lastPassAt).toBe('2026-10-23T05:00:00.000Z')
  })

  it('applies a resize through the update workflow, applied as soon as the job starts, and the machine is resized when it ends', () => {
    const dana = bff('dana')
    const rec = named(open(dana), 'web-02')
    const res = dana.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {}, { 'idempotency-key': 'k-resize' })
    expect(res.status).toBe(202)
    const applied = body<RecommendationApplied>(res)
    expect(applied.recommendationId).toBe(rec.recommendationId)
    expect(applied.jobId).toMatch(/^job_[a-z0-9]{10}$/)
    // the same key and body answer the same again
    const again = dana.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {}, { 'idempotency-key': 'k-resize' })
    expect(again.headers?.['Idempotent-Replayed']).toBe('true')
    expect(body<RecommendationApplied>(again)).toEqual(applied)

    expect(open(dana).some((r) => r.recommendationId === rec.recommendationId)).toBe(false)
    expect(named(open(dana, '&status=applied'), 'web-02')).toMatchObject({ status: 'applied', jobId: applied.jobId, decidedBy: 'dana', can: [] })
    const job = body<BffJobDetail>(dana.get(`/console/v1/jobs/${applied.jobId}`))
    expect(job).toMatchObject({ jobId: applied.jobId, workflow: 'update-vm', requester: 'dana', wfrn: WEB_02, status: 'waiting-approval' })
    expect(job.input).toEqual({ cpu: 2, memoryGb: 8 })
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(WEB_02)}`)).state).toBe('updating')
    const conflict = dana.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {})
    expect(conflict.status).toBe(409)
    expect(body<{ error: string }>(conflict).error).toBe('recommendation.closed')

    dana.tick(APPROVAL_MS + RUN_MS + 1)
    expect(body<BffJobDetail>(dana.get(`/console/v1/jobs/${applied.jobId}`)).status).toBe('succeeded')
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(WEB_02)}`)).attributes).toMatchObject({ cpu: 2, memoryGb: 8 })
    // the same load on half the CPUs reads twice as high from then on
    const ended = Date.parse(body<BffJobDetail>(dana.get(`/console/v1/jobs/${applied.jobId}`)).endedAt!)
    dana.tick(10 * MINUTE)
    const m = body<ResourceMetrics>(dana.get(metricsUrl(WEB_02, `?from=${enc(new Date(ended + 2 * MINUTE).toISOString())}&to=${enc(new Date(ended + 4 * MINUTE).toISOString())}&step=PT1M`)))
    const point = seriesNamed(m, 'cpuPercent').points[0]
    const model = modelOf(dana.data.resources.find((r) => r.id === 'vm-demo03')!, 'cpuPercent')
    expect(point.value).toBeCloseTo(Math.round(valueAt(model, Date.parse(point.at) - 30_000) * 2 * 10) / 10, 5)
  })

  it('applies a delete only with confirm, for someone who may delete, through the delete workflow', () => {
    const dana = bff('dana')
    const idle = named(open(dana), 'shop-01')
    const refused = dana.call('POST', `/console/v1/recommendations/${idle.recommendationId}:apply`, { confirm: true })
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ action: 'compute:delete' })

    const gonzalo = bff('gonzalo')
    const rec = named(open(gonzalo), 'shop-01')
    const unconfirmed = gonzalo.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {})
    expect(unconfirmed.status).toBe(400)
    expect(body<{ error: string; problems: Problem[] }>(unconfirmed)).toMatchObject({ error: 'confirm.required', problems: [{ field: '/confirm' }] })
    const res = gonzalo.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, { confirm: true, reason: 'Nobody has used it since the shop moved' })
    expect(res.status).toBe(202)
    const { jobId } = body<RecommendationApplied>(res)
    const job = body<BffJobDetail>(gonzalo.get(`/console/v1/jobs/${jobId}`))
    expect(job).toMatchObject({ workflow: 'delete-vm', status: 'waiting-approval', wfrn: SHOP_01 })
    expect(job.input).toMatchObject({ vmId: 'vm-demo06', reason: 'Nobody has used it since the shop moved' })
    expect(named(open(gonzalo, '&status=applied'), 'shop-01')).toMatchObject({ jobId, reason: 'Nobody has used it since the shop moved' })
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    expect(body<BffJobDetail>(gonzalo.get(`/console/v1/jobs/${jobId}`)).status).toBe('succeeded')
    expect(gonzalo.data.resources.find((r) => r.id === 'vm-demo06')?.status).toBe('retired')
    gonzalo.tick(DAY)
    expect(open(gonzalo).some((r) => r.name === 'shop-01')).toBe(false)
    expect(named(open(gonzalo, '&status=applied'), 'shop-01').jobId).toBe(jobId)
  })

  it('refuses to apply while another request changes the resource, or what the console cannot carry out', () => {
    const gonzalo = bff('gonzalo')
    const rec = named(open(gonzalo), 'web-02')
    expect(gonzalo.call('PATCH', `/console/v1/r/vm/${enc(WEB_02)}`, { input: { cpu: 8 } }).status).toBe(202)
    const busy = gonzalo.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {})
    expect(busy.status).toBe(409)
    expect(body<{ error: string }>(busy).error).toBe('recommendation.changing')

    expect(gonzalo.call('PUT', '/console/v1/session', { accountId: DATA }).status).toBe(200)
    const db = named(open(gonzalo), 'billing-db')
    expect(db.can).toEqual(['dismiss'])
    const res = gonzalo.call('POST', `/console/v1/recommendations/${db.recommendationId}:apply`, { confirm: true })
    expect(res.status).toBe(409)
    expect(body<{ error: string }>(res).error).toBe('recommendation.notApplicable')
  })

  it('dismisses one for a while or for good, with the reason, and a later pass opens it again after the date', () => {
    const dana = bff('dana')
    const [first, second] = [named(open(dana), 'web-02'), named(open(dana), 'cache-067')]
    expect(bff('mia').call('POST', `/console/v1/recommendations/${first.recommendationId}:dismiss`, {}).status).toBe(403)
    const until = new Date(T0 + 7 * DAY).toISOString()
    const res = dana.call('POST', `/console/v1/recommendations/${first.recommendationId}:dismiss`, { reason: 'Black Friday is coming', until })
    expect(res.status).toBe(200)
    expect(body<Recommendation>(res)).toMatchObject({ status: 'dismissed', decidedBy: 'dana', reason: 'Black Friday is coming', dismissedUntil: until, can: [] })
    expect(dana.call('POST', `/console/v1/recommendations/${second.recommendationId}:dismiss`, {}).status).toBe(200)
    expect(open(dana).some((r) => r.recommendationId === first.recommendationId || r.recommendationId === second.recommendationId)).toBe(false)
    expect(open(dana, '&status=dismissed').map((r) => r.name).sort()).toEqual(['cache-067', 'web-02'])
    const twice = dana.call('POST', `/console/v1/recommendations/${first.recommendationId}:dismiss`, {})
    expect(twice.status).toBe(409)
    expect(body<{ error: string }>(twice).error).toBe('recommendation.closed')
    expect(dana.call('POST', `/console/v1/recommendations/${first.recommendationId}:apply`, {}).status).toBe(409)
    const fields = (b: unknown) => body<{ problems: Problem[] }>(dana.call('POST', `/console/v1/recommendations/${named(open(dana), 'web-028').recommendationId}:dismiss`, b)).problems.map((p) => p.field)
    expect(fields({ until: '2026-01-01T00:00:00Z' })).toEqual(['/until'])
    expect(fields({ reason: 'x'.repeat(1001) })).toEqual(['/reason'])

    dana.tick(8 * DAY)
    const later = open(dana)
    expect(later.find((r) => r.recommendationId === first.recommendationId)?.status).toBe('open')
    expect(later.some((r) => r.recommendationId === second.recommendationId)).toBe(false)
  })

  it('replays applies and dismissals onto a fresh dataset with the same ids', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) }
    const dana = bff('dana')
    const rec = named(open(dana), 'web-02')
    const { jobId } = body<RecommendationApplied>(dana.call('POST', `/console/v1/recommendations/${rec.recommendationId}:apply`, {}))
    const other = named(open(dana), 'cache-067')
    dana.call('POST', `/console/v1/recommendations/${other.recommendationId}:dismiss`, { reason: 'kept for the sale' })
    saveConsole(dana.data, storage)

    const fresh = buildMockData()
    expect(restoreConsole(fresh, storage, T0 + APPROVAL_MS + RUN_MS + 1)).toBe(true)
    const decisions = monitoringStateOf(fresh).decisions
    expect(decisions[rec.recommendationId]).toMatchObject({ kind: 'applied', jobId, by: 'dana' })
    expect(decisions[other.recommendationId]).toMatchObject({ kind: 'dismissed', reason: 'kept for the sale' })
    expect(fresh.jobs.find((j) => j.id === jobId)?.status).toBe('succeeded')
    expect(fresh.resources.find((r) => r.id === 'vm-demo03')?.attributes).toMatchObject({ cpu: 2, memoryGb: 8 })
  })
})
