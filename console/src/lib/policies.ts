import type { Problem } from '@iaf/console-kit'
import { WFRN_MESSAGES, isWfrnError, validateWfrnPattern } from '@iaf/wfrn'
import { get, page, request } from './api'
import { BFF_BASE, idempotencyKey } from './bff'
import type { IamPolicy, IamRole, IamStatement, Page } from './types'

/*
 * Custom policies, permission boundaries, guardrails and the policy simulator (WP 7.8), as the
 * identity service defines them (hub: contracts/openapi/console-identity-service.yaml) and the
 * BFF passes them through under /console/v1/iam. The rules the identity service applies to a
 * policy document are here word for word (PolicyValidation), with its wildcard rule (Globs), so
 * the editor says what is wrong while it is typed and the mock refuses what the service refuses.
 * A JSON reader that remembers where each value sits puts every problem on its line.
 */

/* ----------------------------------------------------------------------------
   Shapes
   ------------------------------------------------------------------------- */

export interface PolicyInput {
  name: string
  description?: string
  statements: IamStatement[]
}

export interface RoleInput {
  name?: string
  description?: string
  policies?: string[]
  /** a policy id or name; '' removes it */
  boundary?: string
}

export interface Guardrail {
  guardrailId: string
  name: string
  description?: string
  statements: IamStatement[]
  /** ['*'] for every account, now and later, or account ids */
  accounts: string[]
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface GuardrailInput {
  name?: string
  description?: string
  statements?: IamStatement[]
  accounts?: string[]
}

/** The organization's guardrails, for its admins: the identity service's page, with what the person may do (the BFF's `can`). */
export interface OrgGuardrails extends Page<Guardrail> {
  can: ('create' | 'update' | 'delete')[]
}

export type DecisionScope = 'policy' | 'boundary' | 'guardrail'

/** One check's answer, as `POST /v1/authorize` gives it. */
export interface SimulatedDecision {
  allowed: boolean
  reason?: string
  role?: string
  policyId?: string
  statement?: string
  requestable?: boolean
  suggestedRole?: string
  scope?: DecisionScope
  guardrailId?: string
  elevated?: boolean
}

export interface SimulationResult extends SimulatedDecision {
  action: string
  resource: string
}

export interface PolicySimulation {
  results: SimulationResult[]
  version: number
}

export interface SimulationCheck {
  action: string
  resource: string
  context?: Record<string, unknown>
}

export interface DraftPolicy {
  name?: string
  statements: IamStatement[]
}

export interface PolicySimulationRequest {
  principal: { userId?: string; role?: string; groups?: string[] }
  policies?: DraftPolicy[]
  boundary?: DraftPolicy
  checks: SimulationCheck[]
}

/* ----------------------------------------------------------------------------
   The client
   ------------------------------------------------------------------------- */

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent
const write = (key?: string, version?: number) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey(), ...(version !== undefined ? { 'If-Match': String(version) } : {}) } })

