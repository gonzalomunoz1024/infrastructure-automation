/*
 * The platform shapes the console reads. The ones the kit shares are re-exported from it; the
 * rest are the console's own views of the platform's answers.
 */

export type { CatalogEntry, ConsoleHints, JobStatus, JobStep, JobSummary, Lineage, LifecycleClass, Problem, Resource, ResourceType, Schema, XConsole } from '@iaf/console-kit'
import type { JobStatus, JobStep, JobSummary } from '@iaf/console-kit'

/** GET /api/v1/me */
export interface Me {
  id: string
  name: string
  email?: string
  roles?: string[]
  teams?: string[]
  admin?: boolean
}

/** A region as the console keeps it (P-07): the file's metadata and spec flattened. */
export interface Region {
  name: string
  title: string
  partition: string
  status?: string
  environments?: string[]
}

export interface JobDetail extends JobSummary {
  steps?: JobStep[]
  input?: Record<string, unknown>
  outputs?: Record<string, unknown>
  children?: JobSummary[]
  error?: { class?: string; message?: string; step?: string }
}

/** The event envelope of the platform feed (platform-changes, section 11). */
export interface PlatformEvent {
  specVersion?: string
  dataVersion?: string
  id: string
  key?: string
  seq: number
  type: string
  at: string
  aggregate?: string
  aggregateId?: string
  actor?: string
  onBehalfOf?: string
  context?: { accountId?: string; region?: string; wfrn?: string; consoleRequestId?: string }
  traceparent?: string
  data?: unknown
}

/** One page of a list (P-05): the rows and the cursor of the next page, if any. */
export interface Page<T> {
  items: T[]
  next: string | null
}

export interface Health {
  status: string
  components?: Record<string, { status: string }>
}

/* ----------------------------------------------------------------------------
   The BFF's own shapes (hub: contracts/openapi/console-bff.yaml) and the identity service's,
   served through /console/v1/iam/* (console-identity-service.yaml)
   ------------------------------------------------------------------------- */

/** An account the signed-in person belongs to, with what they hold there. */
export interface AccountSummary {
  accountId: string
  name: string
  status?: string
  defaultRegion?: string
  owningTeam?: string
  /** the managed roles the person holds in the account */
  roles?: string[]
  /** the groups the person is in */
  groups?: string[]
}

export interface BffRegion {
  name: string
  title?: string
  status: string
  environments: string[]
  types?: string[]
}

export type DenialLayer = 'console-iam' | 'platform'

/** Why an action was refused, and by whom: on every 403 but the CSRF one, and on each type action the person may not run. */
export interface Denial {
  /** who refused: the account's own policies, or the platform's role for the account */
  layer: DenialLayer
  /** '<service>:<verb>' */
  action: string
  /** the wfrn or pattern that was checked */
  resource?: string
  /** plain words */
  reason: string
  /** the role evaluated (console-iam) */
  role?: string
  /** the policy whose statement decided (console-iam) */
  policyId?: string
  /** that statement's sid (console-iam) */
  statement?: string
  /** the platform scope that was missing (platform) */
  scope?: string
  /** the platform service account bound to the account (platform) */
  serviceAccount?: string
  /** what in console IAM refused it: a policy, the role's permission boundary or a guardrail (phase 7) */
  refusedBy?: 'policy' | 'boundary' | 'guardrail'
  /** the guardrail that refused it (phase 7) */
  guardrailId?: string
  /** Request access is offered */
  requestable: boolean
  /** the managed role that would allow it */
  suggestedRole?: string
}

export interface BffConsoleAction {
  verb: string
  title: string
  description?: string
  method?: string
  destructive?: boolean
  approval?: boolean
  bulk?: boolean
  preview?: boolean
  effect?: string
  workflow?: string
  version?: string
  /** the signed-in person may run this verb on the type in this account and region */
  allowed?: boolean
  denied?: Denial
  /** the BFF's first spelling of a refusal, before the Denial block */
  deniedReason?: string
}

