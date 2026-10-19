/**
 * The Parameters tab of a step: a form per step type, generated from the registry where the
 * registry knows the shape (a provider action's input, a child workflow's input, a resource
 * type's attributes) and written by hand for the flow steps (switch cases, fork branches, the
 * loop, wait, set). Every field writes straight into the step body through the store.
 *
 * As n8n has it beside a credential, a provider step's provider field ends with "New provider…"
 * and has an "Edit provider" pencil, both opening the Provider sheet for this step; an HTTP step
 * can Import cURL (curl.ts) and be saved as a provider action (httpProvider.ts), which first lists
 * what of the request does not come along and asks.
 */
import { createContext, useContext, useEffect, useId, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { Button, ConfirmDialog, cx, Icon, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Segmented, Tag, Textarea, useOverlayClose, type SelectOption } from '@iaf/ui'
import { displayName, STEP_TYPES, type JsonObject, type JsonValue, type ParsedStep, type Problem, type StepBody, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import { AddCredentialModal } from '../../components/CredentialPicker'
import { api, errorMessage } from '../../lib/api'
import type { RegistryCredential, Schema } from '../../lib/types'
import { findStepLocation, safeParseSteps } from '../model/graph'
import { findProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { findAction, findProvider, knownGroups, playbookLabel, providerLabel, type Registry } from '../model/registry'
import { fieldsOf, type FieldType } from '../model/schema'
import { useDesignerSelector, useDesignerStore, type DesignerStore, type ProviderSheetOptions } from '../model/store'
import { DRAFT_TAG, effectTags, providersWithDrafts, type ListedProvider } from '../creator/catalog'
import { httpWithFromCurl, parseCurl, type CredentialKind, type CurlRequest } from './curl'
import { httpActionPlan, planProviderAction, saveAsProviderAction } from './httpProvider'
import { useNdv } from './context'
import { asObject, asString, checkAccessOn, checkAccessValue, isSettingsField, problemsAt, readField, rootOf, writeField } from './fields'
import { FieldLabel, KeyValueEditor, ParameterField, ParamSection, ProblemList, type ParamSpec } from './ParameterField'
import { hostsText, hostsValue, inventoryFor, inventoryKind, withGroupKey, type InventoryKind } from './inventory'

interface StepEdit {
  stepId: string
  body: StepBody
  step: ParsedStep
  registry: Registry | null
  doc: WorkflowDocument
  readOnly: boolean
  get: (field: string) => JsonValue | undefined
  set: (field: string, value: JsonValue | undefined) => void
  update: (fn: (body: StepBody) => void, coalesce?: string) => void
}

const StepEditContext = createContext<StepEdit | null>(null)

function useStepEdit(): StepEdit {
  const v = useContext(StepEditContext)
  if (!v) throw new Error('useStepEdit outside StepParameters')
  return v
}

type PProps = Omit<ParamSpec, 'type'> & { type?: FieldType; onChange?: (v: JsonValue | undefined) => void; value?: JsonValue | undefined }

/** A parameter of the open step, read from and written to its body. */
function P({ onChange, value, ...spec }: PProps) {
  const { get, set, readOnly } = useStepEdit()
  return <ParameterField spec={{ type: 'string', ...spec }} value={value !== undefined ? value : get(spec.field)} onChange={onChange ?? ((v) => set(spec.field, v))} readOnly={readOnly} />
}

/** A free-form object parameter as key/value rows. */
function KeyValueParam({ field, label, description, addLabel, keyPlaceholder, valuePlaceholder }: { field: string; label: string; description?: string; addLabel?: string; keyPlaceholder?: string; valuePlaceholder?: string }) {
  const { get, set, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const value = get(field)
  const own = problems.filter((p) => p.field === field)
  return (
    <div data-field={field}>
      <FieldLabel label={label} description={description} />
      <div className="mt-1">
        <KeyValueEditor
          value={asObject(value)}
          onChange={(v) => set(field, Object.keys(v).length === 0 && value === undefined ? undefined : v)}
          addLabel={addLabel}
          problemsOf={(k) => problemsAt(problems, `${field}.${k}`)}
          readOnly={readOnly}
          keyPlaceholder={keyPlaceholder}
          valuePlaceholder={valuePlaceholder}
        />
      </div>
      <ProblemList problems={own} className="mt-1" />
    </div>
  )
}

function optionLabel(schema: Schema, key: string): string {
  return typeof schema.title === 'string' ? schema.title : key
}

/** The fields of an input schema under `prefix` (provider input, child workflow input, attributes). */
function SchemaFields({ schema, prefix, emptyText }: { schema: Schema | null | undefined; prefix: string; emptyText: string }) {
  const { get } = useStepEdit()
  const fields = fieldsOf(schema)
  const current = asObject(get(prefix))
  if (fields.length === 0) {
    return <P field={prefix} label={optionLabel(schema ?? {}, 'Input')} type="object" description={emptyText} />
  }
  const known = new Set(fields.map((f) => f.key))
  const extra = Object.keys(current).filter((k) => !known.has(k))
  return (
    <>
      {fields.map((f) => {
        const hint = [f.description, f.resource ? `A ${f.resource.type} id` : null].filter(Boolean).join('. ')
        return (
          <P
            key={f.key}
            field={`${prefix}.${f.key}`}
            label={f.title}
            type={f.type}
            required={f.required}
            description={hint || undefined}
            options={f.enum?.map((e) => ({ value: e, label: e }))}
            placeholder={f.default !== undefined ? String(f.default) : undefined}
          />
        )
      })}
      {extra.map((k) => (
        <P key={k} field={`${prefix}.${k}`} label={k} type="any" description="Not a field of the schema" />
      ))}
    </>
  )
}

const VAULT_NOTE = 'A Vault reference: the secret itself never goes in the workflow file'

/** The credentials as `vault:` references, the kinds asked for first, then by name. */
export function credentialOptions(credentials: readonly RegistryCredential[], kinds?: readonly string[]): SelectOption[] {
  const rank = (type: string) => (kinds && !kinds.includes(type) ? 1 : 0)
  return [...credentials].sort((a, b) => rank(a.type) - rank(b.type) || a.name.localeCompare(b.name)).map((c) => ({ value: `vault:${c.name}`, label: c.name, hint: c.type }))
}

/** The value of the entry that starts a credential field's options, n8n's "Create new credential". */
export const NEW_CREDENTIAL = '__new-credential__'

/**
 * A credential field's options: "New credential…" first unless the step is read-only (first, as in
 * n8n's credential select, so a long list of stored credentials never pushes it below the fold),
 * then the credentials, then a reference the registry does not list.
 */
export function credentialFieldOptions(credentials: readonly RegistryCredential[], kinds: readonly string[] | undefined, current: string, canCreate: boolean): SelectOption[] {
  const options = credentialOptions(credentials, kinds)
  if (current && !options.some((o) => o.value === current)) options.push({ value: current, label: current, hint: 'not in the list' })
  return canCreate ? [{ value: NEW_CREDENTIAL, label: 'New credential…' }, ...options] : options
}

/**
 * A credential, as a `vault:` reference: one of the registry's, or a new one stored on the spot.
 * "New credential…" opens the Add a credential dialog on the kind the field asks for; once stored,
 * the credential joins the options and the field takes its reference. The value goes to the
 * credential store only, never to the step, the workflow file or the undo history.
 */
function CredentialParam({ field = 'credential', kinds, label = 'Credential', description = VAULT_NOTE }: { field?: string; kinds?: string[]; label?: string; description?: string }) {
  const store = useDesignerStore()
  const { registry, get, set, readOnly } = useStepEdit()
  const [adding, setAdding] = useState(false)
  const options = credentialFieldOptions(registry?.credentials ?? [], kinds, asString(get(field)), !readOnly)
  return (
    <>
      <P field={field} label={label} options={options} onChange={(v) => (v === NEW_CREDENTIAL ? setAdding(true) : set(field, v))} expression={false} mono description={description} />
      {adding && (
        <AddCredentialModal
          raised
          kinds={kinds}
          onClose={() => setAdding(false)}
          onCreated={(ref, name, type) => {
            store.addCredential({ name, type })
            set(field, ref)
          }}
        />
      )}
    </>
  )
}

const METHODS: SelectOption[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))
const CALL_TYPES = ['openapi', 'http', 'sdk']

/** The value of the entry that ends the provider field's options, as "New credential…" ends a credential field's. */
export const NEW_PROVIDER = '__new-provider__'

/** The provider field's options: the providers a step can call, the workflow's drafts marked, then "New provider…" unless the step is read-only. */
export function providerFieldOptions(providers: readonly ListedProvider[], canCreate: boolean): SelectOption[] {
  const options: SelectOption[] = providers.filter((p) => CALL_TYPES.includes(p.type)).map((p) => ({ value: p.name, label: p.title, hint: p.draft ? `${p.name} · draft` : p.unpublished ? `${p.name} · ${p.unpublished}` : p.name }))
  if (canCreate) options.push({ value: NEW_PROVIDER, label: 'New provider…' })
  return options
}

/** "New provider…": the Provider sheet on a new provider, whose chosen action then fills this step. */
export function newProviderOptions(stepId: string): ProviderSheetOptions {
  return { entry: 'step', target: { kind: 'step', step: stepId } }
}

/**
 * "Edit provider": the Provider sheet on the step's provider, for this step. A draft of the
 * workflow's is opened as it is; a published provider opens on a draft of its next patch (the
 * workflow's, or the one the sheet starts from it).
 */
export function editProviderOptions(drafts: Readonly<Record<string, ProviderDraft>>, stepId: string, provider: string): ProviderSheetOptions {
  const target = { kind: 'step', step: stepId } as const
  const draft = findProviderDraft(drafts, provider)
  if (draft?.origin === 'new') return { entry: 'step', draft: draft.id, provider: null, target }
  return { entry: 'step', provider, ...(draft ? { draft: draft.id } : {}), target }
}

function ProviderParams() {
  const store = useDesignerStore()
  const { stepId, get, update, registry, readOnly } = useStepEdit()
  const drafts = useDesignerSelector((s) => s.providerDrafts)
  const listed = useMemo(() => providersWithDrafts(registry, drafts), [registry, drafts])
  const providerName = asString(get('provider'))
  const actionId = asString(get('action'))
  const provider = listed.find((p) => p.name === providerName)
  const action = provider?.actions.find((a) => a.id === actionId)
  const setProvider = (v: JsonValue | undefined) =>
    update((b) => {
      writeField(b, 'provider', v ?? '')
      if (v !== providerName) writeField(b, 'action', '')
    })
  return (
    <>
      <div className="flex items-start gap-1.5">
        <div className="min-w-0 flex-1">
          <P field="provider" label="Provider" required options={providerFieldOptions(listed, !readOnly)} expression={false} onChange={(v) => (v === NEW_PROVIDER ? store.openProviderSheet(newProviderOptions(stepId)) : setProvider(v))} />
        </div>
        {providerName && !readOnly && (
          <button
            type="button"
            onClick={() => store.openProviderSheet(editProviderOptions(store.getState().providerDrafts, stepId, providerName))}
            aria-label="Edit provider"
            title={provider?.draft ? `Edit the draft of ${provider.title}` : `Edit ${provider?.title ?? providerName}: its next version, reviewed and published as a change request`}
            data-testid="edit-provider"
            className="mt-7 flex h-[38px] w-9 shrink-0 items-center justify-center rounded-md border border-stroke-light bg-surface text-secondary hover:bg-surface-secondary hover:text-primary"
          >
            <Icon name="edit" size={14} />
          </button>
        )}
      </div>
      <P field="action" label="Action" required options={(provider?.actions ?? []).map((a) => ({ value: a.id, label: a.title || a.id, hint: `${a.id} · ${a.effect}` }))} expression={false} />
      {(action || provider?.draft) && (
        <div className="flex flex-wrap items-center gap-1.5" data-testid="provider-tags">
          {provider?.draft && (
            <Tag size="sm" tone={DRAFT_TAG.tone} title="Not published yet: Publish opens its change request with the workflow's">
              {DRAFT_TAG.label}
            </Tag>
          )}
          {action &&
            effectTags(action).map((t) => (
              <Tag key={t.label} size="sm" tone={t.tone}>
                {t.label}
              </Tag>
            ))}
          {action && (
            <Tag size="sm" outline>
              retry {action.retry}
            </Tag>
          )}
        </div>
      )}
      <ParamSection title="Input">
        <SchemaFields schema={action?.input} prefix="input" emptyText="The action's fields as JSON; values may be expressions" />
      </ParamSection>
    </>
  )
}

/** What the last Import cURL left out, for the note at the credential field: names only, never a value. */
interface CurlImported {
  dropped: string[]
  warnings: string[]
  kind?: CredentialKind
  /** the credential the step had then: the note stays until another is picked */
  credential: string
}

const SIGNED_IN: Record<CredentialKind, string> = {
  bearer: 'a bearer token',
  basic: 'a user and password',
  header: 'a key or a cookie',
}

/** The note under the credential field after an import dropped what signed in. */
export function curlCredentialNote(imported: Pick<CurlImported, 'dropped' | 'kind'>): string {
  const how = imported.kind ? SIGNED_IN[imported.kind] : 'a secret'
  const what = imported.dropped.join(', ')
  return `The command signed in with ${how} (${what}); it was not imported and is not kept anywhere. Pick a credential, or create one, for the step to sign in with.`
}

/** n8n's "Import cURL command": the command is read in this dialog, and nothing that signs in leaves it. */
function ImportCurlForm({ onImport }: { onImport: (request: CurlRequest) => void }) {
  const close = useOverlayClose()
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const parsed = parseCurl(text)
    if (!parsed.ok) return setError(parsed.error)
    setText('')
    onImport(parsed.request)
    close()
  }
  return (
    <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
      <OverlayHeader title="Import cURL command" subtitle="Its method, URL, headers and body fill in the step." icon="terminal" />
      <OverlayBody className="space-y-3">
        <Textarea
          aria-label="cURL command"
          mono
          rows={8}
          value={text}
          spellCheck={false}
          autoFocus
          autoComplete="off"
          placeholder={`curl -X POST https://api.example.com/things \\\n  -H 'Content-Type: application/json' \\\n  -d '{"name": "web-01"}'`}
          onChange={(e) => {
            setText(e.target.value)
            setError(null)
          }}
        />
        <p className="text-caption text-tertiary">An Authorization header, a -u user and password, cookies and API keys are not imported: the step signs in with a credential instead.</p>
        {error && (
          <p role="alert" className="text-caption text-critical">
            {error}
          </p>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button type="button" variant="ghost" onClick={close}>
          Cancel
        </Button>
        <Button type="submit" variant="primary" icon="download" disabled={!text.trim()}>
          Import
        </Button>
      </OverlayFooter>
    </form>
  )
}

/** Import cURL: the step takes the request's method, URL, headers and body in one undo step, and keeps its credential. */
export function importCurl(update: StepEdit['update'], request: CurlRequest): void {
  update((b) => {
    b.with = httpWithFromCurl(request, asObject(b.with as JsonValue | undefined))
  })
}

/** The HTTP steps whose parameters open on the Import cURL dialog, once: the node creator's "HTTP request (cURL)". */
const importOnOpen = new Set<string>()

/** Opens the Import cURL dialog when the step's parameters show next (the step the node creator just added). */
export function importCurlOnOpen(stepId: string): void {
  importOnOpen.add(stepId)
}

/** Whether the step's parameters open on the Import cURL dialog. */
export function opensOnImportCurl(stepId: string): boolean {
  return importOnOpen.has(stepId)
}

/**
 * Save as provider action, as the HTTP step's button runs it: what does not come along (a header,
 * a GET's body, an endpoint that is not https, a draft that cannot take the request) is listed
 * first and the user confirms; with nothing to say it saves at once.
 */
export function startProviderActionSave(store: DesignerStore, stepId: string): { error: string } | { confirm: string[] } | { saved: true } {
  const planned = planProviderAction(store.getState(), stepId)
  if (!planned.ok) return { error: planned.error }
  if (planned.save.notes.length > 0) return { confirm: planned.save.notes }
  const saved = saveAsProviderAction(store, stepId)
  return saved.ok ? { saved: true } : { error: saved.error }
}

function HttpParams() {
  const store = useDesignerStore()
  const { stepId, body, get, update, readOnly } = useStepEdit()
  const [importing, setImporting] = useState(() => !readOnly && opensOnImportCurl(stepId))
  const [imported, setImported] = useState<CurlImported | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [leftOut, setLeftOut] = useState<string[] | null>(null)
  // asked once: the dialog does not come back the next time the step is opened
  useEffect(() => {
    importOnOpen.delete(stepId)
  }, [stepId])
  const credential = asString(get('credential'))
  const askCredential = imported !== null && imported.dropped.length > 0 && credential === imported.credential
  const plan = httpActionPlan(asObject(body.with as JsonValue | undefined))
  const onImport = (request: CurlRequest) => {
    importCurl(update, request)
    setImported({ dropped: request.dropped, warnings: request.warnings, kind: request.credentialKind, credential })
    setSaveError(null)
  }
  const save = () => {
    const started = startProviderActionSave(store, stepId)
    setSaveError('error' in started ? started.error : null)
    if ('confirm' in started) setLeftOut(started.confirm)
  }
  const saveAnyway = () => {
    const saved = saveAsProviderAction(store, stepId)
    if (!saved.ok) setSaveError(saved.error)
  }
  return (
    <>
      {!readOnly && (
        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button size="sm" variant="secondary" icon="terminal" onClick={() => setImporting(true)}>
            Import cURL
          </Button>
          <Button size="sm" variant="secondary" icon="provider" onClick={save} disabled={!plan.ok} title={plan.ok ? 'Make this request an action of a provider draft: it gets a review, samples, health and an SLO' : plan.error}>
            Save as provider action
          </Button>
        </div>
      )}
      {saveError && (
        <p role="alert" className="text-caption text-critical">
          {saveError}
        </p>
      )}
      {imported && imported.warnings.length > 0 && (
        <ul className="space-y-0.5 rounded-md bg-surface-secondary px-3 py-2 text-caption text-secondary" data-testid="curl-warnings">
          {imported.warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      )}
      <P field="method" label="Method" options={METHODS} expression={false} />
      <P field="url" label="URL" required placeholder="https://api.example.com/things/{{ $input.id }}" mono />
      <div className={cx(askCredential && '-mx-2 rounded-md border border-warning/40 bg-warning-tint/40 px-2 pb-2')} data-testid="http-credential" data-attention={askCredential ? 'true' : undefined}>
        <CredentialParam kinds={['token', 'basic', 'bearer']} />
        {askCredential && imported && (
          <p role="status" className="mt-1.5 flex items-start gap-1.5 text-caption text-warning" data-testid="curl-credential-note">
            <Icon name="key" size={12} className="mt-0.5 shrink-0" />
            <span>{curlCredentialNote(imported)}</span>
          </p>
        )}
      </div>
      <KeyValueParam field="headers" label="Headers" addLabel="Add header" keyPlaceholder="Header" />
      <P field="body" label="Body" type="any" description="JSON, or an expression" />
      {importing && (
        <Modal onClose={() => setImporting(false)} width="max-w-xl" label="Import cURL command" raised>
          <ImportCurlForm onImport={onImport} />
        </Modal>
      )}
      {leftOut && (
        <ConfirmDialog
          title="Save as provider action?"
          message="The step will call an action of an http provider draft, which is reviewed before it is published. Not all of this request comes along:"
          confirmLabel="Save as provider action"
          icon="provider"
          onConfirm={saveAnyway}
          onClose={() => setLeftOut(null)}
        >
          <ul className="list-disc space-y-1 pl-4 text-caption text-secondary" data-testid="provider-action-notes">
            {leftOut.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        </ConfirmDialog>
      )}
    </>
  )
}

type PlaybookSource = 'library' | 'git' | 'upload'

/** The first seven characters of a commit sha, as git shows it. */
export function shortSha(commit: string | null | undefined): string | null {
  if (typeof commit !== 'string') return null
  const c = commit.trim()
  return c ? c.slice(0, 7) : null
}

/**
 * What `GET /playbooks:resolve` gets for a git playbook: the repository, the ref and the read token
 * of the repository (`playbook.git.credential`). The step's own `credential` is the machine
 * credential the playbook reaches the hosts with and never goes to the git server. Null until the
 * repository and the ref are filled in.
 */
export function gitResolveQuery(playbook: JsonValue | undefined): { repo: string; ref: string; credential?: string } | null {
  const git = asObject(asObject(playbook).git)
  const repo = asString(git.repo).trim()
  const ref = asString(git.ref).trim()
  if (!repo || !ref) return null
  const credential = asString(git.credential).trim()
  return credential ? { repo, ref, credential } : { repo, ref }
}

/** The credential kinds that read a git repository, and the ones that reach a host over SSH. */
const GIT_KINDS = ['token', 'basic']
const MACHINE_KINDS = ['ssh-key', 'ssh']

/** Host names typed with commas, written when the field loses focus or on Enter (a half-typed list never reaches the step). */
function HostsInput({ field, label, description, value, onCommit, placeholder = 'web1, web2' }: { field: string; label: string; description?: string; value: string; onCommit: (text: string) => void; placeholder?: string }) {
  const { readOnly } = useStepEdit()
  const { problems } = useNdv()
  const id = useId()
  const [draft, setDraft] = useState<string | null>(null)
  const own = problemsAt(problems, field)
  const commit = () => {
    if (draft === null) return
    setDraft(null)
    if (draft !== value) onCommit(draft)
  }
  return (
    <div data-field={field}>
      <FieldLabel htmlFor={id} label={label} description={description} />
      <Input
        id={id}
        mono
        value={draft ?? value}
        placeholder={placeholder}
        readOnly={readOnly}
        invalid={own.some((p) => p.level === 'error')}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
        }}
        className="mt-1"
      />
      <ProblemList problems={own} className="mt-1" />
    </div>
  )
}

const INVENTORY_NOTE: Record<InventoryKind, string> = {
  dynamic: 'The dynamic inventory the platform is configured with (platform.runners.ansible.dynamic-inventory).',
  path: "An inventory file or directory inside the playbook's source.",
  hosts: 'The hosts the playbook runs on, by name.',
  group: 'Hosts that make up a group, with the variables of the group.',
  none: "No inventory: the playbook runs on ansible's implicit localhost.",
}

/**
 * The step's inventory in the runner's shapes (`./inventory`): dynamic, a path in the playbook's
 * source, a list of hosts, a group `{ hosts, group?, vars? }`, or none. Choosing a kind whose field
 * is still empty writes nothing until it is filled, so the step never holds an empty inventory.
 */
function InventoryParams() {
  const { get, set, update, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const value = get('inventory')
  const [pending, setPending] = useState<{ kind: InventoryKind; from: JsonValue | undefined } | null>(null)
  const kind = pending && pending.from === value ? pending.kind : inventoryKind(value)
  const group = kind === 'group' ? asObject(value) : {}
  const choose = (next: InventoryKind) => {
    if (readOnly || next === kind) return
    const write = inventoryFor(next, value)
    if (write === 'pending') return setPending({ kind: next, from: value })
    setPending(null)
    set('inventory', write.value)
  }
  const setGroup = (key: 'hosts' | 'group' | 'vars', v: JsonValue | undefined) => update((b) => writeField(b, 'inventory', withGroupKey(readField(b, 'inventory'), key, v)), `inventory.${key}`)
  return (
    <>
      <div data-field="inventory">
        <FieldLabel label="Inventory" description="Where the playbook finds its hosts: the platform's dynamic inventory, a path in the playbook's source, a list of hosts or a group of them" />
        <Segmented<InventoryKind>
          value={kind}
          onChange={choose}
          ariaLabel="Inventory"
          size="sm"
          options={[
            { value: 'dynamic', label: 'Dynamic' },
            { value: 'path', label: 'Path' },
            { value: 'hosts', label: 'Hosts' },
            { value: 'group', label: 'Group' },
            { value: 'none', label: 'None' },
          ]}
          className="mt-1"
        />
        <p className="mt-1 text-caption text-tertiary">{INVENTORY_NOTE[kind]}</p>
        <ProblemList problems={problems.filter((p) => p.field === 'inventory' && kind !== 'path' && kind !== 'hosts')} className="mt-1" />
      </div>
      {kind === 'path' && (
        <P
          field="inventory"
          label="Inventory path"
          mono
          value={inventoryKind(value) === 'path' ? value : ''}
          placeholder="inventories/prod.ini"
          description="A file or directory inside the playbook's source; an expression may also give dynamic or a list of hosts"
        />
      )}
      {kind === 'hosts' && (
        <HostsInput field="inventory" label="Hosts" description="Host names separated by commas; each may be an expression" value={Array.isArray(value) ? hostsText(value) : ''} onCommit={(t) => set('inventory', hostsValue(t, value))} />
      )}
      {kind === 'group' && (
        <>
          <div className="grid grid-cols-2 gap-3">
            <HostsInput field="inventory.hosts" label="Hosts" description="Host names separated by commas" value={hostsText(group.hosts)} onCommit={(t) => setGroup('hosts', hostsValue(t, group.hosts, true))} />
            <P field="inventory.group" label="Group" mono expression={false} placeholder="all" value={group.group ?? ''} onChange={(v) => setGroup('group', v)} description="The group the hosts make up (letters, digits and _); all when empty" />
          </div>
          <div data-field="inventory.vars">
            <FieldLabel label="Group variables" description="Variables every host of the group gets" />
            <div className="mt-1">
              <KeyValueEditor
                value={asObject(group.vars)}
                onChange={(v) => setGroup('vars', Object.keys(v).length > 0 ? v : undefined)}
                addLabel="Add variable"
                keyPlaceholder="variable"
                problemsOf={(k) => problemsAt(problems, `inventory.vars.${k}`)}
                readOnly={readOnly}
              />
            </div>
          </div>
        </>
      )}
    </>
  )
}

/** The limit: a host pattern, or the list of hosts a file wrote (kept a list when edited). */
function LimitParam() {
  const { get, set } = useStepEdit()
  const value = get('limit')
  if (Array.isArray(value)) {
    return <HostsInput field="limit" label="Limit" description="The hosts of the inventory the run is limited to, as a list" value={hostsText(value)} onCommit={(t) => set('limit', hostsValue(t, value))} />
  }
  return <P field="limit" label="Limit" mono placeholder="web1,web2 or db*" description="A host pattern (web1,web2, db*, all:!db3) or a list of hosts: the hosts of the inventory the run is limited to" />
}

function AnsibleParams() {
  const { get, set, registry, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const pb = get('playbook')
  const obj = pb !== null && typeof pb === 'object' && !Array.isArray(pb) ? pb : null
  const source: PlaybookSource = typeof pb === 'string' ? 'library' : obj ? (obj.git !== undefined ? 'git' : obj.upload !== undefined ? 'upload' : obj.library !== undefined ? 'library' : 'git') : 'git'
  const playbooks = registry?.playbooks ?? []
  const libraryField = typeof pb === 'string' || !obj ? 'playbook' : 'playbook.library'
  const upload = obj && obj.upload && typeof obj.upload === 'object' && !Array.isArray(obj.upload) ? obj.upload : null
  const pinned = shortSha(asString(get('playbook.commit')))
  const [pinning, setPinning] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [note, setNote] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const setSource = (s: PlaybookSource) => {
    if (s === source) return
    setNote(null)
    if (s === 'library') set('playbook', playbooks[0]?.name ?? '')
    else if (s === 'git') set('playbook', { git: { repo: '', ref: 'main', path: '' }, commit: '' })
    else set('playbook', { upload: { file: '', sha256: '' } })
  }
  // GET /playbooks:resolve: the commit the ref names right now, written into playbook.commit
  const pin = async () => {
    const query = gitResolveQuery(pb)
    if (!query) {
      setNote({ tone: 'error', text: 'Fill in the repository and the ref first.' })
      return
    }
    setPinning(true)
    setNote(null)
    try {
      // the git credential as it stands: the backend wants the vault:<path> reference itself
      const resolved = await api.resolvePlaybook(query)
      set('playbook.commit', resolved.commit)
      setNote({ tone: 'ok', text: `Pinned to ${shortSha(resolved.commit) ?? resolved.commit} (${resolved.ref} of ${resolved.repo} right now).` })
    } catch (e) {
      setNote({ tone: 'error', text: errorMessage(e) })
    } finally {
      setPinning(false)
    }
  }
  // POST /playbooks:upload: the stored archive's reference goes into playbook.upload
  const uploadArchive = async (file: File | undefined) => {
    if (!file) return
    setUploading(true)
    setNote(null)
    try {
      const stored = await api.uploadPlaybook(file, asString(upload?.path) || undefined)
      set('playbook', { ...(obj ?? {}), upload: { ...(upload ?? {}), file: stored.file, sha256: stored.sha256 } })
      const n = stored.entries?.length
      setNote({ tone: 'ok', text: `Uploaded ${file.name} (${Math.max(1, Math.round(stored.size / 1024))} KB${n ? `, ${n} ${n === 1 ? 'file' : 'files'}` : ''}); the step now refers to ${stored.file}.` })
    } catch (e) {
      setNote({ tone: 'error', text: errorMessage(e) })
    } finally {
      setUploading(false)
    }
  }
  return (
    <>
      <ParamSection
        title="Playbook"
        actions={
          pinned && (
            <span data-testid="playbook-pinned">
              <Tag size="sm" tone="healthy" mono title={`Pinned to commit ${asString(get('playbook.commit')) ?? ''}`}>
                pinned {pinned}
              </Tag>
            </span>
          )
        }
      >
        <div>
          <FieldLabel label="Source" required description="A playbook of the library, a path in a git repository, or an archive uploaded to the platform" />
          <Segmented<PlaybookSource>
            value={source}
            onChange={setSource}
            ariaLabel="Playbook source"
            size="sm"
            options={[
              { value: 'library', label: 'Library' },
              { value: 'git', label: 'Git repository' },
              { value: 'upload', label: 'Upload' },
            ]}
            className="mt-1"
          />
          <ProblemList problems={problems.filter((p) => p.field === 'playbook' && source !== 'library')} className="mt-1" />
        </div>
        {source === 'library' && (
          <P
            field={libraryField}
            label="Playbook"
            required
            expression={false}
            mono
            problemsExact={['playbook']}
            options={playbooks.map((x) => ({ value: x.name, label: playbookLabel(x), hint: [x.version, x.owner].filter(Boolean).join(' · ') }))}
            description="A playbook of the library: <provider>/<playbook>"
          />
        )}
        {source === 'git' && (
          <>
            <P field="playbook.git.repo" label="Repository" required mono placeholder="github.com/acme/ops-playbooks" expression={false} />
            <div className="grid grid-cols-2 gap-3">
              <P field="playbook.git.ref" label="Ref" required mono placeholder="v1.0.0" expression={false} />
              <P field="playbook.git.path" label="Path" required mono placeholder="vm/restart.yml" expression={false} />
            </div>
            <CredentialParam
              field="playbook.git.credential"
              kinds={GIT_KINDS}
              label="Git credential"
              description="The read token of a private repository, as a Vault reference: runs clone with it and the pin looks the ref up with it. Leave it empty for a public repository"
            />
            <P field="playbook.commit" label="Commit" mono placeholder="the commit every run uses" expression={false} description="Pin the playbook to a commit so every run uses the same code" />
            {!readOnly && (
              <div className="flex flex-wrap items-center gap-2">
                <Button size="sm" variant="secondary" icon="git-branch" loading={pinning} onClick={() => void pin()} data-testid="pin-playbook">
                  Pin to latest commit
                </Button>
                <span className="text-caption text-tertiary">{pinned ? 'Replaces the pinned commit with what the ref names now' : 'Looks the ref up and writes its commit into the step'}</span>
              </div>
            )}
          </>
        )}
        {source === 'upload' && (
          <>
            <P field="playbook.upload.file" label="File" required mono expression={false} description="The stored archive, as the upload answered it" />
            <P field="playbook.upload.sha256" label="SHA-256" required mono expression={false} />
            <P field="playbook.upload.path" label="Playbook path" mono placeholder="site.yml" expression={false} description="The playbook inside the archive, when it is not the only one" />
            {!readOnly && (
              <label className={cx('inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-stroke-light bg-surface px-2.5 py-1.5 text-caption font-medium text-primary hover:bg-surface-secondary', uploading && 'pointer-events-none opacity-60')}>
                <Icon name="upload" size={13} />
                {uploading ? 'Uploading…' : 'Upload playbook archive'}
                <input type="file" accept=".tar.gz,.tgz,.zip,.yml,.yaml,application/gzip,application/zip" className="sr-only" data-testid="playbook-archive" onChange={(e) => void uploadArchive(e.target.files?.[0])} />
              </label>
            )}
          </>
        )}
        {note && (
          <p role={note.tone === 'error' ? 'alert' : 'status'} className={cx('text-caption', note.tone === 'error' ? 'text-critical' : 'text-healthy')} data-testid="playbook-note">
            {note.text}
          </p>
        )}
      </ParamSection>
      <ParamSection title="Hosts">
        <InventoryParams />
        <LimitParam />
      </ParamSection>
      <KeyValueParam field="extraVars" label="Extra vars" addLabel="Add variable" keyPlaceholder="variable" />
      <CredentialParam kinds={MACHINE_KINDS} label="Machine credential" description={`The SSH key the playbook reaches the hosts with. ${VAULT_NOTE}`} />
      <P field="executionEnvironment" label="Execution environment" mono placeholder="ee-platform:2.17" expression={false} />
      <P field="checkModeOnTest" label="Check mode on the test environment" type="boolean" expression={false} />
    </>
  )
}

function TerraformParams() {
  return (
    <>
      <P field="module.source" label="Module source" required mono problemsExact={['module']} placeholder="git::https://github.com/acme/tf-modules.git//vm" expression={false} />
      <P field="module.version" label="Module version" mono placeholder="1.4.0" expression={false} description="Pin the module version so every run uses the same code" />
      <P field="action" label="Action" required options={['plan', 'apply', 'destroy'].map((a) => ({ value: a, label: a }))} expression={false} />
      <KeyValueParam field="vars" label="Variables" addLabel="Add variable" keyPlaceholder="variable" />
      <CredentialParam kinds={['token', 'cloud']} />
    </>
  )
}

function IdpParams() {
  const { get, registry } = useStepEdit()
  const tiles = (registry?.providers ?? []).filter((p) => p.type === 'idp').flatMap((p) => p.actions.map((a) => ({ provider: p, action: a })))
  const tile = asString(get('tile'))
  const current = tiles.find((t) => t.action.id === tile)
  return (
    <>
      <P field="tile" label="Tile" required expression={false} options={tiles.map((t) => ({ value: t.action.id, label: t.action.title || t.action.id, hint: providerLabel(t.provider) }))} />
      <ParamSection title="Input">
        <SchemaFields schema={current?.action.input} prefix="input" emptyText="The tile's fields as JSON" />
      </ParamSection>
    </>
  )
}

function ActivityParams() {
  return (
    <>
      <P field="taskQueue" label="Task queue" required mono expression={false} />
      <P field="activity" label="Activity" required mono expression={false} />
      <P field="input" label="Input" type="object" />
    </>
  )
}

function RunParams() {
  const { get, registry, doc } = useStepEdit()
  const workflows = (registry?.workflows ?? []).filter((w) => w.name !== doc.metadata?.name)
  const name = asString(get('workflow'))
  const wf = workflows.find((w) => w.name === name)
  return (
    <>
      <P field="workflow" label="Workflow" required expression={false} options={workflows.map((w) => ({ value: w.name, label: w.title, hint: `${w.name} ${w.version}` }))} />
      <P field="version" label="Version" mono placeholder="^1" expression={false} description="A range like ^1, ~1.2 or 1.2.0" />
      <ParamSection title="Input">
        <SchemaFields schema={wf?.input} prefix="input" emptyText="The child job's input as JSON" />
      </ParamSection>
    </>
  )
}

function ApprovalParams() {
  const { doc } = useStepEdit()
  const groups = knownGroups(doc)
  return (
    <>
      <P field="group" label="Approver group" required suggestions={groups} expression={false} />
      <P field="message" label="Message" multiline placeholder="Restart {{ $vm.name }}?" description="What the approver decides on" />
      <P field="timeout" label="Timeout" mono placeholder="4h" expression={false} />
      <div className="grid grid-cols-2 gap-3">
        <P field="escalation.after" label="Escalate after" mono placeholder="2h" expression={false} />
        <P field="escalation.to" label="Escalate to" placeholder="group or person" expression={false} />
      </div>
    </>
  )
}

function laterSteps(doc: WorkflowDocument, stepId: string): { id: string; name: string }[] {
  const loc = findStepLocation(doc, stepId)
  if (!loc) return []
  return safeParseSteps(loc.list.slice(loc.index + 1)).map((s) => ({ id: s.id, name: displayName(s) }))
}

function SwitchParams() {
  const store = useDesignerStore()
  const { stepId, body, doc, update, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const cases = Array.isArray(body.switch) ? body.switch : []
  const later = laterSteps(doc, stepId)
  const hasDefault = cases.some((c) => c.default !== undefined)
  const routeOf = (i: number): { value: string; steps: number } => {
    const c = cases[i]
    if (c.default !== undefined) {
      const d = c.default
      if (typeof d === 'string') return { value: d, steps: 0 }
      return d && Array.isArray(d.do) ? { value: '__do', steps: d.do.length } : { value: d?.then ?? '', steps: 0 }
    }
    return Array.isArray(c.case?.do) ? { value: '__do', steps: c.case.do.length } : { value: c.case?.then ?? '', steps: 0 }
  }
  const setRoute = (i: number, target: string) =>
    update((b) => {
      const entry = Array.isArray(b.switch) ? b.switch[i] : undefined
      if (!entry) return
      if (entry.default !== undefined) entry.default = target === '__do' ? { do: [] } : target
      else {
        entry.case = entry.case ?? {}
        if (target === '__do') {
          delete entry.case.then
          entry.case.do = []
        } else {
          entry.case.then = target
          delete entry.case.do
        }
      }
    })
  const routeOptions: SelectOption[] = [{ value: '__do', label: 'Its own branch on the canvas' }, ...later.map((s) => ({ value: s.id, label: `Jump to ${s.name}`, hint: s.id })), { value: 'end', label: 'End the job' }]
  return (
    <>
      <p className="text-caption text-tertiary">Cases are tried in order; the first whose condition holds runs. Without a default, the job carries on after the switch.</p>
      <ol className="space-y-3">
        {cases.map((c, i) => {
          const route = routeOf(i)
          const isDefault = c.default !== undefined
          return (
            <li key={i} className="rounded-md border border-stroke-light bg-surface-tertiary p-3" data-field={`switch[${i}]`}>
              <div className="mb-2 flex items-center gap-2">
                <span className="text-caption font-semibold text-primary">{isDefault ? 'Default' : `Case ${i + 1}`}</span>
                <span className="flex-1" />
                {!readOnly && (
                  <button type="button" onClick={() => store.removeSwitchCase(stepId, i)} aria-label={`Remove ${isDefault ? 'the default' : `case ${i + 1}`}`} title="Remove" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                    <Icon name="trash" size={13} />
                  </button>
                )}
              </div>
              <div className="space-y-3">
                {!isDefault && <P field={`switch[${i}].when`} label="When" required placeholder="{{ $input.size == 'large' }}" />}
                {route.steps > 0 ? (
                  <p className="text-caption text-secondary">
                    Runs {route.steps} {route.steps === 1 ? 'step' : 'steps'} of its own on the canvas, then carries on after the switch.
                  </p>
                ) : (
                  <ParameterField spec={{ field: `switch[${i}].then`, label: 'Then', type: 'enum', options: routeOptions, required: true, expression: false }} value={route.value || undefined} onChange={(v) => setRoute(i, typeof v === 'string' ? v : '__do')} readOnly={readOnly} />
                )}
                <ProblemList problems={problems.filter((p) => p.field === `switch[${i}]`)} />
              </div>
            </li>
          )
        })}
      </ol>
      {!readOnly && (
        <div className="flex gap-2">
          <Button size="sm" variant="secondary" icon="plus" onClick={() => store.addSwitchCase(stepId)}>
            Add case
          </Button>
          {!hasDefault && (
            <Button size="sm" variant="ghost" icon="plus" onClick={() => store.addSwitchCase(stepId, true)}>
              Add default
            </Button>
          )}
        </div>
      )}
    </>
  )
}

function ForkParams() {
  const store = useDesignerStore()
  const { stepId, step, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const branches = step.branches ?? []
  return (
    <>
      <p className="text-caption text-tertiary">Every branch runs at the same time; the job carries on when all of them are done. Add steps to a branch from its output on the canvas.</p>
      <ol className="space-y-2">
        {branches.map((b, i) => (
          <li key={i} className="flex items-center gap-3 rounded-md border border-stroke-light bg-surface-tertiary px-3 py-2">
            <span className="text-caption font-semibold text-primary">Branch {i + 1}</span>
            <span className="min-w-0 flex-1 truncate text-caption text-tertiary">{b.length === 0 ? 'No steps yet' : b.map((s) => displayName(s)).join(' → ')}</span>
            {!readOnly && branches.length > 1 && (
              <button type="button" onClick={() => store.removeForkBranch(stepId, i)} aria-label={`Remove branch ${i + 1}`} title="Remove the branch and its steps" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                <Icon name="trash" size={13} />
              </button>
            )}
          </li>
        ))}
      </ol>
      <ProblemList problems={problems.filter((p) => p.field?.startsWith('branches'))} />
      {!readOnly && (
        <Button size="sm" variant="secondary" icon="plus" onClick={() => store.addForkBranch(stepId)}>
          Add branch
        </Button>
      )}
    </>
  )
}

function ForParams() {
  return (
    <>
      <P field="in" label="Loop over" required placeholder="{{ $json.items }}" description="An expression that gives a list" />
      <P field="each" label="Each item as" required mono placeholder="item" expression={false} description="The loop variable: $item inside the loop" />
      <p className="text-caption text-tertiary">The loop body hangs off the loop output on the canvas; “done” carries on after the last item.</p>
    </>
  )
}

function TryParams() {
  return (
    <>
      <p className="text-caption text-tertiary">The steps on the “try” output run first; when one fails, the steps on the “catch” output run with the error.</p>
      <P field="catch.as" label="Error as" mono placeholder="error" expression={false} description="The catch steps read the error as $error, or this alias" />
    </>
  )
}

function WaitParams() {
  const { get, update, readOnly } = useStepEdit()
  const mode: 'duration' | 'until' = get('until') !== undefined ? 'until' : 'duration'
  return (
    <>
      <div>
        <FieldLabel label="Wait" />
        <Segmented<'duration' | 'until'>
          value={mode}
          ariaLabel="Wait for"
          size="sm"
          className="mt-1"
          onChange={(m) => {
            if (readOnly || m === mode) return
            update((b) => {
              b.wait = m === 'until' ? { until: '' } : { duration: 'PT5M' }
            })
          }}
          options={[
            { value: 'duration', label: 'A while' },
            { value: 'until', label: 'Until a condition' },
          ]}
        />
      </div>
      {mode === 'duration' ? <P field="duration" label="Duration" mono placeholder="PT5M, 30m or 2h" /> : <P field="until" label="Until" required placeholder="{{ $json.status == 'ready' }}" />}
    </>
  )
}

function SetParams() {
  const { body, update, readOnly } = useStepEdit()
  const { problems } = useNdv()
  const vars = asObject(body.set as JsonValue | undefined)
  return (
    <>
      <p className="text-caption text-tertiary">Later steps read each variable as $vars.name.</p>
      <KeyValueEditor
        value={vars}
        onChange={(v) =>
          update((b) => {
            b.set = v
          }, 'set')
        }
        addLabel="Add variable"
        keyPlaceholder="variable"
        problemsOf={(k) => problemsAt(problems, k)}
        readOnly={readOnly}
      />
    </>
  )
}

function RaiseParams() {
  return (
    <>
      <P field="error" label="Error" required mono placeholder="UpgradeFailed" expression={false} />
      <P field="message" label="Message" placeholder="{{ $json.reason }}" />
    </>
  )
}

function ResourceTypeParam() {
  const { registry } = useStepEdit()
  return <P field="type" label="Resource type" required expression={false} options={(registry?.resourceTypes ?? []).map((t) => ({ value: t.name, label: t.name, hint: t.owner }))} />
}

function UseParams() {
  const { get, set } = useStepEdit()
  return (
    <>
      <ResourceTypeParam />
      <P field="id" label="Id" placeholder="{{ $input.vmId }}" />
      <KeyValueParam field="selector" label="Selector" description="Attributes to match when there is no id" addLabel="Add attribute" keyPlaceholder="attribute" />
      <div className="grid grid-cols-2 gap-3">
        <P field="waitUntil" label="Wait until" options={[{ value: 'ready', label: 'ready' }]} expression={false} />
        {/* on by default: switching it off writes `checkAccess: false`, back on removes the key */}
        <P
          field="checkAccess"
          label="Check access"
          type="boolean"
          expression={false}
          description="Ask policy whether the requester may use it; on unless the step opts out"
          value={checkAccessOn(get('checkAccess'))}
          onChange={(on) => set('checkAccess', checkAccessValue(on))}
        />
      </div>
    </>
  )
}

function RegisterParams() {
  const { get, registry } = useStepEdit()
  const type = asString(get('type'))
  const rt = registry?.resourceTypes.find((t) => t.name === type)
  return (
    <>
      <ResourceTypeParam />
      <P field="id" label="Id" placeholder="minted by the registry when empty" />
      <P field="state" label="State" expression={false} options={(rt?.states ?? []).map((s) => ({ value: s, label: s }))} />
      <ParamSection title="Attributes">
        <SchemaFields schema={rt?.schema} prefix="attributes" emptyText="The resource's attributes as JSON" />
      </ParamSection>
      <P field="builtFrom" label="Built from" type="array" description="Ids of the resources this one was built from" />
    </>
  )
}

function NotifyParams() {
  return (
    <>
      <P field="channel" label="Channel" required placeholder="#ops" />
      <P field="message" label="Message" required multiline placeholder="Restarted {{ $vm.name }}" />
    </>
  )
}

function ScriptParams() {
  return (
    <>
      <P field="language" label="Language" options={[{ value: 'javascript', label: 'JavaScript' }]} expression={false} />
      <P field="code" label="Code" required multiline mono expression={false} description="The function body; input is in scope and the return value is the output" />
      <KeyValueParam field="input" label="Input" description="What the script sees as input" addLabel="Add field" />
    </>
  )
}

const EDITORS: Record<StepType, () => ReactNode> = {
  provider: ProviderParams,
  http: HttpParams,
  ansible: AnsibleParams,
  terraform: TerraformParams,
  idp: IdpParams,
  activity: ActivityParams,
  run: RunParams,
  approval: ApprovalParams,
  switch: SwitchParams,
  fork: ForkParams,
  for: ForParams,
  try: TryParams,
  wait: WaitParams,
  set: SetParams,
  raise: RaiseParams,
  use: UseParams,
  register: RegisterParams,
  notify: NotifyParams,
  script: ScriptParams,
}

/** The top-level keys each editor shows; problems elsewhere are listed above the form. */
const HANDLED: Record<StepType, readonly string[]> = {
  provider: ['provider', 'action', 'input'],
  http: ['method', 'url', 'headers', 'body', 'credential'],
  ansible: ['playbook', 'inventory', 'limit', 'extraVars', 'credential', 'executionEnvironment', 'checkModeOnTest'],
  terraform: ['module', 'action', 'vars', 'credential'],
  idp: ['tile', 'input'],
  activity: ['taskQueue', 'activity', 'input'],
  run: ['workflow', 'version', 'input'],
  approval: ['group', 'message', 'timeout', 'escalation'],
  switch: ['switch'],
  fork: ['branches'],
  for: ['each', 'in'],
  try: ['catch'],
  wait: ['duration', 'until'],
  set: [],
  raise: ['error', 'message'],
  use: ['type', 'id', 'selector', 'waitUntil', 'checkAccess'],
  register: ['type', 'id', 'state', 'attributes', 'builtFrom'],
  notify: ['channel', 'message'],
  script: ['language', 'code', 'input'],
}

/** The step problems the Parameters form does not show under a field. */
export function looseProblems(step: ParsedStep, body: StepBody, problems: readonly Problem[]): Problem[] {
  const handled = step.type === 'set' ? Object.keys(asObject(body.set as JsonValue | undefined)) : HANDLED[step.type]
  return problems.filter((p) => !p.field || (!isSettingsField(p.field) && !handled.includes(rootOf(p.field))))
}

export function StepParameters({ stepId, body, step, registry, doc, readOnly = false }: { stepId: string; body: StepBody; step: ParsedStep; registry: Registry | null; doc: WorkflowDocument; readOnly?: boolean }) {
  const store = useDesignerStore()
  const value = useMemo<StepEdit>(
    () => ({
      stepId,
      body,
      step,
      registry,
      doc,
      readOnly,
      get: (field) => readField(body, field),
      set: (field, v) => store.updateStep(stepId, (b) => writeField(b, field, v), `param:${stepId}:${field}`),
      update: (fn, coalesce) => store.updateStep(stepId, fn, coalesce ? `param:${stepId}:${coalesce}` : undefined),
    }),
    [stepId, body, step, registry, doc, readOnly, store],
  )
  const Editor = EDITORS[step.type]
  return (
    <StepEditContext.Provider value={value}>
      <div className="space-y-4">
        <Editor />
      </div>
    </StepEditContext.Provider>
  )
}

export function stepKindLabel(step: ParsedStep, registry: Registry | null): string {
  const w = asObject(step.with as JsonValue | undefined) as JsonObject
  if (step.type === 'provider') {
    const p = findProvider(registry, asString(w.provider))
    const a = findAction(registry, asString(w.provider), asString(w.action))
    return [p ? providerLabel(p) : asString(w.provider), a?.title ?? asString(w.action)].filter(Boolean).join(' · ') || STEP_TYPES.provider.title
  }
  return STEP_TYPES[step.type].title
}
