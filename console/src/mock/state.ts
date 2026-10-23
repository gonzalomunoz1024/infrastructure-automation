import { serviceOf } from '../lib/descriptors'
import { CANONICALIZATION, byTrailOrder, chainEvents, checkChain, checkPackHashes, digestHash, digestRoot, eventRef, problemWords, toTrailEntry, type ResourceEvent, type StateEventType, type StoredEvent, type TrailDigest, type TrailEntry, type TrailKey } from '../lib/trail'
import { STATE_EVENT_TYPES } from '../lib/trail'
import { mergePatch, replayAt, type Snapshot } from '../lib/timeTravel'
import { outcomeOf, type GraphNode, type LineageEdge, type ResourceGraph, type RevertPlan, type StateLineage, type StateRef } from '../lib/state'
import type { ActivityEntry, ConsoleResource, ConsoleResourceDetail, Resource, ResourceState, ResourceType } from '../lib/types'
import { buildMockData, type MockData } from './data'
import { accountIdsOf, attributesOf, busyOn, classOf, findingsOf, idFrom, iso, isRecord, pageOf, priceOf, problems400, refOf, refused, resourceByWfrn, stateOfResource, toConsoleResource, typeOf, wfrnOfResource, type RouteAnswer, type RouteContext } from './commands'
import { accountOf, authorize, personOf, rolesOf } from './directory'
import { closedFindingsOf } from './drift'
import { accountOfResource, consoleResourceOf, toBffJob } from './resources'
import { demoKey, signEs256, type SigningKey } from './es256'

/*
 * The state service's trail, time travel, lineage and resource map (hub decision 34 through the
 * BFF; the state service's phase 9 contract underneath), answered from the dataset.
 *
 * Every resource gets a log the state service would have written: its request, the job that
 * built it, the provider's reads, the drift found and settled, the seeded resizes and tag changes
 * of its past, and everything the console's own requests did since. Each log is a real hash chain
 * (SHA-256 over the stored event with the hash of the one before), each account's days are sealed
 * in digests chained to the day before and signed with ES256 by demo keys (one rotated out on
 * 1 October, kept readable), and the evidence pack carries all of it with the rules to check it.
 * One stored event was changed after it was written: the resize of `cache-043` (vm-gen-033) on its
 * third day, its cpu raised after the hash was taken. A check of that resource, or of any window
 * that holds that day, finds it; the last seven days hold.
 *
 * The mock seals a day per digest, where the service seals an hour: the same rules, fewer
 * signatures to draw in the browser. `batch-01` (vm-demo04) was resized up three days ago and
 * back down early today, so yesterday is a size worth going back to.
 */

const DAY_MS = 24 * 3600_000
const HOUR_MS = 3600_000
/** a day is sealed once it has ended and this grace has passed */
const GRACE_MS = 5 * 60_000
/** what the console-internal key rotation left: the key before 1 October, and the one since */
const ROTATED_AT = Date.UTC(2026, 9, 1)
export const TAMPERED_SAMPLE = 'vm-gen-033'
export const REPLAY_SAMPLE = 'vm-demo04'

let keys: { old: SigningKey; current: SigningKey } | undefined
function signingKeys() {
  keys ??= { old: demoKey('trail-2026-09', 'console-mock-trail-september'), current: demoKey('trail-2026-10', 'console-mock-trail-october') }
  return keys
}

export function trailKeys(): TrailKey[] {
  const k = signingKeys()
  return [k.old.jwk, k.current.jwk]
}

/* ----------------------------------------------------------------------------
   The logs
   ------------------------------------------------------------------------- */

interface Draft {
  type: StateEventType
  at: number
  actor?: string
  state: ResourceState
  data: Record<string, unknown>
  requestId?: string
}

/** The attributes each sample was born with, before anything the console ran: a fresh dataset's. */
let pristine: Map<string, Record<string, unknown>> | undefined
function pristineOf(id: string): Record<string, unknown> | undefined {
  pristine ??= new Map(buildMockData().resources.map((r) => [r.id, { ...(r.attributes ?? {}) }]))
  return pristine.get(id)
}

const hashOf = (s: string) => {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return h >>> 0
}

/** The person a team's work is done by in the demo: who resized and tagged the samples. */
function personFor(r: Resource): string {
  const owner = r.owner ?? ''
  if (owner.includes('data')) return 'raj'
  if (owner.includes('platform')) return 'gonzalo'
  return 'mateo'
}

const CPUS = [2, 4, 8, 16]

/** A resize the sample's past holds: when, by whom, and the size it went to. */
interface SeededResize {
  at: number
  actor: string
  reason: string
  changes: Record<string, unknown>
}

/**
 * The resizes in a sample's past, oldest first, ending at the size it has now: batch-01's two
 * (up three days ago, back down early today), and for one machine in three a resize a few days
 * after it was built. Each step is worked out backwards from the size it ends at.
 */
function seededResizes(r: Resource, base: Record<string, unknown>, day0: number, now: number): { born: Record<string, unknown>; resizes: SeededResize[] } {
  const created = Date.parse(r.createdAt)
  if (r.type !== 'vm' || typeof base.cpu !== 'number') return { born: base, resizes: [] }
  if (r.id === REPLAY_SAMPLE) {
    const early = day0 + 5 * 60_000 < now - 1000 ? day0 + 5 * 60_000 : day0 - HOUR_MS
    const up = { cpu: 8, memoryGb: 16 }
    const born = { ...base, cpu: 4, memoryGb: 16 }
    return {
      born,
      resizes: [
        { at: day0 - 3 * DAY_MS + 10 * HOUR_MS + 20 * 60_000, actor: 'mateo', reason: 'Month-end batch needs more room', changes: up },
        { at: early, actor: 'mateo', reason: 'Month-end batch finished; back to the usual size', changes: { cpu: base.cpu, memoryGb: base.memoryGb } },
      ].filter((x) => x.at > created),
    }
  }
  const h = hashOf(r.id)
  if (h % 3 !== 0 && r.id !== TAMPERED_SAMPLE) return { born: base, resizes: [] }
  const at = created + (2 + (h % 3)) * DAY_MS + (h % 7) * HOUR_MS
  if (at >= day0 - DAY_MS || at >= now) return { born: base, resizes: [] }
  const i = CPUS.indexOf(base.cpu)
  const before = CPUS[i > 0 ? i - 1 : i + 1]
  return { born: { ...base, cpu: before }, resizes: [{ at, actor: personFor(r), reason: before < base.cpu ? 'More load than planned' : 'Quieter than planned', changes: { cpu: base.cpu } }] }
}

