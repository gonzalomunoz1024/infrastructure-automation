/**
 * The Provider sheet: the Add provider wizard's steps in a drawer over the canvas, so a system can
 * be connected, its actions described, tried, reviewed and published without leaving the
 * designer. The steps are the wizard's own (components/provider-editor/ and the imports of
 * pages/ProviderImport.tsx), not copies; the sheet only lays them out the way n8n's credential
 * modal does (a rail of sections beside the form) and decides what happens on close.
 *
 * It edits one provider draft (designer/model/providerDrafts.ts): a new provider's, started from
 * the entry it was opened from (or the one left unfinished from such an entry), or the next patch
 * of a published one (`draftFromProvider`). The first change saves the draft and every later one
 * replaces it (`upsertProviderDraft`), so closing and reopening loses nothing. The action picked
 * to use becomes a step where the sheet was opened from, inserted or filling that step
 * (`closeProviderSheet`), as n8n adds a node after creating a credential: on "Use this action", or
 * on closing once one is picked.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Card, ConfirmDialog, cx, Drawer, EmptyState, Icon, IconButton, Segmented, Select, Spinner, Tag, useOverlayClose, type IconName } from '@iaf/ui'
import type { StepBody, WorkflowDocument } from '@iaf/workflow-checks'
import { pathFor } from '../../app/router'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { ProviderType } from '../../lib/types'
import { ErrorState } from '../../components/common'
import { NAME_RE, STEPS, STEP_NUMBER, TYPE_LABEL, describeProvider, draftNames, fileSpec, providerYaml, stepIssues, typeChange, yamlActions, type WizardState } from '../../components/provider-editor/state'
import { TypeStep } from '../../components/provider-editor/TypeStep'
import { ConnectStep } from '../../components/provider-editor/ConnectStep'
import { ActionsStep } from '../../components/provider-editor/ActionsStep'
import { TryStep } from '../../components/provider-editor/TryStep'
import { ReviewStep } from '../../components/provider-editor/ReviewStep'
import { DraftModal } from '../../components/provider-editor/DraftModal'
import { ImportStep } from '../../pages/ProviderImport'
import { NodeGlyph, providerGlyph } from '../canvas/glyphs'
import { bodyFor } from '../model/edits'
import { allStepLocations, findStepLocation } from '../model/graph'
import { draftFromProvider, newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { useDesignerSelector, useDesignerStore, type DesignerStore, type ProviderSheetEntry, type ProviderSheetState, type ProviderSheetStep, type ProviderSheetTarget } from '../model/store'

/* ----------------------------------------------------------------------------
   What the sheet decides, apart from the screen (exported for its tests)
   ------------------------------------------------------------------------- */

/** The sections of the sheet: the wizard's steps after Type, whose fields Connect shows. */
export type SheetSection = 'connect' | 'actions' | 'try' | 'review'

const ENTRY_TYPE: Partial<Record<ProviderSheetEntry, ProviderType>> = { openapi: 'openapi', curl: 'http', ansible: 'ansible', terraform: 'terraform', idp: 'idp', sdk: 'sdk' }

/** The entries that connect a new system: the node creator's (each provider type and Describe it) and a step's "New provider…". */
const NEW_SYSTEM: ReadonlySet<ProviderSheetEntry> = new Set<ProviderSheetEntry>(['openapi', 'curl', 'ansible', 'terraform', 'idp', 'sdk', 'describe', 'step'])

/** The provider type an entry starts a new provider as: cURL is an http provider; Describe it and the rest start where the wizard does. */
export function entryType(entry: ProviderSheetEntry): ProviderType {
  return ENTRY_TYPE[entry] ?? 'openapi'
}

/** A new provider's draft for the entry it was opened from, owned by the workflow's team until changed. */
export function startDraft(entry: ProviderSheetEntry, owner: string): ProviderDraft {
  return newProviderDraft(entryType(entry), owner ? { owner } : {})
}

