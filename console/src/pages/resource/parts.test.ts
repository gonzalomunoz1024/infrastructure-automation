import { describe, expect, it } from 'vitest'
import { buildMockData } from '../../mock/data'
import { composeTypes, type ConsoleAction, type ConsoleType } from '../../lib/descriptors'
import type { Finding } from '../../lib/types'
import { startValue } from './ActionDrawer'
import { jobTitle, tombstoneOf } from './JobBanner'
import { driftStatusOf } from './tabs'

/*
 * The action form starts from what the resource already is; a deleted resource is read from its
 * tombstone; a request in flight is named by its workflow; drift is read from the contract's
 * boolean, the open finding and when a scan last read the resource.
 */

const data = buildMockData()
const vm = composeTypes(data.resourceTypes, data.catalog).find((t) => t.name === 'vm') as ConsoleType
const action = (verb: string) => vm.actions.find((a) => a.verb === verb) as ConsoleAction

describe('the action form’s first value', () => {
  it('starts from the defaults, then the resource’s own values where they fit the field', () => {
    expect(startValue(action('restart').input, 'vmId', {})).toEqual({ environment: 'dev' })
    expect(startValue(action('restart').input, 'vmId', { environment: 'prod', size: 'small' })).toEqual({ environment: 'prod' })
  })

  it('skips a value the field would refuse: outside its choices, or of another kind', () => {
    expect(startValue(action('restart').input, 'vmId', { environment: 'staging' })).toEqual({ environment: 'dev' })
    expect(startValue(action('delete').input, 'vmId', { keepSnapshotDays: '14' })).toEqual({ keepSnapshotDays: 7 })
    expect(startValue(action('delete').input, 'vmId', { keepSnapshotDays: 14 })).toEqual({ keepSnapshotDays: 14 })
  })

  it('never fills the subject, nor another resource picker', () => {
    expect(startValue(action('patch').input, 'vmId', { vmId: 'vm-demo01', severity: 'low' })).toEqual({ severity: 'low' })
    expect(startValue(vm.create!.input, undefined, { image: 'img-1', name: 'web-01' })).not.toHaveProperty('image')
  })
})

describe('the tombstone of a deleted resource', () => {
  it('reads it from the BFF’s detail or the state service’s top level', () => {
    const t = { wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-gen-001', deletedAt: '2026-10-23T10:00:00Z', tombstoneUntil: '2026-11-22T10:00:00Z', lastVersion: 4 }
    expect(tombstoneOf({ error: 'resource.deleted', detail: t })).toEqual(t)
    expect(tombstoneOf({ error: 'resource.deleted', ...t })).toEqual(t)
  })

  it('has nothing to say about a body without one', () => {
    expect(tombstoneOf({ error: 'resource.deleted', detail: 'gone' })).toBeUndefined()
    expect(tombstoneOf(null)).toBeUndefined()
    expect(tombstoneOf({ detail: { deletedAt: 3 } })).toBeUndefined()
  })
})

describe('the request banner’s words', () => {
  it('names a job by the workflow it runs: the type’s create, one of its actions, else the job’s title', () => {
    expect(jobTitle(vm, { workflow: vm.create!.workflow })).toBe(vm.create!.title)
    expect(jobTitle(vm, { workflow: action('restart').workflow, title: 'VM restart api-01' })).toBe(action('restart').title)
    expect(jobTitle(vm, { workflow: 'update-vm', title: 'Update virtual machine api-01' })).toBe('Update virtual machine api-01')
    expect(jobTitle(vm, { workflow: 'something-else' })).toBe('The last change')
  })
})

describe('drift, as the page says it', () => {
  const finding: Finding = { findingId: 'drift_1', wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo02', status: 'open', severity: 'warning', fields: [{ path: 'memoryGb', expected: 16, observed: 32 }], openedAt: '2026-10-22T21:40:00Z' }

  it('is drifted when the BFF says so or a finding is open', () => {
    expect(driftStatusOf({ drift: true })).toBe('drifted')
    expect(driftStatusOf({ drift: false, finding })).toBe('drifted')
  })

  it('is in sync only once a scan has read the resource, else not checked yet', () => {
    expect(driftStatusOf({ drift: false, observedAt: '2026-10-23T10:00:00Z' })).toBe('in-sync')
    expect(driftStatusOf({ drift: false, finding: { ...finding, status: 'closed' }, observedAt: '2026-10-23T10:00:00Z' })).toBe('in-sync')
    expect(driftStatusOf({ drift: false })).toBe('unknown')
    expect(driftStatusOf(undefined)).toBe('unknown')
  })
})
