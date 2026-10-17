import { useMemo, useState } from 'react'
import { Button, Input, PageHeader, ProgressBar, Segmented, Select, StateTag, Table, Tag, duration, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { JobStatus, JobSummary } from '../lib/types'
import { ErrorState, Mono, TextLink, When, shortJobId, useNow } from '../components/common'

const ACTIVE = new Set<string>(['queued', 'running', 'waiting-approval', 'needs-attention'])
/** Queued, running, or waiting on a person: the job still changes. */
export const isActiveJob = (status: string): boolean => ACTIVE.has(status)

const STATUSES: { value: JobStatus; label: string }[] = [
  { value: 'queued', label: 'Queued' },
  { value: 'running', label: 'Running' },
  { value: 'waiting-approval', label: 'Waiting for approval' },
  { value: 'needs-attention', label: 'Needs attention' },
  { value: 'succeeded', label: 'Succeeded' },
  { value: 'failed', label: 'Failed' },
  { value: 'cancelled', label: 'Cancelled' },
  { value: 'rolled-back', label: 'Rolled back' },
]

type Scope = 'all' | 'active' | 'finished'

export default function JobsPage({ route }: PageProps) {
  const [q, setQ] = useState('')
  const [workflow, setWorkflow] = useState(route.query.workflow ?? '')
  const [status, setStatus] = useState<string>(route.query.status ?? '')
  const [scope, setScope] = useState<Scope>('all')
  const [polling, setPolling] = useState(true)
  // durations tick every second only while something runs
  const now = useNow(polling ? 1000 : 30_000)

  const workflows = useQuery('workflows', () => api.workflows())
  const jobs = useQuery(['jobs', workflow, status], () => api.jobs({ workflow: workflow || undefined, status: (status || undefined) as JobStatus | undefined, limit: 200 }), {
    refetchInterval: polling ? 5000 : undefined,
  })
  // every 5 s while something is still moving, then at rest
  const anyActive = !jobs.data || jobs.data.some((j) => isActiveJob(j.status))
  if (anyActive !== polling) setPolling(anyActive)

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (jobs.data ?? []).filter((j) => {
      if (scope === 'active' && !isActiveJob(j.status)) return false
      if (scope === 'finished' && isActiveJob(j.status)) return false
      return !needle || `${j.id} ${j.title} ${j.workflow} ${j.requester}`.toLowerCase().includes(needle)
    })
  }, [jobs.data, q, scope])

  const columns: TableColumn<JobSummary>[] = [
    {
      key: 'title',
      header: 'Job',
      render: (j) => (
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-primary">{j.title}</span>
            {j.kind === 'batch' && (
              <Tag size="sm" outline>
                batch
              </Tag>
            )}
          </div>
          <Mono className="text-caption text-tertiary" title={j.id}>
            {shortJobId(j.id)}
          </Mono>
        </div>
      ),
    },
    { key: 'status', header: 'Status', sortable: true, accessor: (j) => j.status, width: '11rem', render: (j) => <StateTag status={j.status} /> },
    {
      key: 'workflow',
      header: 'Workflow',
      sortable: true,
      accessor: (j) => j.workflow,
      render: (j) => (
        <span className="inline-flex items-center gap-1.5">
          <TextLink to={pathFor('workflow', { name: j.workflow })} mono onClick={(e) => e.stopPropagation()}>
            {j.workflow}
          </TextLink>
          <Mono className="text-caption text-tertiary">{j.version}</Mono>
        </span>
      ),
    },
    { key: 'requester', header: 'Requester', sortable: true, accessor: (j) => j.requester, render: (j) => <span className="text-primary">{j.requester}</span> },
    {
      key: 'channel',
      header: 'Via',
      width: '6rem',
      render: (j) => (
        <Tag size="sm" outline>
          {j.channel}
        </Tag>
      ),
    },
    { key: 'started', header: 'Started', sortable: true, accessor: (j) => j.startedAt, width: '9rem', render: (j) => <When at={j.startedAt} now={now} /> },
    {
      key: 'duration',
      header: 'Duration',
      numeric: true,
      width: '7rem',
      render: (j) => {
        const ms = j.durationMs ?? (j.endedAt ? Date.parse(j.endedAt) - Date.parse(j.startedAt) : isActiveJob(j.status) ? now - Date.parse(j.startedAt) : undefined)
        return <span className="tabular-nums text-secondary">{ms !== undefined && Number.isFinite(ms) && ms >= 0 ? duration(ms) : '—'}</span>
      },
    },
    {
      key: 'progress',
      header: 'Progress',
      width: '9rem',
      render: (j) =>
        j.progress && j.progress.total > 0 ? (
          <div className="flex items-center gap-2">
            <ProgressBar size="sm" value={j.progress.done} max={j.progress.total} tone={j.progress.failed > 0 ? 'warning' : 'accent'} label={`${j.progress.done} of ${j.progress.total}`} className="w-16" />
            <span className="text-caption tabular-nums text-tertiary">
              {j.progress.done}/{j.progress.total}
              {j.progress.failed > 0 && <span className="text-critical"> · {j.progress.failed} failed</span>}
            </span>
          </div>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
  ]

  const filtered = !!q || !!workflow || !!status || scope !== 'all'
  const activeCount = (jobs.data ?? []).filter((j) => isActiveJob(j.status)).length

  return (
    <>
      <PageHeader
        title="Jobs"
        subtitle="Every run, from any door: the catalog, the API, an MCP tool, a trigger or a batch."
        actions={
          activeCount > 0 ? (
            <Tag tone="accent" dot pulse title="Refreshing every 5 seconds while jobs are active">
              {activeCount} active
            </Tag>
          ) : undefined
        }
      >
        <div className="flex flex-wrap items-center gap-2">
          <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search id, title or requester" aria-label="Search jobs" className="sm:max-w-xs" />
          <Select
            ariaLabel="Workflow"
            size="sm"
            value={workflow}
            onChange={setWorkflow}
            placeholder="Any workflow"
            mono
            options={[{ value: '', label: 'Any workflow' }, ...(workflows.data ?? []).map((w) => ({ value: w.name, label: w.name, hint: w.title }))]}
          />
          <Select ariaLabel="Status" size="sm" value={status} onChange={setStatus} placeholder="Any status" options={[{ value: '', label: 'Any status' }, ...STATUSES]} />
          <Segmented
            ariaLabel="Scope"
            size="sm"
            value={scope}
            onChange={setScope}
            options={[
              { value: 'all', label: 'All' },
              { value: 'active', label: 'Active' },
              { value: 'finished', label: 'Finished' },
            ]}
          />
          {filtered && (
            <Button size="sm" variant="ghost" icon="close" onClick={() => { setQ(''); setWorkflow(''); setStatus(''); setScope('all') }}>
              Clear
            </Button>
          )}
        </div>
      </PageHeader>

      {jobs.error && !jobs.data ? (
        <ErrorState error={jobs.error} title="Could not load the jobs" onRetry={() => void jobs.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(j) => j.id}
          loading={jobs.loading}
          onRowClick={(j) => navigate(pathFor('job', { id: j.id }))}
          defaultSort={{ key: 'started', dir: 'desc' }}
          emptyIcon="job"
          emptyTitle={filtered ? 'No jobs match' : 'No jobs yet'}
          emptyMessage={filtered ? 'Loosen the filters to see more.' : 'Run a workflow from the catalog and it shows up here within a second.'}
          caption="Jobs"
        />
      )}
    </>
  )
}
