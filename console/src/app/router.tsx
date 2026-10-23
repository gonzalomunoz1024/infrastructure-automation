import { useMemo, useSyncExternalStore, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from 'react'

/*
 * A small hash router, in the style of the platform's frontend/src/app/router.tsx. The URL is
 * `#/path?query`; the table below maps patterns with `:params` to route names. A path may be
 * scoped to an account first (`#/account/123456789012/console/compute/vm`): the prefix is read
 * off and the rest matched as usual. A wfrn in a path is one percent-encoded segment. No library.
 */

export type RouteName =
  | 'home'
  | 'signin'
  | 'service'
  | 'type'
  | 'create'
  | 'resource'
  | 'requests'
  | 'request'
  | 'iam'
  | 'iam-users'
  | 'iam-user'
  | 'iam-groups'
  | 'iam-group'
  | 'iam-roles'
  | 'iam-role'
  | 'iam-keys'
  | 'iam-requests'
  | 'iam-request'
  | 'groups'
  | 'group'
  | 'tag-editor'
  | 'tag-policy'
  | 'drift'
  | 'unmanaged'
  | 'alarms'
  | 'rightsizing'
  | 'activity'
  | 'as-of'
  | 'map'
  | 'settings'
  | 'not-found'

export interface RouteDef {
  name: RouteName
  pattern: string
  /** document title and breadcrumb label */
  title: string
  /** the page stands alone, outside the shell (the sign-in page) */
  bare?: boolean
}

export const ROUTES: RouteDef[] = [
  { name: 'home', pattern: '/', title: 'Console home' },
  { name: 'signin', pattern: '/signin', title: 'Sign in', bare: true },
  { name: 'service', pattern: '/console/:service', title: 'Service' },
  { name: 'type', pattern: '/console/:service/:type', title: 'Resources' },
  // a static last segment outranks the wfrn of `resource` (resolve picks the most specific pattern)
  { name: 'create', pattern: '/console/:service/:type/create', title: 'Create' },
  { name: 'resource', pattern: '/console/:service/:type/:wfrn', title: 'Resource' },
  { name: 'requests', pattern: '/requests', title: 'Requests' },
  { name: 'request', pattern: '/requests/:id', title: 'Request' },
  { name: 'iam', pattern: '/iam', title: 'Identity and access' },
  { name: 'iam-users', pattern: '/iam/users', title: 'Users' },
  { name: 'iam-user', pattern: '/iam/users/:id', title: 'User' },
  { name: 'iam-groups', pattern: '/iam/groups', title: 'Groups' },
  { name: 'iam-group', pattern: '/iam/groups/:id', title: 'Group' },
  { name: 'iam-roles', pattern: '/iam/roles', title: 'Roles' },
  { name: 'iam-role', pattern: '/iam/roles/:id', title: 'Role' },
  { name: 'iam-keys', pattern: '/iam/access-keys', title: 'Access keys' },
  { name: 'iam-requests', pattern: '/iam/access-requests', title: 'Access requests' },
  { name: 'iam-request', pattern: '/iam/access-requests/:id', title: 'Access request' },
  // resource groups and tags (phase 4): across every type of the account and region
  { name: 'groups', pattern: '/resource-groups', title: 'Resource groups' },
  { name: 'group', pattern: '/resource-groups/:id', title: 'Resource group' },
  { name: 'tag-editor', pattern: '/tag-editor', title: 'Tag Editor' },
  { name: 'tag-policy', pattern: '/tag-policy', title: 'Required tags' },
  // operations (phase 5): drift, what the providers have that nobody manages, alarms and rightsizing
  { name: 'drift', pattern: '/operations/drift', title: 'Drift' },
  { name: 'unmanaged', pattern: '/operations/unmanaged', title: 'Unmanaged resources' },
  { name: 'alarms', pattern: '/operations/alarms', title: 'Alarms' },
  { name: 'rightsizing', pattern: '/operations/rightsizing', title: 'Rightsizing' },
  // the trail, the account as it was and the resource map (phase 7)
  { name: 'activity', pattern: '/history/activity', title: 'Activity trail' },
  { name: 'as-of', pattern: '/history/as-of', title: 'Inventory as of a date' },
  { name: 'map', pattern: '/history/map', title: 'Resource map' },
  { name: 'settings', pattern: '/settings', title: 'Settings' },
]

/** The identity and access section: the overview and every page under it. */
export function isIamRoute(name: RouteName): boolean {
  return name === 'iam' || name.startsWith('iam-')
}

/** The resource groups and tags section: the pages that work across every type. */
export function isTagsRoute(name: RouteName): boolean {
  return name === 'groups' || name === 'group' || name === 'tag-editor' || name === 'tag-policy'
}

export interface Route {
  name: RouteName
  /** the path part of the hash without the account prefix, e.g. '/console/compute/vm' */
  path: string
  params: Record<string, string>
  query: Record<string, string>
  def: RouteDef | null
  /** the account the path was scoped to, when it was */
  accountId: string | null
}

/** Every page receives the matched route. */
export interface PageProps {
  route: Route
}

/** '#/console/compute?tab=all' → { path: '/console/compute', query: { tab: 'all' } } */
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

const ACCOUNT_PREFIX = /^\/account\/(\d{12})(?=\/|$)/

/** '/account/123456789012/console/compute' → { accountId: '123456789012', path: '/console/compute' } */
export function splitAccount(path: string): { accountId: string | null; path: string } {
  const m = ACCOUNT_PREFIX.exec(path)
  if (!m) return { accountId: null, path }
  const rest = path.slice(m[0].length)
  return { accountId: m[1], path: rest || '/' }
}

/** Params when `path` matches `pattern`, else null. Each segment is decoded, so a wfrn travels as one segment. */
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

/** Static segments beat params, whatever the table order. */
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
  const parsed = parseHash(hash)
  const { accountId, path } = splitAccount(parsed.path)
  const { def, params } = resolve(path)
  return { name: def?.name ?? 'not-found', path, params, query: parsed.query, def, accountId }
}

/** '#/requests' from '/requests' (or from 'requests', or an already-hashed value). */
export function href(to: string): string {
  if (to.startsWith('#')) return to
  return `#${to.startsWith('/') ? to : `/${to}`}`
}

export type QueryValue = string | number | boolean | undefined | null

/** Builds a path from a route pattern: pathFor('type', { service, type }) → '/console/<service>/<type>'. */
export function pathFor(name: RouteName, params: Record<string, string> = {}, query?: Record<string, QueryValue>, opts: { accountId?: string | null } = {}): string {
  const def = ROUTES.find((r) => r.name === name)
  if (!def) return '/'
  let path = def.pattern.replace(/:([A-Za-z0-9_]+)/g, (_, key: string) => encodeURIComponent(params[key] ?? ''))
  if (opts.accountId) path = `/account/${opts.accountId}${path === '/' ? '' : path}`
  if (!query) return path
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== '') sp.set(k, String(v))
  const s = sp.toString()
  return s ? `${path}?${s}` : path
}

/** The page of a resource: its type's list, then its wfrn as one segment. */
export function resourcePath(service: string, type: string, wfrn: string): string {
  return pathFor('resource', { service, type, wfrn })
}

/**
 * Where creating a resource of a type starts; the palette's Create items and the list's Create
 * button go here. The type's list with `action=create` until the create wizard has its own route.
 */
export function createPath(service: string, type: string): string {
  return pathFor('type', { service, type }, { action: 'create' })
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
