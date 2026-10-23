import { partsOfWfrn, serviceOf } from '../lib/descriptors'
import { FAVOURITES_MAX, RECENT_MAX, TITLE_MAX, VISIT_KEY_MAX, type Favourite, type RecentVisit } from '../lib/personal'
import { typeOf, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { authorize, type PersonalRecord } from './directory'

/*
 * The BFF's recent visits and favourites (console-bff.yaml, tag Recent and favourites), kept per
 * person and account in the directory, so the browser keeps them across a reload with the rest of
 * what the BFF and the identity service hold. The rules are the contract's: the 20 latest visits,
 * newest first, a visit to the same key moving it to the top; at most 100 favourites, newest
 * first, a PUT of one already starred keeping it, and the list leaving out the resources whose
 * type the person may no longer list.
 */

const TYPE_KEY = /^[a-z0-9-]{1,32}\/[a-z0-9-]{1,64}$/

function recordOf(data: MockData, userId: string, accountId: string): PersonalRecord {
  const all = (data.directory.personal ??= {})
  const rec = (all[`${userId}/${accountId}`] ??= { recent: [], favourites: [] })
  rec.recent ??= []
  rec.favourites ??= []
  return rec
}

export function personalRoutes(data: MockData, ctx: RouteContext, path: string, _params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const at = new Date(ctx.now).toISOString()
  const bad = (error: string): RouteAnswer => ({ status: 400, body: { error } })

  if (path === '/console/v1/recent') {
    const rec = recordOf(data, s.userId, s.accountId)
    if (method === 'GET') return { status: 200, body: { items: rec.recent.slice(0, RECENT_MAX) } }
    if (method === 'POST') {
      const { kind, key, title } = ctx.body
      if (kind !== 'type' && kind !== 'resource') return bad('kind is type or resource')
      if (typeof key !== 'string' || !key || key.length > VISIT_KEY_MAX) return bad('key is a type key or a wfrn, 1,024 characters at most')
      if (kind === 'type' ? !TYPE_KEY.test(key) : !partsOfWfrn(key)) return bad(kind === 'type' ? 'key is a type key, service/type' : 'key is a wfrn')
      if (typeof title !== 'string' || !title.trim() || title.length > TITLE_MAX) return bad('title is 1 to 256 characters')
      const visit: RecentVisit = { kind, key, title: title.trim(), visitedAt: at }
      rec.recent = [visit, ...rec.recent.filter((v) => !(v.kind === kind && v.key === key))].slice(0, RECENT_MAX)
      return { status: 204 }
    }
    return { status: 405, body: { error: `${method} is not allowed on the recent visits` } }
  }

  if (path === '/console/v1/favourites' && method === 'GET') {
    const rec = recordOf(data, s.userId, s.accountId)
    const listable = (f: Favourite) => {
      const rt = typeOf(data, f.type)
      const parts = partsOfWfrn(f.wfrn)
      if (!rt || !parts) return false
      return authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `${serviceOf(rt)}:list`, `wfrn:${parts.partition}:${parts.service}:${parts.region}:${parts.account}:${rt.name}/*`).allowed
    }
    return { status: 200, body: { items: rec.favourites.filter(listable) } }
  }

  const m = /^\/console\/v1\/favourites\/([^/]+)$/.exec(path)
  if (!m) return null
  const wfrn = decodeURIComponent(m[1])
  const parts = partsOfWfrn(wfrn)
  const rec = recordOf(data, s.userId, s.accountId)
  if (method === 'PUT') {
    if (!parts) return bad('the path names a wfrn')
    if (parts.account !== s.accountId) return bad('the wfrn names another account; switch to it first')
    const { type, name } = ctx.body
    if (typeof type !== 'string' || !typeOf(data, type)) return bad('type is the name of a resource type')
    if (typeof name !== 'string' || !name.trim() || name.length > TITLE_MAX) return bad('name is 1 to 256 characters')
    const kept = rec.favourites.find((f) => f.wfrn === wfrn)
    if (kept) return { status: 200, body: kept }
    if (rec.favourites.length >= FAVOURITES_MAX) return { status: 409, body: { error: 'favourites.full', detail: `An account keeps ${FAVOURITES_MAX} favourites; remove one first` } }
    const fav: Favourite = { wfrn, type, name: name.trim(), addedAt: at }
    rec.favourites = [fav, ...rec.favourites]
    return { status: 200, body: fav }
  }
  if (method === 'DELETE') {
    rec.favourites = rec.favourites.filter((f) => f.wfrn !== wfrn)
    return { status: 204 }
  }
  return null
}
