import { lifecycleOf } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import { WEIGHTS, fillDefault, scoreOf, type Counts, type Evaluation, type HeatmapRow, type PackScore, type RemediationSpec, type ResourceCompliance, type RuleResult, type RulePackSummary, type RuleStatus, type Scorecard, type Severity } from '../lib/scorecards'
import type { Resource, ResourceType } from '../lib/types'
import { applyCommand, accountIdsOf, busyOn, emptyConsoleState, idFrom, iso, isRecord, journaled, observedAtOf, pageOf, refused, resourceByWfrn, subjectFieldOf, toConsoleResource, typeOf, verbEntryOf, wfrnOfResource, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { accountOf, authorize, membershipsOf, type Decision } from './directory'
import { accountOfResource } from './resources'

/*
 * Compliance scorecards (WP 9.6, hub decision 26), as the BFF's /console/v1/scorecards routes
 * answer them over the drift service of hub PR #19: the hub's three rule packs (rule-packs/*.yaml
 * on console-root--stack: required tags, the compute baseline and the data baseline), judged here
 * by an embedded engine that reads each resource the way the packs' `check` and `when` do: its
 * tags, its effective attributes (what the provider reports over what was asked for), whether it
 * runs in production, and what vSphere last reported of its power. Three production machines are
 * powered off (vm-gen-011, vm-gen-026 and vm-demo05) until a restart of them succeeds.
 *
 * A fix is a journaled command (`scorecard.remediate`): a tags fix sets the tags through the same
 * write as the Tags drawer, a workflow fix starts the type's verb as the person, labelled with the
 * rule, so it shows on the Requests page and the resource's page like any other request; the rule
 * passes once that request succeeded. The trend is made up for the days before today (a slow climb
 * to today's live score); every number of today is worked out from the resources at each read.
 */

interface RuleSpec {
  id: string
  title: string
  description?: string
  severity: Severity
  types: string[]
  /** null: the rule does not apply to the resource */
  judge: (r: Facts) => { status: RuleStatus; message?: string } | null
  remediation?: RemediationSpec
}

interface PackSpec {
  name: string
  title: string
  description: string
  version: number
  owner: string
  rules: RuleSpec[]
}

/** What the engine reads of a resource, as the packs' paths name it. */
interface Facts {
  type: string
  tags: Record<string, string>
  attributes: Record<string, unknown>
  effective: Record<string, unknown>
  production: boolean
  observed: { powerState?: string }
}

const pass = { status: 'pass' as const }
const fail = (message: string) => ({ status: 'fail' as const, message })
const present = (v: unknown) => v !== undefined && v !== null && v !== ''

const tagsFix = (title: string, input: RemediationSpec['inputs'][number]): RemediationSpec => ({ title, kind: 'tags', inputs: [input] })

export const PACKS: PackSpec[] = [
  {
    name: 'required-tags',
    title: 'Required tags',
    description: 'Every resource carries the four tags the organization requires, with values from the agreed lists.',
    version: 1,
    owner: 'platform-governance',
    rules: [
      { id: 'owner', title: 'Names its owner', description: 'The owner tag names the team that answers for the resource.', severity: 'warning', types: ['*'], judge: (r) => (present(r.tags.owner) ? pass : fail('No owner tag; tag the team that owns this resource.')), remediation: tagsFix('Set the owner tag', { name: 'owner', title: 'Owning team', type: 'string', required: true, default: '{{ $resource.owner }}' }) },
      { id: 'cost-centre', title: 'Names its cost centre', description: 'The cost-centre tag is the finance code the resource is charged to (CC- and four digits).', severity: 'warning', types: ['*'], judge: (r) => (/^CC-[0-9]{4}$/.test(r.tags['cost-centre'] ?? '') ? pass : fail('No valid cost-centre tag; tag the CC-nnnn code the resource is charged to.')), remediation: tagsFix('Set the cost-centre tag', { name: 'costCentre', title: 'Cost centre (CC-nnnn)', type: 'string', required: true }) },
      { id: 'data-class', title: 'Declares its data class', description: 'The data-class tag is one of public, internal, confidential or restricted.', severity: 'warning', types: ['*'], judge: (r) => (['public', 'internal', 'confidential', 'restricted'].includes(r.tags['data-class'] ?? '') ? pass : fail('No valid data-class tag; use public, internal, confidential or restricted.')), remediation: tagsFix('Set the data-class tag', { name: 'dataClass', title: 'Data class', type: 'string', enum: ['public', 'internal', 'confidential', 'restricted'], default: 'internal', required: true }) },
      { id: 'environment', title: 'Declares its environment', description: 'The environment tag is one of dev, test, staging or prod.', severity: 'info', types: ['*'], judge: (r) => (['dev', 'test', 'staging', 'prod'].includes(r.tags.environment ?? '') ? pass : fail('No valid environment tag; use dev, test, staging or prod.')), remediation: tagsFix('Set the environment tag', { name: 'environment', title: 'Environment', type: 'string', enum: ['dev', 'test', 'staging', 'prod'], default: '{{ $resource.environment }}', required: true }) },
    ],
  },
  {
    name: 'compute-baseline',
    title: 'Compute baseline',
    description: 'Production virtual machines have at least 2 vCPUs and 4 GiB of memory, and are powered on.',
    version: 1,
    owner: 'team-compute',
    rules: [
      { id: 'prod-min-cpu', title: 'Production VMs have at least 2 vCPUs', severity: 'warning', types: ['vm'], judge: (r) => (!r.production ? null : Number(r.effective.cpu) >= 2 ? pass : fail('A production VM with fewer than 2 vCPUs.')), remediation: { title: 'Resize to 2 vCPUs', kind: 'workflow', verb: 'update', workflow: 'update-vm', inputs: [{ name: 'cpu', title: 'vCPUs', type: 'integer', default: 2, required: true }] } },
      { id: 'prod-min-memory', title: 'Production VMs have at least 4 GiB of memory', severity: 'info', types: ['vm'], judge: (r) => (!r.production ? null : Number(r.effective.memoryGb) >= 4 ? pass : fail('A production VM with less than 4 GiB of memory.')), remediation: { title: 'Resize to 4 GiB', kind: 'workflow', verb: 'update', workflow: 'update-vm', inputs: [{ name: 'memoryGb', title: 'Memory (GiB)', type: 'integer', default: 4, required: true }] } },
      {
        id: 'prod-powered-on',
        title: 'Production VMs are powered on',
        description: 'Judged only once vSphere has reported a power state.',
        severity: 'critical',
        types: ['vm'],
        judge: (r) => (!r.production || !r.observed.powerState ? null : r.observed.powerState === 'poweredOn' ? pass : fail(`A production VM that is not powered on (${r.observed.powerState}).`)),
        remediation: { title: 'Restart the VM', kind: 'workflow', verb: 'restart', workflow: 'vm-restart', inputs: [{ name: 'reason', title: 'Reason', type: 'string', default: 'Compliance: a production VM was not powered on', required: false }] },
      },
    ],
  },
  {
    name: 'data-baseline',
    title: 'Data baseline',
    description: 'Databases run a supported MongoDB version, production databases are not on the M10 tier, and connection strings live in Vault.',
    version: 1,
    owner: 'team-data',
    rules: [
      { id: 'supported-version', title: 'Runs a supported MongoDB version (7.0 or 8.0)', description: 'Judged once the version is known.', severity: 'critical', types: ['database'], judge: (r) => (!present(r.effective.version) ? null : ['7.0', '8.0'].includes(String(r.effective.version)) ? pass : fail(`MongoDB ${String(r.effective.version)} is out of support; upgrade to 7.0 or 8.0.`)), remediation: { title: 'Upgrade MongoDB', kind: 'workflow', verb: 'upgrade', workflow: 'mongodb-upgrade', inputs: [{ name: 'targetVersion', title: 'Target version', type: 'string', enum: ['7.0', '8.0'], default: '8.0', required: true }] } },
      { id: 'prod-tier', title: 'Production databases are not on the M10 tier', description: 'M10 is a shared development tier with no backup guarantee.', severity: 'warning', types: ['database'], judge: (r) => (!r.production ? null : r.effective.tier && r.effective.tier !== 'M10' ? pass : fail('A production database on the M10 development tier.')) },
      { id: 'secret-in-vault', title: 'Keeps its connection string in Vault', severity: 'critical', types: ['database'], judge: (r) => (!present(r.attributes.secret) ? null : /^vault:.+/.test(String(r.attributes.secret)) ? pass : fail('The connection string is not a Vault reference.')) },
    ],
  },
]

/** The production machines vSphere reports powered off, until a restart of them succeeds. */
export const POWERED_OFF = new Set(['vm-gen-011', 'vm-gen-026', 'vm-demo05'])

const DAY_MS = 86_400_000

export interface RemediationRecord {
  wfrn: string
  pack: string
  ruleId: string
  kind: 'workflow' | 'tags'
  jobId?: string
  by: string
  at: number
}

export interface ScorecardsState {
  remediations: RemediationRecord[]
}

export function emptyScorecardsState(): ScorecardsState {
  return { remediations: [] }
}

function stateOf(data: MockData): ScorecardsState {
  const st = (data.console ??= emptyConsoleState())
  return (st.scorecards ??= emptyScorecardsState())
}

type Session = RouteContext['session']

/* ----------------------------------------------------------------------------
   The engine
   ------------------------------------------------------------------------- */

function factsOf(data: MockData, s: Session, rt: ResourceType, r: Resource, now: number): Facts {
  const view = toConsoleResource(data, s, rt, r, now)
  const effective = { ...view.attributes, ...(view.observed ?? {}) }
  const production = view.attributes.environment === 'prod' || view.tags.environment === 'prod'
  let powerState: string | undefined
  if (rt.name === 'vm' && observedAtOf(r, now)) {
    const restarted = data.jobs.some((j) => j.workflow === 'vm-restart' && j.status === 'succeeded' && (j.wfrn === view.wfrn || data.console?.jobs[j.id]?.resourceId === r.id))
    powerState = POWERED_OFF.has(r.id) && !restarted ? 'poweredOff' : 'poweredOn'
  }
  return { type: rt.name, tags: view.tags, attributes: view.attributes, effective, production, observed: { powerState } }
}

interface Judged {
  r: Resource
  rt: ResourceType
  wfrn: string
  pack: PackSpec
  rule: RuleSpec
  status: RuleStatus
  message?: string
  since: number
}

/** The account's live resources, each rule that applies judged. */
function judgedIn(data: MockData, s: Session, now: number): Judged[] {
  const ids = accountIdsOf(data)
  const out: Judged[] = []
  for (const r of data.resources) {
    if (accountOfResource(r, ids) !== s.accountId) continue
    const rt = typeOf(data, r.type)
    if (!rt || (lifecycleOf(r.status, rt.console?.lifecycle) ?? 'active') === 'deleted') continue
    const facts = factsOf(data, s, rt, r, now)
    const wfrn = wfrnOfResource(data, s, rt, r)
    for (const pack of PACKS) {
      for (const rule of pack.rules) {
        if (!rule.types.includes('*') && !rule.types.includes(rt.name)) continue
        const verdict = rule.judge(facts)
        if (!verdict) continue
        // how long it has been so: from a seeded day in the resource's life, never before it existed
        const created = Date.parse(r.createdAt)
        const seeded = now - (1 + (parseInt(idFrom(`${r.id}:${rule.id}`, 4), 36) % 40)) * DAY_MS
        const fixed = stateOf(data).remediations.filter((x) => x.wfrn === wfrn && x.ruleId === rule.id).map((x) => x.at)
        const since = verdict.status === 'pass' && fixed.length ? Math.max(...fixed) : Math.max(created, seeded)
        out.push({ r, rt, wfrn, pack, rule, status: verdict.status, message: verdict.message, since })
      }
    }
  }
  return out
}

function countsOf(rows: readonly { status: RuleStatus }[]): Counts {
  return { passed: rows.filter((x) => x.status === 'pass').length, failed: rows.filter((x) => x.status === 'fail').length }
}

const weighed = (rows: readonly Judged[]) => scoreOf(rows.map((x) => ({ severity: x.rule.severity, status: x.status })))

export function scorecardOf(data: MockData, s: Session, now: number, days = 30): Scorecard {
  const judged = judgedIn(data, s, now)
  const score = weighed(judged)
  const packs: PackScore[] = PACKS.map((pack) => {
    const mine = judged.filter((x) => x.pack === pack)
    return {
      name: pack.name,
      title: pack.title,
      version: pack.version,
      score: weighed(mine),
      ...countsOf(mine),
      rules: pack.rules.map((rule) => {
        const rows = mine.filter((x) => x.rule === rule)
        return { id: rule.id, title: rule.title, severity: rule.severity, score: weighed(rows), remediable: !!rule.remediation, ...countsOf(rows) }
      }),
    }
  })
  const resources = new Set(judged.map((x) => x.r.id))
  const failing = new Set(judged.filter((x) => x.status === 'fail').map((x) => x.r.id))
  const bySeverity = Object.fromEntries((['critical', 'warning', 'info'] as Severity[]).map((sev) => [sev, countsOf(judged.filter((x) => x.rule.severity === sev))])) as Scorecard['bySeverity']
  const trend: Scorecard['trend'] = []
  for (let d = days - 1; d >= 0; d--) {
    const day = new Date(now - d * DAY_MS).toISOString().slice(0, 10)
    if (d === 0 || score === null) trend.push({ day, score })
    else {
      const wobble = (parseInt(idFrom(`${s.accountId}:${day}`, 2), 36) % 5) - 2
      trend.push({ day, score: Math.max(0, Math.min(100, score - Math.round(d * 0.4) + wobble)) })
    }
  }
  const evaluatedAt = judged.length ? iso(Math.max(...judged.map((x) => Math.min(now, Date.parse(x.r.updatedAt ?? x.r.createdAt))))) : undefined
  return { accountId: s.accountId, score, ...countsOf(judged), resources: resources.size, failingResources: failing.size, bySeverity, packs, trend, ...(evaluatedAt ? { evaluatedAt } : {}) }
}

function packSummaries(): RulePackSummary[] {
  return PACKS.map((p) => ({ name: p.name, title: p.title, description: p.description, version: p.version, owner: p.owner, rules: p.rules.map((r) => ({ id: r.id, title: r.title, ...(r.description ? { description: r.description } : {}), severity: r.severity, types: r.types, ...(r.remediation ? { remediation: r.remediation } : {}) })) }))
}

function evaluationOf(x: Judged, s: Session, now: number): Evaluation {
  return { wfrn: x.wfrn, accountId: s.accountId, region: s.region, type: x.rt.name, name: x.r.name ?? x.r.id, pack: x.pack.name, ruleId: x.rule.id, severity: x.rule.severity, status: x.status, ...(x.message ? { message: x.message } : {}), since: iso(x.since), evaluatedAt: iso(Math.min(now, Math.max(x.since, Date.parse(x.r.updatedAt ?? x.r.createdAt)))) }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

function decisionOf(data: MockData, s: Session, action: string, resource: string): Decision {
  const d = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  // scorecards:* is the console's own action: the platform's role for the account never decides it
  if (!d.allowed && d.denied?.layer === 'platform' && action.startsWith('scorecards:')) return { allowed: true }
  return d
}

const correlationOf = (now: number, path: string) => `mock-${idFrom(`${now}:${path}`, 8)}`

function accountPattern(data: MockData, accountId: string): string {
  return `wfrn:${data.partition}:*:*:${accountId}:*`
}

export function scorecardRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const now = ctx.now
  const compliance = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)\/compliance$/.exec(path)
  if (!path.startsWith('/console/v1/scorecards') && !compliance) return null
  const key = ctx.headers['idempotency-key']
  if (method === 'POST' && key && data.console?.replies[key]) return journaled(data, ctx, { at: now, kind: 'scorecard.replayed', userId: s.userId, accountId: s.accountId, region: s.region, type: '', body: ctx.body, ids: {} })

  if (compliance) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const rt = typeOf(data, compliance[1])
    if (!rt) return { status: 404, body: { error: `No resource type ${compliance[1]}` } }
    const r = resourceByWfrn(data, rt, decodeURIComponent(compliance[2]))
    if (!r || accountOfResource(r, accountIdsOf(data)) !== s.accountId) return { status: 404, body: { error: 'resource.not-found' } }
    const wfrn = wfrnOfResource(data, s, rt, r)
    for (const [action, on] of [['scorecards:list', accountPattern(data, s.accountId)], [`${serviceOf(rt)}:get`, wfrn]] as const) {
      const d = decisionOf(data, s, action, on)
      if (!d.allowed) return refused(d, correlationOf(now, path))
    }
    const mine = judgedIn(data, s, now).filter((x) => x.r === r)
    const order: Record<RuleStatus, number> = { fail: 0, error: 1, pass: 2 }
    const results: RuleResult[] = mine
      .map((x) => ({ pack: x.pack.name, ruleId: x.rule.id, title: x.rule.title, severity: x.rule.severity, status: x.status, ...(x.message ? { message: x.message } : {}), since: iso(x.since), remediable: !!x.rule.remediation }))
      .sort((a, b) => order[a.status] - order[b.status] || WEIGHTS[b.severity] - WEIGHTS[a.severity] || a.title.localeCompare(b.title))
    const body: ResourceCompliance = { wfrn, accountId: s.accountId, region: s.region, type: rt.name, ...(mine.length ? { evaluatedAt: iso(Math.max(...mine.map((x) => Math.min(now, Math.max(x.since, Date.parse(r.updatedAt ?? r.createdAt)))))) } : {}), ...countsOf(mine), results }
    return { status: 200, body }
  }

  if (path === '/console/v1/scorecards/packs') return method === 'GET' ? { status: 200, body: { items: packSummaries() } } : { status: 405, body: { error: `${method} is not allowed on ${path}` } }

  if (path === '/console/v1/scorecards/heatmap') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const rows: HeatmapRow[] = []
    for (const m of membershipsOf(data.directory, s.userId)) {
      const as = { userId: s.userId, accountId: m.accountId, region: s.region }
      if (!decisionOf(data, as, 'scorecards:list', accountPattern(data, m.accountId)).allowed) continue
      const card = scorecardOf(data, as, now, 1)
      rows.push({ accountId: m.accountId, accountName: accountOf(data.directory, m.accountId)?.name, score: card.score, failed: card.failed, packs: Object.fromEntries(card.packs.map((p) => [p.name, p.score])) })
    }
    rows.sort((a, b) => (a.score ?? 101) - (b.score ?? 101) || (a.accountName ?? '').localeCompare(b.accountName ?? ''))
    return { status: 200, body: { packs: PACKS.map((p) => ({ name: p.name, title: p.title })), rows } }
  }

  const listDecision = decisionOf(data, s, 'scorecards:list', accountPattern(data, s.accountId))
  if (path === '/console/v1/scorecards') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    if (!listDecision.allowed) return refused(listDecision, correlationOf(now, path))
    const raw = params.get('days')
    const days = raw === null ? 30 : Number(raw)
    if (!Number.isInteger(days) || days < 1 || days > 400) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'range', field: 'days', message: 'days is a whole number from 1 to 400' }] } }
    return { status: 200, body: scorecardOf(data, s, now, days) }
  }

  const m = /^\/console\/v1\/scorecards\/packs\/([a-z0-9-]+)\/rules\/([a-z0-9-]+)(\/failing|:remediate)$/.exec(path)
  if (!m) return null
  const pack = PACKS.find((p) => p.name === m[1])
  const rule = pack?.rules.find((r) => r.id === m[2])
  if (!pack || !rule) return { status: 404, body: { error: 'rule.notFound', detail: { pack: m[1], ruleId: m[2] } } }
  if (m[3] === '/failing') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    if (!listDecision.allowed) return refused(listDecision, correlationOf(now, path))
    const type = params.get('type')
    const rows = judgedIn(data, s, now)
      .filter((x) => x.pack === pack && x.rule === rule && x.status === 'fail' && (!type || x.rt.name === type))
      .sort((a, b) => a.since - b.since || (a.r.name ?? a.r.id).localeCompare(b.r.name ?? b.r.id))
      .map((x) => evaluationOf(x, s, now))
    return pageOf(rows, params)
  }

  /* :remediate */
  if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  const body = ctx.body ?? {}
  const wfrn = typeof body.wfrn === 'string' ? body.wfrn : ''
  if (!wfrn) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'required', field: '/wfrn', message: 'Name the resource to fix' }] } }
  if (body.input !== undefined && !isRecord(body.input)) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'type', field: '/input', message: 'The fix’s inputs are an object, by name' }] } }
  if (body.reason !== undefined && (typeof body.reason !== 'string' || body.reason.length > 1000)) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is text of at most 1,000 characters' }] } }
  const found = judgedIn(data, s, now).find((x) => x.pack === pack && x.rule === rule && x.wfrn === wfrn)
  if (!found) return { status: 404, body: { error: 'evaluation.notFound', detail: { wfrn } } }
  const d = decisionOf(data, s, 'scorecards:remediate', wfrn)
  if (!d.allowed) return refused(d, correlationOf(now, path))
  const fix = rule.remediation
  if (!fix) return { status: 409, body: { error: 'remediation.none', message: `${rule.title} has no one-click fix; change the resource yourself` } }
  const service = serviceOf(found.rt)
  const dd = decisionOf(data, s, fix.kind === 'tags' ? `${service}:tag` : `${service}:${fix.verb}`, wfrn)
  if (!dd.allowed) return refused(dd, correlationOf(now, path))
  if (found.status === 'pass') return { status: 409, body: { error: 'evaluation.passing', message: 'The resource passes this rule now; nothing to fix' } }
  if (fix.kind === 'workflow' && !verbEntryOf(data, found.rt, fix.verb ?? '')) return { status: 409, body: { error: 'remediation.unavailable', message: `${fix.workflow ?? fix.verb} is not in the catalog for ${found.rt.plural}, so the fix cannot run here` } }
  if (busyOn(data, found.r)) return { status: 409, body: { error: 'resource.inFlight', message: 'A request is already changing this resource; wait for it to end, then fix it' } }
  const view = toConsoleResource(data, s, found.rt, found.r, now)
  const input: Record<string, unknown> = {}
  for (const i of fix.inputs) {
    const v = isRecord(body.input) && body.input[i.name] !== undefined ? body.input[i.name] : fillDefault(i.default, view)
    if (v === undefined || v === '') {
      if (i.required) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'required', field: `/input/${i.name}`, message: `${i.title} is required` }] } }
      continue
    }
    if (i.enum && !i.enum.includes(v)) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'enum', field: `/input/${i.name}`, message: `${i.title} is one of ${i.enum.join(', ')}` }] } }
    if (i.name === 'costCentre' && !/^CC-[0-9]{4}$/.test(String(v))) return { status: 400, body: { error: 'validation.failed', problems: [{ level: 'error', code: 'pattern', field: `/input/${i.name}`, message: 'A cost centre is CC- and four digits' }] } }
    input[i.name] = i.type === 'integer' ? Number(v) : v
  }
  return journaled(data, ctx, { at: now, kind: 'scorecard.remediate', userId: s.userId, accountId: s.accountId, region: s.region, type: found.rt.name, wfrn, body: { pack: pack.name, ruleId: rule.id, input, ...(typeof body.reason === 'string' && body.reason.trim() ? { reason: body.reason.trim() } : {}) }, ids: {} })
}

