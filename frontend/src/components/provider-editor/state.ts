/**
 * The Add provider wizard's state and the provider file it writes. The whole wizard is one plain
 * object (`WizardState`); `buildProviderDocument` turns it into the provider document and
 * `emitYaml` (components/yaml-emit.ts) writes the YAML that :validate, :try and :publish take.
 * The wizard page (pages/ProviderWizardPage.tsx) and the designer's provider drafts
 * (designer/model/providerDrafts.ts) share it; the steps that edit it sit beside this file.
 */
import type { IconName } from '@iaf/ui'
import type { ActionInfo, Effect, ProviderAuth, ProviderDetail, ProviderImport, ProviderType, RetryPolicy, Schema, SpecImportRequest, Strategy, TestConnectionResult, TryResult } from '../../lib/types'
import { parseJsonText } from '../JsonEditor'
import { emitYaml } from '../yaml-emit'

export type AuthType = ProviderAuth['type']

export interface Env {
  key: number
  name: string
  url: string
}

export interface ActionRow {
  key: number
  selected: boolean
  id: string
  title: string
  /** operationId (openapi) or activity name (sdk) */
  operation: string
  method: string
  path: string
  playbook: string
  effect: Effect
  retry: RetryPolicy
  approval: boolean
  plan: string
  /** a delete's line for the preview when no plan step reads its target: what it removes */
  effectDetail: string
  description: string
  /** JSON schema text: input (openapi/http/idp/sdk) or vars (ansible/terraform) */
  inputText: string
  outputText: string
  /** http: one example response, JSON */
  exampleText: string
  /** the action's own SLO as typed, success rate in percent and p95 in ms; empty takes the provider's */
  sloSuccessRate: string
  sloP95Ms: string
}

/**
 * One error class of `spec.errors`: a failed call that matches is called `class`, and retried
 * when `retry` says so. The backend matches the status first, then text the answer's body
 * contains (`match.body`, a plain substring); the first class that matches wins.
 */
export interface ErrorRow {
  key: number
  /** the HTTP status, as typed */
  status: string
  /** text the body contains, for a status several errors share */
  body: string
  class: string
  retry: boolean
}

export interface WizardState {
  type: ProviderType
  title: string
  name: string
  nameTouched: boolean
  owner: string
  ownerTouched: boolean
  version: string
  description: string
  specMode: 'url' | 'paste' | 'upload' | 'git'
  specUrl: string
  specContent: string
  /** the uploaded file's name, for the record */
  specFile: string
  /** a spec in a Git repository: read at the ref, pinned to the commit it came from */
  specGit: { repo: string; ref: string; path: string; credential: string }
  specCommit: string
  specSha256: string
  specTitle: string
  environments: Env[]
  auth: { type: AuthType; credential: string; header: string }
  ansible: { repo: string; ref: string; path: string; commit: string; executionEnvironment: string }
  terraform: { source: string; version: string }
  idp: { tileId: string }
  /** Both optional: the queue defaults to provider-<name>, no heartbeat timeout when empty */
  sdk: { taskQueue: string; heartbeatTimeout: string }
  actions: ActionRow[]
  tested: TestConnectionResult | null
  tries: Record<string, TryResult>
  samples: Record<string, unknown>
  overrides: Record<string, Schema>
  errors: ErrorRow[]
  slo: { successRate: string; p95Ms: string; channel: string }
  strategy: Strategy
  /** Devin's draft, a tried draft or an import's, when the user chose it over the wizard's document */
  draftYaml: string | null
  /** an Ansible repository or a Terraform module the backend drafted; its yaml is the draft */
  imported: ProviderImport | null
  /** the name of the provider being edited: its file is the draft, the steps collect evidence, and the version is the next patch */
  editing: string | null
}