/** A type as the BFF composes it; the console reads the actions' decisions from it in phase 1. */
export interface BffConsoleType {
  key: string
  type: string
  service: string
  plural?: string
  title?: string
  actions: BffConsoleAction[]
  etag?: string
  [key: string]: unknown
}

export interface IamUser {
  userId: string
  email: string
  name?: string
  status: 'active' | 'suspended'
  /** the identity provider */
  issuer?: string
  subject?: string
  groups?: string[]
  version: number
  createdAt?: string
  lastSignInAt?: string
}

export interface IamGroupInput {
  name?: string
  description?: string
  roles?: string[]
  members?: string[]
  /** identity provider groups whose members join this group at sign-in */
  idpGroups?: string[]
}

export interface IamGroup extends IamGroupInput {
  groupId: string
  name: string
  version: number
  builtIn?: boolean
}

export interface IamRole {
  roleId: string
  name: string
  description?: string
  policies?: string[]
  /** the role's permission boundary: a policy id (phase 7) */
  boundary?: string
  version: number
  builtIn?: boolean
}

export interface IamStatement {
  sid?: string
  effect: 'allow' | 'deny'
  /** '<service>:<verb>' with `*` allowed */
  actions: string[]
  /** actions carved out of an allow (the identity service's extension), so a role can grant everything but deletes without a deny that would also override the person's other roles */
  notActions?: string[]
  /** wfrn patterns */
  resources: string[]
  conditions?: Record<string, unknown>
}

export interface IamPolicy {
  policyId: string
  name: string
  description?: string
  statements: IamStatement[]
  version: number
  builtIn?: boolean
  updatedAt?: string
}

export interface AccessKey {
  /** the public part, `ck_` and a ULID */
  keyId: string
  userId: string
  description?: string
  status: 'active' | 'rotating' | 'revoked' | 'expired'
  createdAt: string
  expiresAt?: string
  lastUsedAt?: string
}

export interface AccessKeyCreated extends AccessKey {
  /** shown once and never stored in clear */
  secretPart: string
  /** after a rotation: when the previous secret part stops working */
  graceUntil?: string
}

export interface Quota {
  key: string
  limit: number
  burst?: number
  used: number
  source?: 'default' | 'override' | 'platform'
  /** an open increase request, followed with GET /console/v1/iam/quota-requests/{requestId} */
  pendingRequestId?: string
}

export type QuotaRequestStatus = 'open' | 'approved' | 'rejected' | 'withdrawn'

/** An ask for a higher limit, which the platform team decides (never the account's own admins). */
export interface QuotaRequest {
  requestId: string
  accountId: string
  quotaKey: string
  currentLimit: number
  requestedLimit: number
  reason: string
  status: QuotaRequestStatus
  /** the limit granted, on approval: the ask by default, possibly less or more */
  grantedLimit?: number
  requestedBy: string
  requestedAt: string
  decidedAt?: string
  decidedBy?: string
  comment?: string
}

export interface QuotaRequestInput {
  /** at least 1, above the current limit */
  limit: number
  /** 10 to 1000 characters */
  reason: string
}

export type AccessRequestStatus = 'pending' | 'approved' | 'rejected' | 'expired'

/** A request for a role, sent from a refusal to the account's admins. */
export interface AccessRequest {
  requestId: string
  accountId: string
  requester: { userId: string; email?: string; name?: string }
  action: string
  resource?: string
  role: string
  reason?: string
  status: AccessRequestStatus
  requestedAt: string
  decidedAt?: string
  decidedBy?: string
  comment?: string
  /** who may decide it, sorted: the account's admins and the members of groups granted access-request:decide */
  approvers?: string[]
  /** which layer refused, when the request came from a refusal */
  layer?: DenialLayer
}

export interface AccessRequestInput {
  action: string
  resource?: string
  role?: string
  reason: string
  layer?: DenialLayer
}

/** One person the demo identity provider knows (mock mode and the BFF's demo profile). */
export interface DemoPerson {
  id: string
  name: string
  email: string
  memberships: { accountId: string; accountName: string; roles: string[] }[]
}

