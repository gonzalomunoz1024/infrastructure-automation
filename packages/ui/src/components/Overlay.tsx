import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react'
import { createPortal } from 'react-dom'
import { cx } from '../lib/cx'
import { Icon, type IconName } from './Icon'
import { IconButton } from './Button'

/**
 * The one overlay system: Modal, Drawer, Popover.
 * - One scrim recipe (.material-scrim), one z-scale: base overlays z-50,
 *   overlays stacked above a drawer z-[60] via `raised`.
 * - Spring scale-in for modals, slide for drawers; exit animations play
 *   before unmount (the `closing` state below), no motion library needed.
 * - Escape + scrim click close (only the topmost overlay reacts to Escape and
 *   Tab, and Escape first closes a list open inside it); Tab stays inside the
 *   panel; focus returns to the opener.
 */

const OverlayCtx = createContext<{ close: () => void } | null>(null)

/** Children of Modal/Drawer can call this to close with the exit animation. */
export function useOverlayClose(): () => void {
  const ctx = useContext(OverlayCtx)
  return ctx?.close ?? (() => {})
}

/** open overlays, innermost last — Escape only closes the top one */
const stack: symbol[] = []

export interface OverlayLifecycle {
  /** the exit animation is playing */
  closing: boolean
  /** start the exit animation; `onClose` fires when it ends */
  close: () => void
  /** attach to the animated panel's onAnimationEnd */
  onPanelAnimationEnd: () => void
  /** attach to the panel so Tab cycles inside it and focus lands in it */
  panelRef: RefObject<HTMLElement | null>
}

/**
 * Exit-before-unmount, focus management and keyboard handling for any custom
 * overlay built outside Modal/Drawer.
 */
export function useOverlayLifecycle(onClose: () => void): OverlayLifecycle {
  const [closing, setClosing] = useState(false)
  const openerRef = useRef<HTMLElement | null>(null)
  const panelRef = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null
    const panel = panelRef.current
    if (panel && !panel.contains(document.activeElement)) {
      const target = panel.querySelector<HTMLElement>('[data-autofocus]') ?? panel
      target.focus({ preventScroll: true })
    }
    return () => openerRef.current?.focus?.({ preventScroll: true })
  }, [])

  const close = useCallback(() => setClosing(true), [])

  useEffect(() => {
    const id = Symbol('overlay')
    stack.push(id)
    const onKey = (e: KeyboardEvent) => {
      // only the topmost overlay handles the keys: an overlay stacked above (a dialog over a drawer) keeps Escape and the focus
      if (stack[stack.length - 1] !== id) return
      if (e.key === 'Escape') {
        // a list open inside the panel (a Select's, a menu's) closes on its own Escape; the overlay stays
        if (panelRef.current?.querySelector('[aria-haspopup]:not([aria-haspopup="false"])[aria-expanded="true"]')) return
        e.stopPropagation()
        close()
      } else if (e.key === 'Tab' && panelRef.current) {
        // minimal focus trap: keep Tab cycling inside the panel
        const focusables = panelRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        )
        if (focusables.length === 0) {
          e.preventDefault()
          return
        }
        const first = focusables[0]
        const last = focusables[focusables.length - 1]
        const active = document.activeElement
        if (e.shiftKey && (active === first || !panelRef.current.contains(active))) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && (active === last || !panelRef.current.contains(active))) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      const i = stack.indexOf(id)
      if (i >= 0) stack.splice(i, 1)
    }
  }, [close])

  // when the exit animation on the panel finishes, actually unmount
  const onPanelAnimationEnd = useCallback(() => {
    if (closing) onCloseRef.current()
  }, [closing])

  // safety net: unmount even if no exit animation runs on the panel
  useEffect(() => {
    if (!closing) return
    const t = setTimeout(() => onCloseRef.current(), 260)
    return () => clearTimeout(t)
  }, [closing])

  return { closing, close, onPanelAnimationEnd, panelRef }
}

/**
 * Fixed-position overlays render under <body> so an animated ancestor
 * (`.animate-fade-in` keeps a transform, which turns it into the containing
 * block for `position: fixed`) cannot offset them. Context still flows through
 * the portal. The server renderer has no portals, so there it renders inline.
 */
export function Portal({ children }: { children: ReactNode }) {
  if (typeof document === 'undefined') return <>{children}</>
  return createPortal(children, document.body)
}

export interface ModalProps {
  onClose: () => void
  children: ReactNode
  /** panel width class, e.g. 'max-w-2xl' */
  width?: string
  /** stack above an open drawer */
  raised?: boolean
  /** vertical placement: center (default) or top (command palette) */
  align?: 'center' | 'top'
  panelClassName?: string
  /** accessible name of the dialog */
  label?: string
}