/** The form's selected actions, by id. */
function formActions(d: WizardState): SheetAction[] {
  return d.actions.filter((a) => a.selected && a.id.trim()).map((a) => ({ id: a.id.trim(), title: a.title.trim(), effect: a.effect }))
}

/**
 * Whether a new provider's actions are those of a file it took (Devin's, an import's) and not the
 * form's: the form's rows then describe nothing in the file, so Actions is not shown and Try it is
 * done from the step. A file that is the form's own document with a try's answer in it (Try it's
 * "Use this") has the form's actions, and keeps them.
 */
export function takesFileActions(d: WizardState): boolean {
  if (d.editing || d.draftYaml === null) return false
  const form = formActions(d).map((a) => a.id)
  const file = yamlActions(d.draftYaml).map((a) => a.id)
  return form.length === 0 || file.length !== form.length || file.some((id) => !form.includes(id))
}

/** What the sheet shows of a draft: an edit keeps its file, so it collects evidence (Connect, Try it) and is reviewed, as the wizard's Edit provider; so does a file taken instead of the form. */
export function sectionsOf(d: WizardState): SheetSection[] {
  return d.editing || takesFileActions(d) ? ['connect', 'try', 'review'] : ['connect', 'actions', 'try', 'review']
}

/** Where the sheet opens: on the review from Publish (to fix what blocks it), else on Connect. */
export function firstSection(entry: ProviderSheetEntry): SheetSection {
  return entry === 'publish' ? 'review' : 'connect'
}

/**
 * What a section still needs, as the wizard's steps judge it; Connect holds the Type step's
 * fields too. Nothing once the draft took a file instead of the wizard's document (Devin's, an
 * import's), whose checks run on the review.
 */
export function sectionIssues(d: WizardState, section: SheetSection): string[] {
  if (d.draftYaml !== null && !d.editing) return []
  if (section === 'connect') return d.editing ? stepIssues(d, STEP_NUMBER.connect) : [...stepIssues(d, STEP_NUMBER.type), ...stepIssues(d, STEP_NUMBER.connect)]
  if (section === 'actions') return stepIssues(d, STEP_NUMBER.actions)
  return []
}

/** An action the sheet offers to use as a step. */
export interface SheetAction {
  id: string
  title: string
  effect: string
}

/** The actions the draft offers: those of its file when the file is what it calls (an edit's, Devin's, an import's), else the wizard's selected actions. */
export function sheetActions(d: WizardState): SheetAction[] {
  const fromFile = d.draftYaml !== null && (d.editing || takesFileActions(d)) ? yamlActions(d.draftYaml) : []
  return fromFile.length > 0 ? fromFile : formActions(d)
}

/**
 * The step an action of the draft becomes, as the node creator inserts a published provider's
 * (catalog.ts). The kind and a terraform module are the file's when the draft holds one (an
 * edit's, Devin's, an import's), which may be of another type than the entry it started from.
 */
export function actionStep(d: WizardState, a: SheetAction): ProviderSheetStep {
  const name = a.title || a.id
  const spec = d.draftYaml !== null ? fileSpec(d.draftYaml) : {}
  switch (spec.type ?? d.type) {
    case 'terraform': {
      const terraformAction = a.id === 'apply' || a.id === 'destroy' ? a.id : 'plan'
      const module = spec.module ?? { source: d.terraform.source.trim(), version: d.terraform.version.trim() }
      return { type: 'terraform', params: { name: `${d.title.trim() || d.name}: ${name}`, module, terraformAction } }
    }
    case 'ansible':
      return { type: 'ansible', params: { playbook: `${d.name}/${a.id}`, name } }
    case 'idp':
      return { type: 'idp', params: { tile: a.id, name } }
    default:
      return { type: 'provider', params: { provider: d.name, action: a.id, name } }
  }
}

/**
 * What a draft takes from a file Devin drafted: the file as its document, its names, and the type
 * and terraform module it declares, so the header, the entries and the step each action becomes
 * follow the file and not the entry the sheet was opened from.
 */