/* ----------------------------------------------------------------------------
   The BFF's resource rows (console-bff.yaml ConsoleResource), search and preferences; the
   resource page's detail, previews, activity, drift and jobs follow below
   ------------------------------------------------------------------------- */

/** The state service's lifecycle state of a resource. */
export type ResourceState = 'pending' | 'active' | 'updating' | 'deleting' | 'deleted' | 'failed'

export const RESOURCE_STATES: readonly ResourceState[] = ['pending', 'active', 'updating', 'deleting', 'deleted', 'failed']

/** A resource named by its wfrn, in previews, lineage and findings. */
export interface ResourceRef {
  wfrn: string
  type: string
  name?: string
  state?: ResourceState | string
}

/** A resource as `GET /console/v1/r/{type}` lists it: the state service's row, the account's view. */
export interface ConsoleResource {
  wfrn: string
  type: string
  service: string
  name?: string
  region?: string
  state: ResourceState
  version: number
  attributes: Record<string, unknown>
  /** what the provider last reported, when it differs from the desired attributes */
  observed?: Record<string, unknown>
  drift?: boolean
  tags: Record<string, string>
  createdAt: string
  updatedAt: string
  /** the change in flight or the last one, as the BFF summarises a job */
  lastJob?: BffJobSummary
}

/** The filters `GET /console/v1/r/{type}` and its `:counts` take: free text, a state, drift and tags. */
export type ResourceFilters = {
  q?: string
  state?: ResourceState
  drift?: boolean
  [tag: `tag.${string}`]: string | undefined
}

/**
 * `GET /console/v1/r/{type}:counts`, with the list's filters: how many resources there are, in
 * each lifecycle state and drifting. When exact counting would be slow the counts stop at
 * `cappedAt` and read "1,000+". The console's addition to the contract, asked of the hub on
 * 2026-10-23.
 */
export interface ResourceCounts {
  total: number
  byState: Partial<Record<ResourceState, number>>
  drift: number
  cappedAt?: number | null
}

/** One hit of `GET /console/v1/search`. */
export interface SearchHit {
  wfrn: string
  type: string
  service?: string
  region?: string
  name?: string
  state?: ResourceState
  drift?: boolean
  tags?: Record<string, string>
  score: number
  /** HTML-escaped text with the matches in <em>: split on <em>, never render it as HTML */
  highlights?: Record<string, string>
  updatedAt?: string
}

/** One entry of `GET /console/v1/suggest`; a tag's value is the term to put in the query (`tag:env=prod`). */
export interface Suggestion {
  kind: 'resource' | 'type' | 'tag' | 'wfrn'
  value: string
  label: string
  type?: string
  wfrn?: string
}

/** A filter token as a saved view keeps it (the kit's FilterToken, by value). */
export interface SavedFilter {
  property: string | null
  operator: string
  value: string
}

/** A list the person named: its filters, sort and page size, per type. */
export interface SavedView {
  id: string
  name: string
  filters: SavedFilter[]
  /** a column, `-` for descending, as the BFF sorts */
  sort?: string
  pageSize?: number
  createdAt: string
}

/**
 * The person's preferences (`/console/v1/preferences`). `savedViews` is the console's addition to
 * the contract, asked of the hub on 2026-10-23; the BFF keeps the whole object as sent.
 */
export interface Preferences {
  theme?: 'system' | 'light' | 'dark'
  density?: 'comfortable' | 'compact'
  defaultRegion?: string
  pinnedTypes?: string[]
  /** per type key (`service/type`), the list columns the person chose */
  columns?: Record<string, string[]>
  /** per type key, the views the person saved */
  savedViews?: Record<string, SavedView[]>
  version?: number
}

/* ----------------------------------------------------------------------------
   The resource page, the create wizard and the Actions menu (console-bff.yaml, phase 3): a
   resource's detail, the command bodies, previews, activity, drift findings and jobs
   ------------------------------------------------------------------------- */

