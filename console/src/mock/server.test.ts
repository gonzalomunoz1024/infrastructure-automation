import { describe, expect, it } from 'vitest'
import { createMockServer, etagOf, parseCursor, stepsOf, type MockRequest } from './server'
import { buildMockData, nextMockEvent } from './data'
import type { ConsoleResource, JobDetail, JobSummary, Lineage, Me, PlatformEvent, Preferences, Resource, ResourceCounts, ResourceType, SearchHit, Suggestion } from '../lib/types'
import { countsOf } from './resources'

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
  it('needs the X-User header and answers the platform view of each person: their first account', () => {
    expect(get('/api/v1/me').status).toBe(401)
    expect(get('/api/v1/me', { 'x-user': 'nobody' }).status).toBe(401)
    const res = get('/api/v1/me', { 'x-user': 'ana' })
    expect(res.status).toBe(200)
    expect(body<Me>(res)).toMatchObject({ id: 'ana', roles: ['operator', 'viewer'], teams: ['members', 'viewers'] })
    expect(body<Me>(get('/api/v1/me', { 'x-user': 'mia' }))).toMatchObject({ id: 'mia', roles: ['viewer', 'approver'] })
    expect(body<Me>(get('/api/v1/me', { 'x-user': 'gonzalo' }))).toMatchObject({ id: 'gonzalo', roles: ['account-admin'], admin: true })
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

/* ---------------------------------------------------------------- the BFF */

import { csrfTokenOf, memorySessionStore } from './server'
import type { AccessKey, AccessKeyCreated, AccessRequest, BffConsoleType, IamGroup, IamPolicy, IamUser, Quota, QuotaRequest } from '../lib/types'
import type { Session } from '../lib/session'

function bff() {
  const sessions = memorySessionStore()
  const srv = createMockServer(buildMockData(), sessions, () => Date.parse('2026-10-23T10:00:00Z'))
  let who = ''
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => srv.handle({ method, url, headers: { ...(who && method !== 'GET' ? { 'x-csrf-token': csrfTokenOf(who) } : {}), ...headers }, body })
  const signIn = (person: string, returnTo = '/#/') => {
    const res = srv.handle({ method: 'GET', url: `/auth/login?returnTo=${encodeURIComponent(returnTo)}&login_hint=${person}`, headers: {} })
    expect(res.status).toBe(200)
    who = person
    return res
  }
  const session = () => body<Session>(call('GET', '/console/v1/session'))
  const vmType = () => body<{ items: BffConsoleType[] }>(call('GET', '/console/v1/types')).items.find((t) => t.key === 'compute/vm')!
  const verb = (name: string) => vmType().actions.find((a) => a.verb === name)!
  return { srv, sessions, call, signIn, session, vmType, verb }
}

describe('the BFF: session and sign-in', () => {
  it('answers 401 without a session, signs a known person in with login_hint and keeps the account', () => {
    const b = bff()
    expect(b.call('GET', '/console/v1/session')).toMatchObject({ status: 401, body: { error: 'Not signed in' } })
    expect(b.call('GET', '/console/v1/types').status).toBe(401)
    expect(b.srv.handle({ method: 'GET', url: '/auth/login?returnTo=%2F', headers: {} })).toMatchObject({ status: 400, body: { error: 'login_hint.required' } })
    expect(b.srv.handle({ method: 'GET', url: '/auth/login?login_hint=nobody', headers: {} }).status).toBe(400)
    expect(b.signIn('mia', '/#/requests').body).toEqual({ returnTo: '/#/requests' })
    const s = b.session()
    expect(s).toMatchObject({ user: { userId: 'mia', email: 'mia@example.com' }, account: { accountId: '123456789012', roles: ['viewer', 'approver'], groups: ['viewers', 'compute-approvers'] }, region: 'global', csrfToken: 'mock-csrf-mia' })
    expect(Date.parse(s.expiresAt) - Date.parse(s.authTime ?? '')).toBe(8 * 3_600_000)
    expect(body<{ items: unknown[] }>(b.call('GET', '/console/v1/accounts')).items).toHaveLength(1)
    expect(body<{ items: { name: string }[] }>(b.call('GET', '/console/v1/regions')).items.map((r) => r.name)).toEqual(['global'])
    expect(body<{ items: { id: string }[] }>(b.srv.handle({ method: 'GET', url: '/demo-idp/people', headers: {} })).items.map((p) => p.id)).toEqual(['gonzalo', 'dana', 'mateo', 'raj', 'ana', 'mia', 'noah'])
    // noah is known to the identity provider but belongs to no account
    expect(b.srv.handle({ method: 'GET', url: '/auth/login?login_hint=noah', headers: {} })).toMatchObject({ status: 403, body: { error: 'Noah Berg belongs to no account' } })
  })

  it('needs the CSRF token on writes, switches account for a member only, and signs out', () => {
    const b = bff()
    b.signIn('gonzalo')
    expect(b.srv.handle({ method: 'PUT', url: '/console/v1/session', headers: {}, body: { accountId: '210987654321' } })).toMatchObject({ status: 403, body: { error: 'csrf.missing' } })
    expect(b.srv.handle({ method: 'PUT', url: '/console/v1/session', headers: { 'x-csrf-token': 'wrong' }, body: {} }).status).toBe(403)
    expect(b.call('PUT', '/console/v1/session', { region: 'mars' })).toMatchObject({ status: 400 })
    const switched = body<Session>(b.call('PUT', '/console/v1/session', { accountId: '210987654321' }))
    expect(switched.account).toMatchObject({ accountId: '210987654321', roles: ['account-admin'] })
    expect(b.session().account.accountId).toBe('210987654321')
    // signing in again as the same person keeps the account; another person starts in theirs
    b.signIn('gonzalo')
    expect(b.session().account.accountId).toBe('210987654321')
    b.signIn('mia')
    expect(b.call('PUT', '/console/v1/session', { accountId: '210987654321' }).status).toBe(404)
    expect(b.call('POST', '/auth/logout').status).toBe(204)
    expect(b.call('GET', '/console/v1/session').status).toBe(401)
  })

  it('expires the session after eight hours', () => {
    const sessions = memorySessionStore()
    let now = Date.parse('2026-10-23T10:00:00Z')
    const srv = createMockServer(buildMockData(), sessions, () => now)
    expect(srv.handle({ method: 'GET', url: '/auth/login?login_hint=dana', headers: {} }).status).toBe(200)
    expect(srv.handle({ method: 'GET', url: '/console/v1/session', headers: {} }).status).toBe(200)
    now += 8 * 3_600_000 + 1
    expect(srv.handle({ method: 'GET', url: '/console/v1/session', headers: {} }).status).toBe(401)
    expect(sessions.get()).toBeNull()
  })
})

describe('the BFF: types with decisions', () => {
  it('decides every verb for the person, the account and the region, with the layer and the role', () => {
    const b = bff()
    b.signIn('mia')
    const type = b.vmType()
    expect(type).toMatchObject({ type: 'vm', service: 'compute', plural: 'vms' })
    expect(type.actions.map((a) => a.verb)).toEqual(expect.arrayContaining(['list', 'get', 'create', 'update', 'delete']))
    expect(b.verb('list').allowed).toBe(true)
    const create = b.verb('create')
    expect(create.allowed).toBe(false)
    expect(create.denied).toMatchObject({ layer: 'console-iam', action: 'compute:create', resource: 'wfrn:wf:compute:global:123456789012:vm/*', role: 'viewer', requestable: true, suggestedRole: 'operator' })

    b.signIn('dana')
    expect(b.verb('create').allowed).toBe(true)
    // the operator's allow carves deletes out: nothing allows it, which names the role and no statement
    expect(b.verb('delete').denied).toMatchObject({ layer: 'console-iam', reason: 'no policy allows compute:delete on wfrn:wf:compute:global:123456789012:vm/*', role: 'operator', requestable: true, suggestedRole: 'account-admin' })
    expect(b.verb('delete').denied?.statement).toBeUndefined()

    b.signIn('gonzalo')
    expect(b.verb('delete').allowed).toBe(true)

    b.signIn('raj')
    expect(b.session().account.accountId).toBe('210987654321')
    expect(b.verb('list').allowed).toBe(true)
    expect(b.verb('create').denied).toMatchObject({ layer: 'platform', scope: 'compute:write', requestable: true })
  })

  it('carries an ETag per person and account and answers 304', () => {
    const b = bff()
    b.signIn('mia')
    const first = b.call('GET', '/console/v1/types')
    const etag = first.headers?.ETag
    expect(etag).toMatch(/^W\//)
    expect(b.call('GET', '/console/v1/types', undefined, { 'if-none-match': etag ?? '' }).status).toBe(304)
    b.signIn('gonzalo')
    expect(b.call('GET', '/console/v1/types', undefined, { 'if-none-match': etag ?? '' }).status).toBe(200)
    expect(b.call('GET', '/console/v1/types/compute%2Fvm').status).toBe(200)
    expect(b.call('GET', '/console/v1/types/nope').status).toBe(404)
  })
})

describe('the BFF: identity and access', () => {
  it('lets every managed role read, refuses writes with the Denial, and lets admins write', () => {
    const b = bff()
    b.signIn('mia')
    expect(body<{ items: IamUser[] }>(b.call('GET', '/console/v1/iam/users')).items.map((u) => u.userId)).toContain('gonzalo')
    expect(body<{ items: IamUser[] }>(b.call('GET', '/console/v1/iam/users?q=gonz')).items.map((u) => u.userId)).toEqual(['gonzalo'])
    expect(body<{ items: IamGroup[] }>(b.call('GET', '/console/v1/iam/groups')).items.length).toBeGreaterThan(2)
    expect(body<{ items: { roleId: string }[] }>(b.call('GET', '/console/v1/iam/roles')).items.map((r) => r.roleId)).toEqual(['viewer', 'operator', 'account-admin', 'auditor', 'approver'])
    expect(b.call('GET', '/console/v1/iam/roles/operator').status).toBe(200)
    const operator = body<IamPolicy>(b.call('GET', '/console/v1/iam/policies/managed-operator'))
    expect(operator.statements[0]).toMatchObject({ sid: 'changes', effect: 'allow', actions: ['*:*'], notActions: expect.arrayContaining(['*:delete', 'iam:*']) as string[] })
    expect(body<{ items: IamPolicy[] }>(b.call('GET', '/console/v1/iam/policies')).items.map((x) => x.policyId)).toEqual(['managed-viewer', 'managed-operator', 'managed-account-admin', 'managed-auditor', 'approver'])
    const refused = b.call('POST', '/console/v1/iam/groups', { name: 'team-x', roles: ['viewer'] })
    expect(refused.status).toBe(403)
    expect(refused.body).toMatchObject({ denied: { layer: 'console-iam', action: 'iam:create', resource: 'wfrn:wf:iam::123456789012:group/*', role: 'viewer', suggestedRole: 'account-admin' }, correlationId: expect.stringMatching(/^mock-/) as string })

    b.signIn('gonzalo')
    const created = b.call('POST', '/console/v1/iam/groups', { name: 'team-x', roles: ['viewer'] })
    expect(created.status).toBe(201)
    const group = body<IamGroup>(created)
    expect(b.call('PATCH', `/console/v1/iam/groups/${group.groupId}`, { members: ['mia'] }, { 'if-match': '99' }).status).toBe(409)
    expect(body<IamGroup>(b.call('PATCH', `/console/v1/iam/groups/${group.groupId}`, { members: ['mia'] }, { 'if-match': String(group.version) })).members).toEqual(['mia'])
    expect(b.call('GET', '/console/v1/iam/users/mia').headers?.ETag).toBeDefined()
    expect(b.call('POST', '/console/v1/iam/users', { email: 'new.person@example.com', groups: [group.groupId] }).status).toBe(201)
    expect(b.call('DELETE', `/console/v1/iam/groups/${group.groupId}`).status).toBe(204)
    expect(b.call('GET', `/console/v1/iam/groups/${group.groupId}`).status).toBe(404)
    expect(b.call('POST', '/console/v1/iam/users', { name: 'no email' }).status).toBe(400)
    const owners = body<{ items: IamGroup[] }>(b.call('GET', '/console/v1/iam/groups')).items.find((g) => g.name === 'owners')!
    expect(b.call('DELETE', `/console/v1/iam/groups/${owners.groupId}`)).toMatchObject({ status: 409, body: { error: 'the owners group cannot be deleted' } })
    expect(b.call('DELETE', '/console/v1/iam/users/gonzalo')).toMatchObject({ status: 409, body: { error: 'the owners group keeps at least one active member' } })
  })

  it('lets a person manage their own access keys, two at most, and reads everyone’s with iam:list', () => {
    const b = bff()
    b.signIn('mia')
    const created = b.call('POST', '/console/v1/iam/access-keys', { description: 'laptop' })
    expect(created.status).toBe(201)
    const key = body<AccessKeyCreated>(created)
    expect(key.secretPart).toMatch(/^cs_/)
    expect(body<{ items: AccessKey[] }>(b.call('GET', '/console/v1/iam/access-keys?userId=mia')).items.map((k) => k.keyId)).toEqual([key.keyId])
    // a viewer's reads include iam:list, so the account's keys (never their secrets)
    expect(new Set(body<{ items: AccessKey[] }>(b.call('GET', '/console/v1/iam/access-keys')).items.map((k) => k.userId)).size).toBeGreaterThan(1)
    expect(b.call('POST', '/console/v1/iam/access-keys', { userId: 'dana' }).status).toBe(403)
    b.call('POST', '/console/v1/iam/access-keys', { description: 'second' })
    expect(b.call('POST', '/console/v1/iam/access-keys', { description: 'third' })).toMatchObject({ status: 409, body: { error: 'a user has at most 2 active access keys' } })
    const rotated = body<AccessKeyCreated>(b.call('POST', `/console/v1/iam/access-keys/${key.keyId}:rotate`))
    expect(rotated.secretPart).not.toBe(key.secretPart)
    expect(b.call('DELETE', `/console/v1/iam/access-keys/${key.keyId}`).status).toBe(204)
    expect(body<AccessKey>(b.call('GET', `/console/v1/iam/access-keys/${key.keyId}`)).status).toBe('revoked')
    expect(b.call('POST', `/console/v1/iam/access-keys/${key.keyId}:rotate`)).toMatchObject({ status: 409, body: { error: 'the key is revoked' } })

    b.signIn('gonzalo')
    const all = body<{ items: AccessKey[] }>(b.call('GET', '/console/v1/iam/access-keys')).items
    expect(new Set(all.map((k) => k.userId)).size).toBeGreaterThan(1)
    expect(body<{ items: AccessKey[] }>(b.call('GET', '/console/v1/iam/access-keys?userId=mia')).items.every((k) => k.userId === 'mia')).toBe(true)
    expect(b.call('POST', '/console/v1/iam/access-keys', { userId: 'dana', description: 'for dana' }).status).toBe(201)
  })

  it('serves the quotas and takes a request for more, which the requester or an admin withdraws', () => {
    const b = bff()
    b.signIn('dana')
    const quotas = body<{ items: Quota[] }>(b.call('GET', '/console/v1/iam/quotas')).items
    // one resources quota per platform resource type, by its name, as the identity service lists them
    expect(quotas.map((q) => q.key)).toEqual(['jobs-per-minute', 'access-keys', 'resources.database', 'resources.environment', 'resources.image', 'resources.vm', 'resources.volume'])
    expect(quotas.find((q) => q.key === 'access-keys')).toMatchObject({ limit: 50, used: 2, source: 'default' })
    const vms = quotas.find((q) => q.key === 'resources.vm')!
    expect(vms.used).toBeGreaterThan(100)
    expect(vms).toMatchObject({ source: 'override' })
    expect(vms.limit).toBeGreaterThan(vms.used)
    // the count leaves out what the state service keeps only as a tombstone
    const live = b.srv.data.resources.filter((r) => r.type === 'vm' && (!r.accountId || r.accountId === '123456789012') && r.status !== 'retired')
    expect(vms.used).toBe(live.length)
    // Data's databases are not Compute's
    expect(quotas.find((q) => q.key === 'resources.database')).toMatchObject({ used: 0, limit: 100, source: 'default' })
    expect(b.call('GET', '/console/v1/iam/quotas/nope').status).toBe(404)

    expect(b.call('POST', '/console/v1/iam/quotas/access-keys:request-increase', { limit: 80, reason: 'more' })).toMatchObject({ status: 400, body: { error: 'reason is 10 to 1000 characters' } })
    const asked = b.call('POST', '/console/v1/iam/quotas/access-keys:request-increase', { limit: 80, reason: 'the build farm grows next week' })
    expect(asked.status).toBe(202)
    const request = body<QuotaRequest>(asked)
    expect(request).toMatchObject({ quotaKey: 'access-keys', currentLimit: 50, requestedLimit: 80, status: 'open', requestedBy: 'dana' })
    expect(body<Quota>(b.call('GET', '/console/v1/iam/quotas/access-keys')).pendingRequestId).toBe(request.requestId)
    expect(b.call('POST', '/console/v1/iam/quotas/access-keys:request-increase', { limit: 90, reason: 'the build farm grows next week' })).toMatchObject({ status: 409, body: { error: 'an increase of access-keys is already requested', detail: { requestId: request.requestId } } })
    expect(b.call('GET', `/console/v1/iam/quota-requests/${request.requestId}`).status).toBe(200)

    // someone else needs quota:set, which only the admins hold
    b.signIn('mateo')
    expect(b.call('DELETE', `/console/v1/iam/quota-requests/${request.requestId}`)).toMatchObject({ status: 403, body: { denied: { action: 'quota:set', role: 'operator' } } })
    b.signIn('dana')
    expect(b.call('DELETE', `/console/v1/iam/quota-requests/${request.requestId}`).status).toBe(204)
    expect(body<QuotaRequest>(b.call('GET', `/console/v1/iam/quota-requests/${request.requestId}`)).status).toBe('withdrawn')
    expect(b.call('DELETE', `/console/v1/iam/quota-requests/${request.requestId}`)).toMatchObject({ status: 409, body: { error: 'the request is withdrawn' } })
    expect(body<Quota>(b.call('GET', '/console/v1/iam/quotas/access-keys')).pendingRequestId).toBeUndefined()
  })
})

describe('the BFF: access requests', () => {
  it('goes from a refusal to an approval by an approver that unlocks the action', () => {
    const b = bff()
    b.signIn('mia')
    expect(b.verb('create').allowed).toBe(false)
    expect(b.call('POST', '/console/v1/access-requests', { action: 'compute:create', reason: ' ' })).toMatchObject({ status: 400, body: { error: 'reason is 1 to 2000 characters' } })
    const sent = b.call('POST', '/console/v1/access-requests', { action: 'compute:create', resource: 'wfrn:wf:compute:global:123456789012:vm/*', reason: 'I build the team machines', layer: 'console-iam' })
    expect(sent.status).toBe(202)
    const request = body<AccessRequest>(sent)
    expect(request).toMatchObject({ requester: { userId: 'mia', name: 'Mia Chen', email: 'mia@example.com' }, role: 'operator', status: 'pending', approvers: ['dana', 'gonzalo'] })
    // the same role again answers 409 with the pending request as the body
    expect(b.call('POST', '/console/v1/access-requests', { action: 'compute:update', reason: 'and changing them' })).toMatchObject({ status: 409, body: { requestId: request.requestId, status: 'pending' } })
    expect(b.call('GET', `/console/v1/access-requests/${request.requestId}`).status).toBe(200)
    // an approver never decides their own, approve or reject: the identity service's denial
    expect(b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'approve' })).toMatchObject({ status: 403, body: { denied: { action: 'access-request:decide', reason: 'nobody decides their own access request', requestable: false } } })
    expect(b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'reject' }).status).toBe(403)
    // an approver reads the account's queue: asking for someone else's requests answers 200
    expect(b.call('GET', '/console/v1/access-requests?requester=~someone-else&limit=1')).toMatchObject({ status: 200, body: { items: [] } })

    // mateo reads only the requests mateo made, and may not ask for someone else's
    b.signIn('mateo')
    expect(body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests?status=pending')).items).toEqual([])
    expect(body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests')).items.map((r) => [r.requestId, r.status])).toEqual([['areq_002', 'expired']])
    expect(b.call('GET', '/console/v1/access-requests?requester=mia')).toMatchObject({ status: 403, body: { denied: { action: 'access-request:list', role: 'operator' } } })
    expect(b.call('GET', '/console/v1/access-requests?requester=~someone-else&limit=1').status).toBe(403)
    expect(b.call('GET', `/console/v1/access-requests/${request.requestId}`)).toMatchObject({ status: 403, body: { denied: { action: 'access-request:get' } } })
    expect(b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'approve' })).toMatchObject({ status: 403, body: { denied: { action: 'access-request:decide', role: 'operator', suggestedRole: 'account-admin' } } })

    // dana is an approver, so reads the queue and decides
    b.signIn('dana')
    expect(b.call('GET', '/console/v1/access-requests?status=maybe')).toMatchObject({ status: 400, body: { error: 'status is pending, approved, rejected or expired' } })
    const pending = body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests?status=pending')).items
    expect(pending.map((r) => r.requestId)).toEqual([request.requestId])
    expect(body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests?requester=mateo')).items.map((r) => r.requestId)).toEqual(['areq_002'])
    expect(b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'maybe' }).status).toBe(400)
    const decided = b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'approve', comment: 'Go ahead' })
    expect(decided.status).toBe(202)
    expect(body<AccessRequest>(decided)).toMatchObject({ status: 'approved', decidedBy: 'dana', comment: 'Go ahead' })
    expect(body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests?status=pending')).items).toEqual([])
    expect(b.call('POST', `/console/v1/access-requests/${request.requestId}:decide`, { decision: 'reject' })).toMatchObject({ status: 409, body: { error: 'the request is approved' } })
    expect(b.call('POST', '/console/v1/access-requests/areq_002:decide', { decision: 'approve' })).toMatchObject({ status: 409, body: { error: 'the request is expired' } })

    b.signIn('mia')
    expect(b.session().account.roles).toEqual(['operator', 'viewer', 'approver'])
    expect(b.verb('create').allowed).toBe(true)
  })

  it('refuses an auditor a request, since the auditor’s reads leave access-request:create out', () => {
    const b = bff()
    b.signIn('gonzalo')
    const auditors = body<{ items: IamGroup[] }>(b.call('GET', '/console/v1/iam/groups')).items.find((g) => g.name === 'auditors')!
    b.call('PATCH', `/console/v1/iam/groups/${auditors.groupId}`, { members: ['mateo'] })
    b.call('PATCH', '/console/v1/iam/users/mateo', { groups: ['auditors'] })
    b.signIn('mateo')
    expect(b.session().account.roles).toEqual(['auditor'])
    expect(b.call('POST', '/console/v1/access-requests', { action: 'compute:create', reason: 'on call' })).toMatchObject({ status: 403, body: { denied: { action: 'access-request:create', role: 'auditor' } } })
    // and reads the account's queue
    expect(body<{ items: AccessRequest[] }>(b.call('GET', '/console/v1/access-requests')).items.map((r) => r.requestId)).toEqual(['areq_001', 'areq_002'])
  })
})

