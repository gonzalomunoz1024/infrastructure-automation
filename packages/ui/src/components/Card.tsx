import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from '../lib/cx'

export interface CardProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: ReactNode
  subtitle?: ReactNode
  /** controls at the right of the title */
  actions?: ReactNode
  /** remove the body padding (tables, code) */
  flush?: boolean
  /** hover lift + press feedback for clickable cards */
  interactive?: boolean
  children?: ReactNode
}

/** The one card: hairline, soft shadow, optional titled header. */
export function Card({ title, subtitle, actions, flush, interactive, className, children, ...rest }: CardProps) {
  const hasHeader = title || subtitle || actions
  return (
    <div
      className={cx(
        'rounded-lg border border-stroke-light bg-surface shadow-card',
        interactive && 'cursor-pointer transition-all duration-150 hover:border-accent/40 hover:shadow-md active:scale-[0.99]',
        className,
      )}
      {...rest}
    >
      {hasHeader && (
        <div className={cx('flex items-start justify-between gap-4 px-5 pt-4', children ? 'pb-3' : 'pb-4')}>
          <div className="min-w-0">
            {title && <h3 className="text-subheadline font-semibold text-primary">{title}</h3>}
            {subtitle && <p className="mt-0.5 text-caption text-secondary">{subtitle}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </div>
      )}
      {children !== undefined && <div className={cx(!flush && (hasHeader ? 'px-5 pb-5' : 'p-5'))}>{children}</div>}
    </div>
  )
}

export interface CardRowProps {
  label: ReactNode
  /** quiet line under the label */
  description?: ReactNode
  children?: ReactNode
  /** the value under the label, full width: for long values (paths, URLs) in narrow cards */
  stacked?: boolean
  className?: string
}

/** Settings-style row: label at the left, control or value at the right, hairline between rows. */
export function CardRow({ label, description, children, stacked, className }: CardRowProps) {
  if (stacked) {
    return (
      <div className={cx('border-b border-stroke-light py-3 last:border-b-0 last:pb-0 first:pt-0', className)}>
        <div className="text-sm text-primary">{label}</div>
        {description && <div className="mt-0.5 text-caption text-tertiary">{description}</div>}
        {children !== undefined && <div className="mt-1.5 flex min-w-0 flex-wrap items-center gap-2 text-sm text-secondary">{children}</div>}
      </div>
    )
  }
  return (
    <div className={cx('flex items-center justify-between gap-6 border-b border-stroke-light py-3 last:border-b-0 last:pb-0 first:pt-0', className)}>
      <div className="min-w-28 shrink-0 basis-1/3">
        <div className="text-sm text-primary">{label}</div>
        {description && <div className="mt-0.5 text-caption text-tertiary">{description}</div>}
      </div>
      {children !== undefined && (
        <div className="flex min-w-0 flex-wrap items-center justify-end gap-2 text-sm text-secondary">{children}</div>
      )}
    </div>
  )
}
