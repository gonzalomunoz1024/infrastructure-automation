import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { EmptyState, Icon, IconButton, Kbd, PopoverPanel, SkeletonText, cx, modKeyLabel, useDismiss, useTheme, type IconName } from '@iaf/ui'
import { BrandLockup } from './BrandMark'
import { UserMenuButton } from './UserMenuButton'
import { useConsole } from './console'
import { Link, navigate, pathFor } from './router'
import { DEMO_ACCOUNTS, formatAccountId, setAccount } from '../lib/accounts'
import { setCurrentUser } from '../lib/identity'
import { clearQueryCache } from '../lib/queries'
import { setRegionName } from '../lib/regions'
import { phaseNote } from './Phase'

/*
 * The top bar, in the AWS console's arrangement drawn in Atlas: the brand and the Services menu
 * at the left, one search field in the centre, and at the right the notifications bell, help,
 * the account picker, the region picker and the user menu. It stays put while the page scrolls.
 */

export interface HeaderProps {
  onOpenSearch: () => void
  /** shown on narrow screens when a service sidebar exists */
  onOpenSidebar?: () => void
}

export function Header({ onOpenSearch, onOpenSidebar }: HeaderProps) {
  return (
    <header className="relative z-40 shrink-0 border-b border-stroke-light bg-surface/85 backdrop-blur-md">
      <div className="flex h-14 items-center gap-1.5 px-3 sm:gap-2 sm:px-4">
        {onOpenSidebar && (
          <IconButton label="Open the service menu" className="lg:hidden" onClick={onOpenSidebar}>
            <Icon name="menu" size={18} />
          </IconButton>
        )}
        <BrandLockup />
        <ServicesMenu />
        <div className="flex min-w-0 flex-1 justify-center px-1 sm:px-4">
          <SearchTrigger onClick={onOpenSearch} />
        </div>
        <div className="flex shrink-0 items-center gap-0.5 sm:gap-1">
          <NotificationsBell />
          <HelpMenu />
          <AccountPicker />
          <RegionPicker />
          <UserButton />
        </div>
      </div>
    </header>
  )
}

/* ---------------------------------------------------------------- services */

