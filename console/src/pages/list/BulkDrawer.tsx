import { useMemo, useState } from 'react'
import { Button, Drawer, Field, Icon, Input, OverlayBody, OverlayFooter, OverlayHeader, Segmented, SkeletonRows, Textarea, plural, useDebounce, useOverlayClose } from '@iaf/ui'
import { ConfirmDialog, SchemaForm, fieldNames, fieldRoot, isComplete, validateAgainst, type Problem, type Schema, type SchemaValue } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { bff } from '../../lib/bff'
import { subjectFor } from '../../lib/code'
import { singularOf, type ConsoleAction, type ConsoleType } from '../../lib/descriptors'
import { explain } from '../../lib/plain'
import { rolloutOf } from '../../lib/rollout'
import { useQuery } from '../../lib/queries'
import { nameOf } from '../../lib/resourceList'
import { cleanInput, useSubmissionKey } from '../../lib/submission'
import type { BatchAccepted, BatchPreview, ConsoleResource } from '../../lib/types'
import { BlastRadiusCard } from '../resource/BlastRadius'
import { startValue } from '../resource/ActionDrawer'
import { batchBodyOf, chosenRollout, confirmTextOf, ownInputsOf, plannedRollout, rowInputOf, unrunnableOf, wavesLine, type RolloutPreset } from './bulk'

/*
 * One verb on the list's selected rows, as one request (WP 4.2): the workflow's input every row
 * shares, the rollout (the workflow's own waves, two waves or all at once, how many run at once
 * and the share of failures that stops it), the waves that rollout makes of these rows, and the
 * BFF's preview of every row: the ones that cannot run as they are are named, with why, and can
 * be left out in one click. A destructive verb asks for the verb and the count typed out. Once
 * sent, the page opens the request, which follows its waves.
 */

export interface BulkDrawerProps {
  type: ConsoleType
  action: ConsoleAction
  rows: readonly ConsoleResource[]
  onClose: () => void
  onAccepted: (accepted: BatchAccepted) => void
}

const PRESETS: { value: RolloutPreset; label: string }[] = [
  { value: 'own', label: 'Its own waves' },
  { value: 'two', label: 'Two waves' },
  { value: 'all', label: 'All at once' },
]

function CancelButton() {
  const close = useOverlayClose()
  return <Button onClick={close}>Cancel</Button>
}

const whole = (raw: string, fallback: number) => {
  const n = Number(raw)
  return raw.trim() !== '' && Number.isInteger(n) ? n : fallback
}

