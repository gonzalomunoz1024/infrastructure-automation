import { describe, expect, it } from 'vitest'
import type { CloneDraft, ResourceGroup, TagApplyResult, TagPolicy, TaggableResource } from '../lib/tags'
import type { CommandAccepted, ConsolePreview, ConsoleResource, Page, Problem } from '../lib/types'
import { buildMockData } from './data'
import { replayConsole } from './commands'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { copyName } from './tags'

/*
 * The BFF's tag endpoints in the mock (phase 4): the account's required tags and what they do to
 * a create, a tag change and a Tag Editor change; resource groups and the resources they match;
 * and the draft of a clone, in the same account and in another.
 */

const ACCOUNT = '123456789012'
const VM = `wfrn:wf:compute:global:${ACCOUNT}:vm/vm-demo01`
const VM2 = `wfrn:wf:compute:global:${ACCOUNT}:vm/vm-demo02`
const VOLUME = `wfrn:wf:platform:global:${ACCOUNT}:volume/volume-demo01`
const TAGS = { owner: 'team-compute', 'cost-centre': 'cc-1042', 'data-class': 'internal', environment: 'dev' }
const INPUT = { name: 'web-90', image: 'image-demo01', size: 'small', environment: 'dev' }

function bff(person: string, start = Date.parse('2026-10-23T10:00:00Z')) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body })
  return { server, data, call, get: (url: string) => call('GET', url), tick: (ms: number) => void (now += ms) }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const enc = encodeURIComponent

