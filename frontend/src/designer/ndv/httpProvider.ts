/**
 * "Save as provider action" for the HTTP step: the request becomes an action of an `http` provider
 * draft (the URL's origin as its endpoint, the action as `{ method, path }`, the step's last test
 * output as its example), and the step calls that action instead, so the request gets a review,
 * samples, health and an SLO like every provider call.
 *
 * The step's credential becomes the provider's auth (it is a `vault:` reference, never a value);
 * a header the action cannot carry is named in the notes, never copied. The notes are worked out
 * before anything changes (planProviderAction), so the step can show them and ask first.
 *
 * A draft of the same endpoint is reused when it can take the request: the action it already has
 * for the same method and path, or a new action beside its others, never on a draft whose file is
 * its own (a try's or Devin's `draftYaml`, which the rows no longer write) and never at the cost of
 * the step's credential; otherwise the request starts a new draft, and the notes say why.
 */
import { STEP_TYPES, type JsonObject, type JsonValue } from '@iaf/workflow-checks'
import { blankAction, ENDPOINT_RE, ID_RE, inferSchema, kebab, nextKey, type ActionRow, type AuthType } from '../../components/provider-editor/state'
import type { Effect, RetryPolicy } from '../../lib/types'
import { findStepNode } from '../model/graph'
import { newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import type { DesignerState, DesignerStore } from '../model/store'

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
/** The methods whose input is the JSON body; the others send it as query parameters (HttpProviderCall). */
const BODY_METHODS = ['POST', 'PUT', 'PATCH']

const EFFECT: Record<string, Effect> = { GET: 'read', POST: 'create', PUT: 'change', PATCH: 'change', DELETE: 'delete' }

/** The action an HTTP step's request makes, and what the provider draft needs around it. */
export interface HttpActionPlan {
  /** the URL's origin: the provider's endpoint */
  endpoint: string
  method: string
  /** the path and query after the origin, each `{{ … }}` turned into a `{name}` the input fills */
  path: string
  /** the step's input for the action: the path's expressions, then the body's fields */
  input: JsonObject
  effect: Effect
  retry: RetryPolicy
  /** how the provider signs in: the step's credential, or a header holding a `vault:` reference */
  auth: { type: AuthType; credential: string; header: string }
  /** what did not come along, in words */
  notes: string[]
}

const isObject = (v: unknown): v is JsonObject => typeof v === 'object' && v !== null && !Array.isArray(v)
const EXPR_RE = /\{\{([\s\S]*?)\}\}/g
const VAULT_VALUE_RE = /^(?:(\w+)\s+)?(vault:\S+)$/

/** The name a path expression fills: its last identifier (`{{ $input.vmId }}` → vmId), unique in the path. */
function paramName(expr: string, taken: Set<string>): string {
  const ids = expr.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? []
  const base = ids.at(-1) ?? 'param'
  let name = base
  for (let n = 2; taken.has(name); n++) name = `${base}${n}`
  taken.add(name)
  return name
}

/** The action of an HTTP step's `with`, or why it cannot be one yet. */
export function httpActionPlan(w: JsonObject): { ok: true; plan: HttpActionPlan } | { ok: false; error: string } {
  const method = (typeof w.method === 'string' && w.method.trim() ? w.method.trim() : 'GET').toUpperCase()
  if (!METHODS.includes(method)) return { ok: false, error: `A provider action is a GET, POST, PUT, PATCH or DELETE; this step's method is ${method}.` }
  const raw = typeof w.url === 'string' ? w.url.trim() : ''
  if (!raw) return { ok: false, error: 'Fill in the URL first.' }
  const m = /^(https?:\/\/)([^/?#\s{}]+)(.*)$/i.exec(raw)
  if (!m) return { ok: false, error: 'The URL has to start with the address of the system (https://…), not an expression.' }
  const notes: string[] = []
  let host = m[2]
  const at = host.lastIndexOf('@')
  if (at >= 0) {
    host = host.slice(at + 1)
    notes.push('The user and password in the URL were left out: pick a credential for the provider.')
  }
  const endpoint = `${m[1].toLowerCase()}${host}`
  if (!ENDPOINT_RE.test(endpoint)) notes.push(`${endpoint} is not https: a provider's endpoints are https URLs (plain http only to localhost).`)
  const rest = m[3].split('#')[0]
  const taken = new Set<string>()
  const input: JsonObject = {}
  const path =
    rest.replace(EXPR_RE, (whole, expr: string) => {
      const name = paramName(expr, taken)
      input[name] = whole.trim()
      return `{${name}}`
    }) || '/'

  // the body: the rest of the input of a POST, PUT or PATCH
  const body = w.body as JsonValue | undefined
  if (body !== undefined && body !== null && body !== '') {
    if (!BODY_METHODS.includes(method)) notes.push(`A ${method} action sends no body: the step's body was left out.`)
    else if (isObject(body)) {
      for (const [k, v] of Object.entries(body)) {
        if (k in input) notes.push(`The body's ${k} has the name of a path parameter: it was left out.`)
        else input[k] = v
      }
    } else notes.push("The body is not an object of fields: give the action's input its fields in the step.")
  }

  // how it signs in: the step's credential (sent as a bearer token), else a header holding a vault: reference
  let auth: HttpActionPlan['auth'] = { type: 'none', credential: '', header: '' }
  const credential = typeof w.credential === 'string' ? w.credential.trim() : ''
  if (credential.startsWith('vault:')) auth = { type: 'bearer', credential, header: '' }
  const left: string[] = []
  for (const [name, value] of Object.entries(isObject(w.headers) ? w.headers : {})) {
    const text = typeof value === 'string' ? value.trim() : ''
    const vault = VAULT_VALUE_RE.exec(text)
    if (vault && auth.type === 'none') {
      const scheme = (vault[1] ?? '').toLowerCase()
      auth = /^authorization$/i.test(name) ? { type: scheme === 'basic' ? 'basic' : 'bearer', credential: vault[2], header: '' } : { type: 'header', credential: vault[2], header: name }
      continue
    }
    // JSON in and out is what a provider call sends anyway
    if (/^(content-type|accept)$/i.test(name) && /json/i.test(text)) continue
    left.push(name)
  }
  if (left.length > 0) notes.push(`${left.length === 1 ? 'The header' : 'The headers'} ${left.join(', ')} ${left.length === 1 ? 'is' : 'are'} not part of a provider action and ${left.length === 1 ? 'was' : 'were'} left out.`)
  const effect = EFFECT[method]
  return { ok: true, plan: { endpoint, method, path, input, effect, retry: effect === 'read' ? 'safe' : 'unsafe', auth, notes } }
}

/** A provider name for a host (`api.example.com` → example), not one of `taken`. */
export function providerNameFor(host: string, taken: ReadonlySet<string>): string {
  const labels = host.toLowerCase().replace(/:\d+$/, '').split('.').filter(Boolean)
  let core = labels.length >= 2 && !/^\d+$/.test(labels.at(-1) ?? '') ? labels.slice(0, -1) : labels
  if (core.length > 1 && /^(www|api)$/.test(core[0])) core = core.slice(1)
  let base = kebab(core.join('-')) || 'http-api'
  if (!/^[a-z]/.test(base)) base = `api-${base}`
  if (!taken.has(base)) return base
  if (!taken.has(`${base}-api`)) return `${base}-api`
  let n = 2
  while (taken.has(`${base}-${n}`)) n++
  return `${base}-${n}`
}

/** An action id for a method and path (`GET /vms/{id}` → vms.get, `GET /vms` → vms.list), not one of `taken`. */
export function actionIdFor(method: string, path: string, taken: ReadonlySet<string>): string {
  const route = path.split('?')[0]
  const literal = route.split('/').filter((seg) => seg && !seg.includes('{'))
  const resource = kebab(literal.at(-1) ?? '')
  const endsWithParam = /\{[^}]*\}\/?$/.test(route)
  const verb = method === 'GET' ? (endsWithParam ? 'get' : 'list') : method === 'POST' ? 'create' : method === 'DELETE' ? 'delete' : 'update'
  const base = resource && ID_RE.test(`${resource}.${verb}`) ? `${resource}.${verb}` : verb
  let id = base
  for (let n = 2; taken.has(id); n++) id = `${base}-${n}`
  return id
}

/**
 * The step's last test output: its test run's, else what it gave in a run that succeeded (the
 * execution open in the designer); the body of an HTTP answer (`{ status, body }`). Never pinned
 * data, which is the designer's own mock and no answer of the system's.
 */
export function lastOutputOf(state: Pick<DesignerState, 'testRun' | 'execution'>, stepId: string): JsonValue | undefined {
  const tested = state.testRun?.result?.steps.find((s) => s.id === stepId && s.output !== undefined && s.output !== null)
  const ran = state.execution?.job?.steps.find((s) => s.id === stepId && s.status === 'succeeded' && s.output !== undefined && s.output !== null)
  const out = (tested?.output ?? ran?.output) as JsonValue | undefined
  if (isObject(out) && 'status' in out && 'body' in out) return out.body
  return out
}

type Auth = HttpActionPlan['auth']
const signsIn = (a: Auth) => a.type !== 'none' && a.credential.trim() !== ''
const sameAuth = (a: Auth, b: Auth) => a.type === b.type && a.credential === b.credential && (a.type !== 'header' || a.header.toLowerCase() === b.header.toLowerCase())

/** How the step's credential goes with a draft's auth: as it is, taken by the draft (which signs in with none and is written from its rows), or not at all. */
function authFit(d: ProviderDraft, plan: HttpActionPlan): 'same' | 'take' | 'clash' {
  if (!signsIn(plan.auth) || sameAuth(d.auth, plan.auth)) return 'same'
  if (!signsIn(d.auth) && d.draftYaml === null) return 'take'
  return 'clash'
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Whether a draft's own file (a try's, Devin's) lists an action of that id. */
const fileHasAction = (yaml: string, id: string) => new RegExp(`^\\s*(-\\s+)?id:\\s*["']?${escapeRe(id)}["']?\\s*$`, 'm').test(yaml)

/** What Save as provider action will do, worked out before it does it. */
export interface ProviderActionSave {
  /** the draft as it will be kept */
  draft: ProviderDraft
  /** false when the draft already has the action as it is, and stays untouched */
  changed: boolean
  /** the action the step will call, and its input */
  action: string
  input: JsonObject
  /** what does not come along, or goes somewhere the user may not expect, in words */
  notes: string[]
}

/**
 * Save as provider action, worked out without changing anything: the draft the request goes into
 * (an endpoint's draft that already has it, one that can take it beside its other actions, or a
 * new one), the action the step will call, and the notes to show first.
 */
export function planProviderAction(state: DesignerState, stepId: string): { ok: true; save: ProviderActionSave } | { ok: false; error: string } {
  const node = findStepNode(state.graph, stepId)
  const body = node?.data.body
  if (!body || body.call !== 'http') return { ok: false, error: 'Only an HTTP request step can be saved as a provider action.' }
  const planned = httpActionPlan(isObject(body.with) ? body.with : {})
  if (!planned.ok) return planned
  const plan = planned.plan
  const notes = [...plan.notes]
  const example = lastOutputOf(state, stepId)
  const exampleText = example !== undefined ? JSON.stringify(example, null, 2) : ''
  const inputText = Object.keys(plan.input).length > 0 ? JSON.stringify(inferSchema(plan.input), null, 2) : ''
  const done = (draft: ProviderDraft, changed: boolean, action: string) => ({ ok: true as const, save: { draft, changed, action, input: plan.input, notes } })

  const drafts = Object.values(state.providerDrafts)
  const sameEndpoint = drafts.filter((d) => d.origin === 'new' && d.type === 'http' && d.environments.some((e) => e.url.trim().replace(/\/+$/, '').toLowerCase() === plan.endpoint.toLowerCase()))
  const fitting = sameEndpoint.filter((d) => authFit(d, plan) !== 'clash')
  const sameRoute = (a: ActionRow) => ID_RE.test(a.id) && a.method.toUpperCase() === plan.method && a.path.trim() === plan.path

  // a draft of the endpoint that has this request already: the step calls that action
  for (const d of fitting) {
    const row = d.actions.find((a) => sameRoute(a) && (d.draftYaml === null || (a.selected && fileHasAction(d.draftYaml, a.id))))
    if (!row) continue
    if (d.draftYaml !== null) return done(d, false, row.id)
    const filled: ActionRow = { ...row, selected: true, exampleText: row.exampleText.trim() ? row.exampleText : exampleText, inputText: row.inputText.trim() ? row.inputText : inputText }
    const take = authFit(d, plan) === 'take'
    const changed = take || filled.selected !== row.selected || filled.exampleText !== row.exampleText || filled.inputText !== row.inputText
    return done({ ...d, ...(take ? { auth: { ...plan.auth } } : {}), actions: d.actions.map((a) => (a.key === row.key ? filled : a)) }, changed, row.id)
  }

  const stepName = typeof body.name === 'string' ? body.name.trim() : ''
  const rowFor = (id: string) =>
    blankAction({
      id,
      title: stepName && stepName !== STEP_TYPES.http.title ? stepName : `${plan.method} ${plan.path}`,
      method: plan.method,
      path: plan.path,
      effect: plan.effect,
      retry: plan.retry,
      approval: plan.effect === 'delete',
      inputText,
      exampleText,
    })

  // a draft of the endpoint written from its rows: the action goes beside its others
  const open = fitting.find((d) => d.draftYaml === null)
  if (open) {
    const id = actionIdFor(plan.method, plan.path, new Set(open.actions.map((a) => a.id)))
    return done({ ...open, ...(authFit(open, plan) === 'take' ? { auth: { ...plan.auth } } : {}), actions: [...open.actions, rowFor(id)] }, true, id)
  }

  // a new draft
  const host = plan.endpoint.replace(/^https?:\/\//i, '')
  const taken = new Set([...(state.registry?.providers ?? []).map((p) => p.name), ...drafts.map((d) => d.name)])
  const owner = typeof state.doc.metadata?.owner === 'string' ? state.doc.metadata.owner : ''
  const id = actionIdFor(plan.method, plan.path, new Set())
  const draft = newProviderDraft('http', {
    title: host,
    name: providerNameFor(host, taken),
    nameTouched: true,
    owner,
    ownerTouched: owner !== '',
    environments: [{ key: nextKey(), name: 'dev', url: plan.endpoint }],
    auth: { ...plan.auth },
    actions: [rowFor(id)],
  })
  for (const d of sameEndpoint) {
    if (authFit(d, plan) === 'clash') notes.push(`The draft ${d.name} of ${plan.endpoint} signs in with another credential than this step, so the request goes into a new draft, ${draft.name}.`)
    else if (d.draftYaml !== null) notes.push(`The draft ${d.name} of ${plan.endpoint} keeps a file of its own (a try's answer or a draft it took), which a new action would not reach, so the request goes into a new draft, ${draft.name}.`)
  }
  return done(draft, true, id)
}

export type SaveAsProvider = { ok: true; draft: ProviderDraft; provider: string; action: string; notes: string[] } | { ok: false; error: string }

/**
 * Saves the HTTP step's request as an action of an `http` provider draft, as planProviderAction
 * works it out, and turns the step into a call of it (one undo step), then opens the Provider
 * sheet on the draft.
 */
export function saveAsProviderAction(store: DesignerStore, stepId: string): SaveAsProvider {
  const planned = planProviderAction(store.getState(), stepId)
  if (!planned.ok) return planned
  const { draft, changed, action, input, notes } = planned.save
  if (changed) store.upsertProviderDraft(draft)
  store.updateStep(stepId, (b) => {
    b.call = 'provider'
    b.with = { provider: draft.name, action, input }
  })
  store.openProviderSheet({ entry: 'step', draft: draft.id, target: { kind: 'step', step: stepId } })
  return { ok: true, draft, provider: draft.name, action, notes }
}
