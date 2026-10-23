import { Card, Icon, cx } from '@iaf/ui'
import { Mono } from '@iaf/console-kit'
import type { BffJobDetail } from '../../lib/types'
import { failureOf } from './parts'

/*
 * What went wrong with a request, from the BFF's job: the step that failed, the class of the
 * error and the provider's words, or the approver who said no and why. A paused request shows it
 * above its choices, an ended one on its own. It is one block, so a fuller explanation can take
 * its place without touching the page.
 */

export function FailureCard({ job, className }: { job: BffJobDetail; className?: string }) {
  const failure = failureOf(job)
  if (!failure) return null
  const paused = job.status === 'needs-attention'
  return (
    <Card title="What went wrong" subtitle={failure.step ? `In step ${failure.step}` : undefined} className={cx('mb-8', className)} data-failure-card={failure.errorClass ?? 'failed'}>
      <div className="flex items-start gap-3 text-sm">
        <Icon name="alert-triangle" size={18} className={cx('mt-0.5 shrink-0', paused ? 'text-warning' : 'text-critical')} />
        <div className="min-w-0 space-y-1">
          <p className="text-primary">{failure.message}</p>
          {failure.rejected?.comment && (
            <p className="text-secondary">
              <span className="text-tertiary">Their comment: </span>“{failure.rejected.comment}”
            </p>
          )}
          {failure.errorClass && failure.errorClass !== 'rejected' && (
            <p className="text-caption text-secondary">
              Class <Mono>{failure.errorClass}</Mono>
              {failure.retryable === false ? '. The platform says running it again as it was will not help.' : failure.retryable ? '. The platform says running it again can help.' : ''}
            </p>
          )}
        </div>
      </div>
    </Card>
  )
}
