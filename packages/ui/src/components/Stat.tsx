import type { ReactNode } from 'react'
import { cx } from '../lib/cx'
import { formatNumber } from '../lib/format'
import { Icon, type IconName } from './Icon'
import { Sparkline } from './Charts'

export interface StatDelta {
  /** change versus the previous period, in the value's unit (or %) */
  value: number
  suffix?: string
  /** which direction is good; drives the colour (default 'up') */
  betterWhen?: 'up' | 'down'
  /** "vs last week" */
  label?: string
}

export interface StatProps {
  label: string
  value: ReactNode
  delta?: StatDelta
  /** quiet line under the value */
  hint?: ReactNode
  icon?: IconName
  /** recent values, drawn as a sparkline at the right */
  sparkline?: number[]
  className?: string
}

/** A number with its label and change: the admin's headline tile. */
export function Stat({ label, value, delta, hint, icon, sparkline, className }: StatProps) {
  let deltaNode: ReactNode = null
  if (delta) {
    const dir = delta.value > 0 ? 'up' : delta.value < 0 ? 'down' : 'flat'
    const good = dir === 'flat' ? null : (delta.betterWhen ?? 'up') === dir
    const tone = dir === 'flat' ? 'text-tertiary' : good ? 'text-healthy' : 'text-critical'
    const sign = delta.value > 0 ? '+' : ''
    deltaNode = (
      <span className={cx('inline-flex items-center gap-0.5 text-caption font-medium tabular-nums', tone)}>
        {dir !== 'flat' && <Icon name={dir === 'up' ? 'chevron-up' : 'chevron-down'} size={11} strokeWidth={2.2} />}
        {sign}
        {formatNumber(delta.value)}
        {delta.suffix ?? ''}
        {delta.label && <span className="ml-1 font-normal text-tertiary">{delta.label}</span>}
      </span>
    )
  }
  return (
    <div className={cx('flex items-start justify-between gap-3 rounded-md bg-surface-secondary p-3', className)}>
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 text-caption text-tertiary">
          {icon && <Icon name={icon} size={13} />}
          {label}
        </div>
        <div className="mt-0.5 truncate text-title3 font-semibold tabular-nums text-primary">{value}</div>
        {(deltaNode || hint) && <div className="mt-0.5 flex items-center gap-2 text-caption text-tertiary">{deltaNode ?? hint}</div>}
      </div>
      {sparkline && sparkline.length > 1 && <Sparkline values={sparkline} width={88} height={30} className="mt-1 shrink-0" />}
    </div>
  )
}
