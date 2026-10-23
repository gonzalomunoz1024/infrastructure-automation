import { StatusDot, cx, jobState, type DotKind } from '@iaf/ui'
import { titleCase } from './common'

/*
 * A status word the way the console shows every status: the colour in the dot, the word in the
 * primary text colour on a soft tint, so the label reads at AA on both themes. Jobs, steps and
 * anything else with a state word fold onto Atlas's six job states (jobState), plus the console's
 * own reading of the words that mean "undone" (rolled back, compensated). Atlas's StateTag puts
 * the colour in the text, which does not reach AA on its tints: a note for P-14.
 */

export type StatusTone = 'queued' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'cancelled' | 'undone'

const STYLE: Record<StatusTone, { tint: string; dot?: DotKind; varName?: string }> = {
  queued: { tint: 'bg-surface-secondary', dot: 'neutral' },
  running: { tint: 'bg-accent-tint', varName: '--color-accent' },
  waiting: { tint: 'bg-warning-tint', dot: 'warning' },
  succeeded: { tint: 'bg-healthy-tint', dot: 'healthy' },
  failed: { tint: 'bg-critical-tint', dot: 'critical' },
  cancelled: { tint: 'bg-surface-secondary', dot: 'neutral' },
  undone: { tint: 'bg-warning-tint', dot: 'warning' },
}

/** The tone of a status word: rolled back and compensated are undone; the rest is Atlas's folding. */
export function statusTone(status: string): StatusTone {
  const s = status.toLowerCase()
  if (s === 'rolled-back' || s === 'compensated') return 'undone'
  return jobState(s)
}

export interface StatusBadgeProps {
  /** the status word, as the platform spells it */
  status: string
  /** the text shown; defaults to the word in sentence case ("Waiting approval") */
  label?: string
  size?: 'sm' | 'md'
  className?: string
}

export function StatusBadge({ status, label, size = 'md', className }: StatusBadgeProps) {
  const tone = statusTone(status)
  const style = STYLE[tone]
  // running and waiting are live: their dot pulses
  const pulse = tone === 'running' || /wait/i.test(status)
  return (
    <span className={cx('inline-flex max-w-full items-center gap-1.5 rounded-full font-medium text-primary', size === 'sm' ? 'h-5 px-1.5 text-caption2' : 'h-6 px-2 text-caption', style.tint, className)} data-status={status} data-tone={tone}>
      {/* the word carries the meaning; the dot is decoration for assistive technology */}
      <span aria-hidden="true" className="flex">
        <StatusDot kind={style.dot} varName={style.varName} pulse={pulse} size="sm" />
      </span>
      <span className="truncate">{label ?? titleCase(status)}</span>
    </span>
  )
}
