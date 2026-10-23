import { useState } from 'react'
import { Button, Card, Field, Tag, Textarea, plural, type Tone } from '@iaf/ui'
import { ConfirmDialog, When, WfrnText } from '@iaf/console-kit'
import type { BffApproval, BffJobDetail } from '../../lib/types'
import { deciderOf } from './parts'

/*
 * The approvals a request waits on, or waited on: whose group decides each, who decided and
 * what they said. While a batch runs, its rows' pending approvals are here too. One decision
 * covers every approval this person may decide (the BFF's `approvals:decide`, once per wave);
 * the ones left say why. A rejection asks first, since it ends the request, or stops the run.
 */

const TONE: Record<string, Tone> = { pending: 'warning', approved: 'healthy', rejected: 'critical', expired: 'neutral' }

export interface ApprovalsCardProps {
  job: BffJobDetail
  busy?: boolean
  onDecide: (decision: 'approve' | 'reject', comment: string | undefined) => Promise<unknown>
}

export function ApprovalsCard({ job, busy, onDecide }: ApprovalsCardProps) {
  const approvals = job.approvals ?? []
  const [comment, setComment] = useState('')
  const [rejecting, setRejecting] = useState(false)
  if (!approvals.length) return null
  const pending = approvals.filter((a) => a.status === 'pending')
  const mine = pending.filter((a) => a.canDecide)
  const batch = !!job.batch
  const groups = [...new Set(pending.map((a) => a.group).filter(Boolean))]
  const said = comment.trim() || undefined

  return (
    <Card
      title="Approvals"
      subtitle={pending.length ? `${plural(pending.length, 'approval')} waiting${groups.length ? ` for ${groups.join(', ')}` : ''}` : 'Decided'}
      className="mb-8"
      data-approvals={pending.length}
    >
      <ul className="divide-y divide-stroke-light text-sm" aria-label="Approvals of this request">
        {approvals.map((a) => (
          <ApprovalRow key={a.approvalId} approval={a} />
        ))}
      </ul>
      {mine.length > 0 ? (
        <div className="mt-4 border-t border-stroke-light pt-4">
          <Field label="Comment" htmlFor="approval-comment" hint="Optional. It stays with the request’s trail.">
            <Textarea id="approval-comment" rows={2} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} />
          </Field>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button variant="primary" icon="check" loading={busy} onClick={() => void onDecide('approve', said).then(() => setComment(''))}>
              {mine.length === 1 ? 'Approve' : `Approve ${mine.length}`}
            </Button>
            <Button variant="danger" icon="close" disabled={busy} onClick={() => setRejecting(true)}>
              Reject
            </Button>
          </div>
        </div>
      ) : (
        pending.length > 0 && <p className="mt-3 text-caption text-tertiary">{decideNote(pending)}</p>
      )}
      {rejecting && (
        <ConfirmDialog
          title="Reject this request?"
          tone="danger"
          icon="close"
          confirmLabel="Reject"
          cancelLabel="Keep it waiting"
          message={batch ? 'The run stops here: the rows waiting in this wave are cancelled and no further wave goes out. Rows that already ran keep what they did.' : 'The request ends at its approval step and nothing runs.'}
          onConfirm={async () => {
            await onDecide('reject', said)
            setComment('')
          }}
          onClose={() => setRejecting(false)}
        />
      )}
    </Card>
  )
}

function ApprovalRow({ approval: a }: { approval: BffApproval }) {
  return (
    <li className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2.5 first:pt-0 last:pb-0">
      <Tag size="sm" tone={TONE[a.status] ?? 'neutral'}>
        {a.status}
      </Tag>
      <div className="min-w-0 flex-1">
        <p className="text-primary">
          {a.status === 'pending' ? `Waits for ${a.group ?? 'an approver'}` : a.decidedBy ? `${a.status === 'approved' ? 'Approved' : a.status === 'rejected' ? 'Rejected' : 'Decided'} by ${deciderOf(a.decidedBy)}` : a.status}
          {a.decidedAt && (
            <span className="text-tertiary">
              {' '}
              <When at={a.decidedAt} />
            </span>
          )}
        </p>
        {a.comment && <p className="text-secondary">“{a.comment}”</p>}
        {a.wfrn && <WfrnText wfrn={a.wfrn} />}
      </div>
      {a.status === 'pending' && a.requestedAt && (
        <span className="text-caption text-tertiary">
          Asked <When at={a.requestedAt} />
        </span>
      )}
    </li>
  )
}

function decideNote(pending: BffApproval[]): string {
  const group = pending[0]?.group
  return group ? `Only members of ${group} with a role that may decide approvals can decide ${pending.length === 1 ? 'it' : 'them'}, and never on their own request.` : 'Someone the account allows decides it.'
}
