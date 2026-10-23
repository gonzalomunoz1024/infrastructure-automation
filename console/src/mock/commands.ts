import { lifecycleOf, validateAgainst, type LifecycleClass, type Problem, type Schema } from '@iaf/console-kit'
import { idOfWfrn, serviceOf, titleOf } from '../lib/descriptors'
import type { ActivityEntry, BlastRadius, CatalogEntry, CommandAccepted, CommandInput, ConsolePreview, ConsoleResource, ConsoleResourceDetail, Dependent, Finding, JobSummary, PlatformEvent, PreviewStep, Resource, ResourceRef, ResourceState, ResourceType } from '../lib/types'
import type { MockData } from './data'
import { APPROVER_ROLE, accountOf, authorize, quotaOf, type Decision } from './directory'
import { accountOfResource, consoleResourceOf, driftOf, mintWfrn, stateOf, toBffJob, type ResourceContext } from './resources'
import { applyJobCommand, settleBatches, type MockBatch } from './jobs'
import { applyTagCommand, createTagProblems, createTagRules, tagChangeProblems, type TagsState } from './tags'

/*
 * The BFF's resource and job endpoints (hub: contracts/openapi/console-bff.yaml, /console/v1/r
 * and /console/v1/jobs), answered from the dataset. A create, update or verb is checked against
 * the account's policies, validated against the workflow's input schema, and answered 202 with a
 * job; the job then runs on the clock the server is given (an approval first when the verb needs
 * one, the apply a few seconds later) and the resource settles into its next state, so the pages
 * see pending, then active, as they would live. Every change is kept in a journal the browser
 * persists and replays, so what you created is still there after a reload.
 */

/**
 * What a command does. The resource commands are this module's own; a namespaced kind
 * (`<module>.<what>`, such as `group.create`) belongs to the module that owns the prefix, which
 * applies it when it runs and when the journal replays it (MODULE_COMMANDS below).
 */
export type ConsoleCommandKind = 'create' | 'update' | 'verb' | 'tags' | 'cancel' | `${string}.${string}`

export interface ConsoleCommand {
  at: number
  kind: ConsoleCommandKind
  userId: string
  accountId: string
  region: string
  type: string
  wfrn?: string
  verb?: string
  body: unknown
  /** the ids minted when the command first ran, so a replay mints the same; a steering command names its job as `jobId` and a rerun's new job as `rerunJobId` */
  ids: { jobId?: string; requestId?: string; resourceId?: string; wfrn?: string; wfrns?: string[]; rerunJobId?: string }
}

export interface InFlight {
  jobId: string
  resourceId?: string
  effect: 'create' | 'update' | 'delete' | 'change'
  /** a failed step pauses the job (`attention`) until someone retries, skips or aborts it */
  stage: 'approval' | 'running' | 'attention'
  approvalAt?: number
  dueAt: number
  /** attributes an update writes when it lands */
  patch?: Record<string, unknown>
  /** the apply fails while this holds; a retry judges it again on the fixed input (failsOn) */
  fail?: boolean
  /** the state the resource takes when the job succeeds */
  settledState?: string
  /** which attempt of the apply runs */
  attempt?: number
  /** the steps someone chose to go on past */
  skipped?: string[]
}

export interface ConsoleApproval {
  approvalId: string
  group: string
  status: string
  requestedAt?: string
  decidedBy?: string
  decidedAt?: string
  comment?: string
}

export interface ConsoleJobExtra {
  type: string
  verb: string
  wfrn?: string
  resourceId?: string
  consoleRequestId: string
  input: Record<string, unknown>
  output?: Record<string, unknown>
  reason?: string
  labels?: Record<string, string>
  approvals: ConsoleApproval[]
  /** the step a paused job waits on, what may be done with it, and since when (hub decision 24) */
  attention?: { step: string; options: ('retry' | 'skip' | 'abort')[]; since: string }
  /** the batch this job is a row of */
  parentId?: string
  /** the job this one runs again */
  retryOf?: string
  /** how many times the apply ran */
  attempts?: number
  /** the steps someone chose to go on past */
  skipped?: string[]
  /** a bulk run's rows and waves (./jobs) */
  batch?: MockBatch
}

export interface MockConsoleState {
  journal: ConsoleCommand[]
  inFlight: InFlight[]
  tags: Record<string, Record<string, string>>
  jobs: Record<string, ConsoleJobExtra>
  /** Idempotency-Key → the reply it earned, with a hash of the body it came with (P-21) */
  replies: Record<string, { bodyHash: string; status: number; body: unknown }>
  /** resource groups, the tag policy and the Tag Editor's state (./tags), made on first use */
  tools?: TagsState
  /** the batches still running, by job id (./jobs) */
  batches?: string[]
}

export function emptyConsoleState(): MockConsoleState {
  return { journal: [], inFlight: [], tags: {}, jobs: {}, replies: {} }
}

/** How long the mock's jobs take: the approval, then the apply. */
export const APPROVAL_MS = 6_000
export const RUN_MS = 8_000

/** The account's approver group, which the mock's approvals wait on: the group holding the approver role. */
export function approverGroupOf(data: MockData, accountId: string): string {
  return (data.directory.groups[accountId] ?? []).find((g) => g.roles?.includes(APPROVER_ROLE))?.name ?? 'approvers'
}

/** Whether a run waits for an approver: a destructive verb, a workflow that always asks, or a production resource. */
export function needsApprovalOf(entry: CatalogEntry | undefined, verb: string, attributes: Record<string, unknown> | undefined): boolean {
  const destructive = entry?.destructive ?? verb === 'delete'
  return destructive || !!(entry?.approval && (typeof entry.approval === 'boolean' ? entry.approval : entry.approval.required)) || attributes?.environment === 'prod'
}

export interface RouteAnswer {
  status: number
  body?: unknown
  headers?: Record<string, string>
}

export interface RouteContext {
  session: { userId: string; accountId: string; region: string }
  body: Record<string, unknown>
  headers: Record<string, string>
  now: number
}

/* ----------------------------------------------------------------------------
   Small helpers
   ------------------------------------------------------------------------- */

function hash32(s: string, seed = 5381): number {
  let h = seed
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return h >>> 0
}

/** `len` base-36 characters drawn from a seed, the same every time. */
export function idFrom(seed: string, len = 10): string {
  let out = ''
  let round = 0
  while (out.length < len) out += hash32(`${seed}:${round++}`).toString(36)
  return out.slice(0, len)
}

export function iso(ms: number): string {
  return new Date(ms).toISOString()
}

export function stateFor(rt: ResourceType, cls: LifecycleClass): string | undefined {
  for (const s of rt.states ?? []) if (lifecycleOf(s, rt.console?.lifecycle) === cls) return s
  return undefined
}

export function classOf(rt: ResourceType, status: string): LifecycleClass {
  return lifecycleOf(status, rt.console?.lifecycle) ?? 'active'
}

export function nounOf(rt: ResourceType): string {
  return (rt.console?.title ?? rt.plural).replace(/s$/, '')
}

export function providerOf(service: string): string {
  return service === 'compute' ? 'vsphere' : service === 'data' ? 'mongodb' : service === 'storage' ? 'netapp' : service === 'network' ? 'ipam' : 'provider'
}

export function problems400(problems: Problem[]): RouteAnswer {
  return { status: 400, body: { error: 'validation.failed', detail: `${problems.length === 1 ? 'One field needs' : `${problems.length} fields need`} attention`, problems } }
}