export function fileDraftPatch(d: WizardState, yaml: string): Partial<WizardState> {
  const spec = fileSpec(yaml)
  return {
    ...(spec.type && spec.type !== d.type ? typeChange(d, spec.type) : {}),
    ...(spec.module ? { terraform: spec.module } : {}),
    draftYaml: yaml,
    ...draftNames(yaml),
  }
}

/** The fields of a step's `with` that say what it calls, by its `call`; the rest (its input) is the person's. */
const CALLED: Partial<Record<string, readonly string[]>> = { provider: ['provider', 'action'], ansible: ['playbook'], terraform: ['module', 'action'], idp: ['tile'] }

const blank = (v: unknown) => v === undefined || v === null || v === ''

/** Two values of a step's `with`, an empty field the same as none and a module's keys in any order. */
function sameValue(a: unknown, b: unknown): boolean {
  if (blank(a) && blank(b)) return true
  if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) {
    const x = a as Record<string, unknown>
    const y = b as Record<string, unknown>
    return [...new Set([...Object.keys(x), ...Object.keys(y)])].every((k) => sameValue(x[k], y[k]))
  }
  return a === b
}

/** Whether a step already makes the call an action's step would: the same provider and action, playbook, module and action, or tile. */
export function sameCall(body: StepBody | undefined, step: ProviderSheetStep): boolean {
  const made = bodyFor(step.type, step.params)
  const keys = made.call ? CALLED[made.call] : undefined
  if (!body || !keys || body.call !== made.call) return false
  return keys.every((k) => sameValue(body.with?.[k], made.with?.[k]))
}

/** Whether a step of the workflow calls the draft: names its provider, or makes the call one of its actions would. */
export function calledBy(doc: WorkflowDocument, d: ProviderDraft): boolean {
  if (!NAME_RE.test(d.name)) return false
  const steps = sheetActions(d).map((a) => actionStep(d, a))
  return allStepLocations(doc).some(({ body }) => (body.call === 'provider' && body.with?.provider === d.name) || steps.some((s) => sameCall(body, s)))
}

/**
 * The draft a new-system entry carries on with: the latest of the workflow's new providers that
 * no step calls yet and that was not published, of the entry's type (Describe it: one that took
 * Devin's file; a step's "New provider…": any). None for the other entries.
 */
export function unfinishedDraft(drafts: Readonly<Record<string, ProviderDraft>>, doc: WorkflowDocument, entry: ProviderSheetEntry): ProviderDraft | undefined {
  if (!NEW_SYSTEM.has(entry)) return undefined
  const fits = (d: ProviderDraft) => (entry === 'describe' ? d.draftYaml !== null && d.imported === null : entry === 'step' || d.type === entryType(entry))
  return Object.values(drafts)
    .filter((d) => d.origin === 'new' && !d.changeRequest && fits(d) && !calledBy(doc, d))
    .at(-1)
}

/** The action of the draft that the step the sheet fills already calls, if it calls one. */
export function currentAction(doc: WorkflowDocument, d: WizardState, target: ProviderSheetTarget | null): string | null {
  if (target?.kind !== 'step') return null
  const body = findStepLocation(doc, target.step)?.body
  return body ? (sheetActions(d).find((a) => sameCall(body, actionStep(d, a)))?.id ?? null) : null
}

/**
 * Keeps a change in the draft: merged into the copy the store holds (so two changes made at once
 * both land), or into the draft shown when it is not saved yet, and saved at once. Not a document
 * edit: outside undo and the dirty flag. Once the sheet dropped the draft (discarded, an edit
 * published, started over), a change that answers late (a try, a test, an import) is ignored
 * instead of bringing the draft back: null.
 */
export function keepInDraft(store: DesignerStore, shown: ProviderDraft, patch: Partial<ProviderDraft>, dropped = false): ProviderDraft | null {
  if (dropped) return null
  const base = store.getState().providerDrafts[shown.id] ?? shown
  const next: ProviderDraft = { ...base, ...patch }
  store.upsertProviderDraft(next)
  return next
}

