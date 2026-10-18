/**
 * The API contract, typed from docs/api.md. Dates are ISO strings; enums are
 * lowercase strings. The pages and the designer import from here.
 */

/* ----------------------------------------------------------------------------
   Common
   ------------------------------------------------------------------------- */

import type { ParsedStep, StepType, WorkflowDocument } from '@iaf/workflow-checks'

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
/** What a service account may call; `admin` allows everything (docs/api.md, Identity). */
export type ServiceAccountScope = 'registry:read' | 'workflows:validate' | 'workflows:dry-run' | 'workflows:write' | 'jobs:start' | 'jobs:read' | 'providers:write' | 'admin'
/** A named scope set: `invoker` (what an account created without scopes gets), `assistant` (the builder agent's token), `ci`. */
export type ServiceAccountPreset = 'invoker' | 'assistant' | 'ci'
export type ServiceAccount = { id: string; name: string; roles: string[]; teams?: string[]; createdAt: string; scopes?: ServiceAccountScope[] }
/** `scopes` given with a `preset` must fit in it; neither means the `invoker` set. */
export type CreateServiceAccount = { name: string; roles: string[]; teams?: string[]; scopes?: ServiceAccountScope[]; preset?: ServiceAccountPreset }
export type ServiceAccountCreated = { id: string; token: string; scopes?: ServiceAccountScope[] }

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

/**
 * The YAML document as JSON: exactly the `WorkflowDocument` shape of @iaf/workflow-checks
 * (`workflow.do` is a list of `{ "<stepId>": { ... } }` objects). This is what every endpoint
 * means by `definition`; the canvas's parsed model is `ParsedStep` from the same package.
 */
export type { WorkflowDocument, StepDocument, StepBody, SwitchCaseDocument, TriggerDocument, JsonValue, JsonObject } from '@iaf/workflow-checks'
export type { ParsedStep, ParsedWorkflow, StepType } from '@iaf/workflow-checks'
export type WorkflowDefinition = WorkflowDocument
/** The canvas's parsed step record (ids, type, `with`, children), never sent to the API. */
export type Step = ParsedStep

/*
 * The parts of a WorkflowDocument under the names this file used before it took them from
 * @iaf/workflow-checks. Every field is optional now, as in the YAML.
 */
export type { WorkflowMetadata, ApiDocument as WorkflowApi, McpDocument as WorkflowMcp, PolicyDocument as WorkflowPolicy, RolloutDocument as WorkflowRollout, TriggerDocument as WorkflowTrigger, SwitchCaseDocument as SwitchCase } from '@iaf/workflow-checks'
export type StepRetry = { maxAttempts?: number; backoff?: string }

export type Rollout = { waves: number[]; maxParallel: number; stopIfFailedPercent: number }

export type CheckStatus = 'passed' | 'failed' | 'skipped' | 'warning'
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
/** Body of the draft, create, validate and dry-run calls: YAML text or the document as JSON. */
export type WorkflowSource = { yaml: string; definition?: undefined } | { definition: WorkflowDefinition; yaml?: undefined }
export type DraftSaved = { name: string; version: string; problems: Problem[] }
export type RenderResult = { yaml: string }
/** `definition` is null when the YAML does not parse; the problems say why. */
export type ParseResult = { definition: WorkflowDefinition | null; problems: Problem[] }
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
  /** 'required' | 'none' */
  approval?: string | boolean
  input?: Schema | null
  output?: Schema | null
  sample?: unknown
}
export type ProviderType = 'openapi' | 'http' | 'ansible' | 'terraform' | 'idp' | 'sdk'
/** A terraform provider's `spec.module`: where the module lives and the version (or commit) every run uses. */
export type TerraformModuleRef = { source: string; version?: string; ref?: string; commit?: string; credential?: string }
/**
 * `module` is the terraform provider's `spec.module`. GET /registry/providers does not carry it;
 * the designer's registry reads it through the backend (registry.ts, `withTerraformModules`).
 */