export function refused(decision: Decision, correlationId: string): RouteAnswer {
  return { status: 403, body: { error: decision.denied?.reason ?? 'Forbidden', denied: decision.denied, correlationId } }
}

function parseCursor(after: string | null): number {
  const m = after && /^c(\d+)$/.exec(after)
  return m ? Number(m[1]) : 0
}

export function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const start = parseCursor(params.get('after'))
  const slice = items.slice(start, start + limit)
  return { status: 200, body: { items: slice, next: start + limit < items.length ? `c${start + limit}` : null }, headers: { 'X-Total-Count': String(items.length) } }
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

/* ----------------------------------------------------------------------------
   Types, actions and their inputs
   ------------------------------------------------------------------------- */

export function typeOf(data: MockData, name: string): ResourceType | undefined {
  return data.resourceTypes.find((t) => t.name === name)
}

export function wfrnPatternOf(data: MockData, s: RouteContext['session'], rt: ResourceType): string {
  return `wfrn:${data.partition}:${serviceOf(rt)}:${s.region}:${s.accountId}:${rt.name}/*`
}

/** The accounts of the directory, in order: a sample with none of its own belongs to one by its owner. */
export function accountIdsOf(data: MockData): string[] {
  return data.directory.accounts.map((a) => a.accountId)
}

/** Where a resource lives: the session's partition and region, the account it belongs to. */
function contextOf(data: MockData, s: { region: string }, r: Resource): ResourceContext {
  return { partition: data.partition, region: s.region, accountId: accountOfResource(r, accountIdsOf(data)) }
}

/** A resource's wfrn, minted the way the list mints it (./resources), so links from either agree. */
export function wfrnOfResource(data: MockData, s: { region: string }, rt: ResourceType, r: Resource): string {
  return mintWfrn(r, rt, contextOf(data, s, r))
}

/** The resource a wfrn names: the one carrying it, else the one whose id the wfrn ends in. */
export function resourceByWfrn(data: MockData, rt: ResourceType, wfrn: string): Resource | undefined {
  const id = idOfWfrn(wfrn)
  return data.resources.find((r) => r.type === rt.name && (r.wfrn === wfrn || (!!id && r.id === id)))
}

/** The workflow that creates the type: its producer, else the catalog's create verb. */
export function createEntryOf(data: MockData, rt: ResourceType): CatalogEntry | undefined {
  return (rt.producedBy ? data.catalog.find((e) => e.workflow === rt.producedBy) : undefined) ?? data.catalog.find((e) => e.noun === rt.name && e.verb === 'create')
}

export function verbEntryOf(data: MockData, rt: ResourceType, verb: string): CatalogEntry | undefined {
  return data.catalog.find((e) => e.noun === rt.name && e.verb === verb)
}

/** The input that names the resource a verb acts on (P-08): `subject`, else the one x-resource input of the type. */
export function subjectFieldOf(entry: CatalogEntry, rt: ResourceType): string | undefined {
  if (entry.subject) return entry.subject
  return Object.entries(entry.input?.properties ?? {}).find(([, p]) => p?.['x-resource']?.type === rt.name)?.[0]
}

/** The verb's input without its subject: what the console asks for. */
export function inputSchemaOf(entry: CatalogEntry, subject: string | undefined): Schema {
  const schema = entry.input ?? { type: 'object' }
  if (!subject) return schema
  const { [subject]: _dropped, ...rest } = schema.properties ?? {}
  return { ...schema, properties: rest, required: (schema.required ?? []).filter((n) => n !== subject) }
}

/** The fields an update may change: the x-console `mutable` ones. */
export function updateSchemaOf(rt: ResourceType): Schema {
  const props = Object.entries(rt.schema?.properties ?? {}).filter(([, p]) => p?.['x-console']?.mutable === true)
  return { type: 'object', properties: Object.fromEntries(props) }
}

const SIZES: Record<string, { cpu: number; memoryGb: number }> = { small: { cpu: 2, memoryGb: 8 }, medium: { cpu: 4, memoryGb: 16 }, large: { cpu: 8, memoryGb: 32 }, xlarge: { cpu: 16, memoryGb: 64 } }

/** The attributes a create records: the input's fields the type knows, plus what the workflow derives (a size becomes cpu and memory). */
export function attributesOf(rt: ResourceType, input: Record<string, unknown>, owner: string): Record<string, unknown> {
  const known = rt.schema?.properties ?? {}
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(input)) {
    if (!(k in known) || v === undefined || v === null || v === '') continue
    out[k] = isRecord(v) && 'create' in v ? `${k}-built-by-request` : v
  }
  const size = typeof input.size === 'string' ? SIZES[input.size] : undefined
  if (size) {
    if ('cpu' in known && out.cpu === undefined) out.cpu = size.cpu
    if ('memoryGb' in known && out.memoryGb === undefined) out.memoryGb = size.memoryGb
  }
  if ('owner' in known && !out.owner) out.owner = owner
  return out
}

/** The registered resources an input refers to through its x-resource fields. */
export function referencedResources(data: MockData, schema: Schema | undefined, input: Record<string, unknown>): Resource[] {
  const out: Resource[] = []
  for (const [name, prop] of Object.entries(schema?.properties ?? {})) {
    const type = prop?.['x-resource']?.type
    const v = input[name]
    if (!type || typeof v !== 'string') continue
    const r = data.resources.find((x) => x.type === type && (x.id === v || x.wfrn === v || idOfWfrn(v) === x.id))
    if (r) out.push(r)
  }
  return out
}

/* ----------------------------------------------------------------------------
   Prices (list-prices.yaml): what a run costs per month
   ------------------------------------------------------------------------- */

const VM_BY_CPU: Record<string, number> = { 2: 38.5, 4: 77, 8: 154, 16: 308 }
const DB_BY_TIER: Record<string, number> = { M10: 57, M30: 210, M60: 520 }

export function priceOf(rt: ResourceType, attributes: Record<string, unknown>): { monthly: number; detail: string } | undefined {
  const service = serviceOf(rt)
  if (rt.name === 'vm' || (service === 'compute' && 'cpu' in attributes)) {
    const cpu = Number(attributes.cpu ?? SIZES[String(attributes.size ?? 'small')]?.cpu ?? 2)
    const monthly = VM_BY_CPU[String(cpu)] ?? cpu * 19.25
    return { monthly, detail: `list-prices: vsphere vm.create by cpu (${cpu}) → ${monthly.toFixed(2)} USD a month` }
  }
  if (rt.name === 'database' || 'tier' in attributes) {
    const tier = String(attributes.tier ?? 'M10')
    if (rt.name === 'volume') {
      const gb = Number(attributes.sizeGb ?? 0)
      const rate = tier === 'ssd' ? 0.1 : 0.05
      return { monthly: Math.round(gb * rate * 100) / 100, detail: `mock estimate: ${gb} GB at ${rate.toFixed(2)} USD per GB a month (${tier})` }
    }
    const monthly = DB_BY_TIER[tier] ?? 57
    return { monthly, detail: `list-prices: mongodb cluster.create by tier (${tier}) → ${monthly.toFixed(2)} USD a month` }
  }
  if (rt.name === 'image') {
    const gb = Number(attributes.sizeGb ?? 40)
    return { monthly: Math.round(gb * 0.02 * 100) / 100, detail: `mock estimate: ${gb} GB of template storage at 0.02 USD per GB a month` }
  }
  if (rt.name === 'environment') {
    const n = Number(attributes.vmCount ?? 2)
    return { monthly: n * 38.5, detail: `mock estimate: ${n} small virtual machines at 38.50 USD a month` }
  }
  return undefined
}

