import { Suspense, lazy, useCallback, useEffect, useState, type ComponentType } from 'react'
import { ErrorBoundary, SkeletonRows, SkeletonText, useCommandK, useToast } from '@iaf/ui'
import { isApiError } from '../lib/api'
import { findService } from '../lib/descriptors'
import { useAccessRequestQueue } from './access'
import { useConsole } from './console'
import { Header } from './Header'
import { RefusalProvider } from './Refusal'
import { SectionDrawer, SectionSidebar, iamNav, serviceNav, toolsNav } from './ServiceSidebar'
import { Breadcrumbs } from './Breadcrumbs'
import { Footer } from './Footer'
import { PlatformFeed } from './PlatformFeed'
import { Search } from './Search'
import { isIamRoute, type PageProps, type Route, type RouteName } from './router'
import { toolSectionOf } from './tools'
import { HeldRolesBanner } from './elevation'

import SignInPage from '../pages/SignInPage'

/*
 * Every page but the sign-in is its own chunk (route-level code splitting): the first paint loads
 * the shell and the page it lands on, and each other page arrives the first time it is opened.
 */
const HomePage = lazy(() => import('../pages/HomePage'))
const ServicePage = lazy(() => import('../pages/ServicePage'))
const TypeListPage = lazy(() => import('../pages/TypeListPage'))
const ResourceDetailPage = lazy(() => import('../pages/ResourceDetailPage'))
const CreatePage = lazy(() => import('../pages/CreatePage'))
const RequestsPage = lazy(() => import('../pages/RequestsPage'))
const RequestPage = lazy(() => import('../pages/RequestPage'))
const IamPage = lazy(() => import('../pages/IamPage'))
const IamUsersPage = lazy(() => import('../pages/iam/UsersPage'))
const IamUserPage = lazy(() => import('../pages/iam/UsersPage').then((m) => ({ default: m.IamUserPage })))
const IamGroupsPage = lazy(() => import('../pages/iam/GroupsPage'))
const IamGroupPage = lazy(() => import('../pages/iam/GroupsPage').then((m) => ({ default: m.IamGroupPage })))
const IamRolesPage = lazy(() => import('../pages/iam/RolesPage'))
const IamRolePage = lazy(() => import('../pages/iam/RolesPage').then((m) => ({ default: m.IamRolePage })))
const IamAccessKeysPage = lazy(() => import('../pages/iam/AccessKeysPage'))
const IamAccessRequestsPage = lazy(() => import('../pages/iam/AccessRequestsPage'))
const IamPoliciesPage = lazy(() => import('../pages/iam/policies/PoliciesPage'))
const IamPolicyPage = lazy(() => import('../pages/iam/policies/PoliciesPage').then((m) => ({ default: m.IamPolicyPage })))
const IamPolicyEditPage = lazy(() => import('../pages/iam/policies/PoliciesPage').then((m) => ({ default: m.IamPolicyEditPage })))
const IamSimulatorPage = lazy(() => import('../pages/iam/policies/SimulatorPage'))
const IamGuardrailsPage = lazy(() => import('../pages/iam/policies/GuardrailsPage'))
const IamGuardrailPage = lazy(() => import('../pages/iam/policies/GuardrailsPage').then((m) => ({ default: m.IamGuardrailPage })))
const IamAccessRequestPage = lazy(() => import('../pages/iam/AccessRequestsPage').then((m) => ({ default: m.IamAccessRequestPage })))
const IamAccessReviewsPage = lazy(() => import('../pages/iam/AccessReviewsPage'))
const IamAccessReviewPage = lazy(() => import('../pages/iam/AccessReviewsPage').then((m) => ({ default: m.IamAccessReviewPage })))
const IamElevationsPage = lazy(() => import('../pages/iam/ElevationsPage'))
const OrganizationAccountsPage = lazy(() => import('../pages/organization/AccountsPage'))
const ResourceGroupsPage = lazy(() => import('../pages/groups/ResourceGroupsPage'))
const ResourceGroupPage = lazy(() => import('../pages/groups/ResourceGroupsPage').then((m) => ({ default: m.ResourceGroupPage })))
const TagEditorPage = lazy(() => import('../pages/tags/TagEditorPage'))
const TagPolicyPage = lazy(() => import('../pages/tags/TagPolicyPage'))
const DriftPage = lazy(() => import('../pages/operations/DriftPage'))
const UnmanagedPage = lazy(() => import('../pages/operations/UnmanagedPage'))
const AlarmsPage = lazy(() => import('../pages/operations/AlarmsPage'))
const RightsizingPage = lazy(() => import('../pages/operations/RightsizingPage'))
const SettingsPage = lazy(() => import('../pages/SettingsPage'))
const DevicePage = lazy(() => import('../pages/developer/DevicePage'))
const CliSessionsPage = lazy(() => import('../pages/iam/CliSessionsPage'))
const ShellPage = lazy(() => import('../pages/developer/ShellPage'))
const PortalPage = lazy(() => import('../pages/developer/PortalPage'))
const NotificationsPage = lazy(() => import('../pages/notify/NotificationsPage'))
const NotificationSettingsPage = lazy(() => import('../pages/notify/NotificationSettingsPage'))
const ChatLinkPage = lazy(() => import('../pages/notify/ChatLinkPage'))
const DigestsPage = lazy(() => import('../pages/notify/DigestsPage'))
const DigestPage = lazy(() => import('../pages/notify/DigestsPage').then((m) => ({ default: m.DigestPage })))
const NotFoundPage = lazy(() => import('../pages/NotFoundPage'))

