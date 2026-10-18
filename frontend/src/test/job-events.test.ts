import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isLastJobEvent, jobEvents } from '../lib/api'
import * as api from '../lib/api'
import type { JobEvent, JobEventType } from '../lib/types'

/*
 * docs/api.md "Jobs": a job's stream replays its history, sends its events as they happen, and
 * ends after its last one (job.finished or job.cancelled); a job already over replays and ends.
 * EventSource reconnects to a stream that ends, which would replay a finished job forever, so the
 * client stops there. The job page has no WebSocket client: the server closes that socket after
 * the last event too, and nothing used it.
 */

const CONNECTING = 0
const OPEN = 1
const CLOSED = 2

class FakeEventSource {
  static readonly CONNECTING = CONNECTING
  static readonly OPEN = OPEN
  static readonly CLOSED = CLOSED
  static all: FakeEventSource[] = []
  readyState = CONNECTING
  onmessage: ((e: MessageEvent) => void) | null = null
  onopen: ((e: Event) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  private listeners = new Map<string, ((e: Event) => void)[]>()

  constructor(readonly url: string) {
    FakeEventSource.all.push(this)
  }

  addEventListener(name: string, fn: (e: Event) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), fn])
  }

  close() {
    this.readyState = CLOSED
  }

  open() {
    this.readyState = OPEN
    this.onopen?.({} as Event)
  }

  send(event: JobEvent) {
    const ev = { data: JSON.stringify(event) } as MessageEvent
    for (const fn of this.listeners.get(event.type) ?? []) fn(ev)
  }

  /** The response ended: the browser says so and would reconnect by itself. */
  end() {
    this.readyState = CONNECTING
    this.onerror?.({} as Event)
  }
}

const ev = (seq: number, type: JobEventType, event: string): JobEvent => ({ seq, at: '2026-10-18T10:00:00Z', jobId: 'job_a', type, data: { jobId: 'job_a', event } })

beforeEach(() => {
  FakeEventSource.all = []
  vi.useFakeTimers()
  vi.stubGlobal('EventSource', FakeEventSource)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function listen() {
  const seen: number[] = []
  const errors: Event[] = []
  const sub = jobEvents('job_a', (e) => seen.push(e.seq), { onError: (e) => errors.push(e) })
  const es = FakeEventSource.all[0]
  es.open()
  return { sub, es, seen, errors }
}

describe('the last event of a job', () => {
  it('is job.finished or job.cancelled, named in data.event', () => {
    expect(isLastJobEvent(ev(9, 'job', 'job.finished'))).toBe(true)
    expect(isLastJobEvent(ev(9, 'job', 'job.cancelled'))).toBe(true)
    expect(isLastJobEvent(ev(1, 'job', 'job.started'))).toBe(false)
    expect(isLastJobEvent(ev(2, 'step', 'job.step.finished'))).toBe(false)
    expect(isLastJobEvent(ev(3, 'log', 'job.log'))).toBe(false)
    expect(isLastJobEvent({ ...ev(4, 'job', 'job.finished'), data: {} })).toBe(false)
  })
})

describe('a job’s event stream', () => {
  it('reads the job’s stream and ends with it, without reconnecting or calling it an error', () => {
    const { es, seen, errors } = listen()
    expect(es.url).toMatch(/\/jobs\/job_a\/events$/)
    es.send(ev(1, 'job', 'job.started'))
    es.send(ev(2, 'step', 'job.step.finished'))
    es.send(ev(3, 'job', 'job.finished'))
    es.end()
    expect(es.readyState).toBe(CLOSED)
    vi.advanceTimersByTime(120_000)
    expect(FakeEventSource.all).toHaveLength(1)
    expect(seen).toEqual([1, 2, 3])
    expect(errors).toEqual([])
  })

  it('ends after a cancel too', () => {
    const { es } = listen()
    es.send(ev(1, 'job', 'job.started'))
    es.send(ev(2, 'job', 'job.cancelled'))
    es.end()
    expect(es.readyState).toBe(CLOSED)
  })

  it('leaves a stream dropped while the job runs to the browser’s reconnect, as an error', () => {
    const { es, errors } = listen()
    es.send(ev(1, 'job', 'job.started'))
    es.send(ev(2, 'log', 'job.log'))
    es.end()
    expect(es.readyState).toBe(CONNECTING)
    expect(errors).toHaveLength(1)
  })

  it('reads the whole history of a batch that ran its failed rows again, past its first finish', () => {
    const { es, seen } = listen()
    es.send(ev(1, 'job', 'job.started'))
    es.send(ev(2, 'job', 'job.finished'))
    es.send(ev(3, 'batch', 'batch.wave.started'))
    expect(es.readyState).toBe(OPEN)
    es.send(ev(4, 'job', 'job.finished'))
    es.end()
    expect(seen).toEqual([1, 2, 3, 4])
    expect(es.readyState).toBe(CLOSED)
  })

  it('closes on request and starts nothing after', () => {
    const { sub, es } = listen()
    sub.close()
    expect(es.readyState).toBe(CLOSED)
    vi.advanceTimersByTime(120_000)
    expect(FakeEventSource.all).toHaveLength(1)
  })

  it('has no WebSocket client left to replay a finished job', () => {
    expect('jobSocket' in api).toBe(false)
  })
})