function personRef(data: MockData, actor: string | undefined): ResourceEvent['onBehalfOf'] | undefined {
  const p = actor ? personOf(data.directory, actor) : undefined
  return p ? { id: `user_${p.id}`, name: p.name, email: p.email } : undefined
}

/** The events of one resource, oldest first, before they are chained. */
function draftsOf(data: MockData, rt: ResourceType, r: Resource, day0: number, now: number): Draft[] {
  const st = data.console
  const drafts: Draft[] = []
  const created = Date.parse(r.createdAt)
  const createJob = data.jobs.find((j) => j.id === r.createdBy?.jobId)
  const createExtra = r.createdBy?.jobId ? st?.jobs[r.createdBy.jobId] : undefined
  const byConsole = !!createExtra
  const requester = createJob?.requester ?? (byConsole ? undefined : personFor(r))
  const workflow = r.createdBy?.workflow ?? `create-${rt.name}`
  const jobId = r.createdBy?.jobId
  const base = byConsole ? attributesOf(rt, createExtra!.input, r.owner ?? '') : (pristineOf(r.id) ?? { ...(r.attributes ?? {}) })
  const { born, resizes } = byConsole || classOf(rt, r.status) !== 'active' ? { born: base, resizes: [] } : seededResizes(r, base, day0, now)
  let desired: Record<string, unknown> = { ...born }
  const observedOf = () => ({ ...desired, status: r.status })

  // the request, the job that built it, and the first read of the provider
  const requestedAt = byConsole ? created : created - 3 * 60_000
  drafts.push({ type: 'ResourceRequested', at: requestedAt, actor: requester, state: 'pending', data: { name: r.name ?? r.id, workflow, input: { ...born }, ...(jobId ? { jobId } : {}) }, requestId: createExtra?.consoleRequestId })
  drafts.push({ type: 'ProvisioningStarted', at: requestedAt + 1000, actor: requester, state: 'pending', data: { jobId, workflow, workflowVersion: r.createdBy?.version } })
  const cls = classOf(rt, r.status)
  const createEnd = byConsole ? (createJob?.endedAt ? Date.parse(createJob.endedAt) : undefined) : cls === 'pending' ? undefined : created
  const createStatus = byConsole ? createJob?.status : cls === 'failed' ? 'failed' : 'succeeded'
  if (createEnd === undefined || !createStatus || ['queued', 'running', 'waiting-approval', 'needs-attention'].includes(createStatus)) return drafts
  if (createStatus !== 'succeeded') {
    drafts.push({ type: 'ProvisioningFailed', at: createEnd, actor: requester, state: 'failed', data: { jobId, workflow, status: createStatus, failedStep: createJob?.failedStep ?? 'apply', errorClass: createJob?.errorClass ?? 'provider', message: 'The provider refused the request' } })
    return drafts
  }
  drafts.push({ type: 'ProvisioningSucceeded', at: createEnd, actor: requester, state: 'active', data: { jobId, workflow, workflowVersion: r.createdBy?.version, registryId: r.id, providerId: r.providerId ?? `${serviceOf(rt)}:${r.id}`, attributes: { ...born }, ...(r.builtFrom?.length ? { builtFrom: [...r.builtFrom] } : {}) } })
  if (!byConsole) drafts.push({ type: 'Observed', at: createEnd + 15 * 60_000, actor: 'drift-service', state: 'active', data: { status: 'found', observed: observedOf(), observedAt: iso(createEnd + 15 * 60_000), provider: rt.observe?.provider, action: rt.observe?.action } })

  // the seeded past: resizes through the update workflow, read back from the provider an hour later
  for (const [i, x] of resizes.entries()) {
    const job = `job_${idFrom(`${r.id}:resize:${i}`, 10)}`
    drafts.push({ type: 'UpdateRequested', at: x.at, actor: x.actor, state: 'updating', data: { jobId: job, workflow: 'update-vm', workflowVersion: '1.0.0', changes: { ...x.changes }, reason: x.reason } })
    drafts.push({ type: 'ProvisioningStarted', at: x.at + 2000, actor: x.actor, state: 'updating', data: { jobId: job, workflow: 'update-vm', workflowVersion: '1.0.0' } })
    desired = { ...desired, ...x.changes }
    drafts.push({ type: 'ProvisioningSucceeded', at: x.at + 8000, actor: x.actor, state: 'active', data: { jobId: job, workflow: 'update-vm', workflowVersion: '1.0.0', registryId: r.id, attributes: { ...desired } } })
    if (x.at + HOUR_MS < now) drafts.push({ type: 'Observed', at: x.at + HOUR_MS, actor: 'drift-service', state: 'active', data: { status: 'found', observed: observedOf(), observedAt: iso(x.at + HOUR_MS), provider: rt.observe?.provider, action: rt.observe?.action } })
  }
  // a cost-centre tag set once on the machines that were resized: a change of tags in the trail
  if (!byConsole && resizes.length && resizes[0].at + DAY_MS < now) drafts.push({ type: 'Tagged', at: resizes[0].at + DAY_MS, actor: resizes[0].actor, state: 'active', data: { set: { 'cost-centre': r.owner?.includes('data') ? 'cc-2210' : 'cc-1140' }, removed: [] } })

  // the drift the scans found and how each finding closed
  const session = { userId: 'drift-service', accountId: accountOfResource(r, accountIdsOf(data)), region: data.regionName }
  for (const f of closedFindingsOf(data, session, rt, r, now)) {
    const opened = Date.parse(f.openedAt)
    const closed = Date.parse(f.closedAt ?? f.openedAt)
    if (opened <= createEnd || closed > now) continue
    drafts.push({ type: 'DriftDetected', at: opened, actor: 'drift-service', state: 'active', data: { findingId: f.findingId, fields: f.fields.map((x) => ({ path: `/${x.path.replace(/^attributes\./, '')}`, expected: x.expected, observed: x.observed })) } })
    drafts.push({ type: 'DriftResolved', at: closed, actor: f.decidedBy ?? 'drift-service', state: 'active', data: { findingId: f.findingId, resolution: f.resolution ?? 'vanished' } })
  }
  const open = classOf(rt, r.status) === 'deleted' ? [] : findingsOf(data, session, rt, r, now)
  for (const f of open) {
    const opened = Math.floor(Date.parse(f.openedAt) / HOUR_MS) * HOUR_MS
    if (opened <= createEnd || opened > now) continue
    drafts.push({ type: 'Observed', at: opened - 60_000, actor: 'drift-service', state: 'active', data: { status: 'found', observed: { ...desired, ...Object.fromEntries(f.fields.map((x) => [x.path, x.observed])), status: r.status }, observedAt: iso(opened - 60_000), provider: rt.observe?.provider, action: rt.observe?.action } })
    drafts.push({ type: 'DriftDetected', at: opened, actor: 'drift-service', state: 'active', data: { findingId: f.findingId, fields: f.fields.map((x) => ({ path: `/${x.path}`, expected: x.expected, observed: x.observed })) } })
  }

  // what the console's requests did: updates and deletes through their jobs, tags as they were set
  if (st) {
    const own = data.jobs.filter((j) => st.jobs[j.id]?.resourceId === r.id && j.id !== jobId).sort((a, b) => a.startedAt.localeCompare(b.startedAt))
    for (const j of own) {
      const extra = st.jobs[j.id]
      const at = Date.parse(j.startedAt)
      const actor = j.requester
      if (extra.verb === 'update') {
        const changes = Object.fromEntries(Object.entries(extra.input).filter(([k]) => k !== 'reason'))
        drafts.push({ type: 'UpdateRequested', at, actor, state: 'updating', data: { jobId: j.id, workflow: j.workflow, changes, ...(extra.reason ? { reason: extra.reason } : {}) }, requestId: extra.consoleRequestId })
        drafts.push({ type: 'ProvisioningStarted', at: at + 1000, actor, state: 'updating', data: { jobId: j.id, workflow: j.workflow } })
        if (!j.endedAt) continue
        if (j.status === 'succeeded') {
          desired = { ...desired, ...changes }
          drafts.push({ type: 'ProvisioningSucceeded', at: Date.parse(j.endedAt), actor, state: 'active', data: { jobId: j.id, workflow: j.workflow, registryId: r.id, attributes: { ...desired } } })
        } else drafts.push({ type: 'ProvisioningFailed', at: Date.parse(j.endedAt), actor, state: 'active', data: { jobId: j.id, workflow: j.workflow, status: j.status === 'cancelled' ? 'cancelled' : 'failed', failedStep: j.failedStep ?? 'apply', message: 'The update did not apply' } })
      } else if (extra.verb === 'delete') {
        drafts.push({ type: 'DeleteRequested', at, actor, state: 'deleting', data: { jobId: j.id, workflow: j.workflow, ...(extra.reason ? { reason: extra.reason } : {}) }, requestId: extra.consoleRequestId })
        if (j.endedAt && j.status === 'succeeded') drafts.push({ type: 'Deleted', at: Date.parse(j.endedAt), actor, state: 'deleted', data: { jobId: j.id, registryId: r.id, tombstoneUntil: iso(Date.parse(j.endedAt) + 90 * DAY_MS) } })
        else if (j.endedAt) drafts.push({ type: 'ProvisioningFailed', at: Date.parse(j.endedAt), actor, state: 'active', data: { jobId: j.id, workflow: j.workflow, status: 'failed', message: 'The delete did not finish' } })
      }
    }
    // the tags before the first change are the row's own, as the list derives them
    let tags: Record<string, string> = { ...consoleResourceOf(r, data, { partition: data.partition, region: data.regionName, accountId: session.accountId }).tags }
    const tagCommands = st.journal.filter((c) => c.kind === 'tags' && c.wfrn === wfrnOfResource(data, session, rt, r))
    for (const c of tagCommands) {
      const next = isRecord(c.body) ? (c.body as Record<string, string>) : {}
      const set = Object.fromEntries(Object.entries(next).filter(([k, v]) => tags[k] !== v))
      const removed = Object.keys(tags).filter((k) => !(k in next))
      if (Object.keys(set).length || removed.length) drafts.push({ type: 'Tagged', at: c.at, actor: c.userId, state: 'active', data: { set, removed } })
      tags = { ...next }
    }
  }
  // a sample retired before the console knew it: its delete, a day after it was last changed
  if (!byConsole && classOf(rt, r.status) === 'deleted' && !drafts.some((d) => d.type === 'Deleted')) {
    const at = Math.max(Date.parse(r.updatedAt ?? r.createdAt), createEnd) + DAY_MS
    if (at < now) {
      drafts.push({ type: 'DeleteRequested', at: at - 10 * 60_000, actor: personFor(r), state: 'deleting', data: { jobId: `job_${idFrom(`${r.id}:retire`, 10)}`, workflow: `delete-${rt.name}`, reason: 'Retired' } })
      drafts.push({ type: 'Deleted', at, actor: personFor(r), state: 'deleted', data: { jobId: `job_${idFrom(`${r.id}:retire`, 10)}`, registryId: r.id, tombstoneUntil: iso(at + 90 * DAY_MS) } })
    }
  }
  // anything the dataset changed that no request above explains (a drift accepted, say) is recorded as the change it was
  const now0 = r.attributes ?? {}
  const drifted = Object.fromEntries(Object.entries(now0).filter(([k, v]) => JSON.stringify(desired[k]) !== JSON.stringify(v)))
  if (Object.keys(drifted).length && classOf(rt, r.status) !== 'deleted') {
    const at = Math.max(Date.parse(r.updatedAt ?? r.createdAt), ...drafts.map((d) => d.at)) + 1000
    drafts.push({ type: 'UpdateRequested', at, actor: 'drift-service', state: 'updating', data: { changes: drifted, reason: 'The provider’s values were accepted as the desired ones' } })
    drafts.push({ type: 'ProvisioningSucceeded', at: at + 1000, actor: 'drift-service', state: 'active', data: { registryId: r.id, attributes: { ...desired, ...drifted } } })
  }
  return drafts.filter((d) => d.at <= now)
}

