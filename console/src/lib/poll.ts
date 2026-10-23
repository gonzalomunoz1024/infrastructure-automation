import { useEffect, useRef } from 'react'

/*
 * Calls `fn` every `ms` while `active`: how a page follows a request it started (a create, a
 * restart) until the request ends, without a timer per query. The latest `fn` is always the one
 * called, so the caller needs no memoisation.
 */
export function usePoll(active: boolean, fn: () => void, ms = 2_500) {
  const ref = useRef(fn)
  useEffect(() => {
    ref.current = fn
  })
  useEffect(() => {
    if (!active) return
    const t = setInterval(() => ref.current(), ms)
    return () => clearInterval(t)
  }, [active, ms])
}

/** The statuses of a request that has not ended yet. */
export const OPEN_STATUSES: ReadonlySet<string> = new Set(['queued', 'running', 'waiting-approval', 'needs-attention'])

export function isOpenStatus(status: string | undefined | null): boolean {
  return !!status && OPEN_STATUSES.has(status)
}
