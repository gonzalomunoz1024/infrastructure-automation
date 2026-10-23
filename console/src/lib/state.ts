import { get, page, qs, type ListParams } from './api'
import { BFF_BASE } from './bff'
import type { ChainCheck, EvidencePack, ResourceEvent, StateEventType, TrailDigest, TrailEntry, TrailKey } from './trail'
import type { ActivityEntry, ConsoleResource, ConsoleResourceDetail, ResourceState } from './types'

/*
 * The state service's reads through the BFF (hub decision 34, contracts/openapi/console-bff.yaml
 * on console-root--bff-clients): the account's activity trail with its digests, keys and evidence
 * pack, a resource's chain check, its revert plan and `?at=` time travel, its lineage, and the
 * resource map. Every body is the state service's own, passed through unchanged. Each read is
 * decided as the resource's `<service>:get` or `:list`; the evidence pack is for the account's
 * admins and auditors only. The words the pages use for events live here too.
 */

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/** A resource as the relationship reads draw it (the state service's ResourceRef). */
export interface StateRef {
  wfrn: string
  type: string
  service?: string
  name?: string
  state: ResourceState
  accountId: string
  region: string
  drift?: { findingId: string; severity?: 'info' | 'warning' | 'critical' }
  observedStatus?: 'found' | 'not_found' | 'error'
}

export interface LineageEdge {
  from: string
  to: string
  via?: string | null
}

/** `GET /console/v1/r/{type}/{wfrn}/lineage`: what it was built from and what was built from it. */
export interface StateLineage {
  resource: StateRef
  builtFrom: StateRef[]
  dependents: StateRef[]
  nodes: StateRef[]
  edges: LineageEdge[]
  unmanaged: string[]
  hidden: number
  truncated: boolean
}

export interface GraphNode extends StateRef {
  tags?: Record<string, string>
  updatedAt?: string
  /** drawn because a map node links to it, not because it matched the filters */
  context?: boolean
  /** what it costs a month, from the cost service: the BFF's addition to the state service's node */
  cost?: { monthly: number; currency: string }
}

/** `GET /console/v1/map`: the account's resources and the lineage between them. */
export interface ResourceGraph {
  nodes: GraphNode[]
  edges: LineageEdge[]
  external: { accountId: string; type: string; direction: 'builtFrom' | 'dependent'; count: number }[]
  unmanaged: string[]
  truncated: boolean
}

export interface RevertPoint {
  version: number
  at: string
  state: ResourceState
}

export type RevertReason = 'resource.notYet' | 'resource.deleted' | 'resource.busy' | 'resource.wasDeleted' | 'resource.wasNotProvisioned' | 'resource.unchanged'

/** `GET /console/v1/r/{type}/{wfrn}/revert-plan?to=`: what bringing it back to then takes. */
export interface RevertPlan {
  wfrn: string
  from: RevertPoint
  to?: RevertPoint
  possible: boolean
  reason?: RevertReason
  changes?: Record<string, unknown>
  tags?: { set: Record<string, string>; removed: string[] }
  fields?: { path: string; now?: unknown; then?: unknown }[]
}

/** The trail's filters: the BFF's window, and the state service's own filters passed through. */
export interface TrailParams extends ListParams {
  from?: string
  to?: string
  type?: StateEventType
  actor?: string
  wfrn?: string
  jobId?: string
}

/** A resource as it was: the state service's record at that instant (read only, so `can` is empty). */
export type ResourceAt = ConsoleResourceDetail

export const stateApi = {
  activity: (params?: TrailParams) => page<TrailEntry>(b('/activity'), params),
  digests: (params?: { from?: string; to?: string } & ListParams) => page<TrailDigest>(b('/activity/digests'), params),
  keys: () => get<{ keys: TrailKey[] }>(b('/activity/keys')),
  evidence: (from: string, to: string) => get<EvidencePack>(`${b('/activity/evidence')}${qs({ from, to })}`),
  verify: (type: string, wfrn: string) => get<ChainCheck>(b(`/r/${enc(type)}/${enc(wfrn)}/activity:verify`)),
  revertPlan: (type: string, wfrn: string, to: string) => get<RevertPlan>(`${b(`/r/${enc(type)}/${enc(wfrn)}/revert-plan`)}${qs({ to })}`),
  at: (type: string, wfrn: string, at: string) => get<ResourceAt>(`${b(`/r/${enc(type)}/${enc(wfrn)}`)}${qs({ at })}`),
  /** the type's resources as they were at `at`: the state service's `GET /v1/resources?at=`, mirrored on the type's list */
  listAt: (type: string, at: string, params?: ListParams) => page<ConsoleResource>(b(`/r/${enc(type)}`), { ...params, at }),
  lineage: (type: string, wfrn: string) => get<StateLineage>(b(`/r/${enc(type)}/${enc(wfrn)}/lineage`)),
  map: (params?: { accountIds?: string[]; type?: string }) => get<ResourceGraph>(`${b('/map')}${qs({ accountIds: params?.accountIds?.join(','), type: params?.type })}`),
  /** a resource's whole log, from its activity: the state events, oldest first */
  async eventsOf(type: string, wfrn: string): Promise<ResourceEvent[]> {
    const out: ResourceEvent[] = []
    let after: string | undefined
    for (let n = 0; n < 20; n++) {
      const p = await page<ActivityEntry>(b(`/r/${enc(type)}/${enc(wfrn)}/activity`), { limit: 200, after })
      for (const entry of p.items) {
        const e = eventOfActivity(entry, wfrn)
        if (e) out.push(e)
      }
      if (!p.next) break
      after = p.next
    }
    return out.sort((a, b) => a.data.version - b.data.version)
  },
}

