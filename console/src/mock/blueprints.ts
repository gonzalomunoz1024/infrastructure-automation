import type { Problem } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import { parameterProblem, stackNameProblem, type Blueprint, type BlueprintParameter, type BlueprintResource, type BlueprintSummary, type PlanAuthorization, type Stack, type StackEvent, type StackPlan, type StackState, type StackStep, type StepState } from '../lib/blueprints'
import type { JobSummary, Resource } from '../lib/types'
import { OPEN, applyCommand, createEntryOf, emptyConsoleState, idFrom, iso, isRecord, journaled, pageOf, refused, settleConsole, typeOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { authorize, type Decision } from './directory'
import { createTagProblems } from './tags'

/*
 * Blueprints and stacks (WP 9.7, hub decision 33), as the state service's /v1/blueprints and
 * /v1/stacks would answer them through the BFF (mirrored under /console/v1; the BFF contract has
 * no such routes yet, README decision 100). The blueprints are the hub's two (`web-app`,
 * `dev-sandbox`, blueprints/*.yaml on console-root--state-blueprints) and `web-tier`, the
 * mock's own: a hardened image, the web server built from it and its data volume, every one of
 * whose services the Compute account's platform role may create.
 *
 * A launch, a teardown and a resume are journaled commands (`stack.*`). What follows them is level
 * triggered from the children, as the state service runs a stack: on every settle of the mock's
 * clock (./commands settleConsole) each step whose dependencies are done starts its child, as
 * the BFF does with a ready step, through the type's create workflow at the moment the last of
 * them ended, named by the stack and the step, with the stack's tags; a child that fails stops
 * the stack and nothing is rolled back; Resume retries the paused child in place (decision 24),
 * or announces again a step that could not start. A teardown deletes the children the blueprint
 * does not retain, the last wave first, each once nothing in the stack depends on it any more,
 * and aborts a create that is still paused. Because every start is worked out from the jobs'
 * own times, a reload that replays the journal starts the same children at the same moments.
 *
 * The mock's catalog answers a delete for every type, so web-tier's volume is deleted at
 * teardown; the hub's blueprints keep theirs (deletionPolicy retain) until its providers publish
 * a delete workflow.
 */

/* ----------------------------------------------------------------------------
   The blueprints
   ------------------------------------------------------------------------- */

const SERVICES: Record<string, string> = { vm: 'compute', image: 'platform', volume: 'platform', database: 'data', environment: 'platform' }

interface BlueprintSource {
  name: string
  version: string
  title: string
  description: string
  owner: string
  tags: string[]
  parameters: Record<string, Omit<BlueprintParameter, 'required'>>
  resources: Record<string, { type: string; title: string; input: Record<string, unknown>; tags?: Record<string, string>; dependsOn?: string[]; deletionPolicy?: 'delete' | 'retain'; deleteInput?: Record<string, unknown> }>
  outputs: Blueprint['outputs']
}

const NAME_PATTERN = '^[a-z][a-z0-9-]{2,20}$'

export const BLUEPRINT_SOURCES: BlueprintSource[] = [
  {
    name: 'web-tier',
    version: '1.2.0',
    title: 'Web tier',
    description: 'A hardened image, the web server built from it and a data volume on the server, launched and torn down together.',
    owner: 'team-compute',
    tags: ['web', 'starter'],
    parameters: {
      appName: { type: 'string', title: 'Application name', description: 'Names the image and the server', pattern: NAME_PATTERN },
      environment: { type: 'string', title: 'Stage', enum: ['dev', 'test', 'prod'], default: 'dev' },
      size: { type: 'string', title: 'Server size', enum: ['small', 'medium', 'large'], default: 'small' },
      os: { type: 'string', title: 'Operating system', enum: ['ubuntu-24.04', 'rhel-9'], default: 'ubuntu-24.04' },
      dataGb: { type: 'integer', title: 'Data volume (GB)', minimum: 10, maximum: 4000, default: 100 },
    },
    resources: {
      image: { type: 'image', title: 'Hardened image', input: { os: '${params.os}', hardening: 'cis-l1', name: '${params.appName}-image' }, deletionPolicy: 'retain' },
      web: { type: 'vm', title: 'Web server', input: { name: '${params.appName}-web', image: '${resources.image.registryId}', size: '${params.size}', environment: '${params.environment}' }, tags: { app: '${params.appName}' }, deleteInput: { reason: 'Stack ${stack.name} torn down: ${teardown.reason}', keepSnapshotDays: 0 } },
      data: { type: 'volume', title: 'Data volume', input: { vmId: '${resources.web.registryId}', sizeGb: '${params.dataGb}', tier: 'standard' } },
    },
    outputs: {
      server: { title: 'Web server', value: '${resources.web.wfrn}' },
      image: { title: 'Image', description: 'The image the server was built from', value: '${resources.image.attributes.name}' },
    },
  },
  {
    name: 'web-app',
    version: '1.0.0',
    title: 'Web application',
    description: 'A hardened image, a web server built from it, its MongoDB database and a data volume on the server, launched and torn down together.',
    owner: 'team-platform',
    tags: ['web', 'starter'],
    parameters: {
      appName: { type: 'string', title: 'Application name', description: 'Names the server, the database and the image', pattern: NAME_PATTERN },
      environment: { type: 'string', title: 'Stage', enum: ['dev', 'test', 'prod'], default: 'dev' },
      size: { type: 'string', title: 'Server size', enum: ['small', 'medium', 'large'], default: 'small' },
      os: { type: 'string', title: 'Operating system', enum: ['ubuntu-24.04', 'rhel-9'], default: 'ubuntu-24.04' },
      dbTier: { type: 'string', title: 'Database tier', enum: ['M10', 'M30', 'M60'], default: 'M10' },
      dataGb: { type: 'integer', title: 'Data volume (GB)', minimum: 10, maximum: 4000, default: 100 },
    },
    resources: {
      image: { type: 'image', title: 'Hardened image', input: { os: '${params.os}', hardening: 'cis-l1', name: '${params.appName}-image' }, deletionPolicy: 'retain' },
      database: { type: 'database', title: 'Database', input: { name: '${params.appName}-db', environment: '${params.environment}', tier: '${params.dbTier}' }, tags: { app: '${params.appName}' }, deletionPolicy: 'retain' },
      web: { type: 'vm', title: 'Web server', dependsOn: ['database'], input: { name: '${params.appName}-web', image: '${resources.image.registryId}', size: '${params.size}', environment: '${params.environment}' }, tags: { app: '${params.appName}' }, deleteInput: { reason: 'Stack ${stack.name} torn down: ${teardown.reason}' } },
      data: { type: 'volume', title: 'Data volume', input: { vmId: '${resources.web.registryId}', sizeGb: '${params.dataGb}', tier: 'standard' }, deletionPolicy: 'retain' },
    },
    outputs: {
      server: { title: 'Web server', value: '${resources.web.wfrn}' },
      database: { title: 'Database', value: '${resources.database.wfrn}' },
      image: { title: 'Image', description: 'The image the server was built from', value: '${resources.image.attributes.name}' },
    },
  },
  {
    name: 'dev-sandbox',
    version: '1.0.0',
    title: 'Developer sandbox',
    description: 'A development VM from an image you pick, with a scratch volume; tear it down when you are done.',
    owner: 'team-compute',
    tags: ['dev'],
    parameters: {
      image: { type: 'string', title: 'Image', description: 'The registered image to build the VM from', 'x-resource': { type: 'image' } },
      size: { type: 'string', title: 'VM size', enum: ['small', 'medium'], default: 'small' },
      scratchGb: { type: 'integer', title: 'Scratch volume (GB)', minimum: 10, maximum: 500, default: 50 },
    },
    resources: {
      vm: { type: 'vm', title: 'Sandbox VM', input: { name: '${stack.name}-vm', image: '${params.image}', size: '${params.size}', environment: 'dev' }, deleteInput: { reason: 'Sandbox ${stack.name} torn down: ${teardown.reason}', keepSnapshotDays: 0 } },
      scratch: { type: 'volume', title: 'Scratch volume', input: { vmId: '${resources.vm.registryId}', sizeGb: '${params.scratchGb}' }, deletionPolicy: 'retain' },
    },
    outputs: { vm: { title: 'Sandbox VM', value: '${resources.vm.wfrn}' } },
  },
]

const REF = /\$\{([a-z]+)\.([A-Za-z0-9_-]+)(?:\.([A-Za-z0-9_.-]+))?\}/g

/** The resources a value names through `${resources.<id>...}`. */
function referencesIn(value: unknown, out: Set<string> = new Set()): Set<string> {
  if (typeof value === 'string') {
    for (const m of value.matchAll(REF)) if (m[1] === 'resources') out.add(m[2])
  } else if (isRecord(value)) {
    for (const v of Object.values(value)) referencesIn(v, out)
  }
  return out
}

/** A blueprint as the launch form draws it: each resource's service and every resource it waits for, and the waves. */
export function blueprintOf(src: BlueprintSource): Blueprint {
  const resources: Record<string, BlueprintResource> = {}
  for (const [id, r] of Object.entries(src.resources)) {
    const deps = new Set(r.dependsOn ?? [])
    referencesIn(r.input, deps)
    resources[id] = { type: r.type, service: SERVICES[r.type] ?? 'platform', title: r.title, input: r.input, ...(r.tags ? { tags: r.tags } : {}), dependsOn: [...deps], deletionPolicy: r.deletionPolicy ?? 'delete', ...(r.deleteInput ? { deleteInput: r.deleteInput } : {}) }
  }
  const depth = new Map<string, number>()
  const depthOf = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    const deps = resources[id]?.dependsOn ?? []
    const d = deps.length ? 1 + Math.max(...deps.map(depthOf)) : 0
    depth.set(id, d)
    return d
  }
  const waves: string[][] = []
  for (const id of Object.keys(resources)) (waves[depthOf(id)] ??= []).push(id)
  const parameters = Object.fromEntries(Object.entries(src.parameters).map(([k, p]) => [k, { ...p, required: p.default === undefined }]))
  return { name: src.name, version: src.version, title: src.title, description: src.description, owner: src.owner, tags: src.tags, parameters, resources, outputs: src.outputs, waves: waves.map((w) => w ?? []) }
}

