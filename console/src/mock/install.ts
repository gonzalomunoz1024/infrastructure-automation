import type { Directory } from './directory'
import { replayConsole, type ConsoleCommand } from './commands'
import type { MockData } from './data'
import { createMockServer, memorySessionStore, nextMockEvent, type MockServer, type MockSession, type MockSessionStore } from './server'

/*
 * Mock mode (VITE_CONSOLE_MOCK=1): fetch answers the platform (/api, /actuator), the BFF (/auth,
 * /console) and the demo identity provider (/demo-idp) from the mock server in this browser, and
 * EventSource plays the platform feed, so the shell and the e2e suite run with no backend at
 * all. The session the BFF would keep behind its cookie lives in localStorage, so a reload keeps
 * you signed in, and so does the identity directory (people, groups, keys, access requests), so
 * what an admin changed is still there after a reload or a sign-in as someone else. Everything
 * else goes to the network as usual.
 */

const LIVE_EVERY_MS = 2_000
export const MOCK_SESSION_KEY = 'console-mock-session'
/** versioned: a browser that kept an older directory's shape starts again from the seed */
export const MOCK_DIRECTORY_KEY = 'console-mock-directory-v2'
export const MOCK_JOURNAL_KEY = 'console-mock-journal'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

/** The directory the browser kept, over the fresh one; a write saves it again. */
export function restoreDirectory(dir: Directory, storage: Pick<Storage, 'getItem'> = localStorage): boolean {
  try {
    const raw = storage.getItem(MOCK_DIRECTORY_KEY)
    if (!raw) return false
    const saved = JSON.parse(raw) as Partial<Directory>
    if (!saved || typeof saved !== 'object' || !Array.isArray(saved.people) || typeof saved.groups !== 'object' || typeof saved.policies !== 'object' || Array.isArray(saved.policies)) return false
    Object.assign(dir, saved)
    return true
  } catch {
    return false
  }
}

export function saveDirectory(dir: Directory, storage: Pick<Storage, 'setItem'> = localStorage) {
  try {
    storage.setItem(MOCK_DIRECTORY_KEY, JSON.stringify(dir))
  } catch {
    /* storage unavailable: the directory lives for this page */
  }
}

/** The journal of what the console created or changed, replayed onto the fresh dataset so a reload keeps it. */
export function restoreConsole(data: MockData, storage: Pick<Storage, 'getItem'> = localStorage, now = Date.now()): boolean {
  try {
    const raw = storage.getItem(MOCK_JOURNAL_KEY)
    if (!raw) return false
    const saved = JSON.parse(raw) as unknown
    if (!Array.isArray(saved)) return false
    const journal = saved.filter((c): c is ConsoleCommand => !!c && typeof c === 'object' && typeof (c as ConsoleCommand).kind === 'string' && typeof (c as ConsoleCommand).at === 'number')
    replayConsole(data, journal, now)
    return journal.length > 0
  } catch {
    return false
  }
}

export function saveConsole(data: MockData, storage: Pick<Storage, 'setItem'> = localStorage) {
  try {
    storage.setItem(MOCK_JOURNAL_KEY, JSON.stringify((data.console?.journal ?? []).slice(-200)))
  } catch {
    /* storage unavailable: the changes live for this page */
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

function headersOf(init: RequestInit | undefined, input: RequestInfo | URL): Record<string, string> {
  const out: Record<string, string> = {}
  const h = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined))
  h.forEach((v, k) => {
    out[k.toLowerCase()] = v
  })
  return out
}

/** The session store the browser keeps across reloads, as a cookie would. */
export function storageSessionStore(): MockSessionStore {
  const memory = memorySessionStore()
  try {
    const raw = localStorage.getItem(MOCK_SESSION_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as MockSession
      if (parsed && typeof parsed === 'object' && typeof parsed.userId === 'string' && typeof parsed.accountId === 'string') memory.set(parsed)
    }
  } catch {
    /* nothing kept */
  }
  return {
    get: memory.get,
    set: (next) => {
      memory.set(next)
      try {
        if (next) localStorage.setItem(MOCK_SESSION_KEY, JSON.stringify(next))
        else localStorage.removeItem(MOCK_SESSION_KEY)
      } catch {
        /* storage unavailable: the session lives for this page */
      }
    },
  }
}

