import { cx } from '../lib/cx'

export type ProgressTone = 'accent' | 'healthy' | 'warning' | 'critical'

const FILL: Record<ProgressTone, string> = {
  accent: 'bg-accent',
  healthy: 'bg-healthy',
  warning: 'bg-warning',
  critical: 'bg-critical',
}

export interface ProgressBarProps {
  /** 0..max */
  value?: number
  max?: number
  /** no known total: a bar sweeps the track */
  indeterminate?: boolean
  tone?: ProgressTone
  size?: 'sm' | 'md'
  /** accessible name; shown above the track when `showLabel` */
  label?: string
  showLabel?: boolean
  /** "42%" at the right of the label row */
  showValue?: boolean
  className?: string
}

/** Thin track with a tone fill; indeterminate when the total is unknown. */
export function ProgressBar({ value = 0, max = 100, indeterminate = false, tone = 'accent', size = 'md', label, showLabel, showValue, className }: ProgressBarProps) {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0
  const hasHeader = (showLabel && label) || (showValue && !indeterminate)
  return (
    <div className={className}>
      {hasHeader && (
        <div className="mb-1 flex items-center justify-between gap-3 text-caption">
          <span className="text-secondary">{showLabel ? label : ''}</span>
          {showValue && !indeterminate && <span className="tabular-nums text-tertiary">{Math.round(pct)}%</span>}
        </div>
      )}
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={indeterminate ? undefined : value}
        className={cx('relative w-full overflow-hidden rounded-full bg-surface-secondary', size === 'sm' ? 'h-1' : 'h-1.5')}
      >
        <div
          className={cx('absolute inset-y-0 left-0 rounded-full transition-[width] duration-300', FILL[tone], indeterminate && 'animate-progress w-1/3')}
          style={indeterminate ? undefined : { width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
