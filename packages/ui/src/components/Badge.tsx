import { cx } from '../lib/cx'

export interface BadgeProps {
  count: number
  /** counts above this render as "99+" */
  max?: number
  tone?: 'neutral' | 'accent' | 'critical'
  /** render even when the count is zero */
  showZero?: boolean
  className?: string
}

const TONE = {
  neutral: 'bg-surface-secondary text-secondary',
  accent: 'bg-accent text-white',
  critical: 'bg-critical text-white',
}

/** Count pill for nav items, tabs and headers. */
export function Badge({ count, max = 99, tone = 'neutral', showZero, className }: BadgeProps) {
  if (count === 0 && !showZero) return null
  return (
    <span
      className={cx(
        'inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full px-1.5 text-[10px] font-semibold tabular-nums leading-none',
        TONE[tone],
        className,
      )}
    >
      {count > max ? `${max}+` : count}
    </span>
  )
}
