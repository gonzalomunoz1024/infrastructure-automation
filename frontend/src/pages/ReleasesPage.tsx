import { useMemo, useState } from 'react'
import { PageHeader, ProgressBar, Segmented, Select, StateTag, StatusDot, Table, Tag, duration, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { Release, ReleaseGate } from '../lib/types'
import { ErrorState, Mono, TextLink, When, useNow } from '../components/common'

/** A canary still moving or held: it can change under you. */
export const isActiveRelease = (r: Pick<Release, 'status'>): boolean => r.status === 'canary' || r.status === 'paused'

export function gateDot(g: Pick<ReleaseGate, 'passed'>) {
  return g.passed === true ? 'healthy' : g.passed === false ? 'critical' : 'neutral'
}

export function stageLabel(r: Release): string {
  if (r.status === 'promoted') return 'Promoted'
  if (r.status === 'rolled-back') return 'Rolled back'
  if (r.strategy === 'all') return 'All at once'
  return `Stage ${Math.min(r.stageIndex + 1, r.stages.length)} of ${r.stages.length}`
}

type Scope = 'active' | 'all'

export default function ReleasesPage({ route }: PageProps) {
  const [workflow, setWorkflow] = useState(route.query.workflow ?? '')
  const [scope, setScope] = useState<Scope>('all')
  const [polling, setPolling] = useState(true)
  const now = useNow(polling ? 1000 : 30_000)
  const workflows = useQuery('workflows', () => api.workflows())
  const list = useQuery(['releases', workflow], () => api.releases(workflow || undefined), { refetchInterval: polling ? 5000 : undefined })
  // every 5 s while a canary is in flight, then at rest
  const anyActive = !list.data || list.data.some(isActiveRelease)
  if (anyActive !== polling) setPolling(anyActive)

  const rows = useMemo(() => (list.data ?? []).filter((r) => scope === 'all' || isActiveRelease(r)), [list.data, scope])

  const columns: TableColumn<Release>[] = [
    {
      key: 'workflow',
      header: 'Workflow',
      sortable: true,
      accessor: (r) => r.workflow,
      render: (r) => (
        <div className="min-w-0">
          <TextLink to={pathFor('workflow', { name: r.workflow })} mono onClick={(e) => e.stopPropagation()}>
            {r.workflow}
          </TextLink>
          <div className="text-caption text-tertiary">
            <Mono>{r.from ?? 'new'}</Mono> → <Mono className="text-primary">{r.to}</Mono>
          </div>
        </div>
      ),
    },
    { key: 'status', header: 'Status', sortable: true, width: '9rem', accessor: (r) => r.status, render: (r) => <StateTag status={r.status} /> },
    {
      key: 'strategy',
      header: 'Strategy',
      width: '7rem',
      render: (r) => (
        <Tag size="sm" outline>
          {r.strategy}
        </Tag>
      ),
    },
    {
      key: 'stage',
      header: 'Stage',
      width: '13rem',
      render: (r) => (
        <div className="space-y-1">
          <div className="flex items-baseline justify-between gap-2 text-caption">
            <span className="text-primary">{stageLabel(r)}</span>
            <span className="tabular-nums text-secondary">{r.stagePercent}%</span>
          </div>
          <ProgressBar size="sm" value={r.stagePercent} tone={r.status === 'rolled-back' ? 'critical' : r.status === 'paused' ? 'warning' : 'accent'} label={`${r.stagePercent}% of traffic on ${r.to}`} />
        </div>
      ),
    },
    {
      key: 'gates',
      header: 'Gates',
      width: '8rem',
      render: (r) =>
        r.gates.length === 0 ? (
          <span className="text-tertiary">—</span>
        ) : (
          <span className="inline-flex items-center gap-1" title={r.gates.map((g) => `${g.label}: ${g.passed === null ? 'pending' : g.passed ? 'passed' : 'failed'}`).join('\n')}>
            {r.gates.map((g) => (
              <StatusDot key={g.id} kind={gateDot(g)} />
            ))}
            <span className="ml-1 text-caption tabular-nums text-secondary">
              {r.gates.filter((g) => g.passed).length}/{r.gates.length}
            </span>
          </span>
        ),
    },
    {
      key: 'inStage',
      header: 'In stage',
      width: '7rem',
      numeric: true,
      render: (r) => <span className="tabular-nums text-secondary">{isActiveRelease(r) ? duration(now - Date.parse(r.stageStartedAt)) : '—'}</span>,
    },
    { key: 'started', header: 'Started', sortable: true, width: '9rem', accessor: (r) => r.startedAt, render: (r) => <When at={r.startedAt} now={now} /> },
  ]

  return (
    <>
      <PageHeader title="Releases" subtitle="How new versions reach traffic: stage by stage, gated on how they behave.">
        <div className="flex flex-wrap items-center gap-2">
          <Select
            ariaLabel="Workflow"
            size="sm"
            mono
            value={workflow}
            onChange={setWorkflow}
            options={[{ value: '', label: 'Every workflow' }, ...(workflows.data ?? []).map((w) => ({ value: w.name, label: w.name, hint: w.title }))]}
          />
          <Segmented
            ariaLabel="Scope"
            size="sm"
            value={scope}
            onChange={setScope}
            options={[
              { value: 'all', label: 'All' },
              { value: 'active', label: 'In flight' },
            ]}
          />
        </div>
      </PageHeader>

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the releases" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          loading={list.loading}
          onRowClick={(r) => navigate(pathFor('release', { id: r.id }))}
          defaultSort={{ key: 'started', dir: 'desc' }}
          emptyIcon="release"
          emptyTitle={scope === 'active' ? 'Nothing in flight' : 'No releases yet'}
          emptyMessage={scope === 'active' ? 'Every release has finished.' : 'Merging a change request with the canary strategy starts one.'}
          caption="Releases"
        />
      )}
    </>
  )
}
