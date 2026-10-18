/**
 * The API contract, typed from docs/api.md. Dates are ISO strings; enums are
 * lowercase strings. The pages and the designer import from here.
 */

/* ----------------------------------------------------------------------------
   Common
   ------------------------------------------------------------------------- */

export type CommandAccepted = { id: string; status: string; links?: Record<string, string> }

export type ProblemLevel = 'error' | 'warning'
export type ProblemFixKind = 'replace' | 'insert-approval' | 'connect'
export type ProblemFix = { label: string; kind: ProblemFixKind; value?: string }
export type Problem = { level: ProblemLevel; code: string; step?: string; field?: string; message: string; fix?: ProblemFix }

/** JSON Schema as the platform uses it: inputs, outputs, resource attributes. */
export type Schema = {
  type?: string | string[]
  title?: string
  description?: string
  properties?: Record<string, Schema>
  required?: string[]
  items?: Schema
  enum?: unknown[]
  default?: unknown
  format?: string
  minimum?: number
  maximum?: number
  pattern?: string
  'x-resource'?: { type: string; orCreateWith?: string }
  [key: string]: unknown
}

export type Effect = 'create' | 'change' | 'delete' | 'read' | 'none' | 'unknown'
export type RetryPolicy = 'safe' | 'unsafe'
export type Strategy = 'canary' | 'all'

/* ----------------------------------------------------------------------------
   Identity
   ------------------------------------------------------------------------- */

export type IdentityKind = 'user' | 'service' | 'system'
export type Me = { id: string; name: string; email?: string; teams: string[]; roles: string[]; admin: boolean; kind?: IdentityKind }
export type ServiceAccount = { id: string; name: string; roles: string[]; teams?: string[]; createdAt: string }
export type CreateServiceAccount = { name: string; roles: string[]; teams?: string[] }
export type ServiceAccountCreated = { id: string; token: string }

/* ----------------------------------------------------------------------------
   Workflows
   ------------------------------------------------------------------------- */

export type WorkflowStatus = 'draft' | 'published'
export type WorkflowSummary = {
  name: string
  title: string
  noun: string
  verb: string
  persona?: string
  folder: string
  owner: string
  tags: string[]
  status: WorkflowStatus
  latestVersion: string
  publishedVersion?: string
  stepCount: number
  updatedAt: string
  mcp: boolean
}

export type WorkflowVersionStatus = 'draft' | 'candidate' | 'published' | 'retired'
export type WorkflowVersion = {
  version: string
  status: WorkflowVersionStatus
  contentHash: string
  publishedAt?: string
  publishedBy?: string
  releaseId?: string
  notes?: string
}

export type StepType =
  | 'provider'
  | 'http'
  | 'ansible'
  | 'terraform'
  | 'idp'
  | 'activity'
  | 'run'
  | 'approval'
  | 'switch'
  | 'fork'
  | 'for'
  | 'try'
  | 'wait'
  | 'set'
  | 'raise'
  | 'use'
  | 'register'
  | 'notify'
  | 'script'

export type StepRetry = { maxAttempts?: number; backoff?: string }
export type SwitchCase = { case?: { when: string; then?: string; do?: Step[] }; default?: string | { do: Step[] } }
export type Step = {
  id: string
  name: string
  type: StepType
  with?: Record<string, unknown>
  output?: { as: string }
  assert?: string
  retry?: StepRetry
  timeout?: string
  pauseOnFailure?: boolean
  position?: { x: number; y: number }
  notes?: string
  /** fork */
  branches?: Step[][]
  /** switch */
  cases?: SwitchCase[]
  /** for, try, switch do-branches */
  children?: Step[]
  [key: string]: unknown
}

