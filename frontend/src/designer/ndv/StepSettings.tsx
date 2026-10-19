/**
 * The Settings tab of a step: its id (renamed everywhere it is referenced), its name, the output
 * alias, the assertion on its output, retry, timeout, what happens on failure, notes, and the
 * compensation steps that undo it when a later step fails. A provider step also shows its action's
 * own fields, the ones every workflow calling the action shares (effect, retry policy, approval,
 * plan, what a delete removes, the action's own SLO, the provider's error classes): an edit there
 * goes into the provider's draft, not into the workflow.
 */
import { useMemo, useState } from 'react'
import { Button, cx, Icon, Input, Select, Switch, Tag, Textarea } from '@iaf/ui'
import { STEP_TYPES, type ExpressionContext, type JsonValue, type ParsedStep, type Problem, type StepBody, type StepDocument, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import type { Effect, RetryPolicy } from '../../lib/types'
import { Link, pathFor } from '../../app/router'
import { ActionSloInputs, ErrorClasses } from '../../components/provider-editor/ActionsStep'
import { EFFECTS, stateFromProvider, type ErrorRow, type WizardState } from '../../components/provider-editor/state'
import { bodyFor, newStepId, stepIdProblem } from '../model/edits'
import { stepTypeOfBody } from '../model/fixes'
import { useDesignerStore } from '../model/store'
import { NodeGlyph, typeGlyph } from '../canvas/glyphs'
import { readField, writeField } from './fields'
import { FieldLabel, JsonInput, ParameterField, ParamSection, ProblemList, type ParamSpec } from './ParameterField'
import { errorClassesText } from './providerFile'
import { actionRow, errorRows, openChange, planOptions, providerSlo, RETRY_OPTIONS, useDraftEdit, withErrorClasses, withPolicy, type ActionPolicy, type ProviderStepInfo } from './providerStep'

const COMPENSATION_TYPES: StepType[] = ['provider', 'http', 'notify', 'script', 'ansible', 'run']

function StepIdField({ stepId, doc, readOnly }: { stepId: string; doc: WorkflowDocument; readOnly: boolean }) {
  const store = useDesignerStore()
  const [draft, setDraft] = useState(stepId)
  const problem = draft === stepId ? null : stepIdProblem(doc, draft, stepId)
  const apply = () => {
    if (draft === stepId) return
    if (problem || !store.renameStepId(stepId, draft)) setDraft(stepId)
  }
  return (
    <div>
      <FieldLabel htmlFor="ndv-step-id" label="Step id" required description="How expressions and switch targets refer to the step; renaming it updates every reference" />
      <Input
        id="ndv-step-id"
        mono
        value={draft}
        readOnly={readOnly}
        invalid={problem !== null}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={apply}
        onKeyDown={(e) => {
          if (e.key === 'Enter') apply()
          if (e.key === 'Escape' && draft !== stepId) {
            e.stopPropagation()
            setDraft(stepId)
          }
        }}
        className="mt-1"
      />
      {problem && <p className="mt-1 text-caption text-critical">{problem}</p>}
    </div>
  )
}

function CompensateEditor({ stepId, body, doc, readOnly, problems }: { stepId: string; body: StepBody; doc: WorkflowDocument; readOnly: boolean; problems: readonly Problem[] }) {
  const store = useDesignerStore()
  const steps: StepDocument[] = Array.isArray(body.compensate) ? body.compensate : []
  const [open, setOpen] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const write = (next: StepDocument[]) => store.setCompensate(stepId, next.length > 0 ? next : null)
  const add = (type: StepType) => {
    const id = newStepId(doc, `undo ${stepId}`)
    write([...steps, { [id]: bodyFor(type, { name: `Undo ${body.name ?? stepId}` }) }])
    setOpen(id)
    setAdding(false)
  }
  return (
    <div className="space-y-2">
      <p className="text-caption text-tertiary">When a later step fails, these steps run (newest first across the job) to undo what this step did. They see this step's output as $json.</p>
      {steps.length === 0 && <p className="text-caption text-secondary">No compensation.</p>}
      <ul className="space-y-2">
        {steps.map((entry, i) => {
          const id = Object.keys(entry)[0] ?? `step${i}`
          const b = entry[id]
          const type = b ? stepTypeOfBody(b) : null
          const own = problems.filter((p) => p.step === id)
          return (
            <li key={`${id}-${i}`} className="rounded-md border border-dashed border-warning/60 bg-surface-tertiary">
              <div className="flex items-center gap-2 px-3 py-2">
                {type && <NodeGlyph glyph={typeGlyph(type)} size={16} />}
                <button type="button" onClick={() => setOpen(open === id ? null : id)} aria-expanded={open === id} className="min-w-0 flex-1 truncate text-left text-caption font-medium text-primary hover:underline">
                  {b?.name ?? id}
                  <span className="ml-2 font-mono text-caption2 text-tertiary">{id}</span>
                </button>
                {own.length > 0 && (
                  <span className={cx('text-caption2', own.some((p) => p.level === 'error') ? 'text-critical' : 'text-warning')}>
                    {own.length} {own.length === 1 ? 'problem' : 'problems'}
                  </span>
                )}
                {!readOnly && (
                  <button type="button" onClick={() => write(steps.filter((_, j) => j !== i))} aria-label={`Remove ${b?.name ?? id}`} title="Remove" className="rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                    <Icon name="trash" size={13} />
                  </button>
                )}
              </div>
              {open === id && b && (
                <div className="space-y-2 border-t border-stroke-light px-3 py-2">
                  <JsonInput
                    value={b as unknown as JsonValue}
                    rows={8}
                    ariaLabel={`Body of ${id}`}
                    readOnly={readOnly}
                    onChange={(v) => {
                      if (!v || typeof v !== 'object' || Array.isArray(v)) return
                      write(steps.map((e, j) => (j === i ? { [id]: v as unknown as StepBody } : e)))
                    }}
                  />
                  <ProblemList problems={own} />
                </div>
              )}
            </li>
          )
        })}
      </ul>
      {!readOnly &&
        (adding ? (
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Kind of compensation step">
            {COMPENSATION_TYPES.map((t) => (
              <Button key={t} size="sm" variant="secondary" onClick={() => add(t)}>
                {STEP_TYPES[t].title}
              </Button>
            ))}
            <Button size="sm" variant="ghost" onClick={() => setAdding(false)}>
              Cancel
            </Button>
          </div>
        ) : (
          <Button size="sm" variant="secondary" icon="plus" onClick={() => setAdding(true)}>
            Add compensation step
          </Button>
        ))}
    </div>
  )
}

/**
 * The provider's error classes in the shared action: the wizard's rows, edited into the draft
 * (its state, or its file by lines), for a draft written from its state and for a provider file
 * that writes them one per line as the designer does (the repository's files do); otherwise, and
 * where the step cannot change, the classes as the file writes them.
 */
function SharedErrorClasses({ info, disabled, onChange }: { info: ProviderStepInfo; disabled: boolean; onChange: (errors: ErrorRow[]) => void }) {
  const published = info.detail?.yaml
  const file = info.draft ? info.draft.draftYaml : (published ?? null)
  // read again only when the draft or the published file changes, so a row keeps its key (and its focus) while it is typed in
  const rows = useMemo(() => errorRows(info.draft, published), [info.draft, published])
  const base = useMemo<WizardState | null>(() => info.draft ?? (info.detail ? stateFromProvider(info.detail) : null), [info.draft, info.detail])
  if (!disabled && rows !== null && base) return <ErrorClasses state={{ ...base, errors: rows }} update={(patch: Partial<WizardState>) => onChange(patch.errors ?? rows)} />
  const text = rows !== null ? errorClassesText(rows) : file ? errorClassesText(file) : ''
  return (
    <>
      <FieldLabel label="Error classes" description="How a failed call of the provider is named, so retries and alerts know what it means" />
      {/* nothing to say while the published file loads: the section says so above */}
      {text ? <pre className="mt-1 overflow-x-auto rounded-sm bg-surface-secondary px-2 py-1.5 font-mono text-caption2 text-secondary">{text}</pre> : file !== null || info.draft ? <p className="text-caption text-tertiary">None.</p> : null}
      {!disabled && rows === null && file && <p className="mt-1 text-caption text-tertiary">The provider file writes them in a way the designer does not edit; open the provider to change them.</p>}
    </>
  )
}

/**
 * A provider step's action as every workflow calling it shares it. Its fields are the provider's,
 * so an edit goes into the provider's draft (started from the published file when there is none)
 * and reaches every workflow once the provider's change request merges; the step's own retry,
 * timeout and pause stay above. Error classes are the provider's too (`SharedErrorClasses`). An
 * open change request of the provider is named, since the draft cannot be published before it
 * merges or closes.
 */
export function SharedAction({ info, stepId, readOnly = false }: { info: ProviderStepInfo; stepId: string; readOnly?: boolean }) {
  const store = useDesignerStore()
  const edit = useDraftEdit(info)
  const row = useMemo(() => actionRow(info), [info])
  const slo = providerSlo(info)
  const plans = planOptions(info)
  const waiting = !info.draft && info.published && !info.detail
  const disabled = readOnly || waiting || !row
  const title = info.draft?.title || info.detail?.title || info.registryProvider?.title || info.provider
  const set = (patch: Partial<ActionPolicy>) => void edit((d) => withPolicy(d, info.action, patch, row), `Could not change ${info.action}`)
  const change = openChange(info)
  const openProvider = () => store.openProviderSheet({ entry: 'node', provider: info.published ? info.provider : null, draft: info.draft?.id ?? null, target: { kind: 'step', step: stepId } })
  if (info.state === 'unknown') {
    return (
      <ParamSection title="Action (shared by every workflow)">
        <p className="text-caption text-tertiary" data-testid="shared-action-unknown">
          {info.provider} is not a registered provider, and no draft of it is kept in this browser; pick a provider in Parameters.
        </p>
      </ParamSection>
    )
  }
  return (
    <ParamSection
      title="Action (shared by every workflow)"
      actions={
        !readOnly && (
          <Button size="sm" variant="ghost" icon="edit" onClick={openProvider}>
            Open provider
          </Button>
        )
      }
    >
      <div className="rounded-md border border-warning/30 bg-warning-tint px-3 py-2 text-caption text-secondary" data-testid="shared-action-note">
        <p className="flex flex-wrap items-center gap-1.5">
          <Icon name="alert-triangle" size={12} className="text-warning" />
          <span className="font-medium text-primary">
            {info.action || 'This action'} of {title}
          </span>
          {info.draft ? (
            <Tag size="sm" tone="accent">
              draft {info.draft.version}
            </Tag>
          ) : info.detail ? (
            <Tag size="sm" outline>
              published {info.detail.version}
            </Tag>
          ) : null}
        </p>
        <p className="mt-1">
          These fields belong to the provider, not to this step: a change here changes {info.action || 'the action'} for every workflow that calls it, once the provider’s change request merges.{' '}
          {info.draft ? 'It goes into the provider’s draft.' : info.published ? 'The first change starts a draft of the provider’s next version.' : ''}
        </p>
        {change && (
          <p className="mt-1" data-testid="shared-action-open-change">
            <Link to={pathFor('change-request', { id: change.id })} className="font-mono text-accent hover:underline">
              {change.id}
            </Link>{' '}
            ({change.version}) is still open: {info.provider} takes no other change until it merges or closes, so {info.draft ? 'the draft' : 'a draft started here'} can be published only after that.
          </p>
        )}
      </div>
      {waiting && <p className="text-caption text-tertiary">{info.detailError ? `Could not load ${info.provider}; its action cannot be changed here.` : `Loading ${info.provider}…`}</p>}
      {!waiting && !row && <p className="text-caption text-tertiary">{info.provider} has no action {info.action || 'named'} to change.</p>}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <FieldLabel label="Effect" description="What the action does to the system: read, create, change or delete. A delete asks for an approval." />
          <Select ariaLabel="Effect of the action" value={row?.effect ?? 'read'} onChange={(v) => set({ effect: v as Effect })} options={EFFECTS.map((e) => ({ value: e, label: e }))} disabled={disabled} size="sm" />
        </div>
        <div>
          <FieldLabel label="Retry policy" description="Whether a failed call may run again on its own: safe may run twice without harm, unsafe needs a person" />
          <Select ariaLabel="Retry policy of the action" value={row?.retry ?? 'safe'} onChange={(v) => set({ retry: v as RetryPolicy })} options={RETRY_OPTIONS} disabled={disabled} size="sm" />
        </div>
      </div>
      <div className="flex items-center justify-between gap-4">
        <FieldLabel label="Needs an approval" description="A person signs off before the action runs, in every workflow; a delete defaults to this" />
        <Switch checked={row?.approval ?? false} label="Needs an approval" disabled={disabled} onChange={(on) => set({ approval: on })} />
      </div>
      <div>
        <FieldLabel label="Plan via" description="Another action of the provider that previews this one before it runs" />
        <Select
          ariaLabel="Plan step of the action"
          value={row?.plan ?? ''}
          onChange={(v) => set({ plan: v })}
          options={[{ value: '', label: 'No plan step' }, ...plans.map((id) => ({ value: id, label: id }))]}
          disabled={disabled || row?.effect === 'read'}
          mono
          size="sm"
        />
      </div>
      {row?.effect === 'delete' && (
        <div>
          <FieldLabel htmlFor="ndv-effect-detail" label="What it removes" description="The line the preview before a run shows when no plan step reads the target" />
          <Input id="ndv-effect-detail" size="sm" value={row.effectDetail} placeholder={row.plan ? `Previewed by ${row.plan}; a line here is optional` : 'The VM and its disks'} disabled={disabled} onChange={(e) => set({ effectDetail: e.target.value })} className="mt-1" />
        </div>
      )}
      <div>
        <FieldLabel label="Own SLO" description="The action's calls are judged against it; a field left empty takes the provider's" />
        {row ? <ActionSloInputs action={row} provider={slo} label={info.action} size="sm" disabled={disabled} onChange={(p) => set(p)} /> : <p className="text-caption text-tertiary">None</p>}
      </div>
      <div data-testid="shared-error-classes">
        <SharedErrorClasses info={info} disabled={readOnly || waiting} onChange={(errors) => void edit((d) => withErrorClasses(d, errors), 'Could not change the error classes')} />
      </div>
    </ParamSection>
  )
}

export function StepSettings({ stepId, body, step, doc, ownCtx, allProblems, readOnly = false, provider }: { stepId: string; body: StepBody; step: ParsedStep; doc: WorkflowDocument; ownCtx: ExpressionContext; allProblems: readonly Problem[]; readOnly?: boolean; provider?: ProviderStepInfo | null }) {
  const store = useDesignerStore()
  const get = (field: string) => readField(body, field)
  const set = (field: string, v: JsonValue | undefined) => store.updateStep(stepId, (b) => writeField(b, field, v), `setting:${stepId}:${field}`)
  const field = (spec: ParamSpec) => <ParameterField key={spec.field} spec={spec} value={get(spec.field)} onChange={(v) => set(spec.field, v)} readOnly={readOnly} />
  const retry = body.retry
  const pause = body.pauseOnFailure !== undefined ? body.pauseOnFailure : step.pauseOnFailure
  const container = step.type === 'switch' || step.type === 'fork' || step.type === 'for' || step.type === 'try'
  return (
    <div className="space-y-6">
      <ParamSection title="Step">
        {/* keyed on the id: a rename starts a fresh draft */}
        <StepIdField key={stepId} stepId={stepId} doc={doc} readOnly={readOnly} />
        <div>
          <FieldLabel htmlFor="ndv-step-name" label="Name" description="What the canvas, the job page and $('Name') expressions show" />
          <Input id="ndv-step-name" value={body.name ?? ''} placeholder={stepId} readOnly={readOnly} onChange={(e) => store.setStepName(stepId, e.target.value)} className="mt-1" />
        </div>
        {!container && field({ field: 'output.as', label: 'Output alias', type: 'string', mono: true, expression: false, placeholder: 'vm', description: 'Later steps read the output as $alias' })}
      </ParamSection>
      {!container && (
        <ParamSection title="Output">
          {field({ field: 'assert', label: 'Assert', type: 'string', placeholder: '{{ $json.items.length == 0 }}', description: 'The step fails unless this holds; $json is the step’s own output', ctx: ownCtx })}
        </ParamSection>
      )}
      <ParamSection title="On failure">
        <div className="flex items-center justify-between gap-4">
          <div>
            <FieldLabel label="Retry" description="Run the step again when it fails" />
          </div>
          <Switch
            checked={retry !== undefined}
            label={retry !== undefined ? 'On' : 'Off'}
            disabled={readOnly}
            onChange={(on) =>
              store.updateStep(stepId, (b) => {
                if (on) b.retry = { maxAttempts: 3, backoff: 'exponential' }
                else delete b.retry
              })
            }
          />
        </div>
        {retry !== undefined && (
          <div className="grid grid-cols-2 gap-3">
            {field({ field: 'retry.maxAttempts', label: 'Max attempts', type: 'number', expression: false })}
            {field({ field: 'retry.backoff', label: 'Backoff', type: 'enum', expression: false, options: ['none', 'fixed', 'exponential'].map((b) => ({ value: b, label: b })) })}
          </div>
        )}
        {field({ field: 'timeout', label: 'Timeout', type: 'string', mono: true, expression: false, placeholder: '30m', description: 'A duration like 30m, 4h or PT30M' })}
        <div className="flex items-center justify-between gap-4">
          <FieldLabel label="Pause on failure" description="When the step fails, the job waits for someone to retry, skip or abort instead of failing" />
          <Switch checked={pause} label={pause ? 'On' : 'Off'} disabled={readOnly} onChange={(on) => store.updateStep(stepId, (b) => void (b.pauseOnFailure = on))} />
        </div>
        {pause &&
          field({
            field: 'attentionTimeout',
            label: 'Decision timeout',
            type: 'string',
            mono: true,
            expression: false,
            placeholder: 'P7D',
            description: 'How long the paused step waits for someone before it aborts: a duration like 4h or P3D; seven days when empty',
          })}
      </ParamSection>
      {provider && <SharedAction info={provider} stepId={stepId} readOnly={readOnly} />}
      {!container && (
        <ParamSection title="Compensation">
          <CompensateEditor stepId={stepId} body={body} doc={doc} readOnly={readOnly} problems={allProblems} />
        </ParamSection>
      )}
      <ParamSection title="Notes">
        <Textarea rows={3} value={body.notes ?? ''} placeholder="Notes for whoever edits this step next" aria-label="Notes" readOnly={readOnly} onChange={(e) => store.updateStep(stepId, (b) => (e.target.value ? void (b.notes = e.target.value) : void delete b.notes), `setting:${stepId}:notes`)} />
      </ParamSection>
    </div>
  )
}
