import { useState } from 'react'
import { Button, Card, ConfirmDialog, Icon, StateTag, Tag, Textarea, cx } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { Approval } from '../lib/types'
import { PreviewCard } from './PreviewCard'
import { TextLink, When, countdown, deadlineOf, useNow } from './common'

/*
 * One approval, pending or decided. Pending: the message, who asked, how long is left, a
 * comment box and the two buttons (rejecting confirms first). Decided: who, when, and the comment.
 */

export interface ApprovalCardProps {
  approval: Approval & { requester?: string; title?: string }
  onApprove?: (comment: string) => Promise<void>
  onReject?: (comment: string) => Promise<void>
  /** link to the job (default true) */
  jobLink?: boolean
  /** show the preview the approval carries (default false: a compact card) */
  showPreview?: boolean
  className?: string
}

export function ApprovalCard({ approval, onApprove, onReject, jobLink = true, showPreview, className }: ApprovalCardProps) {
  const now = useNow(30_000)
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null)
  const [confirmReject, setConfirmReject] = useState(false)
  const pending = approval.status === 'pending'
  const deadline = deadlineOf(approval.requestedAt, approval.timeout)
  const left = deadline ? countdown(deadline, now) : null

  const approve = async () => {
    if (!onApprove) return
    setBusy('approve')
    try {
      await onApprove(comment.trim())
    } finally {
      setBusy(null)
    }
  }
  const reject = async () => {
    if (!onReject) return
    setBusy('reject')
    try {
      await onReject(comment.trim())
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card
      title={approval.title ?? approval.workflow}
      subtitle={
        <span className="flex flex-wrap items-center gap-x-2">
          <span>
            step <span className="font-mono text-secondary">{approval.step}</span>
          </span>
          {jobLink && (
            <>
              <span className="text-tertiary">·</span>
              <TextLink to={pathFor('job', { id: approval.jobId })} mono>
                {approval.jobId}
              </TextLink>
            </>
          )}
        </span>
      }
      actions={pending ? <Tag tone="warning" dot pulse>Waiting</Tag> : <StateTag status={approval.status} />}
      className={cx(pending && 'border-warning/40', className)}
    >
      <blockquote className="rounded-md bg-surface-secondary px-4 py-3 text-sm leading-relaxed text-primary">{approval.message}</blockquote>

      <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-2 text-caption sm:grid-cols-4">
        <div>
          <dt className="text-tertiary">Group</dt>
          <dd className="mt-0.5">
            <Tag size="sm" icon="users">
              {approval.group}
            </Tag>
          </dd>
        </div>
        {approval.requester && (
          <div>
            <dt className="text-tertiary">Requested by</dt>
            <dd className="mt-0.5 text-primary">{approval.requester}</dd>
          </div>
        )}
        <div>
          <dt className="text-tertiary">Requested</dt>
          <dd className="mt-0.5 text-primary">
            <When at={approval.requestedAt} now={now} />
          </dd>
        </div>
        {pending ? (
          <div>
            <dt className="text-tertiary">Timeout</dt>
            <dd className={cx('mt-0.5', left?.expired ? 'text-critical' : 'text-primary')}>
              {left ? `${left.text}` : approval.timeout}
              {left && <span className="ml-1 text-tertiary">({approval.timeout})</span>}
            </dd>
          </div>
        ) : (
          <div>
            <dt className="text-tertiary">Decided</dt>
            <dd className="mt-0.5 text-primary">
              {approval.decidedBy ?? 'nobody'} · <When at={approval.decidedAt} now={now} />
            </dd>
          </div>
        )}
      </dl>

      {!pending && approval.comment && (
        <p className="mt-3 flex items-start gap-2 text-caption text-secondary">
          <Icon name="document" size={13} className="mt-0.5 shrink-0 text-tertiary" />
          <span>“{approval.comment}”</span>
        </p>
      )}

      {showPreview && approval.preview && <PreviewCard preview={approval.preview} compact className="mt-4" />}

      {pending && (onApprove || onReject) && (
        <div className="mt-4 space-y-3">
          <Textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="A comment for the record (optional)" aria-label="Comment" />
          <div className="flex flex-wrap justify-end gap-2">
            {onReject && (
              <Button variant="warning-outline" icon="close" disabled={!!busy} loading={busy === 'reject'} onClick={() => setConfirmReject(true)}>
                Reject
              </Button>
            )}
            {onApprove && (
              <Button variant="primary" icon="check" disabled={!!busy} loading={busy === 'approve'} onClick={() => void approve()}>
                Approve
              </Button>
            )}
          </div>
        </div>
      )}

      {confirmReject && (
        <ConfirmDialog
          title="Reject this approval?"
          tone="danger"
          icon="close-circle"
          message={`The run of ${approval.title ?? approval.workflow} fails at ${approval.step}${comment.trim() ? ' with your comment on the record' : ''}.`}
          confirmLabel="Reject"
          onConfirm={reject}
          onClose={() => setConfirmReject(false)}
        />
      )}
    </Card>
  )
}
