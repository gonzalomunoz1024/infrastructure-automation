import { serviceOf } from '../lib/descriptors'
import type { Session } from '../lib/session'
import type { AccessKey, AccessRequestInput, BffConsoleAction, BffConsoleType, IamGroupInput, IamUser, JobDetail, JobStep, JobSummary, Lineage, PlatformEvent, Resource } from '../lib/types'
import { buildMockData, nextMockEvent, type MockData } from './data'
import { liveRowOf, resourceRoutes, settleConsole, usedByTypeOf } from './commands'
import { assistantRoutes } from './assistant'
import { costRoutes } from './cost'
import { insightRoutes } from './insights'
import { DirectoryError, PendingRequestError, accessKeyOf, accessKeysOf, accessRequestOf, accessRequestsOf, accountSummaryFor, addUser, authorize, createAccessKey, createAccessRequest, createGroup, decideAccessRequest, demoPeople, iamResource, isMember, isQuotaKey, membershipsOf, personOf, preferencesOf, putPreferences, quotaOf, quotaRequestOf, quotasOf, removeGroup, removeUser, requestQuotaIncrease, revokeAccessKey, rotateAccessKey, seedQuotaOverrides, updateGroup, updateUser, userOf, usersOf, withdrawQuotaRequest, type Decision } from './directory'
import { consoleResourcesOf, countsOf, filterConsoleResources, searchConsoleResources, sortConsoleResources, suggestFrom, type ResourceContext } from './resources'

/*
 * The backends answered from the dataset, as a pure function of the request so the unit tests
 * can call it and the browser can serve it through a fetch interceptor. It speaks two contracts:
 * the platform's read endpoints under /api/v1 (ETags on the descriptors, cursor paging with a
 * Link header, regions, the events log) and the BFF's under /auth and /console/v1 (the session
 * behind a cookie the mock keeps in its session store, the CSRF token on every write, the
 * accounts and regions, the types with their decisions, the identity service's resources under
 * /console/v1/iam, and access requests). A refusal answers 403 with the Denial block.
 */

export interface MockRequest {
  method: string
  /** path and query, e.g. '/api/v1/vms?limit=50' */
  url: string
  /** header names in lower case */
  headers: Record<string, string>
  /** a write's JSON body */
  body?: unknown
}

export interface MockResponse {
  status: number
  body?: unknown
  headers?: Record<string, string>
}

/** What the BFF keeps behind the console_session cookie. */
export interface MockSession {
  userId: string
  accountId: string
  region: string
  authTime: string
}

export interface MockSessionStore {
  get: () => MockSession | null
  set: (next: MockSession | null) => void
}

export function memorySessionStore(initial: MockSession | null = null): MockSessionStore {
  let current = initial
  return {
    get: () => current,
    set: (next) => {
      current = next
    },
  }
}

export interface MockServer {
  data: MockData
  sessions: MockSessionStore
  handle: (req: MockRequest) => MockResponse
}

export const SESSION_TTL_MS = 8 * 3_600_000
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

function hash(s: string): string {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return (h >>> 0).toString(36)
}

export function etagOf(name: string, value: unknown): string {
  return `W/"${name}-${hash(JSON.stringify(value))}"`
}

export function parseCursor(after: string | null): number {
  const m = after && /^c(\d+)$/.exec(after)
  return m ? Number(m[1]) : 0
}

export function cursorOf(index: number): string {
  return `c${index}`
}

export function csrfTokenOf(userId: string): string {
  return `mock-csrf-${userId}`
}

function paged<T>(items: T[], path: string, params: URLSearchParams): MockResponse {
  const limit = Math.min(500, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const start = parseCursor(params.get('after'))
  const headers: Record<string, string> = { 'X-Total-Count': String(items.length) }
  if (start + limit < items.length) {
    const next = new URLSearchParams(params)
    next.set('limit', String(limit))
    next.set('after', cursorOf(start + limit))
    headers.Link = `<${path}?${next.toString()}>; rel="next"`
  }
  return { status: 200, body: items.slice(start, start + limit), headers }
}

/** The BFF's page shape: { items, next }. */
function pagedBody<T>(items: T[], params: URLSearchParams): MockResponse {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const start = parseCursor(params.get('after'))
  const slice = items.slice(start, start + limit)
  return { status: 200, body: { items: slice, next: start + limit < items.length ? cursorOf(start + limit) : null }, headers: { 'X-Total-Count': String(items.length) } }
}

function notFound(what: string): MockResponse {
  return { status: 404, body: { error: `${what} not found` } }
}

function cached(name: string, value: unknown, req: MockRequest): MockResponse {
  const etag = etagOf(name, value)
  if (req.headers['if-none-match'] === etag) return { status: 304, headers: { ETag: etag } }
  return { status: 200, body: value, headers: { ETag: etag, 'Cache-Control': 'no-cache' } }
}

function text(v: unknown): string {
  return v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v)
}

