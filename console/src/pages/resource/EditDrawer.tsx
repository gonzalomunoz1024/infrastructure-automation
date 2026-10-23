import { useMemo, useState, type ReactNode } from 'react'
import { Button, Drawer, EmptyState, Icon, OverlayBody, OverlayFooter, OverlayHeader, SkeletonRows, useDebounce, useOverlayClose } from '@iaf/ui'
import { DotTag, PreviewCard, SchemaForm, displayWfrn, fieldNames, fieldRoot, formatValueText, schemaType, titleCase, validateAgainst, xConsoleOf, type Problem, type Schema, type SchemaValue } from '@iaf/console-kit'
import { CodeDisclosure } from '../../app/CodeDisclosure'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { bff } from '../../lib/bff'
import { codeSamplesFor, subjectFor } from '../../lib/code'
import { singularOf, type ConsoleAction, type ConsoleType } from '../../lib/descriptors'
import { explain, explainLine, movedOnTo } from '../../lib/plain'
import { isOpenStatus } from '../../lib/poll'
import { invalidate, useQuery } from '../../lib/queries'
import { useSubmissionKey } from '../../lib/submission'
import type { CommandAccepted, CommandInput, ConsoleResourceDetail } from '../../lib/types'
import { HeldButton } from './HeldButton'
import { ResourceRisk } from '../risk/RiskCard'

/*
 * Edit configuration (WP 4.1): the type's update workflow (P-08) as a form that starts from what
 * the resource is asked to be, a "What changes" table of only the fields that change (what is
 * asked for now, what it becomes, and beside it what the provider reports when that differs), a
 * live preview of the update, and the request as code. Only the changed fields are sent, as a
 * PATCH carrying the version the person read; when someone changed the resource first, the
 * drawer says so and loads the latest without losing the person's edits.
 */

/** What the menu and the Configuration tab say for a type with mutable fields whose provider publishes no update workflow (the plan's words). */
export const EDIT_NOT_OFFERED = 'Editing is not offered by this provider'

/** Why an edit has to wait, as the page says it for every verb: a request in flight, or a resource that is not active or failed. */
export function editWaitNote(detail: Pick<ConsoleResourceDetail, 'state' | 'lastJob'> | undefined, noun: string): string | undefined {
  if (!detail) return undefined
  if (isOpenStatus(detail.lastJob?.status)) return 'Waits for the request in flight to end'
  if (detail.state !== 'active' && detail.state !== 'failed') return `Runs once the ${noun} is active`
  return undefined
}

const isMutable = (prop: Schema | undefined) => xConsoleOf(prop).mutable === true
const isOperational = (prop: Schema | undefined) => xConsoleOf(prop).operational === true

/** The update workflow's form: its input without the subject, keeping what an update may carry, the mutable fields and the operational inputs (P-08). */
export function editSchemaOf(action: ConsoleAction, type: Pick<ConsoleType, 'name'>): Schema {
  const subject = action.subject ?? subjectFor(action, type)
  const props = Object.entries(action.input.properties ?? {}).filter(([name, prop]) => name !== subject && (isMutable(prop) || isOperational(prop)))
  const names = new Set(props.map(([name]) => name))
  return { ...action.input, properties: Object.fromEntries(props), required: (action.input.required ?? []).filter((n) => names.has(n)) }
}

/** The form's first value: each mutable field as the resource's desired attributes hold it, where the value fits the field. */
export function editStart(schema: Schema, attributes: Record<string, unknown>): SchemaValue {
  const out: SchemaValue = {}
  for (const [name, prop] of Object.entries(schema.properties ?? {})) {
    const v = attributes[name]
    if (!isMutable(prop) || v === undefined || v === null || v === '') continue
    if (prop.enum && !prop.enum.some((e) => e === v)) continue
    const t = schemaType(prop)
    const fits = (t === 'string' && typeof v === 'string') || ((t === 'integer' || t === 'number') && typeof v === 'number') || (t === 'boolean' && typeof v === 'boolean') || (t === 'array' && Array.isArray(v)) || (t === 'object' && typeof v === 'object')
    if (fits) out[name] = v
  }
  return out
}

function same(a: unknown, b: unknown): boolean {
  const norm = (v: unknown) => (v === '' || v === null || v === undefined ? null : v)
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b))
}

