import { describe, expect, it } from 'vitest'
import { diffSnapshots, mergePatch, replayAt, replayTo, stopsOf } from './timeTravel'
import type { ResourceEvent } from './trail'

/* A resource rebuilt from its events at any moment, what changed between two, and the way back. */

const W = 'wfrn:wf:compute:global:123456789012:vm/vm-x'
let v = 0
function e(type: ResourceEvent['type'], at: string, state: ResourceEvent['data']['state'], data: Record<string, unknown> = {}): ResourceEvent {
  v++
  return { specVersion: '1.0', dataVersion: 1, id: `evt_${v}`, key: `${type}:${W}:${v}`, type, at, aggregate: 'resource', aggregateId: W, context: { accountId: '123456789012', region: 'global', wfrn: W }, data: { version: v, state, ...data } }
}
v = 0
const events = [
  e('ResourceRequested', '2026-10-01T10:00:00Z', 'pending', { name: 'batch-01', input: { cpu: 4, memoryGb: 16 } }),
  e('ProvisioningSucceeded', '2026-10-01T10:01:00Z', 'active', { attributes: { cpu: 4, memoryGb: 16, image: 'img' } }),
  e('Observed', '2026-10-01T11:00:00Z', 'active', { status: 'found', observed: { cpu: 4, memoryGb: 16 }, observedAt: '2026-10-01T11:00:00Z' }),
  e('Tagged', '2026-10-02T09:00:00Z', 'active', { set: { team: 'batch' }, removed: [] }),
  e('UpdateRequested', '2026-10-20T10:00:00Z', 'updating', { changes: { cpu: 8 } }),
  e('ProvisioningSucceeded', '2026-10-20T10:00:08Z', 'active', { attributes: { cpu: 8, memoryGb: 16, image: 'img' } }),
  e('DriftDetected', '2026-10-21T10:00:00Z', 'active', { findingId: 'f1', fields: [{ path: '/memoryGb', expected: 16, observed: 32 }] }),
  e('Tagged', '2026-10-22T09:00:00Z', 'active', { set: {}, removed: ['team'] }),
]

describe('replay', () => {
  it('rebuilds what was asked for, what the provider reported, the tags and drift at any moment', () => {
    expect(replayAt(events, '2026-09-30T00:00:00Z')).toBeNull()
    const pending = replayAt(events, '2026-10-01T10:00:30Z')!
    expect(pending).toMatchObject({ version: 1, state: 'pending', name: 'batch-01', attributes: { cpu: 4, memoryGb: 16 } })
    const tagged = replayAt(events, '2026-10-10T00:00:00Z')!
    expect(tagged).toMatchObject({ version: 4, state: 'active', attributes: { cpu: 4, memoryGb: 16, image: 'img' }, observed: { cpu: 4, memoryGb: 16 }, tags: { team: 'batch' } })
    const updating = replayAt(events, '2026-10-20T10:00:05Z')!
    expect(updating).toMatchObject({ state: 'updating', changes: { cpu: 8 }, attributes: { cpu: 4 } })
    const latest = replayAt(events, Date.parse('2026-10-23T00:00:00Z'))!
    expect(latest).toMatchObject({ version: 8, attributes: { cpu: 8 }, tags: {}, drift: { findingId: 'f1' } })
    expect(latest.changes).toBeUndefined()
    expect(replayTo(events, 2)?.attributes).toEqual({ cpu: 4, memoryGb: 16, image: 'img' })
  })

  it('says what changed between two moments, leaf by leaf', () => {
    const a = replayAt(events, '2026-10-10T00:00:00Z')
    const b = replayAt(events, '2026-10-23T00:00:00Z')
    expect(diffSnapshots(a, b)).toEqual([
      { section: 'desired', path: '/cpu', before: 4, after: 8 },
      { section: 'tags', path: '/team', before: 'batch', after: undefined },
    ])
    expect(diffSnapshots(null, a)[0]).toEqual({ section: 'state', path: '', before: undefined, after: 'active' })
  })

  it('plans the way back as a JSON merge patch', () => {
    expect(mergePatch({ cpu: 8, memoryGb: 16, extra: 'x' }, { cpu: 4, memoryGb: 16 })).toEqual({ cpu: 4, extra: null })
    expect(mergePatch({ net: { a: 1, b: 2 } }, { net: { a: 1, b: 3 } })).toEqual({ net: { b: 3 } })
    expect(mergePatch({ cpu: 4 }, { cpu: 4 })).toEqual({})
  })

  it('stops at every event, oldest first', () => {
    expect(stopsOf([...events].reverse()).map((s) => s.version)).toEqual([1, 2, 3, 4, 5, 6, 7, 8])
  })
})