export const policiesApi = {
  policies: {
    list: () => page<IamPolicy>(b('/iam/policies'), { limit: 200 }),
    get: (policyId: string) => get<IamPolicy>(b(`/iam/policies/${enc(policyId)}`)),
    create: (body: PolicyInput, key?: string) => request<IamPolicy>('POST', b('/iam/policies'), body, write(key)),
    /** the whole document, against the version read: 409 when it moved on */
    replace: (policyId: string, body: PolicyInput, version: number, key?: string) => request<IamPolicy>('PUT', b(`/iam/policies/${enc(policyId)}`), body, write(key, version)),
    /** 409 while a role holds it or is bounded by it, naming them */
    remove: (policyId: string) => request<void>('DELETE', b(`/iam/policies/${enc(policyId)}`), undefined, write()),
  },
  roles: {
    list: () => page<IamRole>(b('/iam/roles'), { limit: 200 }),
    create: (body: RoleInput, key?: string) => request<IamRole>('POST', b('/iam/roles'), body, write(key)),
    update: (roleId: string, body: RoleInput, version: number, key?: string) => request<IamRole>('PATCH', b(`/iam/roles/${enc(roleId)}`), body, write(key, version)),
    remove: (roleId: string) => request<void>('DELETE', b(`/iam/roles/${enc(roleId)}`), undefined, write()),
  },
  /** nothing is written: each check answered as authorize would, with the drafts laid over the account */
  simulate: (body: PolicySimulationRequest) => request<PolicySimulation>('POST', b('/iam/policy-simulations'), body),
  /** the organization's guardrails that apply to the session's account, in the order they are read */
  accountGuardrails: () => get<{ items: Guardrail[] }>(b('/iam/guardrails')),
  /** the organization's guardrails, for an organization admin; anyone else gets a 403 */
  org: {
    list: () => get<OrgGuardrails>(b('/org/guardrails')),
    get: (guardrailId: string) => get<Guardrail>(b(`/org/guardrails/${enc(guardrailId)}`)),
    create: (body: GuardrailInput, key?: string) => request<Guardrail>('POST', b('/org/guardrails'), body, write(key)),
    update: (guardrailId: string, body: GuardrailInput, version: number) => request<Guardrail>('PATCH', b(`/org/guardrails/${enc(guardrailId)}`), body, write(undefined, version)),
    remove: (guardrailId: string) => request<void>('DELETE', b(`/org/guardrails/${enc(guardrailId)}`), undefined, write()),
  },
}

/* ----------------------------------------------------------------------------
   The wildcard rule (the identity service's Globs: OPA's glob.match with no delimiters)
   ------------------------------------------------------------------------- */

/** `*` matches any run of characters (`:` and `/` too), `?` exactly one; the rest is literal. */
export function globMatches(pattern: string, value: string): boolean {
  const go = (pi: number, vi: number): boolean => {
    while (pi < pattern.length) {
      const c = pattern[pi]
      if (c === '*') {
        while (pi < pattern.length && pattern[pi] === '*') pi++
        if (pi === pattern.length) return true
        for (let i = vi; i <= value.length; i++) if (go(pi, i)) return true
        return false
      }
      if (vi >= value.length) return false
      if (c !== '?' && c !== value[vi]) return false
      pi++
      vi++
    }
    return vi === value.length
  }
  return go(0, 0)
}

/** A checked resource's own wildcards made literal, so a list check over a pattern matches only a statement whose wildcard covers it. */
export function sentinel(resource: string): string {
  return resource.replace(/\*/g, '~').replace(/\?/g, '^')
}

/* ----------------------------------------------------------------------------
   Actions: `<service>:<verb>`, and the ones that exist
   ------------------------------------------------------------------------- */

const PART = /^[a-z0-9*-]{1,64}$/

export function isAction(action: unknown): action is string {
  if (typeof action !== 'string') return false
  const i = action.indexOf(':')
  if (i <= 0 || i === action.length - 1 || action.indexOf(':', i + 1) >= 0) return false
  return PART.test(action.slice(0, i)) && PART.test(action.slice(i + 1))
}

/** The verbs every service has in the console. */
export const READ_VERBS = ['get', 'list', 'observe', 'history'] as const

/** The console's own services and their verbs, whatever the platform publishes. */
export const CONSOLE_SERVICES: Record<string, readonly string[]> = {
  iam: ['get', 'list', 'create', 'update', 'delete', 'manage'],
  account: ['get', 'list', 'update'],
  quota: ['get', 'list', 'set', 'request-increase'],
  audit: ['get', 'list', 'export'],
  cost: ['get', 'list', 'export'],
  budgets: ['get', 'list', 'create', 'update', 'delete'],
  search: ['query'],
  'access-request': ['get', 'list', 'create', 'decide'],
  'access-review': ['get', 'list', 'create', 'decide', 'cancel'],
  elevation: ['get', 'list', 'create', 'decide', 'revoke'],
}

