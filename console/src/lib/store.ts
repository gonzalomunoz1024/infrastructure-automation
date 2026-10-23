import { useSyncExternalStore } from 'react'

/*
 * A value kept in localStorage and read by React: the dev identity, the account, the region,
 * the recent pages. Storage can be missing or full (private windows, quotas), so every read and
 * write is guarded and the value still lives for the session.
 */

export interface Store<T> {
  get: () => T
  set: (next: T) => void
  use: () => T
  subscribe: (listener: () => void) => () => void
}

export function createStore<T>(key: string, fallback: T, parse: (raw: unknown) => T | null = (raw) => raw as T): Store<T> {
  let value: T | undefined
  const listeners = new Set<() => void>()
  const read = (): T => {
    if (value !== undefined) return value
    try {
      const raw = localStorage.getItem(key)
      value = raw === null ? fallback : (parse(JSON.parse(raw)) ?? fallback)
    } catch {
      value = fallback
    }
    return value
  }
  const subscribe = (listener: () => void) => {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }
  const getServer = () => fallback
  return {
    get: read,
    set: (next) => {
      value = next
      try {
        if (next === null || next === undefined) localStorage.removeItem(key)
        else localStorage.setItem(key, JSON.stringify(next))
      } catch {
        /* storage unavailable: the choice lives for this session */
      }
      listeners.forEach((l) => l())
    },
    use: () => useSyncExternalStore(subscribe, read, getServer),
    subscribe,
  }
}
