import type { ReactNode, RefObject } from 'react'
import { Icon, IconButton, Portal, Tooltip, cx, useOverlayLifecycle, type IconName } from '@iaf/ui'
import { createStore } from '../lib/store'
import type { ConsoleService } from '../lib/descriptors'
import { Link, pathFor, type Route } from './router'

/*
 * The left navigation of a service: its landing page and each of its resource types. On wide
 * screens it sits beside the content and can fold to an icon rail; on narrow ones it is a drawer
 * the header's menu button opens.
 */

const collapsedStore = createStore<boolean>('console-sidebar-collapsed', false, (raw) => (typeof raw === 'boolean' ? raw : null))

interface NavItem {
  id: string
  label: string
  icon: IconName
  href: string
  active: boolean
}

export function sidebarItems(service: ConsoleService, route: Route): NavItem[] {
  const overview = pathFor('service', { service: service.id })
  return [
    { id: 'overview', label: 'Overview', icon: 'home', href: overview, active: route.name === 'service' },
    ...service.types.map((t) => ({
      id: t.name,
      label: t.title,
      icon: t.icon,
      href: pathFor('type', { service: service.id, type: t.name }),
      active: (route.name === 'type' || route.name === 'resource') && route.params.type === t.name,
    })),
  ]
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
            {!collapsed && <span className="truncate">{item.label}</span>}
            {collapsed && <span className="sr-only">{item.label}</span>}
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

function ServiceHeading({ service, collapsed, children }: { service: ConsoleService; collapsed: boolean; children?: ReactNode }) {
  return (
    <div className={cx('flex items-center gap-2.5 border-b border-stroke-light', collapsed ? 'justify-center px-2 py-3' : 'px-4 py-3')}>
      <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-accent-tint text-accent">
        <Icon name={service.icon} size={16} />
      </span>
      {!collapsed && (
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-primary">{service.title}</div>
          <div className="truncate text-caption text-tertiary">
            {service.types.length} resource {service.types.length === 1 ? 'type' : 'types'}
          </div>
        </div>
      )}
      {children}
    </div>
  )
}

export interface ServiceSidebarProps {
  service: ConsoleService
  route: Route
}

/** The sidebar beside the content on wide screens. */
export function ServiceSidebar({ service, route }: ServiceSidebarProps) {
  const collapsed = collapsedStore.use()
  const items = sidebarItems(service, route)
  return (
    <aside aria-label={`${service.title} navigation`} className={cx('hidden shrink-0 flex-col border-r border-stroke-light bg-surface-tertiary transition-[width] duration-200 lg:flex', collapsed ? 'w-14' : 'w-60')} data-collapsed={collapsed || undefined}>
      <ServiceHeading service={service} collapsed={collapsed} />
      <nav className="flex-1 overflow-y-auto py-3" aria-label={`${service.title} pages`}>
        <NavList items={items} collapsed={collapsed} />
      </nav>
      <div className={cx('border-t border-stroke-light p-2', collapsed ? 'flex justify-center' : 'flex justify-end')}>
        <IconButton label={collapsed ? 'Expand the sidebar' : 'Collapse the sidebar'} active={collapsed} onClick={() => collapsedStore.set(!collapsed)}>
          <Icon name="sidebar" size={16} />
        </IconButton>
      </div>
    </aside>
  )
}

export interface ServiceDrawerProps extends ServiceSidebarProps {
  onClose: () => void
}

/** The same navigation as a drawer from the left, for narrow screens. */
export function ServiceDrawer({ service, route, onClose }: ServiceDrawerProps) {
  // Escape, the focus trap and the return of focus come with the lifecycle hook
  const { closing, close, onPanelAnimationEnd, panelRef } = useOverlayLifecycle(onClose)
  const items = sidebarItems(service, route)
  return (
    <Portal>
      <div className={cx('material-scrim fixed inset-0 z-50', closing ? 'animate-fade-out' : 'animate-fade-in')} onClick={close} aria-hidden="true" />
      <div
        ref={panelRef as RefObject<HTMLDivElement | null>}
        role="dialog"
        aria-modal="true"
        aria-label={`${service.title} navigation`}
        onAnimationEnd={onPanelAnimationEnd}
        className={cx('fixed inset-y-0 left-0 z-50 flex w-72 max-w-[85vw] flex-col bg-surface shadow-lg', closing ? 'animate-slide-out-left' : 'animate-slide-in-left')}
      >
        <ServiceHeading service={service} collapsed={false}>
          <IconButton label="Close the service menu" onClick={close}>
            <Icon name="close" size={16} />
          </IconButton>
        </ServiceHeading>
        <nav className="flex-1 overflow-y-auto py-3" aria-label={`${service.title} pages`}>
          <NavList items={items} collapsed={false} onNavigate={close} />
        </nav>
      </div>
    </Portal>
  )
}
