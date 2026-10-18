/**
 * The Provider sheet: the Add provider wizard's steps in a drawer over the canvas, so a system can
 * be connected, its actions described, tried, reviewed and published without leaving the
 * designer. The steps are the wizard's own (components/provider-editor/ and the imports of
 * pages/ProviderImport.tsx), not copies; the sheet only lays them out the way n8n's credential
 * modal does (a rail of sections beside the form) and decides what happens on close.
 *
 * It edits one provider draft (designer/model/providerDrafts.ts): a new provider's, started from
 * the entry it was opened from, or the next patch of a published one (`draftFromProvider`). The
 * first change saves the draft and every later one replaces it (`upsertProviderDraft`), so closing
 * and reopening loses nothing. The action picked to use becomes a step where the sheet was opened
 * from, inserted or filling that step (`closeProviderSheet`), as n8n adds a node after creating a
 * credential: on "Use this action", or on closing once one is picked.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, ConfirmDialog, cx, Drawer, Icon, IconButton, Segmented, Select, Spinner, Tag, useOverlayClose } from '@iaf/ui'
import { pathFor } from '../../app/router'
import { api } from '../../lib/api'
import { useQuery } from '../../lib/queries'
import type { ProviderType } from '../../lib/types'
import { ErrorState } from '../../components/common'
import { NAME_RE, STEPS, STEP_NUMBER, TYPE_LABEL, describeProvider, draftNames, providerYaml, stepIssues, yamlActions, type WizardState } from '../../components/provider-editor/state'
import { TypeStep } from '../../components/provider-editor/TypeStep'
import { ConnectStep } from '../../components/provider-editor/ConnectStep'
import { ActionsStep } from '../../components/provider-editor/ActionsStep'
import { TryStep } from '../../components/provider-editor/TryStep'
import { ReviewStep } from '../../components/provider-editor/ReviewStep'
import { DraftModal } from '../../components/provider-editor/DraftModal'
import { ImportStep } from '../../pages/ProviderImport'
import { NodeGlyph, providerGlyph } from '../canvas/glyphs'
import { draftFromProvider, newProviderDraft, type ProviderDraft } from '../model/providerDrafts'
import { useDesignerSelector, useDesignerStore, type DesignerStore, type ProviderSheetEntry, type ProviderSheetState, type ProviderSheetStep } from '../model/store'

/* ----------------------------------------------------------------------------
   What the sheet decides, apart from the screen (exported for its tests)
   ------------------------------------------------------------------------- */

/** The sections of the sheet: the wizard's steps after Type, whose fields Connect shows. */
export type SheetSection = 'connect' | 'actions' | 'try' | 'review'

const ENTRY_TYPE: Partial<Record<ProviderSheetEntry, ProviderType>> = { openapi: 'openapi', curl: 'http', ansible: 'ansible', terraform: 'terraform', idp: 'idp', sdk: 'sdk' }

/** The provider type an entry starts a new provider as: cURL is an http provider; Describe it and the rest start where the wizard does. */
export function entryType(entry: ProviderSheetEntry): ProviderType {
  return ENTRY_TYPE[entry] ?? 'openapi'
}

/** A new provider's draft for the entry it was opened from, owned by the workflow's team until changed. */
export function startDraft(entry: ProviderSheetEntry, owner: string): ProviderDraft {
  return newProviderDraft(entryType(entry), owner ? { owner } : {})
}

