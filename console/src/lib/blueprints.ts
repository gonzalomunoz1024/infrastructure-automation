import { get, page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'
import type { Denial } from './types'

/*
 * Blueprints and stacks (WP 9.7, hub decision 33): a blueprint is a versioned set of resources
 * launched together as a stack, one request whose steps are child resources created in dependency
 * order and torn down in reverse. The state service (contracts/openapi/console-state-service.yaml
 * 1.2.0, /v1/blueprints and /v1/stacks) decides and the BFF executes. The BFF contract has no
 * blueprint or stack route yet, so the console calls the state service's routes mirrored under
 * /console/v1 with the BFF's conventions (the session's account, Idempotency-Key on every write,
 * a 403 with the Denial), plus what the pages need beside them: each authorization of a plan
 * decided for this person, each step's child name and request, and the stack's `can`. They are
 * listed as contract gaps in the README (decision 100).
 */

export type ParameterValue = string | number | boolean

export interface BlueprintParameter {
  type: 'string' | 'integer' | 'boolean'
  title: string
  description?: string
  required: boolean
  default?: ParameterValue
  enum?: (string | number)[]
  pattern?: string
  minimum?: number
  maximum?: number
  /** the value is the registry id of a registered resource of this type */
  'x-resource'?: { type?: string }
}

export interface BlueprintResource {
  type: string
  service: string
  title?: string
  input?: Record<string, unknown>
  tags?: Record<string, string>
  dependsOn: string[]
  deletionPolicy: 'delete' | 'retain'
  deleteInput?: Record<string, unknown>
}

export interface BlueprintSummary {
  name: string
  version: string
  title: string
  description: string
  owner: string
  tags?: string[]
  /** how many resources of each type it launches */
  resources: Record<string, number>
}

export interface Blueprint extends Omit<BlueprintSummary, 'resources'> {
  parameters: Record<string, BlueprintParameter>
  resources: Record<string, BlueprintResource>
  outputs: Record<string, { title: string; description?: string; value: string }>
  /** the resource ids by depth: wave n waits for the waves before it */
  waves: string[][]
}

export interface PlanStep {
  step: string
  type: string
  service: string
  title?: string
  wave: number
  dependsOn: string[]
  input?: Record<string, unknown>
  deletionPolicy: 'delete' | 'retain'
}

/** One authorization the BFF asks before a launch, decided for this person (the console's addition). */
export interface PlanAuthorization {
  action: string
  resource: string
  allowed?: boolean
  denied?: Denial
}

export interface StackPlan {
  blueprint: string
  version: string
  name: string
  parameters: Record<string, unknown>
  steps: PlanStep[]
  creates: Record<string, number>
  authorize: PlanAuthorization[]
}

export type StackState = 'pending' | 'active' | 'failed' | 'deleting' | 'deleted'
export type StepState = 'waiting' | 'ready' | 'running' | 'done' | 'failed' | 'deleting' | 'deleted' | 'retained' | 'skipped'

export interface StackStep {
  step: string
  type: string
  service: string
  title?: string
  state: StepState
  action?: 'create' | 'delete'
  attempt: number
  dependsOn: string[]
  deletionPolicy: 'delete' | 'retain'
  wfrn?: string
  childState?: string
  error?: { code?: string; message?: string; at?: string }
  startedAt?: string
  endedAt?: string
  /** the child's name (the console's addition) */
  name?: string
  /** the child's current request: its create, or its delete in a teardown (the console's addition) */
  jobId?: string
}

export interface Stack {
  wfrn: string
  accountId: string
  region: string
  name: string
  blueprint: string
  blueprintVersion: string
  state: StackState
  operation: 'launch' | 'teardown'
  version: number
  parameters: Record<string, unknown>
  tags?: Record<string, string>
  launchedBy?: string
  teardownReason?: string
  teardownBy?: string
  steps: StackStep[]
  outputs?: Record<string, { title: string; description?: string; value: string }>
  failure?: { step?: string; code?: string; message?: string }
  createdAt: string
  updatedAt: string
  deletedAt?: string
  expiresAt?: string
  /** what this person may do with it now (the console's addition) */
  can?: ('teardown' | 'resume')[]
}

export interface StackEvent {
  id: string
  type: string
  at: string
  actor?: string
  data: { version: number; state: StackState; operation: 'launch' | 'teardown'; step?: string; [k: string]: unknown }
}

export interface LaunchRequest {
  blueprint: string
  version?: string
  name: string
  parameters?: Record<string, unknown>
  tags?: Record<string, string>
}

export interface StackListParams extends ListParams {
  state?: StackState
  blueprint?: string
  name?: string
}

const BASE = '/console/v1'
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const blueprintsApi = {
  list: () => page<BlueprintSummary>(`${BASE}/blueprints`),
  get: (name: string) => get<Blueprint>(`${BASE}/blueprints/${enc(name)}`),
  /** checks the parameters as a launch would (400 with problems) and changes nothing */
  plan: (name: string, body: { name: string; parameters?: Record<string, unknown> }) => request<StackPlan>('POST', `${BASE}/blueprints/${enc(name)}:plan`, body),
  stacks: {
    list: (params?: StackListParams) => page<Stack>(`${BASE}/stacks`, params),
    get: (wfrn: string) => get<Stack>(`${BASE}/stacks/${enc(wfrn)}`),
    events: (wfrn: string, params?: ListParams) => page<StackEvent>(`${BASE}/stacks/${enc(wfrn)}/events`, params),
    /** 201 with the stack, pending, its first wave ready */
    launch: (body: LaunchRequest, key?: string) => request<Stack>('POST', `${BASE}/stacks`, body, keyed(key)),
    /** deletes the children in reverse dependency order; retained ones stay and leave the stack */
    teardown: (wfrn: string, body: { reason: string; retain?: string[]; expectedVersion?: number }, key?: string) => request<Stack>('POST', `${BASE}/stacks/${enc(wfrn)}:teardown`, body, keyed(key)),
    /** starts again what could not: a failed child is retried, a step the BFF could not start is announced again */
    resume: (wfrn: string, body?: { expectedVersion?: number }, key?: string) => request<Stack>('POST', `${BASE}/stacks/${enc(wfrn)}:resume`, body ?? {}, keyed(key)),
  },
}

/* ----------------------------------------------------------------------------
   The words and the form's rules
   ------------------------------------------------------------------------- */

export const STACK_NAME = /^[a-z][a-z0-9-]{2,24}$/
export const TEARDOWN_REASON_MIN = 10

export const STACK_STATE_WORDS: Record<StackState, { label: string; status: string }> = {
  pending: { label: 'Launching', status: 'running' },
  active: { label: 'Active', status: 'succeeded' },
  failed: { label: 'Stopped on a failed step', status: 'failed' },
  deleting: { label: 'Tearing down', status: 'running' },
  deleted: { label: 'Torn down', status: 'cancelled' },
}

export const STEP_STATE_WORDS: Record<StepState, { label: string; status: string }> = {
  waiting: { label: 'Waiting', status: 'queued' },
  ready: { label: 'Starting', status: 'running' },
  running: { label: 'Creating', status: 'running' },
  done: { label: 'Done', status: 'succeeded' },
  failed: { label: 'Failed', status: 'failed' },
  deleting: { label: 'Deleting', status: 'running' },
  deleted: { label: 'Deleted', status: 'cancelled' },
  retained: { label: 'Kept', status: 'succeeded' },
  skipped: { label: 'Skipped', status: 'cancelled' },
}

/** Whether a stack is still moving, so its page follows it. */
export function stackMoving(stack: Pick<Stack, 'state' | 'steps'> | undefined): boolean {
  if (!stack) return false
  return stack.state === 'pending' || stack.state === 'deleting'
}

/** The problems of a stack name, as the state service checks it. */
export function stackNameProblem(name: string): string | null {
  if (!name) return 'Name the stack'
  return STACK_NAME.test(name) ? null : 'A stack name is 3 to 25 lowercase letters, digits and hyphens, starting with a letter'
}

/** The problem of one parameter's value, or null: the type, the choices, the pattern and the range. */
export function parameterProblem(p: BlueprintParameter, value: unknown): string | null {
  const blank = value === undefined || value === null || value === ''
  if (blank) return p.required ? `${p.title} is required` : null
  if (p.type === 'integer') {
    const n = typeof value === 'number' ? value : Number(value)
    if (!Number.isInteger(n)) return `${p.title} is a whole number`
    if (p.minimum !== undefined && n < p.minimum) return `${p.title} is at least ${p.minimum}`
    if (p.maximum !== undefined && n > p.maximum) return `${p.title} is at most ${p.maximum}`
    return null
  }
  if (p.type === 'boolean') return typeof value === 'boolean' ? null : `${p.title} is yes or no`
  const s = String(value)
  if (p.enum && !p.enum.map(String).includes(s)) return `${p.title} is one of ${p.enum.join(', ')}`
  if (p.pattern && !new RegExp(`^(?:${p.pattern.replace(/^\^/, '').replace(/\$$/, '')})$`).test(s)) return `${p.title} does not have the form it needs`
  return null
}

/** The parameters with their defaults, as the form starts. */
export function parameterDefaults(params: Record<string, BlueprintParameter>): Record<string, ParameterValue> {
  const out: Record<string, ParameterValue> = {}
  for (const [k, p] of Object.entries(params)) if (p.default !== undefined) out[k] = p.default
  return out
}

/** What the form sends: integers as numbers, blanks left out. */
export function cleanParameters(params: Record<string, BlueprintParameter>, values: Record<string, unknown>): Record<string, ParameterValue> {
  const out: Record<string, ParameterValue> = {}
  for (const [k, p] of Object.entries(params)) {
    const v = values[k]
    if (v === undefined || v === null || v === '') continue
    out[k] = p.type === 'integer' ? Number(v) : p.type === 'boolean' ? Boolean(v) : String(v)
  }
  return out
}

/** "2 virtual machines, 1 volume": how many of each type, in the types' own words. */
export function countsLine(counts: Record<string, number>, titleOf: (type: string, n: number) => string): string {
  return Object.entries(counts)
    .filter(([, n]) => n > 0)
    .map(([type, n]) => `${n} ${titleOf(type, n)}`)
    .join(', ')
}

/** The steps a teardown deletes, in the order it deletes them: the last wave first. */
export function teardownOrder(stack: Pick<Stack, 'steps'>): { deletes: StackStep[]; keeps: StackStep[]; skips: StackStep[] } {
  const depth = new Map<string, number>()
  const byId = new Map(stack.steps.map((s) => [s.step, s]))
  const depthOf = (id: string, seen: Set<string> = new Set()): number => {
    if (depth.has(id)) return depth.get(id)!
    if (seen.has(id)) return 0
    seen.add(id)
    const s = byId.get(id)
    const d = s && s.dependsOn.length ? 1 + Math.max(...s.dependsOn.map((x) => depthOf(x, seen))) : 0
    depth.set(id, d)
    return d
  }
  const live = stack.steps.filter((s) => !!s.wfrn && s.state !== 'deleted' && s.state !== 'retained')
  return {
    deletes: live.filter((s) => s.deletionPolicy === 'delete').sort((a, b) => depthOf(b.step) - depthOf(a.step) || a.step.localeCompare(b.step)),
    keeps: live.filter((s) => s.deletionPolicy === 'retain'),
    skips: stack.steps.filter((s) => !s.wfrn && s.state !== 'skipped'),
  }
}
