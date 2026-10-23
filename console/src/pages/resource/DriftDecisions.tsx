import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { Button, Field, Icon, Modal, OverlayBody, OverlayFooter, OverlayHeader, Spinner, Textarea, type ButtonSize, type ButtonVariant, type IconName } from '@iaf/ui'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, pathFor } from '../../app/router'
import type { ConsoleType } from '../../lib/descriptors'
import { IGNORE_CHOICES, decisionKeys, driftApi, fieldLabel, findingDecision, ignoreUntilOf, revertStateOf, valueText, type IgnoreChoice } from '../../lib/drift'
import type { Decision } from '../../lib/permissions'
import { invalidate } from '../../lib/queries'
import type { Finding } from '../../lib/types'

/*
 * The decisions on a drift finding, shared by the resource page's Drift tab and the account's
 * Drift page (WP 5.2). Revert is one click: it runs the type's update workflow with what was
 * asked for, and the finding says Reverting, with its request, until the job ends. Accept asks
 * once in a small dialog, since the provider's values become what is asked for. Ignore asks for
 * how long and why. A decision the person's role refuses shows its lock and the refusal; one the
 * drift service does not offer on this finding is greyed, with the reason in words beside it.
 */

export type DecisionVerb = 'revert' | 'accept' | 'ignore'

export const DECISION_WHAT: Record<DecisionVerb, string> = {
  revert: 'Revert to what was asked for',
  accept: 'Accept the provider’s values',
  ignore: 'Ignore these fields',
}

/** The three decisions on a finding for this person, from the type's update permission and the finding's `can`. */
export function decisionsOf(update: Decision, finding: Finding): Record<DecisionVerb, Decision & { why?: string }> {
  return { revert: findingDecision(update, finding, 'revert'), accept: findingDecision(update, finding, 'accept'), ignore: findingDecision(update, finding, 'ignore') }
}

/** The reasons the unavailable decisions give, each once: under the buttons, and what their descriptions point to. */
export function reasonsOf(decisions: Record<DecisionVerb, Decision & { why?: string }>): string[] {
  return Array.from(new Set(Object.values(decisions).flatMap((d) => (d.why ? [d.why] : []))))
}

export interface DecisionButtonProps {
  decision: Decision & { why?: string }
  verb: DecisionVerb
  children: string
  onClick: () => void
  variant?: ButtonVariant
  size?: ButtonSize
  icon?: IconName
  loading?: boolean
  /** the id of the line that says why the decision is not offered */
  describedBy?: string
  className?: string
}

/** A decision as this person may take it: live, locked with the refusal behind it, or greyed with the reason beside it. */
export function DecisionButton({ decision, verb, children, onClick, variant, size = 'sm', icon, loading, describedBy, className }: DecisionButtonProps) {
  if (decision.why) {
    return (
      <Button variant={variant} size={size} icon={icon} disabled aria-describedby={describedBy} data-verb={verb} className={className}>
        {children}
      </Button>
    )
  }
  return (
    <ActionButton decision={decision} what={DECISION_WHAT[verb]} variant={variant} size={size} icon={icon} onClick={onClick} loading={loading} verb={verb} className={className}>
      {children}
    </ActionButton>
  )
}

/** Starts the revert of a finding, one call with its own key; answers the job it started, or undefined when it did not start (the reason is in `failure`). */
export function useRevert(type: Pick<ConsoleType, 'name' | 'plural'>) {
  const cmd = useCommand()
  const revert = async (finding: Pick<Finding, 'findingId'>, opts: { quiet?: boolean } = {}) => {
    const res = await cmd.run(DECISION_WHAT.revert, () => driftApi.findings.revert(finding.findingId), { invalidates: decisionKeys(type), quiet: opts.quiet ?? true })
    // a refusal of the finding's state (closed meanwhile, a revert already running) is news: read it again
    if (!res) invalidate(`console-drift/${type.name}`)
    return res
  }
  return { revert, failure: cmd.failure, clear: cmd.clear, busy: cmd.busy }
}

/** How a finding's revert stands, as one line with its request: running, or failed and offered again. */
export function RevertStatus({ finding, jobId, running }: { finding: Finding; jobId?: string; running?: boolean }) {
  const state = running ? 'running' : revertStateOf(finding)
  const job = jobId ?? finding.revertJobId
  const link = job ? (
    <Link to={pathFor('request', { id: job })} className="font-medium text-accent hover:underline">
      {state === 'running' ? 'Follow the request' : 'See what happened'}
    </Link>
  ) : null
  if (state === 'running') {
    return (
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-primary" data-revert="running">
        <span aria-hidden="true">
          <Spinner size={14} />
        </span>
        <span>Reverting: the update workflow is putting these fields back, and the finding closes when its request succeeds.</span>
        {link}
      </p>
    )
  }
  if (state === 'failed') {
    return (
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-primary" data-revert="failed">
        <Icon name="alert-triangle" size={14} className="text-warning" />
        <span>The last revert failed; the provider still reports these values. You can revert again.</span>
        {link}
      </p>
    )
  }
  return null
}

