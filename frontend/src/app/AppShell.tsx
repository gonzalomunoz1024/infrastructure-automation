import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType } from 'react'
import {
  AppHeader,
  CommandPalette,
  ErrorBoundary,
  Icon,
  Sidebar,
  StatusDot,
  ThemeToggle,
  ToastProvider,
  Tooltip,
  UserMenu,
  useCommandK,
  useMediaQuery,
  useTheme,
  useToast,
  cx,
  type PaletteItem,
} from '@iaf/ui'
import { Link, navigate, pathFor, useRoute, type PageProps, type Route, type RouteName } from './router'
import { NAV_GROUPS, PAGE_ITEMS, PARENT_ROUTE, navIdFor } from './nav'
import { BrandLockup } from './BrandMark'
import { api, isApiError, useCurrentUser } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { JobSummary } from '../lib/types'

import WorkflowsPage from '../pages/WorkflowsPage'
import WorkflowDesignerPage from '../pages/WorkflowDesignerPage'
import WorkflowVersionsPage from '../pages/WorkflowVersionsPage'
import CatalogPage from '../pages/CatalogPage'
import CatalogRunPage from '../pages/CatalogRunPage'
import JobsPage from '../pages/JobsPage'
import JobPage from '../pages/JobPage'
import ApprovalsPage from '../pages/ApprovalsPage'
import ProvidersPage from '../pages/ProvidersPage'
import ProviderPage from '../pages/ProviderPage'
import ProviderWizardPage from '../pages/ProviderWizardPage'
import ResourcesPage from '../pages/ResourcesPage'
import ResourcePage from '../pages/ResourcePage'
import ReleasesPage from '../pages/ReleasesPage'
import ReleasePage from '../pages/ReleasePage'
import AuditPage from '../pages/AuditPage'
import AuditRecordPage from '../pages/AuditRecordPage'
import AssistantPage from '../pages/AssistantPage'
import CredentialsPage from '../pages/CredentialsPage'
import SettingsPage from '../pages/SettingsPage'
import ChangeRequestPage from '../pages/ChangeRequestPage'
import TemplatesPage from '../pages/TemplatesPage'
import NotFoundPage from '../pages/NotFoundPage'

const PAGES: Record<RouteName, ComponentType<PageProps>> = {
  home: WorkflowsPage,
  workflows: WorkflowsPage,
  workflow: WorkflowDesignerPage,
  'workflow-versions': WorkflowVersionsPage,
  catalog: CatalogPage,
  'catalog-run': CatalogRunPage,
  jobs: JobsPage,
  job: JobPage,
  approvals: ApprovalsPage,
  providers: ProvidersPage,
  'provider-new': ProviderWizardPage,
  provider: ProviderPage,
  resources: ResourcesPage,
  resource: ResourcePage,
  releases: ReleasesPage,
  release: ReleasePage,
  audit: AuditPage,
  'audit-record': AuditRecordPage,
  assistant: AssistantPage,
  credentials: CredentialsPage,
  settings: SettingsPage,
  'change-request': ChangeRequestPage,
  templates: TemplatesPage,
  'not-found': NotFoundPage,
}

const APP_TITLE = 'Infrastructure Automation'

/** The application shell: sidebar, glass header, content, toasts, palette. */
export function AppShell() {
  return (
    <ToastProvider>
      <Shell />
    </ToastProvider>
  )
}

/** jobs for the palette, fetched at most every 30s */
let jobsCache: { at: number; jobs: JobSummary[] } | null = null
async function recentJobs(): Promise<JobSummary[]> {
  if (jobsCache && Date.now() - jobsCache.at < 30_000) return jobsCache.jobs
  const jobs = await api.jobs({ limit: 50 })
  jobsCache = { at: Date.now(), jobs }
  return jobs
}