function filterResources(resources: Resource[], params: URLSearchParams): Resource[] {
  const status = params.get('status')
  const owner = params.get('owner')
  const type = params.get('type')
  const account = params.get('accountId')
  const q = params.get('q')?.toLowerCase()
  return resources.filter((r) => {
    if (status && r.status !== status) return false
    if (owner && r.owner !== owner) return false
    if (type && r.type !== type) return false
    if (account && r.accountId && r.accountId !== account) return false
    if (q && !`${r.name ?? ''} ${r.id} ${Object.values(r.attributes ?? {}).map(text).join(' ')}`.toLowerCase().includes(q)) return false
    return true
  })
}

function filterJobs(jobs: JobSummary[], params: URLSearchParams): JobSummary[] {
  const q = params.get('q')?.toLowerCase()
  return jobs.filter((j) => {
    for (const key of ['status', 'workflow', 'requester', 'channel', 'kind'] as const) {
      const want = params.get(key)
      if (want && j[key] !== want) return false
    }
    if (q && !`${j.id} ${j.title} ${j.workflow} ${j.requester}`.toLowerCase().includes(q)) return false
    return true
  })
}

/** The steps a job ran, drawn from its status: the platform-fake keeps only summaries. */
export function stepsOf(job: JobSummary): JobStep[] {
  const at = Date.parse(job.startedAt)
  const step = (id: string, name: string, type: string, status: JobStep['status'], offset: number, extra: Partial<JobStep> = {}): JobStep => ({
    id,
    name,
    type,
    status,
    attempt: 1,
    startedAt: status === 'pending' ? undefined : new Date(at + offset).toISOString(),
    durationMs: status === 'succeeded' ? 4_000 + offset / 10 : status === 'failed' ? 12_000 : undefined,
    ...extra,
  })
  const failed = job.status === 'failed'
  const running = job.status === 'running'
  const waiting = job.status === 'waiting-approval'
  const attention = job.status === 'needs-attention'
  const failedStep = job.failedStep ?? 'apply'
  const steps: JobStep[] = [step('validate', 'Validate the input', 'set', 'succeeded', 0, { input: { workflow: job.workflow, version: job.version } })]
  if (waiting) steps.push(step('approve', 'Wait for an approval', 'approval', 'waiting', 5_000))
  else steps.push(step('approve', 'Wait for an approval', 'approval', 'succeeded', 5_000))
  if (job.kind === 'batch' && job.progress) steps.push(step('rollout', 'Roll out in waves', 'for', running ? 'running' : failed ? 'failed' : 'succeeded', 20_000, { items: job.progress.total }))
  const applyStatus: JobStep['status'] = waiting ? 'pending' : failed ? 'failed' : running ? 'running' : attention ? 'waiting' : 'succeeded'
  steps.push(
    step(failedStep === 'apply' || !failed ? 'apply' : failedStep, failed && job.failedStep ? `Run ${job.failedStep}` : 'Apply the change', 'provider', applyStatus, 30_000, {
      error: failed ? { class: job.errorClass ?? 'provider', message: `${job.failedStep ?? 'apply'} failed: ${job.errorClass ?? 'the provider refused'}`, retryable: job.errorClass !== 'not_found' } : undefined,
      output: applyStatus === 'succeeded' ? { ok: true } : undefined,
    }),
  )
  steps.push(step('register', 'Register the resource', 'register', applyStatus === 'succeeded' ? 'succeeded' : failed ? 'skipped' : 'pending', 60_000))
  return steps
}

function bodyOf(req: MockRequest): Record<string, unknown> {
  const b = req.body
  if (b && typeof b === 'object') return b as Record<string, unknown>
  if (typeof b === 'string' && b.trim()) {
    try {
      const parsed = JSON.parse(b) as unknown
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
    } catch {
      return {}
    }
  }
  return {}
}

function strings(v: unknown): string[] | undefined {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : undefined
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined
}

/** A refusal as the BFF answers it: 403 with the Denial block. */
function refused(decision: Decision): MockResponse {
  return { status: 403, body: { error: decision.denied?.reason ?? 'Forbidden', denied: decision.denied, correlationId: `mock-${hash(JSON.stringify(decision.denied ?? {}))}` } }
}