describe('the BFF: resources, search, suggestions and preferences (phase 3)', () => {
  it('lists the account’s rows of a type with the state service’s shape, filters, sort and pages', () => {
    const b = bff()
    b.signIn('gonzalo')
    const first = b.call('GET', '/console/v1/r/vm?limit=5')
    expect(first.status).toBe(200)
    const pageOne = first.body as { items: ConsoleResource[]; next: string | null }
    expect(pageOne.items).toHaveLength(5)
    expect(pageOne.next).toBeTruthy()
    for (const r of pageOne.items) {
      expect(r.wfrn).toMatch(/^wfrn:wf:compute:global:123456789012:vm\//)
      expect(r.service).toBe('compute')
      expect(['pending', 'active', 'updating', 'deleting', 'deleted', 'failed']).toContain(r.state)
      expect(r.tags.owner).toBeTruthy()
      expect(typeof r.version).toBe('number')
    }
    // the plural works too, the sort reads `-created`, and a state narrows
    const byCreated = (b.call('GET', '/console/v1/r/vms?sort=-created&limit=200').body as { items: ConsoleResource[] }).items
    expect(byCreated.map((r) => r.createdAt)).toEqual([...byCreated.map((r) => r.createdAt)].sort().reverse())
    const active = (b.call('GET', '/console/v1/r/vm?state=active&limit=200').body as { items: ConsoleResource[] }).items
    expect(active.length).toBeGreaterThan(0)
    expect(active.every((r) => r.state === 'active')).toBe(true)
    const drifted = (b.call('GET', '/console/v1/r/vm?drift=true&limit=200').body as { items: ConsoleResource[] }).items
    expect(drifted.every((r) => r.drift)).toBe(true)
    const q = (b.call('GET', '/console/v1/r/vm?q=api-01&limit=200').body as { items: ConsoleResource[] }).items
    expect(q.length).toBeGreaterThan(0)
    expect(q.every((r) => (r.name ?? '').includes('api-01'))).toBe(true)
    const tagged = (b.call('GET', '/console/v1/r/vm?tag.owner=team-compute&limit=200').body as { items: ConsoleResource[] }).items
    expect(tagged.every((r) => r.tags.owner === 'team-compute')).toBe(true)
    expect(b.call('GET', '/console/v1/r/unicorn').status).toBe(404)
  })

  it('scopes the rows to the session’s account: the data team’s databases are not in Compute', () => {
    const b = bff()
    b.signIn('gonzalo')
    const inCompute = (b.call('GET', '/console/v1/r/database?limit=200').body as { items: ConsoleResource[] }).items
    expect(inCompute.every((r) => r.tags.owner !== 'team-data')).toBe(true)
    b.call('PUT', '/console/v1/session', { accountId: '210987654321' })
    const inData = (b.call('GET', '/console/v1/r/database?limit=200').body as { items: ConsoleResource[] }).items
    expect(inData.length).toBeGreaterThan(0)
    expect(inData.every((r) => r.wfrn.includes(':210987654321:'))).toBe(true)
  })

  it('searches names first and suggests types, names and tags', () => {
    const b = bff()
    b.signIn('gonzalo')
    const hits = (b.call('GET', '/console/v1/search?q=api-01').body as { items: SearchHit[] }).items
    expect(hits[0].name?.startsWith('api-01')).toBe(true)
    expect(hits[0].score).toBeGreaterThanOrEqual(hits[hits.length - 1].score)
    expect(b.call('GET', '/console/v1/search?q=').status).toBe(400)
    const scoped = (b.call('GET', '/console/v1/search?q=a&type=vm').body as { items: SearchHit[] }).items
    expect(scoped.every((h) => h.type === 'vm')).toBe(true)
    const suggestions = (b.call('GET', '/console/v1/suggest?q=vir').body as { items: Suggestion[] }).items
    expect(suggestions.some((s) => s.kind === 'type' && s.value === 'vm')).toBe(true)
    expect(suggestions.findIndex((s) => s.kind === 'type')).toBe(0)
    const owners = (b.call('GET', '/console/v1/suggest?q=team-comp&limit=3').body as { items: Suggestion[] }).items
    expect(owners.some((s) => s.kind === 'tag' && s.value === 'owner=team-compute')).toBe(true)
    expect(owners.length).toBeLessThanOrEqual(3)
  })

  it('keeps preferences per person with a version', () => {
    const b = bff()
    b.signIn('gonzalo')
    expect(b.call('GET', '/console/v1/preferences').body).toEqual({ version: 0 })
    const saved = b.call('PUT', '/console/v1/preferences', { columns: { 'compute/vm': ['name', 'status'] }, version: 0 })
    expect(saved.status).toBe(200)
    expect((saved.body as Preferences).version).toBe(1)
    expect(b.call('PUT', '/console/v1/preferences', { version: 0 }).status).toBe(409)
    expect(b.call('PUT', '/console/v1/preferences', { theme: 'light' }).status).toBe(200)
    expect((b.call('GET', '/console/v1/preferences').body as Preferences).version).toBe(2)
    b.signIn('mia')
    expect(b.call('GET', '/console/v1/preferences').body).toEqual({ version: 0 })
  })

  it('checks saved views the way the BFF does', () => {
    const b = bff()
    b.signIn('gonzalo')
    const view = (over: Record<string, unknown> = {}) => ({ id: 'v_1', name: 'Failed in prod', filters: [{ property: 'status', operator: '=', value: 'failed' }], sort: '-name', pageSize: 50, createdAt: '2026-10-23T12:00:00Z', ...over })
    const put = (views: unknown[]) => b.call('PUT', '/console/v1/preferences', { savedViews: { 'compute/vm': views } })
    expect(put([view()]).status).toBe(200)
    expect(put([view({ id: 'view-1' })]).status).toBe(400)
    expect(put([view({ name: '' })]).status).toBe(400)
    expect(put([view({ name: 'x'.repeat(81) })]).status).toBe(400)
    expect(put([view(), view({ id: 'v_2', name: 'FAILED IN PROD' })]).status).toBe(400)
    expect(put([view({ filters: [{ property: null, operator: ':', value: 'x'.repeat(1025) }] })]).status).toBe(400)
    expect(put([view({ createdAt: undefined })]).status).toBe(400)
    expect(put(Array.from({ length: 51 }, (_, i) => view({ id: `v_${i}`, name: `View ${i}` }))).status).toBe(400)
    expect(put(Array.from({ length: 50 }, (_, i) => view({ id: `v_${i}`, name: `View ${i}` }))).status).toBe(200)
    // the rest of the BFF's rules: operators, properties, sorts, page sizes, ids, type keys
    expect(put([view({ filters: [{ property: 'status', operator: '~', value: 'failed' }] })]).status).toBe(400)
    expect(put([view({ filters: [{ property: 'status label', operator: '=', value: 'failed' }] })]).status).toBe(400)
    expect(put([view({ filters: [{ property: 'tag:owner', operator: '=', value: 'team-compute' }, { property: null, operator: ':', value: 'web' }] })]).status).toBe(200)
    expect(put([view({ sort: 'tag:owner' })]).status).toBe(400)
    expect(put([view({ pageSize: 0 })]).status).toBe(400)
    expect(put([view({ pageSize: 201 })]).status).toBe(400)
    expect(put([view({ pageSize: 200 })]).status).toBe(200)
    expect(put([view(), view({ name: 'Another' })]).status).toBe(400)
    expect(b.call('PUT', '/console/v1/preferences', { savedViews: { 'Compute/VM': [view()] } }).status).toBe(400)
    expect(b.call('PUT', '/console/v1/preferences', { density: 'cosy' }).status).toBe(400)
    expect(b.call('PUT', '/console/v1/preferences', { defaultRegion: 'Global' }).status).toBe(400)
    expect(b.call('PUT', '/console/v1/preferences', { columns: { 'compute/vm': Array.from({ length: 41 }, (_, i) => `c${i}`) } }).status).toBe(400)
  })

  it('counts a type’s rows by lifecycle state with the list’s filters, up to the cap', () => {
    const b = bff()
    b.signIn('gonzalo')
    const all: ConsoleResource[] = []
    let after: string | null = ''
    while (after !== null) {
      const pageOf = b.call('GET', `/console/v1/r/vm?limit=200${after ? `&after=${after}` : ''}`).body as { items: ConsoleResource[]; next: string | null }
      all.push(...pageOf.items)
      after = pageOf.next
    }
    expect(all.length).toBeGreaterThan(200)
    // the list leaves tombstones out unless asked for them
    expect(all.some((r) => r.state === 'deleted')).toBe(false)
    const deleted = (b.call('GET', '/console/v1/r/vm?limit=200&state=deleted').body as { items: ConsoleResource[] }).items
    expect(deleted.length).toBeGreaterThan(0)
    const counts = b.call('GET', '/console/v1/r/vm:counts')
    expect(counts.status).toBe(200)
    const c = counts.body as ResourceCounts
    expect(c.total).toBe(all.length)
    expect(c.cappedAt).toBeNull()
    expect(c.byState.deleted).toBe(deleted.length)
    expect(Object.values(c.byState).reduce((n, x) => n + (x ?? 0), 0)).toBe(all.length + deleted.length)
    expect(c.byState.active).toBe(all.filter((r) => r.state === 'active').length)
    expect(c.drift).toBe(all.filter((r) => r.drift).length)
    // total and drift take every filter; the counts by state ignore the state filter
    const active = b.call('GET', '/console/v1/r/vm:counts?state=active').body as ResourceCounts
    expect(active.total).toBe(c.byState.active)
    expect(active.byState).toEqual(c.byState)
    expect((b.call('GET', '/console/v1/r/vm:counts?state=deleted').body as ResourceCounts).total).toBe(deleted.length)
    expect(b.call('GET', '/console/v1/r/nope:counts').status).toBe(404)
    // the cap: a count past it answers the cap, and cappedAt says so
    const rows = [...all, ...deleted]
    expect(countsOf(rows, new URLSearchParams(), 3)).toMatchObject({ total: 3, cappedAt: 3 })
  })
})
