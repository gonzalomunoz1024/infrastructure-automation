import { useId, type ReactNode } from 'react'
import { cx } from '../lib/cx'

export type TooltipSide = 'top' | 'bottom' | 'left' | 'right'

const SIDE: Record<TooltipSide, string> = {
  top: 'bottom-full left-1/2 mb-1.5 -translate-x-1/2',
  bottom: 'top-full left-1/2 mt-1.5 -translate-x-1/2',
  left: 'right-full top-1/2 mr-1.5 -translate-y-1/2',
  right: 'left-full top-1/2 ml-1.5 -translate-y-1/2',
}

export interface TooltipProps {
  label: ReactNode
  side?: TooltipSide
  children: ReactNode
  className?: string
  /** show without delay */
  immediate?: boolean
}

/** CSS tooltip: appears on hover and keyboard focus, no library, no positioning engine. */
export function Tooltip({ label, side = 'top', children, className, immediate }: TooltipProps) {
  const id = useId()
  return (
    <span className={cx('group/tip relative inline-flex', className)} aria-describedby={id}>
      {children}
      <span
        role="tooltip"
        id={id}
        className={cx(
          'material-panel pointer-events-none absolute z-50 whitespace-nowrap rounded-sm px-2 py-1 text-caption text-primary opacity-0 transition-opacity duration-150',
          !immediate && 'delay-300',
          'group-hover/tip:opacity-100 group-focus-within/tip:opacity-100',
          SIDE[side],
        )}
      >
        {label}
      </span>
    </span>
  )
}
