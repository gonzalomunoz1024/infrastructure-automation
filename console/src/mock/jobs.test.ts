import { describe, expect, it } from 'vitest'
import type { BatchAccepted, BatchPreview, BffApproval, BffJobDetail, CommandAccepted, ConsoleResourceDetail, Denial, Problem } from '../lib/types'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { APPROVAL_MS, RUN_MS } from './commands'
import { BATCH_ROW_MS, SKIP_MS } from './jobs'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The BFF's job steering and bulk runs in the mock (WP 4.2): a failed step pauses its job until
 * someone retries, skips or aborts it; an ended job runs again as a new one, judged like the
 * first; one call decides a request's approvals and its rows'; a batch is judged row by row, then
 * released in waves a few rows at a time, and stops when too many fail. The rules follow the
 * BFF's own code (Jobs.can, JobRetries, Resources.retry), and every write replays from the journal.
 */

const START = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = 'wfrn:wf:compute:global:123456789012'
const vmWfrn = (id: string) => `${COMPUTE}:vm/${id}`
const enc = encodeURIComponent
const body = <T>(res: { body?: unknown }) => res.body as T

/** One mock BFF several people take turns on, each signing in before they act, on a clock the test moves. */
function world() {
  let now = START
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const as = (person: string) => {
    const call = (method: string, url: string, payload?: unknown) => {
      server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
      return server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person) }, body: payload })
    }
    return { call, get: (url: string) => call('GET', url), job: (id: string) => body<BffJobDetail>(call('GET', `/console/v1/jobs/${id}`)) }
  }
  return {
    data,
    server,
    as,
    tick: (ms: number) => {
      now += ms
    },
    now: () => now,
  }
}

/** The generated machines a restart may run on: ready ones of the compute account, in id order. */
function readyVms(w: ReturnType<typeof world>, n: number): string[] {
  return w.data.resources
    .filter((r) => r.type === 'vm' && r.id.startsWith('vm-gen-') && r.status === 'ready')
    .slice(0, n)
    .map((r) => vmWfrn(r.id))
}

describe('a step that failed pauses its job', () => {
  it('goes on past the step when someone skips it, and records the skip', () => {
    const w = world()
    const mateo = w.as('mateo')
    const accepted = body<CommandAccepted>(mateo.call('POST', `/console/v1/r/vm/${enc(vmWfrn('vm-demo02'))}:restart`, { input: { reason: 'fail on purpose' } }))
    w.tick(RUN_MS + 1)
    const paused = mateo.job(accepted.jobId)
    expect(paused).toMatchObject({ status: 'needs-attention', failedStep: 'apply', attention: { step: 'apply', options: ['retry', 'skip', 'abort'] } })
    expect(paused.can).toEqual(['cancel', 'retry', 'skip', 'abort'])
    // a viewer sees the pause and may do nothing with it
    expect(w.as('mia').job(accepted.jobId).can).toEqual([])

    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:skip`, { step: 'register' }).status).toBe(400)
    const skipped = mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:skip`, {})
    expect(skipped.status).toBe(202)
    expect(body<CommandAccepted>(skipped).jobId).toBe(accepted.jobId)
    expect(mateo.job(accepted.jobId)).toMatchObject({ status: 'running' })
    w.tick(SKIP_MS)
    const done = mateo.job(accepted.jobId)
    expect(done).toMatchObject({ status: 'succeeded', output: { skipped: ['apply'] } })
    expect(done.failedStep).toBeUndefined()
    expect(done.steps?.find((s) => s.id === 'apply')?.status).toBe('skipped')
    // skip and abort ask for a paused job
    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:skip`, {})).toMatchObject({ status: 409, body: { error: 'job.not-paused', detail: { status: 'succeeded' } } })
    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:abort`, {}).status).toBe(409)
    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, {})).toMatchObject({ status: 409, body: { error: 'job.not-failed', detail: { status: 'succeeded' } } })
  })

  it('fails again on a retry while the input still asks for it, and goes through once the fix changes it', () => {
    const w = world()
    const mateo = w.as('mateo')
    const accepted = body<CommandAccepted>(mateo.call('POST', `/console/v1/r/vm/${enc(vmWfrn('vm-demo02'))}:restart`, { input: { reason: 'fail on purpose' } }))
    w.tick(RUN_MS + 1)
    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, {}).status).toBe(202)
    w.tick(RUN_MS)
    expect(mateo.job(accepted.jobId)).toMatchObject({ status: 'needs-attention' })
    const fixed = mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { input: { reason: 'the host is back' } })
    expect(body<CommandAccepted>(fixed)).toMatchObject({ jobId: accepted.jobId, wfrn: vmWfrn('vm-demo02') })
    w.tick(RUN_MS)
    const done = mateo.job(accepted.jobId)
    expect(done).toMatchObject({ status: 'succeeded', input: { reason: 'the host is back' } })
    expect(done.steps?.find((s) => s.id === 'apply')).toMatchObject({ status: 'succeeded', attempt: 3 })
  })

  it('takes a sample paused before the console knew it in hand when someone steers it', () => {
    const w = world()
    const gonzalo = w.as('gonzalo')
    const sample = gonzalo.job('job_demo_attention')
    expect(sample).toMatchObject({ status: 'needs-attention', attention: { step: 'apply' } })
    expect(sample.can).toEqual(['cancel', 'retry', 'skip', 'abort'])
    expect(gonzalo.call('POST', '/console/v1/jobs/job_demo_attention:retry', {}).status).toBe(202)
    w.tick(RUN_MS)
    expect(gonzalo.job('job_demo_attention')).toMatchObject({ status: 'succeeded' })
  })
})

