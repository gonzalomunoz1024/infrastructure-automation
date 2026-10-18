import { describe, expect, it } from 'vitest'
import { buildProviderDocument, initialState, stepIssues, type WizardState } from '../pages/ProviderWizardPage'

function sdk(worker: Partial<WizardState['sdk']>): WizardState {
  const s = initialState()
  return { ...s, type: 'sdk', title: 'DNS records', name: 'dns-records', owner: 'team-platform', auth: { ...s.auth, type: 'none' }, sdk: { ...s.sdk, ...worker } }
}

describe('the provider wizard for an sdk provider', () => {
  it('leaves the worker block out when the defaults will do', () => {
    const spec = buildProviderDocument(sdk({})).spec as Record<string, unknown>

    expect(spec.worker).toBeUndefined()
    expect(spec.taskQueue).toBeUndefined()
    expect(stepIssues(sdk({}), 1)).toEqual([])
  })

  it('writes the task queue and the heartbeat timeout under worker', () => {
    const spec = buildProviderDocument(sdk({ taskQueue: ' dns-workers ', heartbeatTimeout: '30s' })).spec as Record<string, unknown>

    expect(spec.worker).toEqual({ taskQueue: 'dns-workers', heartbeatTimeout: '30s' })
  })

  it('says what is wrong with a task queue or a heartbeat timeout it cannot use', () => {
    expect(stepIssues(sdk({ taskQueue: 'dns workers' }), 1)).toEqual(['The task queue is letters, digits, dots, dashes and underscores.'])
    expect(stepIssues(sdk({ heartbeatTimeout: 'half a minute' }), 1)).toEqual(['The heartbeat timeout is a duration like 30s or 2m.'])
    expect(stepIssues(sdk({ heartbeatTimeout: 'PT30S' }), 1)).toEqual([])
  })
})