function Shell() {
  const route = useRoute()
  const { mode, resolved, setMode, cycle } = useTheme()
  const toast = useToast()
  const user = useCurrentUser()
  const narrow = useMediaQuery('(max-width: 1023px)')
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  const [palette, setPalette] = useState(false)
  const openPalette = useCallback(() => setPalette(true), [])
  useCommandK(openPalette)

  // '/' is the workflows list
  useEffect(() => {
    if (route.name === 'home') navigate('/workflows', { replace: true })
  }, [route.name])

  useEffect(() => {
    document.title = route.def && route.def.name !== 'home' ? `${route.def.title} · ${APP_TITLE}` : APP_TITLE
  }, [route.def])

  // the shell works without a backend: say so once, keep rendering
  const warned = useRef(false)
  useEffect(() => {
    if (me.data) warned.current = false
    if (!me.error || warned.current) return
    warned.current = true
    if (isApiError(me.error) && me.error.unreachable) {
      toast.error('Backend unreachable', 'Start the backend on :8080 — the shell keeps working meanwhile.')
    } else {
      toast.error('Could not load your identity', isApiError(me.error) ? `${me.error.error} (${me.error.status})` : String(me.error))
    }
  }, [me.data, me.error, toast])

  const paletteItems = useMemo<PaletteItem[]>(
    () => [
      ...PAGE_ITEMS.map((p) => ({
        id: `page-${p.id}`,
        group: 'Pages',
        label: p.label,
        icon: p.icon,
        hint: 'page',
        run: () => navigate(p.href),
      })),
      { id: 'action-new-provider', group: 'Actions', label: 'Add a provider', icon: 'plus', keywords: 'new provider wizard', run: () => navigate('/providers/new') },
      { id: 'action-theme', group: 'Actions', label: resolved === 'dark' ? 'Switch to light mode' : 'Switch to dark mode', icon: resolved === 'dark' ? 'sun' : 'moon', keywords: 'theme appearance', run: cycle },
      { id: 'action-settings', group: 'Actions', label: 'Open settings', icon: 'settings', keywords: 'preferences user', run: () => navigate('/settings') },
    ],
    [resolved, cycle],
  )

  const search = useCallback(async (q: string): Promise<PaletteItem[]> => {
    const needle = q.trim().toLowerCase()
    const [workflows, jobs] = await Promise.all([
      api.registrySearchWorkflows(q).catch(() => [] as Awaited<ReturnType<typeof api.registrySearchWorkflows>>),
      recentJobs().catch(() => [] as JobSummary[]),
    ])
    const wfItems: PaletteItem[] = workflows.map((w) => ({
      id: `wf-${w.name}`,
      group: 'Workflows',
      label: w.title,
      hint: w.name,
      icon: 'workflow',
      keywords: w.name,
      run: () => navigate(pathFor('workflow', { name: w.name })),
    }))
    let matched = jobs.filter((j) => j.id.toLowerCase().includes(needle) || j.title.toLowerCase().includes(needle)).slice(0, 6)
    if (matched.length === 0 && /^job_/i.test(needle)) {
      const direct = await api.job(q.trim()).catch(() => null)
      if (direct) matched = [direct]
    }
    const jobItems: PaletteItem[] = matched.map((j) => ({
      id: `job-${j.id}`,
      group: 'Jobs',
      label: j.id,
      hint: `${j.title} · ${j.status}`,
      icon: 'job',
      run: () => navigate(pathFor('job', { id: j.id })),
    }))
    return [...wfItems, ...jobItems]
  }, [])

  const Page = PAGES[route.name]
  const fullBleed = route.def?.fullBleed === true
  const status = me.loading ? 'loading' : me.error ? 'offline' : 'online'

  return (
    <div className="flex min-h-screen bg-surface">
      <Sidebar
        brand={<BrandLockup collapsed={narrow} />}
        groups={NAV_GROUPS}
        activeId={navIdFor(route.name)}
        footer={
          <UserMenu
            user={me.data ? { id: me.data.id, name: me.data.name, email: me.data.email, roles: me.data.roles, teams: me.data.teams, admin: me.data.admin } : null}
            status={status}
            themeMode={mode}
            onThemeChange={setMode}
            collapsed={narrow}
            items={[
              { id: 'settings', label: 'Settings', icon: 'settings', onSelect: () => navigate('/settings') },
              { id: 'switch-user', label: 'Switch user…', icon: 'users', onSelect: () => navigate('/settings?section=identity') },
            ]}
          />
        }
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <AppHeader
          left={<Breadcrumb route={route} />}
          onSearch={openPalette}
          right={
            <>
              {me.error && (
                <Tooltip label="Backend unreachable" side="bottom">
                  <span className="flex h-9 w-9 items-center justify-center" aria-label="Backend unreachable">
                    <StatusDot kind="critical" pulse />
                  </span>
                </Tooltip>
              )}
              <Tooltip label="API reference" side="bottom">
                <a
                  href="/api/v1/openapi.json"
                  target="_blank"
                  rel="noreferrer"
                  aria-label="API reference"
                  className="flex h-9 w-9 items-center justify-center rounded-full text-tertiary transition-all duration-150 hover:bg-surface-secondary hover:text-primary active:scale-[0.94]"
                >
                  <Icon name="terminal" size={17} />
                </a>
              </Tooltip>
              <ThemeToggle mode={mode} onCycle={cycle} />
            </>
          }
        />

        <main className={fullBleed ? 'relative h-[calc(100vh-3.5rem)] min-h-0 flex-1 overflow-hidden' : 'flex-1 px-6 py-6 lg:px-8'}>
          <ErrorBoundary resetKey={route.path} label="This page">
            {fullBleed ? (
              <Page key={route.path} route={route} />
            ) : (
              <div key={route.path} className="mx-auto w-full max-w-7xl animate-fade-in">
                <Page route={route} />
              </div>
            )}
          </ErrorBoundary>
        </main>
      </div>

      {palette && (
        <CommandPalette
          onClose={() => setPalette(false)}
          items={paletteItems}
          groups={['Pages', 'Actions', 'Workflows', 'Jobs']}
          search={search}
          placeholder="Search pages, workflows, jobs…"
        />
      )}
    </div>
  )
}

/** Header left slot: the page name, or "Parent / identifier" on detail pages. */
function Breadcrumb({ route }: { route: Route }) {
  const parentName = PARENT_ROUTE[route.name]
  const parent = parentName ? pathFor(parentName) : null
  const parentTitle = parentName ? (NAV_GROUPS.flatMap((g) => g.items).find((i) => i.id === navIdFor(parentName))?.label ?? parentName) : null
  const leaf = Object.values(route.params)[0]
  if (parent && parentTitle) {
    return (
      <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-2 text-sm">
        <Link to={parent} className="shrink-0 text-secondary transition-colors hover:text-primary">
          {parentTitle}
        </Link>
        <span className="text-tertiary">/</span>
        <span className={cx('truncate text-sm text-primary', leaf ? 'font-mono' : 'font-medium')}>{leaf ?? route.def?.title}</span>
      </nav>
    )
  }
  return <span className="truncate text-sm font-medium text-primary">{route.def?.title ?? 'Not found'}</span>
}