export function BulkDrawer({ type, action, rows, onClose, onAccepted }: BulkDrawerProps) {
  const { decide } = useConsole()
  const subject = subjectFor(action, type)
  // an input every row answers from its own attributes stays each row's; the form shares the rest
  const own = useMemo(() => ownInputsOf(action.input, subject, rows), [action.input, subject, rows])
  const omit = useMemo(() => [...(subject ? [subject] : []), ...own], [subject, own])
  const schema = useMemo<Schema>(() => ({ ...action.input, required: (action.input.required ?? []).filter((r) => !omit.includes(r)) }), [action.input, omit])
  const [value, setValue] = useState<SchemaValue>(() => startValue({ ...schema, properties: Object.fromEntries(Object.entries(schema.properties ?? {}).filter(([k]) => !omit.includes(k))) }, subject, {}))
  const [problems, setProblems] = useState<Problem[]>([])
  const [left, setLeft] = useState<ReadonlySet<string>>(() => new Set())
  const ownRollout = action.batch?.rollout
  const base = rolloutOf(undefined, ownRollout)
  const [preset, setPreset] = useState<RolloutPreset>('own')
  const [parallel, setParallel] = useState(String(base.maxParallel))
  const [stop, setStop] = useState(String(base.stopIfFailedPercent))
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState(false)
  const keyFor = useSubmissionKey()
  const cmd = useCommand()

  const noun = singularOf(type.title).toLowerCase()
  const kept = rows.filter((r) => !left.has(r.wfrn))
  const wfrns = kept.map((r) => r.wfrn)
  const n = kept.length
  const max = Math.min(500, action.batch?.max ?? 500)
  const these = `these ${plural(n, noun)}`

  const maxParallel = whole(parallel, base.maxParallel)
  const stopIfFailedPercent = whole(stop, base.stopIfFailedPercent)
  const rolloutFields: Problem[] = [
    ...(maxParallel < 1 || maxParallel > 500 ? [{ level: 'error' as const, code: 'minimum', field: '/rollout/maxParallel', message: 'Run between 1 and 500 rows at once' }] : []),
    ...(stopIfFailedPercent < 0 || stopIfFailedPercent > 100 ? [{ level: 'error' as const, code: 'maximum', field: '/rollout/stopIfFailedPercent', message: 'A whole percentage from 0 to 100' }] : []),
  ]
  const rollout = chosenRollout(preset, maxParallel, stopIfFailedPercent, ownRollout)
  const planned = plannedRollout(rollout, ownRollout, n)
  const input = useMemo(() => cleanInput(value), [value])
  const items = kept.map((r) => ({ wfrn: r.wfrn, input: rowInputOf(action.input, subject, own, r.attributes) }))
  const ownReason = !!action.input.properties?.reason
  const body = batchBodyOf(action.verb, items, input, rollout, ownReason ? undefined : reason)
  const complete = isComplete(schema, value) && n > 0 && n <= max && rolloutFields.length === 0
  const debounced = useDebounce(JSON.stringify(body), 350)
  const preview = useQuery(['batch-preview', type.name, debounced], () => bff.resources.previewBatch(type.name, JSON.parse(debounced) as typeof body), { enabled: complete })
  const judged = complete ? preview.data : undefined
  const unrunnable = unrunnableOf(judged, wfrns)
  const decision = decide(type, action.verb)
  const policy = judged?.policy.decision
  const blocked = unrunnable.length > 0 || policy === 'deny'
  // what depends on the rows matters to a delete or a destructive verb; a restart leaves them standing
  const affected = action.verb === 'delete' || action.destructive ? judged?.affected : undefined

  const send = async (confirm: boolean) => {
    const payload = confirm ? { ...body, confirm: true } : body
    const accepted = await cmd.run(`${action.title} on ${these}`, () => bff.resources.batch(type.name, payload, keyFor(payload)), {
      quiet: true,
      invalidates: ['resources', 'console-resources', 'jobs', 'bff-job'],
      onProblems: setProblems,
    })
    if (accepted) onAccepted(accepted)
    else void preview.refetch()
  }

  const submit = () => {
    if (blocked || n === 0 || n > max) return
    const found = [...validateAgainst(schema, value).filter((p) => !omit.includes(fieldRoot(p.field) ?? '')), ...rolloutFields]
    setProblems(found)
    if (found.length) {
      const first = found[0].field ?? ''
      document.getElementById(first.startsWith('/rollout/') ? `bulk-${first.slice('/rollout/'.length)}` : `bulk-${action.verb}-${fieldRoot(first)}`)?.focus()
      return
    }
    if (action.destructive) setConfirming(true)
    else void send(false)
  }

  const fieldError = (field: string) => problems.find((p) => p.field === field)?.message

  return (
    <>
      <Drawer onClose={onClose} width="max-w-xl" label={`${action.title}: ${plural(rows.length, noun)}`}>
        <OverlayHeader title={action.title} icon={action.destructive ? 'trash' : 'play'} subtitle={`${plural(n, noun)}, as one request in waves`} />
        <OverlayBody>
          <form
            id={`bulk-${action.verb}-form`}
            onSubmit={(e) => {
              e.preventDefault()
              submit()
            }}
            noValidate
          >
            <section aria-labelledby="bulk-rows-title" className="mb-6">
              <h3 id="bulk-rows-title" className="mb-1.5 text-caption font-medium text-secondary">
                The rows
              </h3>
              <p className="text-sm text-primary" data-bulk-rows={n}>
                {n === 0 ? 'Every selected row was left out.' : <>{kept.slice(0, 6).map(nameOf).join(', ')}{n > 6 ? `, and ${n - 6} more` : ''}.</>}
              </p>
              {left.size > 0 && (
                <p className="mt-1 text-caption text-secondary">
                  {plural(left.size, 'row')} left out.{' '}
                  <button type="button" className="font-medium text-accent hover:opacity-70" onClick={() => setLeft(new Set())}>
                    Put {left.size === 1 ? 'it' : 'them'} back
                  </button>
                </p>
              )}
              {n > max && <p className="mt-1 text-caption text-critical">{action.title} takes at most {max} rows in one request. Select fewer, or send them in turns.</p>}
            </section>

            {action.description && <p className="mb-4 text-sm text-secondary">{action.description}</p>}
            {Object.keys(schema.properties ?? {}).some((k) => !omit.includes(k)) && (
              <section aria-label="What every row is given" className="mb-6">
                <p className="mb-3 text-caption text-secondary">Every row is given what you fill in here.</p>
                <SchemaForm schema={schema} omit={omit} value={value} onChange={setValue} problems={problems} idPrefix={`bulk-${action.verb}`} />
              </section>
            )}
            {own.length > 0 && (
              <p className="mb-6 text-caption text-secondary" data-own-inputs={own.join(',')}>
                Each row keeps its own {own.map((k) => (action.input.properties?.[k]?.title ?? k).toLowerCase()).join(' and ')}.
              </p>
            )}

            <fieldset className="mb-6 space-y-4">
              <legend className="mb-2 text-caption font-medium text-secondary">How it rolls out</legend>
              <Segmented ariaLabel="Waves" value={preset} onChange={setPreset} options={PRESETS} full />
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="At once" htmlFor="bulk-maxParallel" hint="Rows running at the same time" error={fieldError('/rollout/maxParallel')}>
                  <Input id="bulk-maxParallel" inputMode="numeric" value={parallel} onChange={(e) => setParallel(e.target.value)} invalid={!!fieldError('/rollout/maxParallel')} />
                </Field>
                <Field label="Stop when more than this share fails (%)" htmlFor="bulk-stopIfFailedPercent" hint="0 stops at the first failure" error={fieldError('/rollout/stopIfFailedPercent')}>
                  <Input id="bulk-stopIfFailedPercent" inputMode="numeric" value={stop} onChange={(e) => setStop(e.target.value)} invalid={!!fieldError('/rollout/stopIfFailedPercent')} />
                </Field>
              </div>
              <p className="rounded-lg bg-surface-secondary px-3 py-2.5 text-sm text-primary" data-waves={planned.waves.map((w) => w.size).join(',')}>
                <span className="font-medium">{wavesLine(planned.waves)}</span>
                <span className="text-secondary">
                  : {planned.words}. A wave goes out once the one before it finished{policy === 'needs-approval' ? ' and an approver said yes to it' : ''}.
                </span>
              </p>
            </fieldset>

            {/* a workflow with a reason of its own takes it in its form; the request's reason would say it twice */}
            {!ownReason && (
              <Field label="Reason" htmlFor="bulk-reason" hint="Optional. It stays with the request and every row’s activity." error={fieldError('/reason')}>
                <Textarea id="bulk-reason" rows={2} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
              </Field>
            )}
          </form>

          <section aria-label="What will happen" className="mt-6">
            {!complete ? (
              <p className="rounded-lg border border-dashed border-stroke-light px-3 py-2.5 text-caption text-tertiary">{n === 0 ? 'Put a row back to see what this will do.' : 'Fill in the required fields to see what this will do to every row.'}</p>
            ) : preview.loading && !preview.data ? (
              <SkeletonRows n={2} rowClassName="h-14" />
            ) : preview.error ? (
              <FailureNote failure={explain(preview.error, 'Preview this bulk run')} compact onRetry={() => void preview.refetch()} />
            ) : judged ? (
              <div className="space-y-4">
                {unrunnable.length > 0 && (
                  <div className="rounded-lg border border-critical/30 bg-critical-tint/40 p-3 text-sm" data-unrunnable={unrunnable.length}>
                    <p className="font-medium text-primary">
                      {unrunnable.length === 1 ? 'One row cannot run as it is' : `${unrunnable.length} rows cannot run as they are`}
                    </p>
                    <ul className="mt-1.5 space-y-1 text-secondary">
                      {unrunnable.slice(0, 8).map((u) => (
                        <li key={u.wfrn}>
                          <span className="font-medium text-primary">{u.name}</span>: {u.reasons.join('; ')}
                        </li>
                      ))}
                      {unrunnable.length > 8 && <li>and {unrunnable.length - 8} more</li>}
                    </ul>
                    <Button size="sm" className="mt-3" icon="minus" onClick={() => setLeft(new Set([...left, ...unrunnable.map((u) => u.wfrn)]))}>
                      Leave out the {unrunnable.length === 1 ? 'one' : unrunnable.length} that cannot run
                    </Button>
                  </div>
                )}
                {affected && affected.total > 0 && <BlastRadiusCard radius={affected} noun={noun} name={these} verb={action.verb} compact />}
                <PolicyLines policy={judged.policy} hideDenials={unrunnable.length > 0} />
              </div>
            ) : null}
          </section>

          <FailureNote failure={cmd.failure} shownFields={fieldNames(schema)} onDismiss={cmd.clear} className="mt-5" />
        </OverlayBody>
        <OverlayFooter>
          <CancelButton />
          <ActionButton decision={decision} what={`${action.title} on ${these}`} verb={action.verb} variant={action.destructive ? 'danger' : 'primary'} icon={action.destructive ? 'trash' : 'play'} loading={cmd.busy} disabled={blocked || n === 0 || n > max} onClick={submit}>
            {`${action.title}: ${plural(n, noun)}`}
          </ActionButton>
        </OverlayFooter>
      </Drawer>
      {confirming && (
        <ConfirmDialog
          title={`${action.title}: ${plural(n, noun)}?`}
          tone="danger"
          icon="trash"
          requireText={confirmTextOf(action.verb, n)}
          confirmLabel={`${action.title}: ${plural(n, noun)}`}
          cancelLabel="Keep them"
          message={
            <>
              {action.workflow} runs on {these}, {wavesLine(planned.waves)}
              {policy === 'needs-approval' ? ', each wave once an approver says yes' : ''}. The console cannot undo it; their records and activity stay readable afterwards.
              {affected && affected.total > 0 && <span className="mt-1.5 block text-primary">{plural(affected.total, 'resource')} depend on them.</span>}
            </>
          }
          onConfirm={() => send(true)}
          onClose={() => setConfirming(false)}
        />
      )}
    </>
  )
}

/** The preview's policy in words: approvals asked for, and the refusals not already named row by row. */
function PolicyLines({ policy, hideDenials }: { policy: BatchPreview['policy']; hideDenials: boolean }) {
  const lines = (policy.rules ?? []).filter((r) => r.result === 'needs-approval' || (r.result === 'deny' && !hideDenials))
  if (policy.decision === 'allow' || lines.length === 0) {
    return (
      <p className="flex items-start gap-2 text-sm text-secondary">
        <Icon name="check" size={16} className="mt-0.5 shrink-0 text-healthy" />
        Policy allows every row.
      </p>
    )
  }
  return (
    <ul className="space-y-1.5 text-sm" aria-label="What policy says">
      {lines.map((r) => (
        <li key={r.id} className="flex items-start gap-2">
          <Icon name={r.result === 'deny' ? 'lock' : 'approval'} size={16} className={r.result === 'deny' ? 'mt-0.5 shrink-0 text-critical' : 'mt-0.5 shrink-0 text-warning'} />
          <span className="text-primary">{r.message}</span>
        </li>
      ))}
    </ul>
  )
}
