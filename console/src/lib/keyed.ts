import { useCallback, useState } from 'react'
import type { CursorPaging } from '@iaf/console-kit'

/*
 * State that belongs to one key and starts over when the key changes: the cursor trail of a list
 * belongs to its filters, sort and page size, the selection to the page on screen. Kept with the
 * key it was made for and read back only for that key, so a new key needs no effect to reset it.
 */

export function useKeyedState<T>(key: string, initial: () => T): [T, (update: (current: T) => T) => void] {
  const [state, setState] = useState<{ key: string; value: T }>(() => ({ key, value: initial() }))
  const value = state.key === key ? state.value : initial()
  const update = useCallback((change: (current: T) => T) => setState((s) => ({ key, value: change(s.key === key ? s.value : initial()) })), [key, initial])
  return [value, update]
}

const noCursors = (): string[] => []

/** Cursor paging (`after`, previous by the trail) that starts at the first page for every new key. */
export function useKeyedPaging(key: string): CursorPaging {
  const [trail, setTrail] = useKeyedState(key, noCursors)
  const next = useCallback((cursor: string) => setTrail((t) => [...t, cursor]), [setTrail])
  const previous = useCallback(() => setTrail((t) => t.slice(0, -1)), [setTrail])
  const reset = useCallback(() => setTrail(noCursors), [setTrail])
  return { after: trail[trail.length - 1], page: trail.length + 1, hasPrevious: trail.length > 0, next, previous, reset }
}
