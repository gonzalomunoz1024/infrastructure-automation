import type { ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon } from './Icon'

export interface PageHeaderProps {
  title: ReactNode
  subtitle?: ReactNode
  /** buttons at the right */
  actions?: ReactNode
  /** small uppercase line above the title (a section, a folder) */
  eyebrow?: ReactNode
  /** "← Jobs" link above the title */
  back?: { href: string; label: string; onClick?: () => void }
  /** a tab row or filter bar under the title */
  children?: ReactNode
  className?: string
}

/** Page title block: title1, quiet subtitle, actions at the right. */
export function PageHeader({ title, subtitle, actions, eyebrow, back, children, className }: PageHeaderProps) {
  return (
    <div className={cx('mb-6', className)}>
      {back && (
        <a
          href={back.href}
          onClick={back.onClick}
          className="mb-2 inline-flex items-center gap-1 text-caption text-secondary transition-colors hover:text-primary"
        >
          <Icon name="chevron-left" size={12} />
          {back.label}
        </a>
      )}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          {eyebrow && <div className="mb-1 text-caption font-medium uppercase tracking-wide text-tertiary">{eyebrow}</div>}
          <h1 className="text-title1 font-semibold tracking-tight text-primary">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-secondary">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children && <div className="mt-4">{children}</div>}
    </div>
  )
}
