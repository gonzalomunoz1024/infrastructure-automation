import { describe, expect, it } from 'vitest'
import type { ActivityEntry, BffJobDetail, BffJobSummary, CommandAccepted, ConsolePreview, ConsoleResource, ConsoleResourceDetail, Denial, Finding, JobDetail, Page, Problem, Quota, Resource } from '../lib/types'
import { buildMockData } from './data'
import { restoreConsole, saveConsole, MOCK_JOURNAL_KEY } from './install'
import { APPROVAL_MS, RUN_MS, idFrom } from './commands'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { ADMIN_ROLE } from './directory'

/*
 * The BFF's resource and job endpoints in the mock: a create is authorized, validated and
 * answered 202 with a job; the job runs on the server's clock and the resource settles; verbs,
 * updates, previews with cost and policy, tags, activity, drift, lineage, idempotency, and the
 * journal the browser replays after a reload.
 */

const VM = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'
const VM2 = 'wfrn:wf:compute:global:123456789012:vm/vm-demo02'
const IMAGE = 'wfrn:wf:platform:global:123456789012:image/image-demo01'
/** a generated machine nothing was built from, so it may be deleted */
const VMG = 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001'

function bff(person: string, start = Date.parse('2026-10-23T10:00:00Z')) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body })
  const tick = (ms: number) => {
    now += ms
  }
  return { server, data, call, tick, get: (url: string) => call('GET', url) }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const enc = encodeURIComponent