function ServicesMenu() {
  const { services, loading, error } = useConsole()
  const [open, setOpen] = useState(false)
  // the button and the panel share one node, so a click on the button is never an outside click
  const wrapRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => {
    setOpen(false)
    buttonRef.current?.focus()
  }, [])
  useDismiss(wrapRef, open, close)

  useEffect(() => {
    if (!open) return
    panelRef.current?.querySelector<HTMLElement>('a, button')?.focus()
  }, [open])

  return (
    <div ref={wrapRef}>
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
        className={cx('flex h-9 items-center gap-1 rounded-md px-2 text-sm font-medium transition-colors hover:bg-surface-secondary', open ? 'bg-surface-secondary text-primary' : 'text-secondary hover:text-primary')}
      >
        <Icon name="grid" size={16} />
        <span className="hidden sm:inline">Services</span>
        <Icon name="chevron-down" size={12} className={cx('transition-transform duration-150', open && 'rotate-180')} />
      </button>
      {open && (
        <div ref={panelRef} className="absolute inset-x-0 top-full z-50 px-3 sm:px-4">
          <PopoverPanel role="dialog" ariaLabel="Services" className="mt-1 max-h-[75vh] overflow-y-auto p-4 sm:p-6">
            <div className="mb-4 flex items-center justify-between gap-3">
              <div>
                <h2 className="text-subheadline font-semibold text-primary">Services</h2>
                <p className="text-caption text-secondary">Every service the platform serves, and the resource types in each.</p>
              </div>
              <IconButton label="Close the services menu" onClick={close}>
                <Icon name="close" size={16} />
              </IconButton>
            </div>
            {loading && services.length === 0 ? (
              <SkeletonText lines={4} />
            ) : services.length === 0 ? (
              <EmptyState compact icon={error ? 'alert-triangle' : 'grid'} title={error ? 'The platform did not answer' : 'No services yet'} message={error ? 'The service list could not be loaded, and nothing was cached in this browser.' : 'Services appear as the platform publishes resource types.'} />
            ) : (
              <div className="grid grid-cols-1 gap-x-8 gap-y-6 sm:grid-cols-2 lg:grid-cols-4">
                {services.map((s) => (
                  <section key={s.id} aria-labelledby={`services-${s.id}`}>
                    <Link to={pathFor('service', { service: s.id })} onClick={close} id={`services-${s.id}`} className="flex items-center gap-2 rounded-md text-sm font-semibold text-primary transition-opacity hover:opacity-70">
                      <span className="flex size-7 items-center justify-center rounded-md bg-accent-tint text-accent">
                        <Icon name={s.icon} size={15} />
                      </span>
                      {s.title}
                    </Link>
                    <ul className="mt-2 space-y-0.5">
                      {s.types.map((t) => (
                        <li key={t.name}>
                          <Link to={pathFor('type', { service: s.id, type: t.name })} onClick={close} className="flex items-center gap-2 rounded-md px-2 py-1 text-sm text-secondary transition-colors hover:bg-surface-secondary hover:text-primary">
                            <Icon name={t.icon} size={14} className="text-tertiary" />
                            <span className="truncate">{t.title}</span>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
              </div>
            )}
          </PopoverPanel>
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- search */

function SearchTrigger({ onClick }: { onClick: () => void }) {
  return (
    <>
      {/* named by its visible text; the keycap is a hint, not part of the name */}
      <button
        type="button"
        onClick={onClick}
        aria-keyshortcuts="Meta+K Control+K"
        className="hidden h-9 w-full max-w-xl items-center gap-2 rounded-md border border-stroke-light bg-surface-secondary px-3 text-sm text-tertiary transition-colors hover:border-stroke hover:text-secondary sm:flex"
      >
        <Icon name="search" size={15} />
        <span className="flex-1 truncate text-left">Search services, resources and pages</span>
        <span aria-hidden="true" className="flex">
          <Kbd className="text-secondary">{modKeyLabel()}K</Kbd>
        </span>
      </button>
      <IconButton label="Search" className="sm:hidden" onClick={onClick}>
        <Icon name="search" size={18} />
      </IconButton>
    </>
  )
}

/* ---------------------------------------------------------------- menus */

function HeaderMenu({ label, icon, text, hint, children, width = 'w-72', role = 'menu' }: { label: string; icon: IconName; text?: ReactNode; hint?: ReactNode; children: (close: () => void) => ReactNode; width?: string; role?: 'menu' | 'dialog' }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(ref, open, close)
  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup={role === 'menu' ? 'menu' : 'dialog'}
        aria-expanded={open}
        className={cx('flex h-9 items-center gap-1.5 rounded-full px-2 text-sm transition-colors hover:bg-surface-secondary', open ? 'bg-surface-secondary text-primary' : 'text-secondary hover:text-primary')}
      >
        {/* the full label is the accessible name at every width; the visible text and hint repeat parts of it */}
        <span className="sr-only">{label}</span>
        <Icon name={icon} size={17} />
        {text && (
          <span aria-hidden="true" className="hidden max-w-40 truncate md:inline">
            {text}
          </span>
        )}
        {hint && (
          <span aria-hidden="true" className="hidden font-mono text-caption text-tertiary xl:inline">
            {hint}
          </span>
        )}
      </button>
      {open && (
        <PopoverPanel role={role} ariaLabel={label} className={cx('absolute right-0 top-full mt-1 py-1', width)}>
          {children(close)}
        </PopoverPanel>
      )}
    </div>
  )
}

function MenuItem({ children, onSelect, selected, disabled, hint, icon }: { children: ReactNode; onSelect?: () => void; selected?: boolean; disabled?: boolean; hint?: string; icon?: IconName }) {
  return (
    <button
      type="button"
      role={selected === undefined ? 'menuitem' : 'menuitemradio'}
      onClick={onSelect}
      disabled={disabled}
      aria-checked={selected}
      title={disabled ? hint : undefined}
      className={cx('flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors', disabled ? 'cursor-not-allowed text-tertiary' : 'text-primary hover:bg-surface-secondary', selected && 'bg-accent-tint')}
    >
      {icon && <Icon name={icon} size={15} className="shrink-0 text-tertiary" />}
      <span className="min-w-0 flex-1">{children}</span>
      {selected && <Icon name="check" size={14} className="shrink-0 text-accent" />}
      {hint && disabled && <span className="shrink-0 text-caption text-tertiary">{hint}</span>}
    </button>
  )
}

function MenuHeading({ children }: { children: ReactNode }) {
  return <div className="px-3 pb-1 pt-2 text-caption2 font-semibold uppercase tracking-wide text-tertiary">{children}</div>
}

function NotificationsBell() {
  return (
    <HeaderMenu label="Notifications" icon="bell" role="dialog" width="w-80">
      {() => <EmptyState compact icon="bell" title="No notifications yet" message="Approvals, failures and finished requests will land here in phase 2." />}
    </HeaderMenu>
  )
}

function HelpMenu() {
  return (
    <HeaderMenu label="Help" icon="help" role="dialog" width="w-80">
      {(close) => (
        <div className="px-3 py-2 text-sm">
          <p className="font-medium text-primary">The customer console</p>
          <p className="mt-1 text-caption text-secondary">One place to see and, from phase 3, change the resources the platform manages for your team: pick a service, open a type, open a resource.</p>
          <dl className="mt-3 space-y-1.5 text-caption">
            <div className="flex items-center justify-between gap-3">
              <dt className="text-secondary">Search anything</dt>
              <dd>
                <Kbd className="text-secondary">{modKeyLabel()}K</Kbd>
              </dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-secondary">Close a panel</dt>
              <dd>
                <Kbd className="text-secondary">Esc</Kbd>
              </dd>
            </div>
          </dl>
          <div className="mt-3 flex flex-col gap-1 border-t border-stroke-light pt-2">
            <a href="/api/v1/openapi.json" target="_blank" rel="noreferrer" className="flex items-center gap-2 rounded-md py-1 text-accent transition-opacity hover:opacity-70">
              <Icon name="terminal" size={14} />
              Platform API reference
              <Icon name="external" size={11} />
            </a>
            <Link to={pathFor('settings')} onClick={close} className="flex items-center gap-2 rounded-md py-1 text-accent transition-opacity hover:opacity-70">
              <Icon name="settings" size={14} />
              Settings
            </Link>
          </div>
        </div>
      )}
    </HeaderMenu>
  )
}

function AccountPicker() {
  const { account } = useConsole()
  return (
    <HeaderMenu label={`Account: ${account.name} ${formatAccountId(account.id)}`} icon="users" text={account.name} hint={formatAccountId(account.id)} width="w-80">
      {(close) => (
        <>
          <MenuHeading>Accounts</MenuHeading>
          {DEMO_ACCOUNTS.map((a) => (
            <MenuItem
              key={a.id}
              selected={a.id === account.id}
              onSelect={() => {
                setAccount(a.id)
                close()
              }}
            >
              <span className="block truncate">{a.name}</span>
              <span className="block font-mono text-caption text-tertiary">
                {formatAccountId(a.id)} · {a.team}
              </span>
            </MenuItem>
          ))}
          <div className="my-1 border-t border-stroke-light" />
          <MenuItem disabled hint="phase 2" icon="shield">
            Switch role
          </MenuItem>
          <p className="px-3 pb-2 pt-1 text-caption2 text-tertiary">Demo accounts until the platform serves them ({phaseNote(2).toLowerCase()}).</p>
        </>
      )}
    </HeaderMenu>
  )
}

function RegionPicker() {
  const { regions, region } = useConsole()
  if (regions.length <= 1) {
    return (
      <span className="hidden h-9 items-center gap-1.5 px-2 text-sm text-secondary sm:flex" title="The platform serves one region">
        <Icon name="ring" size={16} />
        <span className="sr-only">Region: </span>
        <span className="hidden md:inline">{region.title}</span>
        <span className="sr-only md:hidden">{region.title}</span>
      </span>
    )
  }
  return (
    <HeaderMenu label={`Region: ${region.title}`} icon="ring" text={region.title} width="w-64">
      {(close) => (
        <>
          <MenuHeading>Regions</MenuHeading>
          {regions.map((r) => (
            <MenuItem
              key={r.name}
              selected={r.name === region.name}
              onSelect={() => {
                setRegionName(r.name)
                close()
              }}
            >
              <span className="block">{r.title}</span>
              <span className="block font-mono text-caption text-tertiary">
                {r.name}
                {r.status && r.status !== 'available' ? ` · ${r.status}` : ''}
              </span>
            </MenuItem>
          ))}
        </>
      )}
    </HeaderMenu>
  )
}

function UserButton() {
  const { me, offline } = useConsole()
  const { mode, setMode } = useTheme()
  return (
    <UserMenuButton
      user={me ? { id: me.id, name: me.name, email: me.email, roles: me.roles, teams: me.teams, admin: me.admin } : null}
      status={offline ? 'offline' : me ? 'online' : 'loading'}
      themeMode={mode}
      onThemeChange={setMode}
      items={[
        { id: 'settings', label: 'Settings', icon: 'settings', onSelect: () => navigate(pathFor('settings')) },
        { id: 'switch-identity', label: 'Switch identity', icon: 'users', onSelect: () => navigate(pathFor('signin', {}, { switch: 1 })) },
        {
          id: 'sign-out',
          label: 'Sign out',
          icon: 'arrow-right',
          tone: 'critical',
          onSelect: () => {
            clearQueryCache()
            setCurrentUser(null)
            navigate(pathFor('signin'))
          },
        },
      ]}
    />
  )
}
