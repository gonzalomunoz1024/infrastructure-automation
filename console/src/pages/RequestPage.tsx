import { useState } from 'react'
import { Button, EmptyState, Icon, IconButton, JsonView, PageHeader, SkeletonRows, cx, duration } from '@iaf/ui'
import { ConfirmDialog, CopyableId, Facts, Section, StatusBadge, StepTimeline, When, WfrnText, useNow, type Problem } from '@iaf/console-kit'
import { ExplainedError, useCommand } from '../app/commands'
import { pathOfWfrn, useConsole } from '../app/console'
import { href, navigate, pathFor, type PageProps } from '../app/router'
import { bff } from '../lib/bff'
import { mayJob } from '../lib/jobs'
import { isOpenStatus, usePoll } from '../lib/poll'
import { useQuery } from '../lib/queries'
import { useSubmissionKey } from '../lib/submission'
import type { RetryFix } from '../lib/types'
import { ApprovalsCard } from './request/ApprovalsCard'
import { AttentionCard } from './request/AttentionCard'
import { BatchSection } from './request/BatchSection'
import { FailureCard } from './request/FailureCard'
import { childrenOf, decidedLine } from './request/parts'
import { RetryDialog } from './request/RetryDialog'

/*
 * One request, as the BFF holds it (WP 4.2): its facts; the step it paused on and the ways on
 * (retry, with changes or not, skip, abort); what went wrong; its approvals, decided here in one
 * call; a bulk run's waves and rows; the steps it ran; its input and output. While it runs the
 * page follows it. Every choice shows only when the BFF's `can` says this person may make it, and
 * a retry of an ended request opens the new request it started.
 */

type Asking = 'cancel' | 'skip' | 'abort' | 'retry' | null

const INVALIDATES = ['jobs', 'bff-job', 'console-resource', 'console-activity', 'resources']

const STEER_WORDS = {
  cancel: { what: 'Cancel this request', title: 'Request cancelled', message: 'The platform stops it at its next safe point.' },
  skip: { what: 'Skip the step', title: 'Step skipped', message: 'The request goes on past it.' },
  abort: { what: 'Abort this request', title: 'Request aborted', message: 'What ran is being undone.' },
} as const

