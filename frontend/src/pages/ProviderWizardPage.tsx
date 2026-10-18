import { useMemo, useRef, useState } from 'react'
import { Button, Card, Checkbox, CodeBlock, ConfirmDialog, EmptyState, Field, Icon, IconButton, Input, JsonView, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Segmented, Select, Spinner, StatusDot, Stepper, Table, Tag, Textarea, cx, duration, useMediaQuery, useOverlayClose, useToast, type IconName, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage, useCurrentUser } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { ActionInfo, Effect, ProviderAuth, ProviderDetail, ProviderDraft, ProviderImport, ProviderType, ProviderValidation, RetryPolicy, Schema, SpecImport, SpecImportRequest, Strategy, TestConnectionResult, TryResult } from '../lib/types'
import { CredentialPicker } from '../components/CredentialPicker'
import { ImportStep, ImportSummary, type ImportKind } from './ProviderImport'
import { JsonEditor, parseJsonText } from '../components/JsonEditor'
import { ProblemList } from '../components/ProblemList'
import { SchemaForm, defaultsFor, type SchemaValue } from '../components/SchemaForm'
import { downloadText } from '../components/download'
import { emitYaml } from '../components/yaml-emit'
import { EffectTag, ErrorState, Mono, When, checkDot } from '../components/common'

/*
 * Add provider: five steps from "what kind of system" to a change request. The whole wizard is one
 * plain object (`WizardState`); `buildProviderDocument` turns it into the provider document and
 * `emitYaml` (components/yaml-emit.ts) writes the YAML that :validate, :try and :publish take.
 */

type AuthType = ProviderAuth['type']

interface Env {
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
  description: string
  /** JSON schema text: input (openapi/http/idp/sdk) or vars (ansible/terraform) */
  inputText: string
  outputText: string
  /** http: one example response, JSON */
  exampleText: string
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
  slo: { successRate: string; p95Ms: string; channel: string }
  strategy: Strategy
  /** Devin's draft, a tried draft or an import's, when the user chose it over the wizard's document */
  draftYaml: string | null
  /** an Ansible repository or a Terraform module the backend drafted; its yaml is the draft */
  imported: ProviderImport | null
  /** the name of the provider being edited: its file is the draft, the steps collect evidence, and the version is the next patch */
  editing: string | null
}

const NAME_RE = /^[a-z][a-z0-9-]*$/
const ID_RE = /^[a-z][a-zA-Z0-9._-]*$/
const VERSION_RE = /^\d+\.\d+\.\d+$/
const HTTPS_RE = /^https:\/\/\S+$/
const TASK_QUEUE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/
const DURATION_RE = /^(P(?!$)(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?|\d+(ms|s|m|h|d))$/
const EFFECTS: Effect[] = ['read', 'create', 'change', 'delete']
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const STEPS = [
  { id: 'type', label: 'Type', description: 'What kind of system' },
  { id: 'connect', label: 'Connect', description: 'Where it lives, how to sign in' },
  { id: 'actions', label: 'Actions', description: 'What workflows may call' },
  { id: 'try', label: 'Try it', description: 'One real call, recorded', optional: true },
  { id: 'review', label: 'Review', description: 'Checks, then a change request' },
]
const TYPES: { value: ProviderType; label: string; icon: IconName; text: string }[] = [
  { value: 'openapi', label: 'OpenAPI', icon: 'document', text: 'Import a spec; every operation can become an action.' },
  { value: 'http', label: 'HTTP', icon: 'link', text: 'A few endpoints, each described with one example response.' },
  { value: 'ansible', label: 'Ansible', icon: 'terminal', text: 'Playbooks from a Git repository, pinned to a commit.' },
  { value: 'terraform', label: 'Terraform', icon: 'layers', text: 'A module with plan, apply and destroy.' },
  { value: 'idp', label: 'IDP tile', icon: 'grid', text: 'A developer-portal tile, requested on the user’s behalf.' },
  { value: 'sdk', label: 'SDK worker', icon: 'bolt', text: 'Activities a team-owned worker registers on a task queue.' },
]
const TYPE_LABEL = Object.fromEntries(TYPES.map((t) => [t.value, t.label])) as Record<ProviderType, string>

let seq = 0
const nextKey = () => ++seq

export const kebab = (s: string): string =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')

export function blankAction(partial: Partial<ActionRow> = {}): ActionRow {
  return { key: nextKey(), selected: true, id: '', title: '', operation: '', method: 'GET', path: '', playbook: '', effect: 'read', retry: 'safe', approval: false, plan: '', description: '', inputText: '', outputText: '', exampleText: '', ...partial }
}

function starterActions(type: ProviderType): ActionRow[] {
  if (type === 'terraform')
    return [
      blankAction({ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }),
      blankAction({ id: 'apply', title: 'Apply', effect: 'create', retry: 'unsafe', plan: 'plan' }),
      blankAction({ id: 'destroy', title: 'Destroy', effect: 'delete', retry: 'unsafe', approval: true }),
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

/** A JSON schema for a value, from its shape: what "Fix schema from response" records. */
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
        inputText: a.input ? JSON.stringify(a.input, null, 2) : '',
        outputText: a.output ? JSON.stringify(a.output, null, 2) : '',
      }),
    ),
    samples: Object.fromEntries(d.actions.filter((a) => a.sample !== undefined).map((a) => [a.id, a.sample])),
    slo: { successRate: d.slo ? String(d.slo.successRate) : '', p95Ms: d.slo ? String(d.slo.p95Ms) : '', channel: d.alerts?.channel ?? '' },
    draftYaml: withVersion(d.yaml, version),
  }
}

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
const parseSchema = (text: string): Schema | undefined => parseObject(text) as Schema | undefined

export function endpointsOf(s: WizardState): Record<string, string> {
  return Object.fromEntries(s.environments.filter((e) => e.name.trim() && e.url.trim()).map((e) => [e.name.trim(), e.url.trim()]))
}

export function authOf(s: WizardState): ProviderAuth {
  if (s.auth.type === 'none') return s.auth.credential ? { type: 'none', credential: s.auth.credential } : { type: 'none' }
  return { type: s.auth.type, credential: s.auth.credential || undefined, header: s.auth.type === 'header' ? s.auth.header.trim() || undefined : undefined }
}

function actionDocument(type: ProviderType, a: ActionRow, s: WizardState): Record<string, unknown> {
  const input = parseSchema(a.inputText)
  const output = parseSchema(a.outputText)
  const head = { id: a.id, title: a.title.trim() || a.id, description: a.description.trim() || undefined }
  const policy = { effect: a.effect, retry: a.retry, approval: a.approval ? 'required' : undefined, plan: a.plan || undefined }
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
    if ((s.type === 'openapi' || s.type === 'http') && !s.environments.some((e) => e.name.trim() && HTTPS_RE.test(e.url.trim()))) out.push('Add at least one environment with an https URL.')
    const plain = s.environments.find((e) => e.url.trim() && !HTTPS_RE.test(e.url.trim()))
    if ((s.type === 'openapi' || s.type === 'http') && plain) out.push(`${plain.name || 'An environment'}: endpoints are https URLs.`)
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
    }
  }
  return out
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

function authTypeOf(t: string | undefined): AuthType {
  const v = (t ?? '').toLowerCase()
  if (v.includes('bearer') || v === 'oauth2' || v === 'http') return 'bearer'
  if (v.includes('basic')) return 'basic'
  if (v.includes('apikey') || v.includes('api-key') || v.includes('header')) return 'header'
  if (v === 'none') return 'none'
  return 'bearer'
}