export function Modal({
  onClose,
  children,
  width = 'max-w-2xl',
  raised,
  align = 'center',
  panelClassName,
  label,
}: ModalProps) {
  const { closing, close, onPanelAnimationEnd, panelRef } = useOverlayLifecycle(onClose)
  return (
    <OverlayCtx.Provider value={{ close }}>
      <Portal>
      <div
        className={cx(
          'fixed inset-0 flex justify-center p-4',
          align === 'center' ? 'items-center' : 'items-start pt-[14vh]',
          raised ? 'z-[60]' : 'z-50',
        )}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        onClick={close}
      >
        <div className={cx('material-scrim absolute inset-0', closing ? 'animate-fade-out' : 'animate-fade-in-slow')} />
        <div
          ref={panelRef as RefObject<HTMLDivElement | null>}
          tabIndex={-1}
          className={cx(
            'relative flex max-h-[85vh] w-full flex-col overflow-hidden rounded-xl border border-stroke-light bg-surface shadow-lg outline-none',
            width,
            closing ? 'animate-scale-out' : 'animate-scale-in',
            panelClassName,
          )}
          onClick={(e) => e.stopPropagation()}
          onAnimationEnd={onPanelAnimationEnd}
        >
          {children}
        </div>
      </div>
      </Portal>
    </OverlayCtx.Provider>
  )
}

export interface DrawerProps {
  onClose: () => void
  children: ReactNode
  /** panel width class, e.g. 'max-w-xl' */
  width?: string
  raised?: boolean
  label?: string
}

/** Right-side panel that slides in; same lifecycle as Modal. */
export function Drawer({ onClose, children, width = 'max-w-xl', raised, label }: DrawerProps) {
  const { closing, close, onPanelAnimationEnd, panelRef } = useOverlayLifecycle(onClose)
  return (
    <OverlayCtx.Provider value={{ close }}>
      <Portal>
      <div
        className={cx('fixed inset-0 flex justify-end', raised ? 'z-[60]' : 'z-50')}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        onClick={close}
      >
        <div className={cx('material-scrim absolute inset-0', closing ? 'animate-fade-out' : 'animate-fade-in-slow')} />
        <aside
          ref={panelRef as RefObject<HTMLElement | null>}
          tabIndex={-1}
          className={cx(
            'relative flex h-full w-full flex-col border-l border-stroke-light bg-surface shadow-lg outline-none',
            width,
            closing ? 'animate-slide-out-right' : 'animate-slide-in-right',
          )}
          onClick={(e) => e.stopPropagation()}
          onAnimationEnd={onPanelAnimationEnd}
        >
          {children}
        </aside>
      </div>
      </Portal>
    </OverlayCtx.Provider>
  )
}

/**
 * Anchored popover panel — parent supplies position classes (e.g. 'absolute
 * bottom-full left-0 mb-2'). Dismissal (outside-click + Escape) belongs to the
 * parent via useDismiss, since the trigger stays interactive.
 */
export function PopoverPanel({
  children,
  className,
  role,
  ariaLabel,
}: {
  children: ReactNode
  className?: string
  role?: string
  ariaLabel?: string
}) {
  return (
    <div
      role={role}
      aria-label={ariaLabel}
      className={cx('material-panel z-50 overflow-hidden rounded-lg', 'animate-scale-in', className)}
    >
      {children}
    </div>
  )
}

export interface OverlayHeaderProps {
  title: ReactNode
  subtitle?: ReactNode
  /** tinted glyph tile at the left */
  icon?: IconName
  /** controls between the title and the close button */
  actions?: ReactNode
  children?: ReactNode
  className?: string
  closeLabel?: string
}

/** The standard Modal/Drawer header: title, optional subtitle, close button that animates out. */
export function OverlayHeader({ title, subtitle, icon, actions, children, className, closeLabel = 'Close' }: OverlayHeaderProps) {
  const close = useOverlayClose()
  return (
    <div className={cx('flex items-start justify-between gap-4 border-b border-stroke-light p-5', className)}>
      <div className="flex min-w-0 items-center gap-3">
        {icon && (
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
            <Icon name={icon} size={19} />
          </span>
        )}
        <div className="min-w-0">
          <h2 className="truncate text-title3 font-semibold text-primary">{title}</h2>
          {subtitle && <p className="mt-0.5 text-sm text-secondary">{subtitle}</p>}
          {children}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {actions}
        <IconButton label={closeLabel} onClick={close}>
          <Icon name="close" size={18} />
        </IconButton>
      </div>
    </div>
  )
}

/** The scrolling middle of a Modal/Drawer. */
export function OverlayBody({ children, className, padded = true }: { children: ReactNode; className?: string; padded?: boolean }) {
  return <div className={cx('min-h-0 flex-1 overflow-y-auto', padded && 'p-5', className)}>{children}</div>
}

/** Right-aligned action row at the bottom of a Modal/Drawer. */
export function OverlayFooter({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cx('flex items-center justify-end gap-2 border-t border-stroke-light px-5 py-3', className)}>
      {children}
    </div>
  )
}
