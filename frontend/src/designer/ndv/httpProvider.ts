/**
 * "Save as provider action" for the HTTP step: the request becomes an action of an `http` provider
 * draft (the URL's origin as its endpoint, the action as `{ method, path }`, the step's last test
 * output as its example), and the step calls that action instead, so the request gets a review,
 * samples, health and an SLO like every provider call.
 *
 * The step's credential becomes the provider's auth (it is a `vault:` reference, never a value);
 * a header the action cannot carry is named in the notes, never copied.
 */
import { STEP_TYPES, type JsonObject, type JsonValue } from '@iaf/workflow-checks'
import { blankAction, ENDPOINT_RE, ID_RE, inferSchema, kebab, nextKey, type AuthType } from '../../components/provider-editor/state'
import type { Effect, RetryPolicy } from '../../lib/types'
import { findStepNode } from '../model/graph'
import { newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import type { DesignerStore } from '../model/store'

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

/** The step's last output: its test run's, else what is pinned or a job gave; the body of an HTTP answer (`{ status, body }`). */
export function lastOutputOf(store: DesignerStore, stepId: string): JsonValue | undefined {
  const state = store.getState()
  const ran = state.testRun?.result?.steps.find((s) => s.id === stepId && s.output !== undefined && s.output !== null)
  const out = (ran?.output as JsonValue | undefined) ?? store.knownOutputs()[stepId]
  if (isObject(out) && 'status' in out && 'body' in out) return out.body
  return out
}

export type SaveAsProvider = { ok: true; draft: ProviderDraft; provider: string; action: string; notes: string[] } | { ok: false; error: string }

/**
 * Saves the HTTP step's request as an action of an `http` provider draft and turns the step into
 * a call of it (one undo step), then opens the Provider sheet on the draft. A new draft of the
 * same endpoint takes the action beside the ones it has.
 */
export function saveAsProviderAction(store: DesignerStore, stepId: string): SaveAsProvider {
  const state = store.getState()
  const node = findStepNode(state.graph, stepId)
  const body = node?.data.body
  if (!body || body.call !== 'http') return { ok: false, error: 'Only an HTTP request step can be saved as a provider action.' }
  const planned = httpActionPlan(isObject(body.with) ? body.with : {})
  if (!planned.ok) return planned
  const plan = planned.plan
  const example = lastOutputOf(store, stepId)

  const drafts = Object.values(state.providerDrafts)
  const sameEndpoint = (d: ProviderDraft) => d.origin === 'new' && d.type === 'http' && d.environments.some((e) => e.url.trim().replace(/\/+$/, '').toLowerCase() === plan.endpoint.toLowerCase())
  const existing = drafts.find(sameEndpoint)
  const id = actionIdFor(plan.method, plan.path, new Set((existing?.actions ?? []).map((a) => a.id)))
  const stepName = typeof body.name === 'string' ? body.name.trim() : ''
  const row = blankAction({
    id,
    title: stepName && stepName !== STEP_TYPES.http.title ? stepName : `${plan.method} ${plan.path}`,
    method: plan.method,
    path: plan.path,
    effect: plan.effect,
    retry: plan.retry,
    approval: plan.effect === 'delete',
    inputText: Object.keys(plan.input).length > 0 ? JSON.stringify(inferSchema(plan.input), null, 2) : '',
    exampleText: example !== undefined ? JSON.stringify(example, null, 2) : '',
  })

  let draft: ProviderDraft
  if (existing) draft = { ...existing, actions: [...existing.actions, row] }
  else {
    const host = plan.endpoint.replace(/^https?:\/\//i, '')
    const taken = new Set([...(state.registry?.providers ?? []).map((p) => p.name), ...drafts.map((d) => d.name)])
    const owner = typeof state.doc.metadata?.owner === 'string' ? state.doc.metadata.owner : ''
    draft = newProviderDraft('http', {
      title: host,
      name: providerNameFor(host, taken),
      nameTouched: true,
      owner,
      ownerTouched: owner !== '',
      environments: [{ key: nextKey(), name: 'dev', url: plan.endpoint }],
      auth: plan.auth,
      actions: [row],
    })
  }
  store.upsertProviderDraft(draft)
  store.updateStep(stepId, (b) => {
    b.call = 'provider'
    b.with = { provider: draft.name, action: id, input: plan.input }
  })
  store.openProviderSheet({ entry: 'step', draft: draft.id, target: { kind: 'step', step: stepId } })
  return { ok: true, draft, provider: draft.name, action: id, notes: plan.notes }
}