describe('the required tags', () => {
  it('answers the account’s tags with their defaults and where each comes from, and another account of the person’s', () => {
    const dana = bff('dana')
    const policy = body<TagPolicy>(dana.get('/console/v1/tag-policy'))
    expect(policy.accountId).toBe(ACCOUNT)
    expect(policy.required.map((t) => [t.key, t.default ?? null, t.defaultFrom ?? null])).toEqual([
      ['owner', 'team-compute', 'account'],
      ['cost-centre', 'cc-1042', 'account'],
      ['data-class', 'internal', 'organization'],
      ['environment', null, 'input.environment'],
    ])
    expect(policy.required[1].pattern).toBe('^cc-[0-9]{4}$')
    expect(policy.required[3].values).toEqual(['dev', 'test', 'prod'])
    const gonzalo = bff('gonzalo')
    expect(body<TagPolicy>(gonzalo.get('/console/v1/tag-policy?account=210987654321')).required.slice(0, 2).map((t) => t.default)).toEqual(['team-data', 'cc-2077'])
    expect(dana.get('/console/v1/tag-policy?account=210987654321').status, 'not an account of Dana’s').toBe(404)
  })

  it('refuses a create that leaves one out, says so in the review, and sets them on the minted resource', () => {
    const dana = bff('dana')
    const preview = body<ConsolePreview>(dana.call('POST', '/console/v1/r/vm:preview', { input: INPUT, tags: { owner: 'team-compute', 'cost-centre': 'x' } }))
    expect(preview.policy.decision).toBe('deny')
    expect(preview.policy.rules?.filter((r) => r.result === 'deny')).toEqual([
      { id: 'tags.required', result: 'deny', message: 'required tags are missing: data-class, environment' },
      { id: 'tags.invalid', result: 'deny', message: 'tags with values they may not take: cost-centre' },
    ])
    const refused = dana.call('POST', '/console/v1/r/vm', { input: INPUT })
    expect(refused.status).toBe(400)
    const problems = body<{ error: string; problems: Problem[] }>(refused)
    expect(problems.error).toBe('tags.required')
    expect(problems.problems.map((p) => p.field)).toEqual(['/tags/owner', '/tags/cost-centre', '/tags/data-class', '/tags/environment'])
    expect(problems.problems[0].message).toBe('Owner is required (this account uses team-compute)')
    expect(dana.data.resources.some((r) => r.name === 'web-90'), 'nothing is minted').toBe(false)

    const ok = dana.call('POST', '/console/v1/r/vm', { input: INPUT, tags: { ...TAGS, team: 'web' } })
    expect(ok.status).toBe(202)
    const { wfrn } = body<CommandAccepted>(ok)
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/vm/${enc(wfrn)}`)).tags).toEqual({ ...TAGS, team: 'web' })
  })
})

describe('the Tag Editor', () => {
  it('finds the resources of the types named that carry every tag, with whether each may be tagged', () => {
    const dana = bff('dana')
    const all = body<Page<TaggableResource>>(dana.get('/console/v1/tags/resources?limit=200'))
    expect(new Set(all.items.map((r) => r.type))).toEqual(new Set(['vm', 'environment', 'image']))
    expect(all.next, 'every type is read, page by page').toBe('c200')
    expect(all.items.every((r) => r.canTag)).toBe(true)
    const prodVms = body<Page<TaggableResource>>(dana.get('/console/v1/tags/resources?type=vm&tag.environment=prod&limit=200'))
    expect(prodVms.items.length).toBeGreaterThan(0)
    expect(prodVms.items.every((r) => r.type === 'vm' && r.tags.environment === 'prod')).toBe(true)
    expect(body<Page<TaggableResource>>(dana.get('/console/v1/tags/resources?type=volume,image')).items.every((r) => r.type === 'volume' || r.type === 'image')).toBe(true)
    const unknown = dana.get('/console/v1/tags/resources?type=vm,nope')
    expect(unknown.status).toBe(400)
    expect(body<{ problems: Problem[] }>(unknown).problems[0]).toMatchObject({ code: 'type.unknown', field: '/types/1' })
    // a viewer reads the rows but may tag none of them
    expect(body<Page<TaggableResource>>(bff('mia').get('/console/v1/tags/resources?type=vm')).items.every((r) => !r.canTag)).toBe(true)
  })

  it('sets and removes tags on resources of different types, each its own change, and names what failed', () => {
    const dana = bff('dana')
    const gone = `wfrn:wf:compute:global:${ACCOUNT}:vm/vm-nothing`
    const res = dana.call('POST', '/console/v1/tags:apply', { resources: [VM, gone, VOLUME], set: { team: 'checkout' }, remove: ['visibility'] }, { 'idempotency-key': 'apply-1' })
    expect(res.status).toBe(200)
    const result = body<TagApplyResult>(res)
    expect(result.applied.map((a) => a.wfrn)).toEqual([VM, VOLUME])
    expect(result.applied[0].tags).toMatchObject({ team: 'checkout', owner: 'team-compute' })
    expect(result.applied[0].tags.visibility).toBeUndefined()
    expect(result.failed).toEqual([{ wfrn: gone, error: 'not-found', message: `no resource ${gone} in this account and region` }])
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/volume/${enc(VOLUME)}`)).tags.team).toBe('checkout')
    // the same key and body again changes nothing more
    const again = body<TagApplyResult>(dana.call('POST', '/console/v1/tags:apply', { resources: [VM, gone, VOLUME], set: { team: 'checkout' }, remove: ['visibility'] }, { 'idempotency-key': 'apply-1' }))
    expect(again.applied.map((a) => a.tags)).toEqual(result.applied.map((a) => a.tags))
  })

  it('refuses a body that gets the tags wrong before anything changes, and a viewer per resource', () => {
    const dana = bff('dana')
    const bad = dana.call('POST', '/console/v1/tags:apply', { resources: [VM], set: { environment: 'staging' }, remove: ['owner'] })
    expect(bad.status).toBe(400)
    expect(body<{ problems: Problem[] }>(bad).problems.map((p) => [p.code, p.field])).toEqual([
      ['tags.required', '/remove/0'],
      ['tags.invalid', '/set/environment'],
    ])
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/vm/${enc(VM)}`)).tags.owner).toBe('team-compute')
    const mia = body<TagApplyResult>(bff('mia').call('POST', '/console/v1/tags:apply', { resources: [VM2], set: { team: 'x' }, remove: [] }))
    expect(mia.applied).toEqual([])
    expect(mia.failed[0]).toMatchObject({ wfrn: VM2, error: 'denied', denied: { layer: 'console-iam', action: 'compute:tag' } })
  })
})

describe('resource groups', () => {
  const query = { types: ['vm', 'volume', 'vm'], tags: { team: 'checkout' } }

  it('creates a group of the account and region, answers the same key again, and refuses its name twice', () => {
    const dana = bff('dana')
    const res = dana.call('POST', '/console/v1/resource-groups', { name: 'checkout', description: 'The checkout service', query }, { 'idempotency-key': 'g-1' })
    expect(res.status).toBe(201)
    const g = body<ResourceGroup>(res)
    expect(g.groupId).toMatch(/^group-[0-9a-hjkmnp-tv-z]{26}$/)
    expect(g).toMatchObject({ name: 'checkout', description: 'The checkout service', query: { types: ['vm', 'volume'], tags: { team: 'checkout' } }, accountId: ACCOUNT, region: 'global', createdBy: 'dana', version: 1, wfrn: `wfrn:wf:resource-groups:global:${ACCOUNT}:group/${g.groupId}` })
    expect(res.headers).toMatchObject({ ETag: '"1"', Location: `/console/v1/resource-groups/${g.groupId}` })
    const replay = dana.call('POST', '/console/v1/resource-groups', { name: 'checkout', description: 'The checkout service', query }, { 'idempotency-key': 'g-1' })
    expect(replay.status).toBe(201)
    expect(replay.headers?.['Idempotent-Replay']).toBe('true')
    expect(body<ResourceGroup>(replay).groupId).toBe(g.groupId)
    expect(body<{ error: string }>(dana.call('POST', '/console/v1/resource-groups', { name: 'other', query }, { 'idempotency-key': 'g-1' })).error).toBe('idempotency.key-reused')
    const taken = dana.call('POST', '/console/v1/resource-groups', { name: 'checkout', query })
    expect(taken.status).toBe(409)
    expect(body<{ error: string; detail: unknown; problems: Problem[] }>(taken)).toMatchObject({ error: 'group.name-taken', detail: { name: 'checkout' }, problems: [{ field: '/name' }] })
    expect(body<Page<ResourceGroup>>(dana.get('/console/v1/resource-groups')).items.map((x) => x.name)).toEqual(['checkout'])
  })

  it('names a body’s problems at their pointers', () => {
    const dana = bff('dana')
    const bad = dana.call('POST', '/console/v1/resource-groups', { name: 'no spaces', query: { types: ['nope'], tags: {} } })
    expect(bad.status).toBe(400)
    expect(body<{ problems: Problem[] }>(bad).problems.map((p) => [p.code, p.field])).toEqual([
      ['group.name', '/name'],
      ['type.unknown', '/query/types/0'],
      ['group.query.tags-required', '/query/tags'],
    ])
  })

  it('lists the resources that carry every tag of its query, changes with If-Match, and only an admin deletes', () => {
    const dana = bff('dana')
    dana.call('POST', '/console/v1/tags:apply', { resources: [VM, VOLUME], set: { team: 'checkout' }, remove: [] })
    const g = body<ResourceGroup>(dana.call('POST', '/console/v1/resource-groups', { name: 'checkout', query }))
    const rows = body<Page<ConsoleResource>>(dana.get(`/console/v1/resource-groups/${g.groupId}/resources`))
    expect(rows.items.map((r) => r.wfrn).sort()).toEqual([VM, VOLUME].sort())

    const changed = dana.call('PATCH', `/console/v1/resource-groups/${g.groupId}`, { description: 'Checkout, machines only', query: { types: ['vm'], tags: { team: 'checkout' } } }, { 'if-match': '"1"' })
    expect(changed.status).toBe(200)
    expect(body<ResourceGroup>(changed)).toMatchObject({ version: 2, description: 'Checkout, machines only' })
    expect(body<Page<ConsoleResource>>(dana.get(`/console/v1/resource-groups/${g.groupId}/resources`)).items.map((r) => r.wfrn)).toEqual([VM])
    const stale = dana.call('PATCH', `/console/v1/resource-groups/${g.groupId}`, { description: 'late' }, { 'if-match': '"1"' })
    expect(stale.status).toBe(409)
    expect(body<{ detail: { version: number } }>(stale).detail.version).toBe(2)
    expect(body<ResourceGroup>(dana.call('PATCH', `/console/v1/resource-groups/${g.groupId}`, { description: 'Checkout, machines only' })).version, 'a change that changes nothing keeps the version').toBe(2)
    expect(dana.call('PATCH', `/console/v1/resource-groups/${g.groupId}`, { name: 'renamed' }).status).toBe(400)

    const refused = dana.call('DELETE', `/console/v1/resource-groups/${g.groupId}`)
    expect(refused.status).toBe(403)
    expect(body<{ denied: { action: string } }>(refused).denied.action).toBe('resource-groups:delete')
    expect(bff('mia').call('POST', '/console/v1/resource-groups', { name: 'mine', query }).status).toBe(403)
  })

  it('replays its groups from the journal, under the same ids', () => {
    const gonzalo = bff('gonzalo')
    const g = body<ResourceGroup>(gonzalo.call('POST', '/console/v1/resource-groups', { name: 'web', query }))
    gonzalo.call('PATCH', `/console/v1/resource-groups/${g.groupId}`, { description: 'the web tier' })
    const kept = gonzalo.call('POST', '/console/v1/resource-groups', { name: 'gone', query })
    expect(gonzalo.call('DELETE', `/console/v1/resource-groups/${body<ResourceGroup>(kept).groupId}`).status).toBe(204)
    const fresh = buildMockData()
    replayConsole(fresh, gonzalo.data.console?.journal ?? [], Date.parse('2026-10-23T11:00:00Z'))
    expect(Object.values(fresh.console?.tools?.groups ?? {})).toEqual([{ ...g, description: 'the web tier', version: 2, updatedAt: g.updatedAt }])
  })
})

describe('a clone’s draft', () => {
  it('fills the create from the resource: its fields, a new name, its tags, and the accounts the person may create it in', () => {
    const gonzalo = bff('gonzalo')
    const draft = body<CloneDraft>(gonzalo.get(`/console/v1/r/vm/${enc(VM)}/clone`))
    expect(draft).toMatchObject({ type: 'vm', workflow: 'create-vm', accountId: ACCOUNT, source: { wfrn: VM, accountId: ACCOUNT, region: 'global' } })
    expect(draft.input.name).toMatch(/-copy$/)
    expect(draft.input.image).toBeTruthy()
    expect(draft.tags.owner).toBe('team-compute')
    expect(draft.notes).toEqual([])
    expect(draft.targets.map((t) => [t.name, t.current, t.allowed])).toEqual([
      ['Compute', true, true],
      ['Data', false, false],
    ])
    expect(draft.tagPolicy.accountId).toBe(ACCOUNT)
  })

  it('gives the account defaults of another account, and notes the references that may not reach it', () => {
    const gonzalo = bff('gonzalo')
    gonzalo.call('PUT', `/console/v1/r/vm/${enc(VM)}/tags`, { ...TAGS, environment: 'prod' })
    const draft = body<CloneDraft>(gonzalo.get(`/console/v1/r/vm/${enc(VM)}/clone?account=210987654321`))
    expect(draft.accountId).toBe('210987654321')
    expect(draft.tags).toMatchObject({ owner: 'team-data', 'cost-centre': 'cc-2077', 'data-class': 'internal', environment: 'prod' })
    expect(draft.notes).toContainEqual({ field: 'image', code: 'reference', message: 'image names a image of this account; check that the target account can use it' })
    expect(draft.tagPolicy.required[1].default).toBe('cc-2077')
    expect(bff('dana').get(`/console/v1/r/vm/${enc(VM)}/clone?account=210987654321`).status, 'not an account of Dana’s').toBe(404)
  })

  it('suggests <name>-copy shortened to fit the field', () => {
    expect(copyName('web-01', { type: 'string', pattern: '^[a-z][a-z0-9-]{2,30}$' })).toBe('web-01-copy')
    expect(copyName('a'.repeat(31), { type: 'string', pattern: '^[a-z][a-z0-9-]{2,30}$' })).toBe(`${'a'.repeat(26)}-copy`)
    expect(copyName('x', { type: 'string', maxLength: 3 })).toBeNull()
  })
})
