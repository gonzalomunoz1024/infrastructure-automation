import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Button, Icon, IconButton, Textarea, formatNumber, useMediaQuery } from '@iaf/ui'
import type { Route } from '../router'
import { Conversation } from './Answer'
import { PANEL_ID, useCopilot, useThread, type Copilot } from './context'
import { contextOf, suggestionsFor, type Suggestion } from './proposals'
import { lastAnswer, statusOf, type ThreadState } from './thread'

/*
 * The copilot's side panel (WP 9.1): plain English to a filtered list, a pre-filled wizard or
 * action, or an explanation of a resource. It never acts: what it proposes opens for the person
 * to check, and every change still goes through its preview and IAM. A non-modal complementary
 * region beside the page: the page makes room for it, so the page stays in reach and nothing
 * focused there hides underneath. On a narrow screen it covers the page, and Tab then stays in it.
 * Opening it puts the focus in its box, Escape closes it and gives the focus back. While the
 * copilot thinks, and when it answers or fails, a polite status line says so.
 */

export const INPUT_ID = 'copilot-input'
/** The most a question may hold. */
export const MAX_MESSAGE = 2_000
/** From here the box says how much of it is used. */
const SHOW_COUNT_FROM = 1_800
/** From this width (Tailwind's md) the panel sits beside the page; narrower, it covers the screen. */
const BESIDE = 768
/** The panel's width beside the page: 26rem, and never more than half the screen. */
const WIDTH = 'min(26rem, 50vw)'
const FOCUSABLE = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function CopilotPanel({ route }: { route: Route }) {
  const copilot = useCopilot()
  const { thread } = copilot
  const state = useThread(thread)
  // narrower than this, the panel covers the screen
  const phone = useMediaQuery(`(max-width: ${BESIDE - 1}px)`)
  const scrollRef = useRef<HTMLDivElement>(null)
  const announcement = useAnnouncement(state)
  const empty = state.turns.length === 0 && !state.unsent && !state.problem && !state.sending

  // the answer is read while the panel is open, and no longer once it closes
  useEffect(() => thread.watch(), [thread])

  // every opening puts the focus in the box, a seeded one too
  useEffect(() => {
    document.getElementById(INPUT_ID)?.focus()
  }, [copilot.opened])

  // the latest question at the top of the view, its answer under it
  const last = lastAnswer(state.turns)
  const shape = `${state.turns.length}/${last?.status ?? ''}/${state.unsent ? 1 : 0}/${state.problem?.kind ?? ''}`
  useEffect(() => {
    const rows = scrollRef.current?.querySelectorAll<HTMLElement>('[data-turn="person"]')
    rows?.[rows.length - 1]?.scrollIntoView({ block: 'start' })
  }, [shape])

  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.defaultPrevented) return
    if (e.key === 'Escape') {
      e.stopPropagation()
      copilot.close()
    } else if (e.key === 'Tab' && phone) {
      // covering the screen, the panel keeps Tab: nothing focused may hide underneath it
      const all = [...e.currentTarget.querySelectorAll<HTMLElement>(FOCUSABLE)]
      const at = all.indexOf(document.activeElement as HTMLElement)
      const next = e.shiftKey ? (at <= 0 ? all[all.length - 1] : undefined) : at === all.length - 1 ? all[0] : undefined
      if (next) {
        e.preventDefault()
        next.focus()
      }
    }
  }

  return (
    <aside
      id={PANEL_ID}
      aria-labelledby="copilot-title"
      onKeyDown={onKeyDown}
      className="fixed bottom-0 left-0 right-0 top-0 z-50 flex animate-slide-in-right flex-col bg-surface md:bottom-9 md:left-auto md:top-14 md:z-30 md:w-[min(26rem,50vw)] md:border-l md:border-stroke-light md:shadow-lg"
    >
      <div className="flex shrink-0 items-center gap-1 border-b border-stroke-light py-2 pl-4 pr-2">
        <h2 id="copilot-title" className="flex flex-1 items-center gap-2 text-subheadline font-semibold text-primary">
          <Icon name="sparkles" size={16} className="text-accent" />
          Copilot
        </h2>
        <Button variant="ghost" size="sm" icon="plus" onClick={thread.reset} disabled={empty}>
          New conversation
        </Button>
        <IconButton label="Close the copilot" onClick={copilot.close}>
          <Icon name="close" size={16} />
        </IconButton>
      </div>
      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        {empty ? <Intro suggestions={suggestionsFor(route)} copilot={copilot} /> : <Conversation state={state} route={route} onRetry={thread.retry} onOpen={phone ? copilot.close : undefined} />}
      </div>
      <Composer state={state} route={route} copilot={copilot} />
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {/* beside the page, the page makes room: nothing of it hides underneath the panel */}
      <style>{`@media (min-width: ${BESIDE}px) { #main { margin-right: ${WIDTH}; } }`}</style>
    </aside>
  )
}

