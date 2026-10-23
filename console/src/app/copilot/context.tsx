import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { AssistantContext } from '../../lib/assistant'

/*
 * Who may open the copilot's panel, and with what: the header's button toggles it, and a page
 * opens it seeded, such as a resource page's Explain asking it to explain that resource. Each
 * seed carries a number, so the panel starts a turn for every new seed and never twice for one.
 */

export interface CopilotSeed {
  intent: 'ask' | 'explain'
  /** the question, for `ask` */
  message?: string
  /** `explain` needs `wfrn` */
  context?: AssistantContext
}

export interface Copilot {
  isOpen: boolean
  /** the latest seed and its number; a panel answers each number once */
  seed?: CopilotSeed & { n: number }
  open: (seed?: CopilotSeed) => void
  close: () => void
  toggle: () => void
}

const NONE: Copilot = { isOpen: false, open: () => {}, close: () => {}, toggle: () => {} }

const CopilotContext = createContext<Copilot>(NONE)

/** Keeps the panel's state for the shell inside it. */
export function CopilotRoot({ children }: { children: ReactNode }) {
  const [isOpen, setOpen] = useState(false)
  const [seed, setSeed] = useState<(CopilotSeed & { n: number }) | undefined>()
  const open = useCallback((next?: CopilotSeed) => {
    setOpen(true)
    if (next) setSeed((prev) => ({ ...next, n: (prev?.n ?? 0) + 1 }))
  }, [])
  const close = useCallback(() => setOpen(false), [])
  const toggle = useCallback(() => setOpen((o) => !o), [])
  const value = useMemo(() => ({ isOpen, seed, open, close, toggle }), [isOpen, seed, open, close, toggle])
  return <CopilotContext.Provider value={value}>{children}</CopilotContext.Provider>
}

/** The copilot of the shell around the page; outside one (a test, the sign-in page) it does nothing. */
export function useCopilot(): Copilot {
  return useContext(CopilotContext)
}
