import { useSyncExternalStore } from 'react'
import type {
  ActuatorHealth,
  ActuatorInfo,
  AlertPayload,
  AnsibleImport,
  AnsibleImportRequest,
  Approval,
  ApprovalStatus,
  AssistantEvent,
  AssistantEventType,
  AssistantSession,
  AssistantSessionCreated,
  AssistantSessionRequest,
  AuditFilter,
  AuditSummary,
  AuditVerification,
  BatchAccepted,
  BatchRequest,
  BatchRowProblem,
  CatalogEntry,
  ChangeRequest,
  ChangeRequestDetail,
  ChangeRequestStatus,
  CommandAccepted,
  CreateCredential,
  CreateServiceAccount,
  Credential,
  Dependencies,
  DraftSaved,
  DryRunResult,
  EventPayload,
  EvidenceRecord,
  ImportResult,
  ImportSource,
  Inbox,
  JobDetail,
  JobEvent,
  JobReplay,
  JobStatus,
  JobSummary,
  JsonRpcResponse,
  Lineage,
  Me,
  Notification,
  OverrideRequest,
  ParseResult,
  PlatformAuditFilter,
  PlatformStream,
  PlaybookUpload,
  Preview,
  Problem,
  ProviderAlert,
  ProviderDetail,
  ProviderDraft,
  ProviderSummary,
  ProviderValidation,
  PublishRequest,
  RefreshSpecRequest,
  RegistryCredential,
  RegistryPlaybook,
  RegistryProvider,
  RegistryWorkflow,
  Release,
  ReleaseFilter,
  RenderResult,
  ReplayCheck,
  RerunRequest,
  ResolvedRef,
  Resource,
  ResourceFilter,
  ResourceType,
  RetryRequest,
  RunAccepted,
  RunOptions,
  SealResult,
  ServiceAccount,
  ServiceAccountCreated,
  ShareLink,
  Slo,
  SpecImport,
  SpecImportRequest,
  StepTypeInfo,
  Strategy,
  Template,
  TerraformImport,
  TerraformImportRequest,
  TestConnectionRequest,
  TestConnectionResult,
  TriggerResult,
  TryRequest,
  TryResult,
  ValidationResult,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowSource,
  WorkflowStatus,
  WorkflowSummary,
  WorkflowVersion,
  WorkflowVersionDetail,
} from './types'

export const API_BASE = '/api/v1'
/** The OpenAPI 3.1 document of every published workflow and resource type. */
export const OPENAPI_URL = `${API_BASE}/openapi.json`
/** Swagger UI over that document; outside /api/v1, so the page and its assets load without a caller. */
export const SWAGGER_UI_URL = '/swagger-ui'
export const USER_STORAGE_KEY = 'iaf-user'

/* ----------------------------------------------------------------------------
   Dev identity: the X-User header. null means "no header" (the backend default).
   ------------------------------------------------------------------------- */

let currentUser: string | null | undefined
const userListeners = new Set<() => void>()

export function getCurrentUser(): string | null {
  if (currentUser === undefined) {
    try {
      currentUser = localStorage.getItem(USER_STORAGE_KEY)
    } catch {
      currentUser = null
    }
  }
  return currentUser ?? null
}

export function setCurrentUser(id: string | null) {
  currentUser = id && id.trim() ? id.trim() : null
  try {
    if (currentUser) localStorage.setItem(USER_STORAGE_KEY, currentUser)
    else localStorage.removeItem(USER_STORAGE_KEY)
  } catch {
    /* storage unavailable: the choice lives for this session */
  }
  userListeners.forEach((l) => l())
}

function subscribeUser(listener: () => void) {
  userListeners.add(listener)
  return () => {
    userListeners.delete(listener)
  }
}

/** The dev user the client acts as (reactive). */
export function useCurrentUser(): string | null {
  return useSyncExternalStore(subscribeUser, getCurrentUser, () => null)
}

/* ----------------------------------------------------------------------------
   Errors
   ------------------------------------------------------------------------- */

export interface ApiErrorBody {
  error?: string
  problems?: Problem[]
  rows?: BatchRowProblem[]
  detail?: unknown
  /** a 500's trace id (its `error` is always `unexpected error`), which finds the cause in the API's log */
  correlationId?: string
  /** a 429's wait in seconds */
  retryAfter?: number
  [key: string]: unknown
}