/* ----------------------------------------------------------------------------
   Views: resources, jobs, findings, lineage
   ------------------------------------------------------------------------- */

export function stateOfResource(data: MockData, rt: ResourceType, r: Resource): ResourceState {
  const st = data.console
  const flight = st?.inFlight.find((f) => f.resourceId === r.id)
  if (flight) return flight.effect === 'create' ? 'pending' : flight.effect === 'delete' ? 'deleting' : 'updating'
  const last = lastJobOf(data, r)
  const verb = last ? st?.jobs[last.id]?.verb : undefined
  // a batch's row runs on the resource as its own job
  if (last && OPEN.has(last.status) && st?.jobs[last.id]?.parentId) return verb === 'create' ? 'pending' : verb === 'delete' ? 'deleting' : 'updating'
  if (last && ENDED_BADLY.has(last.status) && verb === 'create') return 'failed'
  return stateOf(r, rt)
}

/** The ends of a job that did not do what it was asked: what a retry runs again. */
export const ENDED_BADLY = new Set(['failed', 'cancelled', 'rolled-back'])

/** A request running on the resource: one in flight, or a batch's row. */
export function busyOn(data: MockData, r: Resource): boolean {
  const st = data.console
  if (st?.inFlight.some((f) => f.resourceId === r.id)) return true
  const last = lastJobOf(data, r)
  return !!last && OPEN.has(last.status)
}

function lastJobOf(data: MockData, r: Resource): JobSummary | undefined {
  const st = data.console
  if (!st) return undefined
  return data.jobs.find((j) => st.jobs[j.id] && st.jobs[j.id].resourceId === r.id)
}

export function refOf(data: MockData, s: RouteContext['session'], r: Resource): ResourceRef {
  const rt = typeOf(data, r.type)
  return { wfrn: rt ? wfrnOfResource(data, s, rt, r) : (r.wfrn ?? r.id), type: r.type, name: r.name ?? r.id, state: rt ? stateOfResource(data, rt, r) : undefined }
}

/** A resource that is not deleted (a tombstone depends on nothing and nothing depends on it). */
function isLive(data: MockData, r: Resource): boolean {
  const rt = typeOf(data, r.type)
  return !rt || classOf(rt, r.status) !== 'deleted'
}

/** The live resources built from this one, one hop away. */
export function liveDependentsOf(data: MockData, r: Resource): Resource[] {
  return data.resources.filter((x) => x.builtFrom?.includes(r.id) && isLive(data, x))
}

/** The most dependents the walk names before it stops (the BFF's limit). */
export const BLAST_RADIUS_LIMIT = 500

/**
 * What depends on the roots within `depth` hops (BFF BlastRadius): every account counted, only
 * the session's account named, nearest first; a dependent the roots share is counted once, and
 * an edge is shown only between resources the session may see.
 */
export function blastRadiusOf(data: MockData, s: RouteContext['session'], roots: Resource[], depth = 3): BlastRadius {
  const accounts = accountIdsOf(data)
  const accountOf = (x: Resource) => accountOfResource(x, accounts)
  const rootIds = new Set(roots.map((r) => r.id))
  const depthOf = new Map<string, number>()
  const found: Resource[] = []
  const links: { from: Resource; to: Resource }[] = []
  let truncated = false
  let frontier = roots
  for (let d = 1; d <= depth && frontier.length > 0 && !truncated; d++) {
    const next: Resource[] = []
    for (const parent of frontier) {
      for (const x of liveDependentsOf(data, parent)) {
        links.push({ from: parent, to: x })
        if (rootIds.has(x.id) || depthOf.has(x.id)) continue
        if (found.length >= BLAST_RADIUS_LIMIT) {
          truncated = true
          break
        }
        depthOf.set(x.id, d)
        found.push(x)
        next.push(x)
      }
    }
    frontier = next
  }
  const counts = new Map<string, number>()
  for (const x of found) counts.set(accountOf(x), (counts.get(accountOf(x)) ?? 0) + 1)
  const visible = (x: Resource) => accountOf(x) === s.accountId
  const named = found.filter(visible).sort((a, b) => (depthOf.get(a.id) ?? 0) - (depthOf.get(b.id) ?? 0) || (a.name ?? a.id).localeCompare(b.name ?? b.id))
  const wfrnOf = (x: Resource) => refOf(data, s, x).wfrn
  return {
    resource: roots.length === 1 ? refOf(data, s, roots[0]) : undefined,
    total: found.length,
    direct: found.filter((x) => depthOf.get(x.id) === 1).length,
    byAccount: [...counts].map(([accountId, count]) => ({ accountId, count, current: accountId === s.accountId })).sort((a, b) => Number(b.current) - Number(a.current) || b.count - a.count),
    dependents: named.map((x): Dependent => ({ ...refOf(data, s, x), id: x.id, depth: depthOf.get(x.id) })),
    edges: links.filter((l) => visible(l.from) && visible(l.to)).map((l) => ({ from: wfrnOf(l.from), to: wfrnOf(l.to), via: l.to.createdBy?.workflow })),
    depth,
    truncated,
  }
}

/** How often the drift service compares every resource (the plan's hourly scan). */
export const DRIFT_SCAN_MS = 3600_000

/** When the last scan read the resource from its provider; none yet while it is younger than the first scan. */
export function observedAtOf(r: Resource, now: number): string | undefined {
  return now - Date.parse(r.createdAt) < DRIFT_SCAN_MS ? undefined : iso(now - 9 * 60_000)
}

/** The open drift finding of a resource the last scan read, from the drift the samples carry (./resources). */
export function findingsOf(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, now: number): Finding[] {
  const observedAt = observedAtOf(r, now)
  const fields = driftOf(r)
  if (!observedAt || fields.length === 0 || classOf(rt, r.status) === 'deleted') return []
  return [
    {
      findingId: `drift_${idFrom(r.id, 8)}`,
      wfrn: wfrnOfResource(data, s, rt, r),
      status: 'open',
      severity: 'warning',
      fields,
      // opened by the first scan after the resource was created, at most a day ago
      openedAt: iso(Math.min(Date.parse(observedAt), Math.max(Date.parse(r.createdAt) + DRIFT_SCAN_MS, now - 24 * 3600_000))),
      observedAt,
      can: ['revert', 'accept', 'ignore'],
    },
  ]
}

/** How long the state service keeps a deleted resource readable (its tombstone), in the mock. */
export const TOMBSTONE_MS = 30 * 24 * 3600_000

/** A 410's detail, as the BFF answers it (hub Tombstone under `detail`). */
export function tombstoneOf(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource): { wfrn: string; deletedAt: string; tombstoneUntil: string; lastVersion: number } {
  const deletedAt = r.updatedAt ?? r.createdAt
  return { wfrn: wfrnOfResource(data, s, rt, r), deletedAt, tombstoneUntil: iso(Date.parse(deletedAt) + TOMBSTONE_MS), lastVersion: toConsoleResource(data, s, rt, r, Date.parse(deletedAt)).version }
}

/**
 * A resource's row: the list's (./resources) with what the console's own requests did laid over
 * it, the state while one is in flight, its version, the tags set here and the last job; drift
 * only once the hourly scan has read it (a resource younger than its first scan was not compared).
 */
