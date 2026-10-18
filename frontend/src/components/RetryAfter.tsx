/**
 * What a 429 asks for. Every call that starts jobs takes a token from the caller's team; an
 * empty bucket answers 429 with Retry-After and the backend's message. The submit stays
 * disabled until that passes, counting down, and the message says why.
 */
import { useCallback, useEffect, useState } from 'react'
import { Icon, cx } from '@iaf/ui'
import { retryIn, type ApiError } from '../lib/api'

export interface Quota {
  /** the submit must wait: Retry-After has not passed */
  blocked: boolean
  secondsLeft: number
  /** the backend's message of the last 429, until the next attempt */
  message: string | null
  /** holds the submit for the 429's Retry-After */
  block: (e: ApiError) => void
  clear: () => void
}

/** "team-compute is over its quota — try again in 12 s": the backend's message and when, for a toast. */
export function quotaMessage(e: Pick<ApiError, 'error' | 'retryAfter'>): string {
  const when = retryIn(e.retryAfter)
  return when ? `${e.error} — ${when}` : e.error
}

/** The wait a 429 asks for: `block(e)` holds the submit until Retry-After passes, a second at a time. */
export function useRetryAfter(): Quota {
  const [until, setUntil] = useState<number | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const blocked = until !== null && now < until
  useEffect(() => {
    if (until === null || now >= until) return
    const t = setTimeout(() => setNow(Date.now()), Math.min(1000, until - now))
    return () => clearTimeout(t)
  }, [until, now])
  const block = useCallback((e: ApiError) => {
    const at = Date.now()
    setMessage(e.error)
    setNow(at)
    setUntil(e.retryAfter !== undefined && Number.isFinite(e.retryAfter) && e.retryAfter > 0 ? at + Math.ceil(e.retryAfter) * 1000 : null)
  }, [])
  const clear = useCallback(() => {
    setMessage(null)
    setUntil(null)
  }, [])
  return { blocked, secondsLeft: blocked ? Math.max(0, Math.ceil((until - now) / 1000)) : 0, message, block, clear }
}

/** The last 429 under a form: its message, and the countdown while the submit waits. */
export function QuotaNotice({ quota, className }: { quota: Pick<Quota, 'blocked' | 'secondsLeft' | 'message'>; className?: string }) {
  if (!quota.message) return null
  return (
    <p role="status" data-testid="quota-notice" className={cx('flex items-start gap-1.5 text-caption text-warning', className)}>
      <Icon name="clock" size={13} className="mt-0.5 shrink-0" />
      <span>
        {quota.message} — {quota.blocked ? retryIn(quota.secondsLeft) : 'you can try again now'}
      </span>
    </p>
  )
}