/** What the console's own services are, in words, for the action picker. */
export const SERVICE_TITLES: Record<string, string> = {
  iam: 'Identity and access',
  account: 'Accounts',
  quota: 'Quotas',
  audit: 'Audit trail',
  cost: 'Cost',
  budgets: 'Budgets',
  search: 'Search',
  'access-request': 'Access requests',
  'access-review': 'Access reviews',
  elevation: 'Elevations',
}

/** Every service with its verbs, sorted: the action catalog a policy may name. */
export type ActionCatalog = Record<string, string[]>

/** A type as the catalog reads it: its service, and the workflows on it (create among them). */
export interface CatalogType {
  service: string
  actions: { verb: string }[]
  create?: { verb: string } | null
}

/**
 * The actions that exist, as the identity service's ActionCatalog builds them: every type's
 * service gets the read verbs and the verbs of the workflows on it, plus the console's own
 * services. A new workflow adds its action here with no console change.
 */
export function actionCatalogOf(types: readonly CatalogType[]): ActionCatalog {
  const out = new Map<string, Set<string>>()
  const add = (service: string, verbs: Iterable<string>) => {
    const set = out.get(service) ?? new Set<string>()
    for (const v of verbs) set.add(v)
    out.set(service, set)
  }
  for (const t of types) {
    if (!t.service) continue
    add(t.service, READ_VERBS)
    add(
      t.service,
      t.actions.map((a) => a.verb),
    )
    if (t.create) add(t.service, [t.create.verb])
  }
  for (const [service, verbs] of Object.entries(CONSOLE_SERVICES)) add(service, verbs)
  return Object.fromEntries([...out.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([s, v]) => [s, [...v].sort()]))
}

const hasWildcard = (s: string) => s.includes('*') || s.includes('?')

/** Whether an action names a known service and verb; a wildcard part is known when the other part is. */
export function knowsAction(catalog: ActionCatalog, action: string): boolean {
  const i = action.indexOf(':')
  const service = action.slice(0, i)
  const verb = action.slice(i + 1)
  if (hasWildcard(service)) return true
  const verbs = catalog[service]
  if (!verbs) return false
  return hasWildcard(verb) || verbs.includes(verb)
}

/** 'compute:restart' → { service: 'compute', verb: 'restart' } */
export function partsOfAction(action: string): { service: string; verb: string } {
  const i = action.indexOf(':')
  return i < 0 ? { service: action, verb: '' } : { service: action.slice(0, i), verb: action.slice(i + 1) }
}

/* ----------------------------------------------------------------------------
   Conditions
   ------------------------------------------------------------------------- */

export const CONDITION_KEYS = ['region', 'account', 'service', 'type', 'requester.user', 'requester.email', 'requester.group'] as const

export function isConditionKey(key: string): boolean {
  return (CONDITION_KEYS as readonly string[]).includes(key) || (key.startsWith('tag.') && key.length > 4) || (key.startsWith('context.') && key.length > 8)
}

/** What a condition key reads, in words. */
export function conditionKeyWords(key: string): string {
  if (key.startsWith('tag.')) return `the resource’s tag ${key.slice(4)}`
  if (key.startsWith('context.')) return `the request’s ${key.slice(8)}`
  const words: Record<string, string> = {
    region: 'the region',
    account: 'the account',
    service: 'the service',
    type: 'the resource type',
    'requester.user': 'the person',
    'requester.email': 'the person’s email',
    'requester.group': 'one of the person’s groups',
  }
  return words[key] ?? key
}

export type ConditionValue = string | number | boolean | (string | number | boolean)[]

function isScalar(v: unknown): v is string | number | boolean {
  return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'
}

/** 'the resource’s tag environment is prod or test' */
export function conditionWords(key: string, value: unknown): string {
  const list = Array.isArray(value) ? value : [value]
  return `${conditionKeyWords(key)} is ${list.map(String).join(' or ')}`
}

