import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { cx } from '../lib/cx'
import { useDismiss } from '../hooks/useDismiss'
import { Icon } from './Icon'
import { PopoverPanel } from './Overlay'

export interface SelectOption {
  value: string
  label: string
  /** secondary text at the right of the option */
  hint?: string
  disabled?: boolean
}

export interface SelectProps {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  ariaLabel: string
  className?: string
  /** monospace the trigger/options (identifiers, paths) */
  mono?: boolean
  placeholder?: string
  disabled?: boolean
  size?: 'sm' | 'md'
}

/** Styled select: capsule trigger + material listbox with keyboard navigation. */
export function Select({ value, onChange, options, ariaLabel, className, mono, placeholder, disabled, size = 'md' }: SelectProps) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const ref = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)

  const close = useCallback(() => setOpen(false), [])
  useDismiss(ref, open, close)

  // the list opens on the current value
  const openList = () => {
    const idx = options.findIndex((o) => o.value === value)
    setActive(idx >= 0 ? idx : 0)
    setOpen(true)
  }

  useEffect(() => {
    listRef.current
      ?.querySelector<HTMLElement>(`[data-idx="${active}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [active])

  const current = options.find((o) => o.value === value)

  const pick = (o: SelectOption | undefined) => {
    if (!o || o.disabled) return
    onChange(o.value)
    close()
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (disabled) return
    if (!open && (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown')) {
      e.preventDefault()
      openList()
      return
    }
    if (!open) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((a) => Math.min(a + 1, options.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((a) => Math.max(a - 1, 0))
    } else if (e.key === 'Home') {
      e.preventDefault()
      setActive(0)
    } else if (e.key === 'End') {
      e.preventDefault()
      setActive(options.length - 1)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      pick(options[active])
    } else if (e.key === 'Tab') {
      close()
    }
  }

  return (
    <div ref={ref} className={cx('relative', className)} onKeyDown={onKeyDown}>
      <button
        type="button"
        onClick={() => (open ? close() : openList())}
        aria-label={ariaLabel}
        aria-expanded={open}
        aria-haspopup="listbox"
        disabled={disabled}
        className={cx(
          'flex w-full items-center justify-between gap-2 rounded-md border border-stroke-light bg-surface-secondary text-left',
          size === 'sm' ? 'py-1.5 pl-2.5 pr-2' : 'py-2 pl-3 pr-2.5',
          mono ? 'font-mono text-caption' : size === 'sm' ? 'text-caption' : 'text-sm',
          'text-primary transition-colors hover:border-stroke focus-visible:border-accent',
        )}
      >
        <span className={cx('truncate', !current && 'text-tertiary')}>{current?.label ?? placeholder ?? value}</span>
        <Icon
          name="chevron-down"
          size={13}
          className={cx('shrink-0 text-tertiary transition-transform duration-150', open && 'rotate-180')}
        />
      </button>

      {open && (
        <PopoverPanel className="absolute left-0 right-0 top-full mt-1.5 origin-top">
          <div ref={listRef} role="listbox" aria-label={ariaLabel} className="max-h-64 overflow-y-auto py-1">
            {options.length === 0 && <p className="px-3 py-2 text-caption text-tertiary">No options</p>}
            {options.map((o, i) => (
              <button
                key={o.value}
                type="button"
                data-idx={i}
                role="option"
                aria-selected={o.value === value}
                aria-disabled={o.disabled || undefined}
                onClick={() => pick(o)}
                onMouseEnter={() => setActive(i)}
                className={cx(
                  'flex w-full items-center gap-2 px-3 py-1.5 text-left',
                  mono ? 'font-mono text-caption' : 'text-sm',
                  i === active ? 'bg-surface-secondary text-primary' : 'text-secondary',
                  o.disabled && 'opacity-50',
                )}
              >
                <span className="w-3.5 shrink-0">
                  {o.value === value && <Icon name="check" size={13} className="text-accent" />}
                </span>
                <span className="min-w-0 flex-1 truncate">{o.label}</span>
                {o.hint && <span className="shrink-0 text-caption text-tertiary">{o.hint}</span>}
              </button>
            ))}
          </div>
        </PopoverPanel>
      )}
    </div>
  )
}
