import type { ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon } from './Icon'
import { Kbd, modKeyLabel } from './Kbd'

export interface AppHeaderProps {
  /** breadcrumb, page title or brand at the left */
  left?: ReactNode
  /** actions at the right */
  right?: ReactNode
  /** opens the command palette; the centre becomes a search trigger when set */
  onSearch?: () => void
  searchLabel?: string
  /** replaces the search trigger in the centre */
  center?: ReactNode
  className?: string
}

/** The glass sticky header: left slot, centre search/command trigger with the ⌘K hint, right slot. */
export function AppHeader({ left, right, onSearch, searchLabel = 'Search or jump to…', center, className }: AppHeaderProps) {
  return (
    <header className={cx('glass sticky top-0 z-40 border-b border-stroke-light', className)}>
      <div className="grid h-14 w-full grid-cols-[1fr_auto_1fr] items-center gap-4 px-4 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">{left}</div>
        <div className="flex w-[min(28rem,40vw)] justify-center">
          {center ??
            (onSearch && (
              <button
                type="button"
                onClick={onSearch}
                aria-label="Search"
                aria-keyshortcuts={`${modKeyLabel()}+K`}
                className="flex w-full items-center gap-2.5 rounded-md border border-stroke-light bg-surface-secondary px-3 py-1.5 text-left text-sm text-tertiary transition-colors hover:border-stroke hover:text-secondary"
              >
                <Icon name="search" size={15} className="shrink-0" />
                <span className="min-w-0 flex-1 truncate">{searchLabel}</span>
                <Kbd className="shrink-0">{modKeyLabel()}K</Kbd>
              </button>
            ))}
        </div>
        <div className="flex min-w-0 items-center justify-end gap-1">{right}</div>
      </div>
    </header>
  )
}
