import { describe, expect, it } from 'vitest'
import type { JobExplanation } from '../lib/explain'
import { decodePrefill } from '../lib/prefill'
import type { BffJobDetail, CommandAccepted, JobSummary } from '../lib/types'
import { APPROVAL_MS, RUN_MS } from './commands'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The failure explainer in the mock BFF (WP 9.2): rules over the platform's error classes, the
 * copilot for what they cannot read, who may run it again, and how often the same failure
 * happened in the account in the last 30 days. A create the mock's provider fails pauses on its
 * apply first (phase 4), so most of these explain a paused request.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const SEED_FAILED = 'job_83amhygy2c'
const SEED_PAUSED = 'job_demo_attention'
/** the tags the account's policy requires at create (phase 4) */
const TAGS = { owner: 'team-compute', 'cost-centre': 'cc-1042', 'data-class': 'internal', environment: 'dev' }

function bff(person: string, start = NOW) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person) }, body })
  const explain = (jobId: string) => call('GET', `/console/v1/jobs/${jobId}/explanation`)
  /** a VM create whose name makes the mock's provider fail it, run until it pauses on the failed apply (a read settles the clock's jobs) */
  const failingCreate = (name: string) => {
    const accepted = call('POST', '/console/v1/r/vm', { input: { name, image: 'image-demo01', size: 'small', environment: 'dev' }, tags: TAGS }).body as CommandAccepted
    settle()
    expect(call('GET', `/console/v1/jobs/${accepted.jobId}`).body).toMatchObject({ status: 'needs-attention', attention: { step: 'apply' } })
    return accepted
  }
  /** lets every job in flight reach its end */
  const settle = () => {
    now += APPROVAL_MS + RUN_MS + 1
    call('GET', '/console/v1/session')
  }
  return { data, call, explain, failingCreate, settle, tick: (ms: number) => void (now += ms) }
}

const explanation = (res: { status: number; body?: unknown }) => {
  expect(res.status).toBe(200)
  return res.body as JobExplanation
}

