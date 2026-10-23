import { useMemo, useState } from 'react'
import { Button, EmptyState, Icon, IconButton, PageHeader, Stat, Tag, cx, duration, formatNumber, plural } from '@iaf/ui'
import { CursorPager, ErrorState, PropertyFilterBar, StatusBadge, VirtualTable, When, applyFilters, useCursorPaging, useNow, type FilterProperty, type FilterToken, type VirtualColumn, type VirtualSort } from '@iaf/console-kit'
import { navigate, pathFor, type PageProps } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { JobSummary } from '../lib/types'

/*
 * Requests: every job the platform ran, newest first, with the same filter bar and paging as the
 * resource lists. The tiles count what is on screen.
 */

const STATUSES = ['queued', 'running', 'waiting-approval', 'needs-attention', 'succeeded', 'failed', 'cancelled', 'rolled-back']
const CHANNELS = ['console', 'catalog', 'rest', 'mcp', 'trigger', 'batch']
const INDEXED = new Set(['status', 'workflow', 'requester', 'channel', 'kind'])

const PROPERTIES: FilterProperty[] = [
  { key: 'status', label: 'Status', values: STATUSES },
  { key: 'workflow', label: 'Workflow' },
  { key: 'requester', label: 'Requester' },
  { key: 'channel', label: 'Channel', values: CHANNELS },
  { key: 'kind', label: 'Kind', values: ['single', 'batch'] },
]

export function serverParamsOf(tokens: readonly FilterToken[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const t of tokens) {
    if (t.property === null) out.q = t.value
    else if (t.operator === '=' && INDEXED.has(t.property)) out[t.property] = t.value
  }
  return out
}

function fieldValue(job: JobSummary, property: string | null): unknown {
  if (property === null) return `${job.id} ${job.title} ${job.workflow} ${job.requester} ${job.status} ${job.channel}`
  return (job as unknown as Record<string, unknown>)[property]
}

function elapsed(job: JobSummary, now: number): string {
  if (job.durationMs !== undefined) return duration(job.durationMs)
  if (job.status === 'running' || job.status === 'waiting-approval' || job.status === 'needs-attention') return duration(now - Date.parse(job.startedAt))
  return duration(null)
}

const columnsFor = (now: number): VirtualColumn<JobSummary>[] => [
  {
    key: 'title',
    header: 'Request',
    sortable: true,
    width: 'minmax(16rem, 2fr)',
    minRem: 16,
    accessor: (j) => j.title,
    render: (j) => (
      <span className="min-w-0">
        <span className="block truncate font-medium text-primary">{j.title}</span>
        <span className="block truncate font-mono text-caption2 text-tertiary">{j.id}</span>
      </span>
    ),
  },
  { key: 'status', header: 'Status', sortable: true, width: '10rem', accessor: (j) => j.status, render: (j) => <StatusBadge status={j.status} size="sm" /> },
  {
    key: 'workflow',
    header: 'Workflow',
    sortable: true,
    width: 'minmax(10rem, 1fr)',
    minRem: 10,
    accessor: (j) => j.workflow,
    render: (j) => (
      <span className="min-w-0">
        <span className="block truncate font-mono text-caption text-primary">{j.workflow}</span>
        <span className="block truncate text-caption2 text-tertiary">v{j.version}</span>
      </span>
    ),
  },
  { key: 'requester', header: 'Requester', sortable: true, width: '8rem', minRem: 7, accessor: (j) => j.requester },
  {
    key: 'channel',
    header: 'Channel',
    sortable: true,
    width: '7rem',
    minRem: 6,
    accessor: (j) => j.channel,
    render: (j) => (
      <Tag size="sm" outline mono>
        {j.channel}
      </Tag>
    ),
  },
  { key: 'startedAt', header: 'Started', sortable: true, width: '8rem', minRem: 7, accessor: (j) => j.startedAt, render: (j) => <When at={j.startedAt} className="text-caption" /> },
  { key: 'duration', header: 'Duration', sortable: true, numeric: true, width: '6rem', minRem: 6, accessor: (j) => j.durationMs ?? null, render: (j) => <span className="tabular-nums">{elapsed(j, now)}</span> },
  {
    key: 'progress',
    header: 'Progress',
    width: '7rem',
    minRem: 6,
    accessor: (j) => (j.progress ? `${j.progress.done}/${j.progress.total}` : null),
    render: (j) =>
      j.progress ? (
        <span className="tabular-nums">
          {j.progress.done}/{j.progress.total}
          {j.progress.failed > 0 && <span className="text-tertiary"> · {j.progress.failed} failed</span>}
        </span>
      ) : (
        <span className="text-tertiary">single</span>
      ),
  },
]

