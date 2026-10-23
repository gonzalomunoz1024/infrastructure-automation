import type { MouseEvent, ReactNode } from 'react'
import { cx } from '../lib/cx'
import { useMediaQuery } from '../hooks/useMediaQuery'
import { Icon, type IconName } from './Icon'
import { Badge } from './Badge'

export interface SidebarItem {
  id: string
  label: string
  icon: IconName
  href: string
  badge?: number
  disabled?: boolean
}

export interface SidebarGroup {
  id?: string
  label?: string
  items: SidebarItem[]
}

export interface SidebarProps {
  /** top-left lockup */
  brand?: ReactNode
  groups: SidebarGroup[]
  /** id of the active item (gets aria-current) */
  activeId?: string | null
  /** force the icon-only rail; by default it follows the viewport (< 1024px) */
  collapsed?: boolean
  /** bottom slot: the user menu, the theme */
  footer?: ReactNode
  /** intercept navigation (e.g. hash routing); the href still works for middle-click */
  onNavigate?: (item: SidebarItem, e: MouseEvent<HTMLAnchorElement>) => void
  /** expanded width in px */
  width?: number
  className?: string
  ariaLabel?: string
}

/** The left navigation: 232px with grouped items, an icon rail under 1024px. */
export function Sidebar({ brand, groups, activeId, collapsed: forced, footer, onNavigate, width = 232, className, ariaLabel = 'Primary' }: SidebarProps) {
  const narrow = useMediaQuery('(max-width: 1023px)')
  const collapsed = forced ?? narrow

  return (
    <aside
      data-collapsed={collapsed || undefined}
      style={{ width: collapsed ? 64 : width }}
      className={cx(
        'sticky top-0 flex h-screen shrink-0 flex-col border-r border-stroke-light bg-surface-secondary transition-[width] duration-200',
        className,
      )}
    >
      {brand && <div className={cx('flex h-14 shrink-0 items-center', collapsed ? 'justify-center px-2' : 'px-4')}>{brand}</div>}

      <nav aria-label={ariaLabel} className={cx('min-h-0 flex-1 overflow-y-auto overflow-x-hidden py-2', collapsed ? 'px-2' : 'px-3')}>
        {groups.map((group, gi) => (
          <div key={group.id ?? group.label ?? gi}>
            {group.label &&
              (collapsed ? (
                gi > 0 && <div className="mx-2 my-3 h-px bg-stroke-light" aria-hidden="true" />
              ) : (
                <div className={cx('px-2.5 pb-1 text-[10px] font-semibold uppercase tracking-wide text-tertiary', gi > 0 ? 'pt-4' : 'pt-2')}>
                  {group.label}
                </div>
              ))}
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const active = item.id === activeId
                return (
                  <li key={item.id}>
                    <a
                      href={item.href}
                      aria-current={active ? 'page' : undefined}
                      aria-disabled={item.disabled || undefined}
                      title={collapsed ? item.label : undefined}
                      onClick={(e) => {
                        if (item.disabled) {
                          e.preventDefault()
                          return
                        }
                        onNavigate?.(item, e)
                      }}
                      className={cx(
                        'flex items-center gap-2.5 rounded-sm text-sm transition-colors',
                        collapsed ? 'h-9 justify-center' : 'px-2.5 py-1.5',
                        active ? 'bg-accent-tint font-medium text-accent' : 'text-secondary hover:bg-stroke-light hover:text-primary',
                        item.disabled && 'pointer-events-none opacity-50',
                      )}
                    >
                      <Icon name={item.icon} size={17} className={cx('shrink-0', active ? 'text-accent' : 'text-tertiary')} />
                      {!collapsed && <span className="min-w-0 flex-1 truncate">{item.label}</span>}
                      {!collapsed && item.badge != null && item.badge > 0 && <Badge count={item.badge} tone={active ? 'accent' : 'neutral'} />}
                    </a>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      {footer && <div className={cx('shrink-0 border-t border-stroke-light', collapsed ? 'p-2' : 'p-3')}>{footer}</div>}
    </aside>
  )
}
