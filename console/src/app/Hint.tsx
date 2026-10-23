import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Portal, cx } from '@iaf/ui'

/*
 * The note under an action button, kept on screen. Atlas's Tooltip is CSS only: centred under its
 * trigger, on one line, and always in the layout (transparent until hovered), so under a button at
 * the right edge of a phone it widens the page and the page scrolls sideways. This one is in the
 * page only while shown, under <body> (Atlas's Portal, so an animated ancestor cannot offset it),
 * below its trigger (above when there is no room below) and slid sideways to stay inside the
 * viewport, wrapping on a narrow screen. It meets WCAG 2.2's content on hover or focus: Escape
 * hides it, the pointer can move onto it, and it stays until the pointer and focus have left. The
 * buttons carry the note in their names too, so screen readers hear it there and the panel itself
 * is hidden from them.
 */

/** the closest the panel comes to the viewport's edges, and its distance from the trigger */
const MARGIN = 8
const GAP = 6
/** how long the panel waits, once the pointer leaves, for it to arrive on the panel */
const LINGER_MS = 120

export interface HintProps {
  label: string
  children: ReactNode
  className?: string
}

export function Hint({ label, children, className }: HintProps) {
  const anchor = useRef<HTMLSpanElement>(null)
  const panel = useRef<HTMLSpanElement>(null)
  const timer = useRef<number | undefined>(undefined)
  const [open, setOpen] = useState(false)

  const show = () => {
    window.clearTimeout(timer.current)
    setOpen(true)
  }
  const hide = () => {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setOpen(false), LINGER_MS)
  }

  // placed before paint, from the trigger's box and the panel's own size, and again on scroll and resize
  useLayoutEffect(() => {
    const a = anchor.current
    const p = panel.current
    if (!open || !a || !p) return
    const place = () => {
      const r = a.getBoundingClientRect()
      const width = p.offsetWidth
      const height = p.offsetHeight
      const left = Math.max(MARGIN, Math.min(r.left + r.width / 2 - width / 2, window.innerWidth - MARGIN - width))
      const top = r.bottom + GAP + height <= window.innerHeight - MARGIN ? r.bottom + GAP : Math.max(MARGIN, r.top - GAP - height)
      p.style.left = `${left}px`
      p.style.top = `${top}px`
      p.style.visibility = 'visible'
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  useEffect(() => () => window.clearTimeout(timer.current), [])

  return (
    <span ref={anchor} className={cx('inline-flex', className)} onPointerEnter={show} onPointerLeave={hide} onFocus={show} onBlur={hide}>
      {children}
      {open && (
        <Portal>
          <span
            ref={panel}
            aria-hidden="true"
            data-hint=""
            onPointerEnter={show}
            onPointerLeave={hide}
            style={{ visibility: 'hidden', top: 0, left: 0 }}
            className="material-panel fixed z-50 w-max max-w-[min(20rem,calc(100vw-1rem))] rounded-sm px-2 py-1 text-caption text-primary"
          >
            {label}
          </span>
        </Portal>
      )}
    </span>
  )
}
