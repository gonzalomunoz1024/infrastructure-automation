import { isApiError } from '../../lib/api'
import type { AssistantAsk, AssistantConversation, AssistantTurn, SearchInterpretation } from '../../lib/assistant'

/*
 * A conversation with the copilot, kept outside React so that it survives the panel closing and
 * the page changing. A turn starts with a POST (202) and its answer is read from the conversation
 * while the panel is open: at once, then after 1 s, backing off by half a second to every 3 s,
 * until the turn has answered or failed. Closing the panel stops the reading; opening it again
 * carries on. A turn that cannot start says why in plain words: 429 says when to ask again, a
 * conversation the copilot no longer has (24 hours) starts a new one, and Try again sends the
 * same question again. The person's words are also read as a filter (`search:interpret`), so a
 * filter the copilot proposes from a reading it is not sure of can say so.
 */

export interface CopilotApi {
  ask: (body: AssistantAsk) => Promise<{ conversationId: string; turnId: string }>
  conversation: (conversationId: string) => Promise<AssistantConversation>
  interpret: (q: string, type?: string) => Promise<SearchInterpretation>
}

/** Why a turn could not start, or its answer could not be read. */
export type Problem =
  /** 429: the person's ten questions a minute are used; `ready` once the wait is over */
  | { kind: 'rate'; message: string; until: number; ready: boolean }
  /** 400 or 403: asking again the same way would fail the same way */
  | { kind: 'refused'; message: string }
  /** the BFF did not answer, or failed */
  | { kind: 'unreachable'; message: string }
  /** the answer could not be read, three times in a row */
  | { kind: 'unread'; message: string }
  /** the copilot no longer has the conversation */
  | { kind: 'ended'; message: string }

/** What the console knows about an answer that the conversation does not say. */
export interface TurnNote {
  /** how the search indexer read the person's words, for the filter the answer proposes */
  reading?: SearchInterpretation
  /** the resource an Explain was asked about */
  name?: string
}

export interface ThreadState {
  conversationId?: string
  /** oldest first, as the copilot keeps them */
  turns: AssistantTurn[]
  /** the person's words on their way, or that could not be sent */
  unsent?: { text: string; at: string }
  /** a turn is being sent */
  sending: boolean
  /** the answer being waited for: the id of the copilot's turn */
  waitingFor?: string
  problem?: Problem
  /** what Try again sends */
  last?: { body: AssistantAsk; echo: string; name?: string }
  /** by the id of the copilot's turn */
  notes: Record<string, TurnNote>
  /** what is typed in the panel and not sent yet */
  draft: string
}

export interface ThreadStore {
  get: () => ThreadState
  subscribe: (listener: () => void) => () => void
  /** starts a turn; `echo` is what the conversation shows of it until the copilot has it */
  ask: (body: AssistantAsk, echo: string, name?: string) => void
  /** sends the last question again, or reads its answer again */
  retry: () => void
  /** a new conversation: forgets this one (the draft stays) */
  reset: () => void
  setDraft: (text: string) => void
  /** reads answers while the panel shows the conversation; returns the stop */
  watch: () => () => void
}

/** The longest text the search indexer reads. */
export const MAX_INTERPRET = 500
/** The copilot's limit on a person's questions. */
export const TURNS_PER_MINUTE = 10
const MAX_READ_FAILURES = 3

/** The wait before the n-th read of an answer: 1 s, then half a second more each time, up to 3 s. */
export function pollDelay(n: number): number {
  return Math.min(3_000, 1_000 + 500 * Math.max(0, n))
}

function seconds(s: number): string {
  if (s < 90) return `${s} ${s === 1 ? 'second' : 'seconds'}`
  return `${Math.ceil(s / 60)} minutes`
}

export const RATE_READY = `The copilot takes ${TURNS_PER_MINUTE} questions a minute. You can ask again now.`