export interface ConsoleResourceDetail extends ConsoleResource {
  finding?: Finding
  jobs?: BffJobSummary[]
  builtFrom?: ResourceRef[]
  dependents?: ResourceRef[]
  /** the verbs the signed-in person may run on this resource */
  can: string[]
  observedAt?: string
  /** the provider's own id, kept beside the wfrn */
  providerId?: string
}

/** The body of every create, update and verb call (CommandInput). */
export interface CommandInput {
  input: Record<string, unknown>
  /** a destructive verb needs it */
  confirm?: boolean
  /** up to 1000 characters, kept with the job */
  reason?: string
  /** up to 16 labels kept with the job, values up to 128 characters */
  labels?: Record<string, string>
  /** a create's tags (phase 4): every required tag of the account's tag policy, named by the request itself */
  tags?: Record<string, string>
}

/** What a create, update or verb answers: 202 with the job that carries it out. */
export interface CommandAccepted {
  wfrn: string
  jobId: string
  requestId: string
  approval?: { approvalId?: string; group?: string; status?: string; timeout?: string }
}

/** One step of a preview, with what it will do. */
export interface PreviewStep {
  id: string
  name: string
  effect: 'create' | 'change' | 'delete' | 'read' | 'none' | 'unknown' | string
  detail?: string
  skipped?: true
}

/** What a run will do, touch and cost, and what policy says, judged by the BFF before it starts (hub Preview). */
export interface ConsolePreview {
  workflow?: string
  version?: string
  steps: PreviewStep[]
  affectedResources: ResourceRef[]
  cost?: { monthly: number; currency: string; detail: string }
  policy: { decision: 'allow' | 'deny' | 'needs-approval'; rules?: { id: string; result: string; message: string }[]; engine?: string }
  /** a delete's or a destructive verb's: what depends on the resource (phase 4) */
  blastRadius?: BlastRadius
}

/** One line of a resource's activity trail: a platform event, or a job that touched it. */
export interface ActivityEntry {
  at: string
  kind: 'event' | 'job'
  type?: string
  actor?: string
  job?: BffJobSummary
  data?: unknown
}

/** A drift finding: the fields where what the provider reports differs from what was asked for (the drift service's Finding, through the BFF). */
export interface Finding {
  findingId: string
  wfrn: string
  accountId?: string
  region?: string
  type?: string
  status: 'open' | 'closed'
  severity: 'info' | 'warning' | 'critical'
  /** bumped on every change to the finding: a scan's new fields, a decision */
  version?: number
  /** `ignored`: the field differs but an ignore silences it */
  fields: { path: string; expected: unknown; observed: unknown; ignored?: boolean }[]
  openedAt: string
  observedAt?: string
  closedAt?: string
  resolution?: 'reverted' | 'accepted' | 'ignored' | 'vanished'
  /** who reverted, accepted or ignored it */
  decidedBy?: string
  reason?: string
  /** the update job a revert started; the finding stays open while it runs, and after it failed */
  revertJobId?: string
  revertStatus?: 'running' | 'succeeded' | 'failed'
  /** an ignore's end; without one it lasts until the desired state changes */
  ignoredUntil?: string
  /** what the signed-in person may decide on it */
  can?: ('revert' | 'accept' | 'ignore')[]
}

/** A job as the BFF lists it: the platform's summary with its id spelled `jobId`. */
export interface BffJobSummary {
  jobId: string
  workflow: string
  status: JobStatus | string
  title?: string
  version?: string
  requester?: string
  wfrn?: string
  startedAt?: string
  endedAt?: string
  durationMs?: number
  kind?: 'single' | 'batch' | string
  progress?: { done: number; total: number; failed: number }
  errorClass?: string
  failedStep?: string
}

/** What a person may do with a job (the BFF's `can`): stop it, or steer a step that stopped. */
export type JobSteer = 'cancel' | 'retry' | 'skip' | 'abort'

/** An approval a job waits on, or waited on, with whether this person may decide it. */
export interface BffApproval {
  approvalId: string
  jobId?: string
  workflow?: string
  step?: string
  group?: string
  message?: string
  requestedAt?: string
  timeout?: string
  status: 'pending' | 'approved' | 'rejected' | 'expired' | string
  decidedBy?: string
  decidedAt?: string
  comment?: string
  wfrn?: string
  canDecide?: boolean
}

