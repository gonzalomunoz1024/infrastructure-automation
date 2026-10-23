import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Badge, EmptyState, Icon, IconButton, Kbd, PopoverPanel, SkeletonText, cx, modKeyLabel, relativeTime, useDismiss, useTheme, useToast, type IconName } from '@iaf/ui'
import { BrandLockup } from './BrandMark'
import { RoleTag } from './RoleTag'
import { UserMenuButton } from './UserMenuButton'
import { useAccessRequestQueue } from './access'
import { useConsole } from './console'
import { Link, navigate, pathFor } from './router'
import { TOOL_SECTIONS, toolPath, toolsIn } from './tools'
import { formatAccountId } from '../lib/accounts'
import { isApiError } from '../lib/api'
import { bff, signOut, switchAccount, switchIdentity, switchRegion } from '../lib/bff'
import { useQuery } from '../lib/queries'
import { primaryRole, roleLabel } from '../lib/session'
import { PHASES } from './Phase'

/*
 * The top bar, in the AWS console's arrangement drawn in Atlas: the brand and the Services menu
 * at the left, one search field in the centre, and at the right the notifications bell (the
 * access requests an admin has to decide), help, the account picker with the role you hold, the
 * region picker and the user menu. It stays put while the page scrolls.
 */

export interface HeaderProps {
  onOpenSearch: () => void
  /** shown on narrow screens when a sidebar exists */
  onOpenSidebar?: () => void
  /** the copilot's side panel (phase 9) */
  onOpenCopilot?: () => void
  copilotOpen?: boolean
}