/** What the person changed: the mutable fields no longer where they started. A field left empty is unchanged (absent means unchanged, P-08). */
export function changedFields(schema: Schema, start: SchemaValue, value: SchemaValue): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [name, prop] of Object.entries(schema.properties ?? {})) {
    const v = value[name]
    if (!isMutable(prop) || v === undefined || v === null || v === '') continue
    if (!same(v, start[name])) out[name] = v
  }
  return out
}

export interface DiffRow {
  field: string
  label: string
  /** what is asked for now (the desired attribute) */
  now: unknown
  after: unknown
  /** what the provider last reported, when it differs from `now` */
  observed?: unknown
  schema?: Schema
}

/** One row per changed field: now, after, and what the provider reports beside now when it differs (the open finding's field, else the observed record). */
export function diffRows(schema: Schema, detail: Pick<ConsoleResourceDetail, 'attributes' | 'observed' | 'finding'>, changed: Record<string, unknown>): DiffRow[] {
  const props = schema.properties ?? {}
  const found = new Map<string, unknown>()
  if (detail.finding?.status === 'open') for (const f of detail.finding.fields) found.set(f.path.replace(/^attributes\./, ''), f.observed)
  return Object.entries(changed).map(([field, after]) => {
    const now = detail.attributes[field]
    const reported = found.has(field) ? found.get(field) : detail.observed && field in detail.observed ? detail.observed[field] : undefined
    const row: DiffRow = { field, label: props[field]?.title ?? titleCase(field), now, after, schema: props[field] }
    if (reported !== undefined && !same(reported, now)) row.observed = reported
    return row
  })
}

/** The edits carried onto a newer version: what the person changed stays, every other field takes the new value, and the operational inputs stay as typed. */
export function rebaseEdits(schema: Schema, oldStart: SchemaValue, value: SchemaValue, newStart: SchemaValue): SchemaValue {
  const out: SchemaValue = { ...newStart, ...changedFields(schema, oldStart, value) }
  for (const [name, prop] of Object.entries(schema.properties ?? {})) if (!isMutable(prop) && value[name] !== undefined) out[name] = value[name]
  return out
}

/** The PATCH body: the changed fields (and any other operational input) as `input`, the reason beside it as the request's own. */
export function editBody(schema: Schema, changed: Record<string, unknown>, value: SchemaValue): CommandInput {
  const input: Record<string, unknown> = { ...changed }
  for (const [name, prop] of Object.entries(schema.properties ?? {})) {
    const v = value[name]
    if (isMutable(prop) || name === 'reason' || v === undefined || v === null || v === '') continue
    input[name] = v
  }
  const reason = typeof value.reason === 'string' ? value.reason.trim() : ''
  return reason ? { input, reason } : { input }
}

export interface EditDrawerProps {
  type: ConsoleType
  wfrn: string
  name: string
  /** the console's view of the resource: its desired attributes, what the provider reports, its version */
  detail: ConsoleResourceDetail
  onClose: () => void
  onAccepted: (accepted: CommandAccepted) => void
  /** values to start the form from instead of the resource's own (a revert to an earlier time) */
  initial?: SchemaValue
  /** what the drawer opens for, shown above the form */
  note?: ReactNode
}

function CancelButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}

function Subtitle({ name, wfrn }: { name: string; wfrn: string }) {
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-x-1.5">
      <span className="font-medium text-primary">{name}</span>
      <span className="truncate font-mono text-caption text-tertiary" title={wfrn}>
        {displayWfrn(wfrn, 36)}
      </span>
    </span>
  )
}

