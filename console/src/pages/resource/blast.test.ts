import { describe, expect, it } from 'vitest'
import { ApiError } from '../../lib/api'
import type { BlastRadius, Dependent } from '../../lib/types'
import { heldBackByDependents } from './ActionDrawer'
import { dependentsByDepth, distanceLabel, otherAccountsOf, radiusLine, radiusOfRefusal, radiusSummary } from './BlastRadius'

/*
 * The blast radius in words (WP 4.5): what a delete would leave without what it was built from,
 * nearest first, the other accounts counted and never named, and the radius a late 409 carries.
 */

const dep = (name: string, depth?: number, type = 'volume'): Dependent => ({ wfrn: `wfrn:wf:storage:global:123456789012:${type}/${name}`, type, name, state: 'active', id: name, depth })
const radius = (over: Partial<BlastRadius>): BlastRadius => ({ total: 0, direct: 0, byAccount: [], dependents: [], edges: [], depth: 3, ...over })

describe('the blast radius in words', () => {
  it('says what a delete would leave behind, and how many depend on it directly', () => {
    expect(radiusSummary({ total: 2, direct: 2 }, 'web-01')).toBe('Deleting web-01 would leave 2 resources without what they were built from: 2 depend on it directly.')
    expect(radiusSummary({ total: 5, direct: 1 }, 'web-01', 'delete')).toBe('Deleting web-01 would leave 5 resources without what they were built from: 1 depends on it directly and 4 more through them.')
    expect(radiusSummary({ total: 1, direct: 1 }, 'orders-db')).toBe('Deleting orders-db would leave 1 resource without what it was built from: 1 depends on it directly.')
    expect(radiusSummary({ total: 1200, direct: 0 }, 'image-01')).toBe('Deleting image-01 would leave 1,200 resources without what they were built from.')
    expect(radiusSummary({ total: 3, direct: 3 }, 'web-01', 'reimage')).toBe('Running reimage on web-01 would change what 3 resources were built from: 3 depend on it directly.')
  })

  it('reads nothing depending on it as good news', () => {
    expect(radiusSummary({ total: 0, direct: 0 }, 'web-09')).toBe('Deleting web-09 leaves no other resource without what it was built from.')
    expect(radiusLine({ total: 0, byAccount: [] })).toBe('Nothing else depends on it.')
  })

  it('gives the confirmation one line, with what lives in other accounts', () => {
    expect(radiusLine({ total: 2, byAccount: [{ accountId: '123456789012', count: 2, current: true }] })).toBe('2 resources depend on it.')
    expect(radiusLine({ total: 3, byAccount: [{ accountId: '123456789012', count: 1, current: true }, { accountId: '210987654321', count: 2, current: false }] })).toBe('3 resources depend on it, 2 of them in another account.')
    expect(radiusLine({ total: 1, byAccount: [{ accountId: '123456789012', count: 1, current: false }] })).toBe('1 resource depends on it, 1 of them in another account.')
  })

  it('groups the named dependents by how far they are, nearest first', () => {
    const groups = dependentsByDepth([dep('c', 3), dep('a', 1), dep('b', 2), dep('d'), dep('e', 2)])
    expect(groups.map((g) => [g.label, g.items.map((d) => d.name)])).toEqual([
      ['Directly', ['a', 'd']],
      ['Two steps away', ['b', 'e']],
      ['Three steps away', ['c']],
    ])
    expect(distanceLabel(5)).toBe('Five steps away')
  })

  it('counts the other accounts and never the session’s own', () => {
    expect(otherAccountsOf({ byAccount: [{ accountId: '123456789012', count: 2, current: true }, { accountId: '210987654321', count: 1, current: false }, { accountId: '333333333333', count: 0 }] })).toEqual([{ accountId: '210987654321', count: 1 }])
  })
})

describe('a delete held back by what depends on it', () => {
  it('reads the deny rule from the preview’s policy', () => {
    expect(heldBackByDependents({ policy: { decision: 'deny', rules: [{ id: 'dependents.exist', result: 'deny', message: '2 resources are built from this' }] } })).toBe(true)
    expect(heldBackByDependents({ policy: { decision: 'deny', rules: [{ id: 'quota.resources', result: 'deny', message: 'used up' }] } })).toBe(false)
    expect(heldBackByDependents({ policy: { decision: 'needs-approval', rules: [{ id: 'dependents.exist', result: 'pass', message: '' }] } })).toBe(false)
    expect(heldBackByDependents(undefined)).toBe(false)
  })

  it('takes the radius a late 409 carries, and leaves out how many are direct when it cannot tell', () => {
    const named = new ApiError(409, { error: 'dependents.exist', detail: { dependents: [dep('volume-demo01', 1), dep('shop-prod', 1, 'environment')], total: 2, byAccount: [{ accountId: '123456789012', count: 2, current: true }] } })
    expect(radiusOfRefusal(named)).toEqual(radius({ total: 2, direct: 2, byAccount: [{ accountId: '123456789012', count: 2, current: true }], dependents: [dep('volume-demo01', 1), dep('shop-prod', 1, 'environment')], depth: 1 }))
    const elsewhere = new ApiError(409, { error: 'dependents.exist', detail: { dependents: [], total: 1, byAccount: [{ accountId: '123456789012', count: 1, current: false }] } })
    expect(radiusOfRefusal(elsewhere)).toMatchObject({ total: 1, direct: 0, dependents: [] })
    expect(radiusOfRefusal(new ApiError(409, { error: 'resource.busy' }))).toBeUndefined()
    expect(radiusOfRefusal(new Error('boom'))).toBeUndefined()
  })
})