/** A batch's rollout: cumulative percentages of the rows per wave, how many run at once, and when to stop. */
export interface Rollout {
  waves?: number[]
  maxParallel?: number
  stopIfFailedPercent?: number
}

/** One wave of a batch, as the BFF reports it. */
export interface BatchWave {
  index: number
  /** the cumulative share of the rows released once this wave runs */
  percent: number
  size: number
  status: 'pending' | 'waiting-approval' | 'running' | 'succeeded' | 'failed' | 'stopped' | string
  startedAt?: string
  endedAt?: string
}

/** One row of a batch: the resource it runs on and its own job once started. */
export interface BatchRow {
  index: number
  wfrn?: string
  jobId?: string
  status: string
  error?: string
  wave: number
}

/** The batch part of a batch job (`GET /console/v1/jobs/{jobId}`, phase 4). */
export interface BatchProgress {
  rollout?: Rollout
  waves: BatchWave[]
  rows: BatchRow[]
  /** why the batch stopped early: the failure threshold, a rejection, a cancel */
  stoppedReason?: string
}

export interface BffJobDetail extends BffJobSummary {
  input?: Record<string, unknown>
  output?: Record<string, unknown>
  steps?: JobStep[]
  approvals?: BffApproval[]
  preview?: ConsolePreview
  attention?: { step: string; options: string[]; since: string }
  children?: BffJobSummary[]
  /** what this person may do with it now; the BFF lists the verbs, an early mock answered an object */
  can?: JobSteer[] | Partial<Record<JobSteer | 'rerun', boolean>>
  batch?: BatchProgress
}

/** A retry's fix (`POST /console/v1/jobs/{jobId}:retry`): input merged over the job's own, and the step to run again from. */
export interface RetryFix {
  input?: Record<string, unknown>
  fromStep?: string
}

/** The body of `POST /console/v1/r/{type}:batch` and its preview. */
export interface BatchInput {
  verb: string
  /** 1 to 500 rows: the subject's wfrn for every verb but create, and the row's own input */
  items: { wfrn?: string; input: Record<string, unknown>; tags?: Record<string, string> }[]
  /** input every row shares, under its own */
  defaults?: Record<string, unknown>
  rollout?: Rollout
  /** a destructive verb needs it */
  confirm?: boolean
  reason?: string
  /** a create batch's tags, under each row's own */
  tags?: Record<string, string>
}

export interface BatchAccepted {
  jobId: string
  rows: number
  requestId: string
  /** a create batch's minted names, one per row */
  wfrns?: string[]
}

/** A resource that depends on another, as the blast radius names it. */
export interface Dependent extends ResourceRef {
  id?: string
  /** how many hops from the resource: 1 depends on it directly */
  depth?: number
}

/** What depends on a resource, within a depth, counted across every account (`GET .../blast-radius`). */
export interface BlastRadius {
  resource?: ResourceRef
  total: number
  direct: number
  byAccount: { accountId: string; count: number; current?: boolean }[]
  /** named in the session's account only, nearest first; other accounts are counted */
  dependents: Dependent[]
  edges: { from: string; to: string; via?: string }[]
  depth: number
  /** the walk stopped at 500 dependents */
  truncated?: boolean
}

/** `POST /console/v1/r/{type}:batch:preview`: every row judged before anything runs, and what they reach together. */
export interface BatchPreview {
  verb: string
  items: { index: number; wfrn?: string; name?: string; state?: string; dependents: number; named: Dependent[]; problems: { code: string; message: string; field?: string }[] }[]
  affected: BlastRadius
  policy: ConsolePreview['policy']
}

/** `POST /console/v1/jobs/{jobId}/approvals:decide`: what one decision decided for a request and its rows, and what it left. */
export interface ApprovalsDecided {
  decision: 'approve' | 'reject'
  decided: string[]
  left: { approvalId: string; jobId?: string; reason: string }[]
}
