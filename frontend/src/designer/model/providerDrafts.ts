/**
 * Provider drafts: the providers a workflow uses before they are published. A draft is the Add
 * provider wizard's own state (`WizardState`, components/provider-editor/state.ts), so the designer
 * edits it with the wizard's steps and its file is the wizard's (`draftYaml`). Drafts live in the
 * browser only, in localStorage per workflow as the sticky notes do (stickies.ts), outside the
 * document and its undo history.
 *
 * A draft never holds a secret value: its credentials are `vault:` references, as the credential
 * picker gives them. What is stored keeps no credential that is not one, no user or password
 * written into a URL, and none of the tries' live answers (the review's evidence is the server's).
 */
import type { ProviderDetail, ProviderType } from '../../lib/types'
import { blankImportForm, initialState, nextKey, providerYaml, stateFromProvider, typeChange, type ImportForm, type WizardState } from '../../components/provider-editor/state'

/** Where a draft came from: a provider not published yet, or the next patch of a published one. */
export type ProviderDraftOrigin = 'new' | 'edit'

/** A provider the workflow uses before it is published: the wizard's state, and where it came from. */
export interface ProviderDraft extends WizardState {
  /** Its key in the store's `providerDrafts`, kept while the provider is named and renamed. */
  id: string
  origin: ProviderDraftOrigin
  /** An edit's published version, the one `version` (its next patch) follows; null for a new provider. */
  publishedVersion: string | null
  /**
   * The change request a new provider's draft opened on its own (the Provider sheet's "Publish
   * provider only"). The draft stays, read-only, until that change request merges: the workflow's
   * steps keep resolving it, and the workflow's Publish requires that change request instead of
   * opening a second one (one may be open per provider).
   */
  changeRequest?: string
  /** An Ansible repository's or a Terraform module's import as typed, kept until the import runs. */
  importForm?: ImportForm
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
    importForm: d.importForm && {
      ...d.importForm,
      repo: withoutUserinfo(d.importForm.repo),
      readCredential: vaultRef(d.importForm.readCredential),
      registryCredential: vaultRef(d.importForm.registryCredential),
      credential: vaultRef(d.importForm.credential),
    },
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
    changeRequest: typeof raw.changeRequest === 'string' && raw.changeRequest ? raw.changeRequest : undefined,
    importForm: isObject(raw.importForm) ? { ...blankImportForm(), ...raw.importForm } : undefined,
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