export type Rollout = { waves: number[]; maxParallel: number; stopIfFailedPercent: number }
export type WorkflowMetadata = {
  name: string
  title: string
  noun: string
  verb: string
  persona?: string
  owner: string
  folder?: string
  tags?: string[]
  version: string
  description?: string
}
export type WorkflowApi = {
  method?: string
  path?: string
  input: Schema
  response?: { status: number }
  preview?: boolean
  batch?: { max: number; rollout: Rollout }
}
export type WorkflowMcp = { expose: boolean; description?: string }
export type WorkflowPolicy = {
  approval?: { required: boolean; group?: string; timeout?: string }
  rbac?: string[]
  change?: { required: boolean }
}
export type WorkflowTrigger = {
  alert?: { match: Record<string, unknown>; input?: Record<string, unknown> }
  event?: { topic: string; input?: Record<string, unknown> }
}
export type WorkflowRollout = { strategy: Strategy; stages?: number[] }
export type WorkflowDefinition = {
  apiVersion: string
  kind: 'Workflow'
  metadata: WorkflowMetadata
  api: WorkflowApi
  mcp?: WorkflowMcp
  policy?: WorkflowPolicy
  triggers?: WorkflowTrigger[]
  rollout?: WorkflowRollout
  workflow: { do: Step[]; catch?: { do: Step[] }; outputs?: Record<string, string> }
}

export type CheckStatus = 'passed' | 'failed' | 'skipped'
export type Check = { id: string; label: string; status: CheckStatus; detail?: string }

export type ChangeRequestStatus = 'open' | 'merged' | 'closed'
export type ChangeRequest = {
  id: string
  kind: 'workflow' | 'provider'
  name: string
  version: string
  branch: string
  title: string
  author: string
  status: ChangeRequestStatus
  checks: Check[]
  strategy: Strategy
  createdAt: string
  mergedAt?: string
  url?: string
}
export type ChangeRequestDetail = ChangeRequest & { diff: string; yaml: string }

export type WorkflowDetail = WorkflowSummary & {
  yaml: string
  definition: WorkflowDefinition
  versions: WorkflowVersion[]
  problems: Problem[]
  changeRequests: ChangeRequest[]
}
export type WorkflowVersionDetail = { version: string; yaml: string; definition: WorkflowDefinition }
export type DraftSaved = { name: string; version: string; problems: Problem[] }
export type ValidationResult = { valid: boolean; problems: Problem[] }
export type DryRunStep = { id: string; name: string; status: string; input?: unknown; output?: unknown; error?: string }
export type DryRunResult = { passed: boolean; path: string[]; steps: DryRunStep[]; error?: string }
export type ReplayCheck = { passed: boolean; replayed: number; divergences: { jobId: string; step: string; reason: string }[] }
export type ImportSource = 'idp' | 'ansible' | 'terraform' | 'openapi'
export type ImportResult = { yaml: string; notes: string[] }
export type Template = { id: string; title: string; description: string; yaml: string }
export type PublishRequest = { strategy?: Strategy; notes?: string }

/* ----------------------------------------------------------------------------
   Registry (read-only, used by the designer and the assistant)
   ------------------------------------------------------------------------- */

export type StepTypeInfo = { type: StepType; title: string; category: string; schema: Schema; description: string }
export type RegistryAction = {
  id: string
  title: string
  effect: Effect
  retry: RetryPolicy
  approval?: string | boolean
  input: Schema
  output: Schema
  sample?: unknown
}
export type ProviderType = 'openapi' | 'http' | 'ansible' | 'terraform' | 'idp' | 'sdk'
export type RegistryProvider = { name: string; title: string; type: ProviderType; actions: RegistryAction[] }
export type RegistryWorkflow = { name: string; title: string; version: string; input: Schema; output: Schema }
export type RegistryPlaybook = { name: string; version: string; path: string; owner: string }
export type RegistryCredential = { name: string; type: string }

/* ----------------------------------------------------------------------------
   Catalog (generated)
   ------------------------------------------------------------------------- */

