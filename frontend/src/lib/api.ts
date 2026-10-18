import { useSyncExternalStore } from 'react'
import type {
  ActuatorHealth,
  ActuatorInfo,
  AlertPayload,
  Approval,
  ApprovalStatus,
  AssistantEvent,
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
  JobDetail,
  JobEvent,
  JobReplay,
  JobStatus,
  JobSummary,
  JsonRpcResponse,
  Lineage,
  Me,
  OverrideRequest,
  ParseResult,
  Preview,
  Problem,
  ProviderAlert,
  ProviderDetail,
  ProviderDraft,
  ProviderSummary,
  ProviderValidation,
  PublishRequest,
  RegistryCredential,
  RegistryPlaybook,
  RegistryProvider,
  RegistryWorkflow,
  Release,
  RenderResult,
  ReplayCheck,
  RerunRequest,
  Resource,
  ResourceFilter,
  ResourceType,
  RetryRequest,
  RunAccepted,
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

  constructor(status: number, body: ApiErrorBody | null, fallback?: string) {
    const error = body?.error ?? fallback ?? (status === 0 ? 'Backend unreachable' : `Request failed (${status})`)
    super(error)
    this.name = 'ApiError'
    this.status = status
    this.error = error
    this.problems = body?.problems
    this.rows = body?.rows
    this.detail = body?.detail
    this.body = body
  }

  get unreachable(): boolean {
    return this.status === 0
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

/** A one-line message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (isApiError(e)) return e.error
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
  throw new ApiError(res.status, body, res.statusText || undefined)
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
   Streams: SSE (EventSource with reconnect), SSE over POST, WebSocket
   ------------------------------------------------------------------------- */

export interface Subscription {
  close: () => void
}

export interface SubscribeOptions {
  onOpen?: () => void
  onError?: (e: Event) => void
  /** named events to listen for besides `message` */
  events?: string[]
  /** first reconnect delay; doubles up to 30s */
  retryMs?: number
  /** give up after this many consecutive failures (default: never) */
  maxRetries?: number
}

/**
 * Server-sent events with reconnect. EventSource cannot carry headers, so the
 * dev identity falls back to the backend default on streams.
 */
export function subscribe<T = unknown>(url: string, onEvent: (event: T, raw: MessageEvent) => void, opts: SubscribeOptions = {}): Subscription {
  let es: EventSource | null = null
  let closed = false
  let attempt = 0
  let timer: ReturnType<typeof setTimeout> | undefined

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
    onEvent(data as T, msg)
  }

  const connect = () => {
    if (closed) return
    es = new EventSource(resolveUrl(url))
    es.onmessage = handle
    for (const name of opts.events ?? []) es.addEventListener(name, handle)
    es.onopen = () => {
      attempt = 0
      opts.onOpen?.()
    }
    es.onerror = (e) => {
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
  return {
    close: () => {
      closed = true
      if (timer) clearTimeout(timer)
      es?.close()
    },
  }
}

/** Live events of a job (history replayed first). */
export function jobEvents(id: string, onEvent: (event: JobEvent) => void, opts: SubscribeOptions = {}): Subscription {
  return subscribe<JobEvent>(`/jobs/${enc(id)}/events`, onEvent, {
    events: ['job', 'step', 'approval', 'attention', 'batch', 'log'],
    ...opts,
  })
}

export interface JobSocketHandlers {
  onEvent: (event: JobEvent) => void
  onOpen?: () => void
  onClose?: (ev: CloseEvent) => void
  onError?: (ev: Event) => void
  /** reconnect with backoff until closed by the caller (default true) */
  reconnect?: boolean
}

export interface JobSocket {
  close: () => void
  send: (data: unknown) => void
  readonly state: () => 'connecting' | 'open' | 'closed'
}

/** The WebSocket twin of jobEvents: ws(s)://host/ws/jobs/{id}. */
export function jobSocket(id: string, handlers: JobSocketHandlers): JobSocket {
  const proto = typeof location !== 'undefined' && location.protocol === 'https:' ? 'wss' : 'ws'
  const host = typeof location !== 'undefined' ? location.host : 'localhost:3000'
  const url = `${proto}://${host}/ws/jobs/${enc(id)}`
  let ws: WebSocket | null = null
  let closed = false
  let attempt = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  const connect = () => {
    if (closed) return
    ws = new WebSocket(url)
    ws.onopen = () => {
      attempt = 0
      handlers.onOpen?.()
    }
    ws.onmessage = (ev) => {
      try {
        handlers.onEvent(JSON.parse(String(ev.data)) as JobEvent)
      } catch {
        /* not JSON: ignore */
      }
    }
    ws.onerror = (ev) => handlers.onError?.(ev)
    ws.onclose = (ev) => {
      handlers.onClose?.(ev)
      if (closed || handlers.reconnect === false) return
      const delay = Math.min(30_000, 1000 * 2 ** attempt)
      attempt++
      timer = setTimeout(connect, delay)
    }
  }

  connect()
  return {
    close: () => {
      closed = true
      if (timer) clearTimeout(timer)
      ws?.close()
    },
    send: (data) => {
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(typeof data === 'string' ? data : JSON.stringify(data))
    },
    state: () => (closed || !ws || ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING ? 'closed' : ws.readyState === WebSocket.OPEN ? 'open' : 'connecting'),
  }
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

  /* catalog (generated surface) */
  catalog: () => get<CatalogEntry[]>('/catalog'),
  openapi: () => get<Record<string, unknown>>('/openapi.json'),
  /** Invoke a published workflow through its generated endpoint; path params come from the input. */
  run: (entry: Pick<CatalogEntry, 'method' | 'path'>, input: Record<string, unknown>) => {
    const method = entry.method.toUpperCase()
    const { path, rest } = fillPath(entry.path, input)
    if (method === 'GET' || method === 'HEAD') return get<RunAccepted>(`${path}${qs(rest as QueryParams)}`)
    return request<RunAccepted>(method, path, input)
  },
  runBatch: (entry: Pick<CatalogEntry, 'batchPath'>, body: BatchRequest) => post<BatchAccepted>(entry.batchPath, body),
  preview: (entry: Pick<CatalogEntry, 'path' | 'previewPath'>, input: Record<string, unknown>) =>
    post<Preview>(fillPath(entry.previewPath ?? `${entry.path}:preview`, input).path, input),
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
  retryJob: (id: string, body: RetryRequest = {}) => post<CommandAccepted>(`/jobs/${enc(id)}:retry`, body),
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
  testConnection: (body: TestConnectionRequest) => post<TestConnectionResult>('/providers:test-connection', body),
  tryAction: (body: TryRequest) => post<TryResult>('/providers:try', body),
  validateProvider: (yaml: string) => post<ProviderValidation>('/providers:validate', { yaml }),
  publishProvider: (yaml: string, strategy?: Strategy) => post<CommandAccepted>('/providers:publish', strategy ? { yaml, strategy } : { yaml }),
  draftProvider: (description: string) => post<ProviderDraft>('/providers:draft', { description }),
  overrideAction: (name: string, body: OverrideRequest) => post<CommandAccepted>(`/providers/${enc(name)}/overrides`, body),
  setProviderSlo: (name: string, body: Slo) => put<CommandAccepted>(`/providers/${enc(name)}/slo`, body),

  /* releases */
  releases: (workflow?: string) => get<Release[]>(`/releases${qs({ workflow })}`),
  release: (id: string) => get<Release>(`/releases/${enc(id)}`),
  promoteRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:promote`),
  pauseRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:pause`),
  resumeRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:resume`),
  rollbackRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:rollback`),
  evaluateRelease: (id: string) => post<CommandAccepted>(`/releases/${enc(id)}:evaluate`),

  /* audit */
  audit: (filter?: AuditFilter) => get<AuditSummary[]>(`/audit${qs(filter)}`),
  auditRecord: (jobId: string) => get<EvidenceRecord>(`/audit/${enc(jobId)}`),
  verifyAudit: (jobId: string) => get<AuditVerification>(`/audit/${enc(jobId)}:verify`),
  /** href for a download link */
  auditExportUrl: (jobId: string, format: 'json' | 'pdf' = 'json') => `${API_BASE}/audit/${enc(jobId)}/export?format=${format}`,
  exportAudit: (jobId: string, format: 'json' | 'pdf' = 'json') => requestBlob('GET', `/audit/${enc(jobId)}/export?format=${format}`),
  exportAuditPack: (filter: AuditFilter) => requestBlob('POST', '/audit:export-pack', { filter }),
  shareAudit: (jobId: string) => post<ShareLink>(`/audit/${enc(jobId)}:share`),
  sharedAudit: (token: string) => get<EvidenceRecord>(`/audit/shared/${enc(token)}`),
  sealAudit: () => post<SealResult>('/audit:seal'),

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
  /** streams `{ type, data }` events until `done` */
  sendAssistantMessage: (sessionId: string, text: string, onEvent: (event: AssistantEvent) => void, opts?: RequestOptions) =>
    streamSse<AssistantEvent | unknown>(`/assistant/sessions/${enc(sessionId)}/messages`, { text }, (parsed, frame) => {
      const e = parsed as Partial<AssistantEvent>
      if (e && typeof e === 'object' && typeof e.type === 'string') onEvent(e as AssistantEvent)
      else onEvent({ type: (frame.event as AssistantEvent['type']) ?? 'message', data: parsed })
    }, opts),
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
