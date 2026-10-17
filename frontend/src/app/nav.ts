import type { SidebarGroup, SidebarItem } from '@iaf/ui'
import type { RouteName } from './router'

/** The sidebar: four groups, one item per top-level page. */
export const NAV_GROUPS: SidebarGroup[] = [
  {
    id: 'build',
    label: 'Build',
    items: [
      { id: 'workflows', label: 'Workflows', icon: 'workflow', href: '#/workflows' },
      { id: 'templates', label: 'Templates', icon: 'grid', href: '#/templates' },
      { id: 'assistant', label: 'Assistant', icon: 'sparkles', href: '#/assistant' },
    ],
  },
  {
    id: 'run',
    label: 'Run',
    items: [
      { id: 'catalog', label: 'Catalog', icon: 'list', href: '#/catalog' },
      { id: 'jobs', label: 'Jobs', icon: 'job', href: '#/jobs' },
      { id: 'approvals', label: 'Approvals', icon: 'approval', href: '#/approvals' },
    ],
  },
  {
    id: 'operate',
    label: 'Operate',
    items: [
      { id: 'providers', label: 'Providers', icon: 'provider', href: '#/providers' },
      { id: 'resources', label: 'Resources', icon: 'resource', href: '#/resources' },
      { id: 'releases', label: 'Releases', icon: 'release', href: '#/releases' },
      { id: 'audit', label: 'Audit', icon: 'audit', href: '#/audit' },
    ],
  },
  {
    id: 'admin',
    label: 'Admin',
    items: [
      { id: 'credentials', label: 'Credentials', icon: 'key', href: '#/credentials' },
      { id: 'settings', label: 'Settings', icon: 'settings', href: '#/settings' },
    ],
  },
]

export const PAGE_ITEMS: SidebarItem[] = NAV_GROUPS.flatMap((g) => g.items)

const NAV_BY_ROUTE: Partial<Record<RouteName, string>> = {
  home: 'workflows',
  workflows: 'workflows',
  workflow: 'workflows',
  'workflow-new': 'workflows',
  'workflow-versions': 'workflows',
  'change-request': 'workflows',
  templates: 'templates',
  assistant: 'assistant',
  catalog: 'catalog',
  'catalog-run': 'catalog',
  jobs: 'jobs',
  job: 'jobs',
  approvals: 'approvals',
  providers: 'providers',
  provider: 'providers',
  'provider-new': 'providers',
  resources: 'resources',
  resource: 'resources',
  releases: 'releases',
  release: 'releases',
  audit: 'audit',
  'audit-record': 'audit',
  credentials: 'credentials',
  settings: 'settings',
}

/** Which sidebar item a route lights up. */
export function navIdFor(name: RouteName): string | null {
  return NAV_BY_ROUTE[name] ?? null
}

/** The list page a detail route belongs to (for the header breadcrumb). */
export const PARENT_ROUTE: Partial<Record<RouteName, RouteName>> = {
  workflow: 'workflows',
  'workflow-new': 'workflows',
  'workflow-versions': 'workflows',
  'change-request': 'workflows',
  'catalog-run': 'catalog',
  job: 'jobs',
  provider: 'providers',
  'provider-new': 'providers',
  resource: 'resources',
  release: 'releases',
  'audit-record': 'audit',
}