export type CatalogEntry = {
  workflow: string
  title: string
  noun: string
  verb: string
  persona?: string
  method: string
  path: string
  batchPath: string
  previewPath?: string
  input: Schema
  mcpTool?: string
  owner: string
  version: string
  description?: string
  batch: { max: number; rollout: Rollout }
}
export type RunAccepted = { jobId: string; status: 'queued'; links: { job: string } }
export type BatchRequest = { items: Record<string, unknown>[]; defaults?: Record<string, unknown>; rollout?: Rollout }
export type BatchAccepted = { jobId: string; rows: BatchRow[] }
export type BatchRowProblem = { index: number; problems: Problem[] }

export type PolicyDecision = 'allow' | 'deny' | 'needs-approval'
export type Preview = {
  workflow: string
  version: string
  steps: { id: string; name: string; effect: Effect; detail?: string; skipped?: true }[]
  affectedResources: { id: string; type: string; name: string; via: string }[]
  cost?: { monthly: number; currency: string; detail: string }
  policy: { decision: PolicyDecision; rules: { id: string; result: string; message: string }[]; engine: 'mock' | 'opa' }
}

export type JsonRpcRequest = { jsonrpc: '2.0'; id: number | string; method: string; params?: unknown }
export type JsonRpcError = { code: number; message: string; data?: unknown }
export type JsonRpcResponse = { jsonrpc: '2.0'; id: number | string | null; result?: unknown; error?: JsonRpcError }

/* ----------------------------------------------------------------------------
   Jobs
   ------------------------------------------------------------------------- */

export type JobStatus =
  | 'queued'
  | 'running'
  | 'waiting-approval'
  | 'needs-attention'
  | 'succeeded'
  | 'failed'
  | 'cancelled'
  | 'rolled-back'
export type JobChannel = 'catalog' | 'rest' | 'mcp' | 'trigger' | 'batch'
export type JobKind = 'single' | 'batch'
export type JobProgress = { done: number; total: number; failed: number }
export type JobSummary = {
  id: string
  workflow: string
  title: string
  version: string
  status: JobStatus
  requester: string
  channel: JobChannel
  startedAt: string
  endedAt?: string
  durationMs?: number
  parentId?: string
  kind: JobKind
  progress?: JobProgress
}

export type JobStepStatus = 'pending' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'skipped' | 'compensated'
export type JobStepError = { class: string; message: string; retryable: boolean }
export type JobStep = {
  id: string
  name: string
  type: StepType
  status: JobStepStatus
  attempt: number
  startedAt?: string
  endedAt?: string
  durationMs?: number
  input?: unknown
  output?: unknown
  error?: JobStepError
  /** for loops: how many items */
  items?: number
}

export type AttentionOption = 'retry' | 'edit-input' | 'skip' | 'abort'
export type Attention = { step: string; error: string; options: AttentionOption[]; since: string; timeout: string }

export type BatchRow = { index: number; input: Record<string, unknown>; jobId?: string; status: string; error?: string }
export type BatchWave = { index: number; percent: number; size: number; status: string; startedAt?: string; endedAt?: string }
export type Batch = { rows: BatchRow[]; waves: BatchWave[]; rollout: Rollout; stoppedReason?: string }

export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'expired'
export type Approval = {
  id: string
  jobId: string
  workflow: string
  step: string
  group: string
  message: string
  requestedAt: string
  timeout: string
  status: ApprovalStatus
  decidedBy?: string
  decidedAt?: string
  comment?: string
  preview?: Preview
}

export type JobDetail = JobSummary & {
  input: Record<string, unknown>
  output?: Record<string, unknown>
  steps: JobStep[]
  approvals: Approval[]
  preview?: Preview
  change?: { id: string; url: string; state: string }
  children?: JobSummary[]
  batch?: Batch
  attention?: Attention
  traceId: string
}

