import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type ReactNode } from 'react'
import { Icon, PopoverPanel, cx, useDismiss, type IconName } from '@iaf/ui'

/*
 * A button that opens a menu or a small panel, keyboard first (the WAI-ARIA menu button pattern).
 * Enter, Space or Down opens a menu on its first item and Up on its last; the arrows, Home and End
 * move between items; Escape closes it and gives the focus back to the button; Tab closes it and
 * moves on. A panel (role dialog) keeps the browser's own Tab order inside it, takes the focus on
 * its first control, and closes on Escape, on a click outside, or when the focus leaves it. The
 * panel opens under the button, aligned to its left or right edge, and slides sideways as far as
 * it takes to stay on screen (a right-aligned panel under a button that wrapped to the left edge of
 * a phone would otherwise hang off it).
 */

export interface MenuButtonProps {
  /** the accessible name of the button and of what it opens */
  label: string
  /** what the button shows; the label when left out */
  children?: ReactNode
  icon?: IconName
  role?: 'menu' | 'dialog'
  align?: 'left' | 'right'
  /** the panel's width class */
  width?: string
  className?: string
  /** the items (MenuItem closes the menu before it runs), or the panel's content */
  content: ReactNode
}

/** Closes the open menu and gives the focus back to its button; MenuItem calls it before it runs. */
const CloseMenu = createContext<() => void>(() => undefined)

export function useCloseMenu(): () => void {
  return useContext(CloseMenu)
}

const ITEMS = '[role="menuitem"]:not([disabled]),[role="menuitemradio"]:not([disabled]),[role="menuitemcheckbox"]:not([disabled])'
const FOCUSABLE = 'input:not([disabled]),button:not([disabled]),select:not([disabled]),textarea:not([disabled]),a[href],[tabindex]:not([tabindex="-1"])'
/** the closest a panel comes to the viewport's edges */
const MARGIN = 8

export function MenuButton({ label, children, icon, role = 'menu', align = 'left', width = 'w-64', className, content }: MenuButtonProps) {
  const id = useId()
  const [open, setOpen] = useState<false | 'first' | 'last'>(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const close = useCallback(() => setOpen(false), [])
  const closeAndFocus = useCallback(() => {
    setOpen(false)
    button.current?.focus()
  }, [])
  useDismiss(root, !!open, close)

  // Escape after the focus was lost (a control in the panel went disabled under it) still gives it back to the button
  useEffect(() => {
    if (!open) return
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape' && (document.activeElement === document.body || document.activeElement === null)) closeAndFocus()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, closeAndFocus])

  // before paint: aligned as asked, then slid back inside the viewport
  useLayoutEffect(() => {
    const p = panel.current
    const r = root.current
    if (!open || !p || !r) return
    const place = () => {
      const box = r.getBoundingClientRect()
      const width = p.offsetWidth
      const wanted = align === 'right' ? box.width - width : 0
      const left = Math.max(MARGIN - box.left, Math.min(wanted, window.innerWidth - MARGIN - width - box.left))
      p.style.left = `${left}px`
      p.style.right = 'auto'
    }
    place()
    window.addEventListener('resize', place)
    return () => window.removeEventListener('resize', place)
  }, [open, align])

  // on opening, the focus moves in: a menu's first (or last) item, a panel's first control
  useEffect(() => {
    if (!open || !panel.current) return
    const targets = panel.current.querySelectorAll<HTMLElement>(role === 'menu' ? ITEMS : FOCUSABLE)
    const target = open === 'last' ? targets[targets.length - 1] : targets[0]
    target?.focus()
  }, [open, role])

  const onButtonKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'ArrowDown' || (role === 'menu' && e.key === 'ArrowUp')) {
      e.preventDefault()
      setOpen(e.key === 'ArrowUp' ? 'last' : 'first')
    }
  }

  const onPanelKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') {
      // handled here, so the window's Escape (useDismiss) does not also fire
      e.preventDefault()
      e.stopPropagation()
      closeAndFocus()
      return
    }
    if (role !== 'menu' || !panel.current) return
    if (e.key === 'Tab') {
      close()
      return
    }
    const items = Array.from(panel.current.querySelectorAll<HTMLElement>(ITEMS))
    if (items.length === 0) return
    const at = items.indexOf(document.activeElement as HTMLElement)
    const go = (i: number) => {
      e.preventDefault()
      items[(i + items.length) % items.length].focus()
    }
    if (e.key === 'ArrowDown') go(at + 1)
    else if (e.key === 'ArrowUp') go(at < 0 ? items.length - 1 : at - 1)
    else if (e.key === 'Home') go(0)
    else if (e.key === 'End') go(items.length - 1)
  }

  // the focus moved somewhere else (Tab out of a panel): close; a click on empty space moves it nowhere
  const onBlur = (e: FocusEvent<HTMLDivElement>) => {
    if (open && e.relatedTarget && root.current && !root.current.contains(e.relatedTarget as Node)) close()
  }

  return (
    <div ref={root} className={cx('relative', className)} onBlur={onBlur}>
      <button
        ref={button}
        type="button"
        aria-haspopup={role}
        aria-expanded={!!open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((o) => (o ? false : 'first'))}
        onKeyDown={onButtonKeyDown}
        className={cx(
          'inline-flex h-10 max-w-full select-none items-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium text-primary transition-colors',
          open ? 'bg-stroke-light' : 'bg-surface-secondary hover:bg-stroke-light',
        )}
      >
        {icon && <Icon name={icon} size={15} className="shrink-0 text-secondary" />}
        <span className="min-w-0 truncate">{children ?? label}</span>
        <Icon name="chevron-down" size={13} className="shrink-0 text-secondary" />
      </button>
      {open && (
        <div ref={panel} id={id} onKeyDown={onPanelKeyDown} className={cx('absolute top-full z-50 mt-1', align === 'right' ? 'right-0' : 'left-0', width, 'max-w-[calc(100vw-2rem)]')}>
          <PopoverPanel role={role} ariaLabel={label} className="max-h-[min(28rem,70dvh)] overflow-y-auto py-1">
            <CloseMenu.Provider value={closeAndFocus}>{content}</CloseMenu.Provider>
          </PopoverPanel>
        </div>
      )}
    </div>
  )
}