/**
 * What publishing a draft on its own ("Publish provider only") does to it: a new provider's draft
 * stays, marked with its change request (true), so the steps that call it keep resolving and the
 * workflow's Publish requires that change request instead of opening another; an edit's goes
 * (false), since the published version serves the steps meanwhile.
 */
export function markPublished(store: DesignerStore, draft: ProviderDraft, changeRequest: string): boolean {
  if (draft.origin !== 'new') return false
  keepInDraft(store, draft, { changeRequest })
  return true
}

/** Drops the draft a new-system entry carried on with, the sheet staying open for a new one ("Start over"). */
export function startOver(store: DesignerStore, id: string): void {
  // the store's sheet stops pointing at the draft first, so dropping it does not close the sheet
  const open = store.getState().providerSheet
  if (open?.draft === id) store.openProviderSheet({ ...open, draft: null })
  store.removeProviderDraft(id)
}

/** How the sheet was left: the action to insert, if one was picked; whether the draft goes (discarded, or an edit published on its own). */
export interface SheetExit {
  action: SheetAction | null
  dropDraft: boolean
}

/**
 * Closes the sheet: the picked action becomes a step where it was opened from (null when none
 * is), unless the step it fills already makes that call, which then keeps its inputs; a dropped
 * draft goes.
 */
export function leaveSheet(store: DesignerStore, draft: ProviderDraft | null, exit: SheetExit): string | null {
  const { providerSheet, doc } = store.getState()
  const target = providerSheet?.target ?? null
  let step = draft && exit.action && target && NAME_RE.test(draft.name) ? actionStep(draft, exit.action) : undefined
  if (step && target?.kind === 'step' && sameCall(findStepLocation(doc, target.step)?.body, step)) step = undefined
  const id = store.closeProviderSheet(step)
  if (exit.dropDraft && draft) store.removeProviderDraft(draft.id)
  return id
}

/* ----------------------------------------------------------------------------
   The sheet
   ------------------------------------------------------------------------- */

const SECTION_INFO = Object.fromEntries(STEPS.map((s) => [s.id, s])) as Record<string, (typeof STEPS)[number]>

/** The open sheet; one per opening (a new entry, provider or target starts it over). */
export function ProviderSheet() {
  const sheet = useDesignerSelector((s) => s.providerSheet)
  if (!sheet) return null
  return <OpenSheet key={`${sheet.entry}|${sheet.provider ?? ''}|${JSON.stringify(sheet.target)}`} sheet={sheet} />
}

function OpenSheet({ sheet }: { sheet: ProviderSheetState }) {
  const store = useDesignerStore()
  // opened for a new system with no draft named: the one left unfinished from such an entry carries on
  const [resumed, setResumed] = useState<string | null>(() => {
    const { providerDrafts, doc } = store.getState()
    return sheet.draft === null && sheet.provider === null ? (unfinishedDraft(providerDrafts, doc, sheet.entry)?.id ?? null) : null
  })
  const draftId = sheet.draft ?? resumed
  const stored = useDesignerSelector((s) => (draftId ? s.providerDrafts[draftId] : undefined))
  const owner = useDesignerSelector((s) => s.doc.metadata?.owner ?? '')
  const doc = useDesignerSelector((s) => s.doc)
  // a published provider without a draft yet: its next patch starts from what the platform describes
  const published = sheet.provider !== null && !stored
  const detail = useQuery(['provider', sheet.provider ?? ''], () => api.provider(sheet.provider ?? ''), { enabled: published })
  const [pending, setPending] = useState<ProviderDraft | null>(() => (sheet.provider === null ? startDraft(sheet.entry, owner) : null))
  if (published && pending === null && detail.data) setPending(draftFromProvider(detail.data))
  const draft = stored ?? pending
  const [chosen, setChosen] = useState<string | null>(null)
  // how the sheet is being left, read when its exit animation ends
  const dropDraft = useRef(false)

  const actions = useMemo(() => (draft ? sheetActions(draft) : []), [draft])
  // filling a step: the action it already calls stands picked until another one is
  const calls = useMemo(() => (draft ? currentAction(doc, draft, sheet.target) : null), [doc, draft, sheet.target])
  const action = actions.find((a) => a.id === (chosen ?? calls)) ?? null

  const restart = () => {
    if (!draftId) return
    startOver(store, draftId)
    setResumed(null)
    setChosen(null)
  }

  return (
    <Drawer onClose={() => leaveSheet(store, draft, { action, dropDraft: dropDraft.current })} width="max-w-6xl" label="Provider">
      {draft ? (
        <SheetBody
          key={draft.id}
          sheet={sheet}
          draft={draft}
          saved={stored !== undefined}
          resumed={resumed !== null && draftId === resumed}
          actions={actions}
          chosen={action?.id ?? null}
          onChoose={setChosen}
          onDropDraft={() => {
            dropDraft.current = true
          }}
          onStartOver={restart}
        />
      ) : (
        <Loading provider={sheet.provider ?? ''} error={detail.error} onRetry={() => void detail.refetch()} />
      )}
    </Drawer>
  )
}