export function toConsoleResource(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, now: number): ConsoleResource {
  const st = data.console
  const base = consoleResourceOf(r, data, contextOf(data, s, r))
  const last = lastJobOf(data, r)
  const findings = findingsOf(data, s, rt, r, now)
  const touched = data.jobs.filter((j) => st?.jobs[j.id]?.resourceId === r.id && j.status === 'succeeded').length
  return {
    ...base,
    state: stateOfResource(data, rt, r),
    version: 1 + touched,
    observed: findings.length ? { ...base.attributes, status: r.status, ...Object.fromEntries(findings[0].fields.map((f) => [f.path, f.observed])) } : { status: r.status },
    drift: findings.length > 0,
    tags: st?.tags[base.wfrn] ?? base.tags,
    lastJob: last ? toBffJob(last) : base.lastJob,
  }
}

/** A row as the list serves it, the same as the resource page reads it. */
export function liveRowOf(data: MockData, s: RouteContext['session'], r: Resource, now: number): ConsoleResource {
  const rt = typeOf(data, r.type)
  return rt ? toConsoleResource(data, s, rt, r, now) : consoleResourceOf(r, data, contextOf(data, s, r))
}

export function toConsoleResourceDetail(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, now: number, can: (action: string, resource: string) => Decision): ConsoleResourceDetail {
  const base = toConsoleResource(data, s, rt, r, now)
  const service = serviceOf(rt)
  const byId = new Map(data.resources.map((x) => [x.id, x]))
  const builtFrom = (r.builtFrom ?? []).map((id) => byId.get(id)).filter((x): x is Resource => !!x)
  const dependents = data.resources.filter((x) => x.builtFrom?.includes(r.id))
  const verbs = new Set<string>(['get', 'update', 'delete', ...data.catalog.filter((e) => e.noun === rt.name && e.verb !== 'create').map((e) => e.verb)])
  const findings = findingsOf(data, s, rt, r, now)
  return {
    ...base,
    finding: findings[0],
    jobs: data.jobs.filter((j) => j.wfrn === base.wfrn || data.console?.jobs[j.id]?.resourceId === r.id).slice(0, 20).map(toBffJob),
    builtFrom: builtFrom.map((x) => refOf(data, s, x)),
    dependents: dependents.map((x) => refOf(data, s, x)),
    can: [...verbs].filter((v) => can(`${service}:${v}`, base.wfrn).allowed),
    observedAt: observedAtOf(r, now),
    providerId: r.providerId,
  }
}

/* ----------------------------------------------------------------------------
   Previews: what a run would do, touch and cost, and what policy says
   ------------------------------------------------------------------------- */

type Rule = { id: string; result: string; message: string }

function decide(rules: Rule[]): ConsolePreview['policy']['decision'] {
  if (rules.some((r) => r.result === 'deny')) return 'deny'
  if (rules.some((r) => r.result === 'needs-approval')) return 'needs-approval'
  return 'allow'
}

/** What the account holds of each type, by the type's name: the count of every `resources.<type>` quota, where a deleted resource (a tombstone) no longer counts. */
export function usedByTypeOf(data: MockData, accountId: string): Record<string, number> {
  const types = new Map(data.resourceTypes.map((rt) => [rt.name, rt]))
  const out: Record<string, number> = {}
  for (const r of data.resources) {
    const rt = types.get(r.type)
    if (!rt || (r.accountId && r.accountId !== accountId) || classOf(rt, r.status) === 'deleted') continue
    out[rt.name] = (out[rt.name] ?? 0) + 1
  }
  return out
}

/** The account's `resources.<type>` quota for the type being created, as the Quotas card shows it. */
function quotaRule(data: MockData, s: RouteContext['session'], rt: ResourceType): Rule {
  const { limit, used } = quotaOf(data.directory, s.accountId, `resources.${rt.name}`, usedByTypeOf(data, s.accountId))
  const title = titleOf(rt)
  return used >= limit
    ? { id: 'quota.resources', result: 'deny', message: `${title}: the account's quota of ${limit} is used up (${used} exist); delete one, or request an increase from the platform team` }
    : { id: 'quota.resources', result: 'pass', message: `${title}: ${used} of ${limit} in this account` }
}

export function previewCreate(data: MockData, s: RouteContext['session'], rt: ResourceType, entry: CatalogEntry | undefined, input: Record<string, unknown>, _now: number, tags?: unknown): ConsolePreview {
  const service = serviceOf(rt)
  const provider = providerOf(service)
  const noun = nounOf(rt).toLowerCase()
  const name = typeof input.name === 'string' && input.name ? input.name : `new ${noun}`
  const steps: PreviewStep[] = [{ id: 'validate', name: 'Validate the input', effect: 'read', detail: `${entry?.workflow ?? `create-${rt.name}`} checks the request against its schema` }]
  const refs = referencedResources(data, entry?.input, input)
  for (const [field, prop] of Object.entries(entry?.input?.properties ?? {})) {
    const xr = prop?.['x-resource']
    if (!xr) continue
    const v = input[field]
    if (isRecord(v) && 'create' in v) steps.push({ id: `build-${field}`, name: `Build a ${xr.type} first`, effect: 'create', detail: xr.orCreateWith ? `runs ${xr.orCreateWith} and waits for it` : undefined })
    else if (typeof v === 'string' && v) {
      const r = refs.find((x) => x.type === xr.type && (x.id === v || x.wfrn === v || idOfWfrn(v) === x.id))
      steps.push({ id: `use-${field}`, name: `Use ${xr.type} ${r?.name ?? v}`, effect: 'read', detail: r ? `${r.status}, access checked` : 'not found in the registry: the run will fail at this step' })
    }
  }
  const attrs = attributesOf(rt, input, 'team-' + service)
  steps.push({ id: 'apply', name: `Create ${noun} ${name} in ${provider}`, effect: 'create', detail: Object.entries(attrs).filter(([k]) => k !== 'name' && k !== 'owner').map(([k, v]) => `${k} ${String(v)}`).join(' · ') || undefined })
  steps.push({ id: 'register', name: `Register the ${noun}`, effect: 'create', detail: 'the registry records it with its wfrn and lineage' })
  const rules: Rule[] = [{ id: 'rbac.role', result: 'pass', message: `Your role allows ${service}:create here` }, quotaRule(data, s, rt), ...createTagRules(data, s, rt, input, tags)]
  if (input.environment === 'prod') rules.push({ id: 'environment.prod', result: 'needs-approval', message: 'Production changes wait for compute-approvers' })
  if (entry?.approval && (typeof entry.approval === 'boolean' ? entry.approval : entry.approval.required)) rules.push({ id: 'approval.required', result: 'needs-approval', message: `${entry.workflow} always waits for an approval` })
  const missing = refs.length < Object.values(entry?.input?.properties ?? {}).filter((p, i) => p?.['x-resource'] && typeof input[Object.keys(entry?.input?.properties ?? {})[i]] === 'string').length
  if (missing) rules.push({ id: 'references.exist', result: 'deny', message: 'A resource the input names is not in the registry' })
  const price = priceOf(rt, attrs)
  return {
    workflow: entry?.workflow ?? `create-${rt.name}`,
    version: entry?.version ?? '1.0.0',
    steps,
    affectedResources: refs.map((r) => refOf(data, s, r)),
    cost: price ? { monthly: price.monthly, currency: 'USD', detail: price.detail } : undefined,
    policy: { decision: decide(rules), rules, engine: 'mock' },
  }
}

