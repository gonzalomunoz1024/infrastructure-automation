import { useState } from 'react'
import { Button, Card, Checkbox, EmptyState, Field, Icon, IconButton, Input, Select, Table, cx, type TableColumn } from '@iaf/ui'
import type { Effect, RetryPolicy } from '../../lib/types'
import { JsonEditor } from '../JsonEditor'
import { EffectTag, Mono } from '../common'
import { CLASS_RE, EFFECTS, ID_RE, METHODS, STATUS_RE, USUAL_ERRORS, actionSloIssues, blankAction, blankError, blankRow, matchesOperation, renameAction, type ActionRow, type ErrorRow, type StepProps, type WizardState } from './state'

/* ---- 3 · Actions --------------------------------------------------------- */

export function ActionsStep({ state, update }: StepProps) {
  // a spec can have hundreds of operations: the search narrows the table, and "select all" takes what it shows
  const [query, setQuery] = useState('')
  const patch = (key: number, p: Partial<ActionRow>) => update({ actions: state.actions.map((a) => (a.key === key ? { ...a, ...p } : a)) })
  const rename = (key: number, id: string) => update({ actions: renameAction(state.actions, key, id) })
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
    const shown = state.actions.filter((a) => matchesOperation(a, query))
    const shownKeys = new Set(shown.map((a) => a.key))
    const searching = query.trim() !== ''
    const allOn = shown.length > 0 && shown.every((a) => a.selected)
    const columns: TableColumn<ActionRow>[] = [
      {
        key: 'on',
        header: <Checkbox checked={allOn} onChange={(v) => update({ actions: state.actions.map((a) => (shownKeys.has(a.key) ? { ...a, selected: v } : a)) })} label={<span className="sr-only">{searching ? 'Select every operation shown' : 'Select all'}</span>} />,
        width: '2.5rem',
        render: (a) => <Checkbox checked={a.selected} onChange={(v) => patch(a.key, { selected: v })} label={<span className="sr-only">Include {a.operation}</span>} />,
      },
      { key: 'id', header: 'Action id', width: '12rem', render: (a) => <Input size="sm" mono value={a.id} onChange={(e) => rename(a.key, e.target.value)} aria-label={`Id of ${a.operation}`} invalid={a.selected && !ID_RE.test(a.id)} disabled={!a.selected} /> },
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
      {
        key: 'slo',
        header: <span title="An SLO of the action's own, which its calls are judged against; empty takes the provider's">Own SLO</span>,
        width: '11rem',
        render: (a) => <ActionSloInputs action={a} provider={state.slo} label={a.operation} size="sm" disabled={!a.selected} onChange={(p) => patch(a.key, p)} />,
      },
    ]
    return (
      <div className="space-y-4">
        <Card title="Actions" subtitle={state.actions.length ? `${selected.length} of ${state.actions.length} operations become actions. Effect and retry decide what a workflow may do with them.` : undefined} flush>
          {state.actions.length === 0 ? (
            <EmptyState icon="document" title="No operations yet" message="Go back to Connect and import the spec; its operations appear here." />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 pb-3">
                <div className="w-full sm:max-w-xs">
                  <Input leading="search" size="sm" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search operations" aria-label="Search operations" />
                </div>
                {searching && (
                  <span className="text-caption text-secondary" role="status">
                    {shown.length} of {state.actions.length} shown; the hidden ones keep their selection
                  </span>
                )}
              </div>
              <Table
                columns={columns}
                rows={shown}
                rowKey={(a) => String(a.key)}
                dense
                bordered={false}
                maxHeight="max-h-[32rem]"
                caption="Operations"
                emptyIcon="search"
                emptyTitle="No operation matches"
                emptyMessage={`None of the ${state.actions.length} operations has "${query.trim()}" in its id, method, path or summary.`}
              />
            </>
          )}
        </Card>
        <DeletePreviews actions={state.actions} onChange={(key, effectDetail) => patch(key, { effectDetail })} />
        <ErrorClasses state={state} update={update} />
      </div>
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
              <Input id={`act-${a.key}-id`} mono value={a.id} onChange={(e) => rename(a.key, e.target.value)} placeholder="jobs.create" aria-label="Action id" />
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
            {a.effect === 'delete' && (
              <Field label="What it removes" htmlFor={`act-${a.key}-detail`} hint="The preview's line when no plan step reads the target" className="sm:col-span-2">
                <Input id={`act-${a.key}-detail`} value={a.effectDetail} onChange={(e) => patch(a.key, { effectDetail: e.target.value })} placeholder="The backup and its snapshots" aria-label={`What ${a.id || `action ${i + 1}`} removes`} />
              </Field>
            )}
            <Field label="Own SLO" hint="Its calls are judged against it; empty takes the provider's">
              <ActionSloInputs action={a} provider={state.slo} label={a.id || `action ${i + 1}`} onChange={(p) => patch(a.key, p)} />
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
      <ErrorClasses state={state} update={update} />
    </div>
  )
}