/** The fields as the decision would leave them, one line each: "Memory becomes 16 GiB (asked for 8 GiB)". */
function FieldChanges({ finding, type, accept }: { finding: Finding; type?: Pick<ConsoleType, 'schema'>; accept: boolean }) {
  const live = finding.fields.filter((f) => !f.ignored)
  return (
    <ul className="space-y-1 rounded-md border border-stroke-light bg-surface-raised px-3 py-2 text-sm">
      {live.map((f) => (
        <li key={f.path} className="text-primary">
          {accept ? (
            <>
              {fieldLabel(f.path, type)} becomes <span className="font-medium tabular-nums">{valueText(f.path, f.observed, type)}</span>
              <span className="text-secondary"> (asked for {valueText(f.path, f.expected, type)})</span>
            </>
          ) : (
            <>
              {fieldLabel(f.path, type)} stays <span className="font-medium tabular-nums">{valueText(f.path, f.observed, type)}</span>
              <span className="text-secondary"> at the provider, {valueText(f.path, f.expected, type)} asked for</span>
            </>
          )}
        </li>
      ))}
    </ul>
  )
}

export interface DecisionDialogProps {
  finding: Finding
  type: Pick<ConsoleType, 'name' | 'plural' | 'schema'>
  /** the resource's name, for the dialog's title */
  name?: string
  onClose: () => void
  /** after the decision was taken, with a line for the live region */
  onDone: (line: string) => void
}

/** Accept, asked once: what the provider reports becomes what is asked for, with an optional reason. */
export function AcceptDialog({ finding, type, name, onClose, onDone }: DecisionDialogProps) {
  const cmd = useCommand()
  const id = useId()
  const [reason, setReason] = useState('')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (cmd.busy) return
    const trimmed = reason.trim()
    const res = await cmd.run(DECISION_WHAT.accept, () => driftApi.findings.accept(finding.findingId, trimmed ? { reason: trimmed } : undefined), { invalidates: decisionKeys(type), quiet: true })
    if (res) onDone(`Accepted: ${name ?? 'the resource'} now asks for what the provider reports.`)
    else invalidate(`console-drift/${type.name}`)
  }
  return (
    <Modal onClose={onClose} label={DECISION_WHAT.accept} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title="Accept the provider’s values?" subtitle={`What the provider reports becomes what is asked for${name ? ` on ${name}` : ''}. Nothing changes at the provider, and the finding closes.`} icon="check" />
        <OverlayBody>
          <div className="space-y-5">
            <FieldChanges finding={finding} type={type} accept />
            <Field label="Reason" htmlFor={`${id}-reason`} hint="Optional. Kept with the finding’s history.">
              <Textarea id={`${id}-reason`} data-autofocus rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="The new size was agreed with the service’s owners" />
            </Field>
            <FailureNote failure={cmd.failure} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Accept
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/** Ignore, with how long: until what was asked for changes, or a number of days; and an optional reason. */
export function IgnoreDialog({ finding, type, name, onClose, onDone }: DecisionDialogProps) {
  const cmd = useCommand()
  const id = useId()
  const [choice, setChoice] = useState<IgnoreChoice>('desired')
  const [reason, setReason] = useState('')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (cmd.busy) return
    const trimmed = reason.trim()
    const until = ignoreUntilOf(choice)
    const res = await cmd.run(DECISION_WHAT.ignore, () => driftApi.findings.ignore(finding.findingId, { ...(trimmed ? { reason: trimmed } : {}), ...(until ? { until } : {}) }), { invalidates: decisionKeys(type), quiet: true })
    const label = IGNORE_CHOICES.find((c) => c.value === choice)?.label.toLowerCase() ?? ''
    if (res) onDone(`Ignored ${label}: the drift service stays quiet about these fields${name ? ` on ${name}` : ''}.`)
    else invalidate(`console-drift/${type.name}`)
  }
  return (
    <Modal onClose={onClose} label={DECISION_WHAT.ignore} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title="Ignore these fields" subtitle={`The finding closes and the drift service stays quiet about these fields${name ? ` on ${name}` : ''} for as long as you choose. Nothing changes at the provider.`} icon="eye" />
        <OverlayBody>
          <div className="space-y-5">
            <FieldChanges finding={finding} type={type} accept={false} />
            <fieldset className="space-y-2">
              <legend className="mb-1 text-sm font-medium text-primary">For how long</legend>
              {IGNORE_CHOICES.map((c, i) => (
                <label key={c.value} className="flex cursor-pointer items-center gap-2 text-sm text-primary">
                  <input type="radio" name={`${id}-until`} value={c.value} checked={choice === c.value} onChange={() => setChoice(c.value)} data-autofocus={i === 0 ? true : undefined} className="size-4 accent-accent" />
                  {c.label}
                </label>
              ))}
            </fieldset>
            <Field label="Reason" htmlFor={`${id}-reason`} hint="Optional. Kept with the finding’s history.">
              <Textarea id={`${id}-reason`} rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Load test this week; the size goes back on Friday" />
            </Field>
            <FailureNote failure={cmd.failure} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy}>
            Ignore
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/** A polite live region: what a decision did, said once. */
export function LiveLine({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <p role="status" aria-live="polite" className={className ?? 'sr-only'}>
      {children}
    </p>
  )
}