export type RegistryProvider = { name: string; title: string; type: ProviderType; owner?: string; actions: RegistryAction[]; module?: TerraformModuleRef }
export type RegistryWorkflow = { name: string; title: string; version: string; input: Schema; output: Schema }
export type RegistryPlaybook = { name: string; version: string; path: string; owner: string }
export type RegistryCredential = { name: string; type: string }

/** POST /playbooks:upload: the stored archive a step refers to as `playbook.upload`. */
export type PlaybookUpload = { file: string; sha256: string; size: number; entries?: string[] }
/** GET /playbooks:resolve: the commit a git ref names right now. */
export type ResolvedRef = { repo: string; ref: string; commit: string }

/** How a workflow is tied to a resource type or to another workflow (GET /registry/dependencies). */
export type DependencyVia = 'producedBy' | 'register' | 'input' | 'use' | 'run' | 'orCreateWith'
export type DependencyLink = {
  workflow: string
  via: DependencyVia
  /** the step id or the input field the link sits on */
  at?: string
  /** a run step's version range, e.g. ^2 */
  range?: string
}
export type ResourceTypeDependencies = { type: string; producers: DependencyLink[]; consumers: DependencyLink[] }
export type WorkflowDependencies = { workflow: string; version: string; produces: string[]; consumes: string[]; runs: DependencyLink[]; consumers: DependencyLink[] }
/** `cycles` are closed walks among runs, e.g. ['a', 'b', 'a']: a runs b, b runs a. */
export type Dependencies = { resourceTypes: ResourceTypeDependencies[]; workflows: WorkflowDependencies[]; cycles: string[][] }

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
  /** The operation still answers, with Deprecation, Sunset and Link headers (docs/api.md, Calls that start jobs). */
  deprecated?: Deprecation
}
/** `since` and `sunset` are dates (YYYY-MM-DD or ISO date-times); `replacement` is the successor's path. */
export type Deprecation = { since: string; sunset: string; replacement?: string; message?: string }
/**
 * What a call that starts jobs sends besides its input: `version` pins the call to exactly that
 * published or retired version (`X-Workflow-Version`, no canary split), `idempotencyKey` makes the
 * same request answer the same job again (`Idempotency-Key`, scoped to the caller).
 */
export type RunOptions = { version?: string; idempotencyKey?: string }
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
export type Attention = { step: string; error?: JobStepError; options: AttentionOption[]; since: string; timeout: string }

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
  /** where the engine's own tools show the job: its execution in the Temporal UI on the temporal engine with a UI URL */
  links?: { temporal?: string }
  /** true when the call pinned `version` (`X-Workflow-Version`, `?version=` or the body's `version`), skipping the canary split; absent when the backend does not say */
  pinned?: boolean
}