describe('creating a resource through the BFF', () => {
  it('refuses a viewer with the deciding layer, and a missing input with problems', () => {
    const mia = bff('mia')
    const refused = mia.call('POST', '/console/v1/r/vm', { input: { name: 'web-77', image: 'image-demo01', size: 'small' } })
    expect(refused.status).toBe(403)
    expect(body<{ denied: { layer: string; action: string } }>(refused).denied).toMatchObject({ layer: 'console-iam', action: 'compute:create' })

    const dana = bff('dana')
    const bad = dana.call('POST', '/console/v1/r/vm', { input: { name: 'Web 77', size: 'huge' } })
    expect(bad.status).toBe(400)
    const problems = body<{ error: string; problems: Problem[] }>(bad)
    expect(problems.error).toBe('validation.failed')
    expect(problems.problems.map((p) => p.field)).toEqual(expect.arrayContaining(['image', 'name', 'size']))
    expect(dana.call('POST', '/console/v1/r/vm', {}).status).toBe(400)
    expect(dana.call('POST', '/console/v1/r/nothing', { input: {} }).status).toBe(404)
  })

  it('answers 202 with the job, the wfrn and the request id, then settles the machine on the clock', () => {
    const dana = bff('dana')
    const res = dana.call('POST', '/console/v1/r/vm', { input: { name: 'web-77', image: 'image-demo01', size: 'medium', environment: 'dev' }, reason: 'the shop needs one more web node' })
    expect(res.status).toBe(202)
    const accepted = body<CommandAccepted>(res)
    expect(accepted.wfrn).toMatch(/^wfrn:wf:compute:global:123456789012:vm\/vm-[a-z0-9]{8}$/)
    expect(accepted.jobId).toMatch(/^job_[a-z0-9]{10}$/)
    expect(accepted.requestId).toMatch(/^creq_/)
    expect(accepted.approval).toBeUndefined()

    // pending, with the job in flight, through the BFF and the platform alike
    const detail = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`))
    expect(detail).toMatchObject({ wfrn: accepted.wfrn, type: 'vm', service: 'compute', name: 'web-77', state: 'pending', version: 1, region: 'global' })
    expect(detail.attributes).toMatchObject({ name: 'web-77', image: 'image-demo01', cpu: 4, memoryGb: 16, environment: 'dev', owner: 'team-compute' })
    expect(detail.lastJob).toMatchObject({ jobId: accepted.jobId, workflow: 'create-vm', status: 'running' })
    // too new for the hourly drift scan: not drifted, and no scan has read it yet
    expect(detail.drift).toBe(false)
    expect(detail.observedAt).toBeUndefined()
    expect(detail.can).toEqual(expect.arrayContaining(['get', 'update', 'restart']))
    expect(detail.can).not.toContain('delete')
    const platform = body<Resource>(dana.get(`/api/v1/vms/${detail.wfrn.split('/').pop()}`))
    expect(platform.status).toBe('building')
    expect(platform.builtFrom).toEqual(['image-demo01'])
    expect(platform.createdBy).toMatchObject({ jobId: accepted.jobId, workflow: 'create-vm', version: '2.0.1' })
    const job = body<JobDetail>(dana.get(`/api/v1/jobs/${accepted.jobId}`))
    expect(job).toMatchObject({ status: 'running', channel: 'console', requester: 'dana', wfrn: accepted.wfrn, accountId: '123456789012', title: 'Create VM web-77' })
    expect(job.input).toMatchObject({ name: 'web-77', size: 'medium' })

    // the apply finishes
    dana.tick(RUN_MS + 1)
    const settled = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`))
    expect(settled.state).toBe('active')
    expect(settled.providerId).toBe(`vsphere:${settled.wfrn.split('/').pop()}`)
    expect(settled.version).toBe(2)
    const done = body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))
    expect(done).toMatchObject({ jobId: accepted.jobId, status: 'succeeded' })
    expect(done.output).toMatchObject({ name: 'web-77' })
    // nothing left to stop, nothing failed to retry
    expect(done.can).toEqual([])
    expect(done.durationMs).toBe(RUN_MS)
    const events = body<{ items: { type: string }[] }>(dana.get('/api/v1/events?after=0&limit=500')).items
    expect(events.map((e) => e.type)).toEqual(expect.arrayContaining(['job.started', 'job.finished', 'resource.registered']))
  })

  it('needs an approval for production, which the mock approver grants after a while', () => {
    const dana = bff('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'api-90', image: 'image-demo01', size: 'small', environment: 'prod' } }))
    expect(accepted.approval).toMatchObject({ group: 'compute-approvers', status: 'pending' })
    expect(body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`)).status).toBe('waiting-approval')
    dana.tick(APPROVAL_MS + 1)
    const running = body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))
    expect(running.status).toBe('running')
    expect(running.approvals?.[0]).toMatchObject({ status: 'approved', decidedBy: 'approver:compute-approvers' })
    dana.tick(RUN_MS)
    expect(body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`)).status).toBe('succeeded')
  })

  it('pauses a create whose name asks to fail on its failed apply, until someone retries or aborts it', () => {
    const dana = bff('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'fail-01', image: 'image-demo01', size: 'small' } }))
    dana.tick(RUN_MS + 1)
    const paused = body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))
    expect(paused).toMatchObject({ status: 'needs-attention', failedStep: 'apply', errorClass: 'provider', attention: { step: 'apply', options: ['retry', 'abort'] } })
    expect(paused.can).toEqual(['cancel', 'retry', 'abort'])
    expect(paused.steps?.find((x) => x.id === 'apply')).toMatchObject({ status: 'failed', error: { class: 'provider' } })
    // nothing ran past the step: the machine waits as it was
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`)).state).toBe('pending')
    // the compensations undo what ran, and the machine is left failed for a retry
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:abort`).status).toBe(202)
    const aborted = body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))
    expect(aborted).toMatchObject({ status: 'rolled-back', failedStep: 'apply', errorClass: 'provider' })
    expect(aborted.steps?.map((x) => x.id)).toContain('compensate')
    expect(aborted.can).toEqual(['retry'])
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`)).state).toBe('failed')
  })

  it('answers the same reply to the same Idempotency-Key and body, and refuses the key with another body', () => {
    const dana = bff('dana')
    const input = { name: 'cache-21', image: 'image-demo01', size: 'small' }
    const first = dana.call('POST', '/console/v1/r/vm', { input }, { 'idempotency-key': 'k-1' })
    const again = dana.call('POST', '/console/v1/r/vm', { input }, { 'idempotency-key': 'k-1' })
    expect(again.status).toBe(202)
    expect(again.body).toEqual(first.body)
    expect(again.headers?.['Idempotent-Replayed']).toBe('true')
    expect(dana.server.data.jobs.filter((j) => j.title === 'Create VM cache-21')).toHaveLength(1)
    const other = dana.call('POST', '/console/v1/r/vm', { input: { ...input, size: 'large' } }, { 'idempotency-key': 'k-1' })
    expect(other.status).toBe(409)
    expect(body<{ error: string }>(other).error).toBe('idempotency.mismatch')
  })

  it('previews a create with its steps, the image it uses, the list price and the quota rule', () => {
    const dana = bff('dana')
    const res = dana.call('POST', '/console/v1/r/vm:preview', { input: { name: 'web-78', image: 'image-demo01', size: 'large', environment: 'dev' } })
    expect(res.status).toBe(200)
    const preview = body<ConsolePreview>(res)
    expect(preview.workflow).toBe('create-vm')
    expect(preview.steps.map((s) => s.id)).toEqual(['validate', 'use-image', 'apply', 'register'])
    expect(preview.steps.map((s) => s.effect)).toEqual(['read', 'read', 'create', 'create'])
    expect(preview.affectedResources).toEqual([expect.objectContaining({ wfrn: IMAGE, type: 'image', name: 'ubuntu-24.04-cis-l1-2026-10-01' })])
    expect(preview.cost).toMatchObject({ monthly: 154, currency: 'USD' })
    expect(preview.policy.decision).toBe('allow')
    expect(preview.policy.rules?.map((r) => r.id)).toEqual(['rbac.role', 'quota.resources'])

    const prod = body<ConsolePreview>(dana.call('POST', '/console/v1/r/vm:preview', { input: { name: 'web-79', image: 'image-demo01', size: 'small', environment: 'prod' } }))
    expect(prod.policy.decision).toBe('needs-approval')
    const building = body<ConsolePreview>(dana.call('POST', '/console/v1/r/vm:preview', { input: { name: 'web-80', image: { create: { os: 'ubuntu-24.04' } }, size: 'small' } }))
    expect(building.steps.map((s) => s.id)).toContain('build-image')
    expect(building.affectedResources).toEqual([])
  })

  it('counts the type’s quota as the Quotas card does, and a deleted machine frees its place', () => {
    const gonzalo = bff('gonzalo')
    const quota = () => body<Quota>(gonzalo.get('/console/v1/iam/quotas/resources.vm'))
    const input = { name: 'web-81', image: 'image-demo01', size: 'small', environment: 'dev' }
    const ruleOf = () => body<ConsolePreview>(gonzalo.call('POST', '/console/v1/r/vm:preview', { input })).policy.rules?.find((r) => r.id === 'quota.resources')
    const { used, limit } = quota()
    expect(ruleOf()).toMatchObject({ result: 'pass', message: `Virtual machines: ${used} of ${limit} in this account` })

    // the platform team set the limit at what the account holds: the review and the create refuse
    gonzalo.data.directory.quotaOverrides['123456789012']['resources.vm'] = used
    expect(ruleOf()).toMatchObject({ result: 'deny', message: expect.stringContaining('request an increase from the platform team') })
    const refused = gonzalo.call('POST', '/console/v1/r/vm', { input })
    expect(refused.status).toBe(409)
    expect(body<{ error: string; problems: Problem[] }>(refused)).toMatchObject({ error: 'policy.denied', problems: [expect.objectContaining({ code: 'quota.resources' })] })

    // a deleted machine is a tombstone for 30 days, but it no longer counts
    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true }).status).toBe(202)
    expect(quota().used, 'a machine being deleted still counts').toBe(used)
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    expect(gonzalo.get(`/console/v1/r/vm/${enc(VMG)}`).status).toBe(410)
    expect(quota().used).toBe(used - 1)
    expect(ruleOf()).toMatchObject({ result: 'pass' })
  })
})

describe('verbs, updates and tags on one resource', () => {
  it('runs a verb through its workflow with the subject filled in, and refuses a destructive one without confirm', () => {
    const dana = bff('dana')
    // api-01 lives in dev: no approval
    const res = dana.call('POST', `/console/v1/r/vm/${enc(VM2)}:restart`, { input: { reason: 'stuck after a kernel update' } })
    expect(res.status).toBe(202)
    const accepted = body<CommandAccepted>(res)
    expect(accepted.wfrn).toBe(VM2)
    const job = body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))
    expect(job).toMatchObject({ workflow: 'vm-restart', status: 'running', wfrn: VM2, title: 'VM restart api-01' })
    expect(job.input).toMatchObject({ vmId: 'vm-demo02', reason: 'stuck after a kernel update' })
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/vm/${enc(VM2)}`)).state).toBe('updating')
    // one change at a time
    expect(dana.call('POST', `/console/v1/r/vm/${enc(VM2)}:patch`, { input: { cve: 'CVE-2026-1234' } }).status).toBe(409)
    dana.tick(RUN_MS + 1)
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/vm/${enc(VM2)}`)).state).toBe('active')
    // web-01 lives in prod: the restart waits for an approval
    expect(body<CommandAccepted>(dana.call('POST', `/console/v1/r/vm/${enc(VM)}:restart`, { input: {} })).approval).toMatchObject({ group: 'compute-approvers' })
    dana.tick(APPROVAL_MS + RUN_MS + 1)

    // dana is an operator: no policy of the operator role allows a delete, and the refusal says which role would
    const denied = dana.call('POST', `/console/v1/r/vm/${enc(VM)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true })
    expect(denied.status).toBe(403)
    expect(body<{ denied: Denial }>(denied).denied).toMatchObject({ layer: 'console-iam', action: 'compute:delete', role: 'operator', suggestedRole: ADMIN_ROLE, requestable: true })

    const gonzalo = bff('gonzalo')
    const unconfirmed = gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'no longer needed, really' } })
    expect(unconfirmed.status).toBe(400)
    expect(body<{ problems: Problem[] }>(unconfirmed).problems[0]).toMatchObject({ code: 'confirm.required', field: '/confirm' })
    const tooShort = gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'bye' }, confirm: true })
    expect(tooShort.status).toBe(400)
    // web-01 has an environment and a volume built from it: policy refuses the delete
    const blocked = gonzalo.call('POST', `/console/v1/r/vm/${enc(VM)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true })
    expect(blocked.status).toBe(409)
    const refusal = body<{ error: string; problems: Problem[]; detail: { dependents: { wfrn: string; type: string }[] } }>(blocked)
    expect(refusal).toMatchObject({ error: 'dependents.exist', problems: [expect.objectContaining({ code: 'dependents.exist' })] })
    expect(refusal.detail.dependents.length).toBeGreaterThan(0)
    expect(refusal.detail.dependents.every((d) => d.wfrn.startsWith('wfrn:') && d.wfrn !== VM)).toBe(true)
    const deleted = gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete`, { input: { reason: 'no longer needed, really' }, confirm: true })
    expect(deleted.status).toBe(202)
    expect(body<CommandAccepted>(deleted).approval).toMatchObject({ group: 'compute-approvers' })
    expect(body<ConsoleResource>(gonzalo.get(`/console/v1/r/vm/${enc(VMG)}`)).state).toBe('deleting')
    gonzalo.tick(APPROVAL_MS + RUN_MS + 1)
    const gone = gonzalo.get(`/console/v1/r/vm/${enc(VMG)}`)
    expect(gone.status).toBe(410)
    expect(body<{ error: string; detail: Record<string, unknown> }>(gone)).toMatchObject({ error: 'resource.deleted', detail: { wfrn: VMG, deletedAt: expect.any(String), tombstoneUntil: expect.any(String), lastVersion: expect.any(Number) } })
    expect(body<Resource>(gonzalo.get('/api/v1/vms/vm-gen-001')).status).toBe('retired')
    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:restart`, { input: {} }).status).toBe(409)
    expect(gonzalo.call('POST', `/console/v1/r/vm/${enc(VM)}:dance`, { input: {} }).status).toBe(404)
  })

  it('previews a verb: approval for a destructive one, the saving, and a deny when dependents exist', () => {
    const gonzalo = bff('gonzalo')
    const restart = body<ConsolePreview>(gonzalo.call('POST', `/console/v1/r/vm/${enc(VM2)}:restart:preview`, { input: { reason: 'high load' } }))
    expect(restart.workflow).toBe('vm-restart')
    expect(restart.policy.decision).toBe('allow')
    expect(restart.affectedResources).toEqual([expect.objectContaining({ wfrn: VM2, name: 'api-01', state: 'active' })])
    expect(restart.cost).toBeUndefined()
    const prod = body<ConsolePreview>(gonzalo.call('POST', `/console/v1/r/vm/${enc(VM)}:restart:preview`, { input: {} }))
    expect(prod.policy.decision).toBe('needs-approval')

    const del = body<ConsolePreview>(gonzalo.call('POST', `/console/v1/r/vm/${enc(VMG)}:delete:preview`, { input: { reason: 'no longer needed, really' } }))
    expect(del.policy.decision).toBe('needs-approval')
    expect(del.steps.map((s) => s.id)).toEqual(['check', 'approve', 'apply', 'register'])
    expect(del.cost?.monthly).toBeLessThan(0)

    // the image two machines were built from cannot go
    const image = body<ConsolePreview>(gonzalo.call('POST', `/console/v1/r/image/${enc(IMAGE)}:delete:preview`, { input: {} }))
    expect(image.policy.decision).toBe('deny')
    expect(image.policy.rules?.find((r) => r.id === 'dependents.exist')).toBeTruthy()
    expect(image.affectedResources.length).toBeGreaterThan(1)
  })

  it('updates only the mutable fields through the update workflow, and previews the new price', () => {
    const dana = bff('dana')
    const immutable = dana.call('PATCH', `/console/v1/r/vm/${enc(VM2)}`, { input: { name: 'other' } })
    expect(immutable.status).toBe(400)
    expect(body<{ problems: Problem[] }>(immutable).problems[0]).toMatchObject({ code: 'immutable', field: '/input/name' })
    expect(dana.call('PATCH', `/console/v1/r/vm/${enc(VM2)}`, { input: {} }).status).toBe(400)

    const preview = body<ConsolePreview>(dana.call('POST', `/console/v1/r/vm/${enc(VM2)}:update:preview`, { input: { cpu: 8 } }))
    expect(preview.steps.find((s) => s.id === 'apply')?.detail).toContain('cpu')
    expect(preview.cost).toMatchObject({ monthly: 154 })

    const res = dana.call('PATCH', `/console/v1/r/vm/${enc(VM2)}`, { input: { cpu: 8, memoryGb: 32 } })
    expect(res.status).toBe(202)
    const accepted = body<CommandAccepted>(res)
    expect(body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`))).toMatchObject({ workflow: 'update-vm', title: 'Update virtual machine api-01', status: 'running' })
    dana.tick(RUN_MS + 1)
    const after = body<Resource>(dana.get('/api/v1/vms/vm-demo02'))
    expect(after.attributes).toMatchObject({ cpu: 8, memoryGb: 32 })
    expect(body<BffJobDetail>(dana.get(`/console/v1/jobs/${accepted.jobId}`)).output).toEqual({ changed: ['cpu', 'memoryGb'] })
  })

  it('sets tags, validates them, and lists them with the resource and its activity', () => {
    const dana = bff('dana')
    const bad = dana.call('PUT', `/console/v1/r/vm/${enc(VM)}/tags`, { 'bad key!': 'x', fine: 42 })
    expect(bad.status).toBe(400)
    expect(body<{ problems: Problem[] }>(bad).problems).toHaveLength(2)
    const ok = dana.call('PUT', `/console/v1/r/vm/${enc(VM)}/tags`, { owner: 'payments', 'cost-centre': 'CC-4411' })
    expect(ok.status).toBe(200)
    expect(ok.body).toEqual({ owner: 'payments', 'cost-centre': 'CC-4411' })
    expect(body<ConsoleResource>(dana.get(`/console/v1/r/vm/${enc(VM)}`)).tags).toEqual({ owner: 'payments', 'cost-centre': 'CC-4411' })
    const list = body<Page<ConsoleResource>>(dana.get('/console/v1/r/vm?tag.owner=payments'))
    expect(list.items.map((r) => r.wfrn)).toEqual([VM])
    const activity = body<Page<ActivityEntry>>(dana.get(`/console/v1/r/vm/${enc(VM)}/activity`))
    expect(activity.items[0]).toMatchObject({ kind: 'event', type: 'resource.tagged', actor: 'dana' })
    expect(activity.items[activity.items.length - 1]).toMatchObject({ kind: 'event', type: 'resource.registered' })
    // a viewer may read but not tag
    const mia = bff('mia')
    expect(mia.call('PUT', `/console/v1/r/vm/${enc(VM)}/tags`, { a: 'b' }).status).toBe(403)
    expect(mia.get(`/console/v1/r/vm/${enc(VM)}/tags`).status).toBe(200)
  })
})

