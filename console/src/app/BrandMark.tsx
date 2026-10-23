import { cx } from '@iaf/ui'
import { Link } from './router'

/** The console mark: the platform's three nodes on a tile, in the accent. */
export function BrandMark({ size = 22, className }: { size?: number; className?: string }) {
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} className={cx('shrink-0 text-accent', className)} aria-hidden="true">
      <rect x="6" y="6" width="88" height="88" rx="22" fill="currentColor" />
      <circle cx="34" cy="50" r="10" fill="var(--color-surface)" />
      <circle cx="66" cy="32" r="10" fill="var(--color-surface)" />
      <circle cx="66" cy="68" r="10" fill="var(--color-surface)" />
      <path d="M44 50h10M54 50l6-14M54 50l6 14" fill="none" stroke="var(--color-surface)" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" opacity="0.7" />
    </svg>
  )
}

/** Mark + "Console", for the header's left. */
export function BrandLockup({ className }: { className?: string }) {
  return (
    <Link to="/" className={cx('flex shrink-0 items-center gap-2 rounded-md text-primary transition-opacity hover:opacity-70', className)} aria-label="Console home">
      <BrandMark />
      {/* a phone shows the mark alone, which leaves the header room for every control */}
      <span className="hidden text-subheadline font-semibold tracking-tight sm:inline">Console</span>
    </Link>
  )
}
