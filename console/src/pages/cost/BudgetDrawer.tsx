import { useRef, useState, type FormEvent } from 'react'
import { Button, Checkbox, Drawer, Field, Icon, IconButton, Input, OverlayBody, OverlayFooter, OverlayHeader, Segmented, useToast } from '@iaf/ui'
import { problemsFor, type Problem } from '@iaf/console-kit'
import { FailureNote } from '../../app/commands'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { navigate, pathFor } from '../../app/router'
import { isApiError } from '../../lib/api'
import { idempotencyKey } from '../../lib/bff'
import { DEFAULT_THRESHOLDS, costApi, type BudgetInput, type ConsoleBudget } from '../../lib/cost'
import { explain } from '../../lib/plain'
import { invalidate } from '../../lib/queries'
import { BUDGETS_KEY } from './alerts'
import { ESTIMATES } from './common'
import { money, parseAmount, scopeText, thresholdProblem } from './model'

/*
 * Creating and changing a budget (WP 9.3), in a drawer as the AWS console does it: a name, a
 * monthly amount in the price lists' currency, the whole account or one tag within it, up to five
 * alert thresholds (80% and 100% to start with), and whether to refuse creates while the budget
 * is over. The form checks what it can before sending; the cost service's own problems land on
 * the fields they name. A create carries one Idempotency-Key per submission, kept across retries
 * of the same body; a change carries the version it was read at, and when someone changed the
 * budget first the drawer says so and loads their version.
 */

const FIELDS = ['name', 'amount', 'scope', 'thresholds', 'blockCreates']
/** the request's field each part of the form fills */
const FIELD_OF: Record<keyof Draft, string> = { name: 'name', amount: 'amount', scope: 'scope', tagKey: 'scope', tagValue: 'scope', thresholds: 'thresholds', blockCreates: 'blockCreates' }
const TAG_KEY = /^[a-z0-9-]{1,32}$/
const MAX_THRESHOLDS = 5

interface Draft {
  name: string
  amount: string
  scope: 'account' | 'tag'
  tagKey: string
  tagValue: string
  thresholds: string[]
  blockCreates: boolean
}

function draftOf(b?: ConsoleBudget): Draft {
  if (!b) return { name: '', amount: '', scope: 'account', tagKey: 'environment', tagValue: '', thresholds: DEFAULT_THRESHOLDS.map(String), blockCreates: false }
  return { name: b.name, amount: b.amount.toFixed(2), scope: b.scope.tag ? 'tag' : 'account', tagKey: b.scope.tag?.key ?? '', tagValue: b.scope.tag?.value ?? '', thresholds: b.thresholds.map(String), blockCreates: b.blockCreates }
}

/** What the form itself finds wrong, by field. */
function checkDraft(d: Draft, editing: boolean): Record<string, string> {
  const out: Record<string, string> = {}
  const name = d.name.trim()
  if (!name) out.name = 'A budget needs a name.'
  else if (name.length > 64) out.name = 'At most 64 characters.'
  if (parseAmount(d.amount) === null) out.amount = 'An amount above 0 and at most 1,000,000,000, with at most two decimals.'
  if (!editing && d.scope === 'tag') {
    if (!TAG_KEY.test(d.tagKey.trim())) out.tagKey = 'A tag key: 1 to 32 lower-case letters, digits and dashes.'
    if (!d.tagValue.trim() || d.tagValue.trim().length > 256) out.tagValue = 'A tag value of 1 to 256 characters.'
  }
  const t = thresholdProblem(d.thresholds)
  if (t) out.thresholds = t
  return out
}

function bodyOf(d: Draft, accountId: string, editing: boolean): BudgetInput {
  const thresholds = d.thresholds
    .map((t) => t.trim())
    .filter(Boolean)
    .map(Number)
    .sort((a, b) => a - b)
  const body: BudgetInput = { name: d.name.trim(), amount: parseAmount(d.amount) ?? 0, thresholds, blockCreates: d.blockCreates }
  if (!editing) body.scope = { accountId, ...(d.scope === 'tag' ? { tag: { key: d.tagKey.trim(), value: d.tagValue.trim() } } : {}) }
  return body
}

const messageOf = (problems: Problem[]) => problems.map((p) => p.message).join(' ') || undefined

export interface BudgetDrawerProps {
  /** the budget to change; a new one when not given */
  budget?: ConsoleBudget
  onClose: () => void
}