/* ----------------------------------------------------------------------------
   wfrn patterns, and the helper that builds one
   ------------------------------------------------------------------------- */

/** Why a pattern is not one, in words; null when it is. */
export function patternProblem(pattern: string): string | null {
  try {
    validateWfrnPattern(pattern)
    return null
  } catch (e) {
    if (isWfrnError(e)) return WFRN_MESSAGES[e.code]
    throw e
  }
}

export interface PatternParts {
  partition: string
  service: string
  /** '*' for any; '' for things in no region (IAM) */
  region: string
  /** '*' for any account */
  account: string
  type: string
  id: string
}

/** The pattern the helper's parts make; a wildcard type with a wildcard id stops at the type. */
export function patternOf(p: PatternParts): string {
  const head = `wfrn:${p.partition}:${p.service || '*'}:${p.region}:${p.account}:`
  const type = p.type || '*'
  const id = p.id || '*'
  if (type.endsWith('*') && id === '*') return `${head}${type}`
  return `${head}${type}/${id}`
}

/** A pattern read back into the helper's parts, when it has the six of them. */
export function partsOfPattern(pattern: string): PatternParts | null {
  const m = /^wfrn:([^:]*):([^:]*):([^:]*):([^:]*):(.*)$/.exec(pattern)
  if (!m) return null
  const slash = m[5].indexOf('/')
  return { partition: m[1], service: m[2], region: m[3], account: m[4], type: slash < 0 ? m[5] : m[5].slice(0, slash), id: slash < 0 ? '*' : m[5].slice(slash + 1) }
}

/** 'Every vm in account 123456789012, any region' */
export function patternWords(pattern: string, typeTitle?: (service: string, type: string) => string | undefined): string {
  const p = partsOfPattern(pattern)
  if (!p) return pattern
  const anyType = p.type === '*'
  const typeName = anyType ? 'every resource' : `${hasWildcard(p.type) ? 'resources of types like' : ''} ${typeTitle?.(p.service, p.type) ?? p.type}`.trim()
  const what = p.id === '*' ? (anyType ? typeName : `every ${typeName}`) : hasWildcard(p.id) ? `${typeName} named like ${p.id}` : `${typeName} ${p.id}`
  const service = p.service === '*' ? ' of any service' : anyType ? ` of ${SERVICE_TITLES[p.service] ?? p.service}` : ''
  const account = p.account === '*' ? ', any account' : p.account === '' ? '' : `, account ${p.account}`
  const region = p.region === '*' ? ', any region' : p.region === '' ? '' : `, region ${p.region}`
  const sentence = `${what}${service}${account}${region}`
  return sentence.charAt(0).toUpperCase() + sentence.slice(1)
}

/* ----------------------------------------------------------------------------
   The checks on a document (the identity service's PolicyValidation, word for word)
   ------------------------------------------------------------------------- */

export const NAME = /^[a-z0-9-]{2,64}$/
export const MAX_STATEMENTS = 200

const problem = (field: string, message: string, code = 'invalid'): Problem => ({ level: 'error', code, field, message })

