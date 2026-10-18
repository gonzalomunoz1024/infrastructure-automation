import { describe, expect, it } from 'vitest'
import type { JobStep, JobSummary } from '../../lib/types'
import { valueDiff } from '../../lib/value-diff'
import { comparisonSummary, lastSuccessfulBefore, pairSteps } from './replay-compare'

/*
 * The canvas replay's comparison with the last successful run: which run, how this run's steps
 * pair with that run's (by step, its key in the workflow, then by name), and the output fields
 * that differ, computed the way the backend's time travel does.
 */

const step = (id: string, name: string, status: JobStep['status'], output?: unknown): JobStep => ({ id, name, type: 'provider', status, attempt: 1, output })

const job = (id: string, status: JobSummary['status'], startedAt: string, extra: Partial<JobSummary> = {}): JobSummary => ({
  id,
  workflow: 'vm-restart',
  title: 'VM restart',
  version: '1.2.0',
  status,
  requester: 'gonzalo',
  channel: 'catalog',
  startedAt,
  kind: 'single',
  ...extra,
})

describe('the run a replay compares with', () => {
  const failed = job('job_1042', 'failed', '2026-10-16T09:00:00Z')
  const jobs = [
    job('job_1050', 'succeeded', '2026-10-17T09:00:00Z'),
    failed,
    job('job_1041', 'succeeded', '2026-10-15T14:00:00Z'),
    job('job_1030', 'succeeded', '2026-10-10T08:00:00Z'),
    job('job_1039', 'failed', '2026-10-15T20:00:00Z'),
    job('job_1040', 'succeeded', '2026-10-15T18:00:00Z', { workflow: 'create-vm' }),
    job('job_1038', 'succeeded', '2026-10-15T19:00:00Z', { parentId: 'job_900' }),
    job('job_1037', 'succeeded', '2026-10-15T19:30:00Z', { kind: 'batch' }),
  ]

  it('is the last successful run of the workflow that started before it, of the same kind and level', () => {
    expect(lastSuccessfulBefore(failed, jobs)?.id).toBe('job_1041')
  })

  it('is never the job itself: the last successful run compares with the one before it', () => {
    expect(lastSuccessfulBefore(jobs[0], jobs)?.id).toBe('job_1041')
    expect(lastSuccessfulBefore(jobs[2], jobs)?.id).toBe('job_1030')
  })

  it('is none when no successful run came earlier', () => {
    expect(lastSuccessfulBefore(jobs[3], jobs)).toBeNull()
    expect(lastSuccessfulBefore(failed, [])).toBeNull()
  })
})

