import { createMockServer, nextMockEvent, type MockServer } from './server'

/*
 * Mock mode (VITE_CONSOLE_MOCK=1): fetch answers /api and /actuator from the mock server in this
 * browser, and EventSource plays the platform feed, so the shell and the e2e suite run with no
 * platform at all. Everything else goes to the network as usual.
 */

const LIVE_EVERY_MS = 2_000

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

export function installMock(server: MockServer = createMockServer()): MockServer {
  const realFetch = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(raw, window.location.href)
    if (!/^\/(api\/|actuator)/.test(url.pathname)) return realFetch(input, init)
    await delay(20 + Math.random() * 60)
    const method = init?.method ?? (input instanceof Request ? input.method : 'GET')
    const res = server.handle({ method, url: url.pathname + url.search, headers: headersOf(init, input) })
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
