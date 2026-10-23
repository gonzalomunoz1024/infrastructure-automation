/*
 * Server-sent events with resume. EventSource reconnects by itself and sends Last-Event-ID; when
 * it gives up (CLOSED), this reopens it with a backoff that doubles up to 30 seconds and asks for
 * the events after the last one seen (`?after=<id>`, the platform feed's contract), so nothing is
 * missed across a reconnect. EventSource cannot carry headers, so the dev identity does not ride
 * on streams.
 */

export type StreamState = 'connecting' | 'open' | 'retrying' | 'closed'

/** EventSource.CLOSED, spelled out so the module loads where EventSource is not a global (tests). */
const CLOSED = 2

export interface StreamOptions {
  /** named events to listen for besides `message` */
  events?: string[]
  /** first reconnect delay in ms (default 1000); doubles up to `maxDelayMs` */
  retryMs?: number
  maxDelayMs?: number
  /** give up after this many consecutive failures (default: never) */
  maxRetries?: number
  /** the id to resume after on the first connection */
  after?: string | null
  onState?: (state: StreamState, detail: { attempt: number; delayMs: number; lastId: string | null }) => void
  /** builds an EventSource; tests pass a fake */
  factory?: (url: string) => EventSource
}

export interface Stream {
  close: () => void
  lastId: () => string | null
  state: () => StreamState
}

/** The reconnect delay for the n-th consecutive failure (0-based): base * 2^n, capped. */
export function backoffDelay(attempt: number, baseMs = 1000, maxMs = 30_000): number {
  return Math.min(maxMs, baseMs * 2 ** Math.max(0, attempt))
}

/** The url with `after=<id>` set (or removed when id is null). */
export function withAfter(url: string, id: string | null): string {
  const qi = url.indexOf('?')
  const base = qi >= 0 ? url.slice(0, qi) : url
  const sp = new URLSearchParams(qi >= 0 ? url.slice(qi + 1) : '')
  if (id === null || id === '') sp.delete('after')
  else sp.set('after', id)
  const s = sp.toString()
  return s ? `${base}?${s}` : base
}

export function openStream<T = unknown>(url: string, onEvent: (event: T, raw: MessageEvent) => void, opts: StreamOptions = {}): Stream {
  const factory = opts.factory ?? ((u: string) => new EventSource(u))
  let es: EventSource | null = null
  let closed = false
  let attempt = 0
  let lastId: string | null = opts.after ?? null
  let state: StreamState = 'connecting'
  let timer: ReturnType<typeof setTimeout> | undefined

  const setState = (next: StreamState, delayMs = 0) => {
    state = next
    opts.onState?.(next, { attempt, delayMs, lastId })
  }

  const close = () => {
    closed = true
    if (timer) clearTimeout(timer)
    es?.close()
    setState('closed')
  }

  const handle = (ev: Event) => {
    const msg = ev as MessageEvent
    attempt = 0
    if (msg.lastEventId) lastId = msg.lastEventId
    let data: unknown = msg.data
    if (typeof msg.data === 'string') {
      try {
        data = JSON.parse(msg.data)
      } catch {
        data = msg.data
      }
    }
    onEvent(data as T, msg)
  }

  const connect = () => {
    if (closed) return
    setState('connecting')
    es = factory(withAfter(url, lastId))
    es.onmessage = handle
    for (const name of opts.events ?? []) es.addEventListener(name, handle)
    es.onopen = () => {
      attempt = 0
      setState('open')
    }
    es.onerror = () => {
      if (!es || es.readyState !== CLOSED) {
        // the browser retries by itself (and sends Last-Event-ID)
        setState('retrying')
        return
      }
      es.close()
      if (closed) return
      if (opts.maxRetries !== undefined && attempt >= opts.maxRetries) {
        setState('closed')
        return
      }
      const delay = backoffDelay(attempt, opts.retryMs ?? 1000, opts.maxDelayMs ?? 30_000)
      attempt++
      setState('retrying', delay)
      timer = setTimeout(connect, delay)
    }
  }

  connect()
  return { close, lastId: () => lastId, state: () => state }
}
