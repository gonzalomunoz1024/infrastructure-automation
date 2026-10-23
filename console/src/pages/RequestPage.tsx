import { useState } from 'react'
import { Button, EmptyState, Icon, IconButton, JsonView, PageHeader, SkeletonRows, Tag, cx, duration } from '@iaf/ui'
import { ConfirmDialog, CopyableId, ErrorState, Facts, Section, StatusBadge, StepTimeline, When, WfrnText, useNow } from '@iaf/console-kit'
import { useCommand } from '../app/commands'
import { pathOfWfrn, useConsole } from '../app/console'
import { LaterButton } from '../app/Phase'
import { href, type PageProps } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { api } from '../lib/api'
import { bff } from '../lib/bff'
import { isOpenStatus, usePoll } from '../lib/poll'
import { useQuery } from '../lib/queries'
import { FailureExplainer } from './explain/FailureExplainer'

/*
 * One request: its facts, what went wrong if it did, the steps it ran, and its input and
 * outputs as the platform holds them. While it runs the page follows it, and its requester or an
 * admin of the account can cancel it (the BFF's job says who may).
 */

export default function RequestPage({ route }: PageProps) {
  const id = route.params.id
  const { types } = useConsole()
  const q = useQuery(['job', id], () => api.job(id))
  const now = useNow()
  const job = q.data
  const running = !!job && isOpenStatus(job.status)
  // the console's copy of the job says whether this person may cancel it
  const mine = useQuery(['bff-job', id], () => bff.jobs.get(id), { enabled: running })
  const cmd = useCommand()
  const [cancelling, setCancelling] = useState(false)
  usePoll(running, () => void q.refetch())

  if (q.loading) return <SkeletonRows n={4} />
  if (q.error && !job) return <ErrorState error={q.error} title="Could not load this request" onRetry={q.refetch} />
  if (!job) return <EmptyState icon="job" title="Nothing here" message="The platform answered without a request." />

  const open = job.status === 'queued' || job.status === 'running' || job.status === 'waiting-approval' || job.status === 'needs-attention'
  const elapsed = job.durationMs !== undefined ? duration(job.durationMs) : open ? duration(now - Date.parse(job.startedAt)) : duration(null)
  const wfrnPath = pathOfWfrn(job.wfrn, types)

  return (
    <>
      <PageHeader
        eyebrow="Request"
        title={job.title}
        subtitle={<CopyableId id={job.id} />}
        actions={
          <>
            <IconButton label="Refresh" onClick={() => void q.refetch()}>
              <Icon name="refresh" size={16} className={cx(q.fetching && 'animate-spin')} />
            </IconButton>
            {open ? (
              mine.data?.can?.cancel !== false && (
                <Button variant="secondary" icon="stop" loading={cmd.busy} onClick={() => setCancelling(true)}>
                  Cancel request
                </Button>
              )
            ) : job.status === 'failed' ? (
              <LaterButton phase={4} variant="secondary" icon="refresh">
                Retry
              </LaterButton>
            ) : null}
          </>
        }
      />

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
                <span className="text-tertiary">v{job.version}</span>
              </span>
            ),
          },
          { label: 'Requester', value: job.requester },
          {
            label: 'Channel',
            value: (
              <Tag size="sm" outline mono>
                {job.channel}
              </Tag>
            ),
          },
          { label: 'Started', value: <When at={job.startedAt} /> },
          { label: 'Ended', value: <When at={job.endedAt} /> },
          { label: 'Duration', value: <span className="tabular-nums">{elapsed}</span> },
          { label: 'Kind', value: job.kind === 'batch' && job.progress ? `batch · ${job.progress.done}/${job.progress.total} done${job.progress.failed ? `, ${job.progress.failed} failed` : ''}` : job.kind },
          ...(job.accountId ? [{ label: 'Account', value: <span className="font-mono">{formatAccountId(job.accountId)}</span> }] : []),
          ...(job.region ? [{ label: 'Region', value: job.region }] : []),
          ...(job.wfrn ? [{ label: 'Resource', value: <WfrnText wfrn={job.wfrn} size="md" href={wfrnPath ? href(wfrnPath) : undefined} />, wide: true }] : []),
        ]}
      />

      {(job.status === 'failed' || job.status === 'needs-attention' || job.error) && <FailureExplainer jobId={job.id} status={job.status} fallback={{ errorClass: job.errorClass, message: job.error?.message, step: job.failedStep }} className="mb-8" />}

      <Section title="Steps" description="What the workflow ran, in order." className="mb-8">
        {job.steps?.length ? <StepTimeline steps={job.steps} children={job.children} highlight={job.failedStep} defaultOpen={job.failedStep ? [job.failedStep] : []} /> : <EmptyState compact icon="list" title="No steps recorded" message="The platform kept only this request's summary." />}
      </Section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Section title="Input">{job.input ? <JsonView value={job.input} defaultExpanded={2} /> : <p className="text-sm text-tertiary">No input recorded.</p>}</Section>
        <Section title="Outputs">{job.outputs ? <JsonView value={job.outputs} defaultExpanded={2} /> : <p className="text-sm text-tertiary">{open ? 'Nothing yet.' : 'No outputs recorded.'}</p>}</Section>
      </div>

      {cancelling && (
        <ConfirmDialog
          title="Cancel this request?"
          tone="warning"
          icon="stop"
          confirmLabel="Cancel request"
          cancelLabel="Keep it running"
          message={
            <>
              The platform stops <span className="font-medium text-primary">{job.title}</span> at its next safe point. Steps that already ran are not undone; a create stopped before it finished leaves nothing behind.
            </>
          }
          onConfirm={async () => {
            await cmd.run('Cancel this request', () => bff.jobs.cancel(id), {
              invalidates: ['jobs', 'bff-job', 'console-resource', 'console-activity', 'resources'],
              done: () => ({ title: 'Request cancelled', message: 'The platform stops it at its next safe point.' }),
            })
            void q.refetch()
          }}
          onClose={() => setCancelling(false)}
        />
      )}
    </>
  )
}