/** The problems of a list of statements, fields as the identity service names them (`statements[0].actions[1]`). */
export function statementProblems(statements: unknown, catalog?: ActionCatalog): Problem[] {
  if (!Array.isArray(statements) || statements.length === 0) return [problem('statements', 'at least one statement is required')]
  if (statements.length > MAX_STATEMENTS) return [problem('statements', `at most ${MAX_STATEMENTS} statements`)]
  const out: Problem[] = []
  const sids = new Set<string>()
  statements.forEach((raw, i) => {
    const at = `statements[${i}]`
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      out.push(problem(at, 'a statement is required'))
      return
    }
    const s = raw as Partial<IamStatement>
    if (s.effect !== 'allow' && s.effect !== 'deny') out.push(problem(`${at}.effect`, 'effect is allow or deny'))
    if (s.sid !== undefined && s.sid !== null) {
      if (sids.has(s.sid)) out.push(problem(`${at}.sid`, `sid '${s.sid}' is used twice`))
      sids.add(s.sid)
    }
    const actions = Array.isArray(s.actions) ? s.actions : []
    if (!actions.length) out.push(problem(`${at}.actions`, 'at least one action is required'))
    actions.forEach((a, j) => checkAction(out, `${at}.actions[${j}]`, a, catalog))
    const notActions = Array.isArray(s.notActions) ? s.notActions : []
    notActions.forEach((a, j) => checkAction(out, `${at}.notActions[${j}]`, a, catalog))
    if (s.notActions !== undefined && s.notActions !== null && s.effect !== 'allow') out.push(problem(`${at}.notActions`, 'notActions is for allow statements'))
    const resources = Array.isArray(s.resources) ? s.resources : []
    if (!resources.length) out.push(problem(`${at}.resources`, 'at least one resource is required'))
    resources.forEach((r, j) => {
      if (typeof r !== 'string' || patternProblem(r) !== null) out.push(problem(`${at}.resources[${j}]`, `'${String(r)}' is not a wfrn pattern`))
    })
    for (const [key, v] of Object.entries(s.conditions ?? {})) {
      if (!isConditionKey(key)) out.push(problem(`${at}.conditions.${key}`, 'a condition key is region, account, service, type, requester.user, requester.email, requester.group, tag.<name> or context.<name>'))
      const scalar = isScalar(v)
      const list = Array.isArray(v) && v.every(isScalar)
      if (!scalar && !list) out.push(problem(`${at}.conditions.${key}`, 'a condition value is a string, number, boolean or a list of them'))
    }
  })
  return out
}

function checkAction(out: Problem[], field: string, action: unknown, catalog?: ActionCatalog) {
  if (!isAction(action)) {
    out.push(problem(field, `'${String(action)}' is not <service>:<verb>`))
    return
  }
  if (catalog && !knowsAction(catalog, action)) out.push(problem(field, `'${action}' names no known service or verb`, 'unknown-action'))
}

/** The problems of a policy document: its name, then its statements. */
export function policyProblems(doc: { name?: unknown; statements?: unknown }, catalog?: ActionCatalog): Problem[] {
  const out: Problem[] = []
  if (typeof doc.name !== 'string' || !NAME.test(doc.name)) out.push(problem('name', 'name matches ^[a-z0-9-]{2,64}$'))
  return [...out, ...statementProblems(doc.statements, catalog)]
}

/** The problems of a guardrail: its name, its statements and the accounts it applies to. */
export function guardrailProblems(input: GuardrailInput, knownAccounts: readonly string[], catalog?: ActionCatalog, partial = false): Problem[] {
  const out: Problem[] = []
  if (!partial || input.name !== undefined) if (typeof input.name !== 'string' || !NAME.test(input.name)) out.push(problem('name', 'name matches ^[a-z0-9-]{2,64}$'))
  if (!partial || input.statements !== undefined) out.push(...statementProblems(input.statements, catalog))
  if (!partial || input.accounts !== undefined) {
    const accounts = input.accounts ?? []
    if (!accounts.length) out.push(problem('accounts', 'at least one account, or * for every account'))
    accounts.forEach((a, i) => {
      if (a === '*') {
        if (accounts.length > 1) out.push(problem(`accounts[${i}]`, '* stands alone: every account'))
      } else if (!/^[0-9]{12}$/.test(a)) out.push(problem(`accounts[${i}]`, `'${a}' is not a 12-digit account id`))
      else if (!knownAccounts.includes(a)) out.push(problem(`accounts[${i}]`, `no account ${a}`))
    })
  }
  return out
}

/* ----------------------------------------------------------------------------
   The JSON view: a reader that knows the line of every value
   ------------------------------------------------------------------------- */

export interface JsonSpot {
  line: number
  column: number
}