/** An action's own SLO: a success rate and a p95, each left empty to take the provider's (shown as the placeholder). */
export function ActionSloInputs({ action, provider, label, size = 'md', disabled, onChange }: { action: ActionRow; provider: WizardState['slo']; label: string; size?: 'sm' | 'md'; disabled?: boolean; onChange: (p: Partial<ActionRow>) => void }) {
  const unit = (u: string) => <span className="text-caption2 text-tertiary">{u}</span>
  return (
    <div className="grid grid-cols-2 gap-1.5">
      <Input
        size={size}
        mono
        inputMode="decimal"
        value={action.sloSuccessRate}
        onChange={(e) => onChange({ sloSuccessRate: e.target.value })}
        placeholder={provider.successRate.trim() || '99'}
        aria-label={`Success rate SLO of ${label}, percent`}
        invalid={!!action.sloSuccessRate.trim() && actionSloIssues('', { sloSuccessRate: action.sloSuccessRate, sloP95Ms: '' }).length > 0}
        disabled={disabled}
        trailing={unit('%')}
      />
      <Input
        size={size}
        mono
        inputMode="numeric"
        value={action.sloP95Ms}
        onChange={(e) => onChange({ sloP95Ms: e.target.value })}
        placeholder={provider.p95Ms.trim() || '1000'}
        aria-label={`p95 SLO of ${label}, milliseconds`}
        invalid={!!action.sloP95Ms.trim() && actionSloIssues('', { sloSuccessRate: '', sloP95Ms: action.sloP95Ms }).length > 0}
        disabled={disabled}
        trailing={unit('ms')}
      />
    </div>
  )
}

/** What the preview before a run says each delete removes: its plan step reads the target for real; without one, a line written here. */
export function DeletePreviews({ actions, onChange }: { actions: readonly ActionRow[]; onChange: (key: number, effectDetail: string) => void }) {
  const deletes = actions.filter((a) => a.selected && a.effect === 'delete')
  if (deletes.length === 0) return null
  return (
    <Card title="What a delete removes" subtitle="The preview before a run says it. A plan step that reads the target (Plan via, above) shows it for real; without one, say it in one line.">
      <ul className="space-y-2" aria-label="Delete previews">
        {deletes.map((a) => (
          <li key={a.key} className="grid gap-1.5 sm:grid-cols-[12rem_1fr] sm:items-center">
            <Mono className="truncate text-primary">{a.id || a.operation}</Mono>
            <Input size="sm" value={a.effectDetail} onChange={(e) => onChange(a.key, e.target.value)} placeholder={a.plan ? `Previewed by ${a.plan}; a line here is optional` : 'The VM and its disks'} aria-label={`What ${a.id || a.operation} removes`} />
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** `spec.errors`: what a failed call is called and whether it is retried, matched by status and body text. */
export function ErrorClasses({ state, update }: StepProps) {
  const http = state.type === 'openapi' || state.type === 'http'
  const set = (key: number, p: Partial<ErrorRow>) => update({ errors: state.errors.map((e) => (e.key === key ? { ...e, ...p } : e)) })
  const usual = USUAL_ERRORS.filter((u) => !state.errors.some((e) => e.status.trim() === String(u.status) && !e.body.trim()))
  const addUsual = () => update({ errors: [...state.errors.filter((e) => !blankRow(e)), ...usual.map((u) => blankError({ status: String(u.status), class: u.class, retry: u.retry }))] })
  const grid = 'grid grid-cols-2 gap-2 sm:grid-cols-[5.5rem_minmax(0,1fr)_minmax(0,11rem)_4.5rem_2rem] sm:items-center'

  return (
    <Card
      title="Error classes"
      subtitle={
        http
          ? 'How a failed call is named, so retries and alerts know what it means. A call is matched by its status, then by text its body contains; the first class that matches wins, and a call none matches is named by its status alone.'
          : 'How a failure is named, so retries and alerts know what it means. A class matches a status, text the answer contains, or both; the first that matches wins.'
      }
      data-testid="error-classes"
    >
      {state.errors.length === 0 ? (
        <p className="text-caption text-tertiary">None yet. The review asks for them: start from the usual ones, then name what this system really answers.</p>
      ) : (
        <>
          <div className={cx(grid, 'mb-1.5 hidden text-caption2 font-medium text-tertiary sm:grid')} aria-hidden="true">
            <span>Status</span>
            <span>Body contains</span>
            <span>Class</span>
            <span>Retry</span>
            <span />
          </div>
          <ul className="space-y-2" aria-label="Error classes">
            {state.errors.map((e) => (
              <li key={e.key} className={cx(grid, 'border-b border-stroke-light pb-2 last:border-b-0 last:pb-0 sm:border-b-0 sm:pb-0')}>
                <Input size="sm" mono inputMode="numeric" value={e.status} onChange={(ev) => set(e.key, { status: ev.target.value })} placeholder="429" aria-label="Status" invalid={!!e.status.trim() && !STATUS_RE.test(e.status.trim())} />
                <Input size="sm" mono value={e.body} onChange={(ev) => set(e.key, { body: ev.target.value })} placeholder={http ? 'any body' : 'any text'} aria-label="Body contains" />
                <Input size="sm" mono value={e.class} onChange={(ev) => set(e.key, { class: ev.target.value })} placeholder="rate_limited" aria-label="Class" invalid={!!e.class.trim() && !CLASS_RE.test(e.class.trim())} />
                <div className="flex items-center justify-between gap-2 sm:contents">
                  <Checkbox checked={e.retry} onChange={(v) => set(e.key, { retry: v })} label={<span className="sm:sr-only">Retry</span>} />
                  <IconButton label="Remove error class" size="sm" onClick={() => update({ errors: state.errors.filter((x) => x.key !== e.key) })}>
                    <Icon name="trash" size={14} />
                  </IconButton>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <Button size="sm" icon="plus" onClick={() => update({ errors: [...state.errors, blankError()] })}>
          Add error class
        </Button>
        {usual.length > 0 && (
          <Button size="sm" variant="ghost" onClick={addUsual} title={usual.map((u) => `${u.status} ${u.class}${u.retry ? ', retried' : ''}`).join(' · ')}>
            {state.errors.length ? 'Add the usual ones' : 'Start from the usual ones'}
          </Button>
        )}
      </div>
    </Card>
  )
}
