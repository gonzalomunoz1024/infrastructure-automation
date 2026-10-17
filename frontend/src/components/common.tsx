import { useEffect, useState, type MouseEvent, type ReactNode } from 'react'
import { Button, CopyIconButton, EmptyState, Tag, cx, longDate, relativeTime, type DotKind, type Tone } from '@iaf/ui'
import { Link } from '../app/router'
import { errorMessage, isApiError } from '../lib/api'
import type { CheckStatus, Effect } from '../lib/types'

/*
 * Small pieces every page shares: identifiers, times, fact lists, the error state, the tags
 * whose tone depends on a platform word (effects, resource states, checks), and two time helpers.
 */

/** An identifier in SF Mono. */
export function Mono({ children, className, title }: { children: ReactNode; className?: string; title?: string }) {
  return (
    <span title={title} className={cx('font-mono text-caption text-secondary', className)}>
      {children}
    </span>
  )
}

/** A mono identifier with a copy button at its right. */
export function CopyableId({ id, className }: { id: string; className?: string }) {
  return (
    <span className={cx('inline-flex items-center gap-1', className)}>
      <Mono className="text-primary">{id}</Mono>
      <CopyIconButton text={id} label="Copy id" />
    </span>
  )
}

/** "3m ago" with the full date as the tooltip; a dash when there is no date. */
export function When({ at, className, now }: { at?: string | null; className?: string; now?: number }) {
  if (!at) return <span className={cx('text-tertiary', className)}>—</span>
  return (
    <time dateTime={at} title={longDate(at)} className={cx('tabular-nums', className)}>
      {relativeTime(at, now)}
    </time>
  )
}

export interface FactItem {
  label: ReactNode
  value: ReactNode
  /** spans the whole row */
  wide?: boolean
}

/** Label-over-value facts in a responsive grid: the header of every detail page. */
export function Facts({ items, className, columns = 3 }: { items: FactItem[]; className?: string; columns?: 2 | 3 | 4 }) {
  const cols = columns === 2 ? 'sm:grid-cols-2' : columns === 4 ? 'sm:grid-cols-2 lg:grid-cols-4' : 'sm:grid-cols-2 lg:grid-cols-3'
  return (
    <dl className={cx('grid grid-cols-1 gap-x-8 gap-y-4', cols, className)}>
      {items.map((f, i) => (
        <div key={i} className={cx('min-w-0', f.wide && 'sm:col-span-full')}>
          <dt className="text-caption text-tertiary">{f.label}</dt>
          <dd className="mt-0.5 min-w-0 text-sm text-primary">{f.value ?? <span className="text-tertiary">—</span>}</dd>
        </div>
      ))}
    </dl>
  )
}