describe('explaining a failed request', () => {
  it('reads a missing image from its error class, and offers the newest live image from the failed step', () => {
    const x = explanation(bff('gonzalo').explain(SEED_FAILED))
    expect(x).toMatchObject({ jobId: SEED_FAILED, status: 'failed', source: 'rules', cause: { class: 'not_found', title: 'The image it names does not exist' }, failedStep: { id: 'createVm' }, retry: { allowed: true } })
    // the seed's input was not recorded, so the fix is a likely one, not a certain one
    expect(x.fix).toEqual({ summary: expect.stringContaining('ubuntu-24.04-cis-l1-2026-10-01'), input: { image: 'image-demo01' }, fromStep: 'createVm', confidence: 'medium' })
    expect(x.similar).toEqual({ count: 0 })
  })

  it('is sure of the fix when the recorded input names an image that is gone, and never proposes a new name', () => {
    const dana = bff('dana')
    const accepted = dana.failingCreate('web-fail-1')
    Object.assign(dana.data.jobs.find((j) => j.id === accepted.jobId)!, { errorClass: 'not_found' })
    dana.data.console!.jobs[accepted.jobId].input.image = 'image-gone'
    const x = explanation(dana.explain(accepted.jobId))
    expect(x.status).toBe('needs-attention')
    expect(x.cause.detail).toContain('"image-gone"')
    expect(x.fix).toMatchObject({ input: { image: 'image-demo01' }, fromStep: 'apply', confidence: 'high' })
    expect(x.fix?.input).not.toHaveProperty('name')
  })

  it('sends a create that met a clash to a new create with the same input and no name', () => {
    const dana = bff('dana')
    const accepted = dana.failingCreate('web-fail-2')
    Object.assign(dana.data.jobs.find((j) => j.id === accepted.jobId)!, { errorClass: 'conflict' })
    const x = explanation(dana.explain(accepted.jobId))
    expect(x.fix).toMatchObject({ confidence: 'medium' })
    expect(x.fix?.input).toBeUndefined()
    const link = x.links?.[0]
    expect(link?.title).toBe('Create a virtual machine with another name')
    expect(link?.url).toMatch(/^#\/console\/compute\/vm\/create\?prefill=/)
    expect(decodePrefill(new URLSearchParams(link!.url.split('?')[1]).get('prefill') ?? undefined)).toEqual({ image: 'image-demo01', size: 'small', environment: 'dev' })
  })

  it('reads the provider’s own failure, counts the same failure in the account, and finds the rerun that fixed one', () => {
    const dana = bff('dana')
    const first = dana.failingCreate('web-fail-3')
    let x = explanation(dana.explain(first.jobId))
    expect(x).toMatchObject({ source: 'rules', cause: { class: 'provider', title: 'The provider failed while making the change' }, fix: { fromStep: 'apply', confidence: 'medium' } })
    // the step as the request page draws it: its name, its attempts and the provider's words
    const drawn = (dana.call('GET', `/console/v1/jobs/${first.jobId}`).body as BffJobDetail).steps?.find((st) => st.status === 'failed')
    expect(drawn?.error?.message).toBeTruthy()
    expect(x.failedStep).toEqual({ id: drawn?.id, name: drawn?.name, attempt: drawn?.attempt, message: drawn?.error?.message })
    expect(x.fix?.input).toBeUndefined()
    expect(x.similar?.count).toBe(0)

    const second = dana.failingCreate('web-fail-4')
    expect(explanation(dana.explain(second.jobId)).similar?.count).toBe(1)

    // a rerun on the same resource that succeeded, as the phase 4 retry leaves it
    const failed = dana.data.jobs.find((j) => j.id === first.jobId)!
    const rerun: JobSummary = { ...failed, id: 'job_rerun00001', status: 'succeeded', requester: 'dana', startedAt: '2026-10-23T10:05:00Z', endedAt: '2026-10-23T10:06:00Z', errorClass: undefined, failedStep: undefined }
    dana.data.jobs.unshift(rerun)
    x = explanation(dana.explain(second.jobId))
    expect(x.similar).toEqual({ count: 1, lastFixed: { jobId: 'job_rerun00001', by: 'dana', at: '2026-10-23T10:06:00Z' } })
  })

  it('tells a viewer they may not run it again, with the layer that decided', () => {
    const x = explanation(bff('mia').explain(SEED_FAILED))
    expect(x.retry).toMatchObject({ allowed: false, denied: { layer: 'console-iam', action: 'compute:create' } })
    expect(x.fix?.input).toEqual({ image: 'image-demo01' })
  })

  it('points to a create when the account has no live image to use instead', () => {
    const x = explanation(bff('raj').explain(SEED_FAILED))
    expect(x.fix).toMatchObject({ confidence: 'medium' })
    expect(x.fix?.input).toBeUndefined()
    expect(x.links).toEqual([{ title: 'Create an image', url: expect.stringMatching(/^#\/console\/[a-z]+\/image\/create$/) }])
  })
})

describe('explaining a request that waits on a failed step', () => {
  it('reads a paused step the way the request page draws it, with the provider’s class', () => {
    const x = explanation(bff('gonzalo').explain(SEED_PAUSED))
    expect(x).toMatchObject({ status: 'needs-attention', source: 'rules', cause: { class: 'provider' }, failedStep: { id: 'apply', name: 'Apply the change' }, fix: { fromStep: 'apply', confidence: 'medium' } })
    expect(x.fix?.input).toBeUndefined()
  })

  it('lets the copilot answer what no rule reads, leaving the way on to the person', () => {
    const gonzalo = bff('gonzalo')
    Object.assign(gonzalo.data.jobs.find((j) => j.id === SEED_PAUSED)!, { errorClass: 'client_error' })
    const x = explanation(gonzalo.explain(SEED_PAUSED))
    expect(x).toMatchObject({ status: 'needs-attention', source: 'assistant', cause: { class: 'client_error', title: 'A step stopped and waits for a decision' }, fix: { confidence: 'low' } })
    expect(x.fix?.input).toBeUndefined()
    expect(x.fix?.fromStep).toBeUndefined()
  })

  it('reads a paused step’s error class like a failure’s (phase 4 pauses a failed step first)', () => {
    const gonzalo = bff('gonzalo')
    Object.assign(gonzalo.data.jobs.find((j) => j.id === SEED_PAUSED)!, { errorClass: 'timeout', failedStep: 'apply' })
    const x = explanation(gonzalo.explain(SEED_PAUSED))
    expect(x).toMatchObject({ status: 'needs-attention', source: 'rules', cause: { class: 'timeout', title: 'The provider did not answer in time' }, failedStep: { id: 'apply' }, fix: { fromStep: 'apply', confidence: 'high' } })
  })
})

describe('the fix, sent through the phase 4 retry', () => {
  it('resumes a paused create from its step, after which the request has nothing left to explain', () => {
    const dana = bff('dana')
    const paused = dana.failingCreate('web-fail-5')
    const fix = explanation(dana.explain(paused.jobId)).fix!
    const retried = dana.call('POST', `/console/v1/jobs/${paused.jobId}:retry`, { fromStep: fix.fromStep })
    expect(retried).toMatchObject({ status: 202, body: { jobId: paused.jobId } })
    dana.settle()
    expect(dana.explain(paused.jobId)).toMatchObject({ status: 409, body: { error: 'job.not-failed', detail: { status: 'succeeded' } } })
  })

  it('reruns the seed’s create with the image it suggests, and the rerun shows as the last fix', () => {
    const gonzalo = bff('gonzalo')
    const fix = explanation(gonzalo.explain(SEED_FAILED)).fix!
    const rerun = gonzalo.call('POST', `/console/v1/jobs/${SEED_FAILED}:retry`, { input: fix.input, fromStep: fix.fromStep }).body as CommandAccepted
    expect(rerun.jobId).not.toBe(SEED_FAILED)
    gonzalo.settle()
    const x = explanation(gonzalo.explain(SEED_FAILED))
    expect(x.similar).toMatchObject({ count: 0, lastFixed: { jobId: rerun.jobId, by: 'gonzalo' } })
  })
})

describe('the last time the same failure was fixed', () => {
  it('finds a rerun that names the failed job, even with no resource to match on', () => {
    const gonzalo = bff('gonzalo')
    const failed = gonzalo.data.jobs.find((j) => j.id === SEED_FAILED)!
    const rerun: JobSummary = { ...failed, id: 'job_rerun00002', status: 'succeeded', requester: 'dana', startedAt: '2026-10-21T09:00:00Z', endedAt: '2026-10-21T09:02:00Z', errorClass: undefined, failedStep: undefined }
    gonzalo.data.jobs.unshift(rerun)
    gonzalo.data.console!.jobs[rerun.id] = { type: 'vm', verb: 'create', consoleRequestId: 'req-rerun', input: { image: 'image-demo01' }, approvals: [], retryOf: SEED_FAILED } as never
    expect(explanation(gonzalo.explain(SEED_FAILED)).similar).toEqual({ count: 0, lastFixed: { jobId: 'job_rerun00002', by: 'dana', at: '2026-10-21T09:02:00Z' } })
  })
})

describe('what has no explanation', () => {
  it('answers 409 for a request that did not fail, 404 for one it does not know, and reads only', () => {
    const gonzalo = bff('gonzalo')
    const succeeded = gonzalo.data.jobs.find((j) => j.status === 'succeeded')!
    const res = gonzalo.explain(succeeded.id)
    expect(res.status).toBe(409)
    expect(res.body).toMatchObject({ error: 'job.not-failed', detail: { status: 'succeeded' } })
    expect(gonzalo.explain('job_nothing').status).toBe(404)
    expect(gonzalo.call('POST', `/console/v1/jobs/${SEED_FAILED}/explanation`).status).toBe(405)
  })
})