export function previewVerb(data: MockData, s: RouteContext['session'], rt: ResourceType, r: Resource, verb: string, entry: CatalogEntry | undefined, input: Record<string, unknown>, _now: number): ConsolePreview {
  const service = serviceOf(rt)
  const provider = providerOf(service)
  const noun = nounOf(rt).toLowerCase()
  const name = r.name ?? r.id
  const destructive = entry?.destructive ?? verb === 'delete'
  const dependents = data.resources.filter((x) => x.builtFrom?.includes(r.id) && classOf(rt, x.status) !== 'deleted')
  const steps: PreviewStep[] = [{ id: 'check', name: `Check ${noun} ${name} exists`, effect: 'read', detail: `${provider} ${rt.name}.get` }]
  const needsApproval = destructive || !!(entry?.approval && (typeof entry.approval === 'boolean' ? entry.approval : entry.approval.required)) || r.attributes?.environment === 'prod'
  if (needsApproval) steps.push({ id: 'approve', name: 'Wait for an approval', effect: 'none', detail: 'compute-approvers, within 4h' })
  if (verb === 'update') {
    const changes = Object.entries(input).map(([k, v]) => `${k} ${String(r.attributes?.[k])} → ${String(v)}`)
    steps.push({ id: 'apply', name: `Resize ${noun} ${name}`, effect: 'change', detail: changes.join(' · ') || 'nothing changes' })
    steps.push({ id: 'register', name: 'Update the registry', effect: 'change' })
  } else if (verb === 'delete') {
    steps.push({ id: 'apply', name: `Delete ${noun} ${name} in ${provider}`, effect: 'delete', detail: input.keepSnapshotDays !== undefined ? `a snapshot is kept for ${String(input.keepSnapshotDays)} days` : undefined })
    steps.push({ id: 'register', name: `Retire the ${noun} in the registry`, effect: 'delete' })
  } else {
    steps.push({ id: 'apply', name: `${entry?.title ?? verb} on ${name}`, effect: 'change', detail: `${provider} ${rt.name}.${verb}` })
    steps.push({ id: 'register', name: 'Record the change', effect: 'change' })
  }
  const rules: Rule[] = [{ id: 'rbac.role', result: 'pass', message: `Your role allows ${service}:${verb} on this ${noun}` }]
  if (needsApproval) rules.push({ id: destructive ? 'destructive.approval' : 'approval.required', result: 'needs-approval', message: destructive ? `${verb} is destructive: compute-approvers must approve it` : 'This change waits for compute-approvers' })
  if (verb === 'delete' && dependents.length) rules.push({ id: 'dependents.exist', result: 'deny', message: `${dependents.length} ${dependents.length === 1 ? 'resource is' : 'resources are'} built from this ${noun}; delete them first` })
  const current = priceOf(rt, r.attributes ?? {})
  let cost: ConsolePreview['cost']
  if (verb === 'delete' && current) cost = { monthly: -current.monthly, currency: 'USD', detail: `saved: ${current.detail}` }
  else if (verb === 'update') {
    const next = priceOf(rt, { ...r.attributes, ...input })
    if (next) cost = { monthly: next.monthly, currency: 'USD', detail: current && current.monthly !== next.monthly ? `${next.detail}; was ${current.monthly.toFixed(2)} USD` : next.detail }
  }
  const affected: ResourceRef[] = [refOf(data, s, r), ...(verb === 'delete' ? dependents.map((x) => refOf(data, s, x)) : [])]
  return { workflow: entry?.workflow ?? `${verb}-${rt.name}`, version: entry?.version ?? '1.0.0', steps, affectedResources: affected, cost, policy: { decision: decide(rules), rules, engine: 'mock' } }
}

/* ----------------------------------------------------------------------------
   Commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

export function pushEvent(data: MockData, type: string, at: number, actor: string, job: JobSummary | undefined, r: Resource | undefined) {
  const seq = (data.events[data.events.length - 1]?.seq ?? 0) + 1
  const event: PlatformEvent = {
    specVersion: '1.0',
    dataVersion: '1',
    id: String(seq),
    key: `${type}:${type.startsWith('job') ? job?.id : r?.id}`,
    seq,
    type,
    at: iso(at),
    aggregate: type.startsWith('job') ? 'job' : 'resource',
    aggregateId: type.startsWith('job') ? job?.id : r?.id,
    actor,
    context: { accountId: job?.accountId ?? r?.accountId, region: job?.region ?? r?.region, wfrn: job?.wfrn ?? r?.wfrn, consoleRequestId: job?.consoleRequestId },
    data: type.startsWith('job') ? { jobId: job?.id, workflow: job?.workflow, status: job?.status } : { resourceId: r?.id, type: r?.type, status: r?.status },
  }
  data.events.push(event)
}

/** Opens the approval a job waits on, for the account's approver group, named after the request (or the job, for a batch's row). */
export function approvalFor(data: MockData, accountId: string, extra: ConsoleJobExtra, at: number, seed = extra.consoleRequestId): CommandAccepted['approval'] {
  const approval = { approvalId: `apr_${idFrom(seed, 8)}`, group: approverGroupOf(data, accountId), status: 'pending', timeout: '4h' }
  extra.approvals.push({ approvalId: approval.approvalId, group: approval.group, status: 'pending', requestedAt: iso(at) })
  return approval
}

/** The modules that own a namespaced command kind, by its prefix: they apply it when it runs and when the journal replays it. */
const MODULE_COMMANDS: Record<string, (data: MockData, cmd: ConsoleCommand) => RouteAnswer> = {
  // called through, since the modules import this one and may not have loaded when it does
  group: (data, cmd) => applyTagCommand(data, cmd),
  tags: (data, cmd) => applyTagCommand(data, cmd),
  job: (data, cmd) => applyJobCommand(data, cmd),
  batch: (data, cmd) => applyJobCommand(data, cmd),
}

/**
 * Journals a command and answers it, remembering the answer under the request's Idempotency-Key
 * (P-21): the same key with the same body answers the first result again, with another body a
 * 409. Every module's writes go through here, so a reload replays them.
 */
export function journaled(data: MockData, ctx: Pick<RouteContext, 'body' | 'headers'>, cmd: ConsoleCommand): RouteAnswer {
  const st = (data.console ??= emptyConsoleState())
  const key = ctx.headers['idempotency-key']
  const bodyHash = idFrom(JSON.stringify(ctx.body ?? null), 12)
  if (key) {
    const seen = st.replies[key]
    if (seen) {
      if (seen.bodyHash !== bodyHash) return { status: 409, body: { error: 'idempotency.mismatch', detail: 'This Idempotency-Key was already used with a different body; start a new request' } }
      return { status: seen.status, body: seen.body, headers: { 'Idempotent-Replayed': 'true' } }
    }
  }
  const answer = applyCommand(data, cmd)
  if (answer.status < 400) {
    st.journal.push(cmd)
    if (key) st.replies[key] = { bodyHash, status: answer.status, body: answer.body }
  }
  return answer
}

/**
 * Runs a journaled command against the dataset. Authorization and validation happened before it
 * was journaled. The world first moves to the command's time, so a replay sees every job where
 * it was when the command first ran.
 */
