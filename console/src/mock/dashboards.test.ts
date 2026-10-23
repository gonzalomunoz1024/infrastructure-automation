import { describe, expect, it } from 'vitest'
import type { Dashboard, DashboardList, Widget } from '../lib/dashboards'
import type { Denial, Problem } from '../lib/types'
import type { ServiceHealth } from '../lib/health'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Team dashboards and service health in the mock: who sees which dashboard (the owner, a share
 * with the account or a group), what each may do with it (console IAM, then the share), the
 * version every change sends back, the validation of widgets and shares, the journal's replay,
 * and the health of every service with the degraded one.
 */

const T0 = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = '123456789012'
const WEB_01 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo01`

function world() {
  const data = buildMockData()
  let now = T0
  const as = (person: string) => {
    const server = createMockServer(data, memorySessionStore(), () => now)
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
    const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body })
    return { call, get: (url: string) => call('GET', url) }
  }
  return { data, as, tick: (ms: number) => (now += ms) }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const names = (list: DashboardList) => list.items.map((d) => d.name)

const cpu: Widget = { widgetId: 'w-cpu', kind: 'metric', title: 'web-01 CPU', type: 'vm', wfrn: WEB_01, metric: 'cpuPercent', width: 6, height: 2 }
const drift: Widget = { widgetId: 'w-drift', kind: 'drift', title: 'Drift', width: 4, height: 2 }

describe('dashboards', () => {
  it('lists what each person reaches: their own and what is shared with them, never another person’s private board', () => {
    const w = world()
    const dana = body<DashboardList>(w.as('dana').get('/console/v1/dashboards'))
    expect(names(dana)).toEqual(['Compute fleet'])
    expect(dana.items[0]).toMatchObject({ access: 'edit', owner: { name: 'Gonzalo Munoz' }, sharing: { account: 'view', groups: [{ group: 'members', title: 'team-compute', access: 'edit' }] } })
    expect(dana.can.create.allowed).toBe(true)
    const mateo = body<DashboardList>(w.as('mateo').get('/console/v1/dashboards'))
    expect(names(mateo)).toEqual(['Batch nights', 'Compute fleet'])
    expect(names(body<DashboardList>(w.as('mateo').get('/console/v1/dashboards?scope=mine')))).toEqual(['Batch nights'])
    expect(names(body<DashboardList>(w.as('mateo').get('/console/v1/dashboards?scope=shared')))).toEqual(['Compute fleet'])
    expect(w.as('dana').get('/console/v1/dashboards/dash-batch-nights').status).toBe(404)
    // the other account sees none of Compute's
    expect(names(body<DashboardList>(w.as('raj').get('/console/v1/dashboards')))).toEqual([])
  })

  it('lets a viewer read a shared board but neither create one nor change it, with the refusal of console IAM', () => {
    const mia = world().as('mia')
    const list = body<DashboardList>(mia.get('/console/v1/dashboards'))
    expect(list.can.create).toMatchObject({ allowed: false, denied: { layer: 'console-iam', action: 'dashboards:create', suggestedRole: 'operator' } })
    const board = body<Dashboard>(mia.get('/console/v1/dashboards/dash-compute-fleet'))
    expect(board.access).toBe('view')
    expect(board.can.edit).toMatchObject({ allowed: false, denied: { action: 'dashboards:update' } })
    const put = mia.call('PUT', '/console/v1/dashboards/dash-compute-fleet', { name: 'Mine now', widgets: [] }, { 'if-match': `"${board.version}"` })
    expect(put.status).toBe(403)
    expect(body<{ denied: Denial }>(put).denied.layer).toBe('console-iam')
    expect(mia.call('POST', '/console/v1/dashboards', { name: 'Mia’s', widgets: [] }).status).toBe(403)
  })

  it('creates a dashboard once per Idempotency-Key, saves it against its version and refuses a stale save', () => {
    const w = world()
    const dana = w.as('dana')
    const created = dana.call('POST', '/console/v1/dashboards', { name: 'Dana’s board', widgets: [cpu] }, { 'idempotency-key': 'k-1' })
    expect(created.status).toBe(201)
    const d = body<Dashboard>(created)
    expect(d).toMatchObject({ name: 'Dana’s board', access: 'owner', version: 1, sharing: { account: 'none', groups: [] } })
    expect(created.headers?.ETag).toBe('"1"')
    const again = dana.call('POST', '/console/v1/dashboards', { name: 'Dana’s board', widgets: [cpu] }, { 'idempotency-key': 'k-1' })
    expect(body<Dashboard>(again).dashboardId).toBe(d.dashboardId)
    expect(names(body<DashboardList>(dana.get('/console/v1/dashboards?scope=mine')))).toEqual(['Dana’s board'])

    const url = `/console/v1/dashboards/${d.dashboardId}`
    expect(dana.call('PUT', url, { name: 'Dana’s board', widgets: [drift, cpu] }).status).toBe(428)
    w.tick(1000)
    const saved = dana.call('PUT', url, { name: 'Dana’s board', widgets: [drift, cpu] }, { 'if-match': '"1"' })
    expect(saved.status).toBe(200)
    expect(body<Dashboard>(saved).widgets.map((x) => x.widgetId)).toEqual(['w-drift', 'w-cpu'])
    expect(body<Dashboard>(saved).version).toBe(2)
    const stale = dana.call('PUT', url, { name: 'Late', widgets: [] }, { 'if-match': '"1"' })
    expect(stale.status).toBe(409)
    expect(body<{ error: string; version: number }>(stale)).toMatchObject({ error: 'dashboard.changed', version: 2 })
  })

  it('checks every widget and names the field that is wrong', () => {
    const dana = world().as('dana')
    const res = dana.call('POST', '/console/v1/dashboards', {
      name: ' ',
      widgets: [
        { ...cpu, width: 5 },
        { ...cpu, widgetId: 'w-cpu', metric: '' },
        { widgetId: 'w-x', kind: 'pie', title: 'x', width: 3, height: 1 },
        { widgetId: 'w-c', kind: 'count', title: 'Tagged', type: 'vm', width: 3, height: 1, tag: { key: 'Bad Key', value: '' } },
      ],
    })
    expect(res.status).toBe(400)
    const fields = body<{ problems: Problem[] }>(res).problems.map((p) => p.field)
    expect(fields).toEqual(expect.arrayContaining(['/name', '/widgets/0/width', '/widgets/1/widgetId', '/widgets/1/metric', '/widgets/2/kind', '/widgets/3/tag/key', '/widgets/3/tag/value']))
  })

  it('shares with a group so its members see it, only by the owner, and keeps a view share read-only', () => {
    const w = world()
    const dana = w.as('dana')
    const d = body<Dashboard>(dana.call('POST', '/console/v1/dashboards', { name: 'On call', widgets: [cpu, drift] }))
    const url = `/console/v1/dashboards/${d.dashboardId}`
    expect(w.as('mateo').get(url).status).toBe(404)
    expect(dana.call('PUT', `${url}/sharing`, { account: 'none', groups: [{ group: 'nobody', access: 'view' }] }, { 'if-match': '"1"' }).status).toBe(400)
    const shared = dana.call('PUT', `${url}/sharing`, { account: 'none', groups: [{ group: 'members', access: 'view' }] }, { 'if-match': '"1"' })
    expect(shared.status).toBe(200)
    expect(body<Dashboard>(shared).sharing.groups).toEqual([{ group: 'members', title: 'team-compute', access: 'view' }])

    const mateo = w.as('mateo')
    const seen = body<Dashboard>(mateo.get(url))
    expect(seen).toMatchObject({ access: 'view', owner: { name: 'Dana Reyes' } })
    expect(seen.can.edit).toMatchObject({ allowed: false })
    expect(seen.can.edit.why).toMatch(/^Dana Reyes shared it with you to view/)
    expect(seen.can.share.why).toBe('Only its owner, Dana Reyes, chooses who sees it.')
    expect(mateo.call('PUT', url, { name: 'Mine', widgets: [] }, { 'if-match': '"2"' }).status).toBe(403)
    // Mia is a viewer and not in members: she does not reach it
    expect(w.as('mia').get(url).status).toBe(404)
  })

  it('lets the owner delete their own, an operator not someone else’s, and an admin any', () => {
    const w = world()
    const dana = w.as('dana')
    const fleet = body<Dashboard>(dana.get('/console/v1/dashboards/dash-compute-fleet'))
    expect(fleet.can.delete).toMatchObject({ allowed: false, denied: { action: 'dashboards:delete' } })
    expect(dana.call('DELETE', '/console/v1/dashboards/dash-compute-fleet', undefined, { 'if-match': `"${fleet.version}"` }).status).toBe(403)
    const own = body<Dashboard>(dana.call('POST', '/console/v1/dashboards', { name: 'Scratch', widgets: [] }))
    expect(own.can.delete.allowed).toBe(true)
    expect(dana.call('DELETE', `/console/v1/dashboards/${own.dashboardId}`, undefined, { 'if-match': '"1"' }).status).toBe(204)
    const gonzalo = w.as('gonzalo')
    expect(gonzalo.call('DELETE', '/console/v1/dashboards/dash-compute-fleet', undefined, { 'if-match': `"${fleet.version}"` }).status).toBe(204)
  })

  it('replays its writes from the journal', () => {
    const w = world()
    const dana = w.as('dana')
    const d = body<Dashboard>(dana.call('POST', '/console/v1/dashboards', { name: 'Kept', widgets: [cpu] }))
    dana.call('PUT', `/console/v1/dashboards/${d.dashboardId}/sharing`, { account: 'view', groups: [] }, { 'if-match': '"1"' })
    const saved: Record<string, string> = {}
    saveConsole(w.data, { setItem: (k, v) => (saved[k] = v) })
    const fresh = buildMockData()
    restoreConsole(fresh, { getItem: (k) => saved[k] ?? null }, T0 + 5000)
    const server = createMockServer(fresh, memorySessionStore(), () => T0 + 5000)
    server.handle({ method: 'GET', url: '/auth/login?login_hint=mia', headers: {} })
    const res = server.handle({ method: 'GET', url: `/console/v1/dashboards/${d.dashboardId}`, headers: {} })
    expect(res.status).toBe(200)
    expect(body<Dashboard>(res)).toMatchObject({ name: 'Kept', version: 2, access: 'view' })
  })
})

describe('service health', () => {
  it('answers every service with its objectives over 24 hours and 7 days, Search degraded with its incident', () => {
    const res = world().as('mia').get('/console/v1/health')
    expect(res.status).toBe(200)
    const h = body<ServiceHealth>(res)
    expect(h.services.map((s) => s.serviceId)).toEqual(['platform', 'console-bff', 'identity', 'state', 'drift', 'search', 'cost'])
    expect(h.services.filter((s) => s.status !== 'operational').map((s) => s.serviceId)).toEqual(['search'])
    const search = h.services.find((s) => s.serviceId === 'search')!
    const latency = search.slos.find((s) => s.kind === 'latency')!
    expect(latency.windows['24h'].met).toBe(false)
    expect(latency.windows['7d'].met).toBe(true)
    expect(search.days).toHaveLength(7)
    for (const s of h.services.filter((x) => x.serviceId !== 'search')) for (const slo of s.slos) expect(slo.windows['24h'].met).toBe(true)
    expect(h.incidents.map((i) => [i.serviceId, i.status])).toEqual([
      ['search', 'identified'],
      ['state', 'resolved'],
    ])
  })

  it('is the same on two reads of the same minute, and needs a session', () => {
    const w = world()
    const a = w.as('dana').get('/console/v1/health')
    const b = w.as('dana').get('/console/v1/health')
    expect(a.body).toEqual(b.body)
    const server = createMockServer(buildMockData(), memorySessionStore(), () => T0)
    expect(server.handle({ method: 'GET', url: '/console/v1/health', headers: {} }).status).toBe(401)
  })
})
