/**
 * Provider drafts: the providers a workflow uses before they are published. A draft is the Add
 * provider wizard's own state (`WizardState`, components/provider-editor/state.ts), so the designer
 * edits it with the wizard's steps and its file is the wizard's (`draftYaml`). Drafts live in the
 * browser only, in localStorage per workflow as the sticky notes do (stickies.ts), outside the
 * document and its undo history.
 *
 * The designer reads a draft as a published provider (`draftProvider`, merged into the registry),
 * sends its file with a dry run, Validate and Publish (`draftFile`) when the workflow calls it
 * (`draftsUsedBy`), under the workflow's owner when it names none.
 *
 * A draft never holds a secret value: its credentials are `vault:` references, as the credential
 * picker gives them. What is stored keeps no credential that is not one, no user or password
 * written into a URL, and none of the tries' live answers (the review's evidence is the server's).
 */
import type { WorkflowDocument } from '@iaf/workflow-checks'
import type { AnsibleImport, ImportedPlaybook, ProviderDetail, ProviderImport, ProviderType, RegistryAction, RegistryPlaybook, RegistryProvider, Schema, TerraformVariable } from '../../lib/types'
import { initialState, nextKey, parseSchema, providerYaml, stateFromProvider, typeChange, type ActionRow, type WizardState } from '../../components/provider-editor/state'
import { allStepLocations } from './graph'

/** Where a draft came from: a provider not published yet, or the next patch of a published one. */
export type ProviderDraftOrigin = 'new' | 'edit'

/** A provider the workflow uses before it is published: the wizard's state, and where it came from. */
export interface ProviderDraft extends WizardState {
  /** Its key in the store's `providerDrafts`, kept while the provider is named and renamed. */
  id: string
  origin: ProviderDraftOrigin
  /** An edit's published version, the one `version` (its next patch) follows; null for a new provider. */
  publishedVersion: string | null
}

function newDraftId(): string {
  return `draft_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`
}

/**
 * A new provider's draft: the wizard's first state with the type picked, as the wizard's Type step
 * leaves it (its starter actions and the auth it starts with), then what `patch` sets.
 */
export function newProviderDraft(type: ProviderType = 'openapi', patch: Partial<WizardState> = {}): ProviderDraft {
  const s = initialState()
  return { ...s, ...typeChange(s, type), ...patch, id: newDraftId(), origin: 'new', publishedVersion: null }
}

/** The draft of a published provider's next patch, as the wizard's Edit provider starts it: its file is the draft's. */
export function draftFromProvider(detail: ProviderDetail): ProviderDraft {
  return { ...stateFromProvider(detail), id: newDraftId(), origin: 'edit', publishedVersion: detail.version }
}

/**
 * The draft's provider YAML, the wizard's: the file it took instead when it took one (its
 * `draftYaml` field: Devin's, a try's, an import's, an edit's published file), else the one its
 * state writes (`buildProviderDocument`, written by `emitYaml`).
 */
export function draftYaml(draft: ProviderDraft): string {
  return providerYaml(draft)
}

/** The workflow's draft of the provider of that name, if it has one. */
export function findProviderDraft(drafts: Readonly<Record<string, ProviderDraft>>, name: string | null | undefined): ProviderDraft | undefined {
  return name ? Object.values(drafts).find((d) => d.name === name) : undefined
}

/* ----------------------------------------------------------------------------
   The workflow's owner, the file sent, the registry's view of a draft
   ------------------------------------------------------------------------- */

/**
 * The draft under the workflow's owner (`metadata.owner`) when it names none: the team its file,
 * its change request and the review's evidence (kept under the draft's name and owner) go under.
 */
export function withDefaultOwner(draft: ProviderDraft, owner: string | null | undefined): ProviderDraft {
  const team = owner?.trim()
  return !draft.owner.trim() && team ? { ...draft, owner: team } : draft
}

/** The file the designer sends for a draft (a dry run, Validate, the review, Publish): its YAML, under the workflow's owner when it names none. */
export function draftFile(draft: ProviderDraft, owner: string | null | undefined): string {
  return draftYaml(withDefaultOwner(draft, owner))
}