export function applyCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = (data.console ??= emptyConsoleState())
  settleConsole(data, cmd.at)
  const dot = cmd.kind.indexOf('.')
  if (dot > 0) {
    const owner = MODULE_COMMANDS[cmd.kind.slice(0, dot)]
    return owner ? owner(data, cmd) : { status: 404, body: { error: `No command ${cmd.kind}` } }
  }
  // a cancel journaled before the job module owned it replays as the module's own
  if (cmd.kind === 'cancel') return applyJobCommand(data, { ...cmd, kind: 'job.cancel' })
  const rt = typeOf(data, cmd.type)
  if (!rt) return { status: 404, body: { error: `No type ${cmd.type}` } }
  const service = serviceOf(rt)
  const noun = nounOf(rt)
  const body = (isRecord(cmd.body) ? cmd.body : {}) as Partial<CommandInput>
  const input = isRecord(body.input) ? body.input : {}
  const at = cmd.at
  const requestId = cmd.ids.requestId ?? `creq_${idFrom(`${cmd.userId}:${at}`, 10)}`
  const jobId = cmd.ids.jobId ?? `job_${idFrom(requestId, 10)}`

  const newJob = (workflow: string, version: string, title: string, wfrn: string | undefined, status: JobSummary['status']): JobSummary => ({ id: jobId, workflow, title, version, status, requester: cmd.userId, channel: 'console', startedAt: iso(at), kind: 'single', accountId: cmd.accountId, region: cmd.region, wfrn, consoleRequestId: requestId })

  if (cmd.kind === 'create') {
    const entry = createEntryOf(data, rt)
    const resourceId = cmd.ids.resourceId ?? `${rt.name}-${idFrom(requestId, 8)}`
    const wfrn = cmd.ids.wfrn ?? `wfrn:${data.partition}:${service}:${cmd.region}:${cmd.accountId}:${rt.name}/${resourceId}`
    cmd.ids = { jobId, requestId, resourceId, wfrn }
    const owner = (typeof input.owner === 'string' && input.owner) || accountOf(data.directory, cmd.accountId)?.owningTeam || `team-${service}`
    const name = typeof input.name === 'string' && input.name ? input.name : resourceId
    const r: Resource = {
      id: resourceId,
      type: rt.name,
      name,
      status: stateFor(rt, 'pending') ?? rt.states?.[0] ?? 'pending',
      attributes: attributesOf(rt, input, owner),
      owner,
      visibility: 'team',
      createdBy: { jobId, workflow: entry?.workflow ?? `create-${rt.name}`, version: entry?.version },
      builtFrom: referencedResources(data, entry?.input, input).map((x) => x.id),
      createdAt: iso(at),
      updatedAt: iso(at),
      wfrn,
      accountId: cmd.accountId,
      region: cmd.region,
    }
    data.resources.unshift(r)
    // the tags the request named land on the minted resource before its workflow starts
    if (isRecord(body.tags)) st.tags[wfrn] = Object.fromEntries(Object.entries(body.tags).filter((e): e is [string, string] => typeof e[1] === 'string'))
    const job = newJob(entry?.workflow ?? `create-${rt.name}`, entry?.version ?? '1.0.0', `${entry?.title ?? `Create ${noun.toLowerCase()}`} ${name}`, wfrn, 'running')
    data.jobs.unshift(job)
    const extra: ConsoleJobExtra = { type: rt.name, verb: 'create', wfrn, resourceId, consoleRequestId: requestId, input, reason: body.reason, labels: body.labels, approvals: [] }
    st.jobs[jobId] = extra
    const needsApproval = needsApprovalOf(entry, 'create', input)
    let approval: CommandAccepted['approval']
    if (needsApproval) {
      job.status = 'waiting-approval'
      approval = approvalFor(data, cmd.accountId, extra, at)
    }
    st.inFlight.push({ jobId, resourceId, effect: 'create', stage: needsApproval ? 'approval' : 'running', approvalAt: needsApproval ? at + APPROVAL_MS : undefined, dueAt: at + (needsApproval ? APPROVAL_MS : 0) + RUN_MS, fail: failsOn('create', input, name, true), settledState: stateFor(rt, 'active') ?? 'ready' })
    pushEvent(data, 'job.started', at, cmd.userId, job, r)
    const accepted: CommandAccepted = { wfrn, jobId, requestId, approval }
    return { status: 202, body: accepted }
  }

  const r = cmd.wfrn ? resourceByWfrn(data, rt, cmd.wfrn) : undefined
  if (!r) return { status: 404, body: { error: `No ${noun.toLowerCase()} at ${cmd.wfrn ?? '(no wfrn)'}` } }
  const wfrn = wfrnOfResource(data, cmd, rt, r)

  if (cmd.kind === 'tags') {
    const tags = isRecord(cmd.body) ? (Object.fromEntries(Object.entries(cmd.body).filter(([, v]) => typeof v === 'string')) as Record<string, string>) : {}
    st.tags[wfrn] = tags
    r.updatedAt = iso(at)
    return { status: 200, body: tags }
  }

  cmd.ids = { ...cmd.ids, jobId, requestId }
  const verb = cmd.kind === 'update' ? 'update' : (cmd.verb ?? 'change')
  const entry = cmd.kind === 'verb' ? verbEntryOf(data, rt, verb) : undefined
  const needsApproval = needsApprovalOf(entry, verb, r.attributes)
  const title = cmd.kind === 'update' ? `Update ${noun.toLowerCase()} ${r.name ?? r.id}` : `${entry?.title ?? `${verb.charAt(0).toUpperCase()}${verb.slice(1)} ${noun.toLowerCase()}`} ${r.name ?? r.id}`
  const job = newJob(entry?.workflow ?? `${verb}-${rt.name}`, entry?.version ?? '1.0.0', title, wfrn, needsApproval ? 'waiting-approval' : 'running')
  data.jobs.unshift(job)
  const extra: ConsoleJobExtra = { type: rt.name, verb, wfrn, resourceId: r.id, consoleRequestId: requestId, input, reason: body.reason, labels: body.labels, approvals: [] }
  st.jobs[jobId] = extra
  const approval = needsApproval ? approvalFor(data, cmd.accountId, extra, at) : undefined
  const effect: InFlight['effect'] = cmd.kind === 'update' ? 'update' : verb === 'delete' ? 'delete' : 'change'
  st.inFlight.push({
    jobId,
    resourceId: r.id,
    effect,
    stage: needsApproval ? 'approval' : 'running',
    approvalAt: needsApproval ? at + APPROVAL_MS : undefined,
    dueAt: at + (needsApproval ? APPROVAL_MS : 0) + RUN_MS,
    patch: cmd.kind === 'update' ? input : undefined,
    fail: failsOn(effect, input, r.name, true),
    settledState: effect === 'delete' ? (stateFor(rt, 'deleted') ?? 'retired') : undefined,
  })
  r.updatedAt = iso(at)
  pushEvent(data, 'job.started', at, cmd.userId, job, r)
  const accepted: CommandAccepted = { wfrn, jobId, requestId, approval }
  return { status: 202, body: accepted }
}

/**
 * Whether the mock's apply fails, so the demo can show a failure and its fix: a create whose name
 * asks for it fails on its first run only (a retry goes through), any other verb while its
 * `reason` input asks for it (a retry goes through once the fix changes the reason).
 */
export function failsOn(effect: InFlight['effect'], input: Record<string, unknown>, name: string | undefined, firstRun: boolean): boolean {
  if (effect === 'create') return firstRun && !!name && /fail/i.test(name)
  return typeof input.reason === 'string' && /fail/i.test(input.reason)
}

/** A failed step's choices (hub decision 24): a create cannot go on past its own apply, anything else may. */
export function attentionOptionsOf(effect: InFlight['effect']): ('retry' | 'skip' | 'abort')[] {
  return effect === 'create' ? ['retry', 'abort'] : ['retry', 'skip', 'abort']
}

