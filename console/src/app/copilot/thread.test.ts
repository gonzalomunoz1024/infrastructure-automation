import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api'
import type { AssistantAsk, AssistantConversation, AssistantTurn, SearchInterpretation } from '../../lib/assistant'
import { ENDED, RATE_READY, UNREAD, canRetry, createThreadStore, pollDelay, problemOf, statusOf, type CopilotApi } from './thread'

/*
 * The conversation outside React: a turn is sent once, its answer read at once and then on a
 * backing-off clock while the panel watches, never after; what cannot be sent says why and when
 * to try again; a new conversation forgets the old one, late answers included.
 */

const AT = '2026-10-23T10:00:00.000Z'
const READING: SearchInterpretation = {
  filter: { state: 'deleted', tags: { environment: 'prod' } },
  explanation: 'Resources that are deleted.',
  confidence: 'low',
  unparsed: ['machines'],
  q: 'state:deleted tag:environment=prod',
  total: 10,
}

/** The BFF's copilot, in memory: each answer thinks for `thinkReads` reads, then answers with `answer`. */
class FakeCopilot implements CopilotApi {
  asks: AssistantAsk[] = []
  reads: string[] = []
  interprets: [string, string | undefined][] = []
  askErrors: unknown[] = []
  readErrors: unknown[] = []
  thinkReads = 1
  answer: Partial<AssistantTurn> = { status: 'answered', text: 'Done.' }
  /** reads wait until `fail` lets them go */
  hold = false
  private held: ((e: unknown) => void)[] = []
  private made = 0
  private conversations = new Map<string, AssistantConversation>()
  private left = new Map<string, number>()

  ask = async (body: AssistantAsk) => {
    this.asks.push(body)
    const err = this.askErrors.shift()
    if (err) throw err
    let conversation = body.conversationId ? this.conversations.get(body.conversationId) : undefined
    if (body.conversationId && !conversation) throw new ApiError(404, { error: 'Not found' })
    if (!conversation) {
      conversation = { conversationId: `conv_${++this.made}`, status: 'thinking', turns: [] }
      this.conversations.set(conversation.conversationId, conversation)
    }
    const n = conversation.turns.length
    conversation.turns.push({ turnId: `t${n + 1}`, role: 'person', at: AT, text: body.message ?? 'Explain' })
    conversation.turns.push({ turnId: `t${n + 2}`, role: 'assistant', at: AT, status: 'thinking' })
    this.left.set(`t${n + 2}`, this.thinkReads)
    return { conversationId: conversation.conversationId, turnId: `t${n + 2}` }
  }

  conversation = async (id: string) => {
    this.reads.push(id)
    if (this.hold) return new Promise<AssistantConversation>((_, reject) => this.held.push(reject))
    const err = this.readErrors.shift()
    if (err) throw err
    const conversation = this.conversations.get(id)
    if (!conversation) throw new ApiError(404, { error: 'Not found' })
    for (const t of conversation.turns) {
      if (t.status !== 'thinking') continue
      const left = this.left.get(t.turnId) ?? 0
      if (left > 0) this.left.set(t.turnId, left - 1)
      else Object.assign(t, this.answer)
    }
    conversation.status = conversation.turns.some((t) => t.status === 'thinking') ? 'thinking' : 'ready'
    return structuredClone(conversation)
  }

  interpret = async (q: string, type?: string) => {
    this.interprets.push([q, type])
    return READING
  }

  /** forgets every conversation, as after 24 hours */
  forget() {
    this.conversations.clear()
  }

  /** the held reads fail with `e` */
  fail(e: unknown) {
    this.hold = false
    for (const reject of this.held.splice(0)) reject(e)
  }
}

const flush = () => vi.advanceTimersByTimeAsync(0)
const STOPPED = { intent: 'ask', message: 'stopped machines in prod', context: { path: '/' } } as const

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(AT))
})
afterEach(() => {
  vi.useRealTimers()
})