describe('reading resources, drift, lineage and jobs through the BFF', () => {
  it('lists the account\'s resources with state, paging, filters and sorting', () => {
    const dana = bff('dana')
    const page = body<Page<ConsoleResource>>(dana.get('/console/v1/r/vm?limit=10&sort=-created'))
    expect(page.items).toHaveLength(10)
    expect(page.next).toBe('c10')
    expect(page.items[0].state).toBe('active')
    expect(page.items.every((r) => r.wfrn.startsWith('wfrn:wf:compute:global:123456789012:vm/'))).toBe(true)
    const drifted = body<Page<ConsoleResource>>(dana.get('/console/v1/r/vm?drift=true&limit=200'))
    expect(drifted.items.map((r) => r.wfrn)).toContain(VM2)
    expect(drifted.items.every((r) => r.drift === true)).toBe(true)
    const row = drifted.items.find((r) => r.wfrn === VM2)
    expect(row?.observed?.memoryGb).toBe(Number(row?.attributes.memoryGb) * 2)
    // the list and the resource page agree: the same row, with the finding the Drift tab shows
    const detail = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(VM2)}`))
    expect(detail).toMatchObject({ wfrn: VM2, state: row?.state, drift: true, finding: { status: 'open', fields: [{ path: 'memoryGb' }] } })
    expect(detail.observedAt).toBeDefined()
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(VM)}`))).toMatchObject({ drift: false, finding: undefined })
    const q = body<Page<ConsoleResource>>(dana.get('/console/v1/r/vm?q=web-01'))
    expect(q.items.some((r) => r.name === 'web-01')).toBe(true)
    // the data account sees no compute machines
    const raj = bff('raj')
    expect(body<Page<ConsoleResource>>(raj.get('/console/v1/r/vm')).items).toEqual([])
    expect(raj.get(`/console/v1/r/vm/${enc(VM)}`).status).toBe(404)
  })

  it('answers drift findings and lineage', () => {
    const dana = bff('dana')
    expect(body<{ items: Finding[] }>(dana.get(`/console/v1/r/vm/${enc(VM)}/drift`)).items).toEqual([])
    const drift = body<{ items: Finding[] }>(dana.get(`/console/v1/r/vm/${enc(VM2)}/drift`)).items
    expect(drift).toHaveLength(1)
    expect(drift[0]).toMatchObject({ wfrn: VM2, status: 'open', severity: 'warning', can: ['revert', 'accept', 'ignore'] })
    expect(drift[0].fields[0]).toMatchObject({ path: 'memoryGb' })
    const lineage = body<{ resource: { wfrn: string }; nodes: unknown[]; edges: { from: string; to: string; via?: string }[] }>(dana.get(`/console/v1/r/vm/${enc(VM)}/lineage`))
    expect(lineage.resource.wfrn).toBe(VM)
    expect(lineage.edges).toEqual(expect.arrayContaining([{ from: IMAGE, to: VM, via: 'create-vm' }, expect.objectContaining({ from: VM, via: 'request-storage' })]))
    expect(lineage.nodes.length).toBe(lineage.edges.length + 1)
  })

  it('lists jobs by wfrn and status, and cancels an open one', () => {
    const dana = bff('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'etl-05', image: 'image-demo01', size: 'small' } }))
    const mine = body<Page<BffJobSummary>>(dana.get(`/console/v1/jobs?wfrn=${enc(accepted.wfrn)}`))
    expect(mine.items.map((j) => j.jobId)).toEqual([accepted.jobId])
    expect(body<Page<BffJobSummary>>(dana.get('/console/v1/jobs?status=running')).items.every((j) => j.status === 'running')).toBe(true)
    expect(body<Page<BffJobSummary>>(dana.get('/console/v1/jobs?limit=5')).items).toHaveLength(5)

    // stopping a request asks jobs:cancel: a viewer may not
    const mia = bff('mia')
    mia.server.data.jobs.unshift(...dana.server.data.jobs.filter((j) => j.id === accepted.jobId))
    const refused = mia.call('POST', `/console/v1/jobs/${accepted.jobId}:cancel`)
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ action: 'jobs:cancel' })
    const cancelled = dana.call('POST', `/console/v1/jobs/${accepted.jobId}:cancel`)
    expect(cancelled.status).toBe(202)
    expect(cancelled.body).toEqual({ jobId: accepted.jobId, status: 'cancelled' })
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:cancel`).status).toBe(409)
    // a cancelled create never registers: the machine is gone
    expect(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`).status).toBe(404)
    expect(dana.get('/console/v1/jobs/job_nope').status).toBe(404)
  })

  it('needs a session and the CSRF token like every console write', () => {
    const server = createMockServer()
    expect(server.handle({ method: 'POST', url: '/console/v1/r/vm', headers: {}, body: { input: {} } }).status).toBe(401)
    server.handle({ method: 'GET', url: '/auth/login?login_hint=dana', headers: {} })
    expect(server.handle({ method: 'POST', url: '/console/v1/r/vm', headers: {}, body: { input: {} } }).status).toBe(403)
    expect(server.handle({ method: 'POST', url: '/console/v1/r/vm:batch', headers: { 'x-csrf-token': csrfTokenOf('dana') }, body: { verb: 'restart', items: [] } }).status).toBe(400)
  })
})

