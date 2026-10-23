import { get, request } from './api'
import { partsOfWfrn } from './descriptors'
import type { PreferencesChange } from './preferences'
import { pathFor, resourcePath } from '../app/router'

/*
 * What the console remembers for each person in each account, at the BFF (console-bff.yaml, tag
 * Recent and favourites): the types and resources they opened lately (`/console/v1/recent`, the
 * 20 latest, a visit to the same place moving it to the top) and the resources they starred
 * (`/console/v1/favourites`, at most 100). Favourite services stay the preferences'
 * `pinnedTypes`, keyed by type key, so pinning a type is a preferences change.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

export type VisitKind = 'type' | 'resource'

/** One visit: a type by its key (`compute/vm`) or a resource by its wfrn, with the title it had. */
export interface RecentVisit {
  kind: VisitKind
  key: string
  title: string
  visitedAt: string
}

/** A starred resource: its wfrn, its type's name and the name it had. */
export interface Favourite {
  wfrn: string
  type: string
  name: string
  addedAt: string
}

/** The contract's limits: a key of 1,024 characters, a title or name of 256, 100 favourites an account. */
export const VISIT_KEY_MAX = 1024
export const TITLE_MAX = 256
export const FAVOURITES_MAX = 100
export const RECENT_MAX = 20

export const personalApi = {
  recent: () => get<{ items: RecentVisit[] }>(b('/recent')),
  /** the web app posts when a type's list or a resource's page opens */
  recordVisit: (body: { kind: VisitKind; key: string; title: string }) => request<void>('POST', b('/recent'), { ...body, key: body.key.slice(0, VISIT_KEY_MAX), title: body.title.slice(0, TITLE_MAX) }),
  favourites: () => get<{ items: Favourite[] }>(b('/favourites')),
  /** idempotent; 409 `favourites.full` past 100 in the account */
  addFavourite: (wfrn: string, body: { type: string; name: string }) => request<Favourite>('PUT', b(`/favourites/${enc(wfrn)}`), { ...body, name: body.name.slice(0, TITLE_MAX) }),
  removeFavourite: (wfrn: string) => request<void>('DELETE', b(`/favourites/${enc(wfrn)}`)),
}

/** Where a visit leads: a type's list, or a resource's page by the parts of its wfrn. Null for a key the console cannot read. */
export function hrefOfVisit(v: Pick<RecentVisit, 'kind' | 'key'>): string | null {
  if (v.kind === 'type') {
    const [service, type] = v.key.split('/')
    return service && type ? pathFor('type', { service, type }) : null
  }
  const parts = partsOfWfrn(v.key)
  return parts ? resourcePath(parts.service, parts.type, v.key) : null
}

/** A favourite's page. */
export function hrefOfFavourite(f: Pick<Favourite, 'wfrn' | 'type'>): string | null {
  const parts = partsOfWfrn(f.wfrn)
  return parts ? resourcePath(parts.service, f.type || parts.type, f.wfrn) : null
}

/** The pinned type keys, without duplicates, in the order they were pinned. */
export function pinnedOf(prefs: { pinnedTypes?: string[] }): string[] {
  return [...new Set(Array.isArray(prefs.pinnedTypes) ? prefs.pinnedTypes : [])]
}

/** Pins a type (at the end) or unpins it; the preferences keep 50 at most, so the oldest go first. */
export function withPinned(typeKey: string, on: boolean): PreferencesChange {
  return (p) => {
    const rest = pinnedOf(p).filter((k) => k !== typeKey)
    return { ...p, pinnedTypes: on ? [...rest, typeKey].slice(-50) : rest }
  }
}