function Loading({ provider, error, onRetry }: { provider: string; error: unknown; onRetry: () => void }) {
  const close = useOverlayClose()
  return (
    <div className="flex h-full min-h-0 flex-col" data-testid="provider-sheet">
      <div className="flex items-center gap-3 border-b border-stroke-light px-4 py-3">
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-subheadline font-semibold text-primary">{provider}</h2>
        </div>
        <IconButton label="Close" onClick={close}>
          <Icon name="close" size={18} />
        </IconButton>
      </div>
      <div className="flex flex-1 items-center justify-center bg-surface-secondary p-5">
        {error ? (
          <ErrorState error={error} title="Could not load the provider" onRetry={onRetry} />
        ) : (
          <p className="flex items-center gap-2 text-sm text-secondary" role="status">
            <Spinner /> Loading the provider…
          </p>
        )}
      </div>
    </div>
  )
}

/** A line above the section: what state the draft is in, and what can be done about it. */
function Notice({ icon, children, action, testId }: { icon: IconName; children: ReactNode; action?: ReactNode; testId?: string }) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-accent/40 bg-accent-tint px-4 py-2 text-sm" data-testid={testId}>
      <span className="inline-flex min-w-0 items-start gap-2 text-primary">
        <Icon name={icon} size={14} className="mt-0.5 shrink-0 text-accent" />
        <span className="min-w-0">{children}</span>
      </span>
      {action}
    </div>
  )
}

const openChangeRequest = (id: string) => window.open(`#${pathFor('change-request', { id })}`, '_blank', 'noopener')

/** A new provider published on its own: its change request, and the draft read-only until it merges (or editable again once closed). */
function PublishedNotice({ id, name, onEditAgain }: { id: string; name: string; onEditAgain: () => void }) {
  const cr = useQuery(['change-request', id], () => api.changeRequest(id))
  const status = cr.data?.status ?? 'open'
  const open = (
    <Button size="sm" variant="ghost" icon="external" onClick={() => openChangeRequest(id)}>
      Open change request
    </Button>
  )
  if (status === 'closed')
    return (
      <Notice
        icon="git-branch"
        testId="provider-sheet-published"
        action={
          <Button size="sm" icon="edit" onClick={onEditAgain}>
            Edit again
          </Button>
        }
      >
        Change request {id} was closed without merging. Edit the draft again to change it and publish it once more.
      </Notice>
    )
  return (
    <Notice icon="git-branch" testId="provider-sheet-published" action={open}>
      {status === 'merged'
        ? `Change request ${id} merged: ${name || 'the provider'} is published, and this draft is no longer needed.`
        : `Published on its own as change request ${id}. Until it merges, the workflow's steps call this draft and the workflow's Publish waits for that change request; the draft is read-only meanwhile.`}
    </Notice>
  )
}