/** Every failed call throws one of these; status 0 means the backend did not answer. */
export class ApiError extends Error {
  readonly status: number
  readonly error: string
  readonly problems?: Problem[]
  readonly rows?: BatchRowProblem[]
  readonly detail?: unknown
  readonly body: ApiErrorBody | null
  /** seconds until a 429 (over the team's job-start quota) may be retried: the Retry-After header, else the body's `retryAfter` */
  readonly retryAfter?: number
  /** a 500's correlation id: the request's trace id, which finds the cause in the API's log */
  readonly correlationId?: string

  constructor(status: number, body: ApiErrorBody | null, fallback?: string, retryAfter?: number) {
    const error = body?.error ?? fallback ?? (status === 0 ? 'Backend unreachable' : `Request failed (${status})`)
    super(error)
    this.name = 'ApiError'
    this.status = status
    this.error = error
    this.problems = body?.problems
    this.rows = body?.rows
    this.detail = body?.detail
    this.body = body
    this.retryAfter = retryAfter ?? (typeof body?.retryAfter === 'number' ? body.retryAfter : undefined)
    this.correlationId = typeof body?.correlationId === 'string' && body.correlationId ? body.correlationId : undefined
  }

  get unreachable(): boolean {
    return this.status === 0
  }

  /** over the quota: the caller may try again after `retryAfter` seconds */
  get tooManyRequests(): boolean {
    return this.status === 429
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

/** "try again in 42 s", or a longer wait in minutes; nothing when the wait is unknown. */
export function retryIn(seconds: number | undefined): string | null {
  if (seconds === undefined || !Number.isFinite(seconds)) return null
  const s = Math.max(0, Math.ceil(seconds))
  if (s < 90) return `try again in ${s} s`
  return `try again in ${Math.ceil(s / 60)} min`
}

/** A fresh Idempotency-Key for one form submission: a UUID, scoped to the caller by the backend. */
export function newIdempotencyKey(): string {
  const c = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}-${Math.random().toString(16).slice(2, 10)}`
}

/** The headers of a call that starts jobs: the pinned version and the idempotency key, when given. */
export function runHeaders(opts?: RunOptions): Record<string, string> {
  const h: Record<string, string> = {}
  if (opts?.version) h['X-Workflow-Version'] = opts.version
  if (opts?.idempotencyKey) h['Idempotency-Key'] = opts.idempotencyKey
  return h
}

/** A one-line message for any thrown value. */
export function errorMessage(e: unknown): string {
  // an unexpected error says which request it was, so the API's log can find the cause
  if (isApiError(e)) return e.correlationId ? `${e.error} (correlation id ${e.correlationId})` : e.error
  if (e instanceof Error) return e.message
  return String(e)
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

function resolveUrl(path: string): string {
  if (/^https?:\/\//.test(path) || path.startsWith('/api/') || path.startsWith('/actuator')) return path
  return `${API_BASE}${path}`
}

/** Accept + X-User (when a dev user is chosen) + anything extra. */
export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { Accept: 'application/json' }
  const user = getCurrentUser()
  if (user) h['X-User'] = user
  return { ...h, ...extra }
}

export interface RequestOptions {
  signal?: AbortSignal
  headers?: Record<string, string>
}

async function fail(res: Response): Promise<never> {
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
  throw new ApiError(res.status, body, res.statusText || undefined, retryAfter)
}

async function send(method: string, path: string, body: unknown, opts: RequestOptions, accept?: string): Promise<Response> {
  const headers = authHeaders({ ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(accept ? { Accept: accept } : {}), ...opts.headers })
  try {
    return await fetch(resolveUrl(path), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: opts.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, null)
  }
}

/** JSON in, JSON out; throws ApiError on anything but 2xx. */
export async function request<T>(method: string, path: string, body?: unknown, opts: RequestOptions = {}): Promise<T> {
  const res = await send(method, path, body, opts)
  if (!res.ok) return fail(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    return text as unknown as T
  }
}

/** A multipart POST (uploads): the browser sets the boundary, so no Content-Type here. */
export async function requestForm<T>(path: string, form: FormData, opts: RequestOptions = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(resolveUrl(path), { method: 'POST', headers: authHeaders(opts.headers), body: form, signal: opts.signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, null)
  }
  if (!res.ok) return fail(res)
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** A file response (exports). */
export async function requestBlob(method: string, path: string, body?: unknown, opts: RequestOptions = {}): Promise<Blob> {
  const res = await send(method, path, body, opts, '*/*')
  if (!res.ok) return fail(res)
  return res.blob()
}

const get = <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, undefined, opts)
const post = <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('POST', path, body ?? {}, opts)
const put = <T>(path: string, body: unknown, opts?: RequestOptions) => request<T>('PUT', path, body, opts)
const del = <T>(path: string, opts?: RequestOptions) => request<T>('DELETE', path, undefined, opts)

/** Fills `{param}` placeholders from the input; returns the path and the input keys it did not use. */
export function fillPath(path: string, input: Record<string, unknown>): { path: string; rest: Record<string, unknown> } {
  const used = new Set<string>()
  const filled = path.replace(/\{([^}]+)\}/g, (_, key: string) => {
    used.add(key)
    const v = input[key]
    return v === undefined || v === null ? '' : enc(String(v))
  })
  const rest: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(input)) if (!used.has(k)) rest[k] = v
  return { path: filled, rest }
}

/* ----------------------------------------------------------------------------
   Streams: SSE (EventSource with reconnect), SSE over POST
   ------------------------------------------------------------------------- */

export interface Subscription {
  close: () => void
}

export interface SubscribeOptions<T = unknown> {
  onOpen?: () => void
  onError?: (e: Event) => void
  /** named events to listen for besides `message` */
  events?: string[]
  /** first reconnect delay; doubles up to 30s */
  retryMs?: number
  /** give up after this many consecutive failures (default: never) */
  maxRetries?: number
  /**
   * The event after which the server ends the stream: when the stream ends right after one, it
   * is over and not reconnected (EventSource would reconnect and replay it again).
   */
  endsAfter?: (event: T) => boolean
}

/**
 * Server-sent events with reconnect. EventSource cannot carry headers, so the
 * dev identity falls back to the backend default on streams.
 */
export function subscribe<T = unknown>(url: string, onEvent: (event: T, raw: MessageEvent) => void, opts: SubscribeOptions<T> = {}): Subscription {
  let es: EventSource | null = null
  let closed = false
  let attempt = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  // whether the last event of this connection is the one the server ends the stream after
  let ended = false

  const close = () => {
    closed = true
    if (timer) clearTimeout(timer)
    es?.close()
  }

  const handle = (ev: Event) => {
    const msg = ev as MessageEvent
    attempt = 0
    let data: unknown = msg.data
    if (typeof msg.data === 'string') {
      try {
        data = JSON.parse(msg.data)
      } catch {
        data = msg.data
      }
    }
    ended = opts.endsAfter?.(data as T) ?? false
    onEvent(data as T, msg)
  }

  const connect = () => {
    if (closed) return
    es = new EventSource(resolveUrl(url))
    es.onmessage = handle
    for (const name of opts.events ?? []) es.addEventListener(name, handle)
    es.onopen = () => {
      attempt = 0
      ended = false
      opts.onOpen?.()
    }
    es.onerror = (e) => {
      // the server ended the stream after its last event: done
      if (ended) return close()
      opts.onError?.(e)
      if (!es || es.readyState !== EventSource.CLOSED) return // the browser retries by itself
      es.close()
      if (closed) return
      if (opts.maxRetries !== undefined && attempt >= opts.maxRetries) return
      const delay = Math.min(30_000, (opts.retryMs ?? 1000) * 2 ** attempt)
      attempt++
      timer = setTimeout(connect, delay)
    }
  }

  connect()
  return { close }
}

/** The events a job's stream ends after (`data.event`): the job finished or was cancelled. */
export function isLastJobEvent(event: JobEvent): boolean {
  const name = event?.data?.event
  return name === 'job.finished' || name === 'job.cancelled'
}

/**
 * Live events of a job: its history replayed first, then its events as they happen. The server
 * ends the stream after the job's last event (a job already over replays its history and ends),
 * and the subscription ends there too instead of replaying it again.
 */
export function jobEvents(id: string, onEvent: (event: JobEvent) => void, opts: SubscribeOptions<JobEvent> = {}): Subscription {
  return subscribe<JobEvent>(`/jobs/${enc(id)}/events`, onEvent, {
    events: ['job', 'step', 'approval', 'attention', 'batch', 'log'],
    endsAfter: isLastJobEvent,
    ...opts,
  })
}

interface SseFrame {
  event?: string
  data: string
  id?: string
}

function parseSseBlock(block: string): SseFrame | null {
  const frame: SseFrame = { data: '' }
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const i = line.indexOf(':')
    const field = i < 0 ? line : line.slice(0, i)
    const value = i < 0 ? '' : line.slice(i + 1).replace(/^ /, '')
    if (field === 'data') data.push(value)
    else if (field === 'event') frame.event = value
    else if (field === 'id') frame.id = value
  }
  if (data.length === 0 && !frame.event) return null
  frame.data = data.join('\n')
  return frame
}

const ASSISTANT_EVENTS: readonly string[] = ['status', 'draft', 'message', 'validation', 'done'] satisfies AssistantEventType[]

/**
 * A frame of the assistant's message stream as an event: the backend names the event type in the
 * SSE `event:` field (a frame without one is a `message`, as in any SSE stream) and sends the payload
 * alone as the data. Null for an event type the page does not know.
 */
export function assistantEventOf(name: string | undefined, data: unknown): AssistantEvent | null {
  const type = name ?? 'message'
  return ASSISTANT_EVENTS.includes(type) ? { type: type as AssistantEventType, data } : null
}

/** POST a body and read the SSE response as it streams (EventSource cannot POST). */
export async function streamSse<T>(path: string, body: unknown, onEvent: (event: T, frame: { event?: string; id?: string }) => void, opts: RequestOptions = {}): Promise<void> {
  const res = await send('POST', path, body, opts, 'text/event-stream')
  if (!res.ok) return fail(res)
  const reader = res.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  const flush = (block: string) => {
    const frame = parseSseBlock(block)
    if (!frame) return
    let parsed: unknown
    try {
      parsed = JSON.parse(frame.data)
    } catch {
      parsed = frame.data
    }
    onEvent(parsed as T, { event: frame.event, id: frame.id })
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx = buffer.indexOf('\n\n')
    while (idx >= 0) {
      flush(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 2)
      idx = buffer.indexOf('\n\n')
    }
  }
  if (buffer.trim()) flush(buffer)
}

/* ----------------------------------------------------------------------------
   The client: one function per endpoint in docs/api.md
   ------------------------------------------------------------------------- */

export const api = {
  /* identity */
  me: () => get<Me>('/me'),
  serviceAccounts: () => get<ServiceAccount[]>('/service-accounts'),
  createServiceAccount: (body: CreateServiceAccount) => post<ServiceAccountCreated>('/service-accounts', body),

  /* workflows */
  workflows: (filter?: { folder?: string; status?: WorkflowStatus; q?: string }) => get<WorkflowSummary[]>(`/workflows${qs(filter)}`),
  workflow: (name: string) => get<WorkflowDetail>(`/workflows/${enc(name)}`),
  workflowVersions: (name: string) => get<WorkflowVersion[]>(`/workflows/${enc(name)}/versions`),
  workflowVersion: (name: string, version: string) => get<WorkflowVersionDetail>(`/workflows/${enc(name)}/versions/${enc(version)}`),
  /** `{ yaml }` or `{ definition }` (the YAML document as JSON). */
  saveDraft: (name: string, source: WorkflowSource) => put<DraftSaved>(`/workflows/${enc(name)}/draft`, source),
  createWorkflow: (source: WorkflowSource) => post<CommandAccepted>('/workflows', source),
  deleteWorkflow: (name: string) => del<void>(`/workflows/${enc(name)}`),
  validateWorkflow: (source: WorkflowSource) => post<ValidationResult>('/workflows:validate', source),
  dryRunWorkflow: (source: WorkflowSource, input: Record<string, unknown>) => post<DryRunResult>('/workflows:dry-run', { ...source, input }),
  /** definition → canonical YAML; the browser never renders YAML itself. */
  renderWorkflow: (source: { definition: WorkflowDefinition }) => post<RenderResult>('/workflows:render', source),
  /** YAML → definition plus the problems the checks find; the browser never parses YAML itself. */
  parseWorkflow: (source: { yaml: string }) => post<ParseResult>('/workflows:parse', source),
  publishWorkflow: (name: string, body: PublishRequest = {}) => post<CommandAccepted>(`/workflows/${enc(name)}:publish`, body),
  replayCheck: (name: string, version: string) => post<ReplayCheck>(`/workflows/${enc(name)}/versions/${enc(version)}:replay-check`),
  changeRequests: (status?: ChangeRequestStatus) => get<ChangeRequest[]>(`/change-requests${qs({ status })}`),
  changeRequest: (id: string) => get<ChangeRequestDetail>(`/change-requests/${enc(id)}`),
  mergeChangeRequest: (id: string) => post<CommandAccepted>(`/change-requests/${enc(id)}:merge`),
  closeChangeRequest: (id: string) => post<CommandAccepted>(`/change-requests/${enc(id)}:close`),
  importWorkflow: (source: ImportSource, ref: string) => post<ImportResult>('/workflows:import', { source, ref }),
  templates: () => get<Template[]>('/templates'),

  /* registry */
  registryStepTypes: () => get<StepTypeInfo[]>('/registry/step-types'),
  registryProviders: () => get<RegistryProvider[]>('/registry/providers'),
  registryWorkflows: (published = true) => get<RegistryWorkflow[]>(`/registry/workflows${qs({ published })}`),
  registrySearchWorkflows: (q: string) => get<WorkflowSummary[]>(`/registry/workflows/search${qs({ q })}`),
  registryResourceTypes: () => get<ResourceType[]>('/registry/resource-types'),
  registryPlaybooks: () => get<RegistryPlaybook[]>('/registry/playbooks'),
  registryCredentials: () => get<RegistryCredential[]>('/registry/credentials'),
  /** who produces and consumes each resource type, which published workflows run which, and the cycles among runs */
  registryDependencies: () => get<Dependencies>('/registry/dependencies'),

  /* playbooks */
  /** the commit a git ref names right now, to pin an ansible step to */
  resolvePlaybook: (params: { repo: string; ref: string; credential?: string }) => get<ResolvedRef>(`/playbooks:resolve${qs(params)}`),
  /** a playbook archive as multipart `file` (+ `path`); 413 when it is larger than the platform allows */
  uploadPlaybook: (file: File, path?: string) => {
    const form = new FormData()
    form.append('file', file, file.name)
    if (path) form.append('path', path)
    return requestForm<PlaybookUpload>('/playbooks:upload', form)
  },

  /* catalog (generated surface) */
  catalog: () => get<CatalogEntry[]>('/catalog'),
  openapi: () => get<Record<string, unknown>>('/openapi.json'),
  /** Invoke a published workflow through its generated endpoint; path params come from the input. `opts` pins the version and keys the submission. */
  run: (entry: Pick<CatalogEntry, 'method' | 'path'>, input: Record<string, unknown>, opts?: RunOptions) => {
    const method = entry.method.toUpperCase()
    const { path, rest } = fillPath(entry.path, input)
    const headers = runHeaders(opts)
    if (method === 'GET' || method === 'HEAD') return get<RunAccepted>(`${path}${qs(rest as QueryParams)}`, { headers })
    return request<RunAccepted>(method, path, input, { headers })
  },
  runBatch: (entry: Pick<CatalogEntry, 'batchPath'>, body: BatchRequest, opts?: RunOptions) => post<BatchAccepted>(entry.batchPath, body, { headers: runHeaders(opts) }),
  /** judged against the pinned version when `opts.version` is given, like the run would be */
  preview: (entry: Pick<CatalogEntry, 'path' | 'previewPath'>, input: Record<string, unknown>, opts?: Pick<RunOptions, 'version'>) =>
    post<Preview>(fillPath(entry.previewPath ?? `${entry.path}:preview`, input).path, input, { headers: runHeaders(opts) }),
  /** Generated resource read endpoints: /api/v1/{plural} and /{plural}/{id}. */
  resourcesOf: (plural: string, params?: QueryParams) => get<Resource[]>(`/${enc(plural)}${qs(params)}`),
  resourceOf: (plural: string, id: string) => get<Resource>(`/${enc(plural)}/${enc(id)}`),
  mcp: (method: string, params?: unknown, id: number | string = 1) => post<JsonRpcResponse>('/mcp', { jsonrpc: '2.0', id, method, params }),

  /* jobs */
  jobs: (filter?: { workflow?: string; status?: JobStatus; limit?: number; parent?: string }) => get<JobSummary[]>(`/jobs${qs(filter)}`),
  job: (id: string) => get<JobDetail>(`/jobs/${enc(id)}`),
  jobReplay: (id: string) => get<JobReplay>(`/jobs/${enc(id)}/replay`),
  jobEvidence: (id: string) => get<EvidenceRecord>(`/jobs/${enc(id)}/evidence`),
  cancelJob: (id: string) => post<CommandAccepted>(`/jobs/${enc(id)}:cancel`),
  retryJob: (id: string, body: RetryRequest = {}, opts?: Pick<RunOptions, 'idempotencyKey'>) => post<CommandAccepted>(`/jobs/${enc(id)}:retry`, body, { headers: runHeaders(opts) }),
  skipJob: (id: string, step: string) => post<CommandAccepted>(`/jobs/${enc(id)}:skip`, { step }),
  abortJob: (id: string) => post<CommandAccepted>(`/jobs/${enc(id)}:abort`),
  rerunJob: (id: string, body: RerunRequest) => post<CommandAccepted>(`/jobs/${enc(id)}:rerun`, body),
  retryFailedRows: (id: string) => post<CommandAccepted>(`/jobs/${enc(id)}:retry-failed-rows`),
  approvals: (status: ApprovalStatus | undefined = 'pending') => get<Approval[]>(`/approvals${qs({ status })}`),
  approve: (id: string, comment?: string) => post<CommandAccepted>(`/approvals/${enc(id)}:approve`, comment ? { comment } : {}),
  reject: (id: string, comment?: string) => post<CommandAccepted>(`/approvals/${enc(id)}:reject`, comment ? { comment } : {}),
  triggerAlert: (payload: AlertPayload) => post<TriggerResult>('/triggers/alerts', payload),
  triggerEvent: (payload: EventPayload) => post<TriggerResult>('/triggers/events', payload),

  /* providers */
  providers: () => get<ProviderSummary[]>('/providers'),
  provider: (name: string) => get<ProviderDetail>(`/providers/${enc(name)}`),
  providerAlerts: (name: string) => get<ProviderAlert[]>(`/providers/${enc(name)}/alerts`),
  importSpec: (body: SpecImportRequest) => post<SpecImport>('/providers:import-spec', body),
  /** an ansible provider drafted from the playbooks of a repository; nothing is saved */
  importAnsible: (body: AnsibleImportRequest) => post<AnsibleImport>('/providers:import-ansible', body),
  /** a terraform provider drafted from a module in Git or the module registry; nothing is saved */
  importTerraform: (body: TerraformImportRequest) => post<TerraformImport>('/providers:import-terraform', body),
  testConnection: (body: TestConnectionRequest) => post<TestConnectionResult>('/providers:test-connection', body),
  tryAction: (body: TryRequest) => post<TryResult>('/providers:try', body),
  validateProvider: (yaml: string) => post<ProviderValidation>('/providers:validate', { yaml }),
  /** opens a change request (its id); 409 while another one for the provider is open */
  publishProvider: (yaml: string, strategy?: Strategy) => post<CommandAccepted>('/providers:publish', strategy ? { yaml, strategy } : { yaml }),
  draftProvider: (description: string) => post<ProviderDraft>('/providers:draft', { description }),
  /** "Use the real response": a change request for the next patch version; `status: 'unchanged'` when the override would add nothing */
  overrideAction: (name: string, body: OverrideRequest) => post<CommandAccepted>(`/providers/${enc(name)}/overrides`, body),
  /** a change request for the next patch version with the new SLO; `status: 'unchanged'` when nothing changes */
  setProviderSlo: (name: string, body: Slo) => put<CommandAccepted>(`/providers/${enc(name)}/slo`, body),
  /** reads the spec again; a change request for the next minor version, or `status: 'unchanged'` */
  refreshSpec: (name: string, body: RefreshSpecRequest = {}) => post<CommandAccepted>(`/providers/${enc(name)}:refresh-spec`, body),

  /* releases */
  /** a workflow's or a provider's releases, else every release of `kind` (workflow by default) */
  releases: (filter?: ReleaseFilter) => get<Release[]>(`/releases${qs(filter)}`),
  release: (id: string) => get<Release>(`/releases/${enc(id)}`),
  promoteRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:promote`),
  pauseRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:pause`),
  resumeRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:resume`),
  rollbackRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:rollback`),
  evaluateRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:evaluate`),

  /* audit */
  audit: (filter?: AuditFilter) => get<AuditSummary[]>(`/audit${qs(filter)}`),
  /** the platform stream: commands that are not about a job, oldest first, with its chain verified */
  auditPlatform: (filter?: PlatformAuditFilter) => get<PlatformStream>(`/audit/platform${qs(filter)}`),
  auditRecord: (jobId: string) => get<EvidenceRecord>(`/audit/${enc(jobId)}`),
  verifyAudit: (jobId: string) => get<AuditVerification>(`/audit/${enc(jobId)}:verify`),
  /** href for a download link */
  auditExportUrl: (jobId: string, format: 'json' | 'pdf' = 'json') => `${API_BASE}/audit/${enc(jobId)}/export?format=${format}`,
  exportAudit: (jobId: string, format: 'json' | 'pdf' = 'json') => requestBlob('GET', `/audit/${enc(jobId)}/export?format=${format}`),
  exportAuditPack: (filter: AuditFilter) => requestBlob('POST', '/audit:export-pack', { filter }),
  shareAudit: (jobId: string) => post<ShareLink>(`/audit/${enc(jobId)}:share`),
  sharedAudit: (token: string) => get<EvidenceRecord>(`/audit/shared/${enc(token)}`),
  sealAudit: () => post<SealResult>('/audit:seal'),

  /* notifications */
  /** the caller's inbox, newest first; `unread` true keeps only what they have not read */
  notifications: (unread?: boolean) => get<Inbox>(`/notifications${qs({ unread })}`),
  markNotificationRead: (id: string) => post<Notification>(`/notifications/${enc(id)}:read`),

  /* resources */
  resourceTypes: () => get<ResourceType[]>('/resource-types'),
  resources: (filter?: ResourceFilter) => get<Resource[]>(`/resources${qs(filter)}`),
  resource: (id: string) => get<Resource>(`/resources/${enc(id)}`),
  resourceLineage: (id: string) => get<Lineage>(`/resources/${enc(id)}/lineage`),
  /** 409 with `detail` listing dependents when something is built from it */
  retireResource: (id: string) => post<CommandAccepted>(`/resources/${enc(id)}:retire`),

  /* credentials */
  credentials: () => get<Credential[]>('/credentials'),
  createCredential: (body: CreateCredential) => post<CommandAccepted>('/credentials', body),

  /* assistant */
  createAssistantSession: (body: AssistantSessionRequest) => post<AssistantSessionCreated>('/assistant/sessions', body),
  /** streams the events of one message until `done`: the SSE event name is the type, the data the payload */
  sendAssistantMessage: (sessionId: string, text: string, onEvent: (event: AssistantEvent) => void, opts?: RequestOptions) =>
    streamSse<unknown>(
      `/assistant/sessions/${enc(sessionId)}/messages`,
      { text },
      (data, frame) => {
        const event = assistantEventOf(frame.event, data)
        if (event) onEvent(event)
      },
      opts,
    ),
  /** the session as the backend keeps it: mode, workflow, the latest draft and the exchange so far */
  assistantSession: (id: string) => get<AssistantSession>(`/assistant/sessions/${enc(id)}`),

  /* platform */
  health: async (): Promise<ActuatorHealth> => {
    try {
      return await get<ActuatorHealth>('/actuator/health')
    } catch (e) {
      // actuator answers 503 with the same body when a component is down
      if (isApiError(e) && e.status === 503 && e.body && typeof e.body.status === 'string') return e.body as unknown as ActuatorHealth
      throw e
    }
  },
  info: () => get<ActuatorInfo>('/actuator/info'),
}

export type Api = typeof api