describe('a turn', () => {
  it('is sent once, read at once, then after a second, backing off, until it has answered', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 3
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.ask(STOPPED, STOPPED.message)
    expect(thread.get()).toMatchObject({ sending: true, unsent: { text: 'stopped machines in prod' } })
    expect(statusOf(thread.get())).toBe('thinking')
    await flush()
    expect(api.asks).toEqual([STOPPED])
    // read at once: the copilot has the question and thinks
    expect(api.reads).toHaveLength(1)
    expect(thread.get().unsent).toBeUndefined()
    expect(thread.get().turns.map((t) => t.status ?? t.role)).toEqual(['person', 'thinking'])
    await vi.advanceTimersByTimeAsync(999)
    expect(api.reads).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(api.reads).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1_500)
    expect(api.reads).toHaveLength(3)
    await vi.advanceTimersByTimeAsync(2_000)
    expect(api.reads).toHaveLength(4)
    expect(statusOf(thread.get())).toBe('answered')
    expect(thread.get().waitingFor).toBeUndefined()
    // answered: no more reads
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.reads).toHaveLength(4)
    stop()
  })

  it('backs off by half a second to every three', () => {
    expect([0, 1, 2, 3, 4, 5, 9].map(pollDelay)).toEqual([1_000, 1_500, 2_000, 2_500, 3_000, 3_000, 3_000])
  })

  it('is read only while the panel watches: closing it stops the reading, opening it carries on', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 5
    const thread = createThreadStore(api)
    thread.ask(STOPPED, STOPPED.message)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.reads).toHaveLength(0)
    const stop = thread.watch()
    await flush()
    expect(api.reads).toHaveLength(1)
    stop()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.reads).toHaveLength(1)
    const again = thread.watch()
    await flush()
    expect(api.reads).toHaveLength(2)
    again()
  })

  it('goes on in the same conversation, and reads the person’s words as a filter for the answer', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 0
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.ask({ ...STOPPED, context: { path: '/console/compute/vm', type: 'vm' } }, STOPPED.message)
    await flush()
    const first = thread.get().turns[1].turnId
    expect(thread.get().notes[first]).toEqual({ reading: READING })
    expect(api.interprets).toEqual([['stopped machines in prod', 'vm']])
    thread.ask({ intent: 'explain', context: { wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo01' } }, 'Explain web-01', 'web-01')
    await flush()
    expect(api.asks[1].conversationId).toBe('conv_1')
    expect(thread.get().turns).toHaveLength(4)
    // an Explain is not read as a filter; it keeps the resource's name for its card
    expect(api.interprets).toHaveLength(1)
    expect(thread.get().notes[thread.get().turns[3].turnId]).toEqual({ name: 'web-01' })
    stop()
  })

  it('does not read words longer than the search indexer takes', async () => {
    const api = new FakeCopilot()
    const thread = createThreadStore(api)
    thread.ask({ intent: 'ask', message: 'x'.repeat(501) }, 'long')
    await flush()
    expect(api.interprets).toEqual([])
  })
})