interface Trail {
  /** every account's events, in time order */
  events: StoredEvent[]
  /** each resource's log, in version order, by wfrn */
  logs: Map<string, StoredEvent[]>
  /** each account's sealed days, oldest first */
  digests: Map<string, TrailDigest[]>
}

const trails = new WeakMap<MockData, { key: string; trail: Trail }>()
const signatures = new Map<string, string>()

function trailKeyOf(data: MockData, now: number): string {
  const st = data.console
  const jobs = st ? Object.keys(st.jobs).map((id) => `${id}:${data.jobs.find((j) => j.id === id)?.status ?? ''}`).join(',') : ''
  const decisions = st?.drift ? JSON.stringify(st.drift).length : 0
  // early in the day the replay sample's second resize waits for its time: read the minute then
  const early = now - Math.floor(now / DAY_MS) * DAY_MS < 6 * 60_000 ? Math.floor(now / 60_000) : 0
  return `${Math.floor(now / HOUR_MS)}|${early}|${st?.journal.length ?? 0}|${jobs}|${data.resources.length}|${decisions}`
}

/** The whole trail, rebuilt when the dataset moved on (a request, a job ending, the hour turning). */
export function trailOf(data: MockData, now: number): Trail {
  const key = trailKeyOf(data, now)
  const kept = trails.get(data)
  if (kept && kept.key === key) return kept.trail
  const day0 = Math.floor(now / DAY_MS) * DAY_MS
  const accounts = accountIdsOf(data)
  const unchained: { wfrn: string; events: ResourceEvent[] }[] = []
  for (const r of data.resources) {
    const rt = typeOf(data, r.type)
    if (!rt) continue
    const accountId = accountOfResource(r, accounts)
    const wfrn = wfrnOfResource(data, { region: data.regionName }, rt, r)
    const drafts = draftsOf(data, rt, r, day0, now)
      .map((d, i) => ({ d, i }))
      .sort((a, b) => a.d.at - b.d.at || a.i - b.i)
      .map((x) => x.d)
    const events = drafts.map((d, i): ResourceEvent => {
      const version = i + 1
      const onBehalfOf = personRef(data, d.actor)
      return {
        specVersion: '1.0',
        dataVersion: 1,
        id: `evt_${idFrom(`${wfrn}#${version}:${d.type}`, 26)}`,
        key: `${d.type}:${wfrn}:${version}`,
        type: d.type,
        at: iso(d.at),
        aggregate: 'resource',
        aggregateId: wfrn,
        ...(d.actor ? { actor: d.actor } : {}),
        ...(onBehalfOf ? { onBehalfOf } : {}),
        context: { accountId, region: data.regionName, wfrn, ...(d.requestId ? { consoleRequestId: d.requestId } : {}) },
        data: { version, state: d.state, resourceType: rt.name, ...d.data },
      }
    })
    unchained.push({ wfrn, events })
  }
  // the outbox's sequence: one order across the service, by time
  const all = unchained.flatMap((x) => x.events).sort(byTrailOrder)
  all.forEach((e, i) => (e.seq = i + 1))
  const logs = new Map<string, StoredEvent[]>()
  for (const x of unchained) logs.set(x.wfrn, chainEvents(x.events))
  tamper(logs)
  const events = [...logs.values()].flat().sort(byTrailOrder)
  const digests = new Map<string, TrailDigest[]>()
  for (const accountId of accounts) digests.set(accountId, sealDays(events.filter((e) => e.context.accountId === accountId), accountId, data.regionName, now))
  const trail = { events, logs, digests }
  trails.set(data, { key, trail })
  return trail
}

