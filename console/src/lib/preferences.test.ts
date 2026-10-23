import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { buildMockData } from '../mock/data'
import { createMockServer, csrfTokenOf, memorySessionStore, type MockServer } from '../mock/server'
import { resetSessionState, setSessionState, type Session } from './session'
import { columnsOf, currentPreferences, loadPreferences, resetPreferences, updatePreferences, viewsOf, withColumns, withView, withoutView } from './preferences'
import type { Preferences, SavedView } from './types'

/* The module's own logic over the mock BFF: load, change, queue, and a 409 that merges. */

const VIEW: SavedView = { id: 'v_1', name: 'Active', filters: [{ property: 'status', operator: '=', value: 'active' }], sort: '-created', createdAt: '2026-10-23T08:00:00Z' }

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

let server: MockServer
let who = ''
function serve(method: string, url: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf('gonzalo'), ...headers }, body })
  return { status: res.status, json: () => res.body as unknown }
}

beforeEach(() => {
  storage.clear()
  resetPreferences()
  server = createMockServer(buildMockData(), memorySessionStore(), () => Date.parse('2026-10-23T10:00:00Z'))
  expect(serve('GET', '/auth/login?login_hint=gonzalo').status).toBe(200)
  const session = serve('GET', '/console/v1/session').json() as Session
  setSessionState({ status: 'signed-in', session })
  who = session.user.userId
  // the module talks to the BFF through fetch: route it to the mock server directly
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://console.test')
    const method = (init?.method ?? 'GET').toUpperCase()
    const headers: Record<string, string> = {}
    new Headers(init?.headers).forEach((v, k) => {
      headers[k.toLowerCase()] = v
    })
    const res = server.handle({ method, url: url.pathname + url.search, headers, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    return new Response(res.body === undefined ? null : JSON.stringify(res.body), { status: res.status, headers: { 'Content-Type': 'application/json', ...res.headers } })
  }
})
afterEach(() => {
  resetSessionState()
})

describe('preferences', () => {
  it('loads the BFF copy once and keeps it locally', async () => {
    await loadPreferences(who)
    await updatePreferences(withColumns('compute/vm', ['name', 'status']))
    const stored = JSON.parse(storage.get('console-preferences') ?? '{}') as { owner: string; prefs: Preferences }
    expect(stored.owner).toBe(who)
    expect(stored.prefs.columns?.['compute/vm']).toEqual(['name', 'status'])
    expect(stored.prefs.version).toBe(1)
    const theirs = serve('GET', '/console/v1/preferences').json() as Preferences
    expect(theirs.columns?.['compute/vm']).toEqual(['name', 'status'])
  })

  it('queues changes and sends each whole', async () => {
    await loadPreferences(who)
    const a = updatePreferences(withView('compute/vm', VIEW))
    const b = updatePreferences(withView('compute/vm', { ...VIEW, id: 'v_2', name: 'Big' }))
    await Promise.all([a, b])
    const theirs = serve('GET', '/console/v1/preferences').json() as Preferences
    expect(viewsOf(theirs, 'compute/vm').map((v) => v.name)).toEqual(['Active', 'Big'])
    expect(theirs.version).toBe(2)
  })

  it('takes the BFF copy and applies the change again after a 409', async () => {
    await loadPreferences(who)
    await updatePreferences(withView('compute/vm', VIEW))
    // another tab moves the version on
    const put = serve('PUT', '/console/v1/preferences', { columns: { 'compute/vm': ['name'] }, savedViews: { 'compute/vm': [VIEW] }, version: 1 })
    expect(put.status).toBe(200)
    await updatePreferences(withoutView('compute/vm', 'v_1'))
    const theirs = serve('GET', '/console/v1/preferences').json() as Preferences
    expect(viewsOf(theirs, 'compute/vm')).toEqual([])
    expect(columnsOf(theirs, 'compute/vm')).toEqual(['name'])
    expect(theirs.version).toBe(3)
  })

  it('drops a local copy another person left on this browser', async () => {
    await loadPreferences('someone-else')
    // their change stays local: the BFF is unreachable
    const online = globalThis.fetch
    globalThis.fetch = async () => {
      throw new TypeError('Failed to fetch')
    }
    await updatePreferences(withColumns('compute/vm', ['name']))
    expect(columnsOf(currentPreferences(), 'compute/vm')).toEqual(['name'])
    globalThis.fetch = online
    // the next person to sign in here never sees it
    await loadPreferences(who)
    expect(currentPreferences()).toEqual({ version: 0 })
    expect(JSON.parse(storage.get('console-preferences') ?? '{}')).toEqual({ owner: who, prefs: { version: 0 } })
  })

  it('refuses a stale version and a malformed object', () => {
    expect(serve('PUT', '/console/v1/preferences', { version: 4 }).status).toBe(409)
    expect(serve('PUT', '/console/v1/preferences', []).status).toBe(400)
    expect(serve('PUT', '/console/v1/preferences', { theme: 'sepia' }).status).toBe(400)
    expect(serve('PUT', '/console/v1/preferences', { columns: ['name'] }).status).toBe(400)
  })

  it('helpers read and write per type', () => {
    expect(columnsOf({}, 'compute/vm')).toBeNull()
    expect(columnsOf({ columns: { 'compute/vm': [] } }, 'compute/vm')).toBeNull()
    const p = withColumns('compute/vm', null)({ columns: { 'compute/vm': ['name'], 'data/database': ['name'] } })
    expect(p.columns).toEqual({ 'data/database': ['name'] })
    const withTwo = withView('compute/vm', { ...VIEW, id: 'v_2', name: 'Big' })(withView('compute/vm', VIEW)({}))
    expect(viewsOf(withTwo, 'compute/vm').map((v) => v.id)).toEqual(['v_1', 'v_2'])
    expect(withoutView('compute/vm', 'v_1')(withoutView('compute/vm', 'v_2')(withTwo)).savedViews).toEqual({})
  })
})