/** A failed POST in words. */
export function problemOf(e: unknown, now: number): Problem {
  if (isApiError(e)) {
    if (e.status === 429) {
      const wait = Math.max(1, Math.ceil(e.retryAfter ?? 60))
      return { kind: 'rate', until: now + wait * 1_000, ready: false, message: `The copilot takes ${TURNS_PER_MINUTE} questions a minute. Try again in ${seconds(wait)}.` }
    }
    if (e.status === 400) return { kind: 'refused', message: e.problems?.find((p) => p.message)?.message ?? 'The copilot could not take that question. Change it and ask again.' }
    if (e.status === 401) return { kind: 'refused', message: 'Your session has ended. Sign in again to ask the copilot.' }
    if (e.status === 403) return { kind: 'refused', message: e.denied?.reason ? `You cannot ask the copilot here: ${e.denied.reason}` : 'You cannot ask the copilot here.' }
  }
  return { kind: 'unreachable', message: 'The copilot cannot be reached right now.' }
}

export const ENDED = 'The copilot no longer has this conversation: it keeps them for 24 hours. Ask again to start a new one.'
export const UNREAD = 'The copilot’s answer could not be read.'

/** The copilot's last turn. */
export function lastAnswer(turns: readonly AssistantTurn[]): AssistantTurn | undefined {
  for (let i = turns.length - 1; i >= 0; i--) if (turns[i].role === 'assistant') return turns[i]
  return undefined
}

/** Whether Try again has something to do: a question to send again, or an answer to read again. */
export function canRetry(s: ThreadState): boolean {
  if (s.sending) return false
  if (s.problem) return s.problem.kind === 'unread' || s.problem.kind === 'unreachable' || (s.problem.kind === 'rate' && s.problem.ready && !!s.last)
  const last = s.turns[s.turns.length - 1]
  return !!s.last && !s.waitingFor && last?.role === 'assistant' && last.status === 'failed' && last.error?.code !== 'assistant.refused'
}

export type ThreadStatus = 'idle' | 'thinking' | 'answered' | 'failed' | 'problem'

/** Where the conversation stands, for the panel's announcements. */
export function statusOf(s: ThreadState): ThreadStatus {
  if (s.problem) return 'problem'
  if (s.sending || s.waitingFor) return 'thinking'
  const last = lastAnswer(s.turns)
  if (!last) return 'idle'
  return last.status === 'failed' ? 'failed' : last.status === 'thinking' ? 'thinking' : 'answered'
}

const EMPTY: ThreadState = { turns: [], sending: false, notes: {}, draft: '' }

