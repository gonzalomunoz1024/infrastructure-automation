import { describe, expect, it } from 'vitest'
import { buildDirectory, decideAccessRequest, createAccessRequest, rolesOf, userOf } from './directory'
import { MOCK_DIRECTORY_KEY, restoreDirectory, saveDirectory } from './install'

/* A storage for node. */
function storage() {
  const map = new Map<string, string>()
  return { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v), map }
}

describe('the directory the browser keeps', () => {
  it('saves after a write and restores over a fresh directory, so an approval survives a reload', () => {
    const store = storage()
    const dir = buildDirectory()
    const mia = userOf(dir, '123456789012', 'mia')!
    const request = createAccessRequest(dir, '123456789012', mia, { action: 'compute:create', reason: 'I build the team machines' })
    decideAccessRequest(dir, '123456789012', request.requestId, 'approve', 'gonzalo')
    saveDirectory(dir, store)
    expect(store.map.has(MOCK_DIRECTORY_KEY)).toBe(true)

    const fresh = buildDirectory()
    expect(rolesOf(fresh, '123456789012', 'mia')).toEqual(['viewer'])
    expect(restoreDirectory(fresh, store)).toBe(true)
    expect(rolesOf(fresh, '123456789012', 'mia')).toContain('operator')
    expect(fresh.accessRequests['123456789012'].map((r) => r.status)).toContain('approved')
    expect(fresh.counters.requests).toBe(dir.counters.requests)
  })

  it('ignores nothing kept, or something that is not a directory', () => {
    const empty = storage()
    expect(restoreDirectory(buildDirectory(), empty)).toBe(false)
    const broken = storage()
    broken.setItem(MOCK_DIRECTORY_KEY, '{"people": "no"}')
    expect(restoreDirectory(buildDirectory(), broken)).toBe(false)
    const garbage = storage()
    garbage.setItem(MOCK_DIRECTORY_KEY, 'not json')
    expect(restoreDirectory(buildDirectory(), garbage)).toBe(false)
  })
})