function summaryOf(bp: Blueprint): BlueprintSummary {
  const counts: Record<string, number> = {}
  for (const r of Object.values(bp.resources)) counts[r.type] = (counts[r.type] ?? 0) + 1
  return { name: bp.name, version: bp.version, title: bp.title, description: bp.description, owner: bp.owner, tags: bp.tags, resources: counts }
}

function blueprintNamed(name: string): Blueprint | undefined {
  const src = BLUEPRINT_SOURCES.find((b) => b.name === name)
  return src ? blueprintOf(src) : undefined
}

/* ----------------------------------------------------------------------------
   The state these routes keep
   ------------------------------------------------------------------------- */

interface StepRecord {
  step: string
  attempt: number
  resourceId?: string
  wfrn?: string
  /** the create's request, then a rerun's */
  jobId?: string
  deleteJobId?: string
  startedAt?: number
  /** a step the BFF could not start: why */
  error?: { code: string; message: string; at: number }
  /** a teardown found its create paused and aborted it: nothing to delete */
  skipped?: boolean
}

interface StackRecord {
  wfrn: string
  accountId: string
  region: string
  name: string
  blueprint: string
  version: string
  parameters: Record<string, unknown>
  tags: Record<string, string>
  launchedBy: string
  createdAt: number
  operation: 'launch' | 'teardown'
  teardownAt?: number
  teardownReason?: string
  teardownBy?: string
  retain: string[]
  resumes: { at: number; by: string }[]
  steps: Record<string, StepRecord>
}