export function Header({ onOpenSearch, onOpenSidebar, onOpenCopilot, copilotOpen }: HeaderProps) {
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
          {onOpenCopilot && <CopilotButton onClick={onOpenCopilot} open={!!copilotOpen} />}
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
        <span className="sr-only sm:not-sr-only">Services</span>
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
            <div className="mt-6 grid grid-cols-1 gap-x-8 gap-y-6 border-t border-stroke-light pt-5 sm:grid-cols-2 lg:grid-cols-4">
              {TOOL_SECTIONS.map((section) => (
                <section key={section.id} aria-labelledby={`services-tools-${section.id}`}>
                  <h3 id={`services-tools-${section.id}`} className="flex items-center gap-2 text-sm font-semibold text-primary">
                    <span className="flex size-7 items-center justify-center rounded-md bg-accent-tint text-accent">
                      <Icon name={section.icon} size={15} />
                    </span>
                    {section.title}
                  </h3>
                  <ul className="mt-2 space-y-0.5">
                    {toolsIn(section.id).map((t) => (
                      <li key={t.route}>
                        <Link to={toolPath(t)} onClick={close} className="flex items-center gap-2 rounded-md px-2 py-1 text-sm text-secondary transition-colors hover:bg-surface-secondary hover:text-primary">
                          <Icon name={t.icon} size={14} className="text-tertiary" />
                          <span className="truncate">{t.label}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </section>
              ))}
            </div>
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

function HeaderMenu({ label, icon, text, hint, badge, children, width = 'w-72', role = 'menu' }: { label: string; icon: IconName; text?: ReactNode; hint?: ReactNode; badge?: number; children: (close: () => void) => ReactNode; width?: string; role?: 'menu' | 'dialog' }) {
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
        className={cx('relative flex h-9 items-center gap-1.5 rounded-full px-2 text-sm transition-colors hover:bg-surface-secondary', open ? 'bg-surface-secondary text-primary' : 'text-secondary hover:text-primary')}
      >
        {/* the full label is the accessible name at every width; the visible text and hint repeat parts of it */}
        <span className="sr-only">{label}</span>
        <Icon name={icon} size={17} />
        {!!badge && (
          <span aria-hidden="true" className="absolute -right-0.5 -top-0.5">
            <Badge count={badge} tone="accent" />
          </span>
        )}
        {text && (
          <span aria-hidden="true" className="hidden max-w-56 truncate md:inline">
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

/** Opens the copilot's side panel (phase 9): plain English to a list, a form or an explanation. */
function CopilotButton({ onClick, open }: { onClick: () => void; open: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-expanded={open}
      aria-controls="copilot-panel"
      className={cx('flex h-9 items-center gap-1.5 rounded-full px-2 text-sm transition-colors hover:bg-surface-secondary', open ? 'bg-surface-secondary text-primary' : 'text-secondary hover:text-primary')}
    >
      <span className="sr-only">Ask the copilot</span>
      <Icon name="sparkles" size={17} />
      <span aria-hidden="true" className="hidden xl:inline">
        Copilot
      </span>
    </button>
  )
}

function NotificationsBell() {
  const queue = useAccessRequestQueue()
  const items = queue.toDecide
  const count = items.length
  return (
    <HeaderMenu label={count ? `Notifications, ${count} to decide` : 'Notifications'} icon="bell" role="dialog" width="w-80" badge={count}>
      {(close) =>
        count === 0 ? (
          <EmptyState compact icon="bell" title="Nothing to decide" message={queue.readable ? `Access requests waiting for you land here. Alerts, failures and finished requests arrive with ${PHASES[2]}.` : `Alerts, failures and finished requests arrive with ${PHASES[2]}.`} />
        ) : (
          <div>
            <MenuHeading>Access requests to decide</MenuHeading>
            <ul aria-label="Access requests to decide">
              {items.map((r) => (
                <li key={r.requestId}>
                  <Link to={pathFor('iam-request', { id: r.requestId })} onClick={close} className="block px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                    <span className="block text-primary">
                      {r.requester.name ?? r.requester.userId} asks for <span className="font-medium">{roleLabel(r.role)}</span>
                    </span>
                    <span className="block truncate font-mono text-caption text-tertiary">
                      {r.action} · {relativeTime(r.requestedAt)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            <Link to={pathFor('iam-requests')} onClick={close} className="block border-t border-stroke-light px-3 py-2 text-caption font-medium text-accent transition-opacity hover:opacity-70">
              All access requests
            </Link>
          </div>
        )
      }
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
            <Link to={pathFor('iam')} onClick={close} className="flex items-center gap-2 rounded-md py-1 text-accent transition-opacity hover:opacity-70">
              <Icon name="shield" size={14} />
              Identity and access
            </Link>
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
  const { account, session } = useConsole()
  const toast = useToast()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts())
  const role = primaryRole(account.roles)
  const list = accounts.data?.items ?? [session.account]
  const pick = (accountId: string) => {
    switchAccount(accountId).catch((e: unknown) => toast.error('Could not switch account', isApiError(e) ? e.error : String(e)))
  }
  return (
    <HeaderMenu
      label={`Account: ${account.name} ${formatAccountId(account.id)}${role ? `, your role ${roleLabel(role)}` : ''}`}
      icon="users"
      text={
        <span className="flex items-center gap-1.5">
          <span className="truncate">{account.name}</span>
          {role && <RoleTag role={role} />}
        </span>
      }
      hint={formatAccountId(account.id)}
      width="w-80"
    >
      {(close) => (
        <>
          <MenuHeading>Your accounts</MenuHeading>
          {list.map((a) => (
            <MenuItem
              key={a.accountId}
              selected={a.accountId === account.id}
              onSelect={() => {
                close()
                if (a.accountId !== account.id) pick(a.accountId)
              }}
            >
              <span className="block truncate">{a.name}</span>
              <span className="block font-mono text-caption text-tertiary">
                {formatAccountId(a.accountId)}
                {a.owningTeam ? ` · ${a.owningTeam}` : ''}
              </span>
              {!!a.roles?.length && (
                <span className="mt-1 flex flex-wrap gap-1">
                  {a.roles.map((r) => (
                    <RoleTag key={r} role={r} />
                  ))}
                </span>
              )}
            </MenuItem>
          ))}
          <div className="my-1 border-t border-stroke-light" />
          <MenuItem
            icon="shield"
            onSelect={() => {
              close()
              navigate(pathFor('iam'))
            }}
          >
            Identity and access
          </MenuItem>
          <p className="px-3 pb-2 pt-1 text-caption2 text-tertiary">Your role in an account comes from the groups you are in there.</p>
        </>
      )}
    </HeaderMenu>
  )
}

function RegionPicker() {
  const { regions, region } = useConsole()
  const toast = useToast()
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
                close()
                switchRegion(r.name).catch((e: unknown) => toast.error('Could not switch region', isApiError(e) ? e.error : String(e)))
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
      user={{ id: me.id, name: me.name, email: me.email, roles: me.roles, teams: me.teams, admin: me.admin }}
      status={offline ? 'offline' : 'online'}
      themeMode={mode}
      onThemeChange={setMode}
      items={[
        { id: 'iam', label: 'Identity and access', icon: 'shield', onSelect: () => navigate(pathFor('iam')) },
        { id: 'settings', label: 'Settings', icon: 'settings', onSelect: () => navigate(pathFor('settings')) },
        { id: 'switch-identity', label: 'Switch identity', icon: 'users', onSelect: () => void switchIdentity() },
        {
          id: 'sign-out',
          label: 'Sign out',
          icon: 'arrow-right',
          tone: 'critical',
          onSelect: () => void signOut().then(() => navigate(pathFor('signin'))),
        },
      ]}
    />
  )
}
