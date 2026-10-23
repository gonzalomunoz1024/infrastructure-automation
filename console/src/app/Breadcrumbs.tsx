import { Icon, cx } from '@iaf/ui'
import { displayWfrn } from '@iaf/console-kit'
import { useConsole } from './console'
import { findService, findType, idOfWfrn } from '../lib/descriptors'
import { Link, pathFor, type Route } from './router'

/*
 * Where you are: Console home, the service, the type, the resource. Every crumb but the last is
 * a link; the last names the page for screen readers (aria-current).
 */

export interface Crumb {
  label: string
  href?: string
  mono?: boolean
}

export function crumbsFor(route: Route, lookup: { serviceTitle: (id: string) => string; typeTitle: (service: string, type: string) => string }): Crumb[] {
  const home: Crumb = { label: 'Console home', href: pathFor('home') }
  const iam: Crumb = { label: 'Identity and access', href: pathFor('iam') }
  switch (route.name) {
    case 'home':
      return [{ label: 'Console home' }]
    case 'service':
      return [home, { label: lookup.serviceTitle(route.params.service) }]
    case 'type':
      return [home, { label: lookup.serviceTitle(route.params.service), href: pathFor('service', { service: route.params.service }) }, { label: lookup.typeTitle(route.params.service, route.params.type) }]
    case 'create':
      return [
        home,
        { label: lookup.serviceTitle(route.params.service), href: pathFor('service', { service: route.params.service }) },
        { label: lookup.typeTitle(route.params.service, route.params.type), href: pathFor('type', { service: route.params.service, type: route.params.type }) },
        { label: 'Create' },
      ]
    case 'resource':
      return [
        home,
        { label: lookup.serviceTitle(route.params.service), href: pathFor('service', { service: route.params.service }) },
        { label: lookup.typeTitle(route.params.service, route.params.type), href: pathFor('type', { service: route.params.service, type: route.params.type }) },
        { label: idOfWfrn(route.params.wfrn) ?? displayWfrn(route.params.wfrn, 32), mono: true },
      ]
    case 'requests':
      return [home, { label: 'Requests' }]
    case 'request':
      return [home, { label: 'Requests', href: pathFor('requests') }, { label: route.params.id, mono: true }]
    case 'iam':
      return [home, { label: 'Identity and access' }]
    case 'iam-users':
    case 'iam-groups':
    case 'iam-roles':
    case 'iam-keys':
    case 'iam-requests':
    case 'iam-reviews':
    case 'iam-elevations':
      return [home, iam, { label: route.def?.title ?? 'Identity and access' }]
    case 'iam-review':
      return [home, iam, { label: 'Access reviews', href: pathFor('iam-reviews') }, { label: 'Campaign' }]
    case 'org-accounts':
      return [home, { label: 'Organization' }, { label: 'Accounts' }]
    case 'iam-user':
      return [home, iam, { label: 'Users', href: pathFor('iam-users') }, { label: route.params.id, mono: true }]
    case 'iam-group':
      return [home, iam, { label: 'Groups', href: pathFor('iam-groups') }, { label: route.params.id, mono: true }]
    case 'iam-role':
      return [home, iam, { label: 'Roles', href: pathFor('iam-roles') }, { label: route.params.id, mono: true }]
    case 'iam-policies':
    case 'iam-simulator':
    case 'iam-guardrails':
      return [home, iam, { label: route.def?.title ?? 'Identity and access' }]
    case 'iam-policy-new':
    case 'iam-policy':
    case 'iam-policy-edit':
      return [home, iam, { label: 'Policies', href: pathFor('iam-policies') }, { label: route.def?.title ?? 'Policy' }]
    case 'iam-guardrail-new':
    case 'iam-guardrail':
      return [home, iam, { label: 'Guardrails', href: pathFor('iam-guardrails') }, { label: route.def?.title ?? 'Guardrail' }]
    case 'iam-request':
      return [home, iam, { label: 'Access requests', href: pathFor('iam-requests') }, { label: route.params.id, mono: true }]
    case 'groups':
      return [home, { label: 'Resource groups' }]
    case 'group':
      return [home, { label: 'Resource groups', href: pathFor('groups') }, { label: route.params.id, mono: true }]
    case 'tag-editor':
    case 'tag-policy':
      return [home, { label: route.def?.title ?? 'Tags' }]
    case 'drift':
    case 'unmanaged':
    case 'alarms':
    case 'rightsizing':
      return [home, { label: 'Operations', href: pathFor('drift') }, { label: route.def?.title ?? 'Operations' }]
    case 'activity':
    case 'as-of':
    case 'map':
      return [home, { label: 'Activity and history', href: pathFor('activity') }, { label: route.def?.title ?? 'Activity and history' }]
    case 'service-quotas':
      return [home, { label: 'Service Quotas' }]
    case 'settings':
      return [home, { label: 'Settings' }]
    case 'iam-cli':
      return [home, iam, { label: 'CLI sign-ins' }]
    case 'device':
    case 'shell':
    case 'portal':
      return [home, { label: route.def?.title ?? 'Developer tools' }]
    case 'notifications':
    case 'digests':
      return [home, { label: route.def?.title ?? 'Notifications' }]
    case 'notification-settings':
    case 'chat-link':
      return [home, { label: 'Settings', href: pathFor('settings') }, { label: route.def?.title ?? 'Notifications' }]
    case 'digest':
      return [home, { label: 'Weekly digests', href: pathFor('digests') }, { label: route.params.weekStart }]
    case 'insights':
      return [home, { label: 'Executive home' }]
    case 'cost':
      return [home, { label: 'Cost and usage' }]
    case 'budgets':
    case 'chargeback':
      return [home, { label: 'Cost and usage', href: pathFor('cost') }, { label: route.def?.title ?? 'Budgets' }]
    case 'budget':
      return [home, { label: 'Cost and usage', href: pathFor('cost') }, { label: 'Budgets', href: pathFor('budgets') }, { label: route.params.id, mono: true }]
    default:
      return [home, { label: 'Not found' }]
  }
}

export function Breadcrumbs({ route, className }: { route: Route; className?: string }) {
  const { services, types } = useConsole()
  const crumbs = crumbsFor(route, {
    serviceTitle: (id) => findService(services, id)?.title ?? id,
    typeTitle: (service, type) => findType(types, service, type)?.title ?? type,
  })
  if (crumbs.length <= 1) return null
  return (
    <nav aria-label="Breadcrumb" className={cx('mb-4 flex min-w-0 items-center gap-1.5 text-caption text-secondary', className)}>
      <ol className="flex min-w-0 flex-wrap items-center gap-1.5">
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1
          return (
            <li key={`${c.label}-${i}`} className="flex min-w-0 items-center gap-1.5">
              {i > 0 && <Icon name="chevron-right" size={11} className="shrink-0 text-tertiary" aria-hidden="true" />}
              {c.href && !last ? (
                <Link to={c.href} className="truncate rounded-sm transition-colors hover:text-primary">
                  {c.label}
                </Link>
              ) : (
                <span aria-current={last ? 'page' : undefined} className={cx('truncate text-primary', c.mono && 'font-mono')}>
                  {c.label}
                </span>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
