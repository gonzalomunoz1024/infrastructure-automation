import { useEffect, type RefObject } from 'react'

/**
 * The one dismiss behavior for popovers: outside-click + Escape.
 *
 * Outside-click listens in the CAPTURE phase — a canvas that calls
 * stopPropagation() on mousedown would otherwise swallow the click, and a
 * bubble-phase document listener would never fire.
 */
export function useDismiss(
  ref: RefObject<HTMLElement | null>,
  active: boolean,
  onDismiss: () => void,
) {
  useEffect(() => {
    if (!active) return
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onDismiss()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onDismiss()
    }
    document.addEventListener('mousedown', onDoc, true)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc, true)
      window.removeEventListener('keydown', onKey)
    }
  }, [ref, active, onDismiss])
}