/** What the empty panel says, and the first questions it offers for the page. */
function Intro({ suggestions, copilot }: { suggestions: Suggestion[]; copilot: Copilot }) {
  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-primary">Ask in plain English. The copilot turns it into the console’s own filters, forms and actions, and explains resources.</p>
      <p className="text-sm leading-relaxed text-secondary">It never changes anything: you check what it opens, and every change still goes through its review and your access.</p>
      <div>
        <h3 className="mb-2 text-caption font-semibold text-secondary">Try</h3>
        <ul className="flex flex-col items-start gap-2">
          {suggestions.map((s) => (
            <li key={s.text}>
              <button
                type="button"
                onClick={() => copilot.open({ intent: s.intent, message: s.intent === 'ask' ? s.text : undefined, context: s.context, label: s.text })}
                className="rounded-full border border-stroke-light px-3 py-1.5 text-left text-caption text-primary transition-colors hover:bg-surface-secondary"
              >
                {s.text}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** The box: Enter sends, Shift+Enter adds a line; the count shows near the limit. */
function Composer({ state, route, copilot }: { state: ThreadState; route: Route; copilot: Copilot }) {
  const hintId = useId()
  const { thread } = copilot
  // one question at a time; one the copilot could not be heard on does not hold the box
  const busy = state.sending || (!!state.waitingFor && !state.problem)
  const text = state.draft
  const send = () => {
    const message = text.trim()
    if (!message || busy) return
    thread.setDraft('')
    thread.ask({ intent: 'ask', message, context: contextOf(route) }, message)
  }
  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    send()
  }
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || e.shiftKey || e.nativeEvent.isComposing) return
    e.preventDefault()
    send()
  }
  return (
    <form onSubmit={onSubmit} className="shrink-0 border-t border-stroke-light px-4 py-3">
      <label htmlFor={INPUT_ID} className="sr-only">
        Ask the copilot
      </label>
      <Textarea
        id={INPUT_ID}
        rows={2}
        maxLength={MAX_MESSAGE}
        value={text}
        onChange={(e) => thread.setDraft(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Ask in plain English"
        aria-describedby={hintId}
        className="max-h-48"
      />
      <div className="mt-2 flex items-center justify-between gap-3">
        <p id={hintId} className="text-caption2 text-secondary">
          {text.length >= SHOW_COUNT_FROM ? `${formatNumber(text.length)} of ${formatNumber(MAX_MESSAGE)} characters. ` : ''}
          Enter sends. Shift+Enter adds a line.
        </p>
        <Button type="submit" variant="primary" size="sm" icon="send" disabled={!text.trim() || busy}>
          Send
        </Button>
      </div>
    </form>
  )
}

/** The status line's words: said when the conversation moves on, never on opening. */
function useAnnouncement(state: ThreadState): string {
  const status = statusOf(state)
  const key = `${status}/${state.turns.length}/${state.problem?.message ?? ''}`
  const [seen, setSeen] = useState(key)
  const [words, setWords] = useState('')
  if (key !== seen) {
    setSeen(key)
    setWords(announce(state))
  }
  return words
}

function announce(state: ThreadState): string {
  const status = statusOf(state)
  if (status === 'problem') return state.problem?.message ?? ''
  if (status === 'thinking') return 'The copilot is thinking.'
  const last = lastAnswer(state.turns)
  if (status === 'failed') return `The copilot could not answer. ${last?.error?.message ?? ''}`.trim()
  if (status === 'answered') {
    const first = last?.text?.split(/\n\s*\n/)[0]?.trim()
    return first ? `The copilot answered: ${first}` : 'The copilot answered.'
  }
  return ''
}