/** The seeded break: the tampered sample's first resize, its cpu raised after its hash was taken. */
function tamper(logs: Map<string, StoredEvent[]>) {
  for (const [wfrn, log] of logs) {
    if (!wfrn.endsWith(`/${TAMPERED_SAMPLE}`)) continue
    const i = log.findIndex((e) => e.type === 'UpdateRequested')
    if (i < 0) return
    const e = log[i]
    const changes = isRecord(e.data.changes) ? e.data.changes : {}
    log[i] = { ...e, data: { ...e.data, changes: { ...changes, cpu: 16 } } }
  }
}

/** Each day of an account's events sealed: counted, rooted, chained to the day before and signed. */
function sealDays(events: StoredEvent[], accountId: string, region: string, now: number): TrailDigest[] {
  const byDay = new Map<number, StoredEvent[]>()
  for (const e of events) {
    const day = Math.floor(Date.parse(e.at) / DAY_MS) * DAY_MS
    byDay.set(day, [...(byDay.get(day) ?? []), e])
  }
  const out: TrailDigest[] = []
  let previous: string | undefined
  for (const day of [...byDay.keys()].sort((a, b) => a - b)) {
    if (day + DAY_MS + GRACE_MS > now) break
    const inside = byDay.get(day)!
    const body = { id: `${accountId}:${region}:${iso(day)}`, accountId, region, from: iso(day), to: iso(day + DAY_MS), count: inside.length, firstEvent: eventRef(inside[0]), lastEvent: eventRef(inside[inside.length - 1]), root: digestRoot(inside.map((e) => e.hash ?? '')), ...(previous ? { previous } : {}) }
    const hash = digestHash(body)
    const key = day < ROTATED_AT ? signingKeys().old : signingKeys().current
    let signature = signatures.get(`${key.kid}:${hash}`)
    if (!signature) {
      signature = signEs256(key, hash)
      signatures.set(`${key.kid}:${hash}`, signature)
    }
    out.push({ ...body, hash, keyId: key.kid, signature, signedAt: iso(day + DAY_MS + GRACE_MS) })
    previous = hash
  }
  return out
}