export type JobEventType = 'job' | 'step' | 'approval' | 'attention' | 'batch' | 'log'
export type JobEvent = { seq: number; at: string; jobId: string; type: JobEventType; data: Record<string, unknown> }
export type StepDiff = { field: string; before: unknown; after: unknown }
export type JobReplay = { steps: (JobStep & { diff?: StepDiff[] })[]; comparedTo?: string }
export type RetryRequest = { step?: string; input?: Record<string, unknown> }
export type RerunRequest = { fromStep: string; input?: Record<string, unknown> }

export type AlertPayload = { source: string; labels: Record<string, string>; annotations?: Record<string, string> }
export type EventPayload = { topic: string; payload: unknown }
export type TriggerResult = { started: { workflow: string; jobId: string }[] }

/* ----------------------------------------------------------------------------
   Providers
   ------------------------------------------------------------------------- */

export type HealthStatus = 'healthy' | 'degraded' | 'failing' | 'unknown'
export type Health = {
  status: HealthStatus
  successRate: number
  p50Ms: number
  p95Ms: number
  calls: number
  errorTrend: number[]
  reasons: string[]
}
export type Slo = { successRate: number; p95Ms: number; channel?: string }
export type ProviderSummary = {
  name: string
  title: string
  type: ProviderType
  owner: string
  version: string
  environments: string[]
  actionCount: number
  health: Health
  slo?: Slo
  alerts?: { channel?: string; [key: string]: unknown }
}
export type ActionInfo = {
  id: string
  title: string
  operation: string | { method: string; path: string }
  effect: Effect
  retry: RetryPolicy
  approval?: string | boolean
  plan?: string
  input: Schema
  output: Schema
  sample?: unknown
  overridden: boolean
}
export type ProviderDaily = { date: string; calls: number; successRate: number; p50Ms: number; p95Ms: number }
export type ProviderActionStats = {
  action: string
  calls: number
  successRate: number
  p50Ms: number
  p95Ms: number
  topErrors: { class: string; count: number }[]
}
export type ProviderDetail = ProviderSummary & {
  yaml: string
  actions: ActionInfo[]
  daily: ProviderDaily[]
  perAction: ProviderActionStats[]
  changeRequests: ChangeRequest[]
}
export type ProviderAlert = { id: string; at: string; action: string; message: string; severity: string; jobId?: string }
export type ProviderAuth = { type: 'bearer' | 'basic' | 'header' | 'none'; credential?: string; header?: string; where?: string }
export type SpecOperation = {
  operationId: string
  method: string
  path: string
  summary: string
  effect: Effect
  retry: RetryPolicy
  input: Schema
  output: Schema
}
export type SpecImport = {
  title: string
  version: string
  sha256: string
  auth: { type: string; where?: string }
  servers: string[]
  operations: SpecOperation[]
}
export type SpecImportRequest = { type: 'openapi'; url?: string; content?: string }
export type TestConnectionRequest = { endpoints: Record<string, string>; auth: ProviderAuth }
export type TestConnectionResult = { results: { environment: string; ok: boolean; durationMs: number; message: string }[] }
export type TryRequest = { yaml: string; action: string; environment: string; input: Record<string, unknown> }
export type TryResult = {
  ok: boolean
  status: number
  durationMs: number
  response: unknown
  mismatches: { field: string; expected: unknown; actual: unknown }[]
  verified?: boolean
}
export type ProviderValidation = { checks: Check[]; problems: Problem[] }
export type ProviderDraft = { yaml: string; summary: string; assumptions: string[] }
export type OverrideRequest = { action: string; response: Record<string, unknown> }

/* ----------------------------------------------------------------------------
   Releases
   ------------------------------------------------------------------------- */

export type ReleaseStatus = 'canary' | 'paused' | 'promoted' | 'rolled-back'
export type ReleaseGate = { id: string; label: string; passed: boolean | null; value?: number | string; threshold?: number | string; detail: string }
export type Release = {
  id: string
  workflow: string
  from?: string
  to: string
  strategy: Strategy
  status: ReleaseStatus
  stageIndex: number
  stages: number[]
  stagePercent: number
  startedAt: string
  stageStartedAt: string
  endedAt?: string
  gates: ReleaseGate[]
  jobs: { current: number; candidate: number }
  history: { at: string; event: string; by?: string }[]
}