export interface MenuItemProps {
  children: ReactNode
  onSelect: () => void
  /** a choice among several (menuitemradio): whether it is the one in force */
  checked?: boolean
  disabled?: boolean
  icon?: IconName
  /** quiet text at the right */
  hint?: ReactNode
  danger?: boolean
}

/** One item of a MenuButton's menu: closes the menu, then runs. The menu moves the focus between items, so none is in the Tab order. */
export function MenuItem({ children, onSelect, checked, disabled, icon, hint, danger }: MenuItemProps) {
  const close = useCloseMenu()
  return (
    <button
      type="button"
      role={checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={checked}
      tabIndex={-1}
      disabled={disabled}
      onClick={() => {
        close()
        onSelect()
      }}
      className={cx(
        'flex w-full items-center gap-2 px-3 py-2 text-left text-sm outline-none transition-colors',
        disabled ? 'cursor-not-allowed text-tertiary' : cx('hover:bg-surface-secondary focus:bg-surface-secondary focus:ring-2 focus:ring-inset focus:ring-accent/60', danger ? 'text-critical' : 'text-primary'),
        checked && 'bg-accent-tint',
      )}
    >
      {icon && <Icon name={icon} size={15} className={cx('shrink-0', danger ? 'text-critical' : 'text-tertiary')} />}
      <span className="min-w-0 flex-1 truncate">{children}</span>
      {hint && <span className="shrink-0 text-caption text-tertiary">{hint}</span>}
      {checked && <Icon name="check" size={14} className="shrink-0 text-accent" />}
    </button>
  )
}

export function MenuSeparator() {
  return <div role="separator" className="my-1 border-t border-stroke-light" />
}

export function MenuHeading({ children }: { children: ReactNode }) {
  return (
    <div role="presentation" className="px-3 pb-1 pt-2 text-caption2 font-semibold uppercase tracking-wide text-tertiary">
      {children}
    </div>
  )
}