/* ----------------------------------------------------------------------------
   Reading the trail and the logs
   ------------------------------------------------------------------------- */

export function logOf(data: MockData, wfrn: string, now: number): StoredEvent[] {
  return trailOf(data, now).logs.get(wfrn) ?? []
}

/** A glob over wfrns: `*` any run of characters, `?` one. */
function globMatch(pattern: string, value: string): boolean {
  const re = new RegExp(`^${pattern.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.')}$`)
  return re.test(value)
}

const OUTCOMES = new Set(['succeeded', 'failed', 'requested', 'recorded'])

function instant(params: URLSearchParams, name: string): number | undefined | 'bad' {
  const raw = params.get(name)
  if (raw === null || raw === '') return undefined
  const t = Date.parse(raw)
  return Number.isNaN(t) ? 'bad' : t
}

/** The person's own events and every service's, filtered as the state service filters them. */
function trailEntries(data: MockData, accountId: string, params: URLSearchParams, now: number): TrailEntry[] | RouteAnswer {
  const from = instant(params, 'from')
  const to = instant(params, 'to')
  if (from === 'bad' || to === 'bad') return problems400([{ level: 'error', code: 'format', field: from === 'bad' ? '/from' : '/to', message: 'from and to are dates and times' }])
  const type = params.get('type')
  if (type && !(STATE_EVENT_TYPES as readonly string[]).includes(type)) return problems400([{ level: 'error', code: 'enum', field: '/type', message: `type is one of ${STATE_EVENT_TYPES.join(', ')}` }])
  const outcome = params.get('outcome')
  if (outcome && !OUTCOMES.has(outcome)) return problems400([{ level: 'error', code: 'enum', field: '/outcome', message: 'outcome is succeeded, failed, requested or recorded' }])
  const actor = params.get('actor')
  const jobId = params.get('jobId')
  const wfrn = params.get('wfrn')
  return trailOf(data, now)
    .events.filter((e) => e.context.accountId === accountId)
    .filter((e) => (from === undefined || Date.parse(e.at) >= from) && (to === undefined || Date.parse(e.at) < to))
    .filter((e) => (!type || e.type === type) && (!outcome || outcomeOf(e.type) === outcome) && (!actor || e.actor === actor) && (!jobId || e.data.jobId === jobId) && (!wfrn || (wfrn.includes('*') || wfrn.includes('?') ? globMatch(wfrn, e.aggregateId) : e.aggregateId === wfrn)))
    .reverse()
    .map(toTrailEntry)
}

/** Whether the person holds a role in the account that reads the evidence pack. */
function readsEvidence(data: MockData, s: RouteContext['session']): boolean {
  const roles = rolesOf(data.directory, s.accountId, s.userId)
  return roles.includes('account-admin') || roles.includes('auditor')
}

function evidenceRefusal(data: MockData, s: RouteContext['session']): RouteAnswer {
  const account = accountOf(data.directory, s.accountId)
  return {
    status: 403,
    body: {
      error: 'The evidence pack is for the account’s admins and auditors',
      denied: { layer: 'console-iam', action: 'activity:evidence', resource: `wfrn:${data.partition}:*:${s.region}:${s.accountId}:*`, reason: `The evidence pack of ${account?.name ?? 'this account'} is for its admins and auditors; ask for the auditor role to read it.`, role: rolesOf(data.directory, s.accountId, s.userId)[0], requestable: true, suggestedRole: 'auditor' },
      correlationId: `mock-${idFrom(`${s.userId}:evidence`, 8)}`,
    },
  }
}

/* ----------------------------------------------------------------------------
   Lineage and the map
   ------------------------------------------------------------------------- */

function stateRefOf(data: MockData, s: RouteContext['session'], r: Resource, now: number): StateRef {
  const rt = typeOf(data, r.type)
  const ref = refOf(data, s, r)
  const accountId = accountOfResource(r, accountIdsOf(data))
  const finding = rt && classOf(rt, r.status) !== 'deleted' ? findingsOf(data, { ...s, accountId }, rt, r, now)[0] : undefined
  return {
    wfrn: ref.wfrn,
    type: r.type,
    service: rt ? serviceOf(rt) : r.type,
    name: r.name ?? r.id,
    state: (rt ? stateOfResource(data, rt, r) : 'active') as ResourceState,
    accountId,
    region: data.regionName,
    ...(finding ? { drift: { findingId: finding.findingId, severity: finding.severity } } : {}),
    observedStatus: rt?.observe ? 'found' : undefined,
  }
}

const isDeleted = (data: MockData, r: Resource) => {
  const rt = typeOf(data, r.type)
  return !!rt && classOf(rt, r.status) === 'deleted'
}