function describe(s: WizardState): string {
  const sel = s.actions.filter((a) => a.selected)
  const lines = [
    `A ${TYPE_LABEL[s.type]} provider called "${s.title || s.name}" (name ${s.name || '?'}), owned by ${s.owner || '?'}, version ${s.version}.`,
    s.description.trim(),
    s.type === 'openapi' && s.specUrl ? `Spec: ${s.specUrl}.` : '',
    s.environments.some((e) => e.url) ? `Environments: ${s.environments.filter((e) => e.url).map((e) => `${e.name} at ${e.url}`).join(', ')}.` : '',
    s.type === 'ansible' ? `Playbooks in ${s.ansible.repo} at ${s.ansible.ref}.` : '',
    s.type === 'terraform' ? `Module ${s.terraform.source} ${s.terraform.version}.` : '',
    `Authentication: ${s.auth.type}${s.auth.credential ? ` with credential ${s.auth.credential}` : ''}.`,
    sel.length ? `Actions: ${sel.map((a) => `${a.id} (${a.effect}${a.approval ? ', approval' : ''})`).join(', ')}.` : 'Actions: suggest the useful ones.',
  ]
  return lines.filter(Boolean).join('\n')
}

/* ----------------------------------------------------------------------------
   The page
   ------------------------------------------------------------------------- */

type StepId = (typeof STEPS)[number]['id']
/** stepIssues judges by the full wizard's step numbers, whichever steps a mode shows */
const STEP_NUMBER: Record<StepId, number> = { type: 0, connect: 1, actions: 2, try: 3, review: 4 }
/** Editing keeps the file as it is: only the evidence (Connect, Try it) and the review are stepped through. */
const EDIT_STEPS = STEPS.filter((s) => s.id === 'connect' || s.id === 'try' || s.id === 'review')

export default function ProviderWizardPage({ route }: PageProps) {
  // the same cache entry as the shell's, so the owner defaults to the signed-in user's team
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  // /providers/:name/edit: the provider's file is the draft, the next patch its version
  const editingName = route.params.name ?? null
  const detail = useQuery(['provider', editingName ?? ''], () => api.provider(editingName ?? ''), { enabled: editingName !== null })
  const steps = editingName ? EDIT_STEPS : STEPS
  // a phone stacks the steps: five labels do not fit side by side
  const narrow = useMediaQuery('(max-width: 639px)')
  const [state, setState] = useState<WizardState>(initialState)
  const [stepId, setStepId] = useState<StepId>(steps[0].id)
  const [furthest, setFurthest] = useState(0)
  const update = (patch: Partial<WizardState>) => setState((s) => ({ ...s, ...patch }))
  // ?import=ansible or ?import=terraform: the backend drafts the provider from a repository or a module, and the wizard opens on the review
  const importKind: ImportKind | null = !editingName && (route.query.import === 'ansible' || route.query.import === 'terraform') ? route.query.import : null
  const importing = importKind !== null && state.imported === null

  // the provider seeds the wizard once it arrives
  if (detail.data && state.editing !== detail.data.name) setState(stateFromProvider(detail.data))
  // the owner defaults to the signed-in user's first team until they pick one
  const firstTeam = me.data?.teams?.[0]
  if (firstTeam && !state.ownerTouched && !state.owner) update({ owner: firstTeam })

  const stepPos = Math.max(0, steps.findIndex((s) => s.id === stepId))
  const issues = stepIssues(state, STEP_NUMBER[stepId])
  const yaml = useMemo(() => state.draftYaml ?? emitYaml(buildProviderDocument(state)), [state])

  const goTo = (pos: number) => {
    setStepId(steps[pos].id)
    setFurthest((f) => Math.max(f, pos))
  }
  const next = () => {
    if (issues.length) return
    goTo(Math.min(steps.length - 1, stepPos + 1))
  }

  const title = editingName ? `Edit ${detail.data?.title ?? editingName}` : importKind === 'ansible' ? 'Import an Ansible repository' : importKind === 'terraform' ? 'Import a Terraform module' : 'Add a provider'
  const subtitle = editingName
    ? 'The file stays as it is: test the connection and try the actions again, which the platform keeps as review evidence, then publish the next patch. The SLO, the overrides and the spec change from the provider page.'
    : importKind
      ? 'The platform reads it and drafts the provider; review the draft, then open a change request.'
      : 'Five steps from a spec, a repository or a module to a change request.'
  const back = editingName ? { href: `#${pathFor('provider', { name: editingName })}`, label: detail.data?.title ?? editingName } : { href: `#${pathFor('providers')}`, label: 'Providers' }

  if (editingName && !detail.data) {
    return (
      <>
        <PageHeader eyebrow="Providers" title={title} subtitle={subtitle} back={back} />
        {detail.error ? (
          <ErrorState error={detail.error} title="Could not load the provider" onRetry={() => void detail.refetch()} />
        ) : (
          <div className="flex items-center gap-2 py-10 text-sm text-secondary" role="status">
            <Spinner /> Loading the provider…
          </div>
        )}
      </>
    )
  }

  return (
    <>
      <PageHeader eyebrow="Providers" title={title} subtitle={subtitle} back={back} />
      <Stepper steps={steps} current={stepPos} onStepClick={(i) => i <= furthest && setStepId(steps[i].id)} orientation={narrow ? 'vertical' : 'horizontal'} className="mb-6" />

      {importing && importKind ? (
        <ImportStep
          kind={importKind}
          defaultOwner={state.owner}
          onImported={(patch) => {
            update(patch)
            goTo(steps.length - 1)
          }}
        />
      ) : (
        <>
          {stepId === 'type' && <TypeStep state={state} update={update} />}
          {stepId === 'connect' && <ConnectStep state={state} update={update} />}
          {stepId === 'actions' && <ActionsStep state={state} update={update} />}
          {stepId === 'try' && <TryStep state={state} update={update} yaml={yaml} />}
          {stepId === 'review' && <ReviewStep state={state} update={update} yaml={yaml} />}
        </>
      )}

      {!importing && (
        <div className="mt-6 flex flex-wrap items-start justify-between gap-3 border-t border-stroke-light pt-4">
          <Button variant="ghost" icon="arrow-left" disabled={stepPos === 0} onClick={() => setStepId(steps[stepPos - 1].id)}>
            Back
          </Button>
          {issues.length > 0 && (
            <ul className="min-w-0 flex-1 space-y-0.5 text-caption text-secondary" aria-label="Before you continue">
              {issues.slice(0, 4).map((i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                  {i}
                </li>
              ))}
            </ul>
          )}
          {stepPos < steps.length - 1 ? (
            <Button variant="primary" iconRight="arrow-right" disabled={issues.length > 0} onClick={next}>
              {stepId === 'try' && Object.keys(state.tries).length === 0 ? 'Skip to review' : 'Next'}
            </Button>
          ) : (
            <span />
          )}
        </div>
      )}
    </>
  )
}

type StepProps = { state: WizardState; update: (patch: Partial<WizardState>) => void }

/* ---- 1 · Type ------------------------------------------------------------ */

