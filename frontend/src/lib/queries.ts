import { useCallback, useEffect, useRef, useState } from 'react'

/*
 * Tiny data hooks, no library: useQuery caches by key (stale-while-revalidate),
 * refetches on invalidate() and on an interval; useMutation tracks one call.
 */

export type QueryKey = string | readonly unknown[]

/** 'jobs' · ['job', id] → 'job/<id>' — prefixes make invalidation easy. */
export function keyOf(key: QueryKey): string {
  if (typeof key === 'string') return key
  return key.map((part) => (typeof part === 'string' ? part : JSON.stringify(part ?? null))).join('/')
}

const cache = new Map<string, unknown>()
const listeners = new Set<(prefix: string) => void>()

function matches(key: string, prefix: string): boolean {
  return prefix === '' || key === prefix || key.startsWith(`${prefix}/`)
}

/** Refetch every mounted query whose key is `prefix` or starts with `prefix/` (all when omitted). */
export function invalidate(prefix = '') {
  listeners.forEach((l) => l(prefix))
}

/** Drop cached data (next mount shows a skeleton instead of stale rows). */
export function clearQueryCache(prefix = '') {
  for (const k of Array.from(cache.keys())) if (matches(k, prefix)) cache.delete(k)
}

export interface QueryOptions {
  /** false pauses the query (no fetch, no interval) */
  enabled?: boolean
  /** refetch every n ms while mounted */
  refetchInterval?: number
}

export interface QueryResult<T> {
  data: T | undefined
  error: unknown
  /** no data yet and a request is in flight: show a Skeleton */
  loading: boolean
  /** a request is in flight (data may already be on screen) */
  fetching: boolean
  refetch: () => Promise<void>
}

interface QueryState<T> {
  key: string
  data: T | undefined
  error: unknown
  fetching: boolean
}

export function useQuery<T>(key: QueryKey, fn: () => Promise<T>, opts: QueryOptions = {}): QueryResult<T> {
  const { enabled = true, refetchInterval } = opts
  const k = keyOf(key)
  const fnRef = useRef(fn)
  useEffect(() => {
    fnRef.current = fn
  })
  const seq = useRef(0)
  const [state, setState] = useState<QueryState<T>>(() => ({ key: k, data: cache.get(k) as T | undefined, error: null, fetching: enabled }))

  const run = useCallback(async () => {
    const id = ++seq.current
    setState((s) => ({
      key: k,
      data: s.key === k ? s.data : (cache.get(k) as T | undefined),
      error: s.key === k ? s.error : null,
      fetching: true,
    }))
    try {
      const data = await fnRef.current()
      if (id !== seq.current) return
      cache.set(k, data)
      setState({ key: k, data, error: null, fetching: false })
    } catch (error) {
      if (id !== seq.current) return
      setState((s) => ({ key: k, data: s.key === k ? s.data : undefined, error, fetching: false }))
    }
  }, [k])

  useEffect(() => {
    if (!enabled) {
      seq.current++
      setState((s) => (s.key === k && !s.fetching ? s : { key: k, data: s.key === k ? s.data : (cache.get(k) as T | undefined), error: s.key === k ? s.error : null, fetching: false }))
      return
    }
    void run()
    const onInvalidate = (prefix: string) => {
      if (matches(k, prefix)) void run()
    }
    listeners.add(onInvalidate)
    const timer = refetchInterval && refetchInterval > 0 ? setInterval(() => void run(), refetchInterval) : undefined
    // the live counter, not a snapshot: bumping it drops answers still in flight
    const requests = seq
    return () => {
      listeners.delete(onInvalidate)
      if (timer) clearInterval(timer)
      requests.current++
    }
  }, [k, enabled, refetchInterval, run])

  const current: QueryState<T> = state.key === k ? state : { key: k, data: cache.get(k) as T | undefined, error: null, fetching: enabled }
  return {
    data: current.data,
    error: current.error,
    loading: current.fetching && current.data === undefined,
    fetching: current.fetching,
    refetch: run,
  }
}

export interface MutationOptions<A extends unknown[], T> {
  onSuccess?: (data: T, ...args: A) => void
  onError?: (error: unknown, ...args: A) => void
  /** query key prefixes to refetch after success */
  invalidates?: string[]
}

export interface MutationResult<A extends unknown[], T> {
  /** resolves with the data, or undefined when the call failed (see `error`) */
  mutate: (...args: A) => Promise<T | undefined>
  loading: boolean
  error: unknown
  data: T | undefined
  reset: () => void
}

export function useMutation<A extends unknown[], T>(fn: (...args: A) => Promise<T>, opts: MutationOptions<A, T> = {}): MutationResult<A, T> {
  const [state, setState] = useState<{ loading: boolean; error: unknown; data: T | undefined }>({ loading: false, error: null, data: undefined })
  const fnRef = useRef(fn)
  const optsRef = useRef(opts)
  const mounted = useRef(true)
  useEffect(() => {
    fnRef.current = fn
    optsRef.current = opts
  })
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const mutate = useCallback(async (...args: A): Promise<T | undefined> => {
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const data = await fnRef.current(...args)
      if (mounted.current) setState({ loading: false, error: null, data })
      optsRef.current.invalidates?.forEach((p) => invalidate(p))
      optsRef.current.onSuccess?.(data, ...args)
      return data
    } catch (error) {
      if (mounted.current) setState((s) => ({ ...s, loading: false, error }))
      optsRef.current.onError?.(error, ...args)
      return undefined
    }
  }, [])

  const reset = useCallback(() => setState({ loading: false, error: null, data: undefined }), [])
  return { mutate, loading: state.loading, error: state.error, data: state.data, reset }
}
