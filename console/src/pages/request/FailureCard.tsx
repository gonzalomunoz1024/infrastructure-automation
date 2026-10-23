import { Card, Icon, cx } from '@iaf/ui'
import { Mono } from '@iaf/console-kit'
import type { BffJobDetail, RetryFix } from '../../lib/types'
import { FailureExplainer } from '../explain/FailureExplainer'
import { defaultFromStep, failureOf } from './parts'

/*
 * What went wrong with a request, from the BFF's job: the step that failed, the class of the
 * error and the provider's words, or the approver who said no and why. A paused request shows it
 * above its choices, an ended one on its own. It is one block, so a fuller explanation takes its
 * place without touching the page: a failed or paused step gets the failure explainer (phase 9),
 * with the fix to try, which runs through the page's own retry; a rejection, a bulk run and a
 * rollback keep the words here.
 */

export interface FailureCardProps {
  job: BffJobDetail
  /** the page's retry, which the explainer's "Retry with fix" runs with its fix */
  onRetry?: (fix: RetryFix | undefined) => unknown
  busy?: boolean
  className?: string
}

export function FailureCard({ job, onRetry, busy, className }: FailureCardProps) {
  const failure = failureOf(job)
  if (!failure) return null
  const paused = job.status === 'needs-attention'
  const batch = !!job.batch || job.kind === 'batch'
  if ((paused || job.status === 'failed') && failure.errorClass !== 'rejected' && !batch) {
    return (
      <FailureExplainer
        jobId={job.jobId}
        status={job.status}
        since={job.attention?.since ?? job.endedAt}
        onRetry={onRetry}
        busy={busy}
        defaultStep={defaultFromStep(job)}
        fallback={{ errorClass: failure.errorClass, message: failure.message, step: failure.step }}
        className={cx('mb-8', className)}
      />
    )
  }
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