function TypeStep({ state, update }: StepProps) {
  const pick = (type: ProviderType) => {
    if (type === state.type) return
    update({ type, actions: starterActions(type), tested: null, tries: {}, samples: {}, overrides: {}, auth: { ...state.auth, type: type === 'ansible' || type === 'terraform' || type === 'sdk' ? 'none' : state.auth.type === 'none' ? 'bearer' : state.auth.type } })
  }
  return (
    <div className="space-y-5">
      <Card title="What kind of system is it?" subtitle="The type decides how the platform calls it and what the next steps ask.">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" role="radiogroup" aria-label="Provider type">
          {TYPES.map((t) => {
            const on = t.value === state.type
            return (
              <button
                key={t.value}
                type="button"
                role="radio"
                aria-checked={on}
                aria-label={t.label}
                onClick={() => pick(t.value)}
                className={cx(
                  'flex items-start gap-3 rounded-lg border p-4 text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-accent',
                  on ? 'border-accent bg-accent-tint' : 'border-stroke-light bg-surface hover:bg-surface-secondary',
                )}
              >
                <span className={cx('mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md', on ? 'bg-accent text-white' : 'bg-surface-secondary text-secondary')}>
                  <Icon name={t.icon} size={16} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold text-primary">{t.label}</span>
                  <span className="mt-0.5 block text-caption leading-snug text-secondary">{t.text}</span>
                </span>
              </button>
            )
          })}
        </div>
      </Card>

      <Card title="Name it" subtitle="The file lands at providers/<owner>/<name>.yaml.">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Title" htmlFor="prov-title" required>
            <Input id="prov-title" value={state.title} onChange={(e) => update({ title: e.target.value, ...(state.nameTouched ? {} : { name: kebab(e.target.value) }) })} placeholder="VMware vSphere" autoFocus />
          </Field>
          <Field label="Name" htmlFor="prov-name" required hint="Lowercase, dashes; workflows call it by this" error={state.name && !NAME_RE.test(state.name) ? 'Lowercase letters, digits and dashes' : undefined}>
            <Input id="prov-name" mono value={state.name} onChange={(e) => update({ name: e.target.value, nameTouched: true })} invalid={!!state.name && !NAME_RE.test(state.name)} placeholder="vsphere" />
          </Field>
          <Field label="Owner" htmlFor="prov-owner" required hint="The team whose folder it lives in">
            <Input id="prov-owner" mono value={state.owner} onChange={(e) => update({ owner: e.target.value, ownerTouched: true })} placeholder="team-compute" />
          </Field>
          <Field label="Version" htmlFor="prov-version" required>
            <Input id="prov-version" mono value={state.version} onChange={(e) => update({ version: e.target.value })} invalid={!VERSION_RE.test(state.version.trim())} />
          </Field>
          <Field label="Description" htmlFor="prov-description" className="sm:col-span-2">
            <Textarea id="prov-description" rows={2} value={state.description} onChange={(e) => update({ description: e.target.value })} placeholder="Virtual machines, templates and snapshots in the vCenter of each environment." />
          </Field>
        </div>
      </Card>
    </div>
  )
}

/* ---- 2 · Connect --------------------------------------------------------- */