export interface BlueprintsState {
  stacks: Record<string, StackRecord>
}

export function emptyBlueprintsState(): BlueprintsState {
  return { stacks: {} }
}

function stateOf(data: MockData): BlueprintsState {
  const st = (data.console ??= emptyConsoleState())
  return (st.blueprints ??= emptyBlueprintsState())
}

/* ----------------------------------------------------------------------------
   Resolving references
   ------------------------------------------------------------------------- */

interface Scope {
  params: Record<string, unknown>
  stack: { name: string; wfrn: string }
  resources: Record<string, Resource | undefined>
  teardown?: { reason: string }
}

function lookup(scope: Scope, root: string, key: string, rest: string | undefined): unknown {
  if (root === 'params') return scope.params[key]
  if (root === 'stack') return key === 'name' ? scope.stack.name : key === 'wfrn' ? scope.stack.wfrn : undefined
  if (root === 'teardown') return key === 'reason' ? scope.teardown?.reason : undefined
  if (root === 'resources') {
    const r = scope.resources[key]
    if (!r) return undefined
    if (rest === 'registryId') return r.id
    if (rest === 'wfrn') return r.wfrn
    if (rest === 'name') return r.name
    if (rest?.startsWith('attributes.')) return r.attributes?.[rest.slice('attributes.'.length)]
  }
  return undefined
}

/** A value with its references resolved: a value that is one reference keeps its type; one left unresolved stays as text. */
function resolve(value: unknown, scope: Scope): unknown {
  if (typeof value === 'string') {
    const whole = /^\$\{([a-z]+)\.([A-Za-z0-9_-]+)(?:\.([A-Za-z0-9_.-]+))?\}$/.exec(value)
    if (whole) {
      const v = lookup(scope, whole[1], whole[2], whole[3])
      return v === undefined ? value : v
    }
    return value.replace(REF, (all, root: string, key: string, rest: string | undefined) => {
      const v = lookup(scope, root, key, rest)
      return v === undefined ? all : String(v)
    })
  }
  if (isRecord(value)) return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolve(v, scope)]))
  return value
}

function withDefaults(bp: Blueprint, params: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, p] of Object.entries(bp.parameters)) if (p.default !== undefined) out[k] = p.default
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') out[k] = v
  return out
}

function parameterProblems(data: MockData, bp: Blueprint, params: unknown): Problem[] {
  if (params !== undefined && !isRecord(params)) return [{ level: 'error', code: 'type', field: '/parameters', message: 'The parameters are an object, by name' }]
  const given = (params ?? {}) as Record<string, unknown>
  const problems: Problem[] = []
  for (const k of Object.keys(given)) if (!(k in bp.parameters)) problems.push({ level: 'error', code: 'unknown', field: `/parameters/${k}`, message: `${bp.title} takes no parameter ${k}` })
  for (const [k, p] of Object.entries(bp.parameters)) {
    const why = parameterProblem(p, given[k])
    if (why) problems.push({ level: 'error', code: 'parameter', field: `/parameters/${k}`, message: why })
    const xr = p['x-resource']?.type
    const v = given[k]
    if (!why && xr && typeof v === 'string' && v && !data.resources.some((r) => r.type === xr && r.id === v)) problems.push({ level: 'error', code: 'x-resource', field: `/parameters/${k}`, message: `No ${xr} ${v} is registered` })
  }
  return problems
}

/* ----------------------------------------------------------------------------
   Reading a stack: its steps from the children
   ------------------------------------------------------------------------- */

const ENDED_BADLY = new Set(['failed', 'cancelled', 'rolled-back'])

function jobOf(data: MockData, id: string | undefined): JobSummary | undefined {
  return id ? data.jobs.find((j) => j.id === id) : undefined
}

function resourceOf(data: MockData, id: string | undefined): Resource | undefined {
  return id ? data.resources.find((r) => r.id === id) : undefined
}

function scopeOf(data: MockData, rec: StackRecord): Scope {
  return { params: rec.parameters, stack: { name: rec.name, wfrn: rec.wfrn }, resources: Object.fromEntries(Object.values(rec.steps).map((s) => [s.step, resourceOf(data, s.resourceId)])), teardown: rec.teardownReason ? { reason: rec.teardownReason } : undefined }
}

/** A step's state, as its child's requests leave it. */
function stepStateOf(data: MockData, rec: StackRecord, bp: Blueprint, s: StepRecord): StepState {
  const create = jobOf(data, s.jobId)
  if (rec.operation === 'teardown') {
    if (s.skipped || (!s.jobId && !s.error)) return 'skipped'
    if (!create && s.error) return 'skipped'
    const res = bp.resources[s.step]
    if (res.deletionPolicy === 'retain' || rec.retain.includes(s.step)) return 'retained'
    const del = jobOf(data, s.deleteJobId)
    if (del) return del.status === 'succeeded' ? 'deleted' : del.status === 'needs-attention' || ENDED_BADLY.has(del.status) ? 'failed' : 'deleting'
    return create && OPEN.has(create.status) && create.status !== 'needs-attention' ? 'running' : 'done'
  }
  if (s.error) return 'failed'
  if (!create) return 'waiting'
  if (create.status === 'succeeded') return 'done'
  if (create.status === 'needs-attention' || ENDED_BADLY.has(create.status)) return 'failed'
  return 'running'
}

function stackStateOf(rec: StackRecord, states: StepState[]): StackState {
  if (rec.operation === 'teardown') return states.every((x) => x === 'deleted' || x === 'retained' || x === 'skipped') ? 'deleted' : 'deleting'
  if (states.some((x) => x === 'failed')) return 'failed'
  return states.every((x) => x === 'done') ? 'active' : 'pending'
}