export interface JsonRead {
  value?: unknown
  /** the text does not parse: where and why */
  error?: JsonSpot & { message: string }
  /** the line of each value, by the identity service's field names (`statements[0].actions[1]`) */
  spots: Map<string, JsonSpot>
}

/** Parses JSON, remembering where each value starts; the field names are `a.b[0].c`, as the identity service writes them. */
export function readJson(text: string): JsonRead {
  const spots = new Map<string, JsonSpot>()
  let i = 0
  const spotAt = (at: number): JsonSpot => {
    let line = 1
    let col = 1
    for (let k = 0; k < at && k < text.length; k++) {
      if (text[k] === '\n') {
        line++
        col = 1
      } else col++
    }
    return { line, column: col }
  }
  class Stop extends Error {
    constructor(
      readonly at: number,
      message: string,
    ) {
      super(message)
    }
  }
  const ws = () => {
    while (i < text.length && /\s/.test(text[i])) i++
  }
  const fail = (message: string): never => {
    throw new Stop(i, message)
  }
  const str = (): string => {
    if (text[i] !== '"') fail('a string in double quotes is expected')
    i++
    let out = ''
    while (i < text.length && text[i] !== '"') {
      if (text[i] === '\n') fail('a string does not run past the end of its line')
      if (text[i] === '\\') {
        const n = text[i + 1]
        const map: Record<string, string> = { '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' }
        if (n === 'u') {
          const hex = text.slice(i + 2, i + 6)
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('\\u is followed by four hex digits')
          out += String.fromCharCode(parseInt(hex, 16))
          i += 6
          continue
        }
        if (!(n in map)) fail(`\\${n ?? ''} is not an escape JSON knows`)
        out += map[n]
        i += 2
        continue
      }
      out += text[i++]
    }
    if (i >= text.length) fail('the string is not closed')
    i++
    return out
  }
  const value = (path: string): unknown => {
    ws()
    spots.set(path, spotAt(i))
    const c = text[i]
    if (c === '{') {
      i++
      const obj: Record<string, unknown> = {}
      ws()
      if (text[i] === '}') {
        i++
        return obj
      }
      for (;;) {
        ws()
        const keyAt = i
        const key = str()
        ws()
        if (text[i] !== ':') fail(`a colon is expected after "${key}"`)
        i++
        const child = path ? `${path}.${key}` : key
        obj[key] = value(child)
        // the key's line is where a person looks for the value's problem
        spots.set(child, spotAt(keyAt))
        ws()
        if (text[i] === ',') {
          i++
          ws()
          if (text[i] === '}') fail('a comma is not allowed before }')
          continue
        }
        if (text[i] === '}') {
          i++
          return obj
        }
        fail('a comma or } is expected')
      }
    }
    if (c === '[') {
      i++
      const arr: unknown[] = []
      ws()
      if (text[i] === ']') {
        i++
        return arr
      }
      for (;;) {
        arr.push(value(`${path}[${arr.length}]`))
        ws()
        if (text[i] === ',') {
          i++
          ws()
          if (text[i] === ']') fail('a comma is not allowed before ]')
          continue
        }
        if (text[i] === ']') {
          i++
          return arr
        }
        fail('a comma or ] is expected')
      }
    }
    if (c === '"') return str()
    const lit = /^(true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(i))
    if (lit) {
      i += lit[1].length
      return JSON.parse(lit[1]) as unknown
    }
    return fail(i >= text.length ? 'the text ends before the value does' : `unexpected ${JSON.stringify(c)}`)
  }
  try {
    const v = value('')
    ws()
    if (i < text.length) fail('the document ends here; remove what follows')
    return { value: v, spots }
  } catch (e) {
    if (e instanceof Stop) return { error: { ...spotAt(e.at), message: e.message }, spots }
    throw e
  }
}