describe('a job that ended badly runs again as a new one', () => {
  function abortedCreate(w: ReturnType<typeof world>) {
    const dana = w.as('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'fail-77', image: 'image-demo01', size: 'small' } }))
    w.tick(RUN_MS + 1)
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:abort`, {}).status).toBe(202)
    return { dana, accepted }
  }

  it('checks the fix before anything runs: a step the job has, the name it was given', () => {
    const w = world()
    const { dana, accepted } = abortedCreate(w)
    const unknown = dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { fromStep: 'nope' })
    expect(unknown.status).toBe(400)
    expect(body<{ problems: Problem[] }>(unknown).problems[0]).toMatchObject({ code: 'step.unknown', field: '/fromStep' })
    const renamed = dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { input: { name: 'other-77' } })
    expect(body<{ problems: Problem[] }>(renamed).problems[0]).toMatchObject({ code: 'retry.name-fixed', field: '/input/name' })
    // the same name is no change
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { input: { name: 'fail-77' }, fromStep: 'validate' }).status).toBe(202)
  })

  it('puts a failed create back to pending under a new job that names the one it retries', () => {
    const w = world()
    const { dana, accepted } = abortedCreate(w)
    const retried = body<CommandAccepted>(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { input: { size: 'medium' } }))
    expect(retried.jobId).not.toBe(accepted.jobId)
    expect(retried.wfrn).toBe(accepted.wfrn)
    expect(w.data.console?.jobs[retried.jobId]).toMatchObject({ retryOf: accepted.jobId, input: { name: 'fail-77', size: 'medium' } })
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`)).state).toBe('pending')
    // a rerun does not trip the name again
    w.tick(RUN_MS)
    expect(dana.job(retried.jobId)).toMatchObject({ status: 'succeeded' })
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrn)}`)).state).toBe('active')
    // the first job may still be retried by the policies, but its machine moved on
    expect(dana.job(accepted.jobId).can).toEqual(['retry'])
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, {})).toMatchObject({ status: 409, body: { detail: { state: 'active' } } })
  })

  it('reruns the seed failed create, registering what its rerun makes', () => {
    const w = world()
    const dana = w.as('dana')
    const seed = dana.job('job_83amhygy2c')
    expect(seed).toMatchObject({ status: 'failed', failedStep: 'createVm', errorClass: 'not_found' })
    expect(seed.can).toEqual(['retry'])
    expect(seed.steps?.map((s) => s.id)).toEqual(['validate', 'approve', 'createVm', 'register'])
    // its input recorded no name, so a fix may not bring one
    expect(dana.call('POST', '/console/v1/jobs/job_83amhygy2c:retry', { input: { name: 'web-500' } }).status).toBe(400)
    const retried = body<CommandAccepted>(dana.call('POST', '/console/v1/jobs/job_83amhygy2c:retry', { input: { image: 'image-demo01', size: 'small' }, fromStep: 'createVm' }))
    expect(retried.wfrn).toMatch(/^wfrn:wf:compute:global:123456789012:vm\/vm-[a-z0-9]{8}$/)
    expect(w.data.console?.jobs[retried.jobId]?.retryOf).toBe('job_83amhygy2c')
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(retried.wfrn)}`)).state).toBe('pending')
    w.tick(RUN_MS)
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(retried.wfrn)}`))).toMatchObject({ state: 'active', attributes: { image: 'image-demo01', cpu: 2 } })
    // a viewer may not run it again
    const mia = w.as('mia')
    expect(mia.job('job_83amhygy2c').can).toEqual([])
    expect(body<{ denied: Denial }>(mia.call('POST', '/console/v1/jobs/job_83amhygy2c:retry', {})).denied).toMatchObject({ action: 'compute:create' })
  })

  it('does not run a cancelled job again', () => {
    const w = world()
    const dana = w.as('dana')
    const accepted = body<CommandAccepted>(dana.call('POST', '/console/v1/r/vm', { input: { name: 'etl-90', image: 'image-demo01', size: 'small' } }))
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:cancel`, {}).status).toBe(202)
    expect(dana.job(accepted.jobId).can).toEqual([])
    expect(dana.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, {})).toMatchObject({ status: 409, body: { error: 'job.not-failed', detail: { status: 'cancelled' } } })
  })
})