function decisionOf(data: MockData, s: Pick<RouteContext['session'], 'accountId' | 'userId'>, action: string, resource: string): Decision {
  const d = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  // blueprints:* is the console's own action: the platform's role for the account never decides it
  if (!d.allowed && d.denied?.layer === 'platform' && action.startsWith('blueprints:')) return { allowed: true }
  return d
}

function stackPattern(data: MockData, s: RouteContext['session']): string {
  return `wfrn:${data.partition}:blueprints:${s.region}:${s.accountId}:stack/*`
}

function typePattern(data: MockData, s: Pick<RouteContext['session'], 'region' | 'accountId'>, type: string, service: string): string {
  return `wfrn:${data.partition}:${service}:${s.region}:${s.accountId}:${type}/*`
}

/** When each step's child ended, for the log and for what follows it. */
function endOf(job: JobSummary | undefined): number | undefined {
  return job?.endedAt ? Date.parse(job.endedAt) : undefined
}

function stackView(data: MockData, rec: StackRecord, s: RouteContext['session'] | null): Stack {
  const bp = blueprintNamed(rec.blueprint)!
  const steps: StackStep[] = Object.keys(bp.resources).map((id) => {
    const r = bp.resources[id]
    const sr = rec.steps[id] ?? { step: id, attempt: 1 }
    const state = stepStateOf(data, rec, bp, sr)
    const child = resourceOf(data, sr.resourceId)
    const create = jobOf(data, sr.jobId)
    const del = jobOf(data, sr.deleteJobId)
    const job = rec.operation === 'teardown' && del ? del : create
    const action: StackStep['action'] = rec.operation === 'teardown' && (state === 'deleting' || (state === 'failed' && del)) ? 'delete' : state === 'waiting' || state === 'running' || state === 'failed' ? 'create' : undefined
    const errorOf = (): StackStep['error'] => {
      if (sr.error) return { code: sr.error.code, message: sr.error.message, at: iso(sr.error.at) }
      if (state !== 'failed' || !job) return undefined
      return { code: job.errorClass ?? 'provider', message: job.status === 'needs-attention' ? `Its request paused on the step ${job.failedStep ?? 'apply'}: the provider refused it` : `Its request ${job.status.replace(/-/g, ' ')}`, at: job.endedAt ?? job.startedAt }
    }
    return {
      step: id,
      type: r.type,
      service: r.service,
      title: r.title,
      state,
      ...(action ? { action } : {}),
      attempt: sr.attempt,
      dependsOn: r.dependsOn,
      deletionPolicy: rec.retain.includes(id) ? 'retain' : r.deletionPolicy,
      ...(child?.wfrn ? { wfrn: child.wfrn, name: child.name ?? child.id } : {}),
      ...(child ? { childState: child.status } : {}),
      ...(job ? { jobId: job.id } : {}),
      ...(errorOf() ? { error: errorOf() } : {}),
      ...(job?.startedAt ? { startedAt: job.startedAt } : {}),
      ...(job?.endedAt && !OPEN.has(job.status) ? { endedAt: job.endedAt } : {}),
    }
  })
  const state = stackStateOf(rec, steps.map((x) => x.state))
  const scope = scopeOf(data, rec)
  const outputs = state === 'active' ? Object.fromEntries(Object.entries(bp.outputs).map(([k, o]) => [k, { title: o.title, ...(o.description ? { description: o.description } : {}), value: String(resolve(o.value, scope)) }])) : undefined
  const failed = steps.find((x) => x.state === 'failed')
  const events = eventsOf(data, rec, bp)
  const updatedAt = events.length ? events[events.length - 1].at : iso(rec.createdAt)
  const deletedAt = state === 'deleted' ? updatedAt : undefined
  const can: Stack['can'] = []
  if (s) {
    const tearable = state === 'active' || state === 'failed' || (state === 'deleting' && steps.some((x) => x.state === 'failed'))
    if (tearable && teardownDecision(data, s, rec, bp).allowed) can.push('teardown')
    const waits = steps.some((x) => x.state === 'failed')
    if (waits && decisionOf(data, s, rec.operation === 'teardown' ? 'blueprints:teardown' : 'blueprints:launch', rec.wfrn).allowed) can.push('resume')
  }
  return {
    wfrn: rec.wfrn,
    accountId: rec.accountId,
    region: rec.region,
    name: rec.name,
    blueprint: rec.blueprint,
    blueprintVersion: rec.version,
    state,
    operation: rec.operation,
    version: events.length,
    parameters: rec.parameters,
    tags: rec.tags,
    launchedBy: rec.launchedBy,
    ...(rec.teardownReason ? { teardownReason: rec.teardownReason, teardownBy: rec.teardownBy } : {}),
    steps,
    ...(outputs ? { outputs } : {}),
    ...(failed ? { failure: { step: failed.step, code: failed.error?.code, message: failed.error?.message } } : {}),
    createdAt: iso(rec.createdAt),
    updatedAt,
    ...(deletedAt ? { deletedAt, expiresAt: iso(Date.parse(deletedAt) + 90 * 86_400_000) } : {}),
    can,
  }
}