/** A titled block outside a card: title3-ish heading, optional controls, the content under a little air. */
export function Section({ title, description, actions, children, className }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; children?: ReactNode; className?: string }) {
  return (
    <section className={className}>
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-subheadline font-semibold text-primary">{title}</h2>
          {description && <p className="mt-0.5 text-caption text-secondary">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
      {children}
    </section>
  )
}

/** The failed-load state of a view: what went wrong and a way to try again. */
export function ErrorState({ error, title = 'Could not load this', onRetry, compact, className }: { error: unknown; title?: string; onRetry?: () => void; compact?: boolean; className?: string }) {
  const unreachable = isApiError(error) && error.unreachable
  const notFound = isApiError(error) && error.status === 404
  return (
    <EmptyState
      icon={notFound ? 'search' : 'alert-triangle'}
      iconClassName={notFound ? undefined : 'text-warning'}
      title={notFound ? 'Not found' : unreachable ? 'Backend unreachable' : title}
      message={notFound ? 'Nothing lives at this address any more.' : errorMessage(error)}
      compact={compact}
      className={className}
      action={
        onRetry && (
          <Button size="sm" icon="refresh" onClick={onRetry}>
            Try again
          </Button>
        )
      }
    />
  )
}

/** A text link to a route, in the accent. */
export function TextLink({ to, children, className, mono, onClick }: { to: string; children: ReactNode; className?: string; mono?: boolean; onClick?: (e: MouseEvent<HTMLAnchorElement>) => void }) {
  return (
    <Link to={to} onClick={onClick} className={cx('text-accent transition-opacity hover:opacity-70', mono && 'font-mono text-caption', className)}>
      {children}
    </Link>
  )
}

/** An outside link (change tickets, provider URLs). */
export function ExternalLink({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" className={cx('text-accent transition-opacity hover:opacity-70', className)}>
      {children}
    </a>
  )
}

const EFFECT_TONE: Record<Effect, Tone> = { create: 'accent', change: 'warning', delete: 'critical', read: 'neutral', none: 'neutral', unknown: 'neutral' }

/** create / change / delete / read as a tinted capsule; delete is the only critical one. */
export function EffectTag({ effect, size = 'sm' }: { effect: Effect | string; size?: 'sm' | 'md' }) {
  const e = (effect in EFFECT_TONE ? effect : 'unknown') as Effect
  return (
    <Tag tone={EFFECT_TONE[e]} size={size} outline={e === 'unknown' || e === 'none'}>
      {e}
    </Tag>
  )
}

/** The tone of a resource state: ready is healthy, building is in flight, deprecated warns, retired and failed recede. */
export function resourceTone(status: string): Tone {
  const s = status.toLowerCase()
  if (s === 'ready' || s === 'active' || s === 'fulfilled') return 'healthy'
  if (s === 'building' || s === 'pending' || s === 'creating') return 'accent'
  if (s === 'deprecated' || s === 'degraded') return 'warning'
  if (s === 'failed' || s === 'error') return 'critical'
  return 'neutral'
}

export function checkDot(status: CheckStatus | string): DotKind {
  if (status === 'passed') return 'healthy'
  if (status === 'failed') return 'critical'
  return 'neutral'
}

/** "needs-attention" → "Needs attention". */
export function titleCase(s: string): string {
  const t = s.replace(/[-_]+/g, ' ')
  return t.charAt(0).toUpperCase() + t.slice(1)
}

/** The current time, refreshed every `intervalMs`, for countdowns and relative times that must move. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}

const UNIT_MS: Record<string, number> = { ms: 1, s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000, w: 604_800_000 }

/** "4h", "2h30m", "90s", "PT4H", "P1DT2H" → milliseconds; null when unreadable. */
export function parseDuration(text: string | undefined | null): number | null {
  if (!text) return null
  const s = text.trim()
  const iso = /^P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(s)
  if (iso) {
    const [, w, d, h, m, sec] = iso
    return (Number(w ?? 0) * 7 + Number(d ?? 0)) * UNIT_MS.d + Number(h ?? 0) * UNIT_MS.h + Number(m ?? 0) * UNIT_MS.m + Number(sec ?? 0) * UNIT_MS.s
  }
  let total = 0
  let matched = false
  const re = /(\d+(?:\.\d+)?)\s*(ms|s|m|h|d|w)\b/g
  let m: RegExpExecArray | null
  while ((m = re.exec(s))) {
    total += Number(m[1]) * UNIT_MS[m[2]]
    matched = true
  }
  if (!matched && /^\d+$/.test(s)) return Number(s) * UNIT_MS.s
  return matched ? total : null
}

/** "in 3h 20m" or "expired 5m ago" for a deadline. */
export function countdown(deadline: number, now: number): { text: string; expired: boolean } {
  const diff = deadline - now
  const expired = diff <= 0
  const abs = Math.abs(diff)
  const h = Math.floor(abs / UNIT_MS.h)
  const m = Math.floor((abs % UNIT_MS.h) / UNIT_MS.m)
  const d = Math.floor(abs / UNIT_MS.d)
  let span: string
  if (d >= 2) span = `${d}d ${Math.floor((abs % UNIT_MS.d) / UNIT_MS.h)}h`
  else if (h >= 1) span = `${h}h ${m}m`
  else if (m >= 1) span = `${m}m`
  else span = 'under a minute'
  return { text: expired ? `expired ${span} ago` : `in ${span}`, expired }
}

/** The deadline of something that times out, from when it was requested and its timeout word. */
export function deadlineOf(requestedAt: string | undefined, timeout: string | undefined): number | null {
  if (!requestedAt) return null
  const ms = parseDuration(timeout)
  if (ms === null) return null
  const start = new Date(requestedAt).getTime()
  return Number.isNaN(start) ? null : start + ms
}

/** The short form of a job id for tables: the prefix and the tail. */
export function shortJobId(id: string): string {
  if (id.length <= 14) return id
  const sep = id.indexOf('_')
  const prefix = sep > 0 ? id.slice(0, sep + 1) : ''
  return `${prefix}…${id.slice(-6)}`
}

/** A one-line summary of a JSON value for table cells. */
export function summarize(value: unknown, max = 60): string {
  let s: string
  if (value === undefined || value === null) s = '—'
  else if (typeof value === 'string') s = value
  else if (typeof value === 'object') {
    s = Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => `${k}: ${typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)}`)
      .join(' · ')
  } else s = String(value)
  return s.length > max ? `${s.slice(0, max - 1)}…` : s
}