/** `GET .../lineage`: `builtFrom` walked up and the live resources built from it walked down, `depth` hops each way. */
export function lineageOf(data: MockData, s: RouteContext['session'], r: Resource, now: number, depth = 32, limit = 200): StateLineage {
  const accounts = accountIdsOf(data)
  const byId = new Map(data.resources.map((x) => [x.id, x]))
  const mine = (x: Resource) => accountOfResource(x, accounts) === s.accountId
  const drawn = new Map<string, Resource>([[r.id, r]])
  const hiddenIds = new Set<string>()
  const unmanaged = new Set<string>()
  const edges: LineageEdge[] = []
  let truncated = false
  const wfrnOf = (x: Resource) => refOf(data, s, x).wfrn
  const visit = (x: Resource) => {
    if (!mine(x)) {
      hiddenIds.add(x.id)
      return false
    }
    if (drawn.has(x.id)) return true
    if (drawn.size >= limit) {
      truncated = true
      return false
    }
    drawn.set(x.id, x)
    return true
  }
  // up: what it was built from, a deleted parent drawn as deleted
  let frontier = [r]
  for (let d = 0; d < depth && frontier.length; d++) {
    const next: Resource[] = []
    for (const child of frontier) {
      for (const id of child.builtFrom ?? []) {
        const parent = byId.get(id)
        if (!parent) {
          unmanaged.add(id)
          continue
        }
        const fresh = !drawn.has(parent.id)
        if (!visit(parent)) continue
        edges.push({ from: wfrnOf(parent), to: wfrnOf(child), via: child.createdBy?.workflow ?? null })
        if (fresh) next.push(parent)
      }
    }
    frontier = next
  }
  // down: the live resources built from it
  frontier = [r]
  for (let d = 0; d < depth && frontier.length; d++) {
    const next: Resource[] = []
    for (const parent of frontier) {
      for (const child of data.resources.filter((x) => x.builtFrom?.includes(parent.id) && !isDeleted(data, x))) {
        const fresh = !drawn.has(child.id)
        if (!visit(child)) continue
        edges.push({ from: wfrnOf(parent), to: wfrnOf(child), via: child.createdBy?.workflow ?? null })
        if (fresh) next.push(child)
      }
    }
    frontier = next
  }
  const seen = new Set<string>()
  const unique = edges.filter((e) => {
    const k = `${e.from}>${e.to}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
  const refs = [...drawn.values()].map((x) => stateRefOf(data, s, x, now))
  const me = refs[0]
  return {
    resource: me,
    builtFrom: (r.builtFrom ?? []).map((id) => byId.get(id)).filter((x): x is Resource => !!x && mine(x)).map((x) => stateRefOf(data, s, x, now)),
    dependents: data.resources.filter((x) => x.builtFrom?.includes(r.id) && !isDeleted(data, x) && mine(x)).map((x) => stateRefOf(data, s, x, now)),
    nodes: refs,
    edges: unique,
    unmanaged: [...unmanaged],
    hidden: hiddenIds.size,
    truncated,
  }
}

/** `GET /console/v1/map`: the accounts' live resources the person may list, the links between them, what lies beyond. */
export function graphOf(data: MockData, s: RouteContext['session'], accountIds: string[], type: string | null, now: number, limit = 500): ResourceGraph {
  const accounts = accountIdsOf(data)
  const shown = new Set(accountIds)
  const byId = new Map(data.resources.map((x) => [x.id, x]))
  const listable = (x: Resource) => {
    const rt = typeOf(data, x.type)
    const accountId = accountOfResource(x, accounts)
    return !!rt && shown.has(accountId) && authorize(data.directory, { accountId, userId: s.userId }, `${serviceOf(rt)}:list`, `wfrn:${data.partition}:${serviceOf(rt)}:${data.regionName}:${accountId}:${rt.name}/*`).allowed
  }
  const live = data.resources.filter((x) => !isDeleted(data, x))
  const matching = live.filter((x) => listable(x) && (!type || x.type === type))
  let truncated = false
  const nodes = new Map<string, { r: Resource; context: boolean }>()
  for (const x of matching) {
    if (nodes.size >= limit) {
      truncated = true
      break
    }
    nodes.set(x.id, { r: x, context: false })
  }
  // the parents outside the filter that the person may see, drawn for context
  for (const { r } of [...nodes.values()]) {
    for (const id of r.builtFrom ?? []) {
      const parent = byId.get(id)
      if (!parent || nodes.has(id) || !listable(parent)) continue
      if (nodes.size >= limit) {
        truncated = true
        break
      }
      nodes.set(id, { r: parent, context: true })
    }
  }
  const external = new Map<string, { accountId: string; type: string; direction: 'builtFrom' | 'dependent'; count: number }>()
  const unmanaged = new Set<string>()
  const edges: LineageEdge[] = []
  const count = (x: Resource, direction: 'builtFrom' | 'dependent') => {
    const accountId = accountOfResource(x, accounts)
    const k = `${accountId}|${x.type}|${direction}`
    const row = external.get(k) ?? { accountId, type: x.type, direction, count: 0 }
    row.count++
    external.set(k, row)
  }
  const wfrnOf = (x: Resource) => refOf(data, s, x).wfrn
  for (const { r } of nodes.values()) {
    for (const id of r.builtFrom ?? []) {
      const parent = byId.get(id)
      if (!parent) unmanaged.add(id)
      else if (nodes.has(id)) edges.push({ from: wfrnOf(parent), to: wfrnOf(r), via: r.createdBy?.workflow ?? null })
      else if (!shown.has(accountOfResource(parent, accounts))) count(parent, 'builtFrom')
    }
    for (const child of live.filter((x) => x.builtFrom?.includes(r.id))) if (!nodes.has(child.id) && !shown.has(accountOfResource(child, accounts))) count(child, 'dependent')
  }
  const graphNodes: GraphNode[] = [...nodes.values()].map(({ r, context }) => {
    const rt = typeOf(data, r.type)
    const ref = stateRefOf(data, s, r, now)
    const row = rt ? toConsoleResource(data, { ...s, accountId: ref.accountId }, rt, r, now) : undefined
    const price = rt ? priceOf(rt, r.attributes ?? {}) : undefined
    return { ...ref, tags: row?.tags, updatedAt: r.updatedAt ?? r.createdAt, ...(context ? { context: true } : {}), ...(price ? { cost: { monthly: price.monthly, currency: 'USD' } } : {}) }
  })
  return { nodes: graphNodes, edges, external: [...external.values()], unmanaged: [...unmanaged], truncated }
}

/* ----------------------------------------------------------------------------
   Time travel
   ------------------------------------------------------------------------- */

function detailAt(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, snap: Snapshot): ConsoleResourceDetail {
  const live = toConsoleResource(data, s, rt, r, Date.parse(snap.at))
  return { ...live, name: snap.name ?? live.name, state: snap.state, version: snap.version, attributes: snap.attributes, observed: snap.observed, drift: !!snap.drift, tags: snap.tags, updatedAt: snap.at, lastJob: undefined, can: [], observedAt: snap.observedAt }
}

/** `GET .../revert-plan?to=`: the merge patch from now back to then, or why there is none. */
export function revertPlanOf(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, to: number, now: number): RevertPlan {
  const wfrn = wfrnOfResource(data, s, rt, r)
  const log = logOf(data, wfrn, now)
  const current = replayAt(log, now)
  const then = replayAt(log, to)
  const from = { version: current?.version ?? 0, at: current?.at ?? iso(now), state: current?.state ?? stateOfResource(data, rt, r) }
  const plan: RevertPlan = { wfrn, from, possible: false, ...(then ? { to: { version: then.version, at: then.at, state: then.state } } : {}) }
  if (!then) return { ...plan, reason: 'resource.notYet' }
  if (from.state === 'deleted') return { ...plan, reason: 'resource.deleted' }
  if (busyOn(data, r) || ['pending', 'updating', 'deleting'].includes(from.state)) return { ...plan, reason: 'resource.busy' }
  if (then.state === 'deleted') return { ...plan, reason: 'resource.wasDeleted' }
  if (then.state === 'pending' || then.state === 'failed') return { ...plan, reason: 'resource.wasNotProvisioned' }
  const changes = mergePatch(current?.attributes ?? {}, then.attributes)
  const set = Object.fromEntries(Object.entries(then.tags).filter(([k, v]) => current?.tags[k] !== v))
  const removed = Object.keys(current?.tags ?? {}).filter((k) => !(k in then.tags))
  const fields = Object.keys(changes).map((k) => ({ path: `/${k}`, now: current?.attributes[k], then: then.attributes[k] }))
  if (!fields.length && !Object.keys(set).length && !removed.length) return { ...plan, reason: 'resource.unchanged', changes: {}, tags: { set: {}, removed: [] }, fields: [] }
  return { ...plan, possible: true, changes, tags: { set, removed }, fields }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

export function stateRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const now = ctx.now
  const can = (action: string, resource: string) => authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  const correlation = () => `mock-${idFrom(`${now}:${path}`, 8)}`
  const accountPattern = `wfrn:${data.partition}:*:${s.region}:${s.accountId}:*`

  /* the account's trail */
  if (path.startsWith('/console/v1/activity')) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = can('activity:list', accountPattern)
    if (!d.allowed) return refused(d, correlation())
    if (path === '/console/v1/activity') {
      const entries = trailEntries(data, s.accountId, params, now)
      return Array.isArray(entries) ? pageOf(entries, params) : entries
    }
    if (path === '/console/v1/activity/keys') return { status: 200, body: { keys: trailKeys() } }
    if (path === '/console/v1/activity/digests') {
      const from = instant(params, 'from')
      const to = instant(params, 'to')
      if (from === 'bad' || to === 'bad') return problems400([{ level: 'error', code: 'format', field: '/from', message: 'from and to are dates and times' }])
      const digests = (trailOf(data, now).digests.get(s.accountId) ?? []).filter((x) => (from === undefined || Date.parse(x.to) > from) && (to === undefined || Date.parse(x.from) < to))
      return { status: 200, body: { items: digests, next: null } }
    }
    if (path === '/console/v1/activity/evidence') {
      if (!readsEvidence(data, s)) return evidenceRefusal(data, s)
      const from = instant(params, 'from')
      const to = instant(params, 'to')
      if (from === undefined || to === undefined || from === 'bad' || to === 'bad') return problems400([{ level: 'error', code: 'required', field: from === undefined || from === 'bad' ? '/from' : '/to', message: 'from and to are required dates and times' }])
      // widened to whole periods, at most 31 days
      const start = Math.floor(from / DAY_MS) * DAY_MS
      const end = Math.ceil(to / DAY_MS) * DAY_MS
      if (end <= start) return problems400([{ level: 'error', code: 'range', field: '/to', message: 'to comes after from' }])
      if (end - start > 31 * DAY_MS) return problems400([{ level: 'error', code: 'range', field: '/to', message: 'An evidence pack covers at most 31 days' }])
      const trail = trailOf(data, now)
      const events = trail.events.filter((e) => e.context.accountId === s.accountId && Date.parse(e.at) >= start && Date.parse(e.at) < end)
      const digests = (trail.digests.get(s.accountId) ?? []).filter((x) => Date.parse(x.from) >= start && Date.parse(x.to) <= end)
      const checked = checkPackHashes({ events, digests })
      const jobs = [...new Set(events.map((e) => e.data.jobId).filter((j): j is string => typeof j === 'string'))].sort()
      return {
        status: 200,
        body: {
          accountId: s.accountId,
          region: s.region,
          from: iso(start),
          to: iso(end),
          generatedAt: iso(now),
          canonicalization: CANONICALIZATION,
          events,
          digests,
          keys: trailKeys(),
          verification: { valid: checked.valid, events: checked.events, digests: checked.digests, unsealed: checked.unsealed, problems: checked.problems.map((p) => ({ subject: p.subject, reason: problemWords(p) })) },
          jobs: jobs.map((jobId) => ({ jobId, evidence: `/api/v1/audit/${jobId}` })),
        },
      }
    }
    return { status: 404, body: { error: `Path ${path} not found` } }
  }

  /* the map */
  if (path === '/console/v1/map') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const asked = (params.get('accountIds') ?? '').split(',').filter(Boolean)
    if (asked.some((a) => !/^\d{12}$/.test(a))) return problems400([{ level: 'error', code: 'pattern', field: '/accountIds', message: 'Each account is its twelve digits' }])
    // the session's account, and the person's other accounts they asked for
    const member = (a: string) => rolesOf(data.directory, a, s.userId).length > 0
    const accountIds = [...new Set([s.accountId, ...asked.filter(member)])]
    const type = params.get('type')
    if (type && !typeOf(data, type)) return { status: 404, body: { error: `No resource type ${type}` } }
    return { status: 200, body: graphOf(data, s, accountIds, type, now) }
  }

  /* a type's list as it was */
  let m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})$/.exec(path)
  if (m && method === 'GET' && params.has('at')) {
    const rt = typeOf(data, m[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
    const at = instant(params, 'at')
    if (at === undefined || at === 'bad') return problems400([{ level: 'error', code: 'format', field: '/at', message: 'at is a date and time' }])
    if (params.get('sort')) return problems400([{ level: 'error', code: 'conflict', field: '/sort', message: 'A list as it was comes in wfrn order; sort is refused with at' }])
    const d = can(`${serviceOf(rt)}:list`, `wfrn:${data.partition}:${serviceOf(rt)}:${s.region}:${s.accountId}:${rt.name}/*`)
    if (!d.allowed) return refused(d, correlation())
    const accounts = accountIdsOf(data)
    const rows: ConsoleResource[] = []
    for (const r of data.resources) {
      if (r.type !== rt.name || accountOfResource(r, accounts) !== s.accountId) continue
      const snap = replayAt(logOf(data, wfrnOfResource(data, s, rt, r), now), at)
      if (!snap || snap.state === 'deleted') continue
      rows.push(detailAt(data, s, rt, r, snap))
    }
    rows.sort((a, b) => a.wfrn.localeCompare(b.wfrn))
    return pageOf(rows, params)
  }

  /* a resource: as it was, its chain, its revert plan, its activity and its lineage */
  m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)(?:\/(activity|activity:verify|revert-plan|lineage))?$/.exec(path)
  if (!m || method !== 'GET') return null
  const sub = m[3]
  if (!sub && !params.has('at')) return null
  const rt = typeOf(data, m[1])
  if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
  const service = serviceOf(rt)
  const noun = rt.name
  const wfrnParam = decodeURIComponent(m[2])
  const r = resourceByWfrn(data, rt, wfrnParam)
  if (!r || accountOfResource(r, accountIdsOf(data)) !== s.accountId) return { status: 404, body: { error: 'resource.not-found', detail: `No ${noun} lives at ${wfrnParam} in this account` } }
  const wfrn = wfrnOfResource(data, s, rt, r)
  const d = can(`${service}:get`, wfrn)
  if (!d.allowed) return refused(d, correlation())

  if (!sub) {
    const at = instant(params, 'at')
    if (at === undefined || at === 'bad') return problems400([{ level: 'error', code: 'format', field: '/at', message: 'at is a date and time' }])
    const snap = replayAt(logOf(data, wfrn, now), at)
    if (!snap) return { status: 404, body: { error: 'resource.notYet', detail: `This ${noun} did not exist yet at ${iso(at)}` } }
    return { status: 200, body: detailAt(data, s, rt, r, snap) }
  }
  if (sub === 'activity:verify') return { status: 200, body: checkChain(logOf(data, wfrn, now)) }
  if (sub === 'revert-plan') {
    const to = instant(params, 'to')
    if (to === undefined || to === 'bad') return problems400([{ level: 'error', code: 'required', field: '/to', message: 'to is the date and time to go back to' }])
    return { status: 200, body: revertPlanOf(data, s, rt, r, to, now) }
  }
  if (sub === 'lineage') {
    const depth = params.get('depth') ? Number(params.get('depth')) : 32
    if (!Number.isInteger(depth) || depth < 1 || depth > 32) return problems400([{ level: 'error', code: 'range', field: '/depth', message: 'The depth is a whole number from 1 to 32' }])
    return { status: 200, body: lineageOf(data, s, r, now, depth) }
  }
  // its activity: the state events, and the platform jobs about it the events do not already name
  const log = logOf(data, wfrn, now)
  const named = new Set(log.map((e) => e.data.jobId).filter(Boolean))
  const entries: ActivityEntry[] = log.map((e) => ({ at: e.at, kind: 'event', type: e.type, actor: e.actor, data: e.data }))
  const st = data.console
  for (const j of data.jobs) if ((j.wfrn === wfrn || st?.jobs[j.id]?.resourceId === r.id) && !named.has(j.id)) entries.push({ at: j.startedAt, kind: 'job', actor: j.requester, job: toBffJob(j) })
  entries.sort((a, b) => b.at.localeCompare(a.at) || (b.kind === 'event' && a.kind === 'event' ? Number((b.data as { version: number }).version) - Number((a.data as { version: number }).version) : 0))
  return pageOf(entries, params)
}
