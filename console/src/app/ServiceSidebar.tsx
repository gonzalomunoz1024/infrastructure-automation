import type { ReactNode, RefObject } from 'react'
import { Icon, IconButton, Portal, Tooltip, cx, useOverlayLifecycle, type IconName } from '@iaf/ui'
import { createStore } from '../lib/store'
import type { ConsoleService } from '../lib/descriptors'
import { Link, pathFor, type Route } from './router'
import { toolPath, toolsIn, type ToolSection } from './tools'

/*
 * The left navigation of a section: a service (its landing page and each of its resource types)
 * or identity and access (its pages). On wide screens it sits beside the content and can fold to
 * an icon rail; on narrow ones it is a drawer the header's menu button opens.
 */

const collapsedStore = createStore<boolean>('console-sidebar-collapsed', false, (raw) => (typeof raw === 'boolean' ? raw : null))

export interface NavItem {
  id: string
  label: string
  icon: IconName
  href: string
  active: boolean
  /** a count drawn after the label */
  badge?: number
}

export interface NavSection {
  /** 'Compute', 'Identity and access' */
  title: string
  icon: IconName
  subtitle: string
  items: NavItem[]
}

export function serviceNav(service: ConsoleService, route: Route): NavSection {
  const overview = pathFor('service', { service: service.id })
  return {
    title: service.title,
    icon: service.icon,
    subtitle: `${service.types.length} resource ${service.types.length === 1 ? 'type' : 'types'}`,
    items: [
      { id: 'overview', label: 'Overview', icon: 'home', href: overview, active: route.name === 'service' },
      ...service.types.map((t) => ({
        id: t.name,
        label: t.title,
        icon: t.icon,
        href: pathFor('type', { service: service.id, type: t.name }),
        active: (route.name === 'type' || route.name === 'create' || route.name === 'resource') && route.params.type === t.name,
      })),
    ],
  }
}

export function iamNav(route: Route, accountName: string, pendingRequests?: number): NavSection {
  const on = (...names: string[]) => names.includes(route.name)
  return {
    title: 'Identity and access',
    icon: 'shield',
    subtitle: accountName,
    items: [
      { id: 'overview', label: 'Overview', icon: 'home', href: pathFor('iam'), active: on('iam') },
      { id: 'users', label: 'Users', icon: 'users', href: pathFor('iam-users'), active: on('iam-users', 'iam-user') },
      { id: 'groups', label: 'Groups', icon: 'grid', href: pathFor('iam-groups'), active: on('iam-groups', 'iam-group') },
      { id: 'roles', label: 'Roles', icon: 'shield', href: pathFor('iam-roles'), active: on('iam-roles', 'iam-role') },
      { id: 'policies', label: 'Policies', icon: 'document', href: pathFor('iam-policies'), active: on('iam-policies', 'iam-policy', 'iam-policy-new', 'iam-policy-edit') },
      { id: 'simulator', label: 'Policy simulator', icon: 'play', href: pathFor('iam-simulator'), active: on('iam-simulator') },
      { id: 'guardrails', label: 'Guardrails', icon: 'lock', href: pathFor('iam-guardrails'), active: on('iam-guardrails', 'iam-guardrail', 'iam-guardrail-new') },
      { id: 'keys', label: 'Access keys', icon: 'key', href: pathFor('iam-keys'), active: on('iam-keys') },
      { id: 'requests', label: 'Access requests', icon: 'approval', href: pathFor('iam-requests'), active: on('iam-requests', 'iam-request'), badge: pendingRequests || undefined },
    ],
  }
}

/** A section of the console's own tools (resource groups and tags), from the tools list. */
export function toolsNav(section: ToolSection, route: Route): NavSection {
  return {
    title: section.title,
    icon: section.icon,
    subtitle: section.subtitle,
    items: toolsIn(section.id).map((t) => ({ id: t.route, label: t.label, icon: t.icon, href: toolPath(t), active: t.routes.includes(route.name) })),
  }
}

/** The items of a service, as the phase-0 shell named them. */
export function sidebarItems(service: ConsoleService, route: Route): NavItem[] {
  return serviceNav(service, route).items
}

