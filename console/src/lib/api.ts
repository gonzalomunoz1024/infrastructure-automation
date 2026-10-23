import { nextCursorFromLink } from '@iaf/console-kit'
import { getCurrentUser } from './identity'
import { parseRegions } from './regions'
import type { CatalogEntry, Health, JobDetail, JobSummary, Lineage, Me, Page, PlatformEvent, Problem, Region, Resource, ResourceType } from './types'

/*
 * The console's API client: fetch with a traceparent on every call, the X-User dev header,
 * JSON errors turned into a typed ApiError, an ETag cache for the descriptors (resource types,
 * catalog, regions) persisted in localStorage, and cursor paging for lists (P-05).
 */

export const API_BASE = '/api/v1'
export const CACHE_PREFIX = 'console-cache:'

/* ----------------------------------------------------------------------------
   Errors
   ------------------------------------------------------------------------- */

export interface ApiErrorBody {
  error?: string
  problems?: Problem[]
  detail?: unknown
  /** a 500's trace id, which finds the cause in the platform's log */
  correlationId?: string
  /** a 429's wait in seconds */
  retryAfter?: number
  [key: string]: unknown
}

/** Every failed call throws one of these; status 0 means the platform did not answer. */
export class ApiError extends Error {
  readonly status: number
  readonly error: string
  readonly problems?: Problem[]
  readonly detail?: unknown
  readonly body: ApiErrorBody | null
  readonly retryAfter?: number
  readonly correlationId?: string
  /** the traceparent the failed request carried */
  readonly traceparent?: string

  constructor(status: number, body: ApiErrorBody | null, fallback?: string, retryAfter?: number, trace?: string) {
    const error = body?.error ?? fallback ?? (status === 0 ? 'Platform unreachable' : `Request failed (${status})`)
    super(error)
    this.name = 'ApiError'
    this.status = status
    this.error = error
    this.problems = body?.problems
    this.detail = body?.detail
    this.body = body
    this.retryAfter = retryAfter ?? (typeof body?.retryAfter === 'number' ? body.retryAfter : undefined)
    this.correlationId = typeof body?.correlationId === 'string' && body.correlationId ? body.correlationId : undefined
    this.traceparent = trace
  }

  get unreachable(): boolean {
    return this.status === 0
  }

  get notFound(): boolean {
    return this.status === 404
  }

  get unauthorized(): boolean {
    return this.status === 401 || this.status === 403
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

/** The seconds a Retry-After header asks for: a delta, or an HTTP date read against `now`. */
export function retryAfterSeconds(header: string | null | undefined, now = Date.now()): number | undefined {
  if (!header) return undefined
  const text = header.trim()
  if (/^\d+$/.test(text)) return Number(text)
  const at = Date.parse(text)
  if (Number.isNaN(at)) return undefined
  return Math.max(0, Math.ceil((at - now) / 1000))
}

/* ----------------------------------------------------------------------------
   Tracing: a W3C traceparent per request (version 00, sampled)
   ------------------------------------------------------------------------- */

function randomHex(bytes: number): string {
  const buf = new Uint8Array(bytes)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') crypto.getRandomValues(buf)
  else for (let i = 0; i < bytes; i++) buf[i] = Math.floor(Math.random() * 256)
  return Array.from(buf, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** "00-<trace id>-<span id>-01": a fresh trace for one request. */
export function traceparent(): string {
  return `00-${randomHex(16)}-${randomHex(8)}-01`
}

/* ----------------------------------------------------------------------------
   Transport
   ------------------------------------------------------------------------- */

export type QueryParams = Record<string, string | number | boolean | null | undefined>

/** "?a=1&b=x" from an object, skipping empty values. */
export function qs(params?: QueryParams): string {
  if (!params) return ''
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

const enc = encodeURIComponent

export function resolveUrl(path: string): string {
  if (/^https?:\/\//.test(path) || path.startsWith('/api/') || path.startsWith('/actuator')) return path
  return `${API_BASE}${path}`
}

/** Accept, traceparent, X-User (when a dev user is chosen) and anything extra. */
export function requestHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { Accept: 'application/json', traceparent: traceparent() }
  const user = getCurrentUser()
  if (user) h['X-User'] = user
  return { ...h, ...extra }
}

export interface RequestOptions {
  signal?: AbortSignal
  headers?: Record<string, string>
}

async function fail(res: Response, trace?: string): Promise<never> {
  let body: ApiErrorBody | null
  try {
    body = (await res.json()) as ApiErrorBody
  } catch {
    body = null
  }
  let retryAfter: number | undefined
  try {
    retryAfter = retryAfterSeconds(res.headers?.get('Retry-After'))
  } catch {
    retryAfter = undefined
  }
  throw new ApiError(res.status, body, res.statusText || undefined, retryAfter, trace)
}

async function send(method: string, path: string, body: unknown, opts: RequestOptions): Promise<{ res: Response; trace: string }> {
  const headers = requestHeaders({ ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...opts.headers })
  const trace = headers.traceparent
  try {
    const res = await fetch(resolveUrl(path), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: opts.signal,
    })
    return { res, trace }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, null, undefined, undefined, trace)
  }
}

async function parseBody<T>(res: Response): Promise<T> {
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    return text as unknown as T
  }
}

/** JSON in, JSON out; throws ApiError on anything but 2xx. */
export async function request<T>(method: string, path: string, body?: unknown, opts: RequestOptions = {}): Promise<T> {
  const { res, trace } = await send(method, path, body, opts)
  if (!res.ok) return fail(res, trace)
  return parseBody<T>(res)
}

const get = <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, undefined, opts)

/* ----------------------------------------------------------------------------
   The descriptor cache: ETag revalidation, persisted in localStorage
   ------------------------------------------------------------------------- */

interface Cached<T> {
  etag: string | null
  body: T
  at: number
}

function readCache<T>(key: string): Cached<T> | null {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Cached<T>
    return parsed && typeof parsed === 'object' && 'body' in parsed ? parsed : null
  } catch {
    return null
  }
}

