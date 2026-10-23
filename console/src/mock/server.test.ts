import { describe, expect, it } from 'vitest'
import { createMockServer, etagOf, parseCursor, stepsOf, type MockRequest } from './server'
import { buildMockData, nextMockEvent } from './data'
import type { JobDetail, JobSummary, Lineage, Me, PlatformEvent, Resource, ResourceType } from '../lib/types'

const server = createMockServer()
const get = (url: string, headers: Record<string, string> = {}) => server.handle({ method: 'GET', url, headers })
const body = <T>(res: ReturnType<typeof server.handle>) => res.body as T

describe('the dataset', () => {
  it('serves the captured samples plus the vm hints, wfrns and generated vms', () => {
    const data = buildMockData()
    expect(data.resourceTypes.map((t) => t.name)).toEqual(['database', 'environment', 'image', 'vm', 'volume'])
    const vm = data.resourceTypes.find((t) => t.name === 'vm')
    expect(vm?.console?.title).toBe('Virtual machines')
    expect(data.resourceTypes.filter((t) => t.name !== 'vm').every((t) => !t.console && !t.service)).toBe(true)
    const vms = data.resources.filter((r) => r.type === 'vm')
    expect(vms.length).toBeGreaterThan(200)
    expect(vms.every((r) => r.wfrn?.startsWith('wfrn:wf:compute:global:123456789012:vm/') && r.accountId === '123456789012')).toBe(true)
    expect(data.resources.filter((r) => r.type === 'database').every((r) => !r.wfrn && r.accountId === '210987654321')).toBe(true)
    expect(data.jobs.length).toBe(50)
    expect(data.users.gonzalo).toMatchObject({ id: 'gonzalo', name: 'Gonzalo Munoz' })
    expect(data.events.length).toBeGreaterThan(0)
  })

  it('generates the next event with a fresh sequence number', () => {
    const data = buildMockData()
    const last = data.events[data.events.length - 1].seq
    const next = nextMockEvent(data)
    expect(next.seq).toBe(last + 1)
    expect(next.id).toBe(String(last + 1))
    expect(data.events[data.events.length - 1]).toBe(next)
  })
})

describe('identity', () => {
  it('needs the X-User header and knows the dev identities', () => {
    expect(get('/api/v1/me').status).toBe(401)
    expect(get('/api/v1/me', { 'x-user': 'nobody' }).status).toBe(401)
    const res = get('/api/v1/me', { 'x-user': 'ana' })
    expect(res.status).toBe(200)
    expect(body<Me>(res)).toMatchObject({ id: 'ana', roles: ['viewer'] })
  })
})

