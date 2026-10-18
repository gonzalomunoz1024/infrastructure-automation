import { useMemo, useSyncExternalStore, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from 'react'

/*
 * A small hash router. The URL is `#/path?query`; the table below maps
 * patterns with `:params` to route names. No library.
 */

export type RouteName =
  | 'home'
  | 'workflows'
  | 'workflow-new'
  | 'workflow'
  | 'workflow-versions'
  | 'catalog'
  | 'catalog-run'
  | 'jobs'
  | 'job'
  | 'approvals'
  | 'providers'
  | 'provider-new'
  | 'provider'
  | 'resources'
  | 'resource'
  | 'releases'
  | 'release'
  | 'audit'
  | 'audit-record'
  | 'assistant'
  | 'credentials'
  | 'settings'
  | 'change-request'
  | 'templates'
  | 'not-found'

export interface RouteDef {
  name: RouteName
  pattern: string
  /** document title and breadcrumb label */
  title: string
  /** the page owns the whole content area: no padding, no max width (the designer) */
  fullBleed?: boolean
}

export const ROUTES: RouteDef[] = [
  { name: 'home', pattern: '/', title: 'Home' },
  { name: 'workflows', pattern: '/workflows', title: 'Workflows' },
  { name: 'workflow-new', pattern: '/workflows/new', title: 'New workflow', fullBleed: true },
  { name: 'workflow', pattern: '/workflows/:name', title: 'Designer', fullBleed: true },
  { name: 'workflow-versions', pattern: '/workflows/:name/versions', title: 'Versions' },
  { name: 'catalog', pattern: '/catalog', title: 'Catalog' },
  { name: 'catalog-run', pattern: '/catalog/:workflow', title: 'Run' },
  { name: 'jobs', pattern: '/jobs', title: 'Jobs' },
  { name: 'job', pattern: '/jobs/:id', title: 'Job' },
  { name: 'approvals', pattern: '/approvals', title: 'Approvals' },
  { name: 'providers', pattern: '/providers', title: 'Providers' },
  { name: 'provider-new', pattern: '/providers/new', title: 'Add provider' },
  { name: 'provider', pattern: '/providers/:name', title: 'Provider' },
  { name: 'resources', pattern: '/resources', title: 'Resources' },
  { name: 'resource', pattern: '/resources/:id', title: 'Resource' },
  { name: 'releases', pattern: '/releases', title: 'Releases' },
  { name: 'release', pattern: '/releases/:id', title: 'Release' },
  { name: 'audit', pattern: '/audit', title: 'Audit' },
  { name: 'audit-record', pattern: '/audit/:jobId', title: 'Evidence' },
  { name: 'assistant', pattern: '/assistant', title: 'Assistant' },
  { name: 'credentials', pattern: '/credentials', title: 'Credentials' },
  { name: 'settings', pattern: '/settings', title: 'Settings' },
  { name: 'change-request', pattern: '/change-requests/:id', title: 'Change request' },
  { name: 'templates', pattern: '/templates', title: 'Templates' },
]

export interface Route {
  name: RouteName
  /** the path part of the hash, e.g. '/jobs/job_1' */
  path: string
  params: Record<string, string>
  query: Record<string, string>
  def: RouteDef | null
}

/** Every page receives the matched route. */
export interface PageProps {
  route: Route
}

/** '#/jobs/1?tab=steps' → { path: '/jobs/1', query: { tab: 'steps' } } */
export function parseHash(hash: string): { path: string; query: Record<string, string> } {
  let h = hash.startsWith('#') ? hash.slice(1) : hash
  let search = ''
  const qi = h.indexOf('?')
  if (qi >= 0) {
    search = h.slice(qi + 1)
    h = h.slice(0, qi)
  }
  let path = h.startsWith('/') ? h : `/${h}`
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1)
  const query: Record<string, string> = {}
  new URLSearchParams(search).forEach((v, k) => {
    query[k] = v
  })
  return { path, query }
}

/** Params when `path` matches `pattern`, else null. */
export function matchRoute(pattern: string, path: string): Record<string, string> | null {
  const p = pattern.split('/').filter(Boolean)
  const s = path.split('/').filter(Boolean)
  if (p.length !== s.length) return null
  const params: Record<string, string> = {}
  for (let i = 0; i < p.length; i++) {
    const seg = p[i]
    if (seg.startsWith(':')) {
      try {
        params[seg.slice(1)] = decodeURIComponent(s[i])
      } catch {
        params[seg.slice(1)] = s[i]
      }
    } else if (seg !== s[i]) {
      return null
    }
  }
  return params
}

/** Static segments beat params, so '/providers/new' wins over '/providers/:name' whatever the table order. */
function specificity(pattern: string): number {
  return pattern
    .split('/')
    .filter(Boolean)
    .reduce((n, seg) => n + (seg.startsWith(':') ? 1 : 2), 0)
}

export function resolve(path: string): { def: RouteDef | null; params: Record<string, string> } {
  let best: { def: RouteDef; params: Record<string, string>; score: number } | null = null
  for (const def of ROUTES) {
    const params = matchRoute(def.pattern, path)
    if (!params) continue
    const score = specificity(def.pattern)
    if (!best || score > best.score) best = { def, params, score }
  }
  return best ? { def: best.def, params: best.params } : { def: null, params: {} }
}

export function routeFor(hash: string): Route {
  const { path, query } = parseHash(hash)
  const { def, params } = resolve(path)
  return { name: def?.name ?? 'not-found', path, params, query, def }
}

/** '#/jobs' from '/jobs' (or from 'jobs', or an already-hashed value). */
export function href(to: string): string {
  if (to.startsWith('#')) return to
  return `#${to.startsWith('/') ? to : `/${to}`}`
}

/** Builds a path from a route pattern: pathFor('job', { id }) → '/jobs/<id>'. */
export function pathFor(name: RouteName, params: Record<string, string> = {}, query?: Record<string, string | number | boolean | undefined | null>): string {
  const def = ROUTES.find((r) => r.name === name)
  if (!def) return '/'
  const path = def.pattern.replace(/:([A-Za-z0-9_]+)/g, (_, key: string) => encodeURIComponent(params[key] ?? ''))
  if (!query) return path
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== '') sp.set(k, String(v))
  const s = sp.toString()
  return s ? `${path}?${s}` : path
}

export function currentHash(): string {
  return typeof window === 'undefined' ? '' : window.location.hash
}

/** Go to a path; `replace` rewrites the current history entry instead of pushing one. */
export function navigate(to: string, opts: { replace?: boolean } = {}) {
  if (typeof window === 'undefined') return
  const target = href(to)
  if (opts.replace) {
    const url = `${window.location.pathname}${window.location.search}${target}`
    window.history.replaceState(null, '', url)
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    return
  }
  if (window.location.hash === target) return
  window.location.hash = target
}

function subscribeHash(onChange: () => void) {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

const emptyHash = () => ''

/** The matched route; re-renders on every hash change. */
export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribeHash, currentHash, emptyHash)
  return useMemo(() => routeFor(hash), [hash])
}

export interface LinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  to: string
  replace?: boolean
  children?: ReactNode
}

/** An anchor to a route. Plain clicks use the browser's own hash navigation; modifier clicks open tabs. */
export function Link({ to, replace, onClick, children, ...rest }: LinkProps) {
  return (
    <a
      href={href(to)}
      onClick={(e: MouseEvent<HTMLAnchorElement>) => {
        onClick?.(e)
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
        if (replace) {
          e.preventDefault()
          navigate(to, { replace: true })
        }
      }}
      {...rest}
    >
      {children}
    </a>
  )
}
