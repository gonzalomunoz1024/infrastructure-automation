import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { CACHE_PREFIX } from './api'
import { signOut } from './bff'
import { RECENT_STORAGE_KEY, clearRecent, parseRecent, pushRecent, recentOf } from './recent'
import { resetSessionState, setSessionState, type Session } from './session'

/* Recent visits belong to the person who made them, and leave the browser with them. */

const storage = new Map<string, string>()
beforeAll(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (k: string) => storage.get(k) ?? null,
      setItem: (k: string, v: string) => void storage.set(k, String(v)),
      removeItem: (k: string) => void storage.delete(k),
      clear: () => storage.clear(),
      key: (i: number) => [...storage.keys()][i] ?? null,
      get length() {
        return storage.size
      },
    },
  })
})

const person = (userId: string): Session => ({
  user: { userId, email: `${userId}@example.com`, name: userId },
  account: { accountId: '123456789012', name: 'Compute', roles: ['operator'] },
  region: 'global',
  csrfToken: `csrf-${userId}`,
  expiresAt: '2026-10-24T00:00:00Z',
})
const signIn = (userId: string) => setSessionState({ status: 'signed-in', session: person(userId) })
const stored = () => parseRecent(JSON.parse(storage.get(RECENT_STORAGE_KEY) ?? 'null'))

beforeEach(() => {
  clearRecent()
  storage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetSessionState()
})

describe('recent visits', () => {
  it('are kept under the user id of the person who made them', () => {
    signIn('dana')
    pushRecent({ kind: 'type', href: '/console/compute/vm', label: 'Virtual machines' })
    expect(stored()).toMatchObject({ owner: 'dana', items: [{ href: '/console/compute/vm' }] })
  })

  it('are never shown to anyone else, nor to nobody', () => {
    const list = { owner: 'dana', items: [{ kind: 'resource' as const, href: '/x', label: 'web-01', at: 1 }] }
    expect(recentOf(list, 'dana')).toHaveLength(1)
    expect(recentOf(list, 'mia')).toEqual([])
    expect(recentOf(list, null)).toEqual([])
    expect(recentOf({ owner: '', items: list.items }, '')).toEqual([])
  })

  it('start again for the next person: their first visit replaces the last person’s list', () => {
    signIn('dana')
    pushRecent({ kind: 'resource', href: '/a', label: 'secret-project-db' })
    signIn('mia')
    pushRecent({ kind: 'type', href: '/b', label: 'Virtual machines' })
    expect(stored()).toEqual({ owner: 'mia', items: [expect.objectContaining({ href: '/b' })] })
  })

  it('are not kept while nobody is signed in', () => {
    pushRecent({ kind: 'page', href: '/c', label: 'Settings' })
    expect(storage.has(RECENT_STORAGE_KEY)).toBe(false)
  })

  it('read an older copy kept for nobody in particular as empty', () => {
    expect(parseRecent([{ href: '/a', label: 'a', kind: 'page', at: 1 }])).toBeNull()
    expect(parseRecent({ owner: 'dana', items: [{ href: '/a', label: 'a' }, { nope: true }] })?.items).toHaveLength(1)
    expect(parseRecent('dana')).toBeNull()
  })

  it('leave the browser at sign-out, with the cached descriptors', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(new Response(null, { status: 204 })))
    signIn('dana')
    pushRecent({ kind: 'resource', href: '/a', label: 'web-01' })
    storage.set(`${CACHE_PREFIX}dana/123456789012/global:/console/v1/types`, JSON.stringify({ etag: 'x', body: [], at: 1 }))
    await signOut()
    expect(stored()).toEqual({ owner: '', items: [] })
    expect([...storage.keys()].some((k) => k.startsWith(CACHE_PREFIX))).toBe(false)
  })
})
