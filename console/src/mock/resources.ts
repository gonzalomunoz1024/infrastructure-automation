import { lifecycleOf, type LifecycleClass } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import { RESOURCE_STATES, type ConsoleResource, type JobSummary, type Resource, type ResourceCounts, type ResourceState, type ResourceType, type SearchHit, type Suggestion } from '../lib/types'

/*
 * The BFF's view of the platform's resources, for mock mode: `GET /console/v1/r/{type}` lists the
 * account's rows from the state service, `/search` and `/suggest` read the index. Here both are
 * computed from the captured platform samples: each resource belongs to one account (its owning
 * team's), carries a minted wfrn, and its provider status folds onto the lifecycle state. The
 * detail thread reuses `consoleResourceOf` for `GET /console/v1/r/{type}/{wfrn}`.
 */

export interface ResourceContext {
  partition: string
  region: string
  accountId: string
}

export interface MockWorld {
  resourceTypes: ResourceType[]
  resources: Resource[]
  jobs: JobSummary[]
  partition: string
  regionName: string
}

/** The account a sample resource belongs to: its own, else its owning team's; compute teams in the first account, data teams in the second. */
export function accountOfResource(r: Resource, accounts: readonly string[]): string {
  if (r.accountId && /^\d{12}$/.test(r.accountId)) return r.accountId
  const owner = (r.owner ?? '').toLowerCase()
  if (owner.includes('data') && accounts.length > 1) return accounts[1]
  return accounts[0]
}

export function stateOf(r: Resource, rt: ResourceType | undefined): ResourceState {
  const cls: LifecycleClass | null = lifecycleOf(r.status, rt?.console?.lifecycle)
  return cls ?? 'active'
}

function segment(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '')
}

export function mintWfrn(r: Resource, rt: ResourceType | undefined, ctx: ResourceContext): string {
  if (typeof r.wfrn === 'string' && r.wfrn.startsWith('wfrn:')) return r.wfrn
  const service = rt ? serviceOf(rt) : segment(r.type)
  const id = r.id.split('/').map(segment).join('/')
  return `wfrn:${ctx.partition}:${service}:${ctx.region}:${ctx.accountId}:${segment(r.type)}/${id}`
}

/** A platform resource as the BFF lists it. */
export function consoleResourceOf(r: Resource, world: MockWorld, ctx: ResourceContext): ConsoleResource {
  const rt = world.resourceTypes.find((t) => t.name === r.type)
  const attributes = { ...(r.attributes ?? {}) }
  const tags: Record<string, string> = {}
  if (r.owner) tags.owner = r.owner
  if (typeof attributes.environment === 'string') tags.environment = attributes.environment
  if (r.visibility) tags.visibility = r.visibility
  const rawTags = attributes.tags
  if (rawTags && typeof rawTags === 'object' && !Array.isArray(rawTags)) for (const [k, v] of Object.entries(rawTags)) if (typeof v === 'string') tags[k] = v
  const lastJob = r.createdBy?.jobId ? world.jobs.find((j) => j.id === r.createdBy.jobId) : undefined
  // a few rows drift, so the filter and the badge have something to show: every seventh sample
  const drift = /7$/.test(r.id)
  return {
    wfrn: mintWfrn(r, rt, ctx),
    type: r.type,
    service: rt ? serviceOf(rt) : segment(r.type),
    name: r.name ?? r.id,
    region: ctx.region,
    state: stateOf(r, rt),
    version: 1,
    attributes,
    observed: drift ? { ...attributes, status: r.status, cpu: typeof attributes.cpu === 'number' ? attributes.cpu + 2 : attributes.cpu } : { status: r.status },
    drift,
    tags,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt ?? r.createdAt,
    lastJob,
  }
}

/** The account's rows of one type. */
export function consoleResourcesOf(typeName: string, world: MockWorld, ctx: ResourceContext, accounts: readonly string[]): ConsoleResource[] {
  return world.resources.filter((r) => r.type === typeName && accountOfResource(r, accounts) === ctx.accountId).map((r) => consoleResourceOf(r, world, ctx))
}

function text(v: unknown): string {
  return v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v)
}

function haystack(r: ConsoleResource): string {
  return [r.name ?? '', r.wfrn, r.state, ...Object.values(r.tags), ...Object.values(r.attributes).map(text)].join(' ').toLowerCase()
}

/**
 * `?q=&state=&drift=&tag.<k>=` as the BFF reads them. Tombstones (`deleted`) stay out unless the
 * state asked for is `deleted`; `ignoreState` drops the state filter, for the counts by state.
 */
export function filterConsoleResources(rows: readonly ConsoleResource[], params: URLSearchParams, { ignoreState = false } = {}): ConsoleResource[] {
  const q = params.get('q')?.trim().toLowerCase()
  const state = ignoreState ? null : params.get('state')
  const drift = params.get('drift')
  const tags: [string, string][] = []
  params.forEach((v, k) => {
    if (k.startsWith('tag.') && k.length > 4) tags.push([k.slice(4), v])
  })
  const words = q ? q.split(/\s+/).filter(Boolean) : []
  return rows.filter((r) => {
    if (state && r.state !== state) return false
    if (!state && !ignoreState && r.state === 'deleted') return false
    if (drift === 'true' && !r.drift) return false
    if (drift === 'false' && r.drift) return false
    for (const [k, v] of tags) if ((r.tags[k] ?? '') !== v) return false
    if (words.length) {
      const hay = haystack(r)
      if (!words.every((w) => hay.includes(w))) return false
    }
    return true
  })
}