export function EditDrawer(props: EditDrawerProps) {
  const { type, wfrn, name, onClose } = props
  if (type.update) return <EditForm {...props} action={type.update} />
  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Edit configuration: ${name}`}>
      <OverlayHeader title="Edit configuration" icon="edit" subtitle={<Subtitle name={name} wfrn={wfrn} />} />
      <OverlayBody>
        <EmptyState compact icon="edit" title={EDIT_NOT_OFFERED} message={`${type.owner} publishes no update workflow for ${type.title.toLowerCase()}, so the console has nothing to run for a change. The provider adds one to its catalog to offer it.`} />
      </OverlayBody>
      <OverlayFooter>
        <CancelButton />
      </OverlayFooter>
    </Drawer>
  )
}

function EditForm({ type, action, wfrn, name, detail, onClose, onAccepted, initial, note }: EditDrawerProps & { action: ConsoleAction }) {
  const { decide, account, region } = useConsole()
  const noun = singularOf(type.title).toLowerCase()
  const schema = useMemo(() => editSchemaOf(action, type), [action, type])
  // the version the edits are made against: the one the drawer opened on, until the person loads a newer one
  const [base, setBase] = useState(detail)
  const start = useMemo(() => editStart(schema, base.attributes), [schema, base.attributes])
  const [value, setValue] = useState<SchemaValue>(() => ({ ...editStart(schema, detail.attributes), ...initial }))
  const [problems, setProblems] = useState<Problem[]>([])
  const [movedOn, setMovedOn] = useState<number | undefined>(undefined)
  const [loadingLatest, setLoadingLatest] = useState(false)
  const [latestError, setLatestError] = useState<unknown>(null)
  const keyFor = useSubmissionKey()
  const cmd = useCommand()

  const changed = useMemo(() => changedFields(schema, start, value), [schema, start, value])
  const rows = useMemo(() => diffRows(schema, base, changed), [schema, base, changed])
  const nothing = rows.length === 0
  const body = useMemo(() => editBody(schema, changed, value), [schema, changed, value])
  const debounced = useDebounce(JSON.stringify(changed), 350)
  const preview = useQuery(['edit-preview', type.name, wfrn, base.version, debounced], () => bff.resources.preview(type.name, wfrn, 'update', { input: JSON.parse(debounced) as Record<string, unknown> }), { enabled: !nothing && debounced !== '{}' })
  const decision = decide(type, 'update')
  const policy = nothing ? undefined : preview.data?.policy.decision
  // the page goes on following the resource: a request that started since the drawer opened holds the edit back
  const held = movedOn !== undefined ? 'Load the latest first' : nothing ? 'Nothing to change yet' : editWaitNote(detail, noun)
  const samples = codeSamplesFor({ kind: 'update', type, wfrn, body, action, version: base.version, context: { accountId: account.id, region: region.name } })

  const send = async () => {
    if (held || policy === 'deny') return
    const found = validateAgainst(schema, value).filter((p) => p.level === 'error')
    setProblems(found)
    if (found.length) {
      document.getElementById(`edit-${fieldRoot(found[0].field)}`)?.focus()
      return
    }
    // a submission is the body and the version it was made against: the same edit on a newer version is a new request
    const key = keyFor({ ...body, ifMatch: base.version })
    const update = () =>
      bff.resources.update(type.name, wfrn, body, key, base.version).catch((e: unknown) => {
        const version = movedOnTo(e)
        if (version !== undefined) setMovedOn(version)
        throw e
      })
    const accepted = await cmd.run(`Edit ${name}`, update, { quiet: true, invalidates: ['resources', 'jobs', `console-activity/${type.name}`], onProblems: setProblems })
    if (accepted) onAccepted(accepted)
  }

  /** Reads the resource again and carries the person's edits onto it: the changes are worked out again against the new values. */
  const loadLatest = async () => {
    setLoadingLatest(true)
    setLatestError(null)
    try {
      const latest = await bff.resources.get(type.name, wfrn)
      setValue((v) => rebaseEdits(schema, start, v, editStart(schema, latest.attributes)))
      setBase(latest)
      setMovedOn(undefined)
      cmd.clear()
      // the page behind the drawer shows the latest too
      invalidate(`console-resource/${type.name}`)
      invalidate(`resource/${type.plural}`)
    } catch (e) {
      setLatestError(e)
    } finally {
      setLoadingLatest(false)
    }
  }

  return (
    <Drawer onClose={onClose} width="max-w-xl" label={`Edit configuration: ${name}`}>
      <OverlayHeader title="Edit configuration" icon="edit" subtitle={<Subtitle name={name} wfrn={wfrn} />} />
      <OverlayBody>
        <form
          id="edit-form"
          onSubmit={(e) => {
            e.preventDefault()
            void send()
          }}
          noValidate
        >
          {note}
          <p className="mb-5 text-sm text-secondary">
            {action.description ?? `${action.title} changes what this ${noun} is asked to be.`} Only the fields you change are sent; <span className="font-mono">{action.workflow}</span> applies them and records the new values.
          </p>
          <SchemaForm schema={schema} value={value} onChange={setValue} problems={problems} idPrefix="edit" />
        </form>

        <section aria-labelledby="edit-changes-title" className="mt-6">
          <h3 id="edit-changes-title" className="mb-2 text-subheadline font-semibold text-primary">
            What changes
          </h3>
          {nothing ? (
            <p className="rounded-lg border border-dashed border-stroke-light px-3 py-2.5 text-caption text-tertiary">Nothing to change yet. Change a field above to see it here, against what is asked for now and what the provider reports.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-stroke-light">
              <table className="w-full text-left text-sm" data-testid="edit-diff">
                <caption className="sr-only">What changes: each field as asked for now and after this edit, with what the provider reports where it differs</caption>
                <thead className="text-caption text-tertiary">
                  <tr>
                    <th scope="col" className="px-4 py-2 font-medium">
                      Field
                    </th>
                    <th scope="col" className="px-4 py-2 font-medium">
                      Now
                    </th>
                    <th scope="col" className="px-4 py-2 font-medium">
                      After
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-stroke-light">
                  {rows.map((r) => {
                    const x = xConsoleOf(r.schema)
                    return (
                      <tr key={r.field} data-field={r.field}>
                        <th scope="row" className="px-4 py-2 font-normal text-primary">
                          {r.label}
                        </th>
                        <td className="px-4 py-2 tabular-nums text-primary">
                          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                            <span>{r.now === undefined || r.now === null ? <span className="text-tertiary">not set</span> : formatValueText(r.now, x)}</span>
                            {r.observed !== undefined && (
                              <DotTag tone="warning" size="sm" title="What the provider last reported: the drift service found it differs from what is asked for">
                                {`Provider reports ${formatValueText(r.observed, x)}`}
                              </DotTag>
                            )}
                          </span>
                        </td>
                        <td className="px-4 py-2 font-medium tabular-nums text-primary">{formatValueText(r.after, x)}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {!nothing && (
          <section aria-label="What will happen" className="mt-6">
            {preview.loading || !preview.data ? (
              preview.error ? <FailureNote failure={explain(preview.error, 'Preview this change')} compact onRetry={() => void preview.refetch()} /> : <SkeletonRows n={2} rowClassName="h-16" />
            ) : (
              <>
                <PreviewCard preview={preview.data} compact title="What will happen" />
                <ResourceRisk type={type} wfrn={wfrn} verb="update" destructive={false} preview={preview.data} compact className="mt-4" />
              </>
            )}
            {policy === 'deny' && <p className="mt-2 text-caption text-critical">Policy refuses this change as it stands, so it cannot be sent. Change the fields above, or ask the account’s admins.</p>}
          </section>
        )}

        <CodeDisclosure samples={samples} ariaLabel="Edit configuration as code" className="mt-6" />

        {movedOn !== undefined && (
          <div role="alert" className="mt-5 rounded-lg border border-warning/40 bg-warning-tint/40 p-4 text-sm" data-failure="moved-on">
            <div className="flex items-start gap-3">
              <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-warning" />
              <div className="min-w-0 flex-1 space-y-1">
                <p className="font-semibold text-primary">Someone changed this first</p>
                <p className="text-secondary">
                  {name} moved on to version {movedOn} while you were editing, so nothing was sent over their change.
                </p>
                <p className="text-primary">Load the latest: your edits stay, and what changes is worked out again against it.</p>
                <div className="pt-1">
                  <Button size="sm" icon="refresh" loading={loadingLatest} onClick={() => void loadLatest()}>
                    Load the latest
                  </Button>
                </div>
                {latestError !== null && <p className="text-caption text-secondary">{explainLine(latestError, `Load the latest ${noun}`)}</p>}
              </div>
            </div>
          </div>
        )}
        <FailureNote failure={movedOn !== undefined ? null : cmd.failure} shownFields={fieldNames(schema)} onDismiss={cmd.clear} className="mt-5" />
      </OverlayBody>
      <OverlayFooter>
        <CancelButton />
        {decision.allowed && held ? (
          <HeldButton reason={held} icon="check" verb="update">
            {action.title}
          </HeldButton>
        ) : (
          <ActionButton decision={decision} what={`Edit ${name}`} verb="update" variant="primary" icon="check" loading={cmd.busy} disabled={policy === 'deny'} onClick={() => void send()}>
            {action.title}
          </ActionButton>
        )}
      </OverlayFooter>
    </Drawer>
  )
}
