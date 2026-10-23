import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { backoffDelay, openStream, withAfter, type StreamState } from './sse'

/** An EventSource the test drives by hand. */
class FakeSource {
  static instances: FakeSource[] = []
  readyState = 0
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  readonly named = new Map<string, ((e: Event) => void)[]>()

  constructor(readonly url: string) {
    FakeSource.instances.push(this)
  }

  addEventListener(name: string, fn: (e: Event) => void) {
    this.named.set(name, [...(this.named.get(name) ?? []), fn])
  }

  close() {
    this.readyState = 2
  }

  open() {
    this.readyState = 1
    this.onopen?.({} as Event)
  }

  message(data: unknown, lastEventId: string, name?: string) {
    const ev = { data: JSON.stringify(data), lastEventId } as MessageEvent
    if (name) this.named.get(name)?.forEach((fn) => fn(ev))
    else this.onmessage?.(ev)
  }

  /** the browser gave up (CLOSED) or is retrying by itself (CONNECTING) */
  fail(gaveUp: boolean) {
    this.readyState = gaveUp ? 2 : 0
    this.onerror?.({} as Event)
  }
}

const factory = (url: string) => new FakeSource(url) as unknown as EventSource

describe('backoffDelay', () => {
  it('doubles from the base and caps', () => {
    expect([0, 1, 2, 3, 4, 5].map((n) => backoffDelay(n))).toEqual([1000, 2000, 4000, 8000, 16000, 30000])
    expect(backoffDelay(10)).toBe(30000)
    expect(backoffDelay(2, 500, 1500)).toBe(1500)
    expect(backoffDelay(-1)).toBe(1000)
  })
})

describe('withAfter', () => {
  it('sets, replaces and removes the after parameter', () => {
    expect(withAfter('/api/v1/events/stream', '12')).toBe('/api/v1/events/stream?after=12')
    expect(withAfter('/api/v1/events/stream?after=3&types=job', '12')).toBe('/api/v1/events/stream?after=12&types=job')
    expect(withAfter('/api/v1/events/stream?after=3', null)).toBe('/api/v1/events/stream')
    expect(withAfter('/api/v1/events/stream?after=3', '')).toBe('/api/v1/events/stream')
  })
})

describe('openStream', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    FakeSource.instances = []
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('delivers parsed events, remembers the last id and resumes after it when the browser gives up', () => {
    const events: unknown[] = []
    const states: [StreamState, number][] = []
    const stream = openStream('/api/v1/events/stream', (e) => events.push(e), { factory, onState: (s, d) => states.push([s, d.delayMs]) })

    expect(FakeSource.instances).toHaveLength(1)
    const first = FakeSource.instances[0]
    expect(first.url).toBe('/api/v1/events/stream')
    expect(stream.state()).toBe('connecting')

    first.open()
    expect(stream.state()).toBe('open')
    first.message({ seq: 7, type: 'job.started' }, '7')
    first.message('plain text', '8')
    expect(events).toEqual([{ seq: 7, type: 'job.started' }, 'plain text'])
    expect(stream.lastId()).toBe('8')

    // the browser retries by itself: nothing to do but say so
    first.fail(false)
    expect(stream.state()).toBe('retrying')
    expect(FakeSource.instances).toHaveLength(1)
    first.open()

    // the browser gave up: reopen after the backoff, asking for what came after the last id
    first.fail(true)
    expect(stream.state()).toBe('retrying')
    expect(states[states.length - 1]).toEqual(['retrying', 1000])
    expect(FakeSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(999)
    expect(FakeSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeSource.instances).toHaveLength(2)
    expect(FakeSource.instances[1].url).toBe('/api/v1/events/stream?after=8')

    // a second failure in a row waits twice as long
    FakeSource.instances[1].fail(true)
    expect(states[states.length - 1]).toEqual(['retrying', 2000])
    vi.advanceTimersByTime(2000)
    expect(FakeSource.instances).toHaveLength(3)
    FakeSource.instances[2].open()
    FakeSource.instances[2].message({ seq: 9 }, '9')
    expect(stream.lastId()).toBe('9')

    // and a success resets the backoff
    FakeSource.instances[2].fail(true)
    expect(states[states.length - 1]).toEqual(['retrying', 1000])

    stream.close()
    expect(stream.state()).toBe('closed')
    vi.advanceTimersByTime(60_000)
    expect(FakeSource.instances).toHaveLength(3)
  })

  it('starts after a given id, listens to named events and gives up after maxRetries', () => {
    const events: unknown[] = []
    const stream = openStream('/api/v1/events/stream?types=job', (e) => events.push(e), { factory, after: '41', events: ['job'], maxRetries: 1, retryMs: 10 })
    expect(FakeSource.instances[0].url).toBe('/api/v1/events/stream?types=job&after=41')
    FakeSource.instances[0].open()
    FakeSource.instances[0].message({ seq: 42 }, '42', 'job')
    expect(events).toEqual([{ seq: 42 }])

    FakeSource.instances[0].fail(true)
    vi.advanceTimersByTime(10)
    expect(FakeSource.instances).toHaveLength(2)
    FakeSource.instances[1].fail(true)
    expect(stream.state()).toBe('closed')
    vi.advanceTimersByTime(60_000)
    expect(FakeSource.instances).toHaveLength(2)
  })
})