function NavList({ items, collapsed, onNavigate }: { items: NavItem[]; collapsed: boolean; onNavigate?: () => void }) {
  return (
    <ul className="space-y-0.5 px-2">
      {items.map((item) => {
        const link = (
          <Link
            to={item.href}
            onClick={onNavigate}
            aria-current={item.active ? 'page' : undefined}
            className={cx(
              'flex h-9 items-center gap-2.5 rounded-md text-sm transition-colors',
              collapsed ? 'justify-center px-0' : 'px-2.5',
              item.active ? 'bg-accent-tint font-medium text-primary' : 'text-secondary hover:bg-surface-secondary hover:text-primary',
            )}
          >
            <Icon name={item.icon} size={16} className={cx('shrink-0', item.active ? 'text-accent' : 'text-tertiary')} />
            {!collapsed && <span className="min-w-0 flex-1 truncate">{item.label}</span>}
            {collapsed && <span className="sr-only">{item.label}</span>}
            {item.badge !== undefined && !collapsed && (
              <span className="rounded-full bg-accent px-1.5 text-caption2 font-semibold text-white" aria-label={`${item.badge} pending`}>
                {item.badge}
              </span>
            )}
          </Link>
        )
        return (
          <li key={item.id}>
            {collapsed ? (
              <Tooltip label={item.label} side="right">
                {link}
              </Tooltip>
            ) : (
              link
            )}
          </li>
        )
      })}
    </ul>
  )
}

function SectionHeading({ nav, collapsed, children }: { nav: NavSection; collapsed: boolean; children?: ReactNode }) {
  return (
    <div className={cx('flex items-center gap-2.5 border-b border-stroke-light', collapsed ? 'justify-center px-2 py-3' : 'px-4 py-3')}>
      <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
        <Icon name={nav.icon} size={16} />
      </span>
      {!collapsed && (
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-primary">{nav.title}</div>
          <div className="truncate text-caption text-tertiary">{nav.subtitle}</div>
        </div>
      )}
      {children}
    </div>
  )
}

export interface SectionSidebarProps {
  nav: NavSection
}

/** The sidebar beside the content on wide screens. */
export function SectionSidebar({ nav }: SectionSidebarProps) {
  const collapsed = collapsedStore.use()
  return (
    <aside aria-label={`${nav.title} navigation`} className={cx('hidden shrink-0 flex-col border-r border-stroke-light bg-surface-tertiary transition-[width] duration-200 lg:flex', collapsed ? 'w-14' : 'w-60')} data-collapsed={collapsed || undefined}>
      <SectionHeading nav={nav} collapsed={collapsed} />
      <nav className="flex-1 overflow-y-auto py-3" aria-label={`${nav.title} pages`}>
        <NavList items={nav.items} collapsed={collapsed} />
      </nav>
      <div className={cx('border-t border-stroke-light p-2', collapsed ? 'flex justify-center' : 'flex justify-end')}>
        <IconButton label={collapsed ? 'Expand the sidebar' : 'Collapse the sidebar'} active={collapsed} onClick={() => collapsedStore.set(!collapsed)}>
          <Icon name="sidebar" size={16} />
        </IconButton>
      </div>
    </aside>
  )
}

export interface SectionDrawerProps extends SectionSidebarProps {
  onClose: () => void
}

/** The same navigation as a drawer from the left, for narrow screens. */
export function SectionDrawer({ nav, onClose }: SectionDrawerProps) {
  // Escape, the focus trap and the return of focus come with the lifecycle hook
  const { closing, close, onPanelAnimationEnd, panelRef } = useOverlayLifecycle(onClose)
  return (
    <Portal>
      <div className={cx('material-scrim fixed inset-0 z-50', closing ? 'animate-fade-out' : 'animate-fade-in')} onClick={close} aria-hidden="true" />
      <div
        ref={panelRef as RefObject<HTMLDivElement | null>}
        role="dialog"
        aria-modal="true"
        aria-label={`${nav.title} navigation`}
        onAnimationEnd={onPanelAnimationEnd}
        className={cx('fixed inset-y-0 left-0 z-50 flex w-72 max-w-[85vw] flex-col bg-surface shadow-lg', closing ? 'animate-slide-out-left' : 'animate-slide-in-left')}
      >
        <SectionHeading nav={nav} collapsed={false}>
          <IconButton label="Close the service menu" onClick={close}>
            <Icon name="close" size={16} />
          </IconButton>
        </SectionHeading>
        <nav className="flex-1 overflow-y-auto py-3" aria-label={`${nav.title} pages`}>
          <NavList items={nav.items} collapsed={false} onNavigate={close} />
        </nav>
      </div>
    </Portal>
  )
}
