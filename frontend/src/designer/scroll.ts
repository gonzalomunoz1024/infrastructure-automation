/**
 * Scrolls `el` into view inside `container` only. `Element.scrollIntoView` also scrolls every
 * scrollable ancestor, overflow-hidden ones included, which shifts the full-bleed designer under
 * the shell's header; this never touches anything but the container.
 */
export function revealIn(container: HTMLElement, el: HTMLElement, { align = 'nearest', offset = 0, smooth = false }: { align?: 'nearest' | 'start'; offset?: number; smooth?: boolean } = {}): void {
  const box = container.getBoundingClientRect()
  const r = el.getBoundingClientRect()
  let top: number | null = null
  if (align === 'start' || r.top < box.top + offset) top = container.scrollTop + r.top - box.top - offset
  else if (r.bottom > box.bottom) top = container.scrollTop + r.bottom - box.bottom
  if (top !== null) container.scrollTo({ top: Math.max(0, top), behavior: smooth ? 'smooth' : 'auto' })
}