describe('deciding approvals', () => {
  it('lets an approver of the group decide someone else’s request, and says why the rest are left', () => {
    const w = world()
    const mateo = w.as('mateo')
    // vm-demo01 is a production machine, so its restart waits for compute-approvers
    const accepted = body<CommandAccepted>(mateo.call('POST', `/console/v1/r/vm/${enc(vmWfrn('vm-demo01'))}:restart`, { input: { reason: 'kernel update' } }))
    expect(mateo.job(accepted.jobId).approvals?.[0]).toMatchObject({ status: 'pending', group: 'compute-approvers', canDecide: false })
    const own = body<{ left: { reason: string }[] }>(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'approve' }))
    expect(own.left[0].reason).toMatch(/You asked for this request/)
    const gonzalo = body<{ decided: string[]; left: { reason: string }[] }>(w.as('gonzalo').call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'approve' }))
    expect(gonzalo.decided).toEqual([])
    expect(gonzalo.left[0].reason).toMatch(/compute-approvers/)
    // a viewer has no approvals:decide, though mia is in the group
    expect(w.as('mia').call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'approve' }).status).toBe(403)
    expect(w.as('dana').call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'maybe' }).status).toBe(400)

    const dana = w.as('dana')
    expect(dana.job(accepted.jobId).approvals?.[0].canDecide).toBe(true)
    const decided = body<{ decision: string; decided: string[]; left: unknown[] }>(dana.call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'approve', comment: 'go ahead' }))
    expect(decided).toMatchObject({ decision: 'approve', left: [] })
    expect(decided.decided).toHaveLength(1)
    expect(dana.job(accepted.jobId)).toMatchObject({ status: 'running', approvals: [{ status: 'approved', decidedBy: 'dana', comment: 'go ahead' }] })
    w.tick(RUN_MS)
    expect(dana.job(accepted.jobId).status).toBe('succeeded')
  })

  it('ends a rejected request at its approval step', () => {
    const w = world()
    const accepted = body<CommandAccepted>(w.as('mateo').call('POST', `/console/v1/r/vm/${enc(vmWfrn('vm-demo03'))}:restart`, { input: { reason: 'kernel update' } }))
    const dana = w.as('dana')
    dana.call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'reject', comment: 'not during the sale' })
    const rejected = dana.job(accepted.jobId)
    expect(rejected).toMatchObject({ status: 'failed', failedStep: 'approve', errorClass: 'rejected' })
    expect(rejected.steps?.find((s) => s.id === 'approve')).toMatchObject({ status: 'failed', error: { class: 'rejected' } })
    expect(rejected.steps?.find((s) => s.id === 'apply')?.status).toBe('skipped')
  })

  it('decides a sample waiting since before the console knew it', () => {
    const w = world()
    const raj = w.as('raj')
    const sample = raj.job('job_demo_approval')
    expect(sample.approvals).toEqual([expect.objectContaining<Partial<BffApproval>>({ status: 'pending', group: 'data-approvers', canDecide: true })])
    const decided = body<{ decided: string[] }>(raj.call('POST', '/console/v1/jobs/job_demo_approval/approvals:decide', { decision: 'approve' }))
    expect(decided.decided).toEqual([sample.approvals?.[0].approvalId])
    expect(raj.job('job_demo_approval').status).toBe('running')
    w.tick(RUN_MS)
    expect(raj.job('job_demo_approval').status).toBe('succeeded')
  })
})

