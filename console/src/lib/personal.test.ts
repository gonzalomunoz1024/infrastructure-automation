import { describe, expect, it } from 'vitest'
import { hrefOfFavourite, hrefOfVisit, pinnedOf, withPinned } from './personal'

/* Where a visit and a favourite lead, and pinning a type in the preferences. */

const WFRN = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'

describe('recent and favourites', () => {
  it('leads a type to its list and a resource to its page, by its wfrn', () => {
    expect(hrefOfVisit({ kind: 'type', key: 'compute/vm' })).toBe('/console/compute/vm')
    expect(hrefOfVisit({ kind: 'resource', key: WFRN })).toBe(`/console/compute/vm/${encodeURIComponent(WFRN)}`)
    expect(hrefOfVisit({ kind: 'type', key: 'vm' })).toBeNull()
    expect(hrefOfVisit({ kind: 'resource', key: 'vm-demo01' })).toBeNull()
    expect(hrefOfFavourite({ wfrn: WFRN, type: 'vm' })).toBe(`/console/compute/vm/${encodeURIComponent(WFRN)}`)
  })

  it('pins a type once, at the end, keeps 50, and unpins it', () => {
    const pinned = withPinned('compute/vm', true)({ pinnedTypes: ['data/database', 'compute/vm'], theme: 'dark' })
    expect(pinned).toEqual({ pinnedTypes: ['data/database', 'compute/vm'], theme: 'dark' })
    expect(withPinned('platform/image', true)(pinned).pinnedTypes).toEqual(['data/database', 'compute/vm', 'platform/image'])
    expect(withPinned('compute/vm', false)(pinned).pinnedTypes).toEqual(['data/database'])
    const many = { pinnedTypes: Array.from({ length: 50 }, (_, i) => `s/t${i}`) }
    expect(withPinned('s/new', true)(many).pinnedTypes).toHaveLength(50)
    expect(pinnedOf({})).toEqual([])
    expect(pinnedOf({ pinnedTypes: ['a/b', 'a/b'] })).toEqual(['a/b'])
  })
})
