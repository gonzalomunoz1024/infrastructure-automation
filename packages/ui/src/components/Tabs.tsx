import type { KeyboardEvent, ReactNode } from 'react'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'
import { Badge } from './Badge'

export interface TabItem<V extends string> {
  id: V
  label: ReactNode
  icon?: IconName
  count?: number
  disabled?: boolean
}

export interface TabsProps<V extends string> {
  tabs: TabItem<V>[]
  value: V
  onChange: (id: V) => void
  ariaLabel: string
  className?: string
}

/** Soft tabs: the Atlas rounded-sm pill set used in modals and drawers. */
export function Tabs<V extends string>({ tabs, value, onChange, ariaLabel, className }: TabsProps<V>) {
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    e.preventDefault()
    const enabled = tabs.filter((t) => !t.disabled)
    if (enabled.length === 0) return
    const i = Math.max(0, enabled.findIndex((t) => t.id === value))
    const next = enabled[(i + (e.key === 'ArrowRight' ? 1 : -1) + enabled.length) % enabled.length]
    onChange(next.id)
    ;(e.currentTarget as HTMLElement).querySelector<HTMLElement>(`[data-tab="${next.id}"]`)?.focus()
  }
  return (
    <div role="tablist" aria-label={ariaLabel} onKeyDown={onKeyDown} className={cx('flex flex-wrap gap-1', className)}>
      {tabs.map((t) => {
        const selected = t.id === value
        return (
          <button
            key={t.id}
            type="button"
            role="tab"
            data-tab={t.id}
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            disabled={t.disabled}
            onClick={() => onChange(t.id)}
            className={cx(
              'flex items-center gap-1.5 rounded-sm px-3 py-1.5 text-sm font-medium transition-colors',
              selected ? 'bg-surface-secondary text-primary' : 'text-secondary hover:text-primary',
            )}
          >
            {t.icon && <Icon name={t.icon} size={14} className={selected ? 'text-accent' : 'text-tertiary'} />}
            {t.label}
            {t.count != null && <Badge count={t.count} tone={selected ? 'accent' : 'neutral'} />}
          </button>
        )
      })}
    </div>
  )
}