/** An action row as the registry lists an action: the effect, retry and approval the backend reads from the file (a delete needs an approval unless it says otherwise), the schemas, and the sample. */
function rowAction(d: ProviderDraft, a: ActionRow): RegistryAction {
  const sample = d.samples[a.id] ?? (d.type === 'http' ? parseSchema(a.exampleText) : undefined)
  return {
    id: a.id,
    title: a.title.trim() || a.id,
    effect: a.effect,
    retry: a.retry,
    approval: a.approval || a.effect === 'delete' ? 'required' : 'none',
    input: parseSchema(a.inputText) ?? null,
    // "Fix schema from response" writes the response schema the action answers with
    output: d.overrides[a.id] ?? parseSchema(a.outputText) ?? null,
    ...(sample !== undefined ? { sample } : {}),
  }
}

/** A terraform variable's type as a JSON schema type: what the module's `vars` take. */
function terraformSchema(v: TerraformVariable): Schema {
  const t = v.type.trim()
  const type = t === 'string' ? 'string' : t === 'number' ? 'number' : t === 'bool' ? 'boolean' : /^(list|set|tuple)\b/.test(t) ? 'array' : /^(map|object)\b/.test(t) ? 'object' : undefined
  return { ...(type ? { type } : {}), ...(v.description ? { description: v.description } : {}), ...(v.default !== undefined ? { default: v.default } : {}) }
}

const COUNTS: Record<string, Schema> = { add: { type: 'integer' }, change: { type: 'integer' }, destroy: { type: 'integer' } }

/** The playbooks of an Ansible import the provider takes: the chosen ones, every one when none was chosen. */
const chosenPlaybooks = ({ playbooks, chosen }: AnsibleImport): ImportedPlaybook[] => playbooks.filter((p) => chosen.length === 0 || chosen.includes(p.action))

/**
 * The actions an import drafted, for a draft that took the import's file (its rows are empty
 * then): the chosen playbooks of an Ansible repository, a Terraform module's Plan and Apply, as
 * the backend's import writes them (AnsiblePlaybooks, TerraformModule).
 */
function importedActions(imported: ProviderImport): RegistryAction[] {
  if (imported.kind === 'ansible') {
    return chosenPlaybooks(imported.result).map((p) => ({ id: p.action, title: p.title || p.action, effect: p.effect, retry: p.effect === 'read' ? 'safe' : 'unsafe', approval: p.effect === 'delete' ? 'required' : 'none', input: p.vars, output: null }))
  }
  const variables = imported.result.variables.filter((v) => !v.secret)
  const ordered = [...variables.filter((v) => v.required), ...variables.filter((v) => !v.required)]
  const required = variables.filter((v) => v.required).map((v) => v.name)
  const vars: Schema = { type: 'object', ...(required.length ? { required } : {}), properties: Object.fromEntries(ordered.map((v) => [v.name, terraformSchema(v)])) }
  const outputs: Schema = { type: 'object', properties: Object.fromEntries(imported.result.outputs.filter((o) => !o.sensitive).map((o) => [o.name, o.description ? { description: o.description } : {}])) }
  return [
    { id: 'plan', title: 'Plan', effect: 'read', retry: 'safe', approval: 'none', input: vars, output: { type: 'object', properties: COUNTS } },
    { id: 'apply', title: 'Apply', effect: 'create', retry: 'unsafe', approval: 'none', input: vars, output: { type: 'object', properties: { outputs, ...COUNTS } } },
  ]
}

/**
 * The draft as the registry lists a provider, so the node creator, the parameter forms, Expected
 * output and the live checks read it as a published one: its selected actions (or, for a draft
 * that took an import's file, the import's), a terraform draft's module, the workflow's owner when
 * it names none, and `unpublished: 'draft'`.
 */