export const NAME_RE = /^[a-z][a-z0-9-]*$/
export const ID_RE = /^[a-z][a-zA-Z0-9._-]*$/
export const VERSION_RE = /^\d+\.\d+\.\d+$/
export const HTTPS_RE = /^https:\/\/\S+$/
/** An endpoint the platform may call: https, or plain http to a loopback host for local development, as the backend's checks allow (with a warning). */
export const ENDPOINT_RE = /^(https:\/\/\S+|http:\/\/(localhost|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|\[::1\])(:\d+)?(\/\S*)?)$/
export const TASK_QUEUE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/
export const DURATION_RE = /^(P(?!$)(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?|\d+(ms|s|m|h|d))$/
export const CLASS_RE = /^[a-z][a-z0-9_]*$/
export const STATUS_RE = /^[1-5]\d\d$/
/** The classes the backend falls back to for these statuses, as a starting point. */
export const USUAL_ERRORS: { status: number; class: string; retry: boolean }[] = [
  { status: 404, class: 'not_found', retry: false },
  { status: 409, class: 'conflict', retry: false },
  { status: 429, class: 'rate_limited', retry: true },
  { status: 503, class: 'unavailable', retry: true },
]
export const EFFECTS: Effect[] = ['read', 'create', 'change', 'delete']
export const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
export const STEPS = [
  { id: 'type', label: 'Type', description: 'What kind of system' },
  { id: 'connect', label: 'Connect', description: 'Where it lives, how to sign in' },
  { id: 'actions', label: 'Actions', description: 'What workflows may call' },
  { id: 'try', label: 'Try it', description: 'One real call, recorded', optional: true },
  { id: 'review', label: 'Review', description: 'Checks, then a change request' },
]
export const TYPES: { value: ProviderType; label: string; icon: IconName; text: string }[] = [
  { value: 'openapi', label: 'OpenAPI', icon: 'document', text: 'Import a spec; every operation can become an action.' },
  { value: 'http', label: 'HTTP', icon: 'link', text: 'A few endpoints, each described with one example response.' },
  { value: 'ansible', label: 'Ansible', icon: 'terminal', text: 'Playbooks from a Git repository, pinned to a commit.' },
  { value: 'terraform', label: 'Terraform', icon: 'layers', text: 'A module with plan, apply and destroy.' },
  { value: 'idp', label: 'IDP tile', icon: 'grid', text: 'A developer-portal tile, requested on the user’s behalf.' },
  { value: 'sdk', label: 'SDK worker', icon: 'bolt', text: 'Activities a team-owned worker registers on a task queue.' },
]
export const TYPE_LABEL = Object.fromEntries(TYPES.map((t) => [t.value, t.label])) as Record<ProviderType, string>

/** The wizard's step ids. */
export type StepId = (typeof STEPS)[number]['id']
/** stepIssues judges by the full wizard's step numbers, whichever steps a mode shows */
export const STEP_NUMBER: Record<StepId, number> = { type: 0, connect: 1, actions: 2, try: 3, review: 4 }

let seq = 0
/** The key of a new row (an environment, an action, an error class), unique in this page load. */
export const nextKey = () => ++seq

export const kebab = (s: string): string =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')

export function blankAction(partial: Partial<ActionRow> = {}): ActionRow {
  return { key: nextKey(), selected: true, id: '', title: '', operation: '', method: 'GET', path: '', playbook: '', effect: 'read', retry: 'safe', approval: false, plan: '', effectDetail: '', description: '', inputText: '', outputText: '', exampleText: '', sloSuccessRate: '', sloP95Ms: '', ...partial }
}

/** The actions with one renamed: the plan steps that named it follow the new id. */
export function renameAction(actions: readonly ActionRow[], key: number, id: string): ActionRow[] {
  const old = actions.find((a) => a.key === key)?.id
  return actions.map((a) => (a.key === key ? { ...a, id } : old && a.plan === old ? { ...a, plan: id } : a))
}

export function blankError(partial: Partial<ErrorRow> = {}): ErrorRow {
  return { key: nextKey(), status: '', body: '', class: '', retry: false, ...partial }
}

export function starterActions(type: ProviderType): ActionRow[] {
  if (type === 'terraform')
    return [
      blankAction({ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }),
      blankAction({ id: 'apply', title: 'Apply', effect: 'create', retry: 'unsafe', plan: 'plan' }),
      blankAction({ id: 'destroy', title: 'Destroy', effect: 'delete', retry: 'unsafe', approval: true, effectDetail: 'Destroys what the module manages for these variables' }),
    ]
  if (type === 'openapi') return []
  return [blankAction()]
}

export function initialState(): WizardState {
  return {
    type: 'openapi',
    title: '',
    name: '',
    nameTouched: false,
    owner: '',
    ownerTouched: false,
    version: '1.0.0',
    description: '',
    specMode: 'url',
    specUrl: '',
    specContent: '',
    specFile: '',
    specGit: { repo: '', ref: 'main', path: '', credential: '' },
    specCommit: '',
    specSha256: '',
    specTitle: '',
    environments: [{ key: nextKey(), name: 'dev', url: '' }],
    auth: { type: 'bearer', credential: '', header: '' },
    ansible: { repo: '', ref: 'main', path: '', commit: '', executionEnvironment: '' },
    terraform: { source: '', version: '' },
    idp: { tileId: '' },
    sdk: { taskQueue: '', heartbeatTimeout: '' },
    actions: [],
    tested: null,
    tries: {},
    samples: {},
    overrides: {},
    errors: [],
    slo: { successRate: '99.0', p95Ms: '1000', channel: '' },
    strategy: 'canary',
    draftYaml: null,
    imported: null,
    editing: null,
  }
}

/** The `spec` block of an openapi provider: where the spec is read from (its URL, or Git at a ref, pinned to a commit) and the hash that pins it. */
export function specBlock(s: WizardState): Record<string, unknown> {
  if (s.specMode === 'git') {
    const g = s.specGit
    return { git: { repo: g.repo.trim(), ref: g.ref.trim() || undefined, path: g.path.trim() || undefined, credential: g.credential || undefined }, commit: s.specCommit || undefined, sha256: s.specSha256 || undefined }
  }
  return { url: s.specMode === 'url' ? s.specUrl.trim() || undefined : undefined, sha256: s.specSha256 || undefined }
}

/** What Import spec sends for the chosen source: the URL, the pasted or uploaded text, or the Git coordinates. */
export function specImportRequest(s: WizardState): SpecImportRequest {
  if (s.specMode === 'url') return { type: 'openapi', url: s.specUrl.trim() }
  if (s.specMode === 'git') {
    const g = s.specGit
    return { type: 'openapi', git: { repo: g.repo.trim(), ref: g.ref.trim() || undefined, path: g.path.trim() || undefined, credential: g.credential || undefined } }
  }
  return { type: 'openapi', content: s.specContent }
}

/** 1.3.0 → 1.3.1; anything that is not a version comes back as it was. */
export function bumpPatch(version: string): string {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(version.trim())
  return m ? `${m[1]}.${m[2]}.${Number(m[3]) + 1}` : version
}

/**
 * The file with `metadata.version` replaced and nothing else touched. The browser never parses
 * YAML, so this is a line edit of the metadata block: the first `version:` at the block's own
 * indentation. A file without one comes back as it was, and the backend's publish says so.
 */
export function withVersion(yaml: string, version: string): string {
  const lines = yaml.split('\n')
  const start = lines.findIndex((l) => /^metadata:\s*(#.*)?$/.test(l))
  if (start < 0) return yaml
  let indent: number | null = null
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i]
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const lead = line.length - line.trimStart().length
    if (lead === 0) break
    indent ??= lead
    if (lead !== indent) continue
    const m = /^(\s+version:\s*)(?:"[^"]*"|'[^']*'|[^#\s]+)(\s*(?:#.*)?)$/.exec(line)
    if (m) {
      lines[i] = `${m[1]}${version}${m[2]}`
      return lines.join('\n')
    }
  }
  return yaml
}

type MetadataField = 'name' | 'title' | 'owner' | 'version'

/** The plain fields of a file's metadata block, read line by line like `withVersion`: what a draft is called. */
export function metadataOf(yaml: string): Partial<Record<MetadataField, string>> {
  const out: Partial<Record<MetadataField, string>> = {}
  const lines = yaml.split('\n')
  const start = lines.findIndex((l) => /^metadata:\s*(#.*)?$/.test(l))
  if (start < 0) return out
  let indent: number | null = null
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i]
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const lead = line.length - line.trimStart().length
    if (lead === 0) break
    indent ??= lead
    if (lead !== indent) continue
    const m = /^\s+(name|title|owner|version):\s*(?:"([^"]*)"|'([^']*)'|([^#]*?))\s*(?:#.*)?$/.exec(line)
    if (m) out[m[1] as MetadataField] = (m[2] ?? m[3] ?? m[4] ?? '').trim()
  }
  return out
}