/** Moves every job whose time has come: approvals land, applies finish or pause on a failure, resources settle, batches roll out. */
export function settleConsole(data: MockData, now: number) {
  const st = data.console
  if (!st) return
  for (const f of [...st.inFlight]) {
    const job = data.jobs.find((j) => j.id === f.jobId)
    const extra = st.jobs[f.jobId]
    if (!job || !extra) {
      st.inFlight = st.inFlight.filter((x) => x !== f)
      continue
    }
    // a paused step waits for a person, whatever the clock says
    if (f.stage === 'attention') continue
    const rt = typeOf(data, extra.type)
    const r = f.resourceId ? data.resources.find((x) => x.id === f.resourceId) : undefined
    if (f.stage === 'approval') {
      if (f.approvalAt === undefined || now < f.approvalAt) continue
      f.stage = 'running'
      job.status = 'running'
      const pending = extra.approvals.find((a) => a.status === 'pending')
      if (pending) Object.assign(pending, { status: 'approved', decidedBy: `approver:${pending.group}`, decidedAt: iso(f.approvalAt) })
    }
    if (now < f.dueAt) continue
    const endedAt = f.dueAt
    const skippedApply = !!f.skipped?.includes('apply')
    if (f.fail && !skippedApply) {
      // the step failed: the job pauses on it until someone retries, skips or aborts it
      f.stage = 'attention'
      job.status = 'needs-attention'
      job.failedStep = 'apply'
      job.errorClass = 'provider'
      extra.attention = { step: 'apply', options: attentionOptionsOf(f.effect), since: iso(endedAt) }
      pushEvent(data, 'job.attention', endedAt, job.requester, job, r)
      continue
    }
    job.status = 'succeeded'
    job.failedStep = undefined
    job.errorClass = undefined
    if (skippedApply) extra.output = { skipped: f.skipped }
    else {
      if (r && rt) {
        if (f.effect === 'create') {
          r.status = f.settledState ?? r.status
          r.providerId = `${providerOf(serviceOf(rt))}:${r.id}`
          extra.output = { [`${rt.name}Id`]: r.id, name: r.name, wfrn: r.wfrn }
        } else if (f.effect === 'delete') {
          r.status = f.settledState ?? r.status
          extra.output = { deleted: r.id }
        } else if (f.effect === 'update' && f.patch) {
          r.attributes = { ...r.attributes, ...f.patch }
          extra.output = { changed: Object.keys(f.patch) }
        } else {
          extra.output = { [`${rt.name}Name`]: r.name ?? r.id, ok: true }
        }
        r.updatedAt = iso(endedAt)
      } else extra.output = { ok: true }
    }
    job.endedAt = iso(endedAt)
    job.durationMs = Math.max(0, endedAt - Date.parse(job.startedAt))
    pushEvent(data, 'job.finished', endedAt, job.requester, job, r)
    if (!skippedApply && f.effect === 'create' && r) pushEvent(data, 'resource.registered', endedAt, job.requester, job, r)
    st.inFlight = st.inFlight.filter((x) => x !== f)
  }
  settleBatches(data, now)
}