describe('bulk runs in waves', () => {
  it('judges a batch before it runs: the verb, the rows, the rollout, the confirmation', () => {
    const w = world()
    const mateo = w.as('mateo')
    const batch = (payload: unknown) => mateo.call('POST', '/console/v1/r/vm:batch', payload)
    const vms = readyVms(w, 2)
    expect(body<{ error: string }>(batch({ verb: 'nope', items: [{ wfrn: vms[0], input: {} }] })).error).toBe('bulk.unsupported')
    expect(body<{ problems: Problem[] }>(batch({ verb: 'restart', items: [] })).problems[0]).toMatchObject({ field: '/items' })
    expect(body<{ error: string; problems: Problem[] }>(batch({ verb: 'restart', items: Array.from({ length: 501 }, () => ({ wfrn: vms[0], input: {} })) }))).toMatchObject({ error: 'batch.too-large', problems: [{ field: '/items' }] })
    expect(body<{ problems: Problem[] }>(batch({ verb: 'restart', items: [{ input: {} }] })).problems[0]).toMatchObject({ code: 'required', field: '/items/0/wfrn' })
    expect(body<{ problems: Problem[] }>(batch({ verb: 'restart', items: [{ wfrn: vms[0], input: {} }], rollout: { waves: [50, 25] } })).problems[0]).toMatchObject({ field: '/rollout/waves' })
    // mateo is an operator, so a delete is refused before anything else about it
    expect(body<{ denied: Denial }>(batch({ verb: 'delete', items: [{ wfrn: vms[0], input: {} }], defaults: { reason: 'decommissioned host' } })).denied).toMatchObject({ action: 'compute:delete' })
    const gonzalo = w.as('gonzalo')
    const unconfirmed = gonzalo.call('POST', '/console/v1/r/vm:batch', { verb: 'delete', items: [{ wfrn: vms[0], input: {} }], defaults: { reason: 'decommissioned host' } })
    expect(body<{ problems: Problem[] }>(unconfirmed).problems[0]).toMatchObject({ code: 'confirm.required', field: '/confirm' })
  })

  it('previews a delete row by row and refuses rows other resources are built from', () => {
    const w = world()
    const gonzalo = w.as('gonzalo')
    const items = [{ wfrn: vmWfrn('vm-demo01'), input: {} }, { wfrn: readyVms(w, 1)[0], input: {} }, { wfrn: vmWfrn('vm-nothing'), input: {} }]
    const preview = body<BatchPreview>(gonzalo.call('POST', '/console/v1/r/vm:batch:preview', { verb: 'delete', items, defaults: { reason: 'decommissioned host' } }))
    expect(preview.items.map((i) => i.problems.map((p) => p.code))).toEqual([['dependents.exist'], [], ['not-found']])
    expect(preview.items[0]).toMatchObject({ wfrn: vmWfrn('vm-demo01'), state: 'active' })
    expect(preview.items[0].dependents).toBeGreaterThan(0)
    expect(preview.affected.total).toBe(preview.items[0].dependents + preview.items[1].dependents)
    expect(preview.policy.decision).toBe('deny')
    expect(preview.policy.rules?.map((r) => r.id)).toEqual(expect.arrayContaining(['dependents.exist', 'not-found', 'approval.required']))

    const refused = gonzalo.call('POST', '/console/v1/r/vm:batch', { verb: 'delete', items: items.slice(0, 2), defaults: { reason: 'decommissioned host' }, confirm: true })
    expect(refused.status).toBe(409)
    const rows = body<{ error: string; detail: { rows: { wfrn: string; dependents: unknown[]; total: number }[] } }>(refused)
    expect(rows.error).toBe('dependents.exist')
    expect(rows.detail.rows).toEqual([expect.objectContaining({ wfrn: vmWfrn('vm-demo01'), total: preview.items[0].dependents })])
  })

  it('restarts 50 machines in the workflow’s three waves, ten at a time, with each wave’s approvals decided at once', () => {
    const w = world()
    const mateo = w.as('mateo')
    const vms = readyVms(w, 50)
    const res = mateo.call('POST', '/console/v1/r/vm:batch', { verb: 'restart', items: vms.map((wfrn) => ({ wfrn, input: {} })), defaults: { reason: 'monthly patch window' } })
    expect(res.status).toBe(202)
    const accepted = body<BatchAccepted>(res)
    expect(accepted).toMatchObject({ rows: 50 })

    let job = mateo.job(accepted.jobId)
    expect(job).toMatchObject({ kind: 'batch', status: 'running', title: 'VM restart on 50 virtual machines', progress: { done: 0, total: 50, failed: 0 } })
    expect(job.batch?.rollout).toEqual({ waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 })
    expect(job.batch?.waves.map((x) => x.size)).toEqual([3, 10, 37])
    // only the first wave's rows have started, and only they name their resource yet
    expect(job.batch?.rows.filter((r) => r.jobId).map((r) => r.wave)).toEqual([0, 0, 0])
    expect(job.batch?.rows.filter((r) => r.wfrn)).toHaveLength(3)
    expect(job.children).toHaveLength(3)
    expect(job.can).toEqual(['cancel'])

    // a production row waits for its approver; dana decides the wave in one call
    const waiting = () => (mateo.job(accepted.jobId).approvals ?? []).filter((a) => a.status === 'pending')
    const decideWave = () => {
      const pending = waiting()
      if (!pending.length) return
      const decided = body<{ decided: string[]; left: unknown[] }>(w.as('dana').call('POST', `/console/v1/jobs/${accepted.jobId}/approvals:decide`, { decision: 'approve' }))
      expect(decided.decided.sort()).toEqual(pending.map((a) => a.approvalId).sort())
      expect(decided.left).toEqual([])
    }
    for (let wave = 0; wave < 3; wave++) {
      decideWave()
      // ten rows run at once, a second each
      w.tick(BATCH_ROW_MS * 4 + 1)
    }
    job = mateo.job(accepted.jobId)
    expect(job).toMatchObject({ status: 'succeeded', progress: { done: 50, total: 50, failed: 0 }, output: { succeeded: 50 } })
    expect(job.batch?.waves.map((x) => x.status)).toEqual(['succeeded', 'succeeded', 'succeeded'])
    expect(job.children).toHaveLength(50)
    expect(job.children?.every((c) => c.status === 'succeeded')).toBe(true)
    expect(job.can).toEqual([])
    expect(job.steps?.find((s) => s.id === 'rollout')).toMatchObject({ status: 'succeeded', items: 50 })
  })

  it('stops when more rows fail than the rollout allows, and runs only the failed rows again', () => {
    const w = world()
    const mateo = w.as('mateo')
    // dev and test machines only, so no row waits for an approver
    const vms = w.data.resources.filter((r) => r.type === 'vm' && r.id.startsWith('vm-gen-') && r.status === 'ready' && r.attributes.environment !== 'prod').slice(0, 20).map((r) => vmWfrn(r.id))
    const accepted = body<BatchAccepted>(mateo.call('POST', '/console/v1/r/vm:batch', { verb: 'restart', items: vms.map((wfrn) => ({ wfrn, input: {} })), defaults: { reason: 'fail on purpose' }, rollout: { waves: [10, 100], maxParallel: 2 } }))
    w.tick(BATCH_ROW_MS + 1)
    let job = mateo.job(accepted.jobId)
    expect(job).toMatchObject({ status: 'failed', progress: { done: 2, total: 20, failed: 2 } })
    expect(job.batch?.stoppedReason).toMatch(/2 of 2 rows failed \(100%\), more than the 5% the rollout allows/)
    expect(job.batch?.waves.map((x) => x.status)).toEqual(['failed', 'stopped'])
    expect(job.batch?.rows.filter((r) => r.status === 'skipped')).toHaveLength(18)
    expect(job.can).toEqual(['retry'])
    expect(body<{ problems: Problem[] }>(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, { input: { reason: 'fixed' } })).problems[0]).toMatchObject({ code: 'retry.fix-unsupported', field: '/input' })

    const rerun = body<CommandAccepted>(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:retry`, {}))
    expect(rerun.jobId).not.toBe(accepted.jobId)
    expect(w.data.console?.jobs[rerun.jobId]?.retryOf).toBe(accepted.jobId)
    job = mateo.job(rerun.jobId)
    expect(job).toMatchObject({ kind: 'batch', progress: { total: 2 } })
    w.tick(BATCH_ROW_MS + 1)
    // the rows run again as they were, under the same rollout: the first fails again and stops the rest
    expect(mateo.job(rerun.jobId)).toMatchObject({ status: 'failed', progress: { done: 1, total: 2, failed: 1 } })
  })

  it('creates machines in bulk, each named before its wave goes', () => {
    const w = world()
    const dana = w.as('dana')
    const accepted = body<BatchAccepted>(dana.call('POST', '/console/v1/r/vm:batch', { verb: 'create', items: ['web-301', 'web-302'].map((name) => ({ input: { name, image: 'image-demo01', size: 'small' } })), defaults: { environment: 'dev' } }))
    expect(accepted.wfrns).toHaveLength(2)
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrns![0])}`))).toMatchObject({ name: 'web-301', state: 'pending' })
    // two rows of the workflow's waves (5%, 25%, then the rest) are two waves of one
    expect(dana.job(accepted.jobId).batch?.waves.map((x) => x.size)).toEqual([1, 1])
    w.tick(BATCH_ROW_MS * 2 + 1)
    expect(dana.job(accepted.jobId).status).toBe('succeeded')
    expect(body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(accepted.wfrns![1])}`))).toMatchObject({ name: 'web-302', state: 'active' })
    const invalid = dana.call('POST', '/console/v1/r/vm:batch', { verb: 'create', items: [{ input: { name: 'Bad Name', image: 'image-demo01', size: 'small' } }] })
    expect(body<{ problems: Problem[] }>(invalid).problems[0].field).toMatch(/^\/items\/0\/input/)
  })

  it('stops every row not yet done when someone cancels it', () => {
    const w = world()
    const mateo = w.as('mateo')
    const accepted = body<BatchAccepted>(mateo.call('POST', '/console/v1/r/vm:batch', { verb: 'restart', items: readyVms(w, 30).map((wfrn) => ({ wfrn, input: {} })), defaults: { reason: 'monthly patch window' } }))
    expect(mateo.call('POST', `/console/v1/jobs/${accepted.jobId}:cancel`, {}).status).toBe(202)
    const job = mateo.job(accepted.jobId)
    expect(job.status).toBe('cancelled')
    expect(job.batch?.stoppedReason).toBe('Cancelled by Mateo Silva')
    expect(job.batch?.rows.every((r) => r.status === 'cancelled' || r.status === 'skipped')).toBe(true)
  })

  it('replays a batch and its rerun from the journal the browser keeps', () => {
    const w = world()
    const mateo = w.as('mateo')
    const vms = readyVms(w, 12)
    const accepted = body<BatchAccepted>(mateo.call('POST', '/console/v1/r/vm:batch', { verb: 'restart', items: vms.map((wfrn) => ({ wfrn, input: {} })), defaults: { reason: 'monthly patch window' } }))
    w.tick(APPROVAL_MS + BATCH_ROW_MS * 3)
    const before = mateo.job(accepted.jobId)
    const store = new Map<string, string>()
    saveConsole(w.data, { setItem: (k, v) => void store.set(k, v) })
    const fresh = buildMockData()
    expect(restoreConsole(fresh, { getItem: (k) => store.get(k) ?? null }, w.now())).toBe(true)
    const again = createMockServer(fresh, memorySessionStore(), w.now)
    again.handle({ method: 'GET', url: '/auth/login?login_hint=mateo', headers: {} })
    const replayed = body<BffJobDetail>(again.handle({ method: 'GET', url: `/console/v1/jobs/${accepted.jobId}`, headers: {} }))
    expect(replayed.status).toBe(before.status)
    expect(replayed.progress).toEqual(before.progress)
    expect(replayed.batch?.rows.map((r) => [r.jobId, r.status])).toEqual(before.batch?.rows.map((r) => [r.jobId, r.status]))
  })
})
