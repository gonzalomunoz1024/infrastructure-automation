import { describe, expect, it } from 'vitest'
import type { BlastRadius, CommandAccepted, ConsolePreview, ConsoleResource, ConsoleResourceDetail, Dependent, Page, Problem, Resource } from '../lib/types'
import { buildMockData } from './data'
import { APPROVAL_MS, RUN_MS } from './commands'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Changing resources safely (phase 4) in the mock: the blast radius of a resource (every account
 * counted, only the session's named), a delete's preview and its 409 while something depends on
 * it, and the update convention behind Edit configuration (PATCH with the update workflow's
 * mutable fields, If-Match against the version, one change at a time, only while active or failed).
 */

const COMPUTE = '123456789012'
const DATA = '210987654321'
const WEB01 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo01`
const API01 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo02`
const WEB02 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo03`
const enc = encodeURIComponent
const body = <T>(res: { body?: unknown }) => res.body as T

function bff(person: string, start = Date.parse('2026-10-23T10:00:00Z')) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, payload?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body: payload })
  return { data, call, get: (url: string) => call('GET', url), tick: (ms: number) => void (now += ms) }
}

/** Gonzalo in the Data account, where orders-db lives and the environment built from it does not. */
function inData() {
  const g = bff('gonzalo')
  expect(g.call('PUT', '/console/v1/session', { accountId: DATA }).status).toBe(200)
  const orders = body<Page<ConsoleResource>>(g.get('/console/v1/r/database')).items.find((r) => r.name === 'orders-db')
  expect(orders?.wfrn).toBeDefined()
  return { ...g, orders: orders!.wfrn }
}

describe('the blast radius of a resource', () => {
  it('names what depends on web-01, nearest first, and counts it by account', () => {
    const mia = bff('mia')
    const res = mia.get(`/console/v1/r/vm/${enc(WEB01)}/blast-radius`)
    expect(res.status).toBe(200)
    const radius = body<BlastRadius>(res)
    expect(radius).toMatchObject({ resource: { wfrn: WEB01, name: 'web-01' }, total: 2, direct: 2, depth: 3, truncated: false, byAccount: [{ accountId: COMPUTE, count: 2, current: true }] })
    expect(radius.dependents.map((d) => [d.type, d.name, d.depth, d.state])).toEqual([
      ['environment', 'shop-prod', 1, 'active'],
      ['volume', 'volume-demo01', 1, 'active'],
    ])
    expect(radius.edges).toEqual(expect.arrayContaining([expect.objectContaining({ from: WEB01, via: 'request-storage' }), expect.objectContaining({ from: WEB01, via: 'web-environment' })]))
  })

  it('takes a depth of 1 to 5, and refuses anything else', () => {
    const mia = bff('mia')
    expect(body<BlastRadius>(mia.get(`/console/v1/r/vm/${enc(WEB01)}/blast-radius?depth=1`)).depth).toBe(1)
    for (const bad of ['0', '6', 'two', '2.5']) {
      const res = mia.get(`/console/v1/r/vm/${enc(WEB01)}/blast-radius?depth=${bad}`)
      expect(res.status, `depth=${bad}`).toBe(400)
      expect(body<{ problems: Problem[] }>(res).problems[0]).toMatchObject({ field: '/depth' })
    }
  })

  it('counts a dependent in another account without naming it', () => {
    const g = inData()
    const radius = body<BlastRadius>(g.get(`/console/v1/r/database/${enc(g.orders)}/blast-radius`))
    // shop-prod was built from orders-db and lives in Compute: counted, never named from Data
    expect(radius).toMatchObject({ total: 1, direct: 1, dependents: [], edges: [], byAccount: [{ accountId: COMPUTE, count: 1, current: false }] })
  })
})

describe('a delete held back by what depends on it', () => {
  it('previews the blast radius, names the dependents among the affected, and denies with dependents.exist', () => {
    const g = bff('gonzalo')
    const preview = body<ConsolePreview>(g.call('POST', `/console/v1/r/vm/${enc(WEB01)}:delete:preview`, { input: { reason: 'replaced by a larger machine' } }))
    expect(preview.blastRadius).toMatchObject({ total: 2, direct: 2 })
    expect(preview.affectedResources.map((r) => r.name)).toEqual(['web-01', 'shop-prod', 'volume-demo01'])
    expect(preview.policy.decision).toBe('deny')
    expect(preview.policy.rules?.find((r) => r.id === 'dependents.exist')).toMatchObject({ result: 'deny', message: '2 resources are built from this virtual machine; delete them first' })
    // a verb that changes nothing away carries no blast radius
    expect(body<ConsolePreview>(g.call('POST', `/console/v1/r/vm/${enc(WEB01)}:restart:preview`, { input: {} })).blastRadius).toBeUndefined()
    // nothing depends on a generated machine: the radius is empty and the delete waits only for its approver
    const lone = body<ConsolePreview>(g.call('POST', `/console/v1/r/vm/${enc(`wfrn:wf:compute:global:${COMPUTE}:vm/vm-gen-001`)}:delete:preview`, { input: { reason: 'no longer needed, really' } }))
    expect(lone.blastRadius).toMatchObject({ total: 0, direct: 0, dependents: [] })
    expect(lone.policy.decision).toBe('needs-approval')
  })

  it('judges each dependent by its own type, so one its type counts as deleted holds nothing back', () => {
    const g = bff('gonzalo')
    const volumes = g.data.resourceTypes.find((t) => t.name === 'volume')!
    volumes.console = { ...volumes.console, lifecycle: { released: 'deleted' } }
    g.data.resources.find((r) => r.id === 'volume-demo01')!.status = 'released'
    const preview = body<ConsolePreview>(g.call('POST', `/console/v1/r/vm/${enc(WEB01)}:delete:preview`, { input: { reason: 'replaced by a larger machine' } }))
    expect(preview.blastRadius?.dependents.map((d) => d.name)).toEqual(['shop-prod'])
    expect(preview.policy.rules?.find((r) => r.id === 'dependents.exist')?.message).toBe('1 resource is built from this virtual machine; delete them first')
  })

  it('answers the delete itself 409 dependents.exist with what the walk found, and runs nothing', () => {
    const g = bff('gonzalo')
    const jobs = g.data.jobs.length
    const res = g.call('POST', `/console/v1/r/vm/${enc(WEB01)}:delete`, { input: { reason: 'replaced by a larger machine' }, confirm: true })
    expect(res.status).toBe(409)
    const refusal = body<{ error: string; message: string; problems: Problem[]; correlationId: string; detail: { dependents: Dependent[]; total: number; byAccount: BlastRadius['byAccount'] } }>(res)
    expect(refusal).toMatchObject({ error: 'dependents.exist', message: expect.stringContaining('built from this virtual machine'), problems: [expect.objectContaining({ code: 'dependents.exist' })], correlationId: expect.stringMatching(/^mock-/) })
    expect(refusal.detail).toMatchObject({ total: 2, byAccount: [{ accountId: COMPUTE, count: 2, current: true }] })
    expect(refusal.detail.dependents.map((d) => [d.name, d.depth])).toEqual([
      ['shop-prod', 1],
      ['volume-demo01', 1],
    ])
    expect(g.data.jobs.length).toBe(jobs)
    expect(body<ConsoleResource>(g.get(`/console/v1/r/vm/${enc(WEB01)}`)).state).toBe('active')
  })

  it('counts, from the Data account, the dependent that lives in Compute, and names none', () => {
    const g = inData()
    const preview = body<ConsolePreview>(g.call('POST', `/console/v1/r/database/${enc(g.orders)}:delete:preview`, { input: {} }))
    expect(preview.policy.decision).toBe('deny')
    expect(preview.affectedResources.map((r) => r.name)).toEqual(['orders-db'])
    const res = g.call('POST', `/console/v1/r/database/${enc(g.orders)}:delete`, { input: {}, confirm: true })
    expect(res.status).toBe(409)
    expect(body<{ detail: unknown }>(res).detail).toEqual({ dependents: [], total: 1, byAccount: [{ accountId: COMPUTE, count: 1, current: false }] })
  })
})

describe('the update convention behind Edit configuration', () => {
  it('takes the update workflow’s mutable fields and its operational reason, refuses anything else as immutable, and writes only the fields', () => {
    const g = bff('gonzalo')
    const immutable = g.call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { memoryGb: 32, image: 'image-demo02' } })
    expect(immutable.status).toBe(400)
    expect(body<{ problems: Problem[] }>(immutable).problems).toEqual([expect.objectContaining({ code: 'immutable', field: '/input/image' })])
    // a reason alone changes nothing
    expect(body<{ problems: Problem[] }>(g.call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { reason: 'more room' } })).problems[0]).toMatchObject({ code: 'required', field: '/input' })
    // a value outside the workflow's choices
    expect(body<{ problems: Problem[] }>(g.call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { memoryGb: 24 } })).problems[0]).toMatchObject({ field: 'memoryGb' })
    expect(g.call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { memoryGb: 32 }, reason: 'x'.repeat(1001) }).status).toBe(400)

    const res = g.call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { memoryGb: 32, reason: 'the shop needs more cache' } }, { 'if-match': '1' })
    expect(res.status).toBe(202)
    const accepted = body<CommandAccepted>(res)
    // web-02 lives in prod: the change waits for its approver, then runs
    expect(accepted.approval).toMatchObject({ group: 'compute-approvers' })
    expect(g.data.console?.jobs[accepted.jobId]).toMatchObject({ verb: 'update', input: { memoryGb: 32 }, reason: 'the shop needs more cache' })
    g.tick(APPROVAL_MS + RUN_MS + 1)
    const after = body<ConsoleResourceDetail>(g.get(`/console/v1/r/vm/${enc(WEB02)}`))
    expect(after).toMatchObject({ state: 'active', version: 2 })
    expect(after.attributes).toMatchObject({ cpu: 4, memoryGb: 32 })
    expect(after.attributes).not.toHaveProperty('reason')
  })

  it('answers a stale If-Match 409 with the version it moved on to', () => {
    const g = bff('gonzalo')
    expect(g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { cpu: 4 } }, { 'if-match': '1' }).status).toBe(202)
    g.tick(RUN_MS + 1)
    const stale = g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { memoryGb: 16 } }, { 'if-match': '1' })
    expect(stale.status).toBe(409)
    expect(stale.body).toEqual({ error: 'the resource moved on to version 2', detail: { version: 2 } })
    // the version it holds now, however it is written, and a wildcard, pass
    expect(g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { memoryGb: 16 } }, { 'if-match': 'W/"2"' }).status).toBe(202)
    g.tick(RUN_MS + 1)
    expect(g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { memoryGb: 32 } }, { 'if-match': '*' }).status).toBe(202)
  })

  it('takes one change at a time, and only on an active or failed resource', () => {
    const g = bff('gonzalo')
    expect(g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { cpu: 4 } }).status).toBe(202)
    const busy = g.call('PATCH', `/console/v1/r/vm/${enc(API01)}`, { input: { memoryGb: 16 } })
    expect(busy.status).toBe(409)
    expect(body<{ error: string }>(busy).error).toBe('resource.busy')
    // a machine still being built is not changed
    const building = g.data.resources.find((r: Resource) => r.id === 'vm-gen-004')!
    expect(building.status).toBe('building')
    const pending = g.call('PATCH', `/console/v1/r/vm/${enc(building.wfrn!)}`, { input: { cpu: 8 } })
    expect(pending.status).toBe(409)
    expect(body<{ detail: unknown }>(pending).detail).toEqual({ state: 'pending' })
  })

  it('previews through the update workflow, refuses a viewer, and offers no update where no workflow makes one', () => {
    const g = bff('gonzalo')
    const preview = body<ConsolePreview>(g.call('POST', `/console/v1/r/vm/${enc(WEB02)}:update:preview`, { input: { memoryGb: 32, reason: 'more room' } }))
    expect(preview.workflow).toBe('update-vm')
    expect(preview.steps.find((s) => s.id === 'apply')?.detail).toBe('memoryGb 16 → 32')
    expect(preview.policy.decision).toBe('needs-approval')
    expect(preview.blastRadius).toBeUndefined()
    expect(g.call('POST', `/console/v1/r/vm/${enc(WEB02)}:update:preview`, { input: { name: 'web-99' } }).status).toBe(400)
    // the update itself is a PATCH, not a verb
    expect(g.call('POST', `/console/v1/r/vm/${enc(WEB02)}:update`, { input: { memoryGb: 32 } }).status).toBe(404)
    expect(bff('mia').call('PATCH', `/console/v1/r/vm/${enc(WEB02)}`, { input: { memoryGb: 32 } }).status).toBe(403)

    const d = inData()
    const none = d.call('PATCH', `/console/v1/r/database/${enc(d.orders)}`, { input: { tier: 'M60' } })
    expect(none.status).toBe(404)
    expect(body<{ error: string; detail: string }>(none)).toMatchObject({ error: 'verb.unknown', detail: expect.stringContaining('editing is not offered by this provider') })
  })
})
