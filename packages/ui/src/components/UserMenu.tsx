import { useCallback, useRef, useState } from 'react'
import { cx } from '../lib/cx'
import { useDismiss } from '../hooks/useDismiss'
import type { ThemeMode } from '../hooks/useTheme'
import { Icon, type IconName } from './Icon'
import { PopoverPanel } from './Overlay'
import { Segmented } from './Segmented'
import { Skeleton } from './Skeleton'
import { StatusDot } from './StatusDot'
import { Tag } from './Tag'

export interface UserMenuUser {
  id: string
  name: string
  email?: string
  roles?: string[]
  teams?: string[]
  admin?: boolean
}

export interface UserMenuItem {
  id: string
  label: string
  icon?: IconName
  onSelect: () => void
  tone?: 'default' | 'critical'
}

export interface UserMenuProps {
  user?: UserMenuUser | null
  /** loading: skeleton · offline: the backend did not answer */
  status?: 'online' | 'offline' | 'loading'
  items?: UserMenuItem[]
  themeMode?: ThemeMode
  onThemeChange?: (mode: ThemeMode) => void
  /** avatar only (collapsed sidebar) */
  collapsed?: boolean
  /** where the panel opens relative to the trigger */
  placement?: 'top' | 'bottom'
  className?: string
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  return parts
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')
}

/** Avatar trigger with a material panel: identity, theme and account actions. */
export function UserMenu({ user, status = 'online', items = [], themeMode, onThemeChange, collapsed, placement = 'top', className }: UserMenuProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(ref, open, close)

  const offline = status === 'offline'
  const loading = status === 'loading'
  const name = user?.name ?? (offline ? 'Offline' : 'Not signed in')
  const sub = user?.email ?? (user?.roles?.length ? user.roles.join(' · ') : undefined)

  return (
    <div ref={ref} className={cx('relative', className)}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={collapsed ? `Account: ${name}` : undefined}
        className={cx(
          'flex w-full items-center gap-2.5 rounded-md text-left transition-colors hover:bg-stroke-light',
          collapsed ? 'justify-center p-1.5' : 'px-2 py-1.5',
        )}
      >
        {loading ? (
          <Skeleton className="h-8 w-8 rounded-full" />
        ) : (
          <span
            className={cx(
              'relative flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-caption font-semibold',
              user && !offline ? 'bg-accent-tint text-accent' : 'bg-stroke-light text-tertiary',
            )}
          >
            {user ? initials(user.name) : <Icon name="user" size={15} />}
            {offline && (
              <StatusDot kind="critical" size="sm" className="absolute -bottom-px -right-px ring-2 ring-surface-secondary" />
            )}
          </span>
        )}
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1">
              {loading ? (
                <Skeleton className="h-3.5 w-24" />
              ) : (
                <>
                  <span className="block truncate text-sm font-medium text-primary">{name}</span>
                  {(sub || offline) && <span className="block truncate text-caption2 text-tertiary">{offline ? 'Backend unreachable' : sub}</span>}
                </>
              )}
            </span>
            <Icon name="more-horizontal" size={15} className="shrink-0 text-tertiary" />
          </>
        )}
      </button>

      {open && (
        <PopoverPanel
          role="menu"
          ariaLabel="Account"
          className={cx('absolute w-64', placement === 'top' ? 'bottom-full left-0 mb-2 origin-bottom-left' : 'left-0 top-full mt-2 origin-top-left')}
        >
          <div className="border-b border-stroke-light px-4 py-3">
            <p className="truncate text-sm font-medium text-primary">{name}</p>
            {user?.email && <p className="truncate text-caption text-tertiary">{user.email}</p>}
            {user && (user.roles?.length || user.teams?.length || user.admin) && (
              <div className="mt-2 flex flex-wrap gap-1">
                {user.admin && <Tag tone="accent" size="sm">admin</Tag>}
                {user.roles?.filter((r) => !(user.admin && r === 'admin')).map((r) => (
                  <Tag key={r} size="sm">
                    {r}
                  </Tag>
                ))}
                {user.teams?.map((t) => (
                  <Tag key={t} size="sm" outline>
                    {t}
                  </Tag>
                ))}
              </div>
            )}
          </div>
          {themeMode && onThemeChange && (
            <div className="flex items-center justify-between gap-3 border-b border-stroke-light px-4 py-2.5">
              <span className="text-caption text-secondary">Appearance</span>
              <Segmented
                size="sm"
                ariaLabel="Appearance"
                value={themeMode}
                onChange={onThemeChange}
                options={[
                  { value: 'light', icon: 'sun', title: 'Light' },
                  { value: 'dark', icon: 'moon', title: 'Dark' },
                  { value: 'system', icon: 'laptop', title: 'System' },
                ]}
              />
            </div>
          )}
          {items.length > 0 && (
            <div className="py-1">
              {items.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    close()
                    item.onSelect()
                  }}
                  className={cx(
                    'flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm transition-colors hover:bg-surface-secondary',
                    item.tone === 'critical' ? 'text-critical' : 'text-secondary hover:text-primary',
                  )}
                >
                  {item.icon && <Icon name={item.icon} size={15} className={item.tone === 'critical' ? 'text-critical' : 'text-tertiary'} />}
                  {item.label}
                </button>
              ))}
            </div>
          )}
        </PopoverPanel>
      )}
    </div>
  )
}
