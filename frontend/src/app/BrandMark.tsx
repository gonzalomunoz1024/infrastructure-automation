import { cx } from '@iaf/ui'

/** The platform mark: one node fanning out to two — a workflow, in the accent. */
export function BrandMark({ size = 22, className }: { size?: number; className?: string }) {
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} className={cx('shrink-0 text-accent', className)} aria-hidden="true">
      <circle cx="22" cy="50" r="12" fill="currentColor" />
      <circle cx="78" cy="26" r="12" fill="currentColor" />
      <circle cx="78" cy="74" r="12" fill="currentColor" />
      <path d="M34 50h16M50 50l17-17M50 50l17 17" fill="none" stroke="currentColor" strokeWidth="7" strokeLinecap="round" strokeLinejoin="round" opacity="0.55" />
    </svg>
  )
}

/** Mark + name, for the sidebar top. */
export function BrandLockup({ collapsed }: { collapsed: boolean }) {
  return (
    <a href="#/workflows" className="flex items-center gap-2.5 text-primary transition-opacity hover:opacity-70" aria-label="Infrastructure Automation">
      <BrandMark />
      {!collapsed && <span className="text-footnote font-semibold leading-tight tracking-tight">Infrastructure Automation</span>}
    </a>
  )
}