export type JobEventType = 'job' | 'step' | 'approval' | 'attention' | 'batch' | 'log'
export type JobEvent = { seq: number; at: string; jobId: string; type: JobEventType; data: Record<string, unknown> }
/** One field that differs: `before` is the compared run's value, `after` this run's. */
export type StepDiff = { field: string; before: unknown; after: unknown }
export type JobReplay = {
  jobId?: string
  workflow?: string
  version?: string
  status?: JobStatus
  steps: (JobStep & { diff?: StepDiff[] })[]
  /** the run compared with: the last successful one of the workflow, or the one asked for */
  comparedTo?: string
  comparedVersion?: string
  comparedStatus?: JobStatus
  /** fields of the input that differ, `input.<field>` */
  inputDiff?: StepDiff[]
}
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
  /** the line a destructive action shows before it runs, when it has no plan action */
  effectDetail?: string
  /** where the sample came from: the Try it call that recorded it */
  recorded?: { environment: string; at: string }
}
/** One 24-hour window, oldest first; `date` is when it starts. A window without calls has no rates. */
export type ProviderDaily = { date: string; calls: number; successRate: number | null; p50Ms: number | null; p95Ms: number | null }
export type ProviderActionStats = {
  action: string
  calls: number
  successRate: number
  p50Ms: number
  p95Ms: number
  topErrors: { class: string; count: number }[]
}
export type ProviderDetail = ProviderSummary & {
  /** base URL per environment */
  endpoints: Record<string, string>
  /** how it signs in; the credential is a vault: name, never a value */
  auth?: ProviderAuth
  description?: string
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
  /** the commit a spec read from Git came from; it goes in the file's spec.commit */
  commit?: string
}
/** A file in a Git repository: https `repo`, `ref` a branch, tag or commit (default main), `credential` the `vault:` reference of a read token. */
export type GitRef = { repo: string; ref?: string; path?: string; credential?: string }
export type SpecImportRequest = { type: 'openapi'; url?: string; content?: string; git?: GitRef }
/** POST /providers:import-ansible: the playbooks of a repository at a ref, as an ansible provider draft. */
export type AnsibleImportRequest = { git: GitRef; playbooks?: string[]; name?: string; owner?: string; credential?: string; executionEnvironment?: string }
export type ImportedPlaybook = {
  path: string
  action: string
  title: string
  effect: Effect
  roles: string[]
  vars: Schema
  /** where each input came from */
  sources: Record<string, string>
  /** variables kept out of vars: the runner gives them from Vault */
  secrets: string[]
  description?: string
  notes: string[]
}
export type AnsibleImport = { repo: string; ref: string; commit: string; playbooks: ImportedPlaybook[]; chosen: string[]; requirements: string[]; skipped: string[]; notes: string[]; yaml: string }
/** POST /providers:import-terraform: a module from Git or from the module registry, as a terraform provider draft. */
export type TerraformImportRequest = { git?: GitRef; registry?: { module: string; version?: string; credential?: string }; name?: string; owner?: string; credential?: string }
export type TerraformVariable = { name: string; type: string; required: boolean; sensitive: boolean; secret: boolean; description?: string; default?: unknown; file: string }
export type TerraformOutput = { name: string; description?: string; sensitive: boolean; file: string }
export type TerraformImport = { source: string; version: string; commit?: string; notes: string[]; yaml: string; variables: TerraformVariable[]; outputs: TerraformOutput[] }
/** One of the two imports, as the wizard reviews it. */
export type ProviderImport = { kind: 'ansible'; result: AnsibleImport } | { kind: 'terraform'; result: TerraformImport }
/** `provider` (the provider's name) keeps the results as review evidence for its change request. */
export type TestConnectionRequest = { endpoints: Record<string, string>; auth: ProviderAuth; provider?: string }
export type TestConnectionResult = { results: { environment: string; ok: boolean; durationMs: number; message: string }[] }
export type TryRequest = { yaml: string; action: string; environment: string; input: Record<string, unknown> }
export type TryResult = {
  ok: boolean
  status: number
  durationMs: number
  response: unknown
  mismatches: { field: string; expected: unknown; actual: unknown }[]
  verified?: boolean
  environment?: string
  at?: string
  /** the draft with the response saved as the action's sample, when the call went through */
  yaml?: string
}
/** What the review suggests as the SLO, from the tried calls; never enforced. */
export type SloSuggestion = { successRate: number; p95Ms: number; observedP95Ms: number; calls: number; detail: string }
/** The review of a provider file against the wizard's own evidence (test connections, tries), as the change request shows it. */
export type ProviderReview = { ready: boolean; checks: Check[]; problems: Problem[]; slo?: SloSuggestion | null }
/** `review` is null when the file is not a provider. */
export type ProviderValidation = { checks: Check[]; problems: Problem[]; review?: ProviderReview | null }
export type ProviderDraft = { yaml: string; summary: string; assumptions: string[] }
export type OverrideRequest = { action: string; response: Record<string, unknown> }
/** POST /providers/{name}:refresh-spec: read the spec again, at `ref` for a Git spec or from `content` for a pasted one. */
export type RefreshSpecRequest = { ref?: string; content?: string }

/* ----------------------------------------------------------------------------
   Releases
   ------------------------------------------------------------------------- */

