import { useCallback, useRef, useState } from 'react'
import { Icon, PopoverPanel, Segmented, Skeleton, StatusDot, Tag, cx, useDismiss, type ThemeMode, type UserMenuItem, type UserMenuUser } from '@iaf/ui'

/*
 * The header's account button: an avatar with the signed-in person's initials, and a panel with
 * who they are, the appearance and the account actions. It is Atlas's UserMenu drawn for a
 * header. The trigger names itself with the person's name in visually hidden text and keeps the
 * initials out of the name, so the visible text and the accessible name agree (WCAG 2.5.3,
 * label in name); Atlas's collapsed trigger labels itself over the initials, which the axe check
 * rejects. The panel is Atlas's, with the colour of the critical action in its icon, not its
 * text. A note for P-14.
 */

export interface UserMenuButtonProps {
  user?: UserMenuUser | null
  /** loading: a skeleton; offline: the platform did not answer */
  status?: 'online' | 'offline' | 'loading'
  items?: UserMenuItem[]
  themeMode?: ThemeMode
  onThemeChange?: (mode: ThemeMode) => void
  className?: string
}

/** "Gonzalo Munoz" → "GM" */
export function initialsOf(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')
}

export function UserMenuButton({ user, status = 'online', items = [], themeMode, onThemeChange, className }: UserMenuButtonProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(ref, open, close)

  const offline = status === 'offline'
  const loading = status === 'loading'
  const name = user?.name ?? (offline ? 'Offline' : 'Not signed in')

  return (
    <div ref={ref} className={cx('relative', className)}>
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="menu" className={cx('flex h-9 items-center justify-center rounded-full px-1 transition-colors hover:bg-surface-secondary', open && 'bg-surface-secondary')}>
        <span className="sr-only">{loading ? 'Account menu, loading' : `Account menu: ${name}`}</span>
        {loading ? (
          <Skeleton className="h-7 w-7 rounded-full" />
        ) : (
          <span aria-hidden="true" className={cx('relative flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-caption2 font-semibold', user && !offline ? 'bg-accent-tint text-accent' : 'bg-stroke-light text-tertiary')}>
            {user ? initialsOf(user.name) : <Icon name="user" size={14} />}
            {offline && <StatusDot kind="critical" size="sm" className="absolute -bottom-px -right-px ring-2 ring-surface" />}
          </span>
        )}
      </button>

      {open && (
        <PopoverPanel role="menu" ariaLabel="Account" className="absolute right-0 top-full mt-1 w-64 origin-top-right">
          <div className="border-b border-stroke-light px-4 py-3">
            <p className="truncate text-sm font-medium text-primary">{name}</p>
            {user?.email && <p className="truncate text-caption text-tertiary">{user.email}</p>}
            {offline && <p className="text-caption text-tertiary">The platform did not answer the identity call.</p>}
            {user && (user.roles?.length || user.teams?.length || user.admin) && (
              <div className="mt-2 flex flex-wrap gap-1">
                {user.admin && (
                  <Tag tone="accent" size="sm">
                    admin
                  </Tag>
                )}
                {user.roles
                  ?.filter((r) => !(user.admin && r === 'admin'))
                  .map((r) => (
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
              <Segmented<ThemeMode>
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
                  className="flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm text-primary transition-colors hover:bg-surface-secondary"
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