/** The feed as an EventSource: the backlog after `after`, then a live event every couple of seconds. */
class MockEventSource extends EventTarget {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readonly CONNECTING = 0
  readonly OPEN = 1
  readonly CLOSED = 2
  readonly url: string
  readonly withCredentials = false
  readyState = 0
  onopen: ((ev: Event) => void) | null = null
  onmessage: ((ev: MessageEvent) => void) | null = null
  onerror: ((ev: Event) => void) | null = null
  private timer: ReturnType<typeof setTimeout> | undefined
  private ticker: ReturnType<typeof setInterval> | undefined

  constructor(url: string | URL, server: MockServer) {
    super()
    this.url = String(url)
    const after = Number(new URL(this.url, 'http://mock.local').searchParams.get('after') ?? '') || 0
    this.timer = setTimeout(() => {
      if (this.readyState === MockEventSource.CLOSED) return
      this.readyState = MockEventSource.OPEN
      const open = new Event('open')
      this.onopen?.(open)
      this.dispatchEvent(open)
      const backlog = after > 0 ? server.data.events.filter((e) => e.seq > after) : server.data.events.slice(-5)
      for (const e of backlog) this.emit(e)
      this.ticker = setInterval(() => this.emit(nextMockEvent(server.data)), LIVE_EVERY_MS)
    }, 40)
  }

  /** Named after its type, as the platform's stream names its events (`event: job.started`), so only a listener for that name hears it. */
  private emit(e: { id: string; type: string }) {
    if (this.readyState !== MockEventSource.OPEN) return
    this.dispatchEvent(new MessageEvent(e.type, { data: JSON.stringify(e), lastEventId: e.id }))
  }

  close() {
    this.readyState = MockEventSource.CLOSED
    if (this.timer) clearTimeout(this.timer)
    if (this.ticker) clearInterval(this.ticker)
  }
}

declare global {
  interface Window {
    __consoleMock?: MockServer
  }
}

const MOCKED = /^\/(api\/|actuator|console\/|auth\/|demo-idp\/)/

async function bodyOf(init: RequestInit | undefined, input: RequestInfo | URL): Promise<unknown> {
  if (init?.body !== undefined && init.body !== null) return typeof init.body === 'string' ? init.body : undefined
  if (input instanceof Request && input.method !== 'GET' && input.method !== 'HEAD') {
    try {
      return await input.clone().text()
    } catch {
      return undefined
    }
  }
  return undefined
}

export function installMock(server: MockServer = createMockServer(undefined, storageSessionStore())): MockServer {
  restoreDirectory(server.data.directory)
  restoreConsole(server.data)
  const realFetch = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(raw, window.location.href)
    if (!MOCKED.test(url.pathname)) return realFetch(input, init)
    await delay(20 + Math.random() * 60)
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    const res = server.handle({ method, url: url.pathname + url.search, headers: headersOf(init, input), body: await bodyOf(init, input) })
    if (!SAFE_METHODS.has(method) && url.pathname.startsWith('/console/') && res.status < 400) {
      saveDirectory(server.data.directory)
      saveConsole(server.data)
    }
    const empty = res.status === 204 || res.status === 304 || res.body === undefined
    return new Response(empty ? null : JSON.stringify(res.body), { status: res.status, headers: { ...(empty ? {} : { 'Content-Type': 'application/json' }), ...res.headers } })
  }
  const Source = function (this: MockEventSource, url: string | URL) {
    return new MockEventSource(url, server)
  } as unknown as typeof EventSource
  Object.assign(Source, { CONNECTING: 0, OPEN: 1, CLOSED: 2 })
  window.EventSource = Source
  window.__consoleMock = server
  return server
}