/** The stack's log, worked out from its record and its children's requests, oldest first. */
function eventsOf(data: MockData, rec: StackRecord, bp: Blueprint): StackEvent[] {
  const raw: { at: number; type: string; step?: string; actor?: string }[] = [{ at: rec.createdAt, type: 'StackRequested', actor: rec.launchedBy }]
  let launched: number | undefined
  const creates: number[] = []
  for (const id of Object.keys(bp.resources)) {
    const sr = rec.steps[id]
    if (!sr) continue
    const create = jobOf(data, sr.jobId)
    if (sr.error) raw.push({ at: sr.error.at, type: 'StackStepFailed', step: id })
    if (create) {
      raw.push({ at: Date.parse(create.startedAt), type: 'StackStepStarted', step: id })
      const end = endOf(create)
      if (create.status === 'succeeded' && end !== undefined) {
        raw.push({ at: end, type: 'StackStepSucceeded', step: id })
        creates.push(end)
      } else if (create.status === 'needs-attention' || ENDED_BADLY.has(create.status)) raw.push({ at: end ?? Date.parse(create.startedAt), type: 'StackStepFailed', step: id })
    }
    const del = jobOf(data, sr.deleteJobId)
    if (del) {
      raw.push({ at: Date.parse(del.startedAt), type: 'StackStepDeleting', step: id })
      const end = endOf(del)
      if (del.status === 'succeeded' && end !== undefined) raw.push({ at: end, type: 'StackStepRemoved', step: id })
      else if (del.status === 'needs-attention' || ENDED_BADLY.has(del.status)) raw.push({ at: end ?? Date.parse(del.startedAt), type: 'StackStepFailed', step: id })
    }
  }
  if (creates.length === Object.keys(bp.resources).length) {
    launched = Math.max(...creates)
    raw.push({ at: launched, type: 'StackLaunched' })
  }
  for (const r of rec.resumes) raw.push({ at: r.at, type: 'StackResumed', actor: r.by })
  if (rec.teardownAt !== undefined) {
    raw.push({ at: rec.teardownAt, type: 'StackTeardownRequested', actor: rec.teardownBy })
    const steps = Object.keys(bp.resources).map((id) => stepStateOf(data, rec, bp, rec.steps[id] ?? { step: id, attempt: 1 }))
    if (stackStateOf(rec, steps) === 'deleted') {
      const ends = Object.values(rec.steps).map((s) => endOf(jobOf(data, s.deleteJobId)) ?? rec.teardownAt!)
      raw.push({ at: Math.max(rec.teardownAt, ...ends), type: 'StackDeleted' })
    }
  }
  raw.sort((a, b) => a.at - b.at)
  return raw.map((e, i) => ({ id: `${rec.wfrn}:${i + 1}`, type: e.type, at: iso(e.at), ...(e.actor ? { actor: e.actor } : {}), data: { version: i + 1, state: 'pending', operation: e.type.includes('Teardown') || e.type.includes('Delet') || e.type === 'StackStepRemoved' ? 'teardown' : 'launch', ...(e.step ? { step: e.step } : {}) } }))
}

/** What a teardown needs: `blueprints:teardown` on the stack and each child's own delete. */
function teardownDecision(data: MockData, s: RouteContext['session'], rec: StackRecord, bp: Blueprint): Decision {
  const d = decisionOf(data, s, 'blueprints:teardown', rec.wfrn)
  if (!d.allowed) return d
  for (const [id, sr] of Object.entries(rec.steps)) {
    const r = bp.resources[id]
    const child = resourceOf(data, sr.resourceId)
    if (!child?.wfrn || r.deletionPolicy === 'retain' || rec.retain.includes(id)) continue
    const dd = decisionOf(data, s, `${r.service}:delete`, child.wfrn)
    if (!dd.allowed) return dd
  }
  return { allowed: true }
}

/* ----------------------------------------------------------------------------
   Moving stacks on: the BFF starting the ready steps
   ------------------------------------------------------------------------- */

let settling = false

/**
 * Starts every step whose time has come, from the children's own end times, then lets the
 * children settle to `now`; called at the end of ./commands settleConsole, so every read of the
 * mock sees the stacks where the clock has them.
 */
export function settleStacks(data: MockData, now: number) {
  const st = data.console?.blueprints
  if (!st || settling) return
  settling = true
  try {
    for (let round = 0; round < 12; round++) {
      let moved = false
      for (const rec of Object.values(st.stacks)) if (advance(data, rec, now)) moved = true
      if (!moved) break
      settleConsole(data, now)
    }
  } finally {
    settling = false
  }
}

/** One pass over a stack: the creates (or the deletes) that may start now. */
function advance(data: MockData, rec: StackRecord, now: number): boolean {
  const bp = blueprintNamed(rec.blueprint)
  if (!bp) return false
  let moved = false
  if (rec.operation === 'launch') {
    const states = Object.keys(bp.resources).map((id) => stepStateOf(data, rec, bp, rec.steps[id] ?? { step: id, attempt: 1 }))
    // a child that failed stops the stack: nothing new starts until it is retried
    if (states.includes('failed')) return false
    for (const id of Object.keys(bp.resources)) {
      const sr = (rec.steps[id] ??= { step: id, attempt: 1 })
      if (sr.jobId || sr.error) continue
      const deps = bp.resources[id].dependsOn
      const depJobs = deps.map((d) => jobOf(data, rec.steps[d]?.jobId))
      if (depJobs.some((j) => !j || j.status !== 'succeeded')) continue
      const at = Math.max(rec.createdAt, ...rec.resumes.map((r) => r.at).filter((t) => t <= now), ...depJobs.map((j) => endOf(j) ?? rec.createdAt))
      if (at > now) continue
      startChild(data, rec, bp, id, at)
      moved = true
    }
    return moved
  }
  // a teardown: each delete once nothing in the stack that depends on it is left
  const start = rec.teardownAt ?? now
  for (const id of Object.keys(bp.resources)) {
    const sr = rec.steps[id]
    if (!sr || sr.deleteJobId || sr.skipped || !sr.resourceId) continue
    const r = bp.resources[id]
    if (r.deletionPolicy === 'retain' || rec.retain.includes(id)) continue
    const create = jobOf(data, sr.jobId)
    if (create && OPEN.has(create.status)) continue
    if (create && create.status !== 'succeeded') {
      sr.skipped = true
      moved = true
      continue
    }
    const dependents = Object.entries(bp.resources).filter(([, x]) => x.dependsOn.includes(id)).map(([d]) => d)
    let ready = true
    let after = start
    for (const d of dependents) {
      const dsr = rec.steps[d]
      const state = stepStateOf(data, rec, bp, dsr ?? { step: d, attempt: 1 })
      if (state === 'skipped' || state === 'retained') continue
      if (state !== 'deleted') {
        ready = false
        break
      }
      after = Math.max(after, endOf(jobOf(data, dsr?.deleteJobId)) ?? start)
    }
    if (!ready || after > now) continue
    deleteChild(data, rec, bp, id, after)
    moved = true
  }
  return moved
}

