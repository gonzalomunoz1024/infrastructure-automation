import type { ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'

export interface EmptyStateProps {
  icon: IconName
  title: string
  message?: ReactNode
  /** tint the icon (e.g. 'text-healthy' for positive empty states) */
  iconClassName?: string
  /** a button or link under the message */
  action?: ReactNode
  /** less padding, for table bodies and drawers */
  compact?: boolean
  className?: string
}

/** Considered empty state: quiet icon, one confident line, optional detail. */
export function EmptyState({ icon, title, message, iconClassName, action, compact, className }: EmptyStateProps) {
  return (
    <div className={cx('flex h-full flex-col items-center justify-center gap-3 text-center', compact ? 'p-6' : 'p-10', className)}>
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-secondary">
        <Icon name={icon} size={22} className={cx('text-tertiary', iconClassName)} />
      </div>
      <div className="max-w-md">
        <p className="text-sm font-medium text-primary">{title}</p>
        {message && <p className="mt-1 text-footnote text-tertiary">{message}</p>}
      </div>
      {action && <div className="mt-1 flex items-center gap-2">{action}</div>}
    </div>
  )
}