interface SheetBodyProps {
  sheet: ProviderSheetState
  draft: ProviderDraft
  /** the draft is kept in this browser (it changed since the sheet opened, or before) */
  saved: boolean
  /** a new-system entry carries on with this draft, left unfinished from such an entry */
  resumed?: boolean
  actions: SheetAction[]
  chosen: string | null
  onChoose: (id: string | null) => void
  /** the draft goes when the sheet closes: discarded, or an edit published on its own */
  onDropDraft: () => void
  /** drops the draft carried on with, and starts a new one */
  onStartOver?: () => void
}

/** The sheet on a draft: its header, the rail of sections, the section's step and the footer that uses an action. */
export function SheetBody({ sheet, draft, saved, resumed = false, actions, chosen, onChoose, onDropDraft, onStartOver }: SheetBodyProps) {
  const store = useDesignerStore()
  const close = useOverlayClose()
  // the draft was dropped (discarded, published, started over): a try, a test or an import answering late must not bring it back
  const gone = useRef(false)
  const drop = () => {
    gone.current = true
    onDropDraft()
  }
  const update = (patch: Partial<ProviderDraft>) => void keepInDraft(store, draft, patch, gone.current)
  const editing = draft.editing !== null
  // an ansible or terraform provider starts from its repository or module, unless it was started by hand or took a file
  const importable = !editing && (draft.type === 'ansible' || draft.type === 'terraform') && draft.imported === null && draft.draftYaml === null
  const [mode, setMode] = useState<'import' | 'hand'>(() => (draft.ansible.repo.trim() || draft.terraform.source.trim() || (!draft.importForm && draft.title.trim()) ? 'hand' : 'import'))
  // while importing, the import is the whole form: the rail, the issues and Next of the hand-written one wait
  const importing = importable && mode === 'import'
  const fileActions = takesFileActions(draft)
  const sections: SheetSection[] = importing ? ['connect'] : sectionsOf(draft)
  const [current, setSection] = useState<SheetSection>(() => firstSection(sheet.entry))
  const section = sections.includes(current) ? current : sections[0]
  // asked from Connect, Devin's draft then opens on the review
  const [asking, setAsking] = useState<'connect' | 'review' | null>(null)
  // Describe it opens on Ask Devin, once the sheet is up: the dialog then stacks above it, and Escape and the focus go to the dialog first
  const [describe] = useState(() => sheet.entry === 'describe' && !saved)
  useEffect(() => {
    if (!describe) return
    const t = window.setTimeout(() => setAsking('connect'), 0)
    return () => window.clearTimeout(t)
  }, [describe])
  const [confirming, setConfirming] = useState<'discard' | 'restart' | null>(null)
  const yaml = useMemo(() => providerYaml(draft), [draft])
  const issuesOf = (id: SheetSection) => (importing ? [] : sectionIssues(draft, id))
  const pos = sections.indexOf(section)
  const issues = issuesOf(section)
  const target = sheet.target
  const usable = NAME_RE.test(draft.name)
  // published on its own: read-only until its change request merges or is closed
  const readOnly = !!draft.changeRequest

  const title = editing ? `Edit ${draft.title || draft.name}` : draft.title.trim() || 'New provider'
  const subtitle = editing
    ? `${draft.name} ${draft.publishedVersion ?? ''} → ${draft.version} · a draft of the next patch, kept in this browser until published`
    : `${TYPE_LABEL[draft.type]} provider · a draft, kept in this browser until published`

  const connect = editing ? (
    <ConnectStep state={draft} update={update} />
  ) : (
    <div className="space-y-5">
      {importable && (
        <Segmented
          ariaLabel="How to start"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'import', label: draft.type === 'ansible' ? 'Import a repository' : 'Import a module' },
            { value: 'hand', label: 'Write it by hand' },
          ]}
        />
      )}
      {importing ? (
        <ImportStep
          kind={draft.type === 'ansible' ? 'ansible' : 'terraform'}
          defaultOwner={draft.owner}
          // kept in the draft as typed, so closing before the import loses nothing
          form={draft.importForm ?? null}
          onFormChange={(importForm) => update({ importForm })}
          onImported={(patch) => {
            update({ ...patch, importForm: undefined })
            setSection('review')
          }}
        />
      ) : (
        <>
          <TypeStep state={draft} update={update} onAsk={() => setAsking('connect')} />
          <ConnectStep state={draft} update={update} />
        </>
      )}
    </div>
  )

  return (
    <div className="flex h-full min-h-0 flex-col" data-testid="provider-sheet">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-stroke-light px-4 py-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border border-stroke-light bg-surface">
          <NodeGlyph glyph={providerGlyph(draft.type)} size={22} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="flex min-w-0 items-center gap-2">
            <span className="truncate text-subheadline font-semibold text-primary">{title}</span>
            <Tag size="sm" tone="accent">
              draft
            </Tag>
          </h2>
          <p className="truncate text-caption text-tertiary">{subtitle}</p>
        </div>
        {/* where n8n's detail view has Test step: the action to use as a step, its list opening into the sheet */}
        {target && (
          <div className="order-last flex w-full items-center gap-2 sm:order-none sm:w-auto">
            <Select
              ariaLabel="Action to use"
              size="sm"
              mono
              className="min-w-0 flex-1 sm:w-80 sm:flex-none"
              value={chosen ?? ''}
              onChange={(v) => onChoose(v || null)}
              placeholder={actions.length ? 'Pick an action to use' : 'No action yet'}
              disabled={actions.length === 0}
              options={actions.map((a) => ({ value: a.id, label: a.id, hint: [a.title !== a.id ? a.title : '', a.effect].filter(Boolean).join(' · ') }))}
            />
            <Button size="sm" variant="primary" icon="plus" disabled={!chosen || !usable} title={usable ? undefined : 'Name the provider first'} onClick={close} data-testid="provider-sheet-use">
              Use this action
            </Button>
          </div>
        )}
        {saved && (
          <IconButton label="Discard draft" onClick={() => setConfirming('discard')}>
            <Icon name="trash" size={16} />
          </IconButton>
        )}
        <IconButton label="Close" onClick={close}>
          <Icon name="close" size={18} />
        </IconButton>
      </div>

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <nav aria-label="Provider sections" className="flex shrink-0 gap-1 overflow-x-auto border-b border-stroke-light p-2 md:w-52 md:flex-col md:overflow-visible md:border-b-0 md:border-r md:p-3">
          {sections.map((id) => {
            const on = id === section
            const open = issuesOf(id).length
            return (
              <button
                key={id}
                type="button"
                aria-current={on ? 'step' : undefined}
                onClick={() => setSection(id)}
                className={cx('flex shrink-0 items-start gap-2 rounded-md px-3 py-2 text-left transition-colors', on ? 'bg-surface-secondary' : 'hover:bg-surface-secondary')}
              >
                <span className="min-w-0">
                  <span className={cx('flex items-center gap-1.5 text-footnote', on ? 'font-semibold text-primary' : 'font-medium text-secondary')}>
                    {SECTION_INFO[id].label}
                    {open > 0 && <span className="h-1.5 w-1.5 rounded-full bg-warning" title={`${open} to do`} />}
                  </span>
                  <span className="hidden text-caption text-tertiary md:block">{SECTION_INFO[id].description}</span>
                </span>
              </button>
            )
          })}
        </nav>
        <div className="min-h-0 min-w-0 flex-1 overflow-y-auto bg-surface-secondary p-4 md:p-5" data-testid={`provider-sheet-${section}`}>
          {resumed && (
            <Notice
              icon="history"
              testId="provider-sheet-resumed"
              action={
                onStartOver && (
                  <Button size="sm" variant="ghost" icon="refresh" onClick={() => setConfirming('restart')}>
                    Start over
                  </Button>
                )
              }
            >
              Carrying on with the draft left unfinished here.
            </Notice>
          )}
          {draft.changeRequest && <PublishedNotice id={draft.changeRequest} name={draft.name} onEditAgain={() => update({ changeRequest: undefined })} />}
          <fieldset disabled={readOnly} className="min-w-0">
            {section === 'connect' && connect}
            {section === 'actions' && <ActionsStep state={draft} update={update} />}
            {section === 'try' &&
              (fileActions ? (
                <Card flush>
                  <EmptyState icon="play" title="Tried from the step" message="This draft's actions come from its file, not from the form. Use one as a step and try it for real there, or go back to the form's YAML on the review to try the form's actions here." />
                </Card>
              ) : (
                <TryStep state={draft} update={update} yaml={yaml} initialAction={chosen ?? undefined} />
              ))}
            {section === 'review' && (
              <ReviewStep
                state={draft}
                update={update}
                yaml={yaml}
                surface="sheet"
                onAsk={() => setAsking('review')}
                onPublished={(accepted) => {
                  // a new provider's draft stays, marked with its change request; an edit's goes
                  if (!gone.current && !markPublished(store, draft, accepted.id)) drop()
                  // the action picked still becomes a step
                  close()
                }}
                publishLabel="Publish provider only"
                publishHint={
                  draft.origin === 'new'
                    ? "Opens this provider's change request on its own. The draft stays, read-only, so the workflow's steps keep calling it until the change request merges, and the workflow's Publish waits for it instead of opening another."
                    : "Opens the next patch's change request on its own and clears the draft; the workflow's steps call the published version meanwhile."
                }
                openChangeRequest={openChangeRequest}
              />
            )}
          </fieldset>
        </div>
      </div>

      {!importing && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-stroke-light px-4 py-3">
          {issues.length > 0 ? (
            <ul className="min-w-0 flex-1 space-y-0.5 text-caption text-secondary" aria-label="Before you continue">
              {issues.slice(0, 3).map((i) => (
                <li key={i} className="flex items-start gap-1.5">
                  <Icon name="info" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                  {i}
                </li>
              ))}
            </ul>
          ) : (
            <span />
          )}
          <div className="flex flex-wrap items-center justify-end gap-2">
            {pos > 0 && (
              <Button variant="ghost" icon="arrow-left" onClick={() => setSection(sections[pos - 1])}>
                Back
              </Button>
            )}
            {pos < sections.length - 1 && (
              <Button variant={target ? 'secondary' : 'primary'} iconRight="arrow-right" onClick={() => setSection(sections[pos + 1])}>
                {section === 'try' && Object.keys(draft.tries).length === 0 ? 'Skip to review' : 'Next'}
              </Button>
            )}
          </div>
        </div>
      )}

      {asking && (
        <DraftModal
          // above the sheet even when both open at once (Describe it)
          raised
          initial={describeProvider(draft)}
          onUse={(d) => {
            // the draft replaces the form's document, names the provider and says its type; asked from Connect, the review is where it is checked
            update(fileDraftPatch(draft, d.yaml))
            if (asking === 'connect') setSection('review')
            setAsking(null)
          }}
          onClose={() => setAsking(null)}
        />
      )}
      {confirming && (
        <ConfirmDialog
          title={confirming === 'restart' ? 'Start over?' : 'Discard this draft?'}
          message={`${draft.name || 'The provider'} and what was tried and recorded for it are dropped from this browser${confirming === 'restart' ? ', and the sheet starts empty' : ''}. Published providers do not change${draft.changeRequest ? `, and change request ${draft.changeRequest} stays open` : ''}.`}
          confirmLabel={confirming === 'restart' ? 'Start over' : 'Discard draft'}
          tone="danger"
          onConfirm={() => {
            if (confirming === 'restart') {
              gone.current = true
              onStartOver?.()
              return
            }
            drop()
            onChoose(null)
            close()
          }}
          onClose={() => setConfirming(null)}
        />
      )}
    </div>
  )
}
