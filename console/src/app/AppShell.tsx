import { useCallback, useEffect, useState, type ComponentType } from 'react'
import { ErrorBoundary, useCommandK, useToast } from '@iaf/ui'
import { isApiError } from '../lib/api'
import { findService } from '../lib/descriptors'
import { usePendingAccessRequests } from './access'
import { useConsole } from './console'
import { Header } from './Header'
import { RefusalProvider } from './Refusal'
import { SectionDrawer, SectionSidebar, iamNav, serviceNav } from './ServiceSidebar'
import { Breadcrumbs } from './Breadcrumbs'
import { Footer } from './Footer'
import { PlatformFeed } from './PlatformFeed'
import { Search } from './Search'
import { isIamRoute, type PageProps, type Route, type RouteName } from './router'

import HomePage from '../pages/HomePage'
import ServicePage from '../pages/ServicePage'
import TypeListPage from '../pages/TypeListPage'
import ResourceDetailPage from '../pages/ResourceDetailPage'
import RequestsPage from '../pages/RequestsPage'
import RequestPage from '../pages/RequestPage'
import IamPage from '../pages/IamPage'
import IamUsersPage, { IamUserPage } from '../pages/iam/UsersPage'
import IamGroupsPage, { IamGroupPage } from '../pages/iam/GroupsPage'
import IamRolesPage, { IamRolePage } from '../pages/iam/RolesPage'
import IamAccessKeysPage from '../pages/iam/AccessKeysPage'
import IamAccessRequestsPage, { IamAccessRequestPage } from '../pages/iam/AccessRequestsPage'
import SettingsPage from '../pages/SettingsPage'
import SignInPage from '../pages/SignInPage'
import NotFoundPage from '../pages/NotFoundPage'

/*
 * The frame around every page: the header, a sidebar when the route is inside a service or
 * identity and access, the content with its breadcrumbs, the status bar, the search palette,
 * the platform feed and the refusal view.
 */

const PAGES: Record<RouteName, ComponentType<PageProps>> = {
  home: HomePage,
  signin: SignInPage,
  service: ServicePage,
  type: TypeListPage,
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
  settings: SettingsPage,
  'not-found': NotFoundPage,
}

export function AppShell({ route }: { route: Route }) {
  const { services, offline, error, account, admin } = useConsole()
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
  const pending = usePendingAccessRequests(account.id, admin && inIam)
  const nav = service ? serviceNav(service, route) : inIam ? iamNav(route, account.name, pending.data?.items.length) : null

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
        <Header onOpenSearch={openPalette} onOpenSidebar={nav ? () => setDrawerFor(route.path) : undefined} />
        <div className="flex min-h-0 flex-1">
          {nav && <SectionSidebar nav={nav} />}
          <main id="main" className="console-content min-w-0 flex-1 overflow-y-auto px-6 py-6 lg:px-8">
            <div key={route.path} className="mx-auto w-full max-w-7xl animate-fade-in">
              <Breadcrumbs route={route} />
              <ErrorBoundary resetKey={route.path} label="This page">
                <Page route={route} />
              </ErrorBoundary>
            </div>
          </main>
        </div>
        <Footer onOpenFeed={() => setFeed((f) => !f)} feedOpen={feed} />
        {palette && <Search onClose={() => setPalette(false)} />}
        {feed && <PlatformFeed onClose={() => setFeed(false)} />}
        {drawer && nav && <SectionDrawer nav={nav} onClose={() => setDrawerFor(null)} />}
      </div>
    </RefusalProvider>
  )
}
