import { Button, Icon } from '@iaf/ui'
import { When } from '@iaf/console-kit'
import { mayJob } from '../../lib/jobs'
import type { BffJobDetail } from '../../lib/types'

/*
 * A request paused on a failed step (hub decision 24): which step, since when, and the ways on
 * that the platform offers and the policies allow this person. Retry runs the step again, with
 * changes when the input was the problem; skip goes on past it; abort undoes what ran. Nothing
 * here acts by itself: the page confirms skip and abort first.
 */

export interface AttentionCardProps {
  job: BffJobDetail
  busy?: boolean
  onRetry: () => void
  onRetryWithChanges: () => void
  onSkip: () => void
  onAbort: () => void
}

export function AttentionCard({ job, busy, onRetry, onRetryWithChanges, onSkip, onAbort }: AttentionCardProps) {
  const step = job.attention?.step ?? job.failedStep ?? 'a step'
  const retry = mayJob(job, 'retry') === true
  const skip = mayJob(job, 'skip') === true
  const abort = mayJob(job, 'abort') === true
  const offered = job.attention?.options ?? []
  return (
    <section aria-labelledby="attention-title" className="mb-8 rounded-lg border border-warning/40 bg-warning-tint/40 p-4" data-attention={step}>
      <div className="flex flex-wrap items-start gap-3">
        <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-warning" />
        <div className="min-w-0 flex-1">
          <h2 id="attention-title" className="font-semibold text-primary">
            Paused on {step}
          </h2>
          <p className="mt-0.5 text-sm text-secondary">
            The step failed{job.attention?.since ? <>, <When at={job.attention.since} /></> : null}. The request waits for someone to decide how to go on: nothing else runs until then.
          </p>
          {!retry && !skip && !abort && <p className="mt-2 text-sm text-primary">Your role in this account cannot steer it. The requester, or someone the account’s policies allow, can.</p>}
          {offered.length > 0 && !offered.includes('skip') && (retry || abort) && <p className="mt-2 text-caption text-tertiary">This step cannot be skipped: what comes after it needs what it makes.</p>}
        </div>
      </div>
      {(retry || skip || abort) && (
        <div className="mt-4 flex flex-wrap gap-2 pl-0 sm:pl-[30px]">
          {retry && (
            <Button variant="primary" icon="refresh" loading={busy} onClick={onRetry}>
              Retry the step
            </Button>
          )}
          {retry && (
            <Button icon="edit" disabled={busy} onClick={onRetryWithChanges}>
              Retry with changes
            </Button>
          )}
          {skip && (
            <Button icon="arrow-right" disabled={busy} onClick={onSkip}>
              Skip it
            </Button>
          )}
          {abort && (
            <Button variant="danger" icon="stop" disabled={busy} onClick={onAbort}>
              Abort the request
            </Button>
          )}
        </div>
      )}
    </section>
  )
}