/*
 * The frame around every page: a skip link, the header, a sidebar when the route is inside a
 * service or identity and access, the content with its breadcrumbs, the status bar, the search
 * palette, the platform feed and the refusal view.
 */

const PAGES: Record<RouteName, ComponentType<PageProps>> = {
  home: HomePage,
  signin: SignInPage,
  service: ServicePage,
  type: TypeListPage,
  create: CreatePage,
  resource: ResourceDetailPage,
  requests: RequestsPage,
  request: RequestPage,
  iam: IamPage,
  'iam-users': IamUsersPage,
  'iam-user': IamUserPage,
  'iam-groups': IamGroupsPage,
  'iam-group': IamGroupPage,
  'iam-roles': IamRolesPage,
  'iam-role': IamRolePage,
  'iam-keys': IamAccessKeysPage,
  'iam-requests': IamAccessRequestsPage,
  'iam-request': IamAccessRequestPage,
  'iam-reviews': IamAccessReviewsPage,
  'iam-review': IamAccessReviewPage,
  'iam-elevations': IamElevationsPage,
  'org-accounts': OrganizationAccountsPage,
  'iam-policies': IamPoliciesPage,
  'iam-policy-new': IamPolicyEditPage,
  'iam-policy': IamPolicyPage,
  'iam-policy-edit': IamPolicyEditPage,
  'iam-simulator': IamSimulatorPage,
  'iam-guardrails': IamGuardrailsPage,
  'iam-guardrail-new': IamGuardrailPage,
  'iam-guardrail': IamGuardrailPage,
  groups: ResourceGroupsPage,
  group: ResourceGroupPage,
  'tag-editor': TagEditorPage,
  'tag-policy': TagPolicyPage,
  drift: DriftPage,
  unmanaged: UnmanagedPage,
  alarms: AlarmsPage,
  rightsizing: RightsizingPage,
  settings: SettingsPage,
  device: DevicePage,
  'iam-cli': CliSessionsPage,
  shell: ShellPage,
  portal: PortalPage,
  notifications: NotificationsPage,
  'notification-settings': NotificationSettingsPage,
  'chat-link': ChatLinkPage,
  digests: DigestsPage,
  digest: DigestPage,
  'not-found': NotFoundPage,
}

export function AppShell({ route }: { route: Route }) {
  const { services, offline, error, account } = useConsole()
  const toast = useToast()
  const [palette, setPalette] = useState(false)
  const [feed, setFeed] = useState(false)
  // the drawer remembers the path it opened on, so a navigation closes it without an effect
  const [drawerFor, setDrawerFor] = useState<string | null>(null)
  const drawer = drawerFor === route.path
  const openPalette = useCallback(() => setPalette(true), [])
  useCommandK(openPalette)

  const service = route.params.service ? findService(services, route.params.service) : undefined
  const inIam = isIamRoute(route.name)
  const queue = useAccessRequestQueue({ enabled: inIam })
  const tools = toolSectionOf(route)
  const nav = service ? serviceNav(service, route) : inIam ? iamNav(route, account.name, queue.toDecide.length) : tools ? toolsNav(tools, route) : null

  // the shell works without a platform: say so once, keep drawing
  useEffect(() => {
    if (!offline) return
    toast.error('Platform unreachable', 'The console keeps what it cached. Start the BFF on :18081 (it embeds the platform-fake), or run with VITE_CONSOLE_MOCK=1.')
  }, [offline, toast])
  useEffect(() => {
    if (!error || offline) return
    toast.error('Could not load the service list', isApiError(error) ? `${error.error} (${error.status})` : String(error))
  }, [error, offline, toast])

  const Page = PAGES[route.name]

  return (
    <RefusalProvider>
      <div className="flex h-dvh flex-col bg-surface">
        <SkipLink />
        <Header onOpenSearch={openPalette} onOpenSidebar={nav ? () => setDrawerFor(route.path) : undefined} />
        <div className="flex min-h-0 flex-1">
          {nav && <SectionSidebar nav={nav} />}
          <main id="main" tabIndex={-1} className="console-content min-w-0 flex-1 overflow-y-auto px-6 py-6 outline-none lg:px-8">
            <div key={route.path} className="mx-auto w-full max-w-7xl animate-fade-in">
              <Breadcrumbs route={route} />
              <HeldRolesBanner />
              <ErrorBoundary resetKey={route.path} label="This page">
                <Suspense fallback={<PageLoading />}>
                  <Page route={route} />
                </Suspense>
              </ErrorBoundary>
            </div>
          </main>
        </div>
        <Footer onOpenFeed={() => setFeed((f) => !f)} feedOpen={feed} />
        {palette && <Search route={route} onClose={() => setPalette(false)} />}
        {feed && <PlatformFeed onClose={() => setFeed(false)} />}
        {drawer && nav && <SectionDrawer nav={nav} onClose={() => setDrawerFor(null)} />}
      </div>
    </RefusalProvider>
  )
}

/**
 * The first thing Tab reaches: straight to the page's content, past the header and the sidebar.
 * A button, not an `href="#main"`, which the hash router would read as a route.
 */
function SkipLink() {
  return (
    <button
      type="button"
      onClick={() => document.getElementById('main')?.focus()}
      className="sr-only rounded-md bg-surface px-3 py-2 text-sm font-medium text-accent shadow-lg focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[100]"
    >
      Skip to content
    </button>
  )
}

/** While a page's code arrives: the shape of a page, not a spinner. */
function PageLoading() {
  return (
    <div role="status" aria-label="Loading the page" className="space-y-6">
      <SkeletonText lines={2} className="max-w-md" />
      <SkeletonRows n={4} rowClassName="h-12" />
    </div>
  )
}
