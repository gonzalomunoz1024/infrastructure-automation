import { describe, expect, it } from 'vitest'
import { sha256Tagged } from './sha256'
import { canonicalJson, chainEvents, checkChain, checkPackHashes, digestHash, digestRoot, eventHash, problemWords, toTrailEntry, type ResourceEvent } from './trail'

/* The trail's chain: the canonical form, the links, and every way a check finds a change. */

const W = 'wfrn:wf:compute:global:123456789012:vm/vm-x'
function event(version: number, type: ResourceEvent['type'], at: string, data: Record<string, unknown> = {}): ResourceEvent {
  return { specVersion: '1.0', dataVersion: 1, id: `evt_${version}`, key: `${type}:${W}:${version}`, type, at, aggregate: 'resource', aggregateId: W, actor: 'dana', context: { accountId: '123456789012', region: 'global', wfrn: W }, data: { version, state: 'active', ...data } }
}
const log = () => chainEvents([event(1, 'ResourceRequested', '2026-10-01T10:00:00Z', { state: 'pending', input: { cpu: 2 } }), event(2, 'ProvisioningSucceeded', '2026-10-01T10:01:00Z', { attributes: { cpu: 2 } }), event(3, 'UpdateRequested', '2026-10-02T09:00:00Z', { changes: { cpu: 4 } })])

describe('the canonical form', () => {
  it('sorts keys at every level, drops undefined and writes no whitespace', () => {
    expect(canonicalJson({ b: 1, a: { d: [3, { z: 1, y: 2 }], c: 'x' }, u: undefined })).toBe('{"a":{"c":"x","d":[3,{"y":2,"z":1}]},"b":1}')
    expect(canonicalJson('é')).toBe('"é"')
  })
})

describe('the chain', () => {
  it('links each event to the one before and hashes its stored form', () => {
    const chained = log()
    expect(chained[0].previous).toBeUndefined()
    expect(chained[1].previous).toBe(chained[0].hash)
    expect(chained[2].previous).toBe(chained[1].hash)
    const { hash: _hash, ...rest } = chained[2]
    expect(chained[2].hash).toBe(sha256Tagged(canonicalJson(rest)))
    expect(toTrailEntry(chained[1]).chain).toEqual({ previous: chained[0].hash, hash: chained[1].hash })
    expect(checkChain(chained)).toEqual({ valid: true, entries: 3, chained: 3, brokenAt: null, reason: null, head: chained[2].hash })
  })

  it('breaks where an event was changed, taken out or put in', () => {
    const changed = log()
    changed[1] = { ...changed[1], data: { ...changed[1].data, attributes: { cpu: 16 } } }
    expect(checkChain(changed)).toMatchObject({ valid: false, brokenAt: 2, reason: 'its stored form does not match its hash', head: changed[0].hash })
    const missing = log().filter((e) => e.data.version !== 2)
    expect(checkChain(missing)).toMatchObject({ valid: false, brokenAt: 3, reason: 'version 2 is missing' })
    // rehashed after the change, it no longer links to the event before
    const rehashed = log()
    const moved = { ...rehashed[1], previous: 'sha256:' + '0'.repeat(64) }
    rehashed[1] = { ...moved, hash: eventHash(moved) }
    expect(checkChain(rehashed)).toMatchObject({ valid: false, brokenAt: 2, reason: 'it does not link to the event before it' })
  })

  it('checks a pack’s digests: their counts, roots, seals and links', () => {
    const events = log()
    const day = (from: string, to: string, inside: typeof events, previous?: string) => {
      const body = { id: `123456789012:global:${from}`, accountId: '123456789012', region: 'global', from, to, count: inside.length, firstEvent: `${W}#${inside[0].data.version}`, lastEvent: `${W}#${inside[inside.length - 1].data.version}`, root: digestRoot(inside.map((e) => e.hash!)), ...(previous ? { previous } : {}) }
      return { ...body, hash: digestHash(body), keyId: 'k', signature: '', signedAt: to }
    }
    const first = day('2026-10-01T00:00:00.000Z', '2026-10-02T00:00:00.000Z', events.slice(0, 2))
    const second = day('2026-10-02T00:00:00.000Z', '2026-10-03T00:00:00.000Z', events.slice(2), first.hash)
    expect(checkPackHashes({ events, digests: [first, second] })).toMatchObject({ valid: true, events: 3, resources: 1, digests: 2, unsealed: 0, problems: [] })
    expect(checkPackHashes({ events, digests: [first] })).toMatchObject({ valid: true, unsealed: 1 })
    const unlinked = checkPackHashes({ events, digests: [first, { ...second, previous: undefined, hash: digestHash({ ...second, previous: undefined }) }] })
    expect(unlinked.problems.map((p) => p.kind)).toEqual(['digest.unlinked'])
    const rerooted = checkPackHashes({ events, digests: [{ ...first, root: second.root }, second] })
    expect(rerooted.problems.map((p) => p.kind)).toEqual(['digest.root', 'digest.altered'])
    expect(problemWords(rerooted.problems[0])).toContain('do not add up')
  })
})
