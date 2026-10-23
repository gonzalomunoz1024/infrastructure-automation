import type { JobDetail, JobStep, JobSummary, Lineage, PlatformEvent, Resource } from '../lib/types'
import { buildMockData, nextMockEvent, type MockData } from './data'

/*
 * The platform's read endpoints answered from the dataset, as a pure function of the request so
 * the unit tests can call it and the browser can serve it through a fetch interceptor. It speaks
 * the contracts the console codes against: ETags on the descriptors (304 on If-None-Match),
 * cursor paging with a Link header (P-05), regions (P-07), the events log (section 11).
 */

export interface MockRequest {
  method: string
  /** path and query, e.g. '/api/v1/vms?limit=50' */
  url: string
  /** header names in lower case */
  headers: Record<string, string>
}

export interface MockResponse {
  status: number
  body?: unknown
  headers?: Record<string, string>
}

export interface MockServer {
  data: MockData
  handle: (req: MockRequest) => MockResponse
}

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

export function createMockServer(data: MockData = buildMockData()): MockServer {
  const plurals = new Map(data.resourceTypes.map((t) => [t.plural, t.name]))

  const handle = (req: MockRequest): MockResponse => {
    const url = new URL(req.url, 'http://mock.local')
    const path = url.pathname.replace(/\/+$/, '') || '/'
    const params = url.searchParams
    const method = req.method.toUpperCase()
    if (method !== 'GET' && method !== 'HEAD') return { status: 405, body: { error: `${method} is not served by the mock; write operations arrive with phase 3` } }

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
      const detail: JobDetail = { ...job, steps: stepsOf(job), input: { requestedBy: job.requester, channel: job.channel }, error: job.status === 'failed' ? { class: job.errorClass, step: job.failedStep, message: `${job.failedStep ?? 'apply'} failed` } : undefined }
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

  return { data, handle }
}

export { nextMockEvent }
export type { PlatformEvent }
