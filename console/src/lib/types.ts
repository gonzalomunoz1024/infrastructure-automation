/*
 * The platform shapes the console reads. The ones the kit shares are re-exported from it; the
 * rest are the console's own views of the platform's answers.
 */

export type { CatalogEntry, ConsoleHints, JobStatus, JobStep, JobSummary, Lineage, LifecycleClass, Problem, Resource, ResourceType, Schema, XConsole } from '@iaf/console-kit'
import type { JobStep, JobSummary } from '@iaf/console-kit'

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
  version: number
  builtIn?: boolean
}

export interface IamStatement {
  sid?: string
  effect: 'allow' | 'deny'
  /** '<service>:<verb>' with `*` allowed */
  actions: string[]
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
  pendingRequestId?: string
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
  /** who can decide: the account's admins */
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
   The BFF's resource rows (console-bff.yaml ConsoleResource), search and preferences
   ------------------------------------------------------------------------- */

/** The state service's lifecycle state of a resource. */
export type ResourceState = 'pending' | 'active' | 'updating' | 'deleting' | 'deleted' | 'failed'

export const RESOURCE_STATES: readonly ResourceState[] = ['pending', 'active', 'updating', 'deleting', 'deleted', 'failed']

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
  lastJob?: JobSummary
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
  name?: string
  state?: ResourceState
  score: number
  highlights?: Record<string, string>
}

/** One entry of `GET /console/v1/suggest`. */
export interface Suggestion {
  kind: 'resource' | 'type' | 'tag' | 'wfrn'
  value: string
  label: string
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