/** The line a field's problem belongs on: the field itself, else the nearest value that holds it. */
export function lineOf(spots: Map<string, JsonSpot>, field: string | undefined): number | undefined {
  let f = field ?? ''
  for (;;) {
    const hit = spots.get(f)
    if (hit) return hit.line
    if (!f) return undefined
    const cut = Math.max(f.lastIndexOf('.'), f.lastIndexOf('['))
    f = cut < 0 ? '' : f.slice(0, cut)
  }
}

export interface PolicyDoc {
  name: string
  description: string
  statements: IamStatement[]
}

const STATEMENT_KEYS = ['sid', 'effect', 'actions', 'notActions', 'resources', 'conditions']
const DOC_KEYS = ['name', 'description', 'statements']

/** The document as the JSON view shows it: keys in the identity service's order, empty ones left out. */
export function policyJson(doc: PolicyDoc): string {
  const statements = doc.statements.map((s) => {
    const out: Record<string, unknown> = {}
    if (s.sid) out.sid = s.sid
    out.effect = s.effect
    out.actions = s.actions
    if (s.notActions?.length) out.notActions = s.notActions
    out.resources = s.resources
    if (s.conditions && Object.keys(s.conditions).length) out.conditions = s.conditions
    return out
  })
  return JSON.stringify({ name: doc.name, ...(doc.description ? { description: doc.description } : {}), statements }, null, 2)
}

const stringList = (v: unknown) => Array.isArray(v) && v.every((x) => typeof x === 'string')

/**
 * Reads the JSON view into a document. A text that does not parse, or a value of the wrong shape
 * (actions that are not a list of strings), gives problems and no document, so the visual view
 * keeps the last good one; what the identity service would refuse is left to policyProblems.
 */
export function readPolicyJson(text: string): { doc?: PolicyDoc; problems: Problem[]; spots: Map<string, JsonSpot>; syntax?: JsonRead['error'] } {
  const read = readJson(text)
  if (read.error) return { problems: [problem('', `Line ${read.error.line}: ${read.error.message}`, 'json')], spots: read.spots, syntax: read.error }
  const v = read.value
  const shape: Problem[] = []
  if (!v || typeof v !== 'object' || Array.isArray(v)) return { problems: [problem('', 'the document is a JSON object with a name and statements', 'shape')], spots: read.spots }
  const o = v as Record<string, unknown>
  for (const k of Object.keys(o)) if (!DOC_KEYS.includes(k)) shape.push(problem(k, `${k} is not a field of a policy; it has name, description and statements`, 'shape'))
  if (o.name !== undefined && typeof o.name !== 'string') shape.push(problem('name', 'name is a string', 'shape'))
  if (o.description !== undefined && typeof o.description !== 'string') shape.push(problem('description', 'description is a string', 'shape'))
  if (!Array.isArray(o.statements)) shape.push(problem('statements', 'statements is a list', 'shape'))
  else
    o.statements.forEach((s, i) => {
      const at = `statements[${i}]`
      if (!s || typeof s !== 'object' || Array.isArray(s)) {
        shape.push(problem(at, 'a statement is an object', 'shape'))
        return
      }
      const st = s as Record<string, unknown>
      for (const k of Object.keys(st)) if (!STATEMENT_KEYS.includes(k)) shape.push(problem(`${at}.${k}`, `${k} is not a field of a statement; it has ${STATEMENT_KEYS.join(', ')}`, 'shape'))
      if (st.sid !== undefined && typeof st.sid !== 'string') shape.push(problem(`${at}.sid`, 'sid is a string', 'shape'))
      for (const k of ['actions', 'resources']) if (!stringList(st[k] ?? [])) shape.push(problem(`${at}.${k}`, `${k} is a list of strings`, 'shape'))
      if (st.notActions !== undefined && !stringList(st.notActions)) shape.push(problem(`${at}.notActions`, 'notActions is a list of strings', 'shape'))
      if (st.conditions !== undefined && (!st.conditions || typeof st.conditions !== 'object' || Array.isArray(st.conditions))) shape.push(problem(`${at}.conditions`, 'conditions is an object of keys and values', 'shape'))
    })
  if (shape.length) return { problems: shape, spots: read.spots }
  const statements = (o.statements as Record<string, unknown>[]).map(
    (s): IamStatement => ({
      ...(typeof s.sid === 'string' ? { sid: s.sid } : {}),
      effect: s.effect as IamStatement['effect'],
      actions: (s.actions as string[] | undefined) ?? [],
      ...(s.notActions !== undefined ? { notActions: s.notActions as string[] } : {}),
      resources: (s.resources as string[] | undefined) ?? [],
      ...(s.conditions !== undefined ? { conditions: s.conditions as Record<string, unknown> } : {}),
    }),
  )
  return { doc: { name: typeof o.name === 'string' ? o.name : '', description: typeof o.description === 'string' ? o.description : '', statements }, problems: [], spots: read.spots }
}