describe('descriptors', () => {
  it('carry an ETag and answer 304 to a matching If-None-Match', () => {
    const first = get('/api/v1/resource-types')
    expect(first.status).toBe(200)
    const etag = first.headers?.ETag
    expect(etag).toMatch(/^W\/"resource-types-/)
    expect(body<ResourceType[]>(first)).toHaveLength(5)
    const again = get('/api/v1/resource-types', { 'if-none-match': etag ?? '' })
    expect(again.status).toBe(304)
    expect(again.body).toBeUndefined()
    expect(get('/api/v1/catalog').headers?.ETag).toMatch(/^W\/"catalog-/)
    expect(etagOf('x', { a: 1 })).toBe(etagOf('x', { a: 1 }))
    expect(etagOf('x', { a: 1 })).not.toBe(etagOf('x', { a: 2 }))
  })

  it('serves one global region, as a file with metadata and spec', () => {
    expect(body<{ metadata: { name: string } }[]>(get('/api/v1/regions'))).toEqual([{ metadata: { name: 'global', title: 'Global' }, spec: { partition: 'wf', status: 'available', environments: ['dev', 'test', 'prod'] } }])
    expect(get('/api/v1/regions/global').status).toBe(200)
    expect(get('/api/v1/regions/mars').status).toBe(404)
  })
})

describe('resources', () => {
  it('pages a collection with limit and after, the next cursor in the Link header', () => {
    const first = get('/api/v1/vms?limit=50')
    expect(first.status).toBe(200)
    const rows = body<Resource[]>(first)
    expect(rows).toHaveLength(50)
    expect(first.headers?.Link).toBe('</api/v1/vms?limit=50&after=c50>; rel="next"')
    expect(first.headers?.['X-Total-Count']).toBe(String(server.data.resources.filter((r) => r.type === 'vm').length))
    const second = get('/api/v1/vms?limit=50&after=c50')
    expect(body<Resource[]>(second)[0].id).not.toBe(rows[0].id)
    expect(parseCursor('c50')).toBe(50)
    expect(parseCursor('nope')).toBe(0)
    const last = get('/api/v1/vms?limit=500')
    expect(last.headers?.Link).toBeUndefined()
  })

  it('filters by status, owner and free text', () => {
    expect(body<Resource[]>(get('/api/v1/vms?status=building&limit=500')).every((r) => r.status === 'building')).toBe(true)
    expect(body<Resource[]>(get('/api/v1/resources?type=database&limit=500')).map((r) => r.id)).toEqual(['database-demo01', 'database-demo02'])
    expect(body<Resource[]>(get('/api/v1/resources?q=orders-db')).map((r) => r.id)).toEqual(['database-demo01'])
    expect(body<Resource[]>(get('/api/v1/resources?owner=team-platform&limit=500')).every((r) => r.owner === 'team-platform')).toBe(true)
  })

  it('serves one resource by its collection or the registry, and 404 otherwise', () => {
    expect(body<Resource>(get('/api/v1/vms/vm-demo01'))).toMatchObject({ id: 'vm-demo01', type: 'vm' })
    expect(body<Resource>(get('/api/v1/resources/database-demo01'))).toMatchObject({ id: 'database-demo01' })
    expect(get('/api/v1/vms/database-demo01').status).toBe(404)
    expect(get('/api/v1/unicorns').status).toBe(404)
    expect(get('/api/v1/resources/nope').status).toBe(404)
  })

  it('resolves a lineage from builtFrom in both directions', () => {
    const lineage = body<Lineage>(get('/api/v1/resources/image-demo01/lineage'))
    expect(lineage.resource.id).toBe('image-demo01')
    expect(lineage.builtFrom).toEqual([])
    expect(lineage.dependents.length).toBeGreaterThan(5)
    const env = body<Lineage>(get('/api/v1/resources/environment-demo01/lineage'))
    expect(env.builtFrom.map((r) => r.id)).toEqual(['database-demo01', 'database-demo02', 'vm-demo01', 'vm-demo02'])
    expect(get('/api/v1/resources/nope/lineage').status).toBe(404)
  })
})

describe('jobs', () => {
  it('lists newest first with filters, and details one with steps', () => {
    const jobs = body<JobSummary[]>(get('/api/v1/jobs?limit=10'))
    expect(jobs).toHaveLength(10)
    expect(jobs[0].startedAt >= jobs[9].startedAt).toBe(true)
    expect(body<JobSummary[]>(get('/api/v1/jobs?status=failed&limit=500')).every((j) => j.status === 'failed')).toBe(true)
    const failed = server.data.jobs.find((j) => j.status === 'failed')
    const detail = body<JobDetail>(get(`/api/v1/jobs/${failed?.id}`))
    expect(detail.steps?.some((s) => s.status === 'failed')).toBe(true)
    expect(detail.error?.class).toBe(failed?.errorClass)
    expect(get('/api/v1/jobs/job_nope').status).toBe(404)
  })

  it('draws the steps from the status', () => {
    const base: JobSummary = { id: 'j', workflow: 'w', title: 't', version: '1', status: 'succeeded', requester: 'ana', channel: 'rest', startedAt: '2026-10-01T00:00:00Z', kind: 'single' }
    expect(stepsOf(base).map((s) => s.status)).toEqual(['succeeded', 'succeeded', 'succeeded', 'succeeded'])
    expect(stepsOf({ ...base, status: 'waiting-approval' }).map((s) => s.status)).toEqual(['succeeded', 'waiting', 'pending', 'pending'])
    expect(stepsOf({ ...base, status: 'failed', failedStep: 'createVm', errorClass: 'not_found' }).map((s) => [s.id, s.status])).toEqual([
      ['validate', 'succeeded'],
      ['approve', 'succeeded'],
      ['createVm', 'failed'],
      ['register', 'skipped'],
    ])
    expect(stepsOf({ ...base, kind: 'batch', status: 'running', progress: { done: 1, total: 6, failed: 0 } }).map((s) => s.id)).toEqual(['validate', 'approve', 'rollout', 'apply', 'register'])
  })
})

describe('events and the rest', () => {
  it('serves the events after a sequence number', () => {
    const all = body<{ items: PlatformEvent[]; next: string | null }>(get('/api/v1/events'))
    expect(all.items.length).toBe(server.data.events.length)
    const after = body<{ items: PlatformEvent[]; next: string | null }>(get('/api/v1/events?after=3&limit=2'))
    expect(after.items.map((e) => e.seq)).toEqual([4, 5])
    expect(after.next).toBe('5')
  })

  it('reports health, refuses writes and 404s the unknown', () => {
    expect(body<{ status: string }>(get('/actuator/health')).status).toBe('UP')
    expect(server.handle({ method: 'POST', url: '/api/v1/vms', headers: {} } as MockRequest).status).toBe(405)
    expect(get('/api/v2/anything').status).toBe(404)
  })
})