describe('what goes wrong', () => {
  it('429 says when to ask again, and Try again waits until then', async () => {
    const api = new FakeCopilot()
    api.askErrors.push(new ApiError(429, { error: 'The copilot takes 10 questions a minute' }, undefined, 40))
    const thread = createThreadStore(api)
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    expect(thread.get().problem).toMatchObject({ kind: 'rate', ready: false, message: 'The copilot takes 10 questions a minute. Try again in 40 seconds.' })
    expect(statusOf(thread.get())).toBe('problem')
    expect(canRetry(thread.get())).toBe(false)
    thread.retry()
    await flush()
    expect(api.asks).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(40_000)
    expect(thread.get().problem).toMatchObject({ kind: 'rate', ready: true, message: RATE_READY })
    expect(canRetry(thread.get())).toBe(true)
    thread.retry()
    await flush()
    expect(api.asks).toHaveLength(2)
    expect(thread.get().problem).toBeUndefined()
  })

  it('a turn the copilot failed can be asked again; one it refused cannot', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 0
    api.answer = { status: 'failed', error: { code: 'assistant.unavailable', message: 'The copilot is not answering right now.' } }
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    expect(statusOf(thread.get())).toBe('failed')
    expect(canRetry(thread.get())).toBe(true)
    api.answer = { status: 'answered', text: 'Ten match.' }
    thread.retry()
    await flush()
    expect(api.asks[1]).toEqual({ ...STOPPED, conversationId: 'conv_1' })
    expect(statusOf(thread.get())).toBe('answered')
    expect(canRetry(thread.get())).toBe(false)

    api.answer = { status: 'failed', error: { code: 'assistant.refused', message: 'You cannot read web-01.' } }
    thread.ask({ intent: 'explain', context: { wfrn: 'wfrn:x' } }, 'Explain')
    await flush()
    expect(statusOf(thread.get())).toBe('failed')
    expect(canRetry(thread.get())).toBe(false)
    stop()
  })

  it('a conversation the copilot no longer has: a question starts a new one, and a read says it ended', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 0
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    api.forget()
    thread.ask({ intent: 'ask', message: 'and in dev?' }, 'and in dev?')
    await flush()
    // sent to the old conversation (404), then once more as a new one
    expect(api.asks.map((a) => a.conversationId)).toEqual([undefined, 'conv_1', undefined])
    expect(thread.get().conversationId).toBe('conv_2')
    expect(thread.get().turns.map((t) => t.text ?? t.status)).toEqual(['and in dev?', 'Done.'])

    api.thinkReads = 5
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    api.forget()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(thread.get().problem).toEqual({ kind: 'ended', message: ENDED })
    expect(thread.get().conversationId).toBeUndefined()
    expect(canRetry(thread.get())).toBe(false)
    stop()
  })

  it('a read of the old conversation that comes back after the question went to a new one is let go', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 5
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    api.hold = true
    await vi.advanceTimersByTimeAsync(1_000)
    expect(api.reads).toEqual(['conv_1', 'conv_1'])
    api.forget()
    thread.ask({ intent: 'ask', message: 'and in dev?' }, 'and in dev?')
    await flush()
    expect(thread.get().conversationId).toBe('conv_2')
    api.fail(new ApiError(404, { error: 'Not found' }))
    await flush()
    expect(thread.get().problem).toBeUndefined()
    expect(thread.get().conversationId).toBe('conv_2')
    // and the new one is read from then on
    await vi.advanceTimersByTimeAsync(1_000)
    expect(api.reads.at(-1)).toBe('conv_2')
    stop()
  })

  it('an answer that cannot be read three times running says so, and Try again reads it again', async () => {
    const api = new FakeCopilot()
    api.thinkReads = 0
    const thread = createThreadStore(api)
    const stop = thread.watch()
    api.readErrors.push(new ApiError(502, null), new ApiError(0, null), new ApiError(503, null))
    thread.ask(STOPPED, STOPPED.message)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.reads).toHaveLength(3)
    expect(thread.get().problem).toEqual({ kind: 'unread', message: UNREAD })
    expect(canRetry(thread.get())).toBe(true)
    thread.retry()
    await flush()
    expect(api.asks).toHaveLength(1)
    expect(api.reads).toHaveLength(4)
    expect(statusOf(thread.get())).toBe('answered')
    stop()
  })

  it('a question refused as it is goes back into the box', async () => {
    const api = new FakeCopilot()
    api.askErrors.push(
      new ApiError(400, { error: 'validation.failed', problems: [{ level: 'error', code: 'assistant.message', field: 'message', message: 'A question holds at most 2000 characters.' }] }),
    )
    const thread = createThreadStore(api)
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    expect(thread.get().problem).toEqual({ kind: 'refused', message: 'A question holds at most 2000 characters.' })
    expect(thread.get().draft).toBe('stopped machines in prod')
    expect(canRetry(thread.get())).toBe(false)
  })

  it('says plainly when the copilot cannot be reached, and Try again sends the question again', async () => {
    const api = new FakeCopilot()
    api.askErrors.push(new ApiError(0, null))
    const thread = createThreadStore(api)
    thread.ask(STOPPED, STOPPED.message)
    await flush()
    expect(thread.get().problem).toEqual({ kind: 'unreachable', message: 'The copilot cannot be reached right now.' })
    thread.retry()
    await flush()
    expect(api.asks).toHaveLength(2)
    expect(thread.get().problem).toBeUndefined()
  })

  it('words each failure', () => {
    expect(problemOf(new ApiError(429, null, undefined, 120), 0)).toMatchObject({ until: 120_000, message: 'The copilot takes 10 questions a minute. Try again in 2 minutes.' })
    expect(problemOf(new ApiError(429, null, undefined, 1), 0).message).toBe('The copilot takes 10 questions a minute. Try again in 1 second.')
    expect(
      problemOf(new ApiError(403, { error: 'no', denied: { layer: 'console-iam', action: 'console:assist', reason: 'none of your roles allows console:assist', requestable: true } }), 0).message,
    ).toBe('You cannot ask the copilot here: none of your roles allows console:assist')
    expect(problemOf(new Error('boom'), 0).kind).toBe('unreachable')
  })
})

describe('a new conversation', () => {
  it('forgets the old one, an answer still on its way included, and keeps the draft', async () => {
    const api = new FakeCopilot()
    const thread = createThreadStore(api)
    const stop = thread.watch()
    thread.setDraft('half a thought')
    thread.ask(STOPPED, STOPPED.message)
    thread.reset()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(thread.get()).toMatchObject({ turns: [], sending: false, notes: {}, draft: 'half a thought' })
    expect(thread.get().conversationId).toBeUndefined()
    expect(statusOf(thread.get())).toBe('idle')
    // the next question starts a conversation of its own
    thread.ask({ intent: 'ask', message: 'hello there' }, 'hello there')
    await flush()
    expect(api.asks.map((a) => a.conversationId)).toEqual([undefined, undefined])
    stop()
  })
})