function childIds(rec: StackRecord, step: string, attempt: number, what: 'create' | 'delete') {
  const requestId = `creq_${idFrom(`${rec.wfrn}:${step}:${attempt}:${what}`, 10)}`
  return { requestId, jobId: `job_${idFrom(requestId, 10)}` }
}

function startChild(data: MockData, rec: StackRecord, bp: Blueprint, id: string, at: number) {
  const r = bp.resources[id]
  const sr = rec.steps[id]
  const rt = typeOf(data, r.type)
  const input = resolve(r.input ?? {}, scopeOf(data, rec)) as Record<string, unknown>
  const tags = { ...rec.tags, ...((resolve(r.tags ?? {}, scopeOf(data, rec)) as Record<string, string>) ?? {}), stack: rec.name }
  const fail = (code: string, message: string) => {
    sr.error = { code, message, at }
  }
  if (!rt || !createEntryOf(data, rt)) return fail('workflow.missing', `No workflow creates ${r.type}`)
  const d = decisionOf(data, { accountId: rec.accountId, userId: rec.launchedBy }, `${r.service}:create`, typePattern(data, rec, r.type, serviceOf(rt)))
  if (!d.allowed) return fail('iam.denied', d.denied?.reason ?? `${rec.launchedBy} may no longer create ${r.type}`)
  const ids = childIds(rec, id, sr.attempt, 'create')
  const cmd: ConsoleCommand = { at, kind: 'create', userId: rec.launchedBy, accountId: rec.accountId, region: rec.region, type: rt.name, body: { input, tags, reason: `Stack ${rec.name}, step ${id}`, labels: { stack: rec.name, 'stack-step': id } }, ids }
  const answer = applyCommand(data, cmd)
  if (answer.status >= 400) return fail('platform.invalidInput', (isRecord(answer.body) && typeof answer.body.error === 'string' ? answer.body.error : null) ?? 'The platform refused the input')
  sr.jobId = cmd.ids.jobId
  sr.resourceId = cmd.ids.resourceId
  sr.wfrn = cmd.ids.wfrn
  sr.startedAt = at
  const job = jobOf(data, sr.jobId)
  if (job) job.title = `${r.title ?? r.type} of stack ${rec.name}`
}