export function sortValue(r: ConsoleResource, key: string): string | number {
  switch (key) {
    case 'name':
      return (r.name ?? '').toLowerCase()
    case 'status':
    case 'state':
      return r.state
    case 'wfrn':
      return r.wfrn
    case 'created':
    case 'createdAt':
      return r.createdAt
    case 'updated':
    case 'updatedAt':
      return r.updatedAt
    case 'owner':
      return r.tags.owner ?? ''
    case 'drift':
      return r.drift ? 1 : 0
    default: {
      const v = r.attributes[key] ?? r.tags[key]
      return typeof v === 'number' ? v : text(v).toLowerCase()
    }
  }
}

/** `?sort=-created`: one column, `-` for descending; empties last; a stable order by wfrn after. */
export function sortConsoleResources(rows: readonly ConsoleResource[], sort: string | null | undefined): ConsoleResource[] {
  if (!sort) return [...rows]
  const desc = sort.startsWith('-')
  const key = desc ? sort.slice(1) : sort
  const sign = desc ? -1 : 1
  return [...rows].sort((a, b) => {
    const x = sortValue(a, key)
    const y = sortValue(b, key)
    const xe = x === '' || x === undefined
    const ye = y === '' || y === undefined
    if (xe && ye) return a.wfrn.localeCompare(b.wfrn)
    if (xe) return 1
    if (ye) return -1
    const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true, sensitivity: 'base' })
    return sign * c || a.wfrn.localeCompare(b.wfrn)
  })
}

/** The cap the BFF counts to before it answers "1,000+" (the plan's massive-scale rule). */
export const COUNT_CAP = 1000

/**
 * `GET /console/v1/r/{type}:counts` as the BFF answers it, over all of a type's rows: `byState`
 * counts every state whatever the state filter (tombstones under deleted), while `total` and
 * `drift` apply every filter and leave tombstones out unless the state is `deleted`. Each count
 * stops at the cap, and `cappedAt` is null while every count is exact.
 */
export function countsOf(rows: readonly ConsoleResource[], params: URLSearchParams = new URLSearchParams(), cap = COUNT_CAP): ResourceCounts {
  const byState = Object.fromEntries(RESOURCE_STATES.map((s) => [s, 0])) as Record<ResourceState, number>
  for (const r of filterConsoleResources(rows, params, { ignoreState: true })) byState[r.state] += 1
  const matching = filterConsoleResources(rows, params)
  const drift = matching.filter((r) => r.drift).length
  const capped = (n: number) => Math.min(n, cap)
  const reached = [matching.length, drift, ...Object.values(byState)].some((n) => n >= cap)
  return { total: capped(matching.length), byState: Object.fromEntries(RESOURCE_STATES.map((s) => [s, capped(byState[s])])) as Record<ResourceState, number>, drift: capped(drift), cappedAt: reached ? cap : null }
}

/** Hits for `/console/v1/search`: name and wfrn first, then tags and attributes. */
export function searchConsoleResources(rows: readonly ConsoleResource[], q: string, type?: string | null): SearchHit[] {
  const needle = q.trim().toLowerCase()
  if (!needle) return []
  const hits: SearchHit[] = []
  for (const r of rows) {
    if (type && r.type !== type) continue
    const name = (r.name ?? '').toLowerCase()
    let score = 0
    const highlights: Record<string, string> = {}
    if (name === needle) score = 10
    else if (name.startsWith(needle)) score = 6
    else if (name.includes(needle)) score = 4
    if (score) highlights.name = r.name ?? ''
    if (!score && r.wfrn.toLowerCase().includes(needle)) {
      score = 3
      highlights.wfrn = r.wfrn
    }
    if (!score) {
      for (const [k, v] of Object.entries(r.tags)) {
        if (v.toLowerCase().includes(needle)) {
          score = 2
          highlights[`tag.${k}`] = v
          break
        }
      }
    }
    if (!score) {
      for (const [k, v] of Object.entries(r.attributes)) {
        if (text(v).toLowerCase().includes(needle)) {
          score = 1
          highlights[k] = text(v)
          break
        }
      }
    }
    if (score) hits.push({ wfrn: r.wfrn, type: r.type, service: r.service, region: r.region, name: r.name, state: r.state, drift: r.drift, tags: r.tags, score, highlights, updatedAt: r.updatedAt })
  }
  return hits.sort((a, b) => b.score - a.score || (a.name ?? '').localeCompare(b.name ?? ''))
}

/** Type-ahead for `/console/v1/suggest`: types, then names, tags and wfrns. */
export function suggestFrom(rows: readonly ConsoleResource[], types: readonly ResourceType[], q: string, limit = 8): Suggestion[] {
  const needle = q.trim().toLowerCase()
  if (!needle) return []
  const out: Suggestion[] = []
  const seen = new Set<string>()
  const push = (s: Suggestion) => {
    const key = `${s.kind}:${s.value}`
    if (seen.has(key) || out.length >= limit) return
    seen.add(key)
    out.push(s)
  }
  for (const t of types) {
    const title = t.console?.title ?? t.plural
    if (title.toLowerCase().includes(needle) || t.name.includes(needle)) push({ kind: 'type', value: t.name, label: title })
  }
  for (const r of rows) if ((r.name ?? '').toLowerCase().includes(needle)) push({ kind: 'resource', value: r.wfrn, label: r.name ?? r.wfrn })
  for (const r of rows) for (const [k, v] of Object.entries(r.tags)) if (v.toLowerCase().includes(needle) || k.includes(needle)) push({ kind: 'tag', value: `${k}=${v}`, label: `${k}: ${v}` })
  if (needle.startsWith('wfrn:')) for (const r of rows) if (r.wfrn.toLowerCase().startsWith(needle)) push({ kind: 'wfrn', value: r.wfrn, label: r.wfrn })
  return out
}