export function createMockServer(data: MockData = buildMockData(), sessions: MockSessionStore = memorySessionStore(), now: () => number = Date.now): MockServer {
  const plurals = new Map(data.resourceTypes.map((t) => [t.plural, t.name]))
  const dir = data.directory

  const currentSession = (): MockSession | null => {
    const s = sessions.get()
    if (!s) return null
    if (now() - Date.parse(s.authTime) > SESSION_TTL_MS || !personOf(dir, s.userId) || !isMember(dir, s.accountId, s.userId)) {
      sessions.set(null)
      return null
    }
    return s
  }

  const sessionBody = (s: MockSession): Session => {
    const person = personOf(dir, s.userId)!
    const account = accountSummaryFor(dir, s.accountId, s.userId)
    return {
      user: { userId: person.id, email: person.email, name: person.name, groups: account.groups },
      account,
      region: s.region,
      csrfToken: csrfTokenOf(person.id),
      expiresAt: new Date(Date.parse(s.authTime) + SESSION_TTL_MS).toISOString(),
      authTime: s.authTime,
    }
  }

  const login = (params: URLSearchParams): MockResponse => {
    const hint = params.get('login_hint')
    if (!hint) return { status: 400, body: { error: 'login_hint.required', detail: 'The mock identity provider signs a known person in: add login_hint=<person>; GET /demo-idp/people lists them.' } }
    const person = personOf(dir, hint)
    if (!person) return { status: 400, body: { error: `The identity provider knows nobody called ${hint}` } }
    const memberships = membershipsOf(dir, person.id)
    if (!memberships.length) return { status: 403, body: { error: `${person.name} belongs to no account` } }
    const previous = sessions.get()
    const keep = previous && previous.userId === person.id && memberships.some((m) => m.accountId === previous.accountId) ? previous.accountId : memberships[0].accountId
    sessions.set({ userId: person.id, accountId: keep, region: data.regionName, authTime: new Date(now()).toISOString() })
    return { status: 200, body: { returnTo: params.get('returnTo') ?? '/' } }
  }

  /** Every type with the decision for each of its verbs, for this person in this account and region. */
  const consoleTypes = (s: MockSession): BffConsoleType[] => {
    const ctx = { accountId: s.accountId, userId: s.userId }
    return data.resourceTypes.map((rt) => {
      const service = serviceOf(rt)
      const pattern = `wfrn:${data.partition}:${service}:${s.region}:${s.accountId}:${rt.name}/*`
      const entries = data.catalog.filter((e) => e.noun === rt.name)
      const fromCatalog: BffConsoleAction[] = entries.map((e) => ({ verb: e.verb, title: e.title, description: e.description, method: e.method, destructive: e.destructive ?? e.verb === 'delete', approval: typeof e.approval === 'object' ? !!e.approval.required : !!e.approval, bulk: !!e.batchPath, preview: !!e.previewPath, effect: e.verb === 'create' ? 'create' : e.verb === 'delete' ? 'delete' : 'change', workflow: e.workflow, version: e.version }))
      const noun = (rt.console?.title ?? rt.plural).replace(/s$/, '').toLowerCase()
      const standard: BffConsoleAction[] = [
        { verb: 'list', title: `List ${rt.plural}`, method: 'GET', effect: 'read', destructive: false, approval: false, bulk: false, preview: false },
        { verb: 'get', title: `View a ${noun}`, method: 'GET', effect: 'read', destructive: false, approval: false, bulk: false, preview: false },
        { verb: 'create', title: `Create ${noun}`, method: 'POST', effect: 'create', destructive: false, approval: false, bulk: false, preview: false },
        { verb: 'update', title: `Update ${noun}`, method: 'PATCH', effect: 'change', destructive: false, approval: false, bulk: false, preview: false },
        { verb: 'delete', title: `Delete ${noun}`, method: 'DELETE', effect: 'delete', destructive: true, approval: false, bulk: false, preview: false },
      ]
      const actions = [...standard.filter((a) => !fromCatalog.some((c) => c.verb === a.verb)), ...fromCatalog].map((a) => {
        const d = authorize(dir, ctx, `${service}:${a.verb}`, pattern)
        return { ...a, allowed: d.allowed, denied: d.denied }
      })
      const type: BffConsoleType = { key: `${service}/${rt.name}`, type: rt.name, service, plural: rt.plural, title: rt.console?.title ?? rt.plural, actions, etag: '' }
      type.etag = etagOf(`type-${type.key}`, { actions, user: s.userId, account: s.accountId })
      return type
    })
  }

  /** The account's live resources by type, for the `resources.<type>` quotas: what the state service counts (tombstones left out) and reports to the identity service. */
  const typeNames = [...new Set(data.resourceTypes.map((rt) => rt.name))]
  // the create review's quota rule counts with the same function, so the two always agree
  const usedOf = (accountId: string): Record<string, number> => ({ ...Object.fromEntries(typeNames.map((t) => [t, 0])), ...usedByTypeOf(data, accountId) })
  seedQuotaOverrides(dir, Object.fromEntries(dir.accounts.map((a) => [a.accountId, usedOf(a.accountId)])))

  const consoleRoutes = (req: MockRequest, s: MockSession, path: string, params: URLSearchParams, method: string): MockResponse => {
    const ctx = { accountId: s.accountId, userId: s.userId }
    const can = (action: string, resource: string) => authorize(dir, ctx, action, resource)
    const iam = (kind: string, id?: string) => iamResource(s.accountId, kind, id)
    const body = bodyOf(req)
    const ifMatch = req.headers['if-match'] ? Number(req.headers['if-match']) : undefined
    const me = userOf(dir, s.accountId, s.userId)!
    const at = now()

    if (path === '/console/v1/session') {
      if (method === 'GET') return { status: 200, body: sessionBody(s) }
      if (method === 'PUT') {
        const accountId = str(body.accountId)
        const region = str(body.region)
        if (accountId && !isMember(dir, accountId, s.userId)) return { status: 404, body: { error: `You are not a member of account ${accountId}` } }
        if (region && region !== data.regionName) return { status: 400, body: { error: `No region ${region}; the platform serves ${data.regionName}` } }
        const next: MockSession = { ...s, accountId: accountId ?? s.accountId, region: region ?? s.region }
        sessions.set(next)
        return { status: 200, body: sessionBody(next) }
      }
      return { status: 405, body: { error: `${method} is not allowed on the session` } }
    }
    if (path === '/console/v1/accounts') return { status: 200, body: { items: membershipsOf(dir, s.userId), next: null } }
    if (path === '/console/v1/regions') return { status: 200, body: { items: [{ name: data.regionName, title: 'Global', status: 'available', environments: ['dev', 'test', 'prod'], types: data.resourceTypes.map((t) => t.name) }], next: null } }

    if (path === '/console/v1/types') return cached(`types-${s.userId}-${s.accountId}`, consoleTypes(s), req)
    let m = /^\/console\/v1\/types\/([^/]+)$/.exec(path)
    if (m) {
      const key = decodeURIComponent(m[1])
      const t = consoleTypes(s).find((x) => x.key === key || x.type === key)
      return t ? { status: 200, body: t, headers: { ETag: t.etag ?? '' } } : notFound(`Type ${key}`)
    }

    /* identity and access, as the identity service guards it: iam:<verb> on the IAM wfrn of the thing */
    const q = params.get('q')?.toLowerCase()
    if (path === '/console/v1/iam/users') {
      if (method === 'GET') {
        const d = can('iam:list', iam('user'))
        if (!d.allowed) return refused(d)
        return pagedBody(
          usersOf(dir, s.accountId).filter((u) => !q || `${u.userId} ${u.email} ${u.name ?? ''} ${(u.groups ?? []).join(' ')}`.toLowerCase().includes(q)),
          params,
        )
      }
      if (method === 'POST') {
        const d = can('iam:create', iam('user'))
        if (!d.allowed) return refused(d)
        return { status: 201, body: addUser(dir, s.accountId, { userId: str(body.userId), email: str(body.email), name: str(body.name), groups: strings(body.groups) }) }
      }
    }
    m = /^\/console\/v1\/iam\/users\/([^/]+)$/.exec(path)
    if (m) {
      const userId = decodeURIComponent(m[1])
      if (method === 'GET') {
        if (userId !== s.userId) {
          const d = can('iam:get', iam('user', userId))
          if (!d.allowed) return refused(d)
        }
        const u = userOf(dir, s.accountId, userId)
        return u ? { status: 200, body: u, headers: { ETag: String(u.version) } } : notFound(`User ${userId}`)
      }
      if (method === 'PATCH') {
        const d = can('iam:update', iam('user', userId))
        if (!d.allowed) return refused(d)
        return { status: 200, body: updateUser(dir, s.accountId, userId, { groups: strings(body.groups) }, ifMatch) }
      }
      if (method === 'DELETE') {
        const d = can('iam:delete', iam('user', userId))
        if (!d.allowed) return refused(d)
        removeUser(dir, s.accountId, userId)
        return { status: 204 }
      }
    }
    if (path === '/console/v1/iam/groups') {
      if (method === 'GET') {
        const d = can('iam:list', iam('group'))
        if (!d.allowed) return refused(d)
        return pagedBody((dir.groups[s.accountId] ?? []).filter((g) => !q || `${g.name} ${g.description ?? ''} ${(g.roles ?? []).join(' ')}`.toLowerCase().includes(q)), params)
      }
      if (method === 'POST') {
        const d = can('iam:create', iam('group'))
        if (!d.allowed) return refused(d)
        return { status: 201, body: createGroup(dir, s.accountId, body as IamGroupInput) }
      }
    }
    m = /^\/console\/v1\/iam\/groups\/([^/]+)$/.exec(path)
    if (m) {
      const groupId = decodeURIComponent(m[1])
      const g = (dir.groups[s.accountId] ?? []).find((x) => x.groupId === groupId || x.name === groupId)
      if (method === 'GET') {
        const d = can('iam:get', iam('group', g?.groupId ?? groupId))
        if (!d.allowed) return refused(d)
        return g ? { status: 200, body: g, headers: { ETag: String(g.version) } } : notFound(`Group ${groupId}`)
      }
      if (method === 'PATCH') {
        const d = can('iam:update', iam('group', g?.groupId ?? groupId))
        if (!d.allowed) return refused(d)
        return { status: 200, body: updateGroup(dir, s.accountId, groupId, body as IamGroupInput, ifMatch) }
      }
      if (method === 'DELETE') {
        const d = can('iam:delete', iam('group', g?.groupId ?? groupId))
        if (!d.allowed) return refused(d)
        removeGroup(dir, s.accountId, groupId)
        return { status: 204 }
      }
    }
    if (path === '/console/v1/iam/roles' && method === 'GET') {
      const d = can('iam:list', iam('role'))
      return d.allowed ? pagedBody(dir.roles, params) : refused(d)
    }
    m = /^\/console\/v1\/iam\/roles\/([^/]+)$/.exec(path)
    if (m && method === 'GET') {
      const id = decodeURIComponent(m[1])
      const d = can('iam:get', iam('role', id))
      if (!d.allowed) return refused(d)
      const r = dir.roles.find((x) => x.roleId === id || x.name === id)
      return r ? { status: 200, body: r } : notFound(`Role ${id}`)
    }
    if (path === '/console/v1/iam/policies' && method === 'GET') {
      const d = can('iam:list', iam('policy'))
      return d.allowed ? pagedBody(dir.policies[s.accountId] ?? [], params) : refused(d)
    }
    m = /^\/console\/v1\/iam\/policies\/([^/]+)$/.exec(path)
    if (m && method === 'GET') {
      const id = decodeURIComponent(m[1])
      const d = can('iam:get', iam('policy', id))
      if (!d.allowed) return refused(d)
      const p = (dir.policies[s.accountId] ?? []).find((x) => x.policyId === id || x.name === id)
      return p ? { status: 200, body: p } : notFound(`Policy ${id}`)
    }

    /* access keys: a person's own are theirs to manage; anyone else's take iam:<verb> on the key */
    if (path === '/console/v1/iam/access-keys') {
      if (method === 'GET') {
        const wanted = params.get('userId') ?? undefined
        if (wanted !== s.userId) {
          const d = can('iam:list', iam('access-key'))
          if (!d.allowed) return refused(d)
        }
        return pagedBody(accessKeysOf(dir, s.accountId, at).filter((k) => !wanted || k.userId === wanted), params)
      }
      if (method === 'POST') {
        const forUser = str(body.userId) ?? s.userId
        if (forUser !== s.userId) {
          const d = can('iam:create', iam('access-key'))
          if (!d.allowed) return refused(d)
        }
        return { status: 201, body: createAccessKey(dir, s.accountId, forUser, { description: str(body.description), expiresAt: str(body.expiresAt) }, at) }
      }
    }
    m = /^\/console\/v1\/iam\/access-keys\/([^/:]+)(?::(rotate))?$/.exec(path)
    if (m) {
      const keyId = decodeURIComponent(m[1])
      const key: AccessKey | undefined = accessKeyOf(dir, s.accountId, keyId, at)
      if (!key) return notFound(`Access key ${keyId}`)
      const own = key.userId === s.userId
      const needs = (verb: string) => (own ? null : can(`iam:${verb}`, iam('access-key', keyId)))
      if (method === 'GET' && !m[2]) {
        const d = needs('get')
        return d && !d.allowed ? refused(d) : { status: 200, body: key }
      }
      if (method === 'DELETE' && !m[2]) {
        const d = needs('delete')
        if (d && !d.allowed) return refused(d)
        revokeAccessKey(dir, s.accountId, keyId)
        return { status: 204 }
      }
      if (method === 'POST' && m[2] === 'rotate') {
        const d = needs('update')
        if (d && !d.allowed) return refused(d)
        return { status: 200, body: rotateAccessKey(dir, s.accountId, keyId, at) }
      }
    }

    /* quotas: the account reads them, members ask for more, the platform team decides */
    if (path === '/console/v1/iam/quotas' && method === 'GET') {
      const d = can('quota:list', iam('quota'))
      return d.allowed ? { status: 200, body: { items: quotasOf(dir, s.accountId, usedOf(s.accountId), typeNames, at) } } : refused(d)
    }
    m = /^\/console\/v1\/iam\/quotas\/([^/:]+)(?::(request-increase))?$/.exec(path)
    if (m) {
      const key = decodeURIComponent(m[1])
      if (method === 'GET' && !m[2]) {
        const d = can('quota:get', iam('quota', key))
        if (!d.allowed) return refused(d)
        return isQuotaKey(key) ? { status: 200, body: quotaOf(dir, s.accountId, key, usedOf(s.accountId), at) } : notFound(`Quota ${key}`)
      }
      if (method === 'POST' && m[2] === 'request-increase') {
        const d = can('quota:request-increase', iam('quota', key))
        if (!d.allowed) return refused(d)
        if (!isQuotaKey(key)) return notFound(`Quota ${key}`)
        const current = quotaOf(dir, s.accountId, key, usedOf(s.accountId), at)
        return { status: 202, body: requestQuotaIncrease(dir, s.accountId, key, s.userId, { limit: body.limit as number, reason: body.reason as string }, current, at) }
      }
    }
    m = /^\/console\/v1\/iam\/quota-requests\/([^/:]+)$/.exec(path)
    if (m) {
      const requestId = decodeURIComponent(m[1])
      if (method === 'GET') {
        const d = can('quota:get', iam('quota', requestId))
        if (!d.allowed) return refused(d)
        const r = quotaRequestOf(dir, s.accountId, requestId)
        return r ? { status: 200, body: r } : notFound(`Quota request ${requestId}`)
      }
      if (method === 'DELETE') {
        const r = quotaRequestOf(dir, s.accountId, requestId)
        if (!r) return notFound(`Quota request ${requestId}`)
        if (r.requestedBy !== s.userId) {
          const d = can('quota:set', iam('quota', r.quotaKey))
          if (!d.allowed) return refused(d)
        }
        withdrawQuotaRequest(dir, s.accountId, requestId, s.userId, at)
        return { status: 204 }
      }
    }

    /* access requests: anyone reads their own; the account's take access-request:list, deciding access-request:decide */
    if (path === '/console/v1/access-requests') {
      if (method === 'GET') {
        const status = params.get('status')
        if (status && !['pending', 'approved', 'rejected', 'expired'].includes(status)) return { status: 400, body: { error: 'status is pending, approved, rejected or expired' } }
        const requester = params.get('requester') ?? undefined
        const reads = can('access-request:list', iam('access-request'))
        let only = requester
        if (!reads.allowed) {
          if (requester && requester !== s.userId) return refused(reads)
          only = s.userId
        }
        return pagedBody(
          accessRequestsOf(dir, s.accountId, at).filter((r) => (!only || r.requester.userId === only) && (!status || r.status === status)),
          params,
        )
      }
      if (method === 'POST') {
        const d = can('access-request:create', iam('access-request'))
        if (!d.allowed) return refused(d)
        const input: Partial<AccessRequestInput> = { action: str(body.action), resource: str(body.resource), role: str(body.role), reason: str(body.reason), layer: body.layer === 'platform' ? 'platform' : 'console-iam' }
        return { status: 202, body: createAccessRequest(dir, s.accountId, me, input, at) }
      }
    }
    m = /^\/console\/v1\/access-requests\/([^/:]+)(?::(decide))?$/.exec(path)
    if (m) {
      const requestId = decodeURIComponent(m[1])
      if (method === 'GET' && !m[2]) {
        const request = accessRequestOf(dir, s.accountId, requestId, at)
        if (!request) return notFound(`Access request ${requestId}`)
        if (request.requester.userId !== s.userId) {
          const d = can('access-request:get', iam('access-request', requestId))
          if (!d.allowed) return refused(d)
        }
        return { status: 200, body: request }
      }
      if (method === 'POST' && m[2] === 'decide') {
        const d = can('access-request:decide', iam('access-request', requestId))
        if (!d.allowed) return refused(d)
        const request = accessRequestOf(dir, s.accountId, requestId, at)
        if (!request) return notFound(`Access request ${requestId}`)
        // nobody decides their own request: the identity service's denial, for approve and reject alike
        if (request.requester.userId === s.userId) return refused({ allowed: false, denied: { layer: 'console-iam', action: 'access-request:decide', resource: iam('access-request', requestId), reason: 'nobody decides their own access request', requestable: false } })
        const decision = body.decision === 'approve' ? 'approve' : body.decision === 'reject' ? 'reject' : null
        if (!decision) return { status: 400, body: { error: 'decision is approve or reject' } }
        return { status: 202, body: decideAccessRequest(dir, s.accountId, requestId, decision, s.userId, str(body.comment), at) }
      }
    }

    /* phase 9: the executive home's numbers, cost and budgets, the copilot */
    const phase9 = { session: s, body, headers: req.headers, now: now() }
    const beyond = insightRoutes(data, phase9, path, params, method) ?? costRoutes(data, phase9, path, params, method) ?? assistantRoutes(data, phase9, path, params, method)
    if (beyond) return beyond

    /* resources, as the state service lists them for the account; search and suggestions over the same rows */
    const rctx: ResourceContext = { partition: data.partition, region: s.region, accountId: s.accountId }
    const accountIds = dir.accounts.map((a) => a.accountId)
    // each row as the console's own requests left it, the same as the resource page reads it
    const rowsOf = (typeName: string) => consoleResourcesOf(typeName, data, rctx, accountIds, (r) => liveRowOf(data, s, r, now()))
    m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})(:counts)?$/.exec(path)
    if (m && method === 'GET') {
      const typeName = m[1]
      const rt = data.resourceTypes.find((t) => t.name === typeName || t.plural === typeName)
      if (!rt) return notFound(`Type ${typeName}`)
      const listDecision = can(`${serviceOf(rt)}:list`, `wfrn:${data.partition}:${serviceOf(rt)}:${s.region}:${s.accountId}:${rt.name}/*`)
      if (!listDecision.allowed) return refused(listDecision)
      if (m[2]) return { status: 200, body: countsOf(rowsOf(rt.name), params) }
      const matching = filterConsoleResources(rowsOf(rt.name), params)
      return pagedBody(sortConsoleResources(matching, params.get('sort')), params)
    }
    if (path === '/console/v1/search' && method === 'GET') {
      const needle = params.get('q') ?? ''
      if (!needle.trim()) return { status: 400, body: { error: 'q is required' } }
      const typeName = params.get('type')
      const types = typeName ? data.resourceTypes.filter((t) => t.name === typeName || t.plural === typeName) : data.resourceTypes
      const hits = types.flatMap((t) => searchConsoleResources(rowsOf(t.name), needle)).sort((a, b) => b.score - a.score || (a.name ?? '').localeCompare(b.name ?? ''))
      return pagedBody(hits, params)
    }
    if (path === '/console/v1/suggest' && method === 'GET') {
      const needle = params.get('q') ?? ''
      if (!needle.trim()) return { status: 400, body: { error: 'q is required' } }
      const limit = Math.min(20, Math.max(1, Number(params.get('limit') ?? 8) || 8))
      const rows = data.resourceTypes.flatMap((t) => rowsOf(t.name))
      return { status: 200, body: { items: suggestFrom(rows, data.resourceTypes, needle, limit) } }
    }
    if (path === '/console/v1/preferences') {
      if (method === 'GET') return { status: 200, body: preferencesOf(dir, s.userId) }
      if (method === 'PUT') return { status: 200, body: putPreferences(dir, s.userId, body) }
      return { status: 405, body: { error: `${method} is not allowed on the preferences` } }
    }

    /* resources, previews, activity, drift, lineage, tags and jobs (phase 3) */
    const answered = resourceRoutes(data, { session: s, body, headers: req.headers, now: now() }, path, params, method)
    if (answered) return answered
    return notFound(`Path ${path}`)
  }

  const handle = (req: MockRequest): MockResponse => {
    const url = new URL(req.url, 'http://mock.local')
    const path = url.pathname.replace(/\/+$/, '') || '/'
    const params = url.searchParams
    const method = req.method.toUpperCase()
    // the jobs the console started move on the clock: approvals land, applies finish, resources settle
    settleConsole(data, now())

    /* the demo identity provider and the BFF */
    if (path === '/demo-idp/people' && method === 'GET') return { status: 200, body: { items: demoPeople(dir), next: null } }
    if (path === '/auth/login' && method === 'GET') return login(params)
    if (path === '/auth/logout' && method === 'POST') {
      sessions.set(null)
      return { status: 204 }
    }
    if (path.startsWith('/console/')) {
      const s = currentSession()
      if (!s) return { status: 401, body: { error: 'Not signed in' } }
      if (!SAFE_METHODS.has(method) && req.headers['x-csrf-token'] !== csrfTokenOf(s.userId)) return { status: 403, body: { error: 'csrf.missing', detail: 'Every write carries the session\'s csrfToken as X-Csrf-Token' } }
      try {
        return consoleRoutes(req, s, path, params, method)
      } catch (e) {
        if (e instanceof PendingRequestError) return { status: 409, body: e.request }
        if (e instanceof DirectoryError) return { status: e.status, body: e.detail === undefined ? { error: e.message } : { error: e.message, detail: e.detail } }
        throw e
      }
    }

    /* the platform */
    if (method !== 'GET' && method !== 'HEAD') return { status: 405, body: { error: `${method} is not served by the mock on the platform's endpoints; changes go through the BFF from phase 3` } }

    if (path === '/actuator/health') return { status: 200, body: { status: 'UP', components: { db: { status: 'UP' }, events: { status: 'UP' } } } }
    if (path === '/actuator/info') return { status: 200, body: { build: { version: 'platform-fake', commit: 'c9529d5' } } }

    if (path === '/api/v1/me') {
      const user = req.headers['x-user']
      if (!user) return { status: 401, body: { error: 'Not signed in' } }
      const me = data.users[user]
      return me ? { status: 200, body: me } : { status: 401, body: { error: `Unknown user ${user}` } }
    }
    if (path === '/api/v1/resource-types') return cached('resource-types', data.resourceTypes, req)
    if (path === '/api/v1/catalog') return cached('catalog', data.catalog, req)
    if (path === '/api/v1/regions') return cached('regions', data.regions, req)
    let m = /^\/api\/v1\/regions\/([^/]+)$/.exec(path)
    if (m) {
      const name = decodeURIComponent(m[1])
      const region = data.regions.find((r) => (r as { metadata?: { name?: string } }).metadata?.name === name)
      return region ? { status: 200, body: region } : notFound('Region')
    }

    if (path === '/api/v1/events') {
      const after = Number(params.get('after') ?? 0) || 0
      const limit = Math.min(500, Math.max(1, Number(params.get('limit') ?? 100) || 100))
      const types = params.get('types')?.split(',').filter(Boolean)
      const items = data.events.filter((e) => e.seq > after && (!types?.length || types.includes(e.type))).slice(0, limit)
      const more = items.length === limit && data.events.some((e) => e.seq > items[items.length - 1].seq)
      return { status: 200, body: { items, next: more ? String(items[items.length - 1].seq) : null } }
    }

    if (path === '/api/v1/resources') return paged(filterResources(data.resources, params), path, params)
    m = /^\/api\/v1\/resources\/([^/]+)\/lineage$/.exec(path)
    if (m) {
      const id = decodeURIComponent(m[1])
      const resource = data.resources.find((r) => r.id === id)
      if (!resource) return notFound('Resource')
      const byId = new Map(data.resources.map((r) => [r.id, r]))
      const builtFrom = (resource.builtFrom ?? []).map((i) => byId.get(i)).filter((r): r is Resource => !!r)
      const dependents = data.resources.filter((r) => r.builtFrom?.includes(id))
      const lineage: Lineage = { resource, builtFrom, dependents }
      return { status: 200, body: lineage }
    }
    m = /^\/api\/v1\/resources\/([^/]+)$/.exec(path)
    if (m) {
      const id = decodeURIComponent(m[1])
      const resource = data.resources.find((r) => r.id === id)
      return resource ? { status: 200, body: resource } : notFound('Resource')
    }

    if (path === '/api/v1/jobs') return paged(filterJobs(data.jobs, params), path, params)
    m = /^\/api\/v1\/jobs\/([^/]+)$/.exec(path)
    if (m) {
      const id = decodeURIComponent(m[1])
      const job = data.jobs.find((j) => j.id === id)
      if (!job) return notFound('Job')
      const console = data.console?.jobs[id]
      const detail: JobDetail = { ...job, steps: stepsOf(job), input: console?.input ?? { requestedBy: job.requester, channel: job.channel }, outputs: console?.output, error: job.status === 'failed' ? { class: job.errorClass, step: job.failedStep, message: `${job.failedStep ?? 'apply'} failed` } : undefined }
      return { status: 200, body: detail }
    }

    m = /^\/api\/v1\/([a-z0-9-]+)(?:\/([^/]+))?$/.exec(path)
    if (m) {
      const typeName = plurals.get(m[1])
      if (!typeName) return notFound(`Collection ${m[1]}`)
      const ofType = data.resources.filter((r) => r.type === typeName)
      if (!m[2]) return paged(filterResources(ofType, params), path, params)
      const id = decodeURIComponent(m[2])
      const resource = ofType.find((r) => r.id === id)
      return resource ? { status: 200, body: resource } : notFound(`${typeName} ${id}`)
    }

    return notFound(`Path ${path}`)
  }

  return { data, sessions, handle }
}

export { nextMockEvent }
export type { PlatformEvent, IamUser }