/** A state event inside a resource's activity: its type, time and actor, and its data with the version. */
export function eventOfActivity(entry: ActivityEntry, wfrn: string): ResourceEvent | null {
  if (entry.kind !== 'event' || !entry.type || !isStateEventType(entry.type)) return null
  const data = entry.data && typeof entry.data === 'object' ? (entry.data as ResourceEvent['data']) : null
  if (!data || typeof data.version !== 'number') return null
  const [, , , region = '', accountId = ''] = wfrn.split(':')
  return { specVersion: '1.0', dataVersion: 1, id: `${wfrn}#${data.version}`, key: `${entry.type}:${wfrn}:${data.version}`, type: entry.type, at: entry.at, aggregate: 'resource', aggregateId: wfrn, actor: entry.actor, context: { accountId, region, wfrn }, data }
}

export function isStateEventType(t: string): t is StateEventType {
  return (EVENT_TYPE_WORDS as Record<string, unknown>)[t] !== undefined
}

/* ----------------------------------------------------------------------------
   The words
   ------------------------------------------------------------------------- */

/** Each event type as the trail's filter and rows say it. */
export const EVENT_TYPE_WORDS: Record<StateEventType, string> = {
  ResourceRequested: 'Requested',
  ProvisioningStarted: 'Work started',
  ProvisioningSucceeded: 'Work succeeded',
  ProvisioningFailed: 'Work failed',
  UpdateRequested: 'Change requested',
  Observed: 'Provider read',
  DriftDetected: 'Drift found',
  DriftResolved: 'Drift settled',
  Tagged: 'Tags changed',
  DeleteRequested: 'Delete requested',
  Deleted: 'Deleted',
  Imported: 'Imported',
  RetryRequested: 'Retry requested',
  Transferred: 'Moved to another account',
}

/** The outcome an event records, for the trail's outcome filter: what the work ended in, or nothing for a request. */
export type Outcome = 'succeeded' | 'failed' | 'requested' | 'recorded'

export function outcomeOf(type: StateEventType): Outcome {
  if (type === 'ProvisioningSucceeded' || type === 'Deleted' || type === 'Imported' || type === 'Transferred' || type === 'DriftResolved') return 'succeeded'
  if (type === 'ProvisioningFailed') return 'failed'
  if (type === 'ResourceRequested' || type === 'UpdateRequested' || type === 'DeleteRequested' || type === 'RetryRequested') return 'requested'
  return 'recorded'
}

export const OUTCOME_WORDS: Record<Outcome, string> = { succeeded: 'Succeeded', failed: 'Failed', requested: 'Requested', recorded: 'Recorded' }

/** The event types that record each outcome, for the server's `type` filter. */
export function typesOfOutcome(outcome: Outcome): StateEventType[] {
  return (Object.keys(EVENT_TYPE_WORDS) as StateEventType[]).filter((t) => outcomeOf(t) === outcome)
}

/** A line saying what an event did, from its data: "cpu 4 → 8", "set owner", "found". */
export function eventSummary(e: Pick<ResourceEvent, 'type' | 'data'>): string | undefined {
  const d = e.data
  const fields = (o: unknown) =>
    o && typeof o === 'object'
      ? Object.entries(o as Record<string, unknown>)
          .map(([k, v]) => `${k} ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
          .join(', ')
      : ''
  switch (e.type) {
    case 'UpdateRequested':
      return fields(d.changes) || undefined
    case 'Tagged': {
      const set = d.set && typeof d.set === 'object' ? Object.keys(d.set as object) : []
      const removed = Array.isArray(d.removed) ? (d.removed as string[]) : []
      return [set.length ? `set ${set.join(', ')}` : '', removed.length ? `removed ${removed.join(', ')}` : ''].filter(Boolean).join('; ') || undefined
    }
    case 'Observed':
      return d.status === 'found' ? 'the provider reports it' : d.status === 'not_found' ? 'the provider does not have it' : 'the provider could not be read'
    case 'DriftDetected':
      return Array.isArray(d.fields) ? `${(d.fields as { path: string }[]).map((f) => f.path.replace(/^\//, '')).join(', ')} differ` : undefined
    case 'DriftResolved':
      return typeof d.resolution === 'string' ? d.resolution : undefined
    case 'ProvisioningFailed':
      return typeof d.message === 'string' ? d.message : undefined
    default:
      return undefined
  }
}

/** Why a revert plan cannot run, in words. */
export function revertReasonWords(reason: RevertReason | undefined, noun: string): string {
  switch (reason) {
    case 'resource.notYet':
      return `The ${noun} did not exist yet at that time.`
    case 'resource.deleted':
      return `The ${noun} is deleted, so nothing can be brought back.`
    case 'resource.busy':
      return `A request is changing the ${noun} now. Wait for it to finish, then try again.`
    case 'resource.wasDeleted':
      return `The ${noun} was deleted at that time, so there is nothing to bring back.`
    case 'resource.wasNotProvisioned':
      return `The ${noun} was not built yet at that time.`
    case 'resource.unchanged':
      return `The ${noun} is already as it was then: nothing to change.`
    default:
      return `The ${noun} cannot be brought back to that time.`
  }
}