export function draftProvider(draft: ProviderDraft, owner?: string | null): RegistryProvider {
  const d = withDefaultOwner(draft, owner)
  const rows = d.actions.filter((a) => a.selected && a.id.trim())
  const actions = rows.length === 0 && d.imported ? importedActions(d.imported) : rows.map((a) => rowAction(d, a))
  const source = d.terraform.source.trim()
  const version = d.terraform.version.trim()
  return {
    name: d.name,
    title: d.title.trim() || d.name,
    type: d.type,
    ...(d.owner.trim() ? { owner: d.owner.trim() } : {}),
    actions,
    ...(d.type === 'terraform' && source ? { module: version ? { source, version } : { source } } : {}),
    unpublished: 'draft',
  }
}

/**
 * An Ansible draft's playbooks as the library lists them (GET /registry/playbooks names a published
 * provider's `<provider>/<action>`), so the step's Playbook picker and the node creator offer them:
 * its selected actions' (or, for a draft that took an import's file, the import's chosen ones),
 * under the workflow's owner when it names none, tagged `unpublished: 'draft'`. None for another type.
 */
export function draftPlaybooks(draft: ProviderDraft, owner?: string | null): RegistryPlaybook[] {
  if (draft.type !== 'ansible' || !draft.name.trim()) return []
  const d = withDefaultOwner(draft, owner)
  const rows = d.actions.filter((a) => a.selected && a.id.trim())
  const listed = rows.length === 0 && d.imported?.kind === 'ansible' ? chosenPlaybooks(d.imported.result).map((p) => ({ id: p.action, path: p.path })) : rows.map((a) => ({ id: a.id, path: a.playbook.trim() }))
  return listed.map(({ id, path }) => ({ name: `${d.name}/${id}`, version: d.version.trim(), path, owner: d.owner.trim(), unpublished: 'draft' as const }))
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * A terraform draft's module source: its own, else, for the draft of a published provider's next
 * patch (whose state names no module, `stateFromProvider`), the published provider's.
 */
function moduleSource(d: ProviderDraft, published: readonly RegistryProvider[]): string {
  const own = d.terraform.source.trim()
  if (own || d.origin !== 'edit') return own
  const name = d.editing ?? d.name
  return published.find((p) => p.name === name)?.module?.source.trim() ?? ''
}

/**
 * The library playbook an ansible step runs, `<provider>/<playbook>`: the plain string, or the
 * object's `library` (config-as-code.md); undefined for a git or uploaded playbook.
 */
export function libraryPlaybook(playbook: unknown): string | undefined {
  if (typeof playbook === 'string') return playbook
  return isRecord(playbook) && typeof playbook.library === 'string' ? playbook.library : undefined
}

/**
 * Whether a step calls the draft's provider: a provider step that names it, an ansible step on a
 * playbook of its library (`<name>/<action>`, as the plain string or `library`) or of its
 * repository, a terraform step on its module, an IDP step on one of its tiles (the creator's IDP
 * items name a tile by its action id).
 */
function calls(body: Record<string, unknown>, d: ProviderDraft, published: readonly RegistryProvider[]): boolean {
  const w = isRecord(body.with) ? body.with : {}
  switch (body.call) {
    case 'provider':
      return w.provider === d.name
    case 'ansible': {
      if (d.type !== 'ansible') return false
      const pb = w.playbook
      const library = libraryPlaybook(pb)
      if (library !== undefined) return library.startsWith(`${d.name}/`)
      const repo = isRecord(pb) && isRecord(pb.git) ? pb.git.repo : undefined
      return typeof repo === 'string' && !!d.ansible.repo.trim() && repo.trim() === d.ansible.repo.trim()
    }
    case 'terraform': {
      const source = isRecord(w.module) ? w.module.source : undefined
      const own = d.type === 'terraform' ? moduleSource(d, published) : ''
      return typeof source === 'string' && !!own && source.trim() === own
    }
    case 'idp':
      return d.type === 'idp' && typeof w.tile === 'string' && d.actions.some((a) => a.selected && a.id === w.tile)
    default:
      return false
  }
}

/**
 * The drafts the workflow uses, in the order its steps (nested ones and the catch flow's
 * included) first call them: the providers a dry run, Validate and Publish take along. A draft
 * without a name is called by nothing. `published` is the registry's providers as loaded: the
 * module of a terraform provider whose next patch a draft is.
 */
export function draftsUsedBy(doc: WorkflowDocument, drafts: Readonly<Record<string, ProviderDraft>>, published: readonly RegistryProvider[] = []): ProviderDraft[] {
  const named = Object.values(drafts).filter((d) => d.name.trim())
  const out: ProviderDraft[] = []
  for (const { body } of allStepLocations(doc)) {
    for (const d of named) if (!out.some((o) => o.name === d.name) && calls(body as Record<string, unknown>, d, published)) out.push(d)
  }
  return out
}

/* ----------------------------------------------------------------------------
   Storage: localStorage per workflow, as the sticky notes
   ------------------------------------------------------------------------- */

function key(workflow: string): string {
  return `iaf-designer-provider-drafts:${workflow}`
}

/** A credential as a stored draft keeps it: a `vault:` reference, else none. */
const vaultRef = (credential: string): string => (credential.startsWith('vault:') ? credential : '')

/** An http(s) URL, anywhere in the text, without the user and password written before its host. */
const withoutUserinfo = (url: string): string => url.replace(/(https?:\/\/)[^/?#\s]*@/gi, '$1')

/** What a draft keeps in storage: its credentials only as `vault:` references, its URLs without a user or password, and no live answer of a try. */
function stored(d: ProviderDraft): ProviderDraft {
  return {
    ...d,
    specUrl: withoutUserinfo(d.specUrl),
    specGit: { ...d.specGit, repo: withoutUserinfo(d.specGit.repo), credential: vaultRef(d.specGit.credential) },
    environments: d.environments.map((e) => ({ ...e, url: withoutUserinfo(e.url) })),
    auth: { ...d.auth, credential: vaultRef(d.auth.credential) },
    ansible: { ...d.ansible, repo: withoutUserinfo(d.ansible.repo) },
    terraform: { ...d.terraform, source: withoutUserinfo(d.terraform.source) },
    tries: {},
  }
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * A stored draft as the store holds it: under the wizard's first state, so a field added since has
 * its default; its rows with new keys, since the rows made in this page load count theirs from 1;
 * and nothing a stored draft may not keep. Null when it is not a draft.
 */
function restored(raw: unknown): ProviderDraft | null {
  if (!isObject(raw) || typeof raw.id !== 'string' || !raw.id || (raw.origin !== 'new' && raw.origin !== 'edit')) return null
  const d = { ...initialState(), ...raw } as ProviderDraft
  if (!Array.isArray(d.environments) || !Array.isArray(d.actions) || !Array.isArray(d.errors)) return null
  return stored({
    ...d,
    publishedVersion: typeof raw.publishedVersion === 'string' ? raw.publishedVersion : null,
    environments: d.environments.map((e) => ({ ...e, key: nextKey() })),
    actions: d.actions.map((a) => ({ ...a, key: nextKey() })),
    errors: d.errors.map((e) => ({ ...e, key: nextKey() })),
  })
}

/** The workflow's drafts kept in this browser, by id; none when storage is unavailable. A draft this version cannot read is left out. */
export function loadProviderDrafts(workflow: string): Record<string, ProviderDraft> {
  let parsed: unknown
  try {
    const raw = localStorage.getItem(key(workflow))
    if (!raw) return {}
    parsed = JSON.parse(raw)
  } catch {
    return {}
  }
  const out: Record<string, ProviderDraft> = {}
  for (const item of Array.isArray(parsed) ? parsed : []) {
    try {
      const d = restored(item)
      if (d) out[d.id] = d
    } catch {
      /* not a draft this version reads */
    }
  }
  return out
}

/** Keeps the workflow's drafts in this browser, without what a stored draft may not keep. */
export function saveProviderDrafts(workflow: string, drafts: Readonly<Record<string, ProviderDraft>>): void {
  try {
    const list = Object.values(drafts).map(stored)
    if (list.length === 0) localStorage.removeItem(key(workflow))
    else localStorage.setItem(key(workflow), JSON.stringify(list))
  } catch {
    /* storage unavailable or full: the drafts live for this session */
  }
}