describe('the journal the browser keeps', () => {
  it('replays what the console created onto a fresh dataset, settled by the time it is restored', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) }
    const dana = bff('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'batch-44', image: 'image-demo01', size: 'small' } }))
    dana.call('PUT', `/console/v1/r/vm/${enc(accepted.wfrn)}/tags`, { team: 'payments' })
    saveConsole(dana.server.data, storage)
    expect(store.has(MOCK_JOURNAL_KEY)).toBe(true)

    const fresh = buildMockData()
    expect(fresh.resources.some((r) => r.name === 'batch-44')).toBe(false)
    expect(restoreConsole(fresh, storage, Date.parse('2026-10-23T10:00:00Z') + RUN_MS + 1)).toBe(true)
    const vm = fresh.resources.find((r) => r.name === 'batch-44')
    expect(vm).toBeTruthy()
    expect(vm?.wfrn).toBe(accepted.wfrn)
    expect(vm?.status).toBe('ready')
    expect(fresh.jobs.find((j) => j.id === accepted.jobId)?.status).toBe('succeeded')
    expect(fresh.console?.tags[accepted.wfrn]).toEqual({ team: 'payments' })
    expect(fresh.console?.journal).toHaveLength(2)
  })

  it('ignores nothing kept or something broken', () => {
    const empty = { getItem: () => null }
    expect(restoreConsole(buildMockData(), empty)).toBe(false)
    expect(restoreConsole(buildMockData(), { getItem: () => 'not json' })).toBe(false)
    expect(restoreConsole(buildMockData(), { getItem: () => '{"kind":"create"}' })).toBe(false)
  })

  it('mints the same id for the same seed', () => {
    expect(idFrom('a', 10)).toBe(idFrom('a', 10))
    expect(idFrom('a', 10)).toHaveLength(10)
    expect(idFrom('a', 8)).not.toBe(idFrom('b', 8))
  })
})