function ConnectStep({ state, update }: StepProps) {
  const toast = useToast()
  const [busy, setBusy] = useState<'import' | 'test' | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const hasEndpoints = state.type === 'openapi' || state.type === 'http'

  const applySpec = (spec: SpecImport) => {
    const keepEnvs = state.environments.some((e) => e.url.trim())
    update({
      specSha256: spec.sha256,
      specTitle: spec.title,
      title: state.title.trim() || spec.title,
      name: state.nameTouched || state.name ? state.name : kebab(spec.title),
      version: state.version === '1.0.0' && VERSION_RE.test(spec.version) ? spec.version : state.version,
      environments: keepEnvs || spec.servers.length === 0 ? state.environments : envNames(spec.servers).map((name, i) => ({ key: nextKey(), name, url: spec.servers[i] })),
      auth: { ...state.auth, type: authTypeOf(spec.auth?.type), header: spec.auth?.where && !state.auth.header ? spec.auth.where : state.auth.header },
      actions: spec.operations.map((op) =>
        blankAction({
          id: op.operationId,
          title: op.summary || op.operationId,
          operation: op.operationId,
          method: op.method.toUpperCase(),
          path: op.path,
          effect: op.effect,
          retry: op.retry,
          approval: op.effect === 'delete',
          inputText: op.input ? JSON.stringify(op.input, null, 2) : '',
          outputText: op.output ? JSON.stringify(op.output, null, 2) : '',
        }),
      ),
    })
    toast.success('Spec imported', `${spec.operations.length} operations from ${spec.title} ${spec.version}.`)
  }

  const importSpec = async () => {
    setBusy('import')
    try {
      const spec = await api.importSpec(specImportRequest(state))
      applySpec(spec)
      update({ specCommit: spec.commit ?? '' })
    } catch (e) {
      toast.error('Could not import the spec', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const uploadSpec = (file: File | undefined) => {
    if (!file) return
    void file.text().then((content) => update({ specContent: content, specFile: file.name, specSha256: '', specCommit: '' }))
  }
  const canImport = state.specMode === 'url' ? !!state.specUrl.trim() : state.specMode === 'git' ? HTTPS_RE.test(state.specGit.repo.trim()) : !!state.specContent.trim()

  const test = async () => {
    setBusy('test')
    try {
      // editing: the results are kept as review evidence for the provider's change request
      const result = await api.testConnection({ endpoints: endpointsOf(state), auth: authOf(state), ...(state.editing ? { provider: state.editing } : {}) })
      update({ tested: result })
      const bad = result.results.filter((r) => !r.ok).length
      if (bad) toast.toast({ title: `${bad} of ${result.results.length} environments did not answer`, tone: 'warning' })
      else toast.success('Every environment answered')
    } catch (e) {
      toast.error('Test failed', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const setEnv = (key: number, patch: Partial<Env>) => update({ environments: state.environments.map((e) => (e.key === key ? { ...e, ...patch } : e)), tested: null })
  const canTest = hasEndpoints && Object.keys(endpointsOf(state)).length > 0 && (state.auth.type === 'none' || !!state.auth.credential)

  return (
    <div className="space-y-5">
      {state.type === 'openapi' && !state.editing && (
        <Card
          title="Spec"
          subtitle="The platform reads the spec, pins its hash at publish and never edits it. From Git it also pins the commit it read."
          actions={
            <Segmented
              ariaLabel="Spec source"
              size="sm"
              value={state.specMode}
              onChange={(m) => update({ specMode: m })}
              options={[
                { value: 'url', label: 'URL' },
                { value: 'paste', label: 'Paste' },
                { value: 'upload', label: 'Upload' },
                { value: 'git', label: 'Git' },
              ]}
            />
          }
        >
          <div className="space-y-3">
            {state.specMode === 'url' && (
              <Field label="Spec URL" htmlFor="spec-url" required>
                <Input id="spec-url" mono value={state.specUrl} onChange={(e) => update({ specUrl: e.target.value })} placeholder="https://vcenter.example.com/rest/openapi.json" />
              </Field>
            )}
            {state.specMode === 'paste' && (
              <Field label="Spec (JSON or YAML)" htmlFor="spec-content" required>
                <Textarea id="spec-content" mono rows={8} value={state.specContent} onChange={(e) => update({ specContent: e.target.value })} placeholder="openapi: 3.0.0 …" />
              </Field>
            )}
            {state.specMode === 'upload' && (
              <Field label="Spec file" hint="A JSON or YAML file from your machine; the platform keeps the text, not the file">
                <div className="flex flex-wrap items-center gap-3">
                  <input ref={fileRef} type="file" accept=".json,.yaml,.yml,application/json,application/yaml,text/yaml" className="hidden" aria-label="Spec file" onChange={(e) => uploadSpec(e.target.files?.[0])} />
                  <Button icon="upload" onClick={() => fileRef.current?.click()}>
                    Choose a file
                  </Button>
                  {state.specFile ? (
                    <span className="text-caption text-secondary">
                      <Mono className="text-primary">{state.specFile}</Mono> · {state.specContent.length.toLocaleString()} characters
                    </span>
                  ) : (
                    <span className="text-caption text-tertiary">Nothing chosen yet.</span>
                  )}
                </div>
              </Field>
            )}
            {state.specMode === 'git' && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Repository" htmlFor="spec-git-repo" required hint="https only; the token, if any, is a credential, never part of the URL">
                  <Input id="spec-git-repo" mono value={state.specGit.repo} onChange={(e) => update({ specGit: { ...state.specGit, repo: e.target.value } })} placeholder="https://github.com/acme/vcenter-api" invalid={!!state.specGit.repo && !HTTPS_RE.test(state.specGit.repo.trim())} />
                </Field>
                <Field label="Ref" htmlFor="spec-git-ref" hint="A tag, branch or commit; main when empty">
                  <Input id="spec-git-ref" mono value={state.specGit.ref} onChange={(e) => update({ specGit: { ...state.specGit, ref: e.target.value } })} placeholder="v8.0.2" />
                </Field>
                <Field label="Path" htmlFor="spec-git-path" hint="The spec file inside the repository">
                  <Input id="spec-git-path" mono value={state.specGit.path} onChange={(e) => update({ specGit: { ...state.specGit, path: e.target.value } })} placeholder="openapi/vcenter.yaml" />
                </Field>
                <Field label="Read credential" hint="For a private repository: the vault: reference of a read token">
                  <CredentialPicker id="spec-git-credential" ariaLabel="Read credential" value={state.specGit.credential} onChange={(ref) => update({ specGit: { ...state.specGit, credential: ref } })} />
                </Field>
              </div>
            )}
            <div className="flex flex-wrap items-center justify-between gap-3">
              {state.specSha256 ? (
                <span className="inline-flex flex-wrap items-center gap-2 text-caption text-secondary">
                  <StatusDot kind="healthy" />
                  {state.specTitle} · {state.actions.length} operations · <Mono title={state.specSha256}>sha256 {state.specSha256.slice(0, 12)}…</Mono>
                  {state.specCommit && (
                    <Mono title={state.specCommit}>
                      commit {state.specCommit.slice(0, 12)}…
                    </Mono>
                  )}
                </span>
              ) : (
                <span className="text-caption text-tertiary">Import to list the operations and the servers.</span>
              )}
              <Button icon="download" loading={busy === 'import'} disabled={!canImport} onClick={() => void importSpec()}>
                Import spec
              </Button>
            </div>
          </div>
        </Card>
      )}

      {state.type === 'ansible' && !state.editing && (
        <Card title="Source" subtitle="A Git repository pinned to a tag and a commit; the platform runs playbooks with ansible-runner.">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Repository" htmlFor="ans-repo" required>
              <Input id="ans-repo" mono value={state.ansible.repo} onChange={(e) => update({ ansible: { ...state.ansible, repo: e.target.value } })} placeholder="github.com/acme/ops-playbooks" />
            </Field>
            <Field label="Ref" htmlFor="ans-ref" hint="A tag or a branch">
              <Input id="ans-ref" mono value={state.ansible.ref} onChange={(e) => update({ ansible: { ...state.ansible, ref: e.target.value } })} placeholder="v3.1.0" />
            </Field>
            <Field label="Path" htmlFor="ans-path" hint="Folder inside the repository, if not the root">
              <Input id="ans-path" mono value={state.ansible.path} onChange={(e) => update({ ansible: { ...state.ansible, path: e.target.value } })} placeholder="playbooks/" />
            </Field>
            <Field label="Commit" htmlFor="ans-commit" hint="Pinned at publish when empty">
              <Input id="ans-commit" mono value={state.ansible.commit} onChange={(e) => update({ ansible: { ...state.ansible, commit: e.target.value } })} placeholder="4e1d9a2c…" />
            </Field>
            <Field label="Execution environment" htmlFor="ans-ee" className="sm:col-span-2">
              <Input id="ans-ee" mono value={state.ansible.executionEnvironment} onChange={(e) => update({ ansible: { ...state.ansible, executionEnvironment: e.target.value } })} placeholder="ee-platform:2.17" />
            </Field>
          </div>
        </Card>
      )}

      {state.type === 'terraform' && !state.editing && (
        <Card title="Module" subtitle="State lives in the platform's backend, one workspace per job.">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,1fr)_10rem]">
            <Field label="Source" htmlFor="tf-source" required>
              <Input id="tf-source" mono value={state.terraform.source} onChange={(e) => update({ terraform: { ...state.terraform, source: e.target.value } })} placeholder="git::https://github.com/acme/tf-modules//lb" />
            </Field>
            <Field label="Version" htmlFor="tf-version">
              <Input id="tf-version" mono value={state.terraform.version} onChange={(e) => update({ terraform: { ...state.terraform, version: e.target.value } })} placeholder="2.3.0" />
            </Field>
          </div>
        </Card>
      )}

      {state.type === 'idp' && !state.editing && (
        <Card title="Tile" subtitle="A mock catalog answers until the portal's tiles API is wired.">
          <Field label="Tile id" htmlFor="idp-tile" required>
            <Input id="idp-tile" mono value={state.idp.tileId} onChange={(e) => update({ idp: { tileId: e.target.value } })} placeholder="block-storage-request" />
          </Field>
        </Card>
      )}

      {state.type === 'sdk' && !state.editing && (
        <Card title="Worker" subtitle="Your team runs the worker, built with a provider SDK; the platform only schedules the actions on its task queue.">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Task queue" htmlFor="sdk-queue" hint="Leave empty for the default" error={state.sdk.taskQueue.trim() && !TASK_QUEUE_RE.test(state.sdk.taskQueue.trim()) ? 'Letters, digits, dots, dashes and underscores' : undefined}>
              <Input id="sdk-queue" mono value={state.sdk.taskQueue} onChange={(e) => update({ sdk: { ...state.sdk, taskQueue: e.target.value } })} placeholder={`provider-${state.name || '<name>'}`} />
            </Field>
            <Field label="Heartbeat timeout" htmlFor="sdk-heartbeat" hint="A call is lost after this long without a heartbeat" error={state.sdk.heartbeatTimeout.trim() && !DURATION_RE.test(state.sdk.heartbeatTimeout.trim()) ? 'A duration like 30s or 2m' : undefined}>
              <Input id="sdk-heartbeat" mono value={state.sdk.heartbeatTimeout} onChange={(e) => update({ sdk: { ...state.sdk, heartbeatTimeout: e.target.value } })} placeholder="30s" />
            </Field>
          </div>
        </Card>
      )}

      {hasEndpoints && (
        <Card
          title="Environments"
          subtitle="One base URL per environment; a workflow picks the environment at run time."
          actions={
            <Button size="sm" icon="plus" onClick={() => update({ environments: [...state.environments, { key: nextKey(), name: state.environments.length === 1 ? 'prod' : '', url: '' }] })}>
              Add environment
            </Button>
          }
        >
          <div className="space-y-2">
            {state.environments.map((e) => (
              <div key={e.key} className="grid grid-cols-[7rem_minmax(0,1fr)_auto] items-center gap-2">
                <Input mono size="sm" value={e.name} onChange={(ev) => setEnv(e.key, { name: ev.target.value })} placeholder="dev" aria-label="Environment name" />
                <Input mono size="sm" value={e.url} onChange={(ev) => setEnv(e.key, { url: ev.target.value })} placeholder="https://system-dev.example.com/api" aria-label="Environment URL" invalid={!!e.url && !HTTPS_RE.test(e.url.trim())} />
                <IconButton label="Remove environment" size="sm" disabled={state.environments.length === 1} onClick={() => update({ environments: state.environments.filter((x) => x.key !== e.key), tested: null })}>
                  <Icon name="trash" size={14} />
                </IconButton>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Card title="Authentication" subtitle="Only the credential's name goes in the file; its value stays in the store.">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Type">
            <Select
              ariaLabel="Authentication type"
              value={state.auth.type}
              onChange={(t) => update({ auth: { ...state.auth, type: t as AuthType }, tested: null })}
              options={[
                { value: 'none', label: 'None', hint: 'Or a credential the runner itself uses' },
                { value: 'bearer', label: 'Bearer token', hint: 'Authorization: Bearer …' },
                { value: 'basic', label: 'Basic', hint: 'user:password' },
                { value: 'header', label: 'Header', hint: 'A named header, like X-Api-Key' },
              ]}
            />
          </Field>
          {state.auth.type === 'header' && (
            <Field label="Header" htmlFor="auth-header" required>
              <Input id="auth-header" mono value={state.auth.header} onChange={(e) => update({ auth: { ...state.auth, header: e.target.value }, tested: null })} placeholder="X-Api-Key" />
            </Field>
          )}
          <Field label="Credential" required={state.auth.type !== 'none'} className={state.auth.type === 'header' ? 'sm:col-span-2' : undefined}>
            <CredentialPicker id="auth-credential" value={state.auth.credential} onChange={(ref) => update({ auth: { ...state.auth, credential: ref }, tested: null })} required={state.auth.type !== 'none'} />
          </Field>
        </div>
        {hasEndpoints && (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-stroke-light pt-4">
            {state.tested ? (
              <ul className="space-y-1 text-caption" aria-label="Connection results">
                {state.tested.results.map((r) => (
                  <li key={r.environment} className="flex items-center gap-2">
                    <StatusDot kind={r.ok ? 'healthy' : 'critical'} label={r.ok ? 'ok' : 'failed'} />
                    <Mono className="text-primary">{r.environment}</Mono>
                    <span className="tabular-nums text-tertiary">{duration(r.durationMs)}</span>
                    <span className="text-secondary">{r.message}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <span className="text-caption text-tertiary">Signs in to every environment and asks for something harmless.</span>
            )}
            <Button icon="pulse" loading={busy === 'test'} disabled={!canTest} onClick={() => void test()}>
              Test connection
            </Button>
          </div>
        )}
      </Card>
    </div>
  )
}

/* ---- 3 · Actions --------------------------------------------------------- */

function ActionsStep({ state, update }: StepProps) {
  const patch = (key: number, p: Partial<ActionRow>) => update({ actions: state.actions.map((a) => (a.key === key ? { ...a, ...p } : a)) })
  const remove = (key: number) => update({ actions: state.actions.filter((a) => a.key !== key) })
  const add = () => update({ actions: [...state.actions, blankAction()] })
  const selected = state.actions.filter((a) => a.selected)
  const planOptions = (self: ActionRow) => [{ value: '', label: 'No plan step' }, ...state.actions.filter((a) => a.selected && a.id && a.key !== self.key).map((a) => ({ value: a.id, label: a.id }))]
  const effectOptions = EFFECTS.map((e) => ({ value: e, label: e }))
  const retryOptions = [
    { value: 'safe', label: 'safe', hint: 'May run twice without harm' },
    { value: 'unsafe', label: 'unsafe', hint: 'Needs a person before a retry' },
  ]

  if (state.type === 'openapi') {
    const allOn = state.actions.length > 0 && state.actions.every((a) => a.selected)
    const columns: TableColumn<ActionRow>[] = [
      {
        key: 'on',
        header: <Checkbox checked={allOn} onChange={(v) => update({ actions: state.actions.map((a) => ({ ...a, selected: v })) })} label={<span className="sr-only">Select all</span>} />,
        width: '2.5rem',
        render: (a) => <Checkbox checked={a.selected} onChange={(v) => patch(a.key, { selected: v })} label={<span className="sr-only">Include {a.operation}</span>} />,
      },
      { key: 'id', header: 'Action id', width: '12rem', render: (a) => <Input size="sm" mono value={a.id} onChange={(e) => patch(a.key, { id: e.target.value })} aria-label={`Id of ${a.operation}`} invalid={a.selected && !ID_RE.test(a.id)} disabled={!a.selected} /> },
      {
        key: 'op',
        header: 'Operation',
        render: (a) => (
          <div className="min-w-0">
            <Mono className="text-primary">{a.operation}</Mono>
            <div className="truncate text-caption text-tertiary">
              {a.method} {a.path}
              {a.title && a.title !== a.operation ? ` · ${a.title}` : ''}
            </div>
          </div>
        ),
      },
      { key: 'effect', header: 'Effect', width: '7rem', render: (a) => <Select size="sm" ariaLabel={`Effect of ${a.operation}`} value={a.effect} onChange={(v) => patch(a.key, { effect: v as Effect, approval: v === 'delete' ? true : a.approval })} options={effectOptions} disabled={!a.selected} /> },
      { key: 'retry', header: 'Retry', width: '7rem', render: (a) => <Select size="sm" ariaLabel={`Retry of ${a.operation}`} value={a.retry} onChange={(v) => patch(a.key, { retry: v as RetryPolicy })} options={retryOptions} disabled={!a.selected} /> },
      { key: 'approval', header: 'Approval', width: '6rem', render: (a) => <Checkbox checked={a.approval} onChange={(v) => patch(a.key, { approval: v })} label={<span className="sr-only">Approval for {a.operation}</span>} disabled={!a.selected} /> },
      { key: 'plan', header: 'Plan via', width: '9rem', render: (a) => <Select size="sm" ariaLabel={`Plan step of ${a.operation}`} value={a.plan} onChange={(v) => patch(a.key, { plan: v })} options={planOptions(a)} disabled={!a.selected || a.effect === 'read'} mono /> },
    ]
    return (
      <Card title="Actions" subtitle={state.actions.length ? `${selected.length} of ${state.actions.length} operations become actions. Effect and retry decide what a workflow may do with them.` : undefined} flush>
        {state.actions.length === 0 ? (
          <EmptyState icon="document" title="No operations yet" message="Go back to Connect and import the spec; its operations appear here." />
        ) : (
          <Table columns={columns} rows={state.actions} rowKey={(a) => String(a.key)} dense bordered={false} maxHeight="max-h-[32rem]" caption="Operations" />
        )}
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      {state.actions.length === 0 && (
        <Card flush>
          <EmptyState icon="bolt" title="No actions yet" message="Add what workflows may call." action={<Button icon="plus" onClick={add}>Add action</Button>} />
        </Card>
      )}
      {state.actions.map((a, i) => (
        <Card
          key={a.key}
          title={
            <span className="inline-flex items-center gap-2">
              <span>{a.id || `Action ${i + 1}`}</span>
              <EffectTag effect={a.effect} />
            </span>
          }
          actions={
            <div className="flex items-center gap-1">
              <Checkbox checked={a.selected} onChange={(v) => patch(a.key, { selected: v })} label="Include" />
              <IconButton label="Remove action" size="sm" onClick={() => remove(a.key)}>
                <Icon name="trash" size={14} />
              </IconButton>
            </div>
          }
        >
          <div className={cx('grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3', !a.selected && 'opacity-60')}>
            <Field label="Id" htmlFor={`act-${a.key}-id`} required error={a.id && !ID_RE.test(a.id) ? 'Like vm.get' : undefined}>
              <Input id={`act-${a.key}-id`} mono value={a.id} onChange={(e) => patch(a.key, { id: e.target.value })} placeholder="jobs.create" aria-label="Action id" />
            </Field>
            <Field label="Title" htmlFor={`act-${a.key}-title`}>
              <Input id={`act-${a.key}-title`} value={a.title} onChange={(e) => patch(a.key, { title: e.target.value })} placeholder="Start a backup" aria-label="Action title" />
            </Field>
            {state.type === 'http' && (
              <Field label="Operation" htmlFor={`act-${a.key}-path`} required>
                <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-2">
                  <Select size="md" ariaLabel="Method" value={a.method} onChange={(v) => patch(a.key, { method: v })} options={METHODS.map((m) => ({ value: m, label: m }))} mono />
                  <Input id={`act-${a.key}-path`} mono value={a.path} onChange={(e) => patch(a.key, { path: e.target.value })} placeholder="/jobs/{id}" aria-label="Action path" />
                </div>
              </Field>
            )}
            {state.type === 'ansible' && (
              <Field label="Playbook" htmlFor={`act-${a.key}-playbook`} required>
                <Input id={`act-${a.key}-playbook`} mono value={a.playbook} onChange={(e) => patch(a.key, { playbook: e.target.value })} placeholder="vm/restart.yml" aria-label="Playbook" />
              </Field>
            )}
            {state.type === 'sdk' && (
              <Field label="Activity" htmlFor={`act-${a.key}-activity`} hint="The name the worker registers">
                <Input id={`act-${a.key}-activity`} mono value={a.operation} onChange={(e) => patch(a.key, { operation: e.target.value })} placeholder={a.id || 'resizeCluster'} aria-label="Activity" />
              </Field>
            )}
            <Field label="Effect">
              <Select ariaLabel="Effect" value={a.effect} onChange={(v) => patch(a.key, { effect: v as Effect, approval: v === 'delete' ? true : a.approval })} options={effectOptions} />
            </Field>
            <Field label="Retry">
              <Select ariaLabel="Retry" value={a.retry} onChange={(v) => patch(a.key, { retry: v as RetryPolicy })} options={retryOptions} />
            </Field>
            <Field label="Plan via" hint="Another action that previews this one">
              <Select ariaLabel="Plan via" value={a.plan} onChange={(v) => patch(a.key, { plan: v })} options={planOptions(a)} mono disabled={a.effect === 'read'} />
            </Field>
            <div className="sm:col-span-2 lg:col-span-3">
              <Checkbox checked={a.approval} onChange={(v) => patch(a.key, { approval: v })} label="Needs an approval" description="A person signs off before the step runs; delete defaults to this." />
            </div>
            {state.type === 'http' && (
              <div className="sm:col-span-2 lg:col-span-3">
                <JsonEditor id={`act-${a.key}-example`} label="Example response" hint="The output schema is inferred from it" value={a.exampleText} onChange={(t) => patch(a.key, { exampleText: t })} rows={3} placeholder='{ "id": "bk-9001", "state": "running" }' objectOnly />
              </div>
            )}
            <div className="sm:col-span-2 lg:col-span-3">
              <JsonEditor id={`act-${a.key}-input`} label={state.type === 'ansible' || state.type === 'terraform' ? 'Vars (JSON schema)' : 'Input (JSON schema)'} value={a.inputText} onChange={(t) => patch(a.key, { inputText: t })} rows={4} placeholder='{ "type": "object", "required": ["id"], "properties": { "id": { "type": "string" } } }' objectOnly />
            </div>
            {(state.type === 'idp' || state.type === 'sdk' || state.type === 'http') && (
              <div className="sm:col-span-2 lg:col-span-3">
                <JsonEditor id={`act-${a.key}-output`} label="Output (JSON schema)" value={a.outputText} onChange={(t) => patch(a.key, { outputText: t })} rows={3} placeholder='{ "type": "object", "properties": { "id": { "type": "string" } } }' objectOnly />
              </div>
            )}
          </div>
        </Card>
      ))}
      {state.actions.length > 0 && (
        <div>
          <Button icon="plus" onClick={add}>
            Add action
          </Button>
        </div>
      )}
    </div>
  )
}

/* ---- 4 · Try it ---------------------------------------------------------- */

function TryStep({ state, update, yaml }: StepProps & { yaml: string }) {
  const toast = useToast()
  const selected = state.actions.filter((a) => a.selected && a.id)
  const envs = Object.keys(endpointsOf(state))
  const [actionId, setActionId] = useState(selected[0]?.id ?? '')
  const action = selected.find((a) => a.id === actionId) ?? selected[0]
  const [environment, setEnvironment] = useState(envs[0] ?? 'dev')
  const inputText = action?.inputText
  const schema = useMemo(() => (inputText === undefined ? undefined : parseSchema(inputText)), [inputText])
  const [input, setInput] = useState<SchemaValue>(() => defaultsFor(schema))
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const result = action ? state.tries[action.id] : undefined

  const pick = (id: string) => {
    setActionId(id)
    setInput(defaultsFor(parseSchema(selected.find((a) => a.id === id)?.inputText ?? '')))
  }

  const run = async () => {
    if (!action) return
    setBusy(true)
    try {
      const r = await api.tryAction({ yaml, action: action.id, environment, input })
      update({ tries: { ...state.tries, [action.id]: r } })
      if (r.ok) toast.success('It answered', `HTTP ${r.status} in ${duration(r.durationMs)}.`)
      else toast.toast({ title: 'It did not answer as expected', message: `HTTP ${r.status}`, tone: 'warning' })
    } catch (e) {
      toast.error('The call did not go through', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (selected.length === 0) {
    return (
      <Card flush>
        <EmptyState icon="play" title="Nothing to try" message="Select at least one action first." />
      </Card>
    )
  }

  const risky = !!action && action.effect !== 'read'
  const recorded = action ? action.id in state.samples : false
  const fixed = action ? action.id in state.overrides : false
  const used = !!result?.yaml && state.draftYaml === result.yaml

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
      <Card title="One real call" subtitle="Optional, and worth it: the answer becomes the sample dry runs use, and it proves the schema.">
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Action">
              <Select ariaLabel="Action" value={action?.id ?? ''} onChange={pick} mono options={selected.map((a) => ({ value: a.id, label: a.id, hint: `${a.title || a.id} · ${a.effect}` }))} />
            </Field>
            <Field label="Environment">
              <Select ariaLabel="Environment" value={environment} onChange={setEnvironment} mono options={(envs.length ? envs : ['dev']).map((e) => ({ value: e, label: e }))} />
            </Field>
          </div>
          {action && (
            <div className="flex flex-wrap items-center gap-2">
              <EffectTag effect={action.effect} />
              {risky && (
                <Tag size="sm" tone="warning" icon="alert-triangle">
                  changes something in {environment}
                </Tag>
              )}
            </div>
          )}
          <SchemaForm schema={schema} value={input} onChange={setInput} idPrefix="wtry" pickers={false} />
          <div className="flex justify-end">
            <Button variant="primary" icon="play" loading={busy} disabled={!action} onClick={() => (risky ? setConfirm(true) : void run())}>
              Try it
            </Button>
          </div>
        </div>
      </Card>

      <Card title="Answer" subtitle={result ? undefined : 'The real response shows here, compared with the declared output.'}>
        {!result || !action ? (
          <EmptyState compact icon="pulse" title="Not tried yet" message="Nothing is recorded until you try." />
        ) : (
          <div className="animate-fade-in space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <Tag tone={result.ok ? 'healthy' : 'critical'} dot>
                {result.ok ? 'OK' : 'Failed'}
              </Tag>
              <Mono className="text-primary">HTTP {result.status}</Mono>
              <span className="text-caption tabular-nums text-tertiary">{duration(result.durationMs)}</span>
              {result.verified !== undefined && (
                <Tag size="sm" tone={result.verified ? 'healthy' : 'warning'}>
                  {result.verified ? 'verified afterwards' : 'not verified'}
                </Tag>
              )}
              {result.mismatches.length > 0 ? (
                <Tag size="sm" tone="warning" outline>
                  {result.mismatches.length} field{result.mismatches.length === 1 ? '' : 's'} differ from the schema
                </Tag>
              ) : (
                <Tag size="sm" tone="healthy" outline>
                  matches the schema
                </Tag>
              )}
            </div>
            <JsonView value={result.response} name="response" defaultExpanded={2} maxHeight="max-h-72" />
            {result.mismatches.length > 0 && (
              <ul className="space-y-1 text-caption">
                {result.mismatches.map((m) => (
                  <li key={m.field} className="flex flex-wrap gap-2">
                    <Mono className="text-primary">{m.field}</Mono>
                    <span className="text-tertiary">declared</span>
                    <Mono>{JSON.stringify(m.expected)}</Mono>
                    <span className="text-tertiary">got</span>
                    <Mono className="text-primary">{JSON.stringify(m.actual)}</Mono>
                  </li>
                ))}
              </ul>
            )}
            {(result.environment || result.at) && (
              <p className="flex flex-wrap items-center gap-1 text-caption text-secondary" data-testid="try-evidence">
                <Icon name="check" size={12} className="text-healthy" />
                Kept as review evidence{result.environment ? ` · tried in ${result.environment}` : ''}
                {result.at && (
                  <>
                    {' · '}
                    <When at={result.at} />
                  </>
                )}
                . The review's tried check counts it.
              </p>
            )}
            {result.yaml && (
              <details className="rounded-md border border-stroke-light" data-testid="try-yaml">
                <summary className="cursor-pointer px-3 py-2 text-sm text-primary">The file with this answer as {action.id}'s sample</summary>
                <CodeBlock code={result.yaml} language="yaml" maxHeight="max-h-72" />
              </details>
            )}
            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-stroke-light pt-3">
              {result.yaml && (
                <Button
                  variant="primary"
                  icon={used ? 'check' : 'document'}
                  disabled={used}
                  title="The backend's file: the response saved as the sample, with where it was recorded"
                  onClick={() => {
                    update({ draftYaml: result.yaml ?? null })
                    toast.success('Draft replaced', `The file now carries ${action.id}'s answer as its sample.`)
                  }}
                >
                  {used ? 'Using this file' : 'Use this'}
                </Button>
              )}
              {!state.editing && (
                <>
                  <Button
                    icon={recorded ? 'check' : 'document'}
                    disabled={recorded}
                    onClick={() => {
                      update({ samples: { ...state.samples, [action.id]: result.response } })
                      toast.success('Sample recorded', `Dry runs of ${action.id} answer with this.`)
                    }}
                  >
                    {recorded ? 'Sample recorded' : 'Record as sample'}
                  </Button>
                  <Button
                    icon={fixed ? 'check' : 'edit'}
                    disabled={fixed || !result.response || typeof result.response !== 'object'}
                    onClick={() => {
                      update({ overrides: { ...state.overrides, [action.id]: inferSchema(result.response) } })
                      toast.success('Schema fixed', `${action.id}'s output now follows the real answer.`)
                    }}
                  >
                    {fixed ? 'Schema fixed' : 'Fix schema from response'}
                  </Button>
                </>
              )}
            </div>
          </div>
        )}
      </Card>

      {confirm && action && (
        <ConfirmDialog
          title={`Run ${action.id} in ${environment}?`}
          tone={action.effect === 'delete' ? 'danger' : 'warning'}
          icon="alert-triangle"
          message={`This is a real ${action.effect} call, not a dry run.`}
          confirmLabel="Run it"
          onConfirm={async () => {
            await run()
          }}
          onClose={() => setConfirm(false)}
        />
      )}
    </div>
  )
}

/* ---- 5 · Review ---------------------------------------------------------- */

function ReviewStep({ state, update, yaml }: StepProps & { yaml: string }) {
  const toast = useToast()
  const [judged, setJudged] = useState<{ yaml: string; result: ProviderValidation } | null>(null)
  const validation = judged?.yaml === yaml ? judged.result : null
  const setValidation = (result: ProviderValidation) => setJudged({ yaml, result })
  const [busy, setBusy] = useState<'validate' | 'publish' | null>(null)
  const [asking, setAsking] = useState(false)

  const validate = async (): Promise<ProviderValidation | null> => {
    setBusy('validate')
    try {
      const v = await api.validateProvider(yaml)
      setValidation(v)
      return v
    } catch (e) {
      toast.error('The checks did not run', errorMessage(e))
      return null
    } finally {
      setBusy(null)
    }
  }

  const failed = (v: ProviderValidation) => v.checks.some((c) => c.status === 'failed') || v.problems.some((p) => p.level === 'error')
  // the review judges the evidence; publish refuses a file whose review is not ready
  const reviewBlocks = (v: ProviderValidation) => v.review?.ready === false
  const review = validation?.review
  const suggested = review?.slo ?? null

  const publish = async () => {
    setBusy('publish')
    try {
      let v = validation
      if (!v) {
        v = await api.validateProvider(yaml)
        setValidation(v)
      }
      if (failed(v) || reviewBlocks(v)) {
        toast.error('Not ready to publish', reviewBlocks(v) ? 'The review is not ready: see what it found.' : 'Fix the failed checks first.')
        return
      }
      const accepted = await api.publishProvider(yaml, state.strategy)
      toast.toast({
        title: `Opened change request ${accepted.id}`,
        message: `${state.name} ${state.version} goes through review${state.strategy === 'canary' ? ', then a canary' : ', then every call moves at once'}.`,
        tone: 'healthy',
        duration: 8000,
        action: { label: 'Open change request', onClick: () => navigate(pathFor('change-request', { id: accepted.id })) },
      })
      navigate(pathFor('change-request', { id: accepted.id }))
    } catch (e) {
      toast.error('Could not publish', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const path = `providers/${state.owner || 'team'}/${state.name || 'provider'}.yaml`

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="min-w-0 space-y-3">
        {state.imported && <ImportSummary imported={state.imported} />}
        {state.draftYaml && (
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-accent/40 bg-accent-tint px-4 py-2 text-sm">
            <span className="inline-flex items-center gap-2 text-primary">
              <Icon name={state.editing ? 'edit' : 'sparkles'} size={14} className="text-accent" />
              {state.editing ? `The file as published, at version ${state.version}; a try's answer goes in with Use this.` : state.imported ? "The import's draft replaces the wizard's document." : "This draft replaces the wizard's document."}
            </span>
            {!state.editing && (
              <Button size="sm" variant="ghost" onClick={() => update({ draftYaml: null })}>
                Back to the wizard's YAML
              </Button>
            )}
          </div>
        )}
        <CodeBlock code={yaml} language="yaml" title={path} lineNumbers maxHeight="max-h-[60vh]" />
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="ghost" icon="download" onClick={() => downloadText(yaml, `${state.name || 'provider'}.yaml`, 'application/yaml')}>
            Download
          </Button>
          <Button size="sm" variant="ghost" icon="sparkles" onClick={() => setAsking(true)}>
            Ask Devin to draft it
          </Button>
        </div>
      </div>

      <aside className="min-w-0 space-y-4">
        <Card
          title="Checks"
          actions={
            <Button size="sm" icon="refresh" loading={busy === 'validate'} onClick={() => void validate()}>
              Run checks
            </Button>
          }
        >
          {!validation ? (
            <p className="text-sm text-secondary">Schema, reachability, effect and approval rules, samples. Publish runs them too.</p>
          ) : (
            <div className="space-y-3">
              <ul className="space-y-1.5" aria-label="Checks">
                {validation.checks.map((c) => (
                  <li key={c.id} className="flex items-start gap-2 text-sm">
                    <StatusDot kind={checkDot(c.status)} className="mt-1.5" label={c.status} />
                    <span className="min-w-0">
                      <span className="text-primary">{c.label}</span>
                      {c.detail && <span className="block text-caption text-secondary">{c.detail}</span>}
                    </span>
                  </li>
                ))}
              </ul>
              {validation.problems.length > 0 && <ProblemList problems={validation.problems} compact />}
              {!failed(validation) && !reviewBlocks(validation) && (
                <p className="flex items-center gap-1.5 text-caption text-healthy">
                  <Icon name="check" size={13} />
                  Ready to publish.
                </p>
              )}
            </div>
          )}
        </Card>

        {validation && review !== undefined && (
          <Card title="Review" subtitle="What the platform saw in this wizard, judged as the reviewer sees it on the change request." data-testid="review-evidence">
            {review === null ? (
              <p className="text-sm text-secondary">Not a provider file: nothing to review.</p>
            ) : (
              <div className="space-y-3">
                <Tag tone={review.ready ? 'healthy' : 'warning'} dot>
                  {review.ready ? 'Ready for review' : 'Not ready yet'}
                </Tag>
                <ul className="space-y-1.5" aria-label="Review checks">
                  {review.checks.map((c) => (
                    <li key={c.id} className="flex items-start gap-2 text-sm">
                      <StatusDot kind={checkDot(c.status)} className="mt-1.5" label={c.status} />
                      <span className="min-w-0">
                        <span className="text-primary">{c.label}</span>
                        {c.detail && <span className="block text-caption text-secondary">{c.detail}</span>}
                      </span>
                    </li>
                  ))}
                </ul>
                {review.problems.length > 0 && <ProblemList problems={review.problems} compact />}
                {suggested && (
                  <div className="rounded-md border border-stroke-light bg-surface-secondary px-3 py-2 text-caption" data-testid="slo-suggestion">
                    <p className="text-primary">
                      Suggested SLO: {suggested.successRate}% success, p95 {duration(suggested.p95Ms)}
                    </p>
                    <p className="text-secondary">
                      Observed p95 {duration(suggested.observedP95Ms)} over {suggested.calls} calls. {suggested.detail}
                    </p>
                    {!state.editing && (
                      <Button size="sm" className="mt-2" onClick={() => update({ slo: { ...state.slo, successRate: String(suggested.successRate), p95Ms: String(suggested.p95Ms) } })}>
                        Use suggested SLO
                      </Button>
                    )}
                  </div>
                )}
              </div>
            )}
          </Card>
        )}

        <Card title="Publish">
          <div className="space-y-4">
            <Field label="Strategy" hint={state.strategy === 'canary' ? 'New jobs move to this version in stages, gated on its health' : 'Every new job uses this version as soon as it merges'}>
              <Segmented
                ariaLabel="Strategy"
                full
                value={state.strategy}
                onChange={(v) => update({ strategy: v })}
                options={[
                  { value: 'canary', label: 'Canary' },
                  { value: 'all', label: 'All at once' },
                ]}
              />
            </Field>
            {state.editing ? (
              <p className="text-caption text-tertiary">The SLO and the alert channel change from the provider page, through a change request of their own.</p>
            ) : (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="SLO success (%)" htmlFor="pub-slo-rate">
                    <Input id="pub-slo-rate" type="number" min={1} max={100} step={0.1} value={state.slo.successRate} onChange={(e) => update({ slo: { ...state.slo, successRate: e.target.value } })} />
                  </Field>
                  <Field label="SLO p95 (ms)" htmlFor="pub-slo-p95">
                    <Input id="pub-slo-p95" type="number" min={1} value={state.slo.p95Ms} onChange={(e) => update({ slo: { ...state.slo, p95Ms: e.target.value } })} />
                  </Field>
                </div>
                <Field label="Alert channel" htmlFor="pub-channel">
                  <Input id="pub-channel" mono value={state.slo.channel} onChange={(e) => update({ slo: { ...state.slo, channel: e.target.value } })} placeholder="#team-alerts" />
                </Field>
              </>
            )}
            <Button variant="primary" icon="git-branch" className="w-full" loading={busy === 'publish'} disabled={busy === 'validate' || (!!validation && (failed(validation) || reviewBlocks(validation)))} onClick={() => void publish()}>
              Publish as change request
            </Button>
            <p className="text-caption text-tertiary">Opens a change request on a branch; the checks run again there, and merging publishes.</p>
          </div>
        </Card>
      </aside>

      {asking && (
        <DraftModal
          initial={describe(state)}
          onUse={(d) => {
            update({ draftYaml: d.yaml })
            setAsking(false)
          }}
          onClose={() => setAsking(false)}
        />
      )}
    </div>
  )
}

function DraftModal({ initial, onUse, onClose }: { initial: string; onUse: (d: ProviderDraft) => void; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-3xl" label="Ask Devin to draft the provider">
      <DraftForm initial={initial} onUse={onUse} />
    </Modal>
  )
}

function DraftForm({ initial, onUse }: { initial: string; onUse: (d: ProviderDraft) => void }) {
  const close = useOverlayClose()
  const toast = useToast()
  const [text, setText] = useState(initial)
  const [draft, setDraft] = useState<ProviderDraft | null>(null)
  const [busy, setBusy] = useState(false)

  const ask = async () => {
    setBusy(true)
    try {
      setDraft(await api.draftProvider(text.trim()))
    } catch (e) {
      toast.error('Devin did not answer', errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <OverlayHeader title="Ask Devin to draft it" subtitle="Describe the system in words; the draft comes back as a provider document for you to review." icon="sparkles" />
      <OverlayBody className="space-y-4">
        <Field label="Description" htmlFor="draft-text">
          <Textarea id="draft-text" rows={6} value={text} onChange={(e) => setText(e.target.value)} disabled={busy} />
        </Field>
        {draft && (
          <div className="animate-fade-in space-y-3">
            <p className="text-sm text-primary">{draft.summary}</p>
            {draft.assumptions.length > 0 && (
              <ul className="space-y-1 text-caption text-secondary">
                {draft.assumptions.map((a, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                    {a}
                  </li>
                ))}
              </ul>
            )}
            <CodeBlock code={draft.yaml} language="yaml" lineNumbers maxHeight="max-h-80" />
          </div>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="ghost" onClick={close} disabled={busy}>
          Cancel
        </Button>
        {draft ? (
          <>
            <Button icon="refresh" loading={busy} onClick={() => void ask()}>
              Ask again
            </Button>
            <Button variant="primary" icon="check" onClick={() => onUse(draft)}>
              Use this draft
            </Button>
          </>
        ) : (
          <Button variant="primary" icon="sparkles" loading={busy} disabled={!text.trim()} onClick={() => void ask()}>
            Ask Devin
          </Button>
        )}
      </OverlayFooter>
    </>
  )
}