/** A policy's document as the API takes it: empty optional parts left out. */
export function inputOf(doc: PolicyDoc): PolicyInput {
  return {
    name: doc.name.trim(),
    ...(doc.description.trim() ? { description: doc.description.trim() } : {}),
    statements: doc.statements.map((s) => ({
      ...(s.sid ? { sid: s.sid } : {}),
      effect: s.effect,
      actions: s.actions,
      ...(s.notActions?.length ? { notActions: s.notActions } : {}),
      resources: s.resources,
      ...(s.conditions && Object.keys(s.conditions).length ? { conditions: s.conditions } : {}),
    })),
  }
}

/* ----------------------------------------------------------------------------
   Words for statements and decisions
   ------------------------------------------------------------------------- */

/** 'Allows compute:restart on every vm, account 123456789012, any region, when the resource’s tag environment is prod.' */
export function statementWords(s: IamStatement, typeTitle?: (service: string, type: string) => string | undefined): string {
  const verb = s.effect === 'deny' ? 'Denies' : 'Allows'
  const actions = s.actions.length ? s.actions.join(', ') : 'no action'
  const except = s.notActions?.length ? ` except ${s.notActions.join(', ')}` : ''
  const on = s.resources.length ? s.resources.map((r) => patternWords(r, typeTitle).replace(/^./, (c) => c.toLowerCase())).join('; ') : 'nothing'
  const when = Object.entries(s.conditions ?? {}).map(([k, v]) => conditionWords(k, v))
  return `${verb} ${actions}${except} on ${on}${when.length ? `, when ${when.join(' and ')}` : ''}.`
}

/** Which part of console IAM decided, as a person reads it. */
export function scopeLabel(d: SimulatedDecision): string {
  if (d.scope === 'guardrail') return 'Guardrail'
  if (d.scope === 'boundary') return 'Permission boundary'
  if (d.scope === 'policy' || d.statement) return 'Policy'
  return 'No statement'
}

/** The checks of a simulation: every action on every resource, the resource's tags as context when it has them. */
export function checksOf(actions: readonly string[], resources: readonly { wfrn: string; tags?: Record<string, string> }[]): SimulationCheck[] {
  const out: SimulationCheck[] = []
  for (const r of resources) for (const a of actions) out.push({ action: a, resource: r.wfrn, ...(r.tags && Object.keys(r.tags).length ? { context: { tags: r.tags } } : {}) })
  return out
}

/** The roles that hold a policy, and the ones it bounds: where it is in use. */
export function holdersOf(policyId: string, roles: readonly IamRole[]): { attached: IamRole[]; bounding: IamRole[] } {
  return { attached: roles.filter((r) => r.policies?.includes(policyId)), bounding: roles.filter((r) => r.boundary === policyId) }
}

/** The names a 409 on a delete carried (the identity service's holders), else none. */
export function holdersInConflict(detail: unknown): string[] {
  return Array.isArray(detail) && detail.every((x) => typeof x === 'string') ? (detail as string[]) : []
}
