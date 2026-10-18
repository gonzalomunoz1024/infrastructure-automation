/**
 * Change requests as a table: what each one changes (title, kind, name and version), who opened
 * it, where it stands, its checks as dots and when it was opened; a row opens the change request.
 * The Workflows page's "Change requests" tab lists both kinds with it (GET /change-requests), so a
 * reviewer on the owner team finds what waits for them, not only the author through a link.
 */
import { StateTag, StatusDot, Table, Tag, type TableColumn } from '@iaf/ui'
import { navigate, pathFor } from '../app/router'
import type { ChangeRequest, ChangeRequestStatus, Check } from '../lib/types'
import { Mono, When, checkDot } from './common'

export type ChangeRequestFilter = ChangeRequestStatus | 'all'

export const CHANGE_REQUEST_FILTERS: { value: ChangeRequestFilter; label: string }[] = [
  { value: 'open', label: 'Open' },
  { value: 'merged', label: 'Merged' },
  { value: 'closed', label: 'Closed' },
  { value: 'all', label: 'All' },
]

/** `?status=` as the filter: open unless it names another. */
export function filterOf(q: string | undefined): ChangeRequestFilter {
  return q === 'merged' || q === 'closed' || q === 'all' ? q : 'open'
}

const WORST_FIRST: Check['status'][] = ['failed', 'warning', 'passed', 'skipped']

/** "1 failed, 3 passed": the checks counted by result, the worst first. */
export function checksSummary(checks: readonly Check[]): string {
  if (checks.length === 0) return 'none yet'
  return WORST_FIRST.map((s) => [s, checks.filter((c) => c.status === s).length] as const)
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${n} ${s}`)
    .join(', ')
}

/** One dot per check, in the order they ran; the label counts them, the tooltip names each. */
export function CheckDots({ checks }: { checks: readonly Check[] }) {
  if (checks.length === 0) return <span className="text-caption text-tertiary">none yet</span>
  return (
    <span role="img" aria-label={`Checks: ${checksSummary(checks)}`} title={checks.map((c) => `${c.label}: ${c.status}`).join('\n')} className="inline-flex flex-wrap items-center gap-1">
      {checks.map((c) => (
        <StatusDot key={c.id} kind={checkDot(c.status)} />
      ))}
    </span>
  )
}

/** What the table says when the filter leaves nothing. */
export function emptyChangeRequests(filter: ChangeRequestFilter): { title: string; message: string } {
  if (filter === 'open') return { title: 'Nothing waits for review', message: 'Publishing a workflow version or changing a provider opens a change request; the team that owns it reviews it here.' }
  if (filter === 'merged') return { title: 'No merged change requests', message: 'Merging a change request publishes its version.' }
  if (filter === 'closed') return { title: 'No closed change requests', message: 'A change request closed without merging publishes nothing.' }
  return { title: 'No change requests yet', message: 'Publishing a workflow version or changing a provider opens one.' }
}

export const CHANGE_REQUEST_COLUMNS: TableColumn<ChangeRequest>[] = [
  {
    key: 'title',
    header: 'Change request',
    sortable: true,
    accessor: (c) => c.title,
    render: (c) => (
      <div className="min-w-0">
        <div className="font-medium text-primary">{c.title}</div>
        <Mono className="text-tertiary">{c.id}</Mono>
      </div>
    ),
  },
  {
    key: 'kind',
    header: 'Kind',
    sortable: true,
    accessor: (c) => c.kind,
    render: (c) => (
      <Tag size="sm" outline icon={c.kind === 'provider' ? 'provider' : 'workflow'}>
        {c.kind}
      </Tag>
    ),
  },
  { key: 'name', header: 'Name', sortable: true, accessor: (c) => c.name, render: (c) => <Mono className="text-primary">{c.name}</Mono> },
  { key: 'version', header: 'Version', render: (c) => <Mono>{c.version}</Mono> },
  { key: 'author', header: 'Author', sortable: true, accessor: (c) => c.author, render: (c) => <span className="text-primary">{c.author}</span> },
  { key: 'status', header: 'Status', sortable: true, accessor: (c) => c.status, render: (c) => <StateTag status={c.status} size="sm" /> },
  { key: 'checks', header: 'Checks', render: (c) => <CheckDots checks={c.checks} /> },
  { key: 'createdAt', header: 'Opened', sortable: true, align: 'right', accessor: (c) => c.createdAt, render: (c) => <When at={c.createdAt} /> },
]

export interface ChangeRequestTableProps {
  rows: ChangeRequest[]
  loading?: boolean
  /** the status the rows were asked for: it words the empty state */
  filter: ChangeRequestFilter
}

export function ChangeRequestTable({ rows, loading, filter }: ChangeRequestTableProps) {
  const empty = emptyChangeRequests(filter)
  return (
    <Table
      columns={CHANGE_REQUEST_COLUMNS}
      rows={rows}
      rowKey={(c) => c.id}
      loading={loading}
      onRowClick={(c) => navigate(pathFor('change-request', { id: c.id }))}
      defaultSort={{ key: 'createdAt', dir: 'desc' }}
      emptyIcon="git-branch"
      emptyTitle={empty.title}
      emptyMessage={empty.message}
      caption="Change requests"
    />
  )
}
