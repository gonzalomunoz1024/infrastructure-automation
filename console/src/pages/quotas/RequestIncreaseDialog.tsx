import { useMemo, useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Textarea, useToast } from '@iaf/ui'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { isApiError } from '../../lib/api'
import { invalidate, useQuery } from '../../lib/queries'
import { approverWords, approvingRule, ceilingOf, cooldownUntil, quotasApi, ruleCovering, suggestedLimit } from '../../lib/quotas'
import { useSubmissionKey } from '../../lib/submission'
import type { Quota } from '../../lib/types'

/*
 * Asking for a higher limit. As the person types, the dialog says what will happen, read from the
 * organization's rules the way the identity service reads them: approved at once by the rule that
 * covers the quota (within its factor and ceiling, outside its cooldown), or sent to the platform
 * team, who decide. The answer says the same: the new limit, or the request waiting. One
 * Idempotency-Key per body, so a second click sends one request.
 */

export const QUOTA_RULES_KEY = ['iam', 'quota-rules'] as const
export const QUOTA_REQUESTS_KEY = (accountId: string, status = 'all') => ['iam', 'quota-requests', accountId, status] as const

const n = (v: number) => v.toLocaleString('en-US')

export function RequestIncreaseDialog({ quota: q, title, onClose }: { quota: Quota; title: string; onClose: () => void }) {
  const { account } = useConsole()
  const toast = useToast()
  const refusal = useRefusal()
  const keyFor = useSubmissionKey()
  const rules = useQuery(QUOTA_RULES_KEY, () => quotasApi.rules())
  const requests = useQuery(QUOTA_REQUESTS_KEY(account.id), () => quotasApi.requests({ limit: 200 }))
  const past = useMemo(() => requests.data?.items ?? [], [requests.data])
  const rule = ruleCovering(rules.data, q.key)
  const cooling = rule ? cooldownUntil(rule, past, q.key) : undefined
  const [limit, setLimit] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [tried, setTried] = useState(false)
  // the first offer waits for the rules, so it is what the rule gives at once
  const shown = limit ?? (rules.loading ? '' : String(suggestedLimit(q.limit, rule, cooling !== undefined)))
  const asked = Number(shown)
  const lower = title.toLowerCase()
  const limitError = !shown || !Number.isInteger(asked) || asked <= q.limit ? `More than the current ${n(q.limit)}, as a whole number.` : undefined
  const reasonError = reason.trim().length < 10 ? 'Say why in 10 characters or more.' : undefined
  const approver = approverWords(rules.data?.approver)
  const approving = !limitError ? approvingRule(rules.data, past, q.key, q.limit, asked) : undefined

  const outcome = limitError
    ? null
    : approving
      ? { at: 'once' as const, text: `Approved at once by the rule ${approving.name}: the limit becomes ${n(asked)} as soon as you send it.` }
      : rule && cooling
        ? { at: 'later' as const, text: `The rule ${rule.name} raised this quota lately, so until ${new Date(cooling).toLocaleDateString('en-GB', { day: 'numeric', month: 'long' })} ${approver} decides instead.` }
        : rule
          ? { at: 'later' as const, text: `More than the rule ${rule.name} gives at once (${n(ceilingOf(rule, q.limit))}), so ${approver} decides.` }
          : { at: 'later' as const, text: `No rule raises this quota at once, so ${approver} decides.` }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (busy || limitError || reasonError) return
    setBusy(true)
    const body = { limit: asked, reason: reason.trim() }
    try {
      const r = await quotasApi.requestIncrease(q.key, body, keyFor(body))
      invalidate('iam')
      if (r.status === 'approved') toast.success(`${title}: the limit is now ${n(r.grantedLimit ?? asked)}`, r.decidedBy?.startsWith('rule:') ? `Approved at once by the rule ${r.decidedBy.slice('rule:'.length)}.` : 'Approved.')
      else toast.success('Increase requested', `${approver.charAt(0).toUpperCase()}${approver.slice(1)} decides on ${n(asked)} ${lower}. The request is on Service Quotas.`)
      onClose()
    } catch (err) {
      if (!refusal.showError(err, `Request more ${lower}`)) toast.error(`Could not request more ${lower}`, isApiError(err) ? (err.status === 409 ? 'An increase of this quota is already waiting. Withdraw it first, or wait for the decision.' : err.error) : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} label={`Request more ${lower}`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate data-quota-dialog={q.key}>
        <OverlayHeader title={`Request more ${lower}`} subtitle={`Now ${n(q.used)} of ${n(q.limit)} in use in ${account.name}.`} icon="plus" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="New limit" htmlFor="quota-limit" error={tried ? limitError : undefined} required hint={rule ? `The rule ${rule.name} gives up to ${n(ceilingOf(rule, q.limit))} at once.` : undefined}>
              <Input id="quota-limit" data-autofocus type="number" inputMode="numeric" min={q.limit + 1} step={1} value={shown} onChange={(e) => setLimit(e.target.value)} />
            </Field>
            <Field label="Why" htmlFor="quota-reason" hint="Kept with the request, and read by whoever decides. 10 to 1,000 characters." error={tried ? reasonError : undefined} required>
              <Textarea id="quota-reason" rows={3} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="The storage team moves 300 machines here next month" />
            </Field>
            <p className="rounded-md bg-surface-secondary px-3 py-2 text-caption text-secondary" aria-live="polite" data-outcome={outcome?.at ?? 'none'}>
              {outcome ? outcome.text : 'Say how many, and the console tells you who decides.'}
            </p>
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy}>
            Send request
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
