import { createContext, useCallback, useContext, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { assistantApi, type AssistantContext } from '../../lib/assistant'
import { useConsole } from '../console'
import { currentHash, routeFor } from '../router'
import { contextOf } from './proposals'
import { createThreadStore, type ThreadState, type ThreadStore } from './thread'

/*
 * Who may open the copilot's panel, and with what: the header's button toggles it, the search
 * palette asks it a question, and a resource page's Explain asks it to explain that resource.
 * Each seed carries a number and starts exactly one turn. The conversation lives here, one per
 * person and account, so it survives the panel closing and the page changing; New conversation
 * forgets it. Closing the panel from inside it gives the focus back to what opened it (or to the
 * header's button when that is gone, as after the palette).
 */

export interface CopilotSeed {
  intent: 'ask' | 'explain'
  /** the question, for `ask` */
  message?: string
  /** `explain` needs `wfrn`; left out, the context is the page the person is on */
  context?: AssistantContext
  /** what the conversation shows of the question until the copilot has it: "Explain web-01" */
  label?: string
  /** the resource an Explain is about, to title its explanation */
  name?: string
}

export interface Copilot {
  isOpen: boolean
  /** the latest seed and its number; each number starts one turn */
  seed?: CopilotSeed & { n: number }
  /** how many times the panel was asked to open, so it takes the focus each time */
  opened: number
  open: (seed?: CopilotSeed) => void
  /** closes the panel; when the focus was in it, the focus goes back to the opener or the header's button */
  close: () => void
  toggle: () => void
  /** the conversation of the person signed in, in the account they are in */
  thread: ThreadStore
}

export const PANEL_ID = 'copilot-panel'

const threads = new Map<string, ThreadStore>()

/** One conversation per person and account, kept for the page's life (the copilot keeps it 24 hours). */
function threadFor(key: string): ThreadStore {
  let thread = threads.get(key)
  if (!thread) {
    thread = createThreadStore(assistantApi)
    threads.set(key, thread)
  }
  return thread
}

const NONE: Copilot = { isOpen: false, opened: 0, open: () => {}, close: () => {}, toggle: () => {}, thread: createThreadStore(assistantApi) }

const CopilotContext = createContext<Copilot>(NONE)

/** What the conversation shows of a seed until the copilot has it. */
export function echoOf(seed: CopilotSeed): string {
  if (seed.label) return seed.label
  if (seed.intent === 'ask') return seed.message ?? ''
  return seed.name ? `Explain ${seed.name}` : 'Explain this resource'
}

/** Keeps the panel's state and the conversation for the shell inside it. */
export function CopilotRoot({ children }: { children: ReactNode }) {
  const { session } = useConsole()
  const thread = threadFor(`${session.user.userId}/${session.account.accountId}`)
  const [isOpen, setOpen] = useState(false)
  const [opened, setOpened] = useState(0)
  const [seed, setSeed] = useState<(CopilotSeed & { n: number }) | undefined>()
  const opener = useRef<HTMLElement | null>(null)

  const open = useCallback(
    (next?: CopilotSeed) => {
      const active = document.activeElement
      if (active instanceof HTMLElement && active !== document.body && !active.closest(`#${PANEL_ID}`)) opener.current = active
      setOpen(true)
      setOpened((n) => n + 1)
      if (!next) return
      setSeed((prev) => ({ ...next, n: (prev?.n ?? 0) + 1 }))
      const context = next.context ?? contextOf(routeFor(currentHash()))
      thread.ask({ intent: next.intent, ...(next.message ? { message: next.message } : {}), context }, echoOf(next), next.name)
    },
    [thread],
  )

  const close = useCallback(() => {
    setOpen(false)
    if (!document.activeElement?.closest(`#${PANEL_ID}`)) return
    const back = opener.current?.isConnected ? opener.current : document.querySelector<HTMLElement>(`header [aria-controls="${PANEL_ID}"]`)
    back?.focus()
  }, [])

  const toggle = useCallback(() => (isOpen ? close() : open()), [isOpen, open, close])

  const value = useMemo(() => ({ isOpen, seed, opened, open, close, toggle, thread }), [isOpen, seed, opened, open, close, toggle, thread])
  return <CopilotContext.Provider value={value}>{children}</CopilotContext.Provider>
}

/** The copilot of the shell around the page; outside one (a test, the sign-in page) it does nothing. */
export function useCopilot(): Copilot {
  return useContext(CopilotContext)
}

/** The conversation, as it changes. */
export function useThread(thread: ThreadStore): ThreadState {
  return useSyncExternalStore(thread.subscribe, thread.get, thread.get)
}
