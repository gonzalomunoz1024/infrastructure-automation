import type { ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'

export type Tone = 'neutral' | 'accent' | 'healthy' | 'warning' | 'critical'

const TONE: Record<Tone, string> = {
  neutral: 'bg-surface-secondary text-secondary',
  accent: 'bg-accent-tint text-accent',
  healthy: 'bg-healthy-tint text-healthy',
  warning: 'bg-warning-tint text-warning',
  critical: 'bg-critical-tint text-critical',
}

const DOT: Record<Tone, string> = {
  neutral: 'bg-tertiary',
  accent: 'bg-accent',
  healthy: 'bg-healthy',
  warning: 'bg-warning',
  critical: 'bg-critical',
}

export interface TagProps {
  tone?: Tone
  children: ReactNode
  icon?: IconName
  /** small leading dot in the tone colour */
  dot?: boolean
  /** pulse the dot (live) */
  pulse?: boolean
  mono?: boolean
  uppercase?: boolean
  size?: 'sm' | 'md'
  /** outlined capsule instead of a tinted one */
  outline?: boolean
  className?: string
  title?: string
}

/** Capsule label: status words, owners, environments, counts of things. */
export function Tag({ tone = 'neutral', children, icon, dot, pulse, mono, uppercase, size = 'md', outline, className, title }: TagProps) {
  return (
    <span
      title={title}
      className={cx(
        'inline-flex max-w-full items-center gap-1 whitespace-nowrap rounded-full font-medium',
        size === 'sm' ? 'px-1.5 py-px text-caption2' : 'px-2 py-0.5 text-caption',
        outline ? 'border border-stroke-light text-secondary' : TONE[tone],
        mono && 'font-mono',
        uppercase && 'uppercase tracking-wide',
        className,
      )}
    >
      {dot && <span className={cx('h-1.5 w-1.5 shrink-0 rounded-full', DOT[tone], pulse && 'animate-pulse-subtle')} />}
      {icon && <Icon name={icon} size={size === 'sm' ? 10 : 12} />}
      <span className="truncate">{children}</span>
    </span>
  )
}

/** The job states the platform tints; the API's richer statuses fold onto these. */
export type JobState = 'queued' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'cancelled'

const STATE_CLASS: Record<JobState, string> = {
  queued: 'bg-state-queued-tint text-state-queued',
  running: 'bg-state-running-tint text-state-running',
  waiting: 'bg-state-waiting-tint text-state-waiting',
  succeeded: 'bg-state-succeeded-tint text-state-succeeded',
  failed: 'bg-state-failed-tint text-state-failed',
  cancelled: 'bg-state-cancelled-tint text-state-cancelled',
}

const STATE_DOT: Record<JobState, string> = {
  queued: 'bg-state-queued',
  running: 'bg-state-running',
  waiting: 'bg-state-waiting',
  succeeded: 'bg-state-succeeded',
  failed: 'bg-state-failed',
  cancelled: 'bg-state-cancelled',
}

/** Folds any status word onto one of the six tinted states. */
export function jobState(status: string): JobState {
  const s = status.toLowerCase()
  if (s === 'queued' || s === 'pending') return 'queued'
  if (s === 'running' || s === 'in-progress' || s === 'canary') return 'running'
  if (s.includes('wait') || s.includes('attention') || s === 'paused' || s === 'degraded') return 'waiting'
  if (s === 'succeeded' || s === 'passed' || s === 'promoted' || s === 'approved' || s === 'healthy' || s === 'merged' || s === 'ready' || s === 'published' || s === 'up') return 'succeeded'
  if (s === 'failed' || s === 'rejected' || s === 'rolled-back' || s === 'failing' || s === 'error' || s === 'down') return 'failed'
  if (s === 'cancelled' || s === 'canceled' || s === 'skipped' || s === 'expired' || s === 'closed' || s === 'retired' || s === 'compensated' || s === 'unknown') return 'cancelled'
  return 'cancelled'
}

/** The Tag tone that matches a state. */
export function stateTone(status: string): Tone {
  const state = jobState(status)
  if (state === 'queued' || state === 'running') return 'accent'
  if (state === 'waiting') return 'warning'
  if (state === 'succeeded') return 'healthy'
  if (state === 'failed') return 'critical'
  return 'neutral'
}

export interface StateTagProps {
  /** any API status word; folded with jobState() */
  status: string
  /** display text (defaults to the status) */
  children?: ReactNode
  size?: 'sm' | 'md'
  className?: string
}

/** Status capsule with a leading dot; running pulses. */
export function StateTag({ status, children, size = 'md', className }: StateTagProps) {
  const state = jobState(status)
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full font-medium capitalize',
        size === 'sm' ? 'px-1.5 py-px text-caption2' : 'px-2 py-0.5 text-caption',
        STATE_CLASS[state],
        className,
      )}
    >
      <span className={cx('h-1.5 w-1.5 shrink-0 rounded-full', STATE_DOT[state], state === 'running' && 'animate-pulse-subtle')} />
      {children ?? status.replace(/-/g, ' ')}
    </span>
  )
}
