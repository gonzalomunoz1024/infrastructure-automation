import { useCallback, useEffect, useSyncExternalStore } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

/** The Atlas key: 'light' | 'dark' | 'system'; absent means system. */
export const THEME_STORAGE_KEY = 'atlas-theme'

/*
 * One store for the whole app: every useTheme() instance reads the same mode,
 * so a Segmented in Settings and the toggle in the user menu never disagree.
 */
const listeners = new Set<() => void>()
let mode: ThemeMode | null = null
let snapshot = ''

function readStored(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY)
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
  } catch {
    return 'system'
  }
}

function current(): ThemeMode {
  if (mode === null) mode = readStored()
  return mode
}

export function systemPrefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

export function resolveTheme(m: ThemeMode): ResolvedTheme {
  return m === 'dark' || (m === 'system' && systemPrefersDark()) ? 'dark' : 'light'
}

/** Puts `.dark` on <html> (and the matching color-scheme) for a mode. */
export function applyTheme(m: ThemeMode): ResolvedTheme {
  const resolved = resolveTheme(m)
  if (typeof document !== 'undefined') {
    document.documentElement.classList.toggle('dark', resolved === 'dark')
    document.documentElement.style.colorScheme = resolved
  }
  return resolved
}

function refresh() {
  snapshot = `${current()}:${resolveTheme(current())}`
  listeners.forEach((l) => l())
}

export function getThemeMode(): ThemeMode {
  return current()
}

export function setThemeMode(next: ThemeMode) {
  mode = next
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next)
  } catch {
    /* private mode, quota: the choice still applies for this session */
  }
  applyTheme(next)
  refresh()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function getSnapshot(): string {
  if (!snapshot) snapshot = `${current()}:${resolveTheme(current())}`
  return snapshot
}

function getServerSnapshot(): string {
  return 'system:light'
}

export interface Theme {
  mode: ThemeMode
  /** what is on screen right now */
  resolved: ResolvedTheme
  setMode: (mode: ThemeMode) => void
  /** flip to the explicit opposite of the current appearance */
  cycle: () => void
}

/**
 * Tri-state light/dark/system theme, persisted to localStorage and reactive to the OS
 * preference while in "system" mode. Shared across every caller.
 */
export function useTheme(): Theme {
  const snap = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
  const [m, r] = snap.split(':') as [ThemeMode, ResolvedTheme]

  useEffect(() => {
    applyTheme(current())
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => {
      if (current() === 'system') {
        applyTheme('system')
        refresh()
      }
    }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  const cycle = useCallback(() => {
    setThemeMode(resolveTheme(current()) === 'dark' ? 'light' : 'dark')
  }, [])

  return { mode: m, resolved: r, setMode: setThemeMode, cycle }
}