/** Replays a saved journal onto a fresh dataset, then settles everything whose time has passed. */
export function replayConsole(data: MockData, journal: ConsoleCommand[], now: number) {
  const st = (data.console ??= emptyConsoleState())
  for (const cmd of journal) {
    const answer = applyCommand(data, cmd)
    if (answer.status < 400) st.journal.push(cmd)
  }
  settleConsole(data, now)
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

const VERB = /^[a-z][a-z0-9-]{0,31}$/
export const OPEN = new Set(['queued', 'running', 'waiting-approval', 'needs-attention'])

function tagProblems(body: unknown): Problem[] {
  if (!isRecord(body)) return [{ level: 'error', code: 'type', field: '/tags', message: 'Tags are an object of strings' }]
  const problems: Problem[] = []
  const entries = Object.entries(body)
  if (entries.length > 50) problems.push({ level: 'error', code: 'maxItems', field: '/tags', message: 'At most 50 tags' })
  for (const [k, v] of entries) {
    if (!/^[A-Za-z0-9_.:/=+@-]{1,128}$/.test(k)) problems.push({ level: 'error', code: 'pattern', field: `/tags/${k}`, message: `Tag key "${k}" may use letters, digits and _ . : / = + @ -, up to 128 characters` })
    if (typeof v !== 'string') problems.push({ level: 'error', code: 'type', field: `/tags/${k}`, message: `Tag ${k} needs a text value` })
    else if (v.length > 256) problems.push({ level: 'error', code: 'maxLength', field: `/tags/${k}`, message: `Tag ${k} is longer than 256 characters` })
  }
  return problems
}

/** A run policy refuses answers 409 with the rules that said no, as problems the form can show. */
function policyDenied(preview: ConsolePreview): RouteAnswer | null {
  if (preview.policy.decision !== 'deny') return null
  const rules = (preview.policy.rules ?? []).filter((r) => r.result === 'deny')
  return { status: 409, body: { error: 'policy.denied', detail: rules.map((r) => r.message).join('; ') || 'Policy refuses this run', problems: rules.map((r) => ({ level: 'error' as const, code: r.id, message: r.message })), policy: preview.policy } }
}

export function resourceRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const st = (data.console ??= emptyConsoleState())
  const s = ctx.session
  const can = (action: string, resource: string) => authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  const correlation = () => `mock-${idFrom(`${ctx.now}:${path}`, 8)}`
  const now = ctx.now

  /** journals a command and answers it, remembering the answer under its Idempotency-Key */
  const run = (cmd: ConsoleCommand): RouteAnswer => journaled(data, ctx, cmd)

  /* creates and their previews; the list, counts and search are the rows module's (./resources, via the server) */
  let m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})(?::(preview))?$/.exec(path)
  if (m) {
    const rt = typeOf(data, m[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
    const service = serviceOf(rt)
    const pattern = wfrnPatternOf(data, s, rt)
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = can(`${service}:create`, pattern)
    if (!d.allowed) return refused(d, correlation())
    const entry = createEntryOf(data, rt)
    if (!entry) return { status: 404, body: { error: 'create.unavailable', detail: `No workflow creates ${rt.plural}; the provider publishes one in its catalog` } }
    const input = ctx.body.input
    if (!isRecord(input)) return problems400([{ level: 'error', code: 'required', field: '/input', message: 'The body needs an `input` object with the workflow\'s fields' }])
    const problems = validateAgainst(entry.input, input).filter((p) => p.level === 'error')
    if (problems.length) return problems400(problems)
    const preview = previewCreate(data, s, rt, entry, input, now, ctx.body.tags)
    if (m[2] === 'preview') return { status: 200, body: preview }
    const tagged = createTagProblems(data, s, rt, input, ctx.body.tags)
    if (tagged) return { status: 400, body: { error: tagged.error, detail: tagged.problems.map((p) => p.message).join('; '), problems: tagged.problems } }
    if (typeof ctx.body.reason === 'string' && ctx.body.reason.length > 1000) return problems400([{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is longer than 1000 characters' }])
    const denied = policyDenied(preview)
    if (denied) return denied
    return run({ at: now, kind: 'create', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, body: ctx.body, ids: {} })
  }

  m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)(?::([a-z][a-z0-9-]{0,31}))?(?::(preview))?(?:\/(activity|drift|lineage|tags))?$/.exec(path)
  if (m) {
    const rt = typeOf(data, m[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${m[1]}` } }
    const service = serviceOf(rt)
    const noun = nounOf(rt).toLowerCase()
    const wfrnParam = decodeURIComponent(m[2])
    const verb = m[3]
    const preview = m[4] === 'preview'
    const sub = m[5]
    const r = resourceByWfrn(data, rt, wfrnParam)
    if (!r) return { status: 404, body: { error: 'resource.not-found', detail: `No ${noun} lives at ${wfrnParam}` } }
    const wfrn = wfrnOfResource(data, s, rt, r)
    if (accountOfResource(r, accountIdsOf(data)) !== s.accountId) return { status: 404, body: { error: 'resource.not-found', detail: `No ${noun} lives at ${wfrnParam} in this account` } }

    if (!verb && !sub) {
      if (method === 'GET') {
        const d = can(`${service}:get`, wfrn)
        if (!d.allowed) return refused(d, correlation())
        if (classOf(rt, r.status) === 'deleted') return { status: 410, body: { error: 'resource.deleted', detail: tombstoneOf(data, s, rt, r) } }
        return { status: 200, body: toConsoleResourceDetail(data, s, rt, r, now, can) }
      }
      if (method === 'PATCH') {
        const d = can(`${service}:update`, wfrn)
        if (!d.allowed) return refused(d, correlation())
        const schema = updateSchemaOf(rt)
        const input = ctx.body.input
        if (!isRecord(input)) return problems400([{ level: 'error', code: 'required', field: '/input', message: 'The body needs an `input` object with the fields to change' }])
        const immutable = Object.keys(input).filter((k) => !(k in (schema.properties ?? {})))
        if (immutable.length) return problems400(immutable.map((k) => ({ level: 'error' as const, code: 'immutable', field: `/input/${k}`, message: `${k} was set when the ${noun} was created and cannot be changed` })))
        if (Object.keys(input).length === 0) return problems400([{ level: 'error', code: 'required', field: '/input', message: 'Name at least one field to change' }])
        const problems = validateAgainst(schema, input).filter((p) => p.level === 'error')
        if (problems.length) return problems400(problems)
        return run({ at: now, kind: 'update', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, wfrn, body: ctx.body, ids: {} })
      }
      return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    }

    if (sub) {
      if (sub === 'tags') {
        if (method === 'GET') return { status: 200, body: st.tags[wfrn] ?? {} }
        if (method !== 'PUT') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
        const d = can(`${service}:update`, wfrn)
        if (!d.allowed) return refused(d, correlation())
        const problems = tagProblems(ctx.body)
        if (problems.length) return problems400(problems)
        const kept = tagChangeProblems(data, s, st.tags[wfrn] ?? toConsoleResource(data, s, rt, r, now).tags, ctx.body as Record<string, string>)
        if (kept) return { status: 400, body: { error: kept.error, detail: kept.problems.map((p) => p.message).join('; '), problems: kept.problems } }
        return run({ at: now, kind: 'tags', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, wfrn, body: ctx.body, ids: {} })
      }
      if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
      const d = can(`${service}:get`, wfrn)
      if (!d.allowed) return refused(d, correlation())
      if (sub === 'drift') return { status: 200, body: { items: findingsOf(data, s, rt, r, now) } }
      if (sub === 'lineage') {
        const byId = new Map(data.resources.map((x) => [x.id, x]))
        const builtFrom = (r.builtFrom ?? []).map((id) => byId.get(id)).filter((x): x is Resource => !!x)
        const dependents = data.resources.filter((x) => x.builtFrom?.includes(r.id))
        const me = refOf(data, s, r)
        return {
          status: 200,
          body: {
            resource: me,
            nodes: [me, ...builtFrom.map((x) => refOf(data, s, x)), ...dependents.map((x) => refOf(data, s, x))],
            edges: [...builtFrom.map((x) => ({ from: refOf(data, s, x).wfrn, to: me.wfrn, via: r.createdBy.workflow })), ...dependents.map((x) => ({ from: me.wfrn, to: refOf(data, s, x).wfrn, via: x.createdBy.workflow }))],
          },
        }
      }
      /* activity */
      const entries: ActivityEntry[] = [{ at: r.createdAt, kind: 'event', type: 'resource.registered', actor: data.jobs.find((j) => j.id === r.createdBy.jobId)?.requester ?? r.owner, data: { workflow: r.createdBy.workflow, version: r.createdBy.version, jobId: r.createdBy.jobId } }]
      for (const j of data.jobs) if ((j.wfrn === wfrn || st.jobs[j.id]?.resourceId === r.id) && j.id !== r.createdBy.jobId) entries.push({ at: j.startedAt, kind: 'job', actor: j.requester, job: toBffJob(j) })
      for (const c of st.journal) if (c.kind === 'tags' && c.wfrn === wfrn) entries.push({ at: iso(c.at), kind: 'event', type: 'resource.tagged', actor: c.userId, data: c.body })
      for (const f of findingsOf(data, s, rt, r, now)) entries.push({ at: f.openedAt, kind: 'event', type: 'drift.opened', actor: 'drift-service', data: { findingId: f.findingId, fields: f.fields.map((x) => x.path) } })
      entries.sort((a, b) => b.at.localeCompare(a.at))
      return pageOf(entries, params)
    }

    /* a verb on this resource */
    if (!verb || !VERB.test(verb) || verb === 'create') return { status: 404, body: { error: 'verb.unknown', detail: `No verb ${verb ?? ''} on ${rt.plural}` } }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const entry = verb === 'update' ? undefined : verbEntryOf(data, rt, verb)
    if (!entry && verb !== 'update' && verb !== 'delete') return { status: 404, body: { error: 'verb.unknown', detail: `No workflow runs ${verb} on ${rt.plural}` } }
    const d = can(`${service}:${verb}`, wfrn)
    if (!d.allowed) return refused(d, correlation())
    if (classOf(rt, r.status) === 'deleted') return { status: 409, body: { error: 'resource.deleted', detail: `This ${noun} is already deleted` } }
    const subject = entry ? subjectFieldOf(entry, rt) : undefined
    const schema = verb === 'update' ? updateSchemaOf(rt) : entry ? inputSchemaOf(entry, subject) : { type: 'object', properties: { reason: { type: 'string', title: 'Reason' } } }
    const rawInput = ctx.body.input
    const input = isRecord(rawInput) ? { ...rawInput } : {}
    if (subject) delete input[subject]
    const problems = validateAgainst(schema, input).filter((p) => p.level === 'error')
    if (problems.length) return problems400(problems)
    const judged = previewVerb(data, s, rt, r, verb, entry, input, now)
    if (preview) return { status: 200, body: judged }
    // a delete with live dependents is refused by name, the way the BFF answers it (409 with them)
    const blocked = judged.policy.rules?.find((x) => x.id === 'dependents.exist' && x.result === 'deny')
    if (blocked) return { status: 409, body: { error: 'dependents.exist', detail: { dependents: judged.affectedResources.filter((x) => x.wfrn !== wfrn) }, problems: [{ level: 'error' as const, code: 'dependents.exist', message: blocked.message }] } }
    const denied = policyDenied(judged)
    if (denied) return denied
    const destructive = entry?.destructive ?? verb === 'delete'
    if (destructive && ctx.body.confirm !== true) return problems400([{ level: 'error', code: 'confirm.required', field: '/confirm', message: `${entry?.title ?? verb} is destructive: send confirm: true to run it` }])
    if (busyOn(data, r)) return { status: 409, body: { error: 'resource.busy', detail: `A request is already changing this ${noun}; wait for it to finish` } }
    if (verb === 'update') return run({ at: now, kind: 'update', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, wfrn, body: { ...ctx.body, input }, ids: {} })
    return run({ at: now, kind: 'verb', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, wfrn, verb, body: { ...ctx.body, input: subject ? { ...input, [subject]: r.id } : input }, ids: {} })
  }

  return null
}
