import { useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Textarea, useToast } from '@iaf/ui'
import { useRefusal } from '../../app/Refusal'
import { isApiError } from '../../lib/api'
import { invalidate } from '../../lib/queries'
import { quotasApi, type ConsoleQuotaRequest } from '../../lib/quotas'
import { useSubmissionKey } from '../../lib/submission'

/*
 * The platform team's decision on a request no rule approved: approve the ask, or a different
 * limit, with an optional note for the requester; or reject it, saying why. The BFF only takes
 * it from the platform team and never from the requester, and the page offers it the same way.
 */

const n = (v: number) => v.toLocaleString('en-US')

export function DecideQuotaDialog({ request: r, decision, title, requester, onClose }: { request: ConsoleQuotaRequest; decision: 'approve' | 'reject'; title: string; requester: string; onClose: () => void }) {
  const toast = useToast()
  const refusal = useRefusal()
  const keyFor = useSubmissionKey()
  const [limit, setLimit] = useState(String(r.requestedLimit))
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const [tried, setTried] = useState(false)
  const approve = decision === 'approve'
  const granted = Number(limit)
  const lower = title.toLowerCase()
  const limitError = approve && (!Number.isInteger(granted) || granted < 1) ? 'A whole number of at least 1.' : undefined
  const commentError = !approve && !comment.trim() ? `Say why, so ${requester} knows what to ask for instead.` : undefined

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTried(true)
    if (busy || limitError || commentError) return
    setBusy(true)
    const body = approve ? { decision, ...(granted !== r.requestedLimit ? { limit: granted } : {}), ...(comment.trim() ? { comment: comment.trim() } : {}) } : { decision, comment: comment.trim() }
    try {
      const decided = await quotasApi.decide(r.requestId, body, keyFor(body))
      invalidate('iam')
      toast.success(approve ? `${title}: the limit is now ${n(decided.grantedLimit ?? granted)}` : 'Request rejected', approve ? `${requester}’s request is approved.` : `${title} keeps its limit of ${n(r.currentLimit)}.`)
      onClose()
    } catch (err) {
      if (!refusal.showError(err, approve ? 'Approve a quota request' : 'Reject a quota request')) toast.error(approve ? 'Could not approve the request' : 'Could not reject the request', isApiError(err) ? (err.status === 409 ? 'Someone decided it first, or it was withdrawn. The list shows where it is now.' : err.error) : String(err))
      if (isApiError(err) && err.status === 409) invalidate('iam')
    } finally {
      setBusy(false)
    }
  }

  const heading = approve ? `Approve more ${lower}` : `Reject more ${lower}`
  return (
    <Modal onClose={onClose} label={heading} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={heading} subtitle={`${requester} asks for ${n(r.requestedLimit)}, up from ${n(r.currentLimit)}: “${r.reason}”`} icon={approve ? 'check' : 'close'} />
        <OverlayBody>
          <div className="space-y-5">
            {approve && (
              <Field label="Limit to grant" htmlFor="decide-limit" hint="The ask, or less, or more." error={tried ? limitError : undefined} required>
                <Input id="decide-limit" data-autofocus type="number" inputMode="numeric" min={1} step={1} value={limit} onChange={(e) => setLimit(e.target.value)} />
              </Field>
            )}
            <Field label={approve ? 'Note for the requester' : 'Why'} htmlFor="decide-comment" hint={approve ? 'Optional.' : `${requester} reads it with the decision.`} error={tried ? commentError : undefined} required={!approve}>
              <Textarea id="decide-comment" {...(approve ? {} : { 'data-autofocus': true })} rows={3} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} />
            </Field>
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant={approve ? 'primary' : 'danger'} loading={busy}>
            {approve ? 'Approve' : 'Reject'}
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
