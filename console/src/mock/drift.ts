import { lifecycleOf, type Problem } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import type { Assignment, DiscoveryRun, FindingStats, FindingsSummary, OwnerGuess, Resolution, Severity, UnmanagedObject } from '../lib/drift'
import type { Finding, JobSummary, Resource, ResourceType } from '../lib/types'
import { applyCommand, emptyConsoleState, findingsOf, idFrom, journaled, observedAtOf, priceOf, resourceByWfrn, settleConsole, typeOf, updateSchemaOf, wfrnOfResource, wfrnPatternOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { accountOf, authorize } from './directory'
import { accountOfResource } from './resources'

/*
 * The BFF's drift routes (hub contracts/openapi/console-bff.yaml, tag Drift; the drift thread's
 * drift-bff-routes.md), over the drift service of hub PR #17: the account's findings with their
 * summary and stats, a finding's revert, accept and ignore, discovery runs and the unmanaged
 * resources they find, with import and Assign to team. Decisions are journaled through
 * `journaled` in ./commands under the `drift.` and `unmanaged.` kinds, so a reload replays them.
 *
 * The findings are the hourly scan's (./commands scannedFindingsOf, from driftOf in ./resources)
 * with what people decided laid over them. A revert starts the type's update workflow with the
 * desired values through the same job machinery as an edit, and the finding stays open with
 * `revertStatus: running` until that job succeeded (then it closes as reverted, and the provider
 * matches what was asked for from then on) or failed (it stays open, offered again). The provider
 * refuses the first revert of one machine (vm-gen-037), so the failed path can be seen. Accept
 * writes the provider's values into the resource's attributes; ignore silences the fields until
 * the desired attributes change or until `until`, and the next scan after that opens a new
 * finding. A finding on a production resource is critical. Each resource also carries a short,
 * seeded history of findings closed in the last 30 days.
 *
 * Discovery reads a type's provider for what no account manages: a fixed set per account and
 * type (twelve machines and four clusters across the two accounts), each with an owner guess
 * worked out by the drift service's rules (a tag naming an account's team or id is high, the
 * account's name in the object's name medium, a placement only one account's resources use low)
 * and a monthly cost from the console's price list. A run takes four seconds on the mock clock;
 * what it found shows from then on. An import adds a managed resource with a minted wfrn and
 * removes the object from every list; an assignment shows the object in both accounts' lists.
 */

/** How long a discovery run takes on the mock clock. */
export const DISCOVERY_MS = 4_000

const DAY_MS = 86_400_000

/** A finding someone decided on, kept beside the scan: open while its revert runs or after it failed, closed once decided. */
interface FindingRecord {
  findingId: string
  resourceId: string
  type: string
  wfrn: string
  accountId: string
  region: string
  severity: Severity
  fields: Finding['fields']
  openedAt: string
  observedAt?: string
  status: 'open' | 'closed'
  closedAt?: string
  resolution?: Resolution
  decidedBy?: string
  reason?: string
  revertJobId?: string
  revertStatus?: Finding['revertStatus']
  ignoredUntil?: string
  version: number
  /** reverts asked for, so the one machine whose provider refuses the first refuses only that one */
  attempts: number
}

/** What decisions left on one resource: the paths the provider matches again, the silences, and how many findings closed. */
interface ResourceDrift {
  synced: string[]
  silenced: { path: string; digest: string; until?: number; since: number; by: string }[]
  /** findings of this resource that closed: the next one the scan opens gets a new id */
  generation: number
  /** when the last one closed */
  closedAt?: number
}

interface RunRecord {
  runId: string
  type: string
  accountId: string
  region: string
  startedAt: number
  endsAt: number
}

/** What these routes keep beside the dataset, made on first use. */
export interface DriftState {
  /** findings people decided on, by id */
  decisions: Record<string, FindingRecord>
  resources: Record<string, ResourceDrift>
  runs: Record<string, RunRecord>
  /** unmanaged objects taken under management, by `type:providerId` */
  imported: Record<string, { wfrn: string; accountId: string; by: string; at: number }>
  /** unmanaged objects handed to another account, by `type:providerId` */
  assignments: Record<string, Assignment>
}

/** The drift state of a fresh dataset: nothing decided, run, imported or assigned yet. */
export function emptyDriftState(): DriftState {
  return { decisions: {}, resources: {}, runs: {}, imported: {}, assignments: {} }
}

/* ----------------------------------------------------------------------------
   Small helpers
   ------------------------------------------------------------------------- */

type Session = RouteContext['session']

function iso(ms: number): string {
  return new Date(ms).toISOString()
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

function stateOf(data: MockData): DriftState {
  const st = (data.console ??= emptyConsoleState())
  const drift = (st.drift ??= emptyDriftState())
  // a state made by the seams' first shape has only `decisions`
  drift.resources ??= {}
  drift.runs ??= {}
  drift.imported ??= {}
  drift.assignments ??= {}
  return drift
}

function peek(data: MockData): DriftState | undefined {
  return data.console?.drift ? stateOf(data) : undefined
}

function resourceDriftOf(st: DriftState, resourceId: string): ResourceDrift {
  return (st.resources[resourceId] ??= { synced: [], silenced: [], generation: 0 })
}

function accountIdsOf(data: MockData): string[] {
  return data.directory.accounts.map((a) => a.accountId)
}

function deleted(rt: ResourceType, r: Resource): boolean {
  return (lifecycleOf(r.status, rt.console?.lifecycle) ?? 'active') === 'deleted'
}

/** A stable digest of the desired attributes: an ignore is bound to it, and a new desired state ends the ignore. */
function digestOf(attributes: Record<string, unknown> | undefined): string {
  const a = attributes ?? {}
  return idFrom(JSON.stringify(Object.keys(a).sort().map((k) => [k, a[k]])), 12)
}

/** The attribute a field's path names. */
function attributeOf(path: string): string {
  return path.replace(/^\//, '').replace(/^attributes[./]/, '').split(/[./]/)[0]
}

function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { status: 200, body: { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null }, headers: { 'X-Total-Count': String(items.length) } }
}

function refused(d: ReturnType<typeof authorize>, path: string, now: number): RouteAnswer {
  return { status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(`${now}:${path}`, 8)}` } }
}

function bad(problems: Problem[], error = 'validation.failed'): RouteAnswer {
  return { status: 400, body: { error, problems } }
}

/** The drift service grades a finding by where the resource runs: a difference in production is critical. */
function graded(r: Resource, scanned: Severity): Severity {
  return r.attributes?.environment === 'prod' ? 'critical' : scanned
}

/** Why the type's update workflow cannot put these fields back, or null when it can. */
function notRevertableOf(rt: ResourceType, fields: Finding['fields']): string | null {
  const mutable = updateSchemaOf(rt).properties ?? {}
  if (Object.keys(mutable).length === 0) return `No update workflow changes ${rt.plural}`
  const fixed = fields.filter((f) => !f.ignored && !(attributeOf(f.path) in mutable)).map((f) => attributeOf(f.path))
  return fixed.length ? `The update workflow of ${rt.plural} does not set ${fixed.join(', ')}` : null
}

/** The provider refuses the first revert of these machines (a maintenance lock), so the failed path shows. */
const REFUSES_FIRST_REVERT = new Set(['vm-gen-037'])

/* ----------------------------------------------------------------------------
   Following reverts: a running revert ends with its job
   ------------------------------------------------------------------------- */

const FAILED = new Set(['failed', 'cancelled', 'rolled-back'])

/** Closes the findings whose revert job succeeded and marks the failed ones; the jobs move on the server's clock (./commands settleConsole). */
function follow(data: MockData) {
  const st = peek(data)
  if (!st) return
  for (const rec of Object.values(st.decisions)) {
    if (rec.revertStatus !== 'running' || !rec.revertJobId) continue
    const job = data.jobs.find((j) => j.id === rec.revertJobId)
    if (!job) continue
    if (job.status === 'succeeded') {
      rec.revertStatus = 'succeeded'
      rec.version += 1
      const rd = resourceDriftOf(st, rec.resourceId)
      for (const f of rec.fields) if (!f.ignored && !rd.synced.includes(f.path)) rd.synced.push(f.path)
      if (rec.status === 'open') {
        rec.status = 'closed'
        rec.closedAt = job.endedAt ?? job.startedAt
        rec.resolution = 'reverted'
        rd.generation += 1
        rd.closedAt = Date.parse(rec.closedAt)
      }
    } else if (FAILED.has(job.status)) {
      rec.revertStatus = 'failed'
      rec.version += 1
    }
  }
}

/* ----------------------------------------------------------------------------
   Findings: the scan with the decisions laid over it
   ------------------------------------------------------------------------- */

function mayUpdate(data: MockData, s: Session, rt: ResourceType, wfrn: string): boolean {
  return authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `${serviceOf(rt)}:update`, wfrn).allowed
}

function viewOf(rec: FindingRecord, extra: Partial<Finding> = {}): Finding {
  return {
    findingId: rec.findingId,
    wfrn: rec.wfrn,
    accountId: rec.accountId,
    region: rec.region,
    type: rec.type,
    status: rec.status,
    severity: rec.severity,
    version: rec.version,
    fields: rec.fields,
    openedAt: rec.openedAt,
    observedAt: rec.observedAt,
    closedAt: rec.closedAt,
    resolution: rec.resolution,
    decidedBy: rec.decidedBy,
    reason: rec.reason,
    revertJobId: rec.revertJobId,
    revertStatus: rec.revertStatus,
    ignoredUntil: rec.ignoredUntil,
    can: [],
    ...extra,
  }
}

/** The open findings of a resource after what people decided: the scan's (./commands findingsOf) with reverts, accepts and ignores laid over them. */
export function openFindingsOf(data: MockData, session: Session, rt: ResourceType, r: Resource, now: number, scanned: Finding[]): Finding[] {
  follow(data)
  const scan = scanned[0]
  if (!scan) return []
  const st = peek(data)
  const rd = st?.resources[r.id]
  const digest = digestOf(r.attributes)
  const active = (x: ResourceDrift['silenced'][number]) => x.digest === digest && (x.until === undefined || now < x.until)
  const fields = scan.fields.filter((f) => !rd?.synced.includes(f.path)).map((f) => (rd?.silenced.some((x) => x.path === f.path && active(x)) ? { ...f, ignored: true } : f))
  if (fields.length === 0 || fields.every((f) => f.ignored)) return []
  const rec = st ? Object.values(st.decisions).find((x) => x.resourceId === r.id && x.status === 'open') : undefined
  const generation = rd?.generation ?? 0
  // a later finding of the same resource opens once its silence ended, or after the last one closed
  const ended = (rd?.silenced ?? []).filter((x) => !active(x)).map((x) => (x.until !== undefined && x.until <= now ? x.until : Date.parse(r.updatedAt ?? r.createdAt)))
  const reopenedAt = generation > 0 ? Math.max(rd?.closedAt ?? 0, ...ended) : 0
  const openedAt = rec?.openedAt ?? (generation === 0 ? scan.openedAt : iso(Math.min(now, reopenedAt)))
  const running = rec?.revertStatus === 'running'
  const may = mayUpdate(data, session, rt, scan.wfrn)
  const can: NonNullable<Finding['can']> = may ? [...(!running && !notRevertableOf(rt, fields) ? (['revert'] as const) : []), ...(!running ? (['accept'] as const) : []), 'ignore'] : []
  return [
    {
      findingId: rec?.findingId ?? (generation === 0 ? scan.findingId : `drift_${idFrom(`${r.id}:${generation}`, 8)}`),
      wfrn: scan.wfrn,
      accountId: accountOfResource(r, accountIdsOf(data)),
      region: session.region,
      type: rt.name,
      status: 'open',
      severity: graded(r, scan.severity),
      version: rec?.version ?? 1,
      fields,
      openedAt,
      observedAt: scan.observedAt && Date.parse(scan.observedAt) < Date.parse(openedAt) ? openedAt : scan.observedAt,
      ...(rec?.revertJobId ? { revertJobId: rec.revertJobId, revertStatus: rec.revertStatus, decidedBy: rec.decidedBy, reason: rec.reason } : {}),
      can,
    },
  ]
}

/** One finding of the seeded history: closed in the last 30 days, by whom and why. */
interface HistoryEntry {
  resourceId: string
  path: string
  expected: unknown
  observed: unknown
  daysAgo: number
  minutesToClose: number
  resolution: Resolution
  by?: string
  reason?: string
  ignoreDays?: number
}

const HISTORY: HistoryEntry[] = [
  { resourceId: 'vm-demo01', path: 'cpu', expected: 4, observed: 6, daysAgo: 12, minutesToClose: 38, resolution: 'reverted', by: 'dana', reason: 'Resized by hand during the incident; the change request never went through' },
  { resourceId: 'vm-demo02', path: 'memoryGb', expected: 8, observed: 12, daysAgo: 20, minutesToClose: 95, resolution: 'ignored', by: 'mateo', reason: 'Load test this week', ignoreDays: 7 },
  { resourceId: 'vm-demo03', path: 'cpu', expected: 2, observed: 4, daysAgo: 6, minutesToClose: 260, resolution: 'accepted', by: 'gonzalo', reason: 'Four CPUs is the new baseline for the web tier' },
  { resourceId: 'vm-demo05', path: 'memoryGb', expected: 16, observed: 24, daysAgo: 3, minutesToClose: 130, resolution: 'vanished' },
  { resourceId: 'vm-demo04', path: 'memoryGb', expected: 8, observed: 16, daysAgo: 1.5, minutesToClose: 22, resolution: 'reverted', by: 'mateo' },
  { resourceId: 'database-demo01', path: 'tier', expected: 'M10', observed: 'M30', daysAgo: 9, minutesToClose: 45, resolution: 'accepted', by: 'raj', reason: 'Scaled up for the sale; keep it' },
  { resourceId: 'database-demo02', path: 'version', expected: '8.0', observed: '7.0', daysAgo: 15, minutesToClose: 600, resolution: 'vanished' },
]

function historyOf(data: MockData, s: Session, rt: ResourceType, r: Resource, now: number): Finding[] {
  const out: Finding[] = []
  HISTORY.forEach((h, i) => {
    if (h.resourceId !== r.id) return
    const opened = Math.floor((now - h.daysAgo * DAY_MS) / 60_000) * 60_000
    const closed = opened + h.minutesToClose * 60_000
    out.push({
      findingId: `drift_${idFrom(`${r.id}:history:${i}`, 8)}`,
      wfrn: wfrnOfResource(data, s, rt, r),
      accountId: accountOfResource(r, accountIdsOf(data)),
      region: s.region,
      type: rt.name,
      status: 'closed',
      severity: graded(r, 'warning'),
      version: h.resolution === 'vanished' ? 2 : 3,
      fields: [{ path: h.path, expected: h.expected, observed: h.observed, ...(h.resolution === 'ignored' ? { ignored: true } : {}) }],
      openedAt: iso(opened),
      observedAt: iso(closed),
      closedAt: iso(closed),
      resolution: h.resolution,
      decidedBy: h.by,
      reason: h.reason,
      ignoredUntil: h.ignoreDays ? iso(closed + h.ignoreDays * DAY_MS) : undefined,
      can: [],
    })
  })
  return out
}

/** The closed findings of a resource, newest first, for its Drift tab's history. */
export function closedFindingsOf(data: MockData, session: Session, rt: ResourceType, r: Resource, now: number): Finding[] {
  follow(data)
  const decided = Object.values(peek(data)?.decisions ?? {})
    .filter((x) => x.resourceId === r.id && x.status === 'closed')
    .map((x) => viewOf(x))
  return [...decided, ...historyOf(data, session, rt, r, now)].sort((a, b) => (b.closedAt ?? '').localeCompare(a.closedAt ?? ''))
}

interface Located {
  finding: Finding
  rt: ResourceType
  r: Resource | undefined
}

/** Every finding of the account, open and closed, with its resource; the open ones of a resource that left the inventory too, for the decisions to refuse. */
function accountFindings(data: MockData, s: Session, now: number): Located[] {
  const ids = accountIdsOf(data)
  const out: Located[] = []
  for (const r of data.resources) {
    if (accountOfResource(r, ids) !== s.accountId) continue
    const rt = typeOf(data, r.type)
    if (!rt) continue
    for (const finding of [...findingsOf(data, s, rt, r, now), ...closedFindingsOf(data, s, rt, r, now)]) out.push({ finding, rt, r })
  }
  const listed = new Set(out.map((x) => x.finding.findingId))
  for (const rec of Object.values(peek(data)?.decisions ?? {})) {
    if (rec.accountId !== s.accountId || rec.status !== 'open' || listed.has(rec.findingId)) continue
    const rt = typeOf(data, rec.type)
    if (rt) out.push({ finding: viewOf(rec), rt, r: data.resources.find((x) => x.id === rec.resourceId) })
  }
  return out
}

const SEVERITY_RANK: Record<Severity, number> = { critical: 0, warning: 1, info: 2 }

function globMatch(pattern: string, value: string): boolean {
  if (!pattern.includes('*')) return pattern === value
  const re = new RegExp(`^${pattern.split('*').map((p) => p.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`)
  return re.test(value)
}

/* ----------------------------------------------------------------------------
   Unmanaged objects: what each account's discovery finds
   ------------------------------------------------------------------------- */

interface ProviderObject {
  /** the account whose discovery run finds it */
  finder: string
  type: string
  providerId: string
  name: string
  observed: Record<string, unknown>
  tags?: Record<string, string>
}

const COMPUTE = '123456789012'
const DATA = '210987654321'

/** Where each account's resources sit at the providers: an object sharing a placement with one account's resources only is a low guess. */
const PLACEMENTS: Record<string, Record<string, string>> = {
  [COMPUTE]: { folder: '/dc1/compute', project: 'compute-shared' },
  [DATA]: { folder: '/dc2/data', project: 'data-prod' },
}

const vm = (finder: string, n: number, name: string, cpu: number, memoryGb: number, environment: string, extra: { folder?: string; tags?: Record<string, string> } = {}): ProviderObject => ({
  finder,
  type: 'vm',
  providerId: `vsphere:vm-${n}`,
  name,
  observed: { name, cpu, memoryGb, environment, powerState: 'poweredOn', folder: extra.folder ?? '/dc1/shared', ...(extra.tags ? { tags: extra.tags } : {}) },
  tags: extra.tags,
})

const cluster = (finder: string, id: string, name: string, tier: string, version: string, extra: { project?: string; tags?: Record<string, string> } = {}): ProviderObject => ({
  finder,
  type: 'database',
  providerId: `mongodb:${id}`,
  name,
  observed: { name, engine: 'mongodb', version, tier, project: extra.project ?? 'shared', ...(extra.tags ? { tags: extra.tags } : {}) },
  tags: extra.tags,
})

/** Twelve machines and four clusters across the two accounts, each with what makes its owner guessable, or not. */
const PROVIDER_OBJECTS: ProviderObject[] = [
  vm(COMPUTE, 5102, 'compute-scratch-01', 4, 16, 'dev'),
  vm(COMPUTE, 5118, 'pay-gw-test', 2, 8, 'test', { tags: { team: 'team-data' } }),
  vm(COMPUTE, 5121, 'tmp-build-agent', 8, 32, 'dev', { folder: '/dc1/compute' }),
  vm(COMPUTE, 5133, 'old-jenkins', 4, 16, 'prod'),
  vm(COMPUTE, 5140, 'data-etl-spare', 16, 64, 'prod'),
  vm(COMPUTE, 5146, 'web-canary-02', 2, 4, 'test', { tags: { owner: 'team-compute' } }),
  vm(COMPUTE, 5152, 'win-desktop-qa', 2, 8, 'test'),
  vm(COMPUTE, 5169, 'cache-warm-03', 4, 8, 'prod', { tags: { account: COMPUTE } }),
  vm(DATA, 6201, 'mongo-backup-runner', 2, 8, 'prod', { tags: { team: 'team-data' } }),
  vm(DATA, 6214, 'compute-overflow-04', 8, 16, 'prod'),
  vm(DATA, 6229, 'analytics-sandbox', 16, 32, 'dev', { folder: '/dc2/data' }),
  vm(DATA, 6230, 'unnamed-vm', 2, 4, 'dev'),
  cluster(COMPUTE, '65f1a2c4b1', 'reports-db', 'M10', '7.0', { tags: { team: 'team-data' } }),
  cluster(COMPUTE, '65f1a2c4c8', 'compute-metrics-db', 'M30', '8.0'),
  cluster(DATA, '65f1a2c4d3', 'ledger-archive', 'M60', '6.0', { project: 'data-prod' }),
  cluster(DATA, '65f1a2c4e9', 'test-cluster-old', 'M10', '7.0'),
]

const keyOf = (type: string, providerId: string) => `${type}:${providerId}`

/** The owner guess, by the drift service's rules: a tag naming an account's team or id is high, the account's name in the object's name medium, a placement only one account uses low. */
export function ownerGuessOf(data: MockData, o: Pick<ProviderObject, 'name' | 'tags' | 'observed'>): OwnerGuess | undefined {
  const accounts = data.directory.accounts.filter((a) => a.status === 'active')
  const found: { accountId: string; rank: number; reason: string }[] = []
  for (const a of accounts) {
    for (const [k, v] of Object.entries(o.tags ?? {})) {
      if (v === a.owningTeam) found.push({ accountId: a.accountId, rank: 3, reason: `Tagged ${k}=${v}, the team that owns ${a.name}` })
      if (v === a.accountId) found.push({ accountId: a.accountId, rank: 3, reason: `Tagged ${k}=${v}, the id of ${a.name}` })
    }
    if (o.name.toLowerCase().split(/[^a-z0-9]+/).includes(a.name.toLowerCase())) found.push({ accountId: a.accountId, rank: 2, reason: `The name contains the account’s name, ${a.name}` })
    for (const [k, v] of Object.entries(PLACEMENTS[a.accountId] ?? {})) {
      if (o.observed[k] === v) found.push({ accountId: a.accountId, rank: 1, reason: `In ${k} ${v}, which only ${a.name}’s resources use` })
    }
  }
  if (!found.length) return undefined
  const best = found.reduce((x, y) => (y.rank > x.rank ? y : x))
  const account = accounts.find((a) => a.accountId === best.accountId)!
  return { accountId: account.accountId, accountName: account.name, team: account.owningTeam, confidence: best.rank === 3 ? 'high' : best.rank === 2 ? 'medium' : 'low', reasons: found.filter((x) => x.accountId === best.accountId).sort((x, y) => y.rank - x.rank).map((x) => x.reason) }
}

/** The runs of a type in an account that have ended by `now`, oldest first. */
function endedRuns(st: DriftState | undefined, accountId: string, type: string, now: number): RunRecord[] {
  return Object.values(st?.runs ?? {})
    .filter((x) => x.accountId === accountId && x.type === type && x.endsAt <= now)
    .sort((a, b) => a.endsAt - b.endsAt)
}

/** The objects an account sees: what its own finished runs found, and what another account assigned to it; never an imported one. */
function visibleObjects(data: MockData, accountId: string, now: number): ProviderObject[] {
  const st = peek(data)
  return PROVIDER_OBJECTS.filter((o) => {
    const key = keyOf(o.type, o.providerId)
    if (st?.imported[key]) return false
    const assignment = st?.assignments[key]
    if (assignment && (assignment.accountId === accountId || assignment.fromAccountId === accountId)) return endedRuns(st, o.finder, o.type, now).length > 0
    return o.finder === accountId && endedRuns(st, accountId, o.type, now).length > 0
  })
}

function objectView(data: MockData, s: Session, o: ProviderObject, now: number): UnmanagedObject {
  const st = peek(data)
  const rt = typeOf(data, o.type)
  const runs = endedRuns(st, o.finder, o.type, now)
  const price = rt ? priceOf(rt, o.observed) : undefined
  const allowed = !!rt && authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `${serviceOf(rt)}:create`, wfrnPatternOf(data, s, rt)).allowed
  const ownerGuess = ownerGuessOf(data, o)
  const assignment = st?.assignments[keyOf(o.type, o.providerId)]
  return {
    providerId: o.providerId,
    type: o.type,
    region: s.region,
    name: o.name,
    observed: o.observed,
    seenAt: iso(runs[runs.length - 1]?.endsAt ?? now),
    firstSeenAt: iso(runs[0]?.endsAt ?? now),
    ...(ownerGuess ? { ownerGuess } : {}),
    ...(price ? { monthlyCost: { amount: price.monthly, currency: 'USD', basis: price.detail } } : {}),
    ...(assignment ? { assignment } : {}),
    can: allowed ? ['import', 'assign'] : [],
  }
}

function runView(data: MockData, run: RunRecord, now: number): DiscoveryRun {
  const done = now >= run.endsAt
  const share = done ? 1 : Math.max(0, Math.min(1, (now - run.startedAt) / (run.endsAt - run.startedAt)))
  const ids = accountIdsOf(data)
  const managed = data.resources.filter((r) => r.type === run.type && accountOfResource(r, ids) === run.accountId).length
  const unmanaged = PROVIDER_OBJECTS.filter((o) => o.finder === run.accountId && o.type === run.type && !peek(data)?.imported[keyOf(o.type, o.providerId)]).length
  return { runId: run.runId, type: run.type, region: run.region, status: done ? 'succeeded' : 'running', startedAt: iso(run.startedAt), ...(done ? { endedAt: iso(run.endsAt) } : {}), seen: Math.floor((managed + unmanaged) * share), unmanaged: Math.floor(unmanaged * share) }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

/** Answers the findings, discovery and unmanaged paths, or null for any other path. */
export function driftRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!path.startsWith('/console/v1/findings') && !path.startsWith('/console/v1/discover') && !path.startsWith('/console/v1/unmanaged')) return null
  const s = ctx.session
  const now = ctx.now
  const can = (action: string, resource: string) => authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  const listable = (rt: ResourceType) => can(`${serviceOf(rt)}:list`, wfrnPatternOf(data, s, rt)).allowed
  const command = (kind: ConsoleCommand['kind'], type: string, body: unknown, wfrn?: string): ConsoleCommand => ({ at: now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type, wfrn, body, ids: {} })
  // a retried write with a key already answered gets that answer, whatever changed since (./commands journaled)
  const key = ctx.headers['idempotency-key']
  if (method === 'POST' && key && data.console?.replies[key]) return journaled(data, ctx, command('drift.replayed', '', ctx.body))

  /* findings */
  if (path === '/console/v1/findings') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const status = params.get('status') ?? 'open'
    if (!['open', 'closed', 'all'].includes(status)) return bad([{ level: 'error', code: 'enum', field: 'status', message: 'status is open, closed or all' }])
    const severity = params.get('severity')
    if (severity && !(severity in SEVERITY_RANK)) return bad([{ level: 'error', code: 'enum', field: 'severity', message: 'severity is info, warning or critical' }])
    const openedAfter = params.get('openedAfter')
    if (openedAfter && Number.isNaN(Date.parse(openedAfter))) return bad([{ level: 'error', code: 'format', field: 'openedAfter', message: 'openedAfter is a date and time' }])
    const type = params.get('type')
    const wfrn = params.get('wfrn')
    const rows = accountFindings(data, s, now)
      .filter((x) => x.r && !deleted(x.rt, x.r) && listable(x.rt))
      .map((x) => x.finding)
      .filter((f) => (status === 'all' || f.status === status) && (!severity || f.severity === severity) && (!type || f.type === type) && (!wfrn || globMatch(wfrn, f.wfrn)) && (!openedAfter || Date.parse(f.openedAt) > Date.parse(openedAfter)))
      .sort((a, b) => (a.status === b.status ? (a.status === 'open' ? SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || b.openedAt.localeCompare(a.openedAt) || a.wfrn.localeCompare(b.wfrn) : (b.closedAt ?? '').localeCompare(a.closedAt ?? '')) : a.status === 'open' ? -1 : 1))
    return pageOf(rows, params)
  }
  if (path === '/console/v1/findings/summary' && method === 'GET') {
    const open = accountFindings(data, s, now).filter((x) => x.finding.status === 'open' && x.r && !deleted(x.rt, x.r) && listable(x.rt))
    const summary: FindingsSummary = { open: open.length, byType: {}, bySeverity: {} }
    for (const { finding } of open) {
      const type = finding.type ?? 'unknown'
      summary.byType[type] = (summary.byType[type] ?? 0) + 1
      summary.bySeverity[finding.severity] = (summary.bySeverity[finding.severity] ?? 0) + 1
    }
    const ids = accountIdsOf(data)
    if (data.resources.some((r) => accountOfResource(r, ids) === s.accountId && observedAtOf(r, now))) summary.lastScanAt = iso(now - 9 * 60_000)
    return { status: 200, body: summary }
  }
  if (path === '/console/v1/findings/stats' && method === 'GET') {
    const until = params.get('until') ? Date.parse(params.get('until')!) : now
    const since = params.get('since') ? Date.parse(params.get('since')!) : until - 30 * DAY_MS
    if (Number.isNaN(since) || Number.isNaN(until)) return bad([{ level: 'error', code: 'format', field: Number.isNaN(since) ? 'since' : 'until', message: 'since and until are dates and times' }])
    if (since > until) return bad([{ level: 'error', code: 'range', field: 'since', message: 'since comes before until' }])
    const all = accountFindings(data, s, now).map((x) => x.finding)
    const within = (at: string | undefined) => !!at && Date.parse(at) >= since && Date.parse(at) <= until
    const opened = all.filter((f) => within(f.openedAt))
    const closed = all.filter((f) => f.status === 'closed' && within(f.closedAt))
    const count = (r: Resolution) => closed.filter((f) => f.resolution === r).length
    const stats: FindingStats = {
      accountId: s.accountId,
      since: iso(since),
      until: iso(until),
      opened: opened.length,
      closed: { total: closed.length, reverted: count('reverted'), accepted: count('accepted'), ignored: count('ignored'), vanished: count('vanished') },
      open: all.filter((f) => f.status === 'open').length,
      bySeverity: opened.reduce<Partial<Record<Severity, number>>>((acc, f) => ({ ...acc, [f.severity]: (acc[f.severity] ?? 0) + 1 }), {}),
      meanMinutesToClose: closed.length ? Math.round((closed.reduce((sum, f) => sum + (Date.parse(f.closedAt!) - Date.parse(f.openedAt)), 0) / closed.length / 60_000) * 10) / 10 : null,
    }
    return { status: 200, body: stats }
  }
  let m = /^\/console\/v1\/findings\/([^/:]+)(?::(revert|accept|ignore))?$/.exec(path)
  if (m) {
    const findingId = decodeURIComponent(m[1])
    const verb = m[2] as 'revert' | 'accept' | 'ignore' | undefined
    const found = accountFindings(data, s, now).find((x) => x.finding.findingId === findingId)
    if (!found) return { status: 404, body: { error: 'finding.notFound', detail: { findingId } } }
    const { finding, rt, r } = found
    const service = serviceOf(rt)
    if (!verb) {
      if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
      const d = can(`${service}:get`, finding.wfrn)
      return d.allowed ? { status: 200, body: finding } : refused(d, path, now)
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = can(`${service}:update`, finding.wfrn)
    if (!d.allowed) return refused(d, path, now)
    const body = ctx.body ?? {}
    const reason = body.reason
    if (reason !== undefined && (typeof reason !== 'string' || reason.length > 1000)) return bad([{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is text of at most 1,000 characters' }])
    let until: string | undefined
    if (verb === 'ignore' && body.until !== undefined) {
      const at = typeof body.until === 'string' ? Date.parse(body.until) : NaN
      if (Number.isNaN(at)) return bad([{ level: 'error', code: 'format', field: '/until', message: 'until is a date and time' }])
      if (at <= now) return bad([{ level: 'error', code: 'minimum', field: '/until', message: 'until is in the future' }])
      until = iso(at)
    }
    if (finding.status === 'closed') return { status: 409, body: { error: 'finding.closed', detail: { findingId, resolution: finding.resolution, closedAt: finding.closedAt, decidedBy: finding.decidedBy } } }
    if (!r || deleted(rt, r)) return { status: 409, body: { error: 'finding.unwatched', detail: { findingId, wfrn: finding.wfrn } } }
    if (verb !== 'ignore' && finding.revertStatus === 'running') return { status: 409, body: { error: 'finding.reverting', detail: { findingId, revertJobId: finding.revertJobId } } }
    if (verb === 'revert') {
      const why = notRevertableOf(rt, finding.fields)
      if (why) return { status: 409, body: { error: 'finding.notRevertable', detail: { findingId, why, fields: finding.fields.filter((f) => !f.ignored).map((f) => f.path) } } }
      if (data.console?.inFlight.some((f) => f.resourceId === r.id)) return { status: 409, body: { error: 'resource.busy', detail: `A request is already changing this ${(rt.console?.title ?? rt.plural).replace(/s$/, '').toLowerCase()}; wait for it to finish, then revert` } }
    }
    const decision = { findingId, ...(typeof reason === 'string' && reason.trim() ? { reason: reason.trim() } : {}), ...(until ? { until } : {}) }
    return journaled(data, ctx, command(`drift.${verb}`, rt.name, decision, finding.wfrn))
  }

  /* discovery */
  if (path === '/console/v1/discover') {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const name = typeof ctx.body?.type === 'string' ? ctx.body.type : ''
    const rt = typeOf(data, name)
    if (!rt) return bad([{ level: 'error', code: 'required', field: '/type', message: name ? `No resource type ${name}` : 'Name the type to discover' }])
    if (!rt.observe?.discover?.action) return { status: 404, body: { error: 'discover.notSupported', detail: { type: rt.name } } }
    const d = can(`${serviceOf(rt)}:observe`, wfrnPatternOf(data, s, rt))
    if (!d.allowed) return refused(d, path, now)
    const running = Object.values(peek(data)?.runs ?? {}).find((x) => x.accountId === s.accountId && x.region === s.region && x.type === rt.name && x.endsAt > now)
    if (running) return { status: 409, body: { error: 'discover.running', detail: runView(data, running, now) } }
    return journaled(data, ctx, command('unmanaged.discover', rt.name, { type: rt.name }))
  }
  m = /^\/console\/v1\/discover\/([^/:]+)$/.exec(path)
  if (m) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const runId = decodeURIComponent(m[1])
    const run = peek(data)?.runs[runId]
    if (!run || run.accountId !== s.accountId) return { status: 404, body: { error: 'discover.notFound', detail: { runId } } }
    return { status: 200, body: runView(data, run, now) }
  }

  /* unmanaged objects */
  if (path === '/console/v1/unmanaged') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const type = params.get('type')
    const owner = params.get('owner') ?? 'any'
    if (!['any', 'mine', 'unknown'].includes(owner)) return bad([{ level: 'error', code: 'enum', field: 'owner', message: 'owner is any, mine or unknown' }])
    const rows = visibleObjects(data, s.accountId, now)
      .filter((o) => {
        const rt = typeOf(data, o.type)
        return (!type || o.type === type) && !!rt && listable(rt)
      })
      .map((o) => objectView(data, s, o, now))
      .filter((o) => owner === 'any' || (owner === 'unknown' ? !o.ownerGuess : o.ownerGuess?.accountId === s.accountId || o.assignment?.accountId === s.accountId))
      .sort((a, b) => Number(b.assignment?.accountId === s.accountId) - Number(a.assignment?.accountId === s.accountId) || (b.monthlyCost?.amount ?? 0) - (a.monthlyCost?.amount ?? 0) || (a.name ?? '').localeCompare(b.name ?? ''))
    return pageOf(rows, params)
  }
  m = /^\/console\/v1\/unmanaged:(import|assign)$/.exec(path)
  if (m) {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const body = ctx.body ?? {}
    const rt = typeof body.type === 'string' ? typeOf(data, body.type) : undefined
    if (!rt) return bad([{ level: 'error', code: 'required', field: '/type', message: 'Name the type of the object' }])
    const providerId = typeof body.providerId === 'string' ? body.providerId : ''
    const o = visibleObjects(data, s.accountId, now).find((x) => x.type === rt.name && x.providerId === providerId)
    if (!o) {
      const gone = peek(data)?.imported[keyOf(rt.name, providerId)]
      return gone ? { status: 409, body: { error: 'unmanaged.imported', detail: { wfrn: gone.wfrn } } } : { status: 404, body: { error: 'unmanaged.notFound', detail: { type: rt.name, providerId } } }
    }
    const d = can(`${serviceOf(rt)}:create`, wfrnPatternOf(data, s, rt))
    if (!d.allowed) return refused(d, path, now)
    if (m[1] === 'import') {
      const name = body.name
      if (name !== undefined && (typeof name !== 'string' || !/^[a-z0-9][a-z0-9-]{0,62}$/.test(name))) return bad([{ level: 'error', code: 'pattern', field: '/name', message: 'A name is 1 to 63 lower-case letters, digits and dashes, starting with a letter or digit' }])
      return journaled(data, ctx, command('unmanaged.import', rt.name, { type: rt.name, providerId, ...(typeof name === 'string' ? { name } : {}) }))
    }
    const accountId = typeof body.accountId === 'string' ? body.accountId : ''
    const target = accountOf(data.directory, accountId)
    if (!/^\d{12}$/.test(accountId) || !target || target.status !== 'active' || accountId === s.accountId) return { status: 400, body: { error: 'unmanaged.unknownAccount', problems: [{ level: 'error', code: 'unmanaged.unknownAccount', field: '/accountId', message: accountId === s.accountId ? 'The object is already in this account’s list; name another team’s account' : `No active account has the id ${accountId || '(none)'}` }] } }
    const note = body.note
    if (note !== undefined && (typeof note !== 'string' || note.length > 500)) return bad([{ level: 'error', code: 'maxLength', field: '/note', message: 'The note is text of at most 500 characters' }])
    return journaled(data, ctx, command('unmanaged.assign', rt.name, { type: rt.name, providerId, accountId, ...(typeof note === 'string' && note.trim() ? { note: note.trim() } : {}) }))
  }
  return null
}

/* ----------------------------------------------------------------------------
   The commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

/** The record of the open finding a decision acts on, made from the finding the first time someone decides. */
function recordFor(st: DriftState, f: Finding, r: Resource, rt: ResourceType, cmd: ConsoleCommand): FindingRecord {
  const rec = (st.decisions[f.findingId] ??= {
    findingId: f.findingId,
    resourceId: r.id,
    type: rt.name,
    wfrn: f.wfrn,
    accountId: cmd.accountId,
    region: cmd.region,
    severity: f.severity,
    fields: f.fields,
    openedAt: f.openedAt,
    observedAt: f.observedAt,
    status: 'open',
    version: f.version ?? 1,
    attempts: 0,
  })
  rec.fields = f.fields
  rec.observedAt = f.observedAt
  return rec
}

/** Applies a journaled `drift.*` or `unmanaged.*` command, when it runs and when the journal replays it. */
export function applyDriftCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  // a replay brings the jobs to this command's time first, as the server's clock had when it ran
  settleConsole(data, cmd.at)
  const st = stateOf(data)
  const rt = typeOf(data, cmd.type)
  if (!rt) return { status: 404, body: { error: `No type ${cmd.type}` } }
  const s: Session = { userId: cmd.userId, accountId: cmd.accountId, region: cmd.region }
  const body = (isRecord(cmd.body) ? cmd.body : {}) as { findingId?: string; reason?: string; until?: string; providerId?: string; name?: string; accountId?: string; note?: string }
  const at = cmd.at

  if (cmd.kind.startsWith('drift.')) {
    const r = cmd.wfrn ? resourceByWfrn(data, rt, cmd.wfrn) : undefined
    if (!r) return { status: 409, body: { error: 'finding.unwatched', detail: { wfrn: cmd.wfrn } } }
    const kept = body.findingId ? st.decisions[body.findingId] : undefined
    const open = findingsOf(data, s, rt, r, at).find((f) => f.findingId === body.findingId) ?? (kept?.status === 'open' ? viewOf(kept) : undefined)
    if (!open) return { status: 409, body: { error: 'finding.closed', detail: { findingId: body.findingId } } }
    const rec = recordFor(st, open, r, rt, cmd)
    const rd = resourceDriftOf(st, r.id)
    const live = open.fields.filter((f) => !f.ignored)

    if (cmd.kind === 'drift.revert') {
      rec.attempts += 1
      const requestId = cmd.ids.requestId ?? `creq_${idFrom(`revert:${rec.findingId}:${cmd.userId}:${at}`, 10)}`
      const jobId = cmd.ids.jobId ?? `job_${idFrom(requestId, 10)}`
      const input = Object.fromEntries(live.map((f) => [attributeOf(f.path), f.expected]))
      const update: ConsoleCommand = { at, kind: 'update', userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: rt.name, wfrn: cmd.wfrn, body: { input, reason: body.reason ?? `Revert drift finding ${rec.findingId}`, labels: { 'drift-finding': rec.findingId } }, ids: { requestId, jobId } }
      const started = applyCommand(data, update)
      if (started.status >= 400) return started
      cmd.ids = { ...cmd.ids, requestId, jobId }
      const job = data.jobs.find((j) => j.id === jobId)
      if (job) job.title = `Revert drift on ${r.name ?? r.id}`
      if (rec.attempts === 1 && REFUSES_FIRST_REVERT.has(r.id)) {
        const flight = data.console?.inFlight.find((f) => f.jobId === jobId)
        if (flight) flight.fail = true
      }
      Object.assign(rec, { revertJobId: jobId, revertStatus: 'running', decidedBy: cmd.userId, reason: body.reason, version: rec.version + 1 })
      return { status: 202, body: { findingId: rec.findingId, jobId } }
    }

    if (cmd.kind === 'drift.accept') {
      r.attributes = { ...r.attributes, ...Object.fromEntries(live.map((f) => [attributeOf(f.path), f.observed])) }
      r.updatedAt = iso(at)
      for (const f of live) if (!rd.synced.includes(f.path)) rd.synced.push(f.path)
      Object.assign(rec, { status: 'closed', closedAt: iso(at), resolution: 'accepted', decidedBy: cmd.userId, reason: body.reason, version: rec.version + 1 })
      rd.generation += 1
      rd.closedAt = at
      return { status: 200, body: viewOf(rec) }
    }

    if (cmd.kind === 'drift.ignore') {
      const until = body.until ? Date.parse(body.until) : undefined
      const digest = digestOf(r.attributes)
      for (const f of live) rd.silenced.push({ path: f.path, digest, until, since: at, by: cmd.userId })
      Object.assign(rec, { status: 'closed', closedAt: iso(at), resolution: 'ignored', decidedBy: cmd.userId, reason: body.reason, ignoredUntil: until !== undefined ? iso(until) : undefined, fields: open.fields.map((f) => ({ ...f, ignored: true })), version: rec.version + 1 })
      rd.generation += 1
      rd.closedAt = at
      return { status: 200, body: viewOf(rec) }
    }
    return { status: 404, body: { error: `No command ${cmd.kind}` } }
  }

  if (cmd.kind === 'unmanaged.discover') {
    const runId = cmd.ids.requestId ?? `run_${idFrom(`${cmd.accountId}:${rt.name}:${cmd.userId}:${at}`, 10)}`
    cmd.ids = { ...cmd.ids, requestId: runId }
    st.runs[runId] = { runId, type: rt.name, accountId: cmd.accountId, region: cmd.region, startedAt: at, endsAt: at + DISCOVERY_MS }
    return { status: 202, body: runView(data, st.runs[runId], at) }
  }

  const o = PROVIDER_OBJECTS.find((x) => x.type === rt.name && x.providerId === body.providerId)
  if (!o) return { status: 404, body: { error: 'unmanaged.notFound', detail: { type: rt.name, providerId: body.providerId } } }
  const key = keyOf(o.type, o.providerId)

  if (cmd.kind === 'unmanaged.import') {
    if (st.imported[key]) return { status: 409, body: { error: 'unmanaged.imported', detail: { wfrn: st.imported[key].wfrn } } }
    const service = serviceOf(rt)
    const tail = o.providerId.slice(o.providerId.indexOf(':') + 1)
    const resourceId = cmd.ids.resourceId ?? (tail.startsWith(`${rt.name}-`) ? tail : `${rt.name}-${tail}`)
    const wfrn = cmd.ids.wfrn ?? `wfrn:${data.partition}:${service}:${cmd.region}:${cmd.accountId}:${rt.name}/${resourceId}`
    const jobId = cmd.ids.jobId ?? `job_${idFrom(`import:${key}:${at}`, 10)}`
    cmd.ids = { ...cmd.ids, resourceId, wfrn, jobId }
    const owner = accountOf(data.directory, cmd.accountId)?.owningTeam ?? `team-${service}`
    const name = body.name ?? o.name
    const known = rt.schema?.properties ?? {}
    const attributes: Record<string, unknown> = Object.fromEntries(Object.entries(o.observed).filter(([k, v]) => k in known && typeof v !== 'object'))
    attributes.name = name
    if ('owner' in known) attributes.owner = owner
    const active = rt.states?.find((x) => lifecycleOf(x, rt.console?.lifecycle) === 'active') ?? 'ready'
    // the import is recorded as a request of its own, so the resource's page can say who took it under management
    const job: JobSummary = { id: jobId, workflow: 'discover-import', title: `Import ${name} from discovery`, version: '1.0.0', status: 'succeeded', requester: cmd.userId, channel: 'console', startedAt: iso(at), endedAt: iso(at), durationMs: 0, kind: 'single', accountId: cmd.accountId, region: cmd.region, wfrn, consoleRequestId: `creq_${idFrom(jobId, 10)}` }
    data.jobs.unshift(job)
    const r: Resource = { id: resourceId, type: rt.name, name, status: active, attributes, owner, visibility: 'team', createdBy: { jobId, workflow: 'discover-import', version: '1.0.0' }, builtFrom: [], createdAt: iso(at), updatedAt: iso(at), wfrn, accountId: cmd.accountId, region: cmd.region, providerId: o.providerId }
    data.resources.unshift(r)
    st.imported[key] = { wfrn, accountId: cmd.accountId, by: cmd.userId, at }
    delete st.assignments[key]
    return { status: 201, body: { wfrn } }
  }

  if (cmd.kind === 'unmanaged.assign') {
    const target = accountOf(data.directory, body.accountId ?? '')
    st.assignments[key] = { accountId: body.accountId ?? '', accountName: target?.name, fromAccountId: cmd.accountId, by: cmd.userId, ...(body.note ? { note: body.note } : {}), at: iso(at) }
    return { status: 200, body: objectView(data, s, o, at) }
  }

  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}