/* ----------------------------------------------------------------------------
   Audit
   ------------------------------------------------------------------------- */

export type EvidenceKind = 'requested' | 'preview' | 'policy' | 'change' | 'approval' | 'step' | 'changed' | 'closed' | 'decision'
export type EvidenceEntry = { seq: number; at: string; kind: EvidenceKind; actor?: string; data: Record<string, unknown>; hash: string; previous: string }
export type EvidenceRecord = {
  jobId: string
  workflow: string
  version: string
  requester: string
  channel: JobChannel
  entries: EvidenceEntry[]
  latestHash: string
  sealed?: { at: string; signature: string; keyId: string }
  retention: { until: string }
  change?: { id: string; url: string }
}
export type AuditSummary = { jobId: string; workflow: string; requester: string; channel: JobChannel; at: string; entries: number; status: JobStatus }
export type AuditFilter = { workflow?: string; from?: string; to?: string; requester?: string }
export type AuditVerification = { valid: boolean; entries: number; brokenAt?: number }
export type ShareLink = { url: string; expiresAt: string }
export type SealResult = { sealedAt: string; signature: string }

/* ----------------------------------------------------------------------------
   Resources
   ------------------------------------------------------------------------- */

export type ResourceType = { name: string; plural: string; owner: string; schema: Schema; states: string[]; producedBy?: string }
export type Resource = {
  id: string
  type: string
  name?: string
  status: string
  attributes: Record<string, unknown>
  owner: string
  visibility: string
  createdBy: { jobId: string; workflow: string }
  builtFrom: string[]
  createdAt: string
}
export type Lineage = { resource: Resource; builtFrom: Resource[]; dependents: Resource[] }
export type ResourceFilter = { type?: string; owner?: string; status?: string }

/* ----------------------------------------------------------------------------
   Credentials
   ------------------------------------------------------------------------- */

export type Credential = { name: string; type: string; path: string; updatedAt: string }
export type CreateCredential = { name: string; type: string; value: string }

/* ----------------------------------------------------------------------------
   Assistant (natural-language builder)
   ------------------------------------------------------------------------- */

export type AssistantMode = 'create' | 'edit'
export type AssistantSessionRequest = { mode: AssistantMode; workflow?: string; draftYaml?: string }
export type AssistantSessionCreated = { sessionId: string }
export type AssistantEventType = 'status' | 'draft' | 'message' | 'validation' | 'done'
export type AssistantEvent = { type: AssistantEventType; data: unknown }
export type AssistantDraftStatus = 'working' | 'ready' | 'needs_input' | 'failed'
export type AssistantDraft = {
  status: AssistantDraftStatus
  summary: string
  workflowYaml: string
  changes?: string[]
  assumptions?: string[]
  questions?: { question: string; options?: string[] }[]
  missing?: { kind: string; name: string; why: string }[]
  validation: { valid: boolean; rounds?: number; errors?: string[] }
  dryRun?: { passed?: boolean; path?: string[]; error?: string }
}
export type AssistantMessage = { role: 'user' | 'assistant'; text: string; at?: string }
export type AssistantSession = { sessionId: string; status: string; draft?: AssistantDraft; history: AssistantMessage[] }

/* ----------------------------------------------------------------------------
   Platform (actuator)
   ------------------------------------------------------------------------- */

export type ActuatorStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
export type ActuatorHealth = { status: ActuatorStatus; components?: Record<string, { status: string; details?: Record<string, unknown> }> }
export type ActuatorInfo = {
  build?: { version?: string; name?: string; artifact?: string; time?: string }
  git?: { commit?: { id?: string | { abbrev?: string } }; branch?: string }
  [key: string]: unknown
}
