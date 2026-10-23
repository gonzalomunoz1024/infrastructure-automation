import { Card, EmptyState, Icon, IconButton, JsonView, PageHeader, SkeletonRows, Tag, cx, duration } from '@iaf/ui'
import { CopyableId, ErrorState, Facts, Section, StatusBadge, StepTimeline, When, WfrnText, useNow } from '@iaf/console-kit'
import { pathOfWfrn, useConsole } from '../app/console'
import { LaterButton } from '../app/Phase'
import { href, type PageProps } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'

/*
 * One request: its facts, what went wrong if it did, the steps it ran, and its input and
 * outputs as the platform holds them.
 */

export default function RequestPage({ route }: PageProps) {
  const id = route.params.id
  const { types } = useConsole()
  const q = useQuery(['job', id], () => api.job(id))
  const now = useNow()
  const job = q.data

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
              <LaterButton phase={3} variant="secondary" icon="stop">
                Cancel
              </LaterButton>
            ) : job.status === 'failed' ? (
              <LaterButton phase={3} variant="secondary" icon="refresh">
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

      {(job.status === 'failed' || job.error) && (
        <Card title="What went wrong" subtitle={job.failedStep ? `In step ${job.failedStep}` : undefined} className="mb-8">
          <div className="flex items-start gap-3 text-sm">
            <Icon name="alert-triangle" size={18} className="mt-0.5 shrink-0 text-critical" />
            <div className="min-w-0 space-y-1">
              {job.errorClass && (
                <div>
                  <span className="text-secondary">Class </span>
                  <span className="font-mono text-primary">{job.errorClass}</span>
                </div>
              )}
              <p className="text-primary">{job.error?.message ?? 'The platform recorded a failure without a message.'}</p>
            </div>
          </div>
        </Card>
      )}

      <Section title="Steps" description="What the workflow ran, in order." className="mb-8">
        {job.steps?.length ? <StepTimeline steps={job.steps} children={job.children} highlight={job.failedStep} defaultOpen={job.failedStep ? [job.failedStep] : []} /> : <EmptyState compact icon="list" title="No steps recorded" message="The platform kept only this request's summary." />}
      </Section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Section title="Input">{job.input ? <JsonView value={job.input} defaultExpanded={2} /> : <p className="text-sm text-tertiary">No input recorded.</p>}</Section>
        <Section title="Outputs">{job.outputs ? <JsonView value={job.outputs} defaultExpanded={2} /> : <p className="text-sm text-tertiary">{open ? 'Nothing yet.' : 'No outputs recorded.'}</p>}</Section>
      </div>
    </>
  )
}