export type ReleaseStatus = 'canary' | 'paused' | 'promoted' | 'rolled-back'
export type ReleaseGate = { id: string; label: string; passed: boolean | null; value?: number | string; threshold?: number | string; detail: string }
/** What GET /releases lists without a name: a kind of release (`workflow` is the default). */
export type ReleaseKind = 'workflow' | 'provider' | 'all'
export type ReleaseFilter = { workflow?: string; provider?: string; kind?: ReleaseKind }
/** A release rolls out a workflow version (`workflow`) or a provider version (`provider`); a provider release's gates count calls. */
export type Release = {
  id: string
  workflow?: string
  provider?: string
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
export type SealStatus = 'verified' | 'mismatch' | 'unverifiable'
export type AuditVerification = {
  valid: boolean
  entries: number
  brokenAt?: number
  seal?: SealStatus
  seals?: { kind: 'checkpoint' | 'seal'; at: string; seq?: number; keyId: string; status: SealStatus }[]
}
/** GET /audit/platform: the commands that are not about a job (publishes, change requests, provider changes, service accounts...). */
export type PlatformAuditFilter = { from?: string; to?: string; aggregate?: string; actor?: string }
/** The platform stream, oldest first; each entry's `data` carries `event`, `aggregate` and `aggregateId`. */
export type PlatformStream = { entries: EvidenceEntry[]; total: number; latestHash: string; verification: AuditVerification }
export type ShareLink = { url: string; expiresAt: string }
export type SealResult = { sealedAt: string; signature: string }

/* ----------------------------------------------------------------------------
   Notifications
   ------------------------------------------------------------------------- */

export type NotificationKind = 'notify' | 'provider.alert' | 'approval.requested' | 'approval.escalated'
export type NotificationSeverity = 'info' | 'warning' | 'critical'
/** One message for people: `link` is a path in the UI (`/jobs/job_123`); `read` is the caller's own. */
export type Notification = {
  id: string
  at: string
  kind: NotificationKind
  severity: NotificationSeverity
  title: string
  message: string
  link?: string
  channel?: string
  jobId?: string
  workflow?: string
  provider?: string
  users: string[]
  teams: string[]
  read: boolean
}
/** GET /notifications: what was addressed to the caller or one of their teams, newest first. */
export type Inbox = { items: Notification[]; unread: number }

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

/** `owner` is the team that may use it in the wizard and the imports; none means any team (seeded from content). */
export type Credential = { name: string; type: string; path: string; owner?: string; updatedAt: string }
/** `owner` is one of the caller's teams (an admin may name any); the caller's first team when omitted. */
export type CreateCredential = { name: string; type: string; value: string; owner?: string }

/* ----------------------------------------------------------------------------
   Assistant (natural-language builder)
   ------------------------------------------------------------------------- */

export type AssistantMode = 'create' | 'edit'
export type AssistantSessionRequest = { mode: AssistantMode; workflow?: string; draftYaml?: string }
export type AssistantSessionCreated = { sessionId: string }
export type AssistantEventType = 'status' | 'draft' | 'message' | 'validation' | 'done'
/** One event of the message stream: the SSE event name is the type and the SSE data the payload (see the payloads below). */
export type AssistantEvent = { type: AssistantEventType; data: unknown }
/** `status`: what the assistant is doing, with the agent's own session id when there is one. */
export type AssistantStatusData = { status: string; externalId?: string }
/** `message`: the assistant's prose (an explanation, a question, what it did). */
export type AssistantMessageData = { role: 'assistant'; text: string }
/** `done`: the end of the stream; `failed` with the reason when the assistant could not finish. */
export type AssistantDoneData = { status: 'done' | 'failed'; error?: string }
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
/** The `validation` event: the platform's checks on the draft, on their steps, as the designer lists them. */
export type AssistantValidation = { valid: boolean; problems: Problem[] }
/** A turn of the exchange as the session keeps it. */
export type AssistantTurn = { role: 'user' | 'assistant'; text: string; at: string }
export type AssistantSessionStatus = 'idle' | 'working' | 'done' | 'failed'
/** `GET /assistant/sessions/{id}`: the backend's SessionView; `workflow` only in edit mode, `draft` once there is one. */
export type AssistantSession = {
  sessionId: string
  mode: AssistantMode
  workflow?: string
  status: AssistantSessionStatus
  draft?: AssistantDraft
  history: AssistantTurn[]
  createdAt: string
  updatedAt: string
}

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