export function BudgetDrawer({ budget, onClose }: BudgetDrawerProps) {
  const { account } = useConsole()
  const refusal = useRefusal()
  const toast = useToast()
  const editing = !!budget
  const what = editing ? 'Edit budget' : 'Create budget'
  const [draft, setDraft] = useState<Draft>(() => draftOf(budget))
  const [version, setVersion] = useState(budget?.version ?? 0)
  const [tried, setTried] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const [conflict, setConflict] = useState<'changed' | 'reloaded' | null>(null)
  // the fields changed since the cost service last answered: its problems with them are out of date
  const [edited, setEdited] = useState<ReadonlySet<string>>(() => new Set())
  // one key per submission: a retry of the same body reuses it, a changed body gets a new one
  const sent = useRef<{ key: string; body: string } | null>(null)

  const local = tried ? checkDraft(draft, editing) : {}
  const server = isApiError(failure) && failure.status === 400 ? (failure.problems ?? []) : []
  const serverError = (field: string) => (edited.has(field) ? undefined : messageOf(problemsFor(server, field)))
  const fieldError = (field: string) => local[field] ?? serverError(field)
  const scopeProblem = serverError('scope')
  const touch = (fields: string[]) => setEdited((e) => new Set([...e, ...fields]))
  const set = (patch: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...patch }))
    touch(Object.keys(patch).map((k) => FIELD_OF[k as keyof Draft]))
  }
  const setThreshold = (i: number, v: string) => {
    setDraft((d) => ({ ...d, thresholds: d.thresholds.map((t, j) => (j === i ? v : t)) }))
    touch(['thresholds'])
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (busy || Object.keys(checkDraft(draft, editing)).length) return
    const body = bodyOf(draft, account.id, editing)
    setBusy(true)
    setFailure(null)
    setEdited(new Set())
    try {
      if (budget) {
        const saved = await costApi.budgets.update(budget.budgetId, body, version)
        invalidate(BUDGETS_KEY)
        toast.success('Budget saved', `${saved.name}: ${money(saved.amount, saved.currency)} a month.`)
        onClose()
      } else {
        const text = JSON.stringify(body)
        if (!sent.current || sent.current.body !== text) sent.current = { key: idempotencyKey(), body: text }
        const created = await costApi.budgets.create(body, sent.current.key)
        invalidate(BUDGETS_KEY)
        toast.success('Budget created', `${created.name}: ${money(created.amount, created.currency)} a month.`)
        onClose()
        navigate(pathFor('budget', { id: created.budgetId }))
      }
    } catch (err) {
      if (refusal.showError(err, what)) return
      if (isApiError(err) && err.status === 409 && err.error === 'version.conflict') setConflict('changed')
      else setFailure(err)
    } finally {
      setBusy(false)
    }
  }

  const reload = async () => {
    if (!budget) return
    setBusy(true)
    try {
      const fresh = await costApi.budgets.get(budget.budgetId)
      setVersion(fresh.version)
      setDraft(draftOf(fresh))
      setConflict('reloaded')
      setFailure(null)
      invalidate(BUDGETS_KEY)
    } catch (err) {
      setFailure(err)
    } finally {
      setBusy(false)
    }
  }

  const unfielded = server.filter((p) => !FIELDS.some((f) => problemsFor([p], f).length))
  const failureNote = failure && !(isApiError(failure) && failure.status === 400 && !unfielded.length) ? explain(failure, what) : null

  return (
    <Drawer onClose={onClose} width="max-w-lg" label={what}>
      <form onSubmit={(e) => void submit(e)} className="flex h-full min-h-0 flex-col" noValidate>
        <OverlayHeader title={editing ? `Edit ${budget.name}` : 'Create budget'} subtitle={`A monthly amount for ${account.name} or a tag within it. ${ESTIMATES}`} icon="bell" />
        <OverlayBody className="space-y-6">
          {conflict === 'changed' && (
            <div role="alert" className="rounded-lg border border-warning/40 bg-warning-tint/40 p-4 text-sm">
              <div className="flex items-start gap-3">
                <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-warning" />
                <div className="min-w-0 flex-1 space-y-1">
                  <p className="font-semibold text-primary">Someone changed this budget first</p>
                  <p className="text-secondary">Your changes were not saved. Reload to see the budget as it is now, then make your change again.</p>
                </div>
                <Button size="sm" icon="refresh" onClick={() => void reload()} loading={busy}>
                  Reload
                </Button>
              </div>
            </div>
          )}
          {conflict === 'reloaded' && (
            <p role="status" className="rounded-lg bg-surface-secondary p-3 text-sm text-secondary">
              This is the budget as it is now. Make your change again and save.
            </p>
          )}
          {failureNote && <FailureNote failure={failureNote} shownFields={FIELDS} compact />}

          <Field label="Name" htmlFor="budget-name" required error={fieldError('name')}>
            <Input id="budget-name" data-autofocus value={draft.name} maxLength={64} invalid={!!fieldError('name')} onChange={(e) => set({ name: e.target.value })} placeholder="Production" />
          </Field>

          <Field label="Monthly amount" htmlFor="budget-amount" required hint="In US dollars, compared each month with what the scope costs by the price lists." error={fieldError('amount')}>
            <Input id="budget-amount" inputMode="decimal" value={draft.amount} invalid={!!fieldError('amount')} onChange={(e) => set({ amount: e.target.value })} placeholder="5000.00" trailing={<span className="pr-1 text-caption text-tertiary">USD</span>} />
          </Field>

          <fieldset className="space-y-3">
            <legend className="text-caption font-medium text-secondary">What counts</legend>
            {editing ? (
              <p className="text-sm text-primary">
                {scopeText(budget.scope, account.name)}
                <span className="mt-0.5 block text-caption text-secondary">The scope never changes; create another budget for another scope.</span>
              </p>
            ) : (
              <>
                <Segmented<'account' | 'tag'>
                  size="sm"
                  value={draft.scope}
                  onChange={(scope) => set({ scope })}
                  ariaLabel={`What counts: ${draft.scope === 'tag' ? 'Resources with a tag' : `All of ${account.name}`}`}
                  options={[
                    { value: 'account', label: `All of ${account.name}` },
                    { value: 'tag', label: 'Resources with a tag' },
                  ]}
                />
                {draft.scope === 'tag' && (
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Tag key" htmlFor="budget-tag-key" error={local.tagKey}>
                      <Input id="budget-tag-key" mono value={draft.tagKey} invalid={!!local.tagKey} onChange={(e) => set({ tagKey: e.target.value.toLowerCase() })} placeholder="environment" />
                    </Field>
                    <Field label="Tag value" htmlFor="budget-tag-value" error={local.tagValue}>
                      <Input id="budget-tag-value" mono value={draft.tagValue} invalid={!!local.tagValue} onChange={(e) => set({ tagValue: e.target.value })} placeholder="prod" />
                    </Field>
                  </div>
                )}
                {scopeProblem && (
                  <p className="text-caption2 text-critical" role="alert">
                    {scopeProblem}
                  </p>
                )}
              </>
            )}
          </fieldset>

          <fieldset className="space-y-2">
            <legend className="text-caption font-medium text-secondary">Alert thresholds</legend>
            <p className="text-caption2 text-tertiary">Percent of the amount. Each alerts once a month, when spending crosses it.</p>
            <ul className="space-y-2">
              {draft.thresholds.map((t, i) => (
                <li key={i} className="flex items-center gap-2">
                  <div className="w-36">
                    <Input aria-label={`Threshold ${i + 1}, percent of the amount`} inputMode="numeric" value={t} invalid={!!fieldError('thresholds')} onChange={(e) => setThreshold(i, e.target.value)} trailing={<span className="pr-1 text-caption text-tertiary">%</span>} />
                  </div>
                  <IconButton label={`Remove threshold ${i + 1}`} size="sm" disabled={draft.thresholds.length <= 1} onClick={() => set({ thresholds: draft.thresholds.filter((_, j) => j !== i) })}>
                    <Icon name="trash" size={14} />
                  </IconButton>
                </li>
              ))}
            </ul>
            <Button size="sm" icon="plus" variant="ghost" disabled={draft.thresholds.length >= MAX_THRESHOLDS} onClick={() => set({ thresholds: [...draft.thresholds, ''] })}>
              Add threshold
            </Button>
            {fieldError('thresholds') && (
              <p className="text-caption2 text-critical" role="alert">
                {fieldError('thresholds')}
              </p>
            )}
          </fieldset>

          <Checkbox
            checked={draft.blockCreates}
            onChange={(blockCreates) => set({ blockCreates })}
            label="Block creates while over"
            description="Once this month has spent more than the amount, a new resource in this budget's scope is refused, and its review names this budget. Changes to resources that exist go on, and the block lifts when the month turns or the amount is raised."
          />
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy} disabled={conflict === 'changed'}>
            {editing ? 'Save changes' : 'Create budget'}
          </Button>
        </OverlayFooter>
      </form>
    </Drawer>
  )
}