describe('pairing the steps of two runs', () => {
  const compared = [
    step('checkVmExists', 'Check VM exists', 'succeeded', { id: 'vm-0007', powerState: 'poweredOn' }),
    step('approval', 'Approve restart', 'succeeded', { approved: true, by: 'bob' }),
    step('restart', 'Restart with Ansible', 'succeeded', { ok: 4, changed: 2 }),
    step('validate', 'Wait for power on', 'succeeded', { powerState: 'poweredOn' }),
    step('notifyDone', 'Tell the channel', 'succeeded', { sent: true }),
  ]
  const current = [
    step('checkVmExists', 'Check VM exists', 'succeeded', { id: 'vm-1042', powerState: 'poweredOn', cpu: 4 }),
    step('approval', 'Approve restart', 'succeeded', { approved: true, by: 'bob' }),
    step('restart', 'Restart with Ansible', 'failed'),
    step('notifyOwner', 'Tell the on-call', 'succeeded', { sent: true }),
  ]

  it('pairs a step with the same step of the other run and diffs its output', () => {
    const pairs = pairSteps(current, compared)
    expect(pairs.map((p) => [p.id, p.change])).toEqual([
      ['checkVmExists', 'changed'],
      ['approval', 'same'],
      ['restart', 'changed'],
      ['validate', 'removed'],
      ['notifyDone', 'removed'],
      ['notifyOwner', 'added'],
    ])
    expect(pairs[0].diff).toEqual([
      { field: 'output.id', before: 'vm-0007', after: 'vm-1042' },
      { field: 'output.cpu', before: undefined, after: 4 },
    ])
    // a status that changed comes first, then what the output lost
    expect(pairs[2].diff).toEqual([
      { field: 'status', before: 'succeeded', after: 'failed' },
      { field: 'output.ok', before: 4, after: undefined },
      { field: 'output.changed', before: 2, after: undefined },
    ])
    expect(pairs[1].diff).toEqual([])
    expect(comparisonSummary(pairs)).toBe('2 steps differ, 1 added, 2 removed')
  })

  it('shows a step missing on one side as added or removed, each run keeping its order', () => {
    const pairs = pairSteps([step('a', 'A', 'succeeded'), step('new', 'New', 'succeeded'), step('c', 'C', 'succeeded')], [step('gone', 'Gone', 'succeeded'), step('a', 'A', 'succeeded'), step('b', 'B', 'succeeded'), step('c', 'C', 'succeeded')])
    expect(pairs.map((p) => `${p.change}:${p.id}`)).toEqual(['removed:gone', 'same:a', 'removed:b', 'added:new', 'same:c'])
    expect(pairs.find((p) => p.id === 'new')?.compared).toBeUndefined()
    expect(pairs.find((p) => p.id === 'b')?.current).toBeUndefined()
  })

  it('pairs a step whose key was renamed by its name, and never takes a step another key claims', () => {
    const pairs = pairSteps([step('restartVm', 'Restart with Ansible', 'succeeded', { ok: 4 }), step('restart', 'Restart again', 'succeeded', { ok: 3 })], [step('restart', 'Restart with Ansible', 'succeeded', { ok: 3 })])
    // "restart" still exists in this run, so the renamed step does not take it by name
    expect(pairs.map((p) => `${p.change}:${p.id}`)).toEqual(['added:restartVm', 'same:restart'])
    const renamed = pairSteps([step('restartVm', 'Restart with Ansible', 'succeeded', { ok: 4 })], [step('restart', 'Restart with Ansible', 'succeeded', { ok: 3 })])
    expect(renamed.map((p) => `${p.change}:${p.id}`)).toEqual(['changed:restartVm'])
    expect(renamed[0].diff).toEqual([{ field: 'output.ok', before: 3, after: 4 }])
  })

  it('says nothing differs when every step matches', () => {
    expect(comparisonSummary(pairSteps(compared, compared))).toBe('nothing differs')
    expect(pairSteps([], [])).toEqual([])
  })
})

describe('the field diff', () => {
  it('walks objects and lists by dotted path, like the backend', () => {
    expect(valueDiff({ vm: { name: 'web-1', disks: [10, 20] } }, { vm: { name: 'web-2', disks: [10, 40, 80] } }, 'output')).toEqual([
      { field: 'output.vm.name', before: 'web-1', after: 'web-2' },
      { field: 'output.vm.disks[1]', before: 20, after: 40 },
      { field: 'output.vm.disks[2]', before: undefined, after: 80 },
    ])
  })

  it('takes a missing field and null as the same, and an empty object or list as a value', () => {
    expect(valueDiff({ a: null }, {}, 'output')).toEqual([])
    expect(valueDiff({ items: [] }, { items: [] }, 'output')).toEqual([])
    expect(valueDiff({ items: [] }, { items: {} }, 'output')).toEqual([{ field: 'output.items', before: [], after: {} }])
    // a list that filled up says what it holds now, not that it is no longer empty
    expect(valueDiff({ items: [] }, { items: ['vm-1'] }, 'output')).toEqual([{ field: 'output.items[0]', before: undefined, after: 'vm-1' }])
    expect(valueDiff(undefined, {}, 'output')).toEqual([{ field: 'output', before: undefined, after: {} }])
    expect(valueDiff('up', 'down', 'output')).toEqual([{ field: 'output', before: 'up', after: 'down' }])
    expect(valueDiff(undefined, undefined, 'output')).toEqual([])
  })
})