export function createThreadStore(api: CopilotApi, clock: () => number = Date.now): ThreadStore {
  let state: ThreadState = EMPTY
  const listeners = new Set<() => void>()
  /** bumped by a new conversation, so that an answer to the old one is dropped */
  let generation = 0
  let watchers = 0
  let reads = 0
  let failures = 0
  let reading = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let rateTimer: ReturnType<typeof setTimeout> | undefined

  const set = (patch: Partial<ThreadState>) => {
    state = { ...state, ...patch }
    listeners.forEach((l) => l())
  }
  const note = (turnId: string, patch: TurnNote) => set({ notes: { ...state.notes, [turnId]: { ...state.notes[turnId], ...patch } } })

  function stopTimer() {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }

  /** The next read, while someone watches and an answer is awaited: 1 s after the first read, then backing off. */
  function schedule(delay = pollDelay(reads - 1)) {
    stopTimer()
    if (watchers === 0 || reading || !state.waitingFor || !state.conversationId || state.problem) return
    timer = setTimeout(() => void read(), delay)
  }

  async function read() {
    timer = undefined
    const id = state.conversationId
    const gen = generation
    if (!id) return
    reading = true
    let conversation: AssistantConversation | undefined
    let error: unknown
    try {
      conversation = await api.conversation(id)
    } catch (e) {
      error = e
    }
    // a new conversation forgets this one (and has let go of the read)
    if (gen !== generation) return
    reading = false
    // the question went to a new conversation while this read was out: read that one instead
    if (state.conversationId !== id) return schedule()
    if (conversation) {
      failures = 0
      reads += 1
      // a read that left before the turn was sent does not have it yet: keep waiting, and keep the echo
      const answer = conversation.turns.find((t) => t.turnId === state.waitingFor)
      set({ turns: conversation.turns, ...(answer ? { unsent: undefined } : {}), waitingFor: answer && answer.status !== 'thinking' ? undefined : state.waitingFor })
    } else if (isApiError(error) && error.status === 404) {
      set({ conversationId: undefined, waitingFor: undefined, problem: { kind: 'ended', message: ENDED } })
      return
    } else if (++failures >= MAX_READ_FAILURES) {
      set({ problem: { kind: 'unread', message: UNREAD } })
      return
    }
    schedule()
  }

  function waitOut(problem: Problem) {
    if (rateTimer !== undefined) clearTimeout(rateTimer)
    rateTimer = undefined
    if (problem.kind !== 'rate') return
    rateTimer = setTimeout(
      () => {
        rateTimer = undefined
        if (state.problem === problem) set({ problem: { ...problem, ready: true, message: RATE_READY } })
      },
      Math.max(0, problem.until - clock()),
    )
  }

  async function ask(body: AssistantAsk, echo: string, name?: string) {
    if (state.sending) return
    const gen = generation
    const conversationId = state.conversationId
    stopTimer()
    set({
      sending: true,
      unsent: { text: echo, at: new Date(clock()).toISOString() },
      problem: undefined,
      last: { body, echo, name },
      waitingFor: undefined,
      // a conversation the copilot no longer had is not shown above the new one
      turns: conversationId ? state.turns : [],
    })
    const words = body.intent === 'ask' ? body.message?.trim() : undefined
    const readingOf = words && words.length <= MAX_INTERPRET ? api.interpret(words, body.context?.type).catch(() => undefined) : Promise.resolve(undefined)
    let started: { conversationId: string; turnId: string }
    try {
      started = await api.ask(conversationId ? { ...body, conversationId } : body)
    } catch (e) {
      if (gen !== generation) return
      if (isApiError(e) && e.status === 404 && conversationId) {
        // the copilot no longer has the conversation: the question starts a new one
        set({ sending: false, conversationId: undefined, turns: [] })
        return ask(body, echo, name)
      }
      const problem = problemOf(e, clock())
      // a question refused as it is goes back into the box, to be changed
      const draft = problem.kind === 'refused' && body.intent === 'ask' && !state.draft ? (body.message ?? '') : state.draft
      set({ sending: false, problem, draft })
      waitOut(problem)
      return
    }
    if (gen !== generation) return
    reads = 0
    failures = 0
    set({ sending: false, conversationId: started.conversationId, waitingFor: started.turnId })
    if (name) note(started.turnId, { name })
    void readingOf.then((reading) => {
      if (reading && gen === generation) note(started.turnId, { reading })
    })
    schedule(0)
  }

  return {
    get: () => state,
    subscribe: (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    ask: (body, echo, name) => void ask(body, echo, name),
    retry: () => {
      if (!canRetry(state)) return
      if (state.problem?.kind === 'unread') {
        failures = 0
        set({ problem: undefined })
        schedule(0)
        return
      }
      if (state.last) void ask(state.last.body, state.last.echo, state.last.name)
    },
    reset: () => {
      generation += 1
      stopTimer()
      if (rateTimer !== undefined) clearTimeout(rateTimer)
      rateTimer = undefined
      reads = 0
      failures = 0
      reading = false
      set({ ...EMPTY, draft: state.draft })
    },
    setDraft: (draft) => {
      if (draft !== state.draft) set({ draft })
    },
    watch: () => {
      watchers += 1
      schedule(0)
      return () => {
        watchers -= 1
        if (watchers === 0) stopTimer()
      }
    },
  }
}
