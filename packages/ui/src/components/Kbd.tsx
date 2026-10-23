import type { ReactNode } from 'react'
import { cx } from '../lib/cx'

/** true on macOS and iOS, where the command key is ⌘ */
export function isApplePlatform(): boolean {
  if (typeof navigator === 'undefined') return false
  const p = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform ?? navigator.platform ?? ''
  return /mac|iphone|ipad|ipod/i.test(p)
}

/** "⌘" on Apple platforms, "Ctrl" elsewhere. */
export function modKeyLabel(): string {
  return isApplePlatform() ? '⌘' : 'Ctrl'
}

/** Keycap: hints in headers, palettes and tooltips. */
export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <kbd
      className={cx(
        'inline-flex items-center rounded-[5px] border border-stroke-light px-1.5 py-0.5 font-mono text-[10px] leading-none text-tertiary',
        className,
      )}
    >
      {children}
    </kbd>
  )
}