export default function RequestPage({ route }: PageProps) {
  const id = route.params.id
  const { types } = useConsole()
  const q = useQuery(['bff-job', id], () => bff.jobs.get(id))
  const now = useNow()
  const job = q.data
  const open = isOpenStatus(job?.status)
  usePoll(open, () => void q.refetch())
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const [asking, setAsking] = useState<Asking>(null)
  const [problems, setProblems] = useState<Problem[]>([])

  if (q.loading) return <SkeletonRows n={4} />
  if (q.error && !job) return <ExplainedError error={q.error} what="Load this request" onRetry={q.refetch} />
  if (!job) return <EmptyState icon="job" title="Nothing here" message="The console answered without a request." />

  const paused = job.status === 'needs-attention'
  const batch = job.kind === 'batch' || !!job.batch
  const title = job.title ?? job.workflow
  // an open request's time runs on from its start; an ended one's is what the BFF measured
  const elapsed = open && job.startedAt ? duration(Math.max(0, now - Date.parse(job.startedAt))) : job.durationMs !== undefined ? duration(job.durationMs) : job.startedAt && job.endedAt ? duration(Date.parse(job.endedAt) - Date.parse(job.startedAt)) : duration(null)
  const wfrnPath = pathOfWfrn(job.wfrn, types)
  // each pause is its own decision: the key changes with it, so a second retry is not the first one replayed
  const pause = job.attention?.since ?? job.endedAt ?? ''

  /** A paused request goes on in place; an ended one runs again as a new request, which the page then shows. */
  const retry = async (fix?: RetryFix) => {
    const accepted = await cmd.run(paused ? 'Retry the step' : 'Run this request again', () => bff.jobs.retry(id, fix, keyFor({ retry: id, pause, fix })), {
      quiet: asking === 'retry',
      invalidates: INVALIDATES,
      onProblems: setProblems,
      done: (a) => (a.jobId === id ? { title: 'The step runs again', message: 'The request goes on from where it paused.' } : { title: 'Running it again', message: 'It runs as a new request, which this page now shows.' }),
    })
    if (!accepted) return
    setAsking(null)
    setProblems([])
    if (accepted.jobId && accepted.jobId !== id) navigate(pathFor('request', { id: accepted.jobId }))
    else void q.refetch()
  }

  const steer = async (verb: 'cancel' | 'skip' | 'abort') => {
    const words = STEER_WORDS[verb]
    const key = keyFor({ [verb]: id, pause })
    const call = verb === 'cancel' ? () => bff.jobs.cancel(id, key) : verb === 'skip' ? () => bff.jobs.skip(id, job.attention?.step, key) : () => bff.jobs.abort(id, key)
    await cmd.run<unknown>(words.what, call, { invalidates: INVALIDATES, done: () => ({ title: words.title, message: words.message }) })
    void q.refetch()
  }

  const decide = async (decision: 'approve' | 'reject', comment: string | undefined) => {
    const pending = (job.approvals ?? []).filter((a) => a.status === 'pending').map((a) => a.approvalId)
    const result = await cmd.run(decision === 'approve' ? 'Approve this request' : 'Reject this request', () => bff.jobs.decideApprovals(id, { decision, comment }, keyFor({ decide: id, decision, comment, pending })), {
      invalidates: INVALIDATES,
      done: (r) => ({ title: r.decided.length ? (decision === 'approve' ? 'Approved' : 'Rejected') : 'Nothing decided', message: decidedLine(r) }),
    })
    void q.refetch()
    return result
  }

  return (
    <>
      <PageHeader
        eyebrow={batch ? 'Bulk request' : 'Request'}
        title={title}
        subtitle={<CopyableId id={job.jobId} />}
        actions={
          <>
            <IconButton label="Refresh" onClick={() => void q.refetch()}>
              <Icon name="refresh" size={16} className={cx(q.fetching && 'animate-spin')} />
            </IconButton>
            {mayJob(job, 'cancel') && (
              <Button variant="secondary" icon="stop" disabled={cmd.busy} onClick={() => setAsking('cancel')}>
                Cancel request
              </Button>
            )}
            {!open && mayJob(job, 'retry') && (
              <Button variant="secondary" icon="refresh" disabled={cmd.busy} onClick={() => setAsking('retry')}>
                {batch ? 'Run failed rows again' : 'Retry'}
              </Button>
            )}
          </>
        }
      />

      {/* the page follows the request: a change of status is said, not only shown */}
      <p role="status" aria-live="polite" className="sr-only" data-job-status={job.status}>
        {`${title} is ${job.status.replace(/-/g, ' ')}`}
      </p>

      <Facts
        columns={4}
        className="mb-8"
        items={[
          { label: 'Status', value: <StatusBadge status={job.status} /> },
          {
            label: 'Workflow',
            value: (
              <span className="flex flex-wrap items-center gap-x-1.5">
                <span className="font-mono">{job.workflow}</span>
                {job.version && <span className="text-tertiary">v{job.version}</span>}
              </span>
            ),
          },
          { label: 'Requester', value: job.requester ?? <span className="text-tertiary">Not recorded</span> },
          { label: 'Kind', value: batch && job.progress ? `Bulk, ${job.progress.done} of ${job.progress.total} done${job.progress.failed ? `, ${job.progress.failed} failed` : ''}` : 'Single' },
          { label: 'Started', value: <When at={job.startedAt} /> },
          { label: 'Ended', value: <When at={job.endedAt} /> },
          { label: 'Duration', value: <span className="tabular-nums">{elapsed}</span> },
          ...(job.wfrn ? [{ label: 'Resource', value: <WfrnText wfrn={job.wfrn} size="md" href={wfrnPath ? href(wfrnPath) : undefined} />, wide: true }] : []),
        ]}
      />

      {paused && <AttentionCard job={job} busy={cmd.busy} onRetry={() => void retry()} onRetryWithChanges={() => setAsking('retry')} onSkip={() => setAsking('skip')} onAbort={() => setAsking('abort')} />}

      <FailureCard job={job} busy={cmd.busy} onRetry={retry} />

      <ApprovalsCard job={job} busy={cmd.busy} onDecide={decide} />

      <BatchSection job={job} types={types} />

      <Section title="Steps" description="What the workflow ran, in order." className="mb-8">
        {job.steps?.length ? (
          <StepTimeline steps={job.steps} children={childrenOf(job)} highlight={job.attention?.step ?? job.failedStep} defaultOpen={job.failedStep ? [job.failedStep] : []} />
        ) : (
          <EmptyState compact icon="list" title="No steps recorded" message="The platform kept only this request’s summary." />
        )}
      </Section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Section title="Input">{job.input ? <JsonView value={job.input} defaultExpanded={2} /> : <p className="text-sm text-tertiary">No input recorded.</p>}</Section>
        <Section title="Output">{job.output ? <JsonView value={job.output} defaultExpanded={2} /> : <p className="text-sm text-tertiary">{open ? 'Nothing yet.' : 'No output recorded.'}</p>}</Section>
      </div>

      {asking === 'retry' && (
        <RetryDialog
          job={job}
          types={types}
          busy={cmd.busy}
          failure={cmd.failure}
          problems={problems}
          onSubmit={(fix) => void retry(fix)}
          onClose={() => {
            setAsking(null)
            setProblems([])
            cmd.clear()
          }}
        />
      )}
      {asking === 'cancel' && (
        <ConfirmDialog
          title="Cancel this request?"
          tone="warning"
          icon="stop"
          confirmLabel="Cancel request"
          cancelLabel="Keep it running"
          message={
            batch ? (
              'Rows still waiting or running stop, and no further wave goes out. Rows that already ran keep what they did.'
            ) : (
              <>
                The platform stops <span className="font-medium text-primary">{title}</span> at its next safe point. Steps that already ran are not undone; a create stopped before it finished leaves nothing behind.
              </>
            )
          }
          onConfirm={() => steer('cancel')}
          onClose={() => setAsking(null)}
        />
      )}
      {asking === 'skip' && (
        <ConfirmDialog
          title={`Skip ${job.attention?.step ?? 'the step'}?`}
          tone="warning"
          icon="arrow-right"
          confirmLabel="Skip it"
          cancelLabel="Keep it paused"
          message="The request goes on as if the step had done its work, though it did not. Skip it only when its change was made some other way, or it is not needed."
          onConfirm={() => steer('skip')}
          onClose={() => setAsking(null)}
        />
      )}
      {asking === 'abort' && (
        <ConfirmDialog
          title="Abort this request?"
          tone="danger"
          icon="stop"
          confirmLabel="Abort the request"
          cancelLabel="Keep it paused"
          message="The failed step fails for good and the steps that ran are undone. A resource this request was creating is left failed, so it can be retried later."
          onConfirm={() => steer('abort')}
          onClose={() => setAsking(null)}
        />
      )}
    </>
  )
}
