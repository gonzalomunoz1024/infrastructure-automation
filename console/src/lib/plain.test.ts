import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { explain, explainLine, explainProblem, lowerFirst } from './plain'

/* Plain language (WP 3.7): a failed call reads as what happened, why, and what to do next. */

describe('explaining a failed call', () => {
  it('reads the server’s code first, keeping its detail and the correlation id', () => {
    const x = explain(new ApiError(409, { error: 'resource.busy', detail: 'A request is already changing this vm; wait for it to finish', correlationId: 'c-123' }))
    expect(x.title).toBe('Another change is in flight')
    expect(x.message).toContain('the platform takes one at a time')
    expect(x.message).toContain('A request is already changing this vm')
    expect(x.next).toBe('Wait for it to finish, then try again.')
    expect(x).toMatchObject({ code: 'resource.busy', correlationId: 'c-123', retryable: true })
  })

  it('falls back to the status, titled by what the person was doing', () => {
    const x = explain(new ApiError(503, { error: 'unavailable' }), 'Create the VM')
    expect(x.title).toBe('Could not create the VM')
    expect(x.message).toBe('It asked the console to come back later.')
    expect(x.next).toBe('Try again in a moment.')
    expect(x.retryable).toBe(true)
    expect(explain(new ApiError(418, { error: 'teapot' })).title).toBe('The request was not accepted')
    expect(explain(new ApiError(599, null)).title).toBe('The platform had a problem')
  })

  it('says how long to wait when the rate is exceeded', () => {
    expect(explain(new ApiError(429, { error: 'rate.limited' }, undefined, 7)).next).toBe('Wait 7 seconds and try again.')
    expect(explain(new ApiError(429, { error: 'rate.limited' }, undefined, 1)).next).toBe('Wait 1 second and try again.')
  })

  it('names a step-up as one, whatever words its error uses', () => {
    const x = explain(new ApiError(403, { error: 'step-up authentication required for compute:delete', detail: { stepUp: true, action: 'compute:delete', login: '/auth/login?stepUp=true' } }))
    expect(x.title).toBe('Sign in again for this')
    expect(x.next).toBe('Sign in again and come back.')
  })

  it('explains a delete held back by what depends on it, and a deleted resource', () => {
    const held = explain(new ApiError(409, { error: 'dependents.exist', detail: { dependents: [] }, problems: [{ level: 'error', code: 'dependents.exist', message: '2 resources are built from this vm; delete them first' }] }))
    expect(held.title).toBe('Other resources are built from this one')
    expect(held.problems).toHaveLength(1)
    // an object detail is data for the page, never words
    expect(held.message).not.toContain('[object Object]')
    expect(explain(new ApiError(410, { error: 'resource.deleted', detail: { deletedAt: '2026-10-23T10:00:00Z' } })).title).toBe('This resource was deleted')
  })

  it('explains the monitoring and rightsizing refusals, waiting as long as a busy read asks', () => {
    expect(explain(new ApiError(404, { error: 'metrics.notSupported', detail: 'Volumes have no metrics read: their provider publishes none' }))).toMatchObject({ title: 'This type keeps no metrics', message: 'There is nothing to chart, and no alarm can watch it. Volumes have no metrics read: their provider publishes none' })
    expect(explain(new ApiError(429, { error: 'observe.budget' }, undefined, 12))).toMatchObject({ title: 'Too many metrics reads for now', next: 'Wait 12 seconds and try again.', retryable: true })
    expect(explain(new ApiError(409, { error: 'recommendation.closed', detail: 'This recommendation is applied already, through request job_1' }), 'Resize web-02')).toMatchObject({ title: 'This recommendation is already settled', retryable: false })
    expect(explain(new ApiError(409, { error: 'recommendation.notApplicable' }))).toMatchObject({ title: 'The console cannot carry this out', retryable: false })
    expect(explain(new ApiError(409, { error: 'recommendation.changing' }))).toMatchObject({ next: 'Wait for it to finish, then apply the recommendation again.', retryable: true })
  })

  it('explains a call that never reached a server, a cancelled one and a plain error', () => {
    expect(explain(new ApiError(0, null)).title).toBe('The platform did not answer')
    expect(explain(new DOMException('stopped', 'AbortError'))).toMatchObject({ title: 'Cancelled', retryable: true })
    expect(explain(new Error('boom'), 'Load this vm')).toMatchObject({ title: 'Could not load this vm', message: 'boom', retryable: true })
  })

  it('folds an explanation onto one line', () => {
    expect(explainLine(new ApiError(409, { error: 'resource.busy' }))).toBe('A request is already changing this resource; the platform takes one at a time. Wait for it to finish, then try again.')
  })
})

describe('explaining a field’s problem', () => {
  it('names the field in words when the server’s line is terse', () => {
    expect(explainProblem({ level: 'error', code: 'required', field: '/input/size', message: 'required' }, (f) => (f === 'size' ? 'Size' : undefined))).toBe('Size is required.')
    expect(explainProblem({ level: 'error', code: 'minLength', field: '/reason', message: 'short' })).toBe('Reason is too short.')
    expect(explainProblem({ level: 'error', code: 'immutable', field: '/input/keepSnapshotDays', message: '' })).toBe('Keep snapshot days was set when the resource was created and cannot be changed.')
    expect(explainProblem({ level: 'error', code: 'maximum', field: '/input/image/create/maxIOPS', message: 'max' })).toBe('Max IOPS is above the largest allowed value.')
  })

  it('keeps a server’s sentence, ending it with a full stop', () => {
    expect(explainProblem({ level: 'error', code: 'pattern', field: '/name', message: 'must start with a letter and use lowercase letters, digits and dashes' })).toBe('must start with a letter and use lowercase letters, digits and dashes.')
  })

  it('lowers only the first letter', () => {
    expect(lowerFirst('Create VM')).toBe('create VM')
    expect(lowerFirst('')).toBe('')
  })
})