function writeCache<T>(key: string, value: Cached<T>) {
  try {
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(value))
  } catch {
    /* storage unavailable or full: the next call fetches again */
  }
}

/** Forget every cached descriptor (settings, sign-out). */
export function clearDescriptorCache() {
  try {
    const keys: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(CACHE_PREFIX)) keys.push(k)
    }
    keys.forEach((k) => localStorage.removeItem(k))
  } catch {
    /* nothing to clear */
  }
}

/**
 * GET with If-None-Match: a 304 answers from the cache, a fresh body replaces it. When the
 * platform cannot be reached or fails, the last good copy still answers, so the shell keeps
 * drawing its navigation.
 */
export async function cachedGet<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const cached = readCache<T>(path)
  const headers = { ...opts.headers, ...(cached?.etag ? { 'If-None-Match': cached.etag } : {}) }
  let answer: { res: Response; trace: string }
  try {
    answer = await send('GET', path, undefined, { ...opts, headers })
  } catch (e) {
    if (cached && isApiError(e) && e.unreachable) return cached.body
    throw e
  }
  const { res, trace } = answer
  if (res.status === 304 && cached) return cached.body
  if (!res.ok) {
    if (cached && res.status >= 500) return cached.body
    return fail(res, trace)
  }
  const body = await parseBody<T>(res)
  writeCache(path, { etag: res.headers.get('ETag'), body, at: Date.now() })
  return body
}

/* ----------------------------------------------------------------------------
   Paging (P-05): `limit` and `after`; the body is the rows, the next cursor rides the Link header
   ------------------------------------------------------------------------- */

export async function page<T>(path: string, params?: QueryParams, opts: RequestOptions = {}): Promise<Page<T>> {
  const { res, trace } = await send('GET', `${path}${qs(params)}`, undefined, opts)
  if (!res.ok) return fail(res, trace)
  const body = await parseBody<T[] | { items?: T[]; next?: string | null } | undefined>(res)
  const items = Array.isArray(body) ? body : (body?.items ?? [])
  const fromBody = !Array.isArray(body) && typeof body?.next === 'string' && body.next ? body.next : null
  return { items, next: fromBody ?? nextCursorFromLink(res.headers.get('Link')) }
}

/* ----------------------------------------------------------------------------
   The client
   ------------------------------------------------------------------------- */

export interface ListParams extends QueryParams {
  limit?: number
  after?: string
}

export const api = {
  /* identity */
  me: () => get<Me>('/me'),

  /* descriptors, cached by ETag */
  resourceTypes: () => cachedGet<ResourceType[]>('/resource-types'),
  catalog: () => cachedGet<CatalogEntry[]>('/catalog'),
  regions: async (): Promise<Region[]> => parseRegions(await cachedGet<unknown>('/regions')),

  /* resources: the generated read endpoints /api/v1/{plural} and /{plural}/{id}, and the registry */
  resourcesOf: (plural: string, params?: ListParams) => page<Resource>(`/${enc(plural)}`, params),
  resourceOf: (plural: string, id: string) => get<Resource>(`/${enc(plural)}/${enc(id)}`),
  resources: (params?: ListParams) => page<Resource>('/resources', params),
  resource: (id: string) => get<Resource>(`/resources/${enc(id)}`),
  lineage: (id: string) => get<Lineage>(`/resources/${enc(id)}/lineage`),

  /* requests (jobs) */
  jobs: (params?: ListParams) => page<JobSummary>('/jobs', params),
  job: (id: string) => get<JobDetail>(`/jobs/${enc(id)}`),

  /* the platform feed */
  events: (params?: { after?: string | number; limit?: number; types?: string }) => page<PlatformEvent>('/events', params),
  eventsStreamUrl: (after?: string | null) => `${API_BASE}/events/stream${qs({ after })}`,

  /* platform */
  health: async (): Promise<Health> => {
    try {
      return await get<Health>('/actuator/health')
    } catch (e) {
      if (isApiError(e) && e.status === 503 && e.body && typeof e.body.status === 'string') return e.body as unknown as Health
      throw e
    }
  },
}

export type Api = typeof api
