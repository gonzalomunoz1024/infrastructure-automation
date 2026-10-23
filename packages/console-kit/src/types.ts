/**
 * The platform shapes the kit renders, as the platform's frontend/src/lib/types.ts spelled them
 * at c9529d5, plus the fields the planned platform changes add (wfrn and account on resources,
 * P-05; `service`, `console`, `observe` and `x-console` on resource types, P-06).
 */

export type ProblemLevel = 'error' | 'warning'
export type ProblemFixKind = 'replace' | 'insert-approval' | 'connect'
export type ProblemFix = { label: string; kind: ProblemFixKind; value?: string }
export type Problem = { level: ProblemLevel; code: string; step?: string; field?: string; message: string; fix?: ProblemFix }

/** The display formats a field may ask for (P-06, `x-console.format`). */
export type XConsoleFormat = 'bytes' | 'gibibytes' | 'duration' | 'percent' | 'cost' | 'date' | 'wfrn' | 'code'
export const X_CONSOLE_FORMATS: readonly XConsoleFormat[] = ['bytes', 'gibibytes', 'duration', 'percent', 'cost', 'date', 'wfrn', 'code']

/** The widgets a field may ask for (P-06, `x-console.widget`). */
export type XConsoleWidget = 'picker' | 'slider' | 'code' | 'key-value'
export const X_CONSOLE_WIDGETS: readonly XConsoleWidget[] = ['picker', 'slider', 'code', 'key-value']

/** The console hints inside a JSON Schema property (P-06, item 4). */
export type XConsole = {
  format?: XConsoleFormat
  /** a list column by default */
  column?: boolean
  /** promoted to an indexed filter */
  searchable?: boolean
  /** masked, never indexed, never in events */
  sensitive?: boolean
  /** editable through the type's update workflow */
  mutable?: boolean
  /** a section on forms and detail pages */
  group?: string
  /** an info panel link */
  help?: string
  widget?: XConsoleWidget
  /** the resource type a `picker` widget lists, when the field has no x-resource */
  pickerType?: string
  /** the ISO 4217 code a `cost` shows (default USD) */
  currency?: string
  [key: string]: unknown
}

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
  'x-console'?: XConsole
  [key: string]: unknown
}

export type Effect = 'create' | 'change' | 'delete' | 'read' | 'none' | 'unknown'
export type CheckStatus = 'passed' | 'failed' | 'skipped' | 'warning' | 'pending'
export type Rollout = { waves: number[]; maxParallel: number; stopIfFailedPercent: number }

/* ----------------------------------------------------------------------------
   Resources and resource types
   ------------------------------------------------------------------------- */

/** The console's lifecycle classes a type's states fold onto (P-06, `console.lifecycle`). */
export type LifecycleClass = 'pending' | 'active' | 'updating' | 'deleting' | 'deleted' | 'failed'
export const LIFECYCLE_CLASSES: readonly LifecycleClass[] = ['pending', 'active', 'updating', 'deleting', 'deleted', 'failed']

/** The `console:` block of a resource type (P-06, item 2), every field optional. */
export type ConsoleHints = {
  title?: string
  icon?: string
  list?: { columns?: string[]; defaultSort?: string }
  detail?: { tabs?: string[] }
  lifecycle?: Record<string, LifecycleClass>
}

/** The `observe:` block of a resource type (P-06, item 3): how drift detection reads the truth. */
export type ObserveSpec = {
  provider: string
  action: string
  input?: Record<string, unknown>
  map?: Record<string, string>
  ignore?: string[]
  interval?: string
  notFound?: 'missing' | 'deleted'
  discover?: { action: string; input?: Record<string, unknown>; items?: string; id?: string }
}

export type ResourceType = {
  name: string
  plural: string
  owner: string
  schema: Schema
  states: string[]
  producedBy?: string
  description?: string
  visibility?: string
  /** P-06: the console's service grouping; defaults to the owner without `team-` */
  service?: string
  console?: ConsoleHints
  observe?: ObserveSpec
}

export type Resource = {
  id: string
  type: string
  name?: string
  status: string
  attributes: Record<string, unknown>
  owner: string
  visibility: string
  createdBy: { jobId: string; workflow: string; version?: string }
  builtFrom: string[]
  createdAt: string
  updatedAt?: string
  /** P-05: the resource's name in the console, minted at request time */
  wfrn?: string
  accountId?: string
  region?: string
  /** P-05: the provider's own id, kept beside the wfrn */
  providerId?: string
}

/** `to` was built from `from`; `via` is the workflow that registered `to`, when known. */
export type LineageEdge = { from: string; to: string; via?: string }
/**
 * `builtFrom` and `dependents` are the direct neighbours; `nodes` and `edges`, when the backend
 * sends them, are the whole graph around the resource in both directions (the resource first).
 */
export type Lineage = { resource: Resource; builtFrom: Resource[]; dependents: Resource[]; nodes?: Resource[]; edges?: LineageEdge[] }

/* ----------------------------------------------------------------------------
   Catalog and previews
   ------------------------------------------------------------------------- */

export type Deprecation = { since: string; sunset: string; replacement?: string; message?: string }

export type CatalogEntry = {
  workflow: string
  title: string
  noun: string
  verb: string
  persona?: string
  method: string
  path: string
  batchPath?: string
  previewPath?: string
  input: Schema
  mcpTool?: string
  owner: string
  version: string
  description?: string
  batch?: { max: number; rollout: Rollout }
  deprecated?: Deprecation
  /** P-08: a verb the author tagged destructive, and the subject input of a verb on one resource */
  destructive?: boolean
  subject?: string
  approval?: { required?: boolean } | boolean
}

export type PolicyDecision = 'allow' | 'deny' | 'needs-approval'
/** A resource a preview touches: the platform names it by id, the BFF by wfrn (hub ResourceRef); either links. */
export type PreviewResource = { id?: string; wfrn?: string; type: string; name?: string; via?: string; state?: string }
export type Preview = {
  workflow?: string
  version?: string
  steps: { id: string; name: string; effect: Effect | string; detail?: string; skipped?: true }[]
  affectedResources: PreviewResource[]
  /** a negative monthly figure is a saving (a delete) */
  cost?: { monthly: number; currency: string; detail?: string }
  policy: { decision: PolicyDecision; rules?: { id: string; result: string; message: string }[]; engine?: 'mock' | 'opa' | string }
}

/* ----------------------------------------------------------------------------
   Jobs (the console's "requests")
   ------------------------------------------------------------------------- */

export type JobStatus = 'queued' | 'running' | 'waiting-approval' | 'needs-attention' | 'succeeded' | 'failed' | 'cancelled' | 'rolled-back'
export type JobChannel = 'catalog' | 'rest' | 'mcp' | 'trigger' | 'batch' | 'console'
export type JobKind = 'single' | 'batch'
export type JobProgress = { done: number; total: number; failed: number }
export type JobSummary = {
  id: string
  workflow: string
  title: string
  version: string
  status: JobStatus | string
  requester: string
  channel: JobChannel | string
  startedAt: string
  endedAt?: string
  durationMs?: number
  parentId?: string
  kind: JobKind
  progress?: JobProgress
  /** P-04: the console context the job ran in */
  accountId?: string
  region?: string
  wfrn?: string
  consoleRequestId?: string
  failedStep?: string
  errorClass?: string
}

export type JobStepStatus = 'pending' | 'running' | 'waiting' | 'succeeded' | 'failed' | 'skipped' | 'compensated'
export type JobStepError = { class: string; message: string; retryable: boolean }
/** The step kinds the platform runs (the workflow document's step types); any other word is drawn as itself. */
export type StepType = string
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