/** The tag a tags fix sets from its input. */
const TAG_OF: Record<string, string> = { owner: 'owner', costCentre: 'cost-centre', dataClass: 'data-class', environment: 'environment' }

/** Applies a journaled `scorecard.*` command, when it runs and when the journal replays it. */
export function applyScorecardCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  if (cmd.kind !== 'scorecard.remediate') return { status: 404, body: { error: `No command ${cmd.kind}` } }
  const st = stateOf(data)
  const body = (isRecord(cmd.body) ? cmd.body : {}) as { pack?: string; ruleId?: string; input?: Record<string, unknown>; reason?: string }
  const rule = PACKS.find((p) => p.name === body.pack)?.rules.find((r) => r.id === body.ruleId)
  const rt = typeOf(data, cmd.type)
  const r = rt && cmd.wfrn ? resourceByWfrn(data, rt, cmd.wfrn) : undefined
  if (!rule?.remediation || !rt || !r || !cmd.wfrn) return { status: 404, body: { error: 'evaluation.notFound' } }
  const fix = rule.remediation
  const s = { userId: cmd.userId, accountId: cmd.accountId, region: cmd.region }
  const input = body.input ?? {}
  if (fix.kind === 'tags') {
    const current = toConsoleResource(data, s, rt, r, cmd.at).tags
    const set = Object.fromEntries(Object.entries(input).filter(([k]) => TAG_OF[k]).map(([k, v]) => [TAG_OF[k], String(v)]))
    const tags = { ...current, ...set }
    const answer = applyCommand(data, { at: cmd.at, kind: 'tags', userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: rt.name, wfrn: cmd.wfrn, body: tags, ids: {} })
    if (answer.status >= 400) return answer
    st.remediations.push({ wfrn: cmd.wfrn, pack: body.pack!, ruleId: rule.id, kind: 'tags', by: cmd.userId, at: cmd.at })
    return { status: 200, body: { wfrn: cmd.wfrn, pack: body.pack, ruleId: rule.id, kind: 'tags', tags: set } }
  }
  const verb = fix.verb ?? 'update'
  const requestId = cmd.ids.requestId ?? `creq_${idFrom(`remediate:${cmd.wfrn}:${rule.id}:${cmd.at}`, 10)}`
  const jobId = cmd.ids.jobId ?? `job_${idFrom(requestId, 10)}`
  const labels = { 'scorecard-rule': `${body.pack}/${rule.id}` }
  const reason = body.reason ?? (typeof input.reason === 'string' ? input.reason : `Compliance: ${rule.title}`)
  const entry = verbEntryOf(data, rt, verb)
  const subject = entry ? subjectFieldOf(entry, rt) : undefined
  const change: ConsoleCommand =
    verb === 'update'
      ? { at: cmd.at, kind: 'update', userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: rt.name, wfrn: cmd.wfrn, body: { input, reason, labels }, ids: { requestId, jobId } }
      : { at: cmd.at, kind: 'verb', verb, userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: rt.name, wfrn: cmd.wfrn, body: { input: { ...input, ...(subject ? { [subject]: r.id } : {}) }, reason, labels }, ids: { requestId, jobId } }
  const answer = applyCommand(data, change)
  if (answer.status >= 400) return answer
  cmd.ids = { ...cmd.ids, requestId, jobId }
  const job = data.jobs.find((j) => j.id === jobId)
  if (job) job.title = `${fix.title}: ${r.name ?? r.id}`
  st.remediations.push({ wfrn: cmd.wfrn, pack: body.pack!, ruleId: rule.id, kind: 'workflow', jobId, by: cmd.userId, at: cmd.at })
  return { status: 202, body: { wfrn: cmd.wfrn, pack: body.pack, ruleId: rule.id, kind: 'workflow', jobId, workflow: entry?.workflow ?? fix.workflow } }
}