/** What the sheet shows of a draft: an edit keeps its file, so it collects evidence (Connect, Try it) and is reviewed, as the wizard's Edit provider. */
export function sectionsOf(d: WizardState): SheetSection[] {
  return d.editing ? ['connect', 'try', 'review'] : ['connect', 'actions', 'try', 'review']
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

/** The actions the draft offers: those of the file it took when it took one (Devin's, an import's), else the wizard's selected actions. */
export function sheetActions(d: WizardState): SheetAction[] {
  const fromFile = d.draftYaml ? yamlActions(d.draftYaml) : []
  if (fromFile.length > 0) return fromFile
  return d.actions.filter((a) => a.selected && a.id.trim()).map((a) => ({ id: a.id.trim(), title: a.title.trim(), effect: a.effect }))
}

/** The step an action of the draft becomes, as the node creator inserts a published provider's (catalog.ts). */
export function actionStep(d: WizardState, a: SheetAction): ProviderSheetStep {
  const name = a.title || a.id
  switch (d.type) {
    case 'terraform': {
      const terraformAction = a.id === 'apply' || a.id === 'destroy' ? a.id : 'plan'
      return { type: 'terraform', params: { name: `${d.title.trim() || d.name}: ${name}`, module: { source: d.terraform.source.trim(), version: d.terraform.version.trim() }, terraformAction } }
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
 * Keeps a change in the draft: merged into the copy the store holds (so two changes made at once
 * both land), or into the draft shown when it is not saved yet, and saved at once. Not a document
 * edit: outside undo and the dirty flag.
 */
export function keepInDraft(store: DesignerStore, shown: ProviderDraft, patch: Partial<WizardState>): ProviderDraft {
  const base = store.getState().providerDrafts[shown.id] ?? shown
  const next: ProviderDraft = { ...base, ...patch }
  store.upsertProviderDraft(next)
  return next
}

/** How the sheet was left: the action to insert, if one was picked; whether the draft goes (published on its own, or discarded). */
export interface SheetExit {
  action: SheetAction | null
  dropDraft: boolean
}

/** Closes the sheet: the picked action becomes a step where it was opened from (null when none is), and a published or discarded draft is dropped. */
export function leaveSheet(store: DesignerStore, draft: ProviderDraft | null, exit: SheetExit): string | null {
  const target = store.getState().providerSheet?.target ?? null
  const step = draft && exit.action && target && NAME_RE.test(draft.name) ? actionStep(draft, exit.action) : undefined
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
  const stored = useDesignerSelector((s) => (sheet.draft ? s.providerDrafts[sheet.draft] : undefined))
  const owner = useDesignerSelector((s) => s.doc.metadata?.owner ?? '')
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
  const action = actions.find((a) => a.id === chosen) ?? null

  return (
    <Drawer onClose={() => leaveSheet(store, draft, { action, dropDraft: dropDraft.current })} width="max-w-6xl" label="Provider">
      {draft ? (
        <SheetBody
          key={draft.id}
          sheet={sheet}
          draft={draft}
          saved={stored !== undefined}
          actions={actions}
          chosen={action?.id ?? null}
          onChoose={setChosen}
          onDropDraft={() => {
            dropDraft.current = true
          }}
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

interface SheetBodyProps {
  sheet: ProviderSheetState
  draft: ProviderDraft
  /** the draft is kept in this browser (it changed since the sheet opened, or before) */
  saved: boolean
  actions: SheetAction[]
  chosen: string | null
  onChoose: (id: string | null) => void
  /** the draft goes when the sheet closes: published on its own, or discarded */
  onDropDraft: () => void
}

/** The sheet on a draft: its header, the rail of sections, the section's step and the footer that uses an action. */
export function SheetBody({ sheet, draft, saved, actions, chosen, onChoose, onDropDraft }: SheetBodyProps) {
  const store = useDesignerStore()
  const close = useOverlayClose()
  const sections = sectionsOf(draft)
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
  // an ansible or terraform provider starts from its repository or module, unless it was started by hand
  const [mode, setMode] = useState<'import' | 'hand'>(() => (draft.ansible.repo.trim() || draft.terraform.source.trim() ? 'hand' : 'import'))
  const [discarding, setDiscarding] = useState(false)
  const update = (patch: Partial<WizardState>) => void keepInDraft(store, draft, patch)
  const yaml = useMemo(() => providerYaml(draft), [draft])
  const pos = sections.indexOf(section)
  const issues = sectionIssues(draft, section)
  const target = sheet.target
  const usable = NAME_RE.test(draft.name)
  const editing = draft.editing !== null

  const title = editing ? `Edit ${draft.title || draft.name}` : draft.title.trim() || 'New provider'
  const subtitle = editing
    ? `${draft.name} ${draft.publishedVersion ?? ''} → ${draft.version} · a draft of the next patch, kept in this browser until published`
    : `${TYPE_LABEL[draft.type]} provider · a draft, kept in this browser until published`

  const importable = !editing && (draft.type === 'ansible' || draft.type === 'terraform') && draft.imported === null
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
      {importable && mode === 'import' ? (
        <ImportStep
          kind={draft.type === 'ansible' ? 'ansible' : 'terraform'}
          defaultOwner={draft.owner}
          onImported={(patch) => {
            update(patch)
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
          <IconButton label="Discard draft" onClick={() => setDiscarding(true)}>
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
            const open = sectionIssues(draft, id).length
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
          {section === 'connect' && connect}
          {section === 'actions' && <ActionsStep state={draft} update={update} />}
          {section === 'try' && <TryStep state={draft} update={update} yaml={yaml} initialAction={chosen ?? undefined} />}
          {section === 'review' && (
            <ReviewStep
              state={draft}
              update={update}
              yaml={yaml}
              onAsk={() => setAsking('review')}
              onPublished={() => {
                // the provider's change request carries it now: the draft goes, and the action picked still becomes a step
                onDropDraft()
                close()
              }}
              publishLabel="Publish provider only"
              publishHint="Opens this provider's change request on its own and clears the draft; the workflow's steps call it once it merges. The workflow's Publish opens it with the workflow's instead."
              openChangeRequest={(id) => window.open(`#${pathFor('change-request', { id })}`, '_blank', 'noopener')}
            />
          )}
        </div>
      </div>

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

      {asking && (
        <DraftModal
          // above the sheet even when both open at once (Describe it)
          raised
          initial={describeProvider(draft)}
          onUse={(d) => {
            // the draft replaces the wizard's document and names the provider; asked from Connect, the review is where it is checked
            update({ draftYaml: d.yaml, ...draftNames(d.yaml) })
            if (asking === 'connect') setSection('review')
            setAsking(null)
          }}
          onClose={() => setAsking(null)}
        />
      )}
      {discarding && (
        <ConfirmDialog
          title="Discard this draft?"
          message={`${draft.name || 'The provider'} and what was tried and recorded for it are dropped from this browser. Published providers do not change.`}
          confirmLabel="Discard draft"
          tone="danger"
          onConfirm={() => {
            onDropDraft()
            onChoose(null)
            close()
          }}
          onClose={() => setDiscarding(false)}
        />
      )}
    </div>
  )
}