function deleteChild(data: MockData, rec: StackRecord, bp: Blueprint, id: string, at: number) {
  const r = bp.resources[id]
  const sr = rec.steps[id]
  const child = resourceOf(data, sr.resourceId)
  if (!child?.wfrn) return
  const input = resolve(r.deleteInput ?? { reason: `Stack ${rec.name} torn down: ${rec.teardownReason ?? ''}` }, scopeOf(data, rec)) as Record<string, unknown>
  const ids = childIds(rec, id, sr.attempt, 'delete')
  const cmd: ConsoleCommand = { at, kind: 'verb', verb: 'delete', userId: rec.teardownBy ?? rec.launchedBy, accountId: rec.accountId, region: rec.region, type: r.type, wfrn: child.wfrn, body: { input, confirm: true, reason: typeof input.reason === 'string' ? input.reason : rec.teardownReason, labels: { stack: rec.name, 'stack-step': id } }, ids }
  const answer = applyCommand(data, cmd)
  if (answer.status >= 400) return
  sr.deleteJobId = cmd.ids.jobId
  const job = jobOf(data, sr.deleteJobId)
  if (job) job.title = `Delete ${r.title?.toLowerCase() ?? r.type} of stack ${rec.name}`
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

function bad(problems: Problem[], error = 'validation.failed'): RouteAnswer {
  return { status: 400, body: { error, detail: problems.map((p) => p.message).join('; '), problems } }
}

function correlationOf(now: number, path: string): string {
  return `mock-${idFrom(`${now}:${path}`, 8)}`
}

/** The plan of a launch: the steps in their waves with what can be resolved, the counts, and each authorization decided. */
function planOf(data: MockData, s: RouteContext['session'], bp: Blueprint, name: string, params: Record<string, unknown>): StackPlan {
  const parameters = withDefaults(bp, params)
  const scope: Scope = { params: parameters, stack: { name, wfrn: `wfrn:${data.partition}:blueprints:${s.region}:${s.accountId}:stack/(minted at launch)` }, resources: {} }
  const waveOf = new Map(bp.waves.flatMap((w, i) => w.map((id) => [id, i] as const)))
  const steps = Object.entries(bp.resources).map(([id, r]) => ({ step: id, type: r.type, service: r.service, ...(r.title ? { title: r.title } : {}), wave: waveOf.get(id) ?? 0, dependsOn: r.dependsOn, input: resolve(r.input ?? {}, scope) as Record<string, unknown>, deletionPolicy: r.deletionPolicy }))
  const creates: Record<string, number> = {}
  for (const r of Object.values(bp.resources)) creates[r.type] = (creates[r.type] ?? 0) + 1
  const decide = (action: string, resource: string): PlanAuthorization => {
    const d = decisionOf(data, s, action, resource)
    return { action, resource, allowed: d.allowed, ...(d.denied ? { denied: d.denied } : {}) }
  }
  const authorize = [decide('blueprints:launch', stackPattern(data, s)), ...[...new Set(Object.values(bp.resources).map((r) => `${r.service}|${r.type}`))].map((k) => {
    const [service, type] = k.split('|')
    return decide(`${service}:create`, typePattern(data, s, type, service))
  })]
  return { blueprint: bp.name, version: bp.version, name, parameters, steps, creates, authorize }
}

export function blueprintRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!path.startsWith('/console/v1/blueprints') && !path.startsWith('/console/v1/stacks')) return null
  const s = ctx.session
  const now = ctx.now
  const command = (kind: ConsoleCommand['kind'], body: unknown, wfrn?: string): ConsoleCommand => ({ at: now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type: '', wfrn, body, ids: {} })
  const key = ctx.headers['idempotency-key']
  if (method === 'POST' && key && data.console?.replies[key]) return journaled(data, ctx, command('stack.replayed', ctx.body))
  const listDecision = decisionOf(data, s, 'blueprints:list', stackPattern(data, s))

  if (path === '/console/v1/blueprints') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    return { status: 200, body: { items: BLUEPRINT_SOURCES.map((b) => summaryOf(blueprintOf(b))), next: null } }
  }
  let m = /^\/console\/v1\/blueprints\/([a-z][a-z0-9-]{2,40})(:plan)?$/.exec(path)
  if (m) {
    const bp = blueprintNamed(m[1])
    if (!bp) return { status: 404, body: { error: 'blueprint.notFound', detail: { name: m[1] } } }
    if (!m[2]) return method === 'GET' ? { status: 200, body: bp } : { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const name = typeof ctx.body.name === 'string' ? ctx.body.name : ''
    const nameWhy = stackNameProblem(name)
    const problems = [...(nameWhy ? [{ level: 'error' as const, code: 'pattern', field: '/name', message: nameWhy }] : []), ...parameterProblems(data, bp, ctx.body.parameters)]
    if (problems.length) return bad(problems)
    return { status: 200, body: planOf(data, s, bp, name, (ctx.body.parameters ?? {}) as Record<string, unknown>) }
  }

  if (path === '/console/v1/stacks') {
    if (method === 'GET') {
      if (!listDecision.allowed) return refused(listDecision, correlationOf(now, path))
      const state = params.get('state')
      const blueprint = params.get('blueprint')
      const prefix = params.get('name')
      const rows = Object.values(stateOf(data).stacks)
        .filter((r) => r.accountId === s.accountId && r.region === s.region)
        .map((r) => stackView(data, r, s))
        .filter((x) => (!state || x.state === state) && (!blueprint || x.blueprint === blueprint) && (!prefix || x.name.startsWith(prefix)))
        .filter((x) => x.state !== 'deleted' || !x.expiresAt || Date.parse(x.expiresAt) > now)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      return pageOf(rows, params)
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const body = ctx.body
    const bp = typeof body.blueprint === 'string' ? blueprintNamed(body.blueprint) : undefined
    if (!bp) return { status: 404, body: { error: 'blueprint.notFound', detail: { name: body.blueprint } } }
    const name = typeof body.name === 'string' ? body.name : ''
    const nameWhy = stackNameProblem(name)
    const problems = [...(nameWhy ? [{ level: 'error' as const, code: 'pattern', field: '/name', message: nameWhy }] : []), ...parameterProblems(data, bp, body.parameters)]
    if (body.tags !== undefined && !isRecord(body.tags)) problems.push({ level: 'error', code: 'type', field: '/tags', message: 'The tags are an object of text' })
    if (problems.length) return bad(problems)
    if (body.version !== undefined && body.version !== bp.version) return { status: 409, body: { error: 'blueprint.versionChanged', detail: { version: bp.version, asked: body.version }, message: `${bp.title} is at version ${bp.version} now; read it again and check the plan` } }
    // the BFF authorizes the stack and every type it creates before it calls the state service
    const plan = planOf(data, s, bp, name, (body.parameters ?? {}) as Record<string, unknown>)
    const denied = plan.authorize.find((a) => !a.allowed)
    if (denied) return refused({ allowed: false, denied: denied.denied }, correlationOf(now, path))
    const tags = (body.tags ?? {}) as Record<string, string>
    const tagged = createTagProblems(data, s, typeOf(data, Object.values(bp.resources)[0].type)!, plan.parameters, tags)
    if (tagged) return { status: 400, body: { error: tagged.error, detail: tagged.problems.map((p) => p.message).join('; '), problems: tagged.problems } }
    const taken = Object.values(stateOf(data).stacks).find((r) => r.accountId === s.accountId && r.name === name && stackView(data, r, null).state !== 'deleted')
    if (taken) return { status: 409, body: { error: 'stack.nameTaken', message: `The account already has a stack named ${name}`, detail: { wfrn: taken.wfrn } } }
    return journaled(data, ctx, command('stack.launch', { blueprint: bp.name, version: bp.version, name, parameters: plan.parameters, tags }))
  }

  m = /^\/console\/v1\/stacks\/([^/:]+)(?::(teardown|resume)|\/(events))?$/.exec(path)
  if (m) {
    const wfrn = decodeURIComponent(m[1])
    const rec = stateOf(data).stacks[wfrn]
    if (!rec || rec.accountId !== s.accountId) return { status: 404, body: { error: 'stack.notFound', detail: { wfrn } } }
    const bp = blueprintNamed(rec.blueprint)!
    if (m[3]) {
      if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
      const d = decisionOf(data, s, 'blueprints:read', wfrn)
      return d.allowed ? pageOf(eventsOf(data, rec, bp), params) : refused(d, correlationOf(now, path))
    }
    if (!m[2]) {
      if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
      const d = decisionOf(data, s, 'blueprints:read', wfrn)
      if (!d.allowed) return refused(d, correlationOf(now, path))
      const view = stackView(data, rec, s)
      return { status: 200, body: view, headers: { ETag: `"${view.version}"` } }
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const view = stackView(data, rec, s)
    const expected = ctx.body.expectedVersion
    if (expected !== undefined && expected !== view.version) return { status: 409, body: { error: 'stack.versionChanged', message: `The stack moved on to version ${view.version}`, detail: { version: view.version } } }
    if (m[2] === 'teardown') {
      if (view.state === 'deleted') return { status: 409, body: { error: 'stack.deleted', message: 'The stack is already torn down' } }
      const d = teardownDecision(data, s, rec, bp)
      if (!d.allowed) return refused(d, correlationOf(now, path))
      const reason = typeof ctx.body.reason === 'string' ? ctx.body.reason.trim() : ''
      if (reason.length < 10 || reason.length > 1000) return bad([{ level: 'error', code: 'minLength', field: '/reason', message: 'Say why in 10 to 1,000 characters; the delete workflows are given it' }])
      const retain = ctx.body.retain
      if (retain !== undefined && (!Array.isArray(retain) || retain.some((x) => typeof x !== 'string' || !(x in bp.resources)))) return bad([{ level: 'error', code: 'enum', field: '/retain', message: 'Name steps of the blueprint to keep' }])
      if (rec.operation === 'teardown' && !retain) return { status: 200, body: view }
      return journaled(data, ctx, command('stack.teardown', { reason, ...(retain ? { retain } : {}) }, wfrn))
    }
    const d = decisionOf(data, s, rec.operation === 'teardown' ? 'blueprints:teardown' : 'blueprints:launch', wfrn)
    if (!d.allowed) return refused(d, correlationOf(now, path))
    if (!view.steps.some((x) => x.state === 'failed')) return { status: 200, body: view }
    return journaled(data, ctx, command('stack.resume', {}, wfrn))
  }
  return null
}

/* ----------------------------------------------------------------------------
   The commands: run once, journaled, replayed
   ------------------------------------------------------------------------- */

/** Applies a journaled `stack.*` command, when it runs and when the journal replays it. */
export function applyBlueprintCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = stateOf(data)
  const at = cmd.at
  const body = (isRecord(cmd.body) ? cmd.body : {}) as { blueprint?: string; version?: string; name?: string; parameters?: Record<string, unknown>; tags?: Record<string, string>; reason?: string; retain?: string[] }
  const s = { userId: cmd.userId, accountId: cmd.accountId, region: cmd.region }

  if (cmd.kind === 'stack.launch') {
    const bp = blueprintNamed(body.blueprint ?? '')
    if (!bp) return { status: 404, body: { error: 'blueprint.notFound' } }
    const wfrn = cmd.ids.wfrn ?? `wfrn:${data.partition}:blueprints:${cmd.region}:${cmd.accountId}:stack/stack-${idFrom(`${cmd.accountId}:${body.name}:${cmd.userId}:${at}`, 16)}`
    cmd.ids = { ...cmd.ids, wfrn }
    const rec: StackRecord = { wfrn, accountId: cmd.accountId, region: cmd.region, name: body.name ?? '', blueprint: bp.name, version: bp.version, parameters: body.parameters ?? {}, tags: body.tags ?? {}, launchedBy: cmd.userId, createdAt: at, operation: 'launch', retain: [], resumes: [], steps: Object.fromEntries(Object.keys(bp.resources).map((id) => [id, { step: id, attempt: 1 }])) }
    st.stacks[wfrn] = rec
    settleStacks(data, at)
    return { status: 201, body: stackView(data, rec, s) }
  }

  const rec = cmd.wfrn ? st.stacks[cmd.wfrn] : undefined
  if (!rec) return { status: 404, body: { error: 'stack.notFound' } }
  const bp = blueprintNamed(rec.blueprint)!

  if (cmd.kind === 'stack.teardown') {
    if (rec.operation !== 'teardown') {
      rec.operation = 'teardown'
      rec.teardownAt = at
      rec.teardownReason = body.reason
      rec.teardownBy = cmd.userId
      // a create still paused on a failed step will never give a child to delete: it is aborted
      for (const sr of Object.values(rec.steps)) {
        const job = jobOf(data, sr.jobId)
        if (job?.status === 'needs-attention') {
          applyCommand(data, { at, kind: 'job.abort', userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: bp.resources[sr.step].type, wfrn: sr.wfrn, body: null, ids: { jobId: job.id } })
          sr.skipped = true
        }
      }
    }
    if (body.retain) rec.retain = [...new Set([...rec.retain, ...body.retain])]
    settleStacks(data, at)
    return { status: 200, body: stackView(data, rec, s) }
  }

  if (cmd.kind === 'stack.resume') {
    rec.resumes.push({ at, by: cmd.userId })
    for (const [id, sr] of Object.entries(rec.steps)) {
      if (sr.error) {
        sr.error = undefined
        sr.attempt += 1
        continue
      }
      const job = jobOf(data, rec.operation === 'teardown' ? sr.deleteJobId : sr.jobId)
      if (!job) continue
      const retry: ConsoleCommand = { at, kind: 'job.retry', userId: cmd.userId, accountId: cmd.accountId, region: cmd.region, type: bp.resources[id].type, wfrn: sr.wfrn, body: { input: {} }, ids: { jobId: job.id } }
      if (job.status === 'needs-attention') applyCommand(data, retry)
      else if (ENDED_BADLY.has(job.status)) {
        const answer = applyCommand(data, retry)
        const rerun = retry.ids.rerunJobId ?? (isRecord(answer.body) && typeof answer.body.jobId === 'string' ? answer.body.jobId : undefined)
        if (rerun && rerun !== job.id) {
          if (rec.operation === 'teardown') sr.deleteJobId = rerun
          else sr.jobId = rerun
        }
      }
    }
    settleStacks(data, at)
    return { status: 200, body: stackView(data, rec, s) }
  }
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}