export default function RequestsPage(_props: PageProps) {
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(50)
  const [tokens, setTokens] = useState<FilterToken[]>([])
  const [sort, setSort] = useState<VirtualSort>({ key: 'startedAt', dir: 'desc' })
  const server = useMemo(() => serverParamsOf(tokens), [tokens])
  const list = useQuery(['jobs', 'list', { limit: pageSize, after: paging.after ?? null, ...server }], () => api.jobs({ limit: pageSize, after: paging.after, ...server }))
  const now = useNow()
  const columns = useMemo(() => columnsFor(now), [now])
  const rows = useMemo(() => list.data?.items ?? [], [list.data])
  const filtered = useMemo(() => applyFilters(rows, tokens, fieldValue), [rows, tokens])

  const onTokens = (next: FilterToken[]) => {
    setTokens(next)
    paging.reset()
  }

  const count = (status: string) => filtered.filter((j) => j.status === status).length
  const countText = list.data ? (filtered.length === rows.length ? `${plural(rows.length, 'request')} on this page` : `${filtered.length} of ${plural(rows.length, 'request')} on this page`) : undefined

  return (
    <>
      <PageHeader
        title="Requests"
        subtitle="Every workflow run the platform recorded, newest first."
        actions={
          <IconButton label="Refresh the list" onClick={() => void list.refetch()}>
            <Icon name="refresh" size={16} className={cx(list.fetching && 'animate-spin')} />
          </IconButton>
        }
      />
      {filtered.length > 0 && (
        <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label="Running" value={formatNumber(count('running'))} icon="play" hint="on this page" />
          <Stat label="Waiting for approval" value={formatNumber(count('waiting-approval'))} icon="approval" hint="on this page" />
          <Stat label="Needing attention" value={formatNumber(count('needs-attention'))} icon="alert-triangle" hint="on this page" />
          <Stat label="Failed" value={formatNumber(count('failed'))} icon="close-circle" hint="on this page" />
        </div>
      )}
      <PropertyFilterBar properties={PROPERTIES} tokens={tokens} onChange={onTokens} placeholder="Filter requests" countText={countText} className="mb-3" />
      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the requests" onRetry={list.refetch} />
      ) : (
        <VirtualTable<JobSummary>
          columns={columns}
          rows={filtered}
          rowKey={(j) => j.id}
          label="Requests"
          loading={list.loading}
          rowHeight={52}
          sort={sort}
          onSortChange={setSort}
          onRowClick={(j) => navigate(pathFor('request', { id: j.id }))}
          maxHeight="max(18rem, calc(100dvh - 28rem))"
          empty={
            tokens.length ? (
              <EmptyState
                compact
                icon="filter"
                title="No requests match these filters"
                message="Loosen a filter, or clear them all."
                action={
                  <Button size="sm" onClick={() => onTokens([])}>
                    Clear filters
                  </Button>
                }
              />
            ) : (
              <EmptyState compact icon="job" title="No requests yet" message="Requests appear here as workflows run, whichever channel started them." />
            )
          }
          footer={
            <CursorPager
              count={filtered.length}
              hasNext={!!list.data?.next}
              hasPrevious={paging.hasPrevious}
              onNext={() => list.data?.next && paging.next(list.data.next)}
              onPrevious={paging.previous}
              pageSize={pageSize}
              onPageSizeChange={(size) => {
                setPageSize(size)
                paging.reset()
              }}
              page={paging.page}
              loading={list.fetching}
              noun="request"
            />
          }
        />
      )}
    </>
  )
}
