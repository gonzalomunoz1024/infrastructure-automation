import { describe, expect, it } from 'vitest'
import type { BffJobDetail } from '../../lib/types'
import { childrenOf, decidedLine, deciderOf, defaultFromStep, failureOf, retryFixOf, rowCounts } from './parts'

const steps = [
  { id: 'validate', name: 'Validate', type: 'validate', status: 'succeeded' },
  { id: 'apply', name: 'Apply', type: 'call', status: 'failed', error: { class: 'provider.timeout', message: 'The hypervisor did not answer in 60 seconds.', retryable: true } },
  { id: 'register', name: 'Register', type: 'register', status: 'pending' },
] as BffJobDetail['steps']

const job = (over: Partial<BffJobDetail> = {}): BffJobDetail => ({ jobId: 'job_1', workflow: 'restart-vm', status: 'failed', failedStep: 'apply', steps, ...over })

describe('what went wrong with a request', () => {
  it('says the failing step’s own words, its class and whether running it again can help', () => {
    expect(failureOf(job())).toEqual({ step: 'apply', errorClass: 'provider.timeout', message: 'The hypervisor did not answer in 60 seconds.', retryable: true })
    expect(failureOf(job({ status: 'needs-attention' }))?.message).toBe('The hypervisor did not answer in 60 seconds.')
  })

  it('names the step when the platform kept no message, and says nothing while all is well', () => {
    expect(failureOf(job({ steps: [] }))?.message).toBe('The step apply failed.')
    expect(failureOf(job({ status: 'running', failedStep: undefined }))).toBeNull()
    expect(failureOf(job({ status: 'succeeded', failedStep: undefined }))).toBeNull()
  })

  it('names the approver who said no, and what they said', () => {
    const rejected = job({ errorClass: 'rejected', failedStep: 'approve', approvals: [{ approvalId: 'apr_1', status: 'rejected', decidedBy: 'dana', comment: 'Not during the freeze' }] })
    expect(failureOf(rejected)).toMatchObject({ step: 'approve', message: 'Dana said no to this request.', retryable: false, rejected: { by: 'dana', comment: 'Not during the freeze' } })
    const timedOut = job({ errorClass: 'rejected', approvals: [{ approvalId: 'apr_2', status: 'rejected', decidedBy: 'approver:compute-approvers' }] })
    expect(failureOf(timedOut)?.message).toBe('An approver of compute-approvers said no to this request.')
  })

  it('counts a bulk run’s failed rows and points at them', () => {
    const bulk = job({ kind: 'batch', status: 'failed', progress: { done: 50, total: 50, failed: 3 }, batch: { waves: [], rows: [] } })
    expect(failureOf(bulk)).toEqual({ message: '3 of 50 rows failed. The rows below say why.', retryable: true })
    expect(failureOf({ ...bulk, status: 'succeeded', progress: { done: 50, total: 50, failed: 0 } })).toBeNull()
  })
})

describe('the step a retry starts from', () => {
  it('is the paused step, the failed step, or the first step of a request that was rolled back', () => {
    expect(defaultFromStep(job({ status: 'needs-attention', attention: { step: 'apply', options: ['retry'], since: '2026-10-23T10:00:00Z' } }))).toBe('apply')
    expect(defaultFromStep(job())).toBe('apply')
    expect(defaultFromStep(job({ failedStep: undefined }))).toBe('apply')
    expect(defaultFromStep(job({ status: 'rolled-back' }))).toBe('validate')
  })
})

describe('the fix a retry sends', () => {
  const original = { memoryGb: 8, reason: 'fail the first time', tags: { team: 'web' } }

  it('sends only the fields the person changed', () => {
    expect(retryFixOf(original, { ...original, reason: 'patch window' }, 'apply', 'apply')).toEqual({ input: { reason: 'patch window' } })
    expect(retryFixOf(original, { ...original, tags: { team: 'data' } }, undefined, 'apply')).toEqual({ input: { tags: { team: 'data' } } })
  })

  it('names the step only when it is not where the retry starts anyway', () => {
    expect(retryFixOf(original, original, 'validate', 'apply')).toEqual({ fromStep: 'validate' })
    expect(retryFixOf(original, original, 'apply', 'apply')).toBeUndefined()
  })
})

describe('one decision on a request’s approvals', () => {
  it('says how many it decided and why the rest were left', () => {
    expect(decidedLine({ decision: 'approve', decided: ['apr_1'], left: [] })).toBe('Approved one approval.')
    expect(decidedLine({ decision: 'reject', decided: ['apr_1', 'apr_2'], left: [{ approvalId: 'apr_3', reason: 'Your own request' }] })).toBe('Rejected 2 approvals. One was left: your own request.')
    expect(decidedLine({ decision: 'approve', decided: [], left: [{ approvalId: 'a', reason: 'Not in data-approvers' }, { approvalId: 'b', reason: 'Not in data-approvers' }] })).toBe('Nothing was decided. 2 were left: not in data-approvers.')
  })

  it('reads the platform’s own approver as the group it stands for', () => {
    expect(deciderOf('approver:compute-approvers')).toBe('an approver of compute-approvers')
    expect(deciderOf('raj')).toBe('raj')
  })
})

describe('a bulk run’s rows', () => {
  it('counts them by status, the live ones first', () => {
    const rows = ['succeeded', 'failed', 'running', 'succeeded', 'pending', 'odd'].map((status, index) => ({ index, status, wave: 0 }))
    expect(rowCounts({ waves: [], rows })).toEqual([
      { status: 'running', count: 1 },
      { status: 'pending', count: 1 },
      { status: 'succeeded', count: 2 },
      { status: 'failed', count: 1 },
      { status: 'odd', count: 1 },
    ])
  })

  it('pairs a request’s child jobs with the step timeline by their ids', () => {
    expect(childrenOf(job({ children: [{ jobId: 'job_2', workflow: 'restart-vm', status: 'running' }] }))).toEqual([expect.objectContaining({ id: 'job_2', title: 'restart-vm', parentId: 'job_1', kind: 'single' })])
    expect(childrenOf(job())).toBeUndefined()
  })
})