/** One action of a provider file as `yamlActions` reads it. */
export interface FileAction {
  id: string
  title: string
  effect: string
}

const unquote = (raw: string): string => {
  const m = /^\s*(?:"([^"]*)"|'([^']*)'|([^#]*?))\s*(?:#.*)?$/.exec(raw)
  return (m?.[1] ?? m?.[2] ?? m?.[3] ?? '').trim()
}

/**
 * The actions of a file's `spec.actions` list (their id, title and effect), read line by line
 * like `metadataOf`: what a file the wizard took instead of its own document (Devin's, an
 * import's) offers to call. An entry without a plain `id:` is left out.
 */
export function yamlActions(yaml: string): FileAction[] {
  const lines = yaml.split('\n')
  const spec = lines.findIndex((l) => /^spec:\s*(#.*)?$/.test(l))
  if (spec < 0) return []
  const lead = (l: string) => l.length - l.trimStart().length
  let list = -1
  for (let i = spec + 1; i < lines.length; i++) {
    const l = lines[i]
    if (!l.trim() || l.trimStart().startsWith('#')) continue
    if (lead(l) === 0) break
    if (/^\s+actions:\s*(#.*)?$/.test(l)) {
      list = i
      break
    }
  }
  if (list < 0) return []
  const base = lead(lines[list])
  const out: FileAction[] = []
  let item: Partial<FileAction> | null = null
  let keys = -1
  const flush = () => {
    if (item?.id) out.push({ id: item.id, title: item.title ?? '', effect: item.effect ?? '' })
  }
  for (let i = list + 1; i < lines.length; i++) {
    const l = lines[i]
    if (!l.trim() || l.trimStart().startsWith('#')) continue
    const at = lead(l)
    const dash = /^(\s*)-(\s+|$)(.*)$/.exec(l)
    // the list ends at the next key of spec (an item may sit at the key's own indentation)
    if (at < base || (at === base && !dash)) break
    let rest: string | null = null
    if (dash && (keys < 0 || at < keys)) {
      flush()
      item = {}
      keys = at + 1 + dash[2].length
      rest = dash[3]
    } else if (item && at === keys) rest = l.trim()
    if (item && rest !== null) {
      const m = /^(id|title|effect):(.*)$/.exec(rest)
      if (m) item[m[1] as keyof FileAction] = unquote(m[2])
    }
  }
  flush()
  return out
}

const PROVIDER_TYPES = new Set<string>(['openapi', 'http', 'ansible', 'terraform', 'idp', 'sdk'])

/** What a file's `spec` block says the provider is, as `fileSpec` reads it. */
export interface FileSpec {
  type?: ProviderType
  /** a terraform provider's `spec.module` */
  module?: { source: string; version: string }
}

/**
 * The type a provider file declares (`spec.type`) and, for terraform, its module (`spec.module`,
 * as a block or a one-line `{ source: …, version: … }`), read line by line like `metadataOf`:
 * what a file taken instead of the wizard's document (Devin's, an import's, a published one) is.
 */
export function fileSpec(yaml: string): FileSpec {
  const lines = yaml.split('\n')
  const spec = lines.findIndex((l) => /^spec:\s*(#.*)?$/.test(l))
  if (spec < 0) return {}
  const lead = (l: string) => l.length - l.trimStart().length
  const out: FileSpec = {}
  let indent: number | null = null
  // inside `module:`, at the indentation of its first key
  let module: { source: string; version: string } | null = null
  let moduleKeys = -1
  for (let i = spec + 1; i < lines.length; i++) {
    const l = lines[i]
    if (!l.trim() || l.trimStart().startsWith('#')) continue
    const at = lead(l)
    if (at === 0) break
    indent ??= at
    if (module && at > indent) {
      moduleKeys = moduleKeys < 0 ? at : moduleKeys
      const m = at === moduleKeys ? /^\s+(source|version):(.*)$/.exec(l) : null
      if (m) module[m[1] as 'source' | 'version'] = unquote(m[2])
      continue
    }
    module = null
    if (at !== indent) continue
    const type = /^\s+type:(.*)$/.exec(l)
    if (type) {
      const t = unquote(type[1])
      if (PROVIDER_TYPES.has(t)) out.type = t as ProviderType
      continue
    }
    const mod = /^\s+module:(.*)$/.exec(l)
    if (mod) {
      const found = { source: '', version: '' }
      out.module = found
      const flow = /^\s*\{(.*)\}\s*(#.*)?$/.exec(mod[1])
      if (flow) for (const m of flow[1].matchAll(/(source|version)\s*:\s*("[^"]*"|'[^']*'|[^,}]*)/g)) found[m[1] as 'source' | 'version'] = unquote(m[2])
      else {
        module = found
        moduleKeys = -1
      }
    }
  }
  return out
}

/**
 * The form of an Ansible repository's or a Terraform module's import, as typed: what
 * POST /providers:import-ansible and :import-terraform are sent (pages/ProviderImport.tsx), kept in
 * a designer draft until the import runs. Its credentials are `vault:` references.
 */
export interface ImportForm {
  /** terraform: where the module comes from */
  source: 'git' | 'registry'
  repo: string
  ref: string
  path: string
  /** the vault: reference of a read token for a private repository */
  readCredential: string
  /** ansible: playbooks to keep, by path or action id; all when empty */
  playbooks: string
  /** terraform, from the registry: namespace/name/provider and an exact version */
  module: string
  version: string
  registryCredential: string
  name: string
  owner: string
  /** the owner was typed, so the signed-in user's team no longer fills it */
  ownerTouched: boolean
  /** ansible: the machine credential the playbooks connect with; terraform: what the runner gives Terraform */
  credential: string
  executionEnvironment: string
}

export function blankImportForm(owner = ''): ImportForm {
  return { source: 'git', repo: '', ref: 'main', path: '', readCredential: '', playbooks: '', module: '', version: '', registryCredential: '', name: '', owner, ownerTouched: false, credential: '', executionEnvironment: '' }
}

/** A draft names the provider: the wizard takes its name, title, owner and version, so the path and the change request say what is published. */
export function draftNames(yaml: string): Partial<WizardState> {
  const m = metadataOf(yaml)
  return {
    ...(m.name && NAME_RE.test(m.name) ? { name: m.name, nameTouched: true } : {}),
    ...(m.title ? { title: m.title } : {}),
    ...(m.owner ? { owner: m.owner, ownerTouched: true } : {}),
    ...(m.version && VERSION_RE.test(m.version) ? { version: m.version } : {}),
  }
}

const authTypeFrom = (t: string | undefined): AuthType => (t === undefined ? 'none' : t === 'bearer' || t === 'basic' || t === 'header' || t === 'none' ? t : authTypeOf(t))

/**
 * The wizard on an existing provider, as the platform describes it: the file at the next patch
 * version as the draft, and the endpoints, auth and actions the Test connection and Try it calls
 * are made with. The steps that write a new file (Type, Actions) are not shown while editing.
 */
export function stateFromProvider(d: ProviderDetail): WizardState {
  const s = initialState()
  const version = bumpPatch(d.version)
  const names = [...d.environments, ...Object.keys(d.endpoints).filter((e) => !d.environments.includes(e))]
  const environments: Env[] = names.filter((e) => d.endpoints[e]).map((name) => ({ key: nextKey(), name, url: d.endpoints[name] }))
  const operationOf = (a: ActionInfo) => (typeof a.operation === 'string' ? a.operation : '')
  const routeOf = (a: ActionInfo) => (typeof a.operation === 'object' && a.operation !== null ? a.operation : null)
  return {
    ...s,
    editing: d.name,
    type: d.type,
    title: d.title,
    name: d.name,
    nameTouched: true,
    owner: d.owner,
    ownerTouched: true,
    version,
    description: d.description ?? '',
    environments: environments.length ? environments : s.environments,
    auth: { type: authTypeFrom(d.auth?.type), credential: d.auth?.credential ?? '', header: d.auth?.header ?? '' },
    actions: d.actions.map((a) =>
      blankAction({
        id: a.id,
        title: a.title,
        operation: operationOf(a),
        method: routeOf(a)?.method.toUpperCase() ?? 'GET',
        path: routeOf(a)?.path ?? '',
        effect: a.effect,
        retry: a.retry,
        approval: a.approval === true || a.approval === 'required',
        plan: a.plan ?? '',
        effectDetail: a.effectDetail ?? '',
        inputText: a.input ? JSON.stringify(a.input, null, 2) : '',
        outputText: a.output ? JSON.stringify(a.output, null, 2) : '',
        sloSuccessRate: a.slo ? String(a.slo.successRate) : '',
        sloP95Ms: a.slo ? String(a.slo.p95Ms) : '',
      }),
    ),
    samples: Object.fromEntries(d.actions.filter((a) => a.sample !== undefined).map((a) => [a.id, a.sample])),
    slo: { successRate: d.slo ? String(d.slo.successRate) : '', p95Ms: d.slo ? String(d.slo.p95Ms) : '', channel: d.alerts?.channel ?? '' },
    draftYaml: withVersion(d.yaml, version),
  }
}

/** A JSON schema for a value, from its shape: what "Fix schema from response" records. */
export function inferSchema(v: unknown): Schema {
  if (v === null || v === undefined) return { type: 'string' }
  if (Array.isArray(v)) return { type: 'array', items: v.length ? inferSchema(v[0]) : { type: 'string' } }
  switch (typeof v) {
    case 'string':
      return { type: 'string' }
    case 'number':
      return { type: Number.isInteger(v) ? 'integer' : 'number' }
    case 'boolean':
      return { type: 'boolean' }
    case 'object':
      return { type: 'object', properties: Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, inferSchema(x)])) }
    default:
      return {}
  }
}

const parseObject = (text: string): Record<string, unknown> | undefined => {
  const p = parseJsonText(text)
  return !p.empty && !p.error && p.value && typeof p.value === 'object' && !Array.isArray(p.value) ? (p.value as Record<string, unknown>) : undefined
}
export const parseSchema = (text: string): Schema | undefined => parseObject(text) as Schema | undefined

export function endpointsOf(s: WizardState): Record<string, string> {
  return Object.fromEntries(s.environments.filter((e) => e.name.trim() && e.url.trim()).map((e) => [e.name.trim(), e.url.trim()]))
}

export function authOf(s: WizardState): ProviderAuth {
  if (s.auth.type === 'none') return s.auth.credential ? { type: 'none', credential: s.auth.credential } : { type: 'none' }
  return { type: s.auth.type, credential: s.auth.credential || undefined, header: s.auth.type === 'header' ? s.auth.header.trim() || undefined : undefined }
}

const SLO_RATE_RE = /^\d+(\.\d+)?$/
const SLO_MS_RE = /^\d+$/

/** A success rate typed as a percent: above 0, at most 100. */
function sloRate(text: string): number | undefined {
  const t = text.trim()
  const n = Number(t)
  return SLO_RATE_RE.test(t) && n > 0 && n <= 100 ? n : undefined
}

/** A p95 typed in whole milliseconds, above 0. */
function sloMs(text: string): number | undefined {
  const t = text.trim()
  const n = Number(t)
  return SLO_MS_RE.test(t) && n > 0 ? n : undefined
}

/**
 * An action's own `slo`, from what was typed: a field left empty takes the provider's value
 * (docs/config-as-code.md), and with both empty the action has none.
 */
export function actionSlo(a: Pick<ActionRow, 'sloSuccessRate' | 'sloP95Ms'>): { successRate?: number; p95Ms?: number } | undefined {
  const successRate = sloRate(a.sloSuccessRate)
  const p95Ms = sloMs(a.sloP95Ms)
  return successRate === undefined && p95Ms === undefined ? undefined : { successRate, p95Ms }
}

/** What is wrong with an action's own SLO as typed. */
export function actionSloIssues(label: string, a: Pick<ActionRow, 'sloSuccessRate' | 'sloP95Ms'>): string[] {
  const out: string[] = []
  if (a.sloSuccessRate.trim() && sloRate(a.sloSuccessRate) === undefined) out.push(`${label}: the SLO's success rate is a percent above 0, like 99.5.`)
  if (a.sloP95Ms.trim() && sloMs(a.sloP95Ms) === undefined) out.push(`${label}: the SLO's p95 is whole milliseconds, like 800.`)
  return out
}

function actionDocument(type: ProviderType, a: ActionRow, s: WizardState): Record<string, unknown> {
  const input = parseSchema(a.inputText)
  const output = parseSchema(a.outputText)
  const head = { id: a.id, title: a.title.trim() || a.id, description: a.description.trim() || undefined }
  const policy = { effect: a.effect, retry: a.retry, approval: a.approval ? 'required' : undefined, plan: a.plan || undefined, effectDetail: a.effectDetail.trim() || undefined, slo: actionSlo(a) }
  const sample = s.samples[a.id]
  switch (type) {
    case 'openapi':
      return { ...head, operation: a.operation.trim(), ...policy, input, output, sample }
    case 'http':
      return { ...head, operation: { method: a.method.toUpperCase(), path: a.path.trim() }, ...policy, input, output, example: parseObject(a.exampleText), sample }
    case 'ansible':
      return { ...head, playbook: a.playbook.trim(), ...policy, vars: input, sample }
    case 'terraform':
      return { ...head, ...policy, vars: input, sample }
    case 'idp':
      return { ...head, ...policy, input, output, sample }
    case 'sdk':
      return { ...head, activity: a.operation.trim() || a.id, ...policy, input, output, sample }
  }
}

export const blankRow = (e: ErrorRow) => !e.status.trim() && !e.body.trim() && !e.class.trim()

/** One `spec.errors` entry: `{ match: { status, body }, class, retry }`; a row left blank is none. */
export function errorDocument(e: ErrorRow): Record<string, unknown> | undefined {
  if (blankRow(e)) return undefined
  const status = e.status.trim()
  return { match: { status: status ? (/^\d+$/.test(status) ? Number(status) : status) : undefined, body: e.body.trim() || undefined }, class: e.class.trim(), retry: e.retry }
}

/**
 * What is wrong with the error classes: each needs a name and something to match, a status when
 * the provider is called over HTTP, and must not sit behind an earlier class that matches every
 * call it would (same status, and no body text or text its own contains: the first match wins).
 */
export function errorIssues(type: ProviderType, errors: readonly ErrorRow[]): string[] {
  const out: string[] = []
  const http = type === 'openapi' || type === 'http'
  errors.forEach((e, i) => {
    if (blankRow(e)) return
    const name = e.class.trim()
    const label = name || `Error class ${i + 1}`
    const status = e.status.trim()
    const body = e.body.trim()
    if (!CLASS_RE.test(name)) out.push(`${label}: a name in lowercase words joined by underscores, like rate_limited.`)
    if (status && !STATUS_RE.test(status)) out.push(`${label}: the status is an HTTP status, like 429.`)
    else if (!status && http) out.push(`${label}: the status it matches; calls are matched by status first, then by body text.`)
    else if (!status && !body) out.push(`${label}: the status or the body text it matches.`)
    const shadow = errors.slice(0, i).find((x) => !blankRow(x) && x.status.trim() === status && (status ? !x.body.trim() || body.includes(x.body.trim()) : x.body.trim() === body))
    if (shadow) out.push(`${label}: ${shadow.class.trim() || 'an earlier class'} above it matches every call it would, and the first match wins.`)
  })
  return out
}

/** The provider document as a plain object; `emitYaml` writes it. */
export function buildProviderDocument(s: WizardState): Record<string, unknown> {
  const spec: Record<string, unknown> = { type: s.type }
  if (s.type === 'openapi') spec.spec = specBlock(s)
  if (s.type === 'openapi' || s.type === 'http') spec.endpoints = endpointsOf(s)
  if (s.type === 'ansible') {
    spec.source = { git: { repo: s.ansible.repo.trim(), ref: s.ansible.ref.trim() || undefined, path: s.ansible.path.trim() || undefined }, commit: s.ansible.commit.trim() || undefined }
    spec.executionEnvironment = s.ansible.executionEnvironment.trim() || undefined
  }
  if (s.type === 'terraform') spec.module = { source: s.terraform.source.trim(), version: s.terraform.version.trim() || undefined }
  if (s.type === 'idp') spec.tile = { id: s.idp.tileId.trim() }
  if (s.type === 'sdk') {
    const worker = { taskQueue: s.sdk.taskQueue.trim() || undefined, heartbeatTimeout: s.sdk.heartbeatTimeout.trim() || undefined }
    if (worker.taskQueue || worker.heartbeatTimeout) spec.worker = worker
  }
  spec.auth = authOf(s)
  spec.actions = s.actions.filter((a) => a.selected).map((a) => actionDocument(s.type, a, s))
  const overrides = Object.entries(s.overrides).filter(([id]) => s.actions.some((a) => a.selected && a.id === id))
  if (overrides.length) spec.overrides = Object.fromEntries(overrides.map(([id, schema]) => [id, { response: schema }]))
  const errors = s.errors.map(errorDocument).filter((e) => e !== undefined)
  if (errors.length) spec.errors = errors
  const rate = Number(s.slo.successRate)
  const p95 = Number(s.slo.p95Ms)
  if (Number.isFinite(rate) && rate > 0 && Number.isFinite(p95) && p95 > 0) spec.slo = { successRate: rate, p95Ms: p95 }
  if (s.slo.channel.trim()) spec.alerts = { channel: s.slo.channel.trim() }
  return {
    apiVersion: 'iaf.platform/v1',
    kind: 'Provider',
    metadata: { name: s.name, title: s.title.trim() || s.name, owner: s.owner.trim(), version: s.version.trim(), description: s.description.trim() || undefined },
    spec,
  }
}

export function stepIssues(s: WizardState, step: number): string[] {
  const out: string[] = []
  if (step === 0) {
    if (!s.title.trim()) out.push('Give the provider a title.')
    if (!NAME_RE.test(s.name)) out.push('The name is lowercase letters, digits and dashes.')
    if (!s.owner.trim()) out.push('Say which team owns it.')
    if (!VERSION_RE.test(s.version.trim())) out.push('The version looks like 1.0.0.')
  }
  if (step === 1) {
    if (s.type === 'openapi' && !s.editing) {
      if (s.specMode === 'url' && !s.specUrl.trim()) out.push('Point at the spec: its URL.')
      if (s.specMode === 'paste' && !s.specContent.trim()) out.push('Paste the spec.')
      if (s.specMode === 'upload' && !s.specContent.trim()) out.push('Upload the spec file.')
      if (s.specMode === 'git' && !HTTPS_RE.test(s.specGit.repo.trim())) out.push('Name the https repository the spec lives in.')
      if (s.specMode === 'git' && s.specGit.credential && !s.specGit.credential.startsWith('vault:')) out.push('The read token is a vault: reference.')
    }
    if ((s.type === 'openapi' || s.type === 'http') && !s.environments.some((e) => e.name.trim() && ENDPOINT_RE.test(e.url.trim()))) out.push('Add at least one environment with an https URL.')
    const plain = s.environments.find((e) => e.url.trim() && !ENDPOINT_RE.test(e.url.trim()))
    if ((s.type === 'openapi' || s.type === 'http') && plain) out.push(`${plain.name || 'An environment'}: endpoints are https URLs; only localhost may use plain http, for local development.`)
    if (s.type === 'ansible' && !s.editing && !s.ansible.repo.trim()) out.push('Name the Git repository.')
    if (s.type === 'terraform' && !s.editing && !s.terraform.source.trim()) out.push('Name the module source.')
    if (s.type === 'idp' && !s.editing && !s.idp.tileId.trim()) out.push('Name the tile.')
    if (s.type === 'sdk' && s.sdk.taskQueue.trim() && !TASK_QUEUE_RE.test(s.sdk.taskQueue.trim())) out.push('The task queue is letters, digits, dots, dashes and underscores.')
    if (s.type === 'sdk' && s.sdk.heartbeatTimeout.trim() && !DURATION_RE.test(s.sdk.heartbeatTimeout.trim())) out.push('The heartbeat timeout is a duration like 30s or 2m.')
    if (s.auth.type !== 'none' && !s.auth.credential) out.push('Pick the credential, or choose no authentication.')
    if (s.auth.type === 'header' && !s.auth.header.trim()) out.push('Name the header the credential goes in.')
  }
  if (step === 2) {
    const sel = s.actions.filter((a) => a.selected)
    if (sel.length === 0) out.push('Select at least one action.')
    const ids = sel.map((a) => a.id)
    const dup = ids.find((id, i) => id && ids.indexOf(id) !== i)
    if (dup) out.push(`Two actions are called ${dup}.`)
    for (const a of sel) {
      const label = a.id || '(unnamed)'
      if (!ID_RE.test(a.id)) out.push(`${label}: an id like vm.get.`)
      if (a.effect === 'delete' && !a.plan && !a.effectDetail.trim()) out.push(`${label} deletes something: pick a plan step that previews it, or say in one line what it removes.`)
      if (s.type === 'openapi' && !a.operation.trim()) out.push(`${label}: the operationId.`)
      if (s.type === 'http' && !a.path.trim()) out.push(`${label}: a path.`)
      if (s.type === 'ansible' && !a.playbook.trim()) out.push(`${label}: the playbook path.`)
      for (const [what, text] of [
        ['input', a.inputText],
        ['output', a.outputText],
        ['example', a.exampleText],
      ] as const) {
        const p = parseJsonText(text)
        if (!p.empty && p.error) out.push(`${label}: the ${what} is not valid JSON.`)
      }
      out.push(...actionSloIssues(label, a))
    }
    out.push(...errorIssues(s.type, s.errors))
  }
  return out
}

/** Where the review counts tries: dev when the provider has it (or no environment yet), else its first (ProviderReview.sandbox). */
export function sandboxEnvironment(environments: readonly string[]): string {
  return environments.length === 0 || environments.includes('dev') ? 'dev' : environments[0]
}

/** Whether an operation is one the search asks for: every word in its operationId, action id, method, path or summary. */
export function matchesOperation(a: Pick<ActionRow, 'operation' | 'id' | 'method' | 'path' | 'title'>, query: string): boolean {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  const text = `${a.operation} ${a.id} ${a.method} ${a.path} ${a.title}`.toLowerCase()
  return words.every((w) => text.includes(w))
}

/** The input fields that name a registry resource (`x-resource`). */
export function resourceFields(schema: Schema | undefined): string[] {
  return Object.entries(schema?.properties ?? {})
    .filter(([, p]) => !!p && typeof p === 'object' && !!p['x-resource'])
    .map(([name]) => name)
}

/** dev / test / prod for each server URL; an unmarked one is prod, and a repeat becomes env<n>. */
export function envNames(urls: string[]): string[] {
  const used = new Set<string>()
  return urls.map((url, i) => {
    let name = /(^|[-./])dev([-./]|$)/i.test(url) ? 'dev' : /(^|[-./])(test|staging|stage|qa)([-./]|$)/i.test(url) ? 'test' : 'prod'
    if (used.has(name)) name = `env${i + 1}`
    used.add(name)
    return name
  })
}

export function authTypeOf(t: string | undefined): AuthType {
  const v = (t ?? '').toLowerCase()
  if (v.includes('bearer') || v === 'oauth2' || v === 'http') return 'bearer'
  if (v.includes('basic')) return 'basic'
  if (v.includes('apikey') || v.includes('api-key') || v.includes('header')) return 'header'
  if (v === 'none') return 'none'
  return 'bearer'
}

/** What Ask Devin starts from: the wizard so far, in words, ready to be edited. Exported for its tests. */
export function describeProvider(s: WizardState): string {
  const sel = s.actions.filter((a) => a.selected)
  const label = TYPE_LABEL[s.type]
  const called = s.title.trim() || s.name ? ` called "${s.title.trim() || s.name}"${s.name ? ` (name ${s.name})` : ''}` : ''
  const errors = s.errors.filter((e) => !blankRow(e))
  const lines = [
    `${s.type === 'terraform' ? 'A' : 'An'} ${label} provider${called}${s.owner ? `, owned by ${s.owner}` : ''}, version ${s.version}.`,
    s.description.trim(),
    s.type === 'openapi' && s.specUrl ? `Spec: ${s.specUrl}.` : '',
    s.environments.some((e) => e.url) ? `Environments: ${s.environments.filter((e) => e.url).map((e) => `${e.name} at ${e.url}`).join(', ')}.` : '',
    s.type === 'ansible' ? `Playbooks in ${s.ansible.repo} at ${s.ansible.ref}.` : '',
    s.type === 'terraform' ? `Module ${s.terraform.source} ${s.terraform.version}.` : '',
    `Authentication: ${s.auth.type}${s.auth.credential ? ` with credential ${s.auth.credential}` : ''}.`,
    sel.length ? `Actions: ${sel.map((a) => `${a.id} (${a.effect}${a.approval ? ', approval' : ''})`).join(', ')}.` : 'Actions: suggest the useful ones.',
    errors.length ? `Error classes: ${errors.map((e) => `${[e.status.trim() && `HTTP ${e.status.trim()}`, e.body.trim() && `"${e.body.trim()}"`].filter(Boolean).join(' with ')} is ${e.class.trim()}${e.retry ? ', retried' : ''}`).join('; ')}.` : '',
  ]
  return lines.filter(Boolean).join('\n')
}

/** What the steps take: the state, and a patch to merge into it. */
export type StepProps = { state: WizardState; update: (patch: Partial<WizardState>) => void }

/** What picking another type changes: its starter actions, no evidence or samples of the old type, and the auth a type of its kind starts with (none for the ones the platform does not call over HTTP). */
export function typeChange(s: WizardState, type: ProviderType): Partial<WizardState> {
  return { type, actions: starterActions(type), tested: null, tries: {}, samples: {}, overrides: {}, auth: { ...s.auth, type: type === 'ansible' || type === 'terraform' || type === 'sdk' ? 'none' : s.auth.type === 'none' ? 'bearer' : s.auth.type } }
}

/** The provider file: the one the wizard took instead (Devin's draft, a try's, an import's, an edit's), else the one its state writes. */
export function providerYaml(s: WizardState): string {
  return s.draftYaml ?? emitYaml(buildProviderDocument(s))
}
