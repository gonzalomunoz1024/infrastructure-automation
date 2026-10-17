/**
 * n8n's right-click menu: a material panel at the pointer, its actions with their shortcuts,
 * arrow keys to move, Enter to run, Escape or a click outside to close.
 */
import { useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { cx, Icon, Kbd, useDismiss, type IconName } from '@iaf/ui'
import { UiGlyph, type UiGlyphName } from './glyphs'

export interface MenuItem {
  id: string
  label: string
  icon?: IconName
  glyph?: UiGlyphName
  shortcut?: string
  disabled?: boolean
  danger?: boolean
  run: () => void
}

export type MenuEntry = MenuItem | 'separator'

export function ContextMenu({ x, y, entries, onClose, label = 'Canvas menu' }: { x: number; y: number; entries: MenuEntry[]; onClose: () => void; label?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState({ left: x, top: y })
  useDismiss(ref, true, onClose)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const r = el.getBoundingClientRect()
    const left = Math.max(8, Math.min(x, window.innerWidth - r.width - 8))
    const top = Math.max(8, Math.min(y, window.innerHeight - r.height - 8))
    setPos({ left, top })
    el.querySelector<HTMLButtonElement>('button:not([disabled])')?.focus({ preventScroll: true })
  }, [x, y])

  const move = (e: KeyboardEvent<HTMLDivElement>) => {
    const items = [...(ref.current?.querySelectorAll<HTMLButtonElement>('button:not([disabled])') ?? [])]
    if (items.length === 0) return
    const i = items.indexOf(document.activeElement as HTMLButtonElement)
    let next = -1
    if (e.key === 'ArrowDown') next = (i + 1) % items.length
    else if (e.key === 'ArrowUp') next = (i - 1 + items.length) % items.length
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = items.length - 1
    else if (e.key === 'Tab') {
      e.preventDefault()
      onClose()
      return
    }
    if (next >= 0) {
      e.preventDefault()
      items[next].focus()
    }
    e.stopPropagation()
  }

  return createPortal(
    <div
      ref={ref}
      role="menu"
      aria-label={label}
      onKeyDown={move}
      onContextMenu={(e) => e.preventDefault()}
      className="material-panel fixed z-50 min-w-[220px] animate-scale-in overflow-hidden rounded-md py-1"
      style={{ left: pos.left, top: pos.top }}
    >
      {entries.map((entry, i) =>
        entry === 'separator' ? (
          <div key={`sep-${i}`} role="separator" className="my-1 h-px bg-stroke-light" />
        ) : (
          <button
            key={entry.id}
            type="button"
            role="menuitem"
            disabled={entry.disabled}
            onClick={() => {
              onClose()
              entry.run()
            }}
            className={cx(
              'flex w-full items-center gap-2.5 px-3 py-1.5 text-left text-footnote outline-none transition-colors',
              'hover:bg-surface-secondary focus-visible:bg-surface-secondary',
              entry.danger ? 'text-critical' : 'text-primary',
            )}
          >
            <span className={cx('flex w-4 shrink-0 justify-center', entry.danger ? 'text-critical' : 'text-tertiary')}>
              {entry.glyph ? <UiGlyph name={entry.glyph} size={14} /> : entry.icon ? <Icon name={entry.icon} size={14} /> : null}
            </span>
            <span className="flex-1 truncate">{entry.label}</span>
            {entry.shortcut && <Kbd>{entry.shortcut}</Kbd>}
          </button>
        ),
      )}
    </div>,
    document.body,
  )
}
