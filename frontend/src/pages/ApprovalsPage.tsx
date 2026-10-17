import { useState } from 'react'
import { Card, EmptyState, PageHeader, Skeleton, StateTag, Table, Tabs, Tag, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Approval } from '../lib/types'
import { ApprovalCard } from '../components/ApprovalCard'
import { ErrorState, Mono, TextLink, When, shortJobId } from '../components/common'

type Tab = 'waiting' | 'history'

const decidedAt = (a: Approval) => a.decidedAt ?? a.requestedAt

export default function ApprovalsPage(_: PageProps) {
  const toast = useToast()
  const [tab, setTab] = useState<Tab>('waiting')
  // the waiting list refreshes on its own: someone else may decide first
  const pending = useQuery(['approvals', 'pending'], () => api.approvals('pending'), { refetchInterval: 10_000 })
  const history = useQuery(
    ['approvals', 'history'],
    async () => {
      const [approved, rejected, expired] = await Promise.all([api.approvals('approved'), api.approvals('rejected'), api.approvals('expired')])
      return [...approved, ...rejected, ...expired].sort((a, b) => decidedAt(b).localeCompare(decidedAt(a)))
    },
    { enabled: tab === 'history' },
  )

  const decide = async (a: Approval, how: 'approve' | 'reject', comment: string) => {
    try {
      if (how === 'approve') await api.approve(a.id, comment || undefined)
      else await api.reject(a.id, comment || undefined)
      invalidate('approvals')
      invalidate('job')
      toast.toast({
        title: how === 'approve' ? 'Approved' : 'Rejected',
        message: how === 'approve' ? `${a.workflow} continues.` : `${a.workflow} stops at ${a.step}.`,
        tone: how === 'approve' ? 'healthy' : 'neutral',
        action: { label: 'Open job', onClick: () => navigate(pathFor('job', { id: a.jobId })) },
      })
    } catch (e) {
      toast.error(how === 'approve' ? 'Could not approve' : 'Could not reject', errorMessage(e))
      throw e
    }
  }

  const columns: TableColumn<Approval>[] = [
    { key: 'status', header: 'Decision', width: '8rem', sortable: true, accessor: (a) => a.status, render: (a) => <StateTag status={a.status} /> },
    {
      key: 'workflow',
      header: 'Workflow',
      sortable: true,
      accessor: (a) => a.workflow,
      render: (a) => (
        <div className="min-w-0">
          <TextLink to={pathFor('job', { id: a.jobId })} mono onClick={(e) => e.stopPropagation()}>
            {a.workflow}
          </TextLink>
          <div className="text-caption text-tertiary">
            step <Mono>{a.step}</Mono> · job <Mono title={a.jobId}>{shortJobId(a.jobId)}</Mono>
          </div>
        </div>
      ),
    },
    { key: 'message', header: 'Request', render: (a) => <span className="line-clamp-2 text-secondary">{a.message}</span> },
    {
      key: 'group',
      header: 'Group',
      width: '9rem',
      render: (a) => (
        <Tag size="sm" outline>
          {a.group}
        </Tag>
      ),
    },
    { key: 'by', header: 'Decided by', width: '10rem', render: (a) => <span className="text-primary">{a.decidedBy ?? (a.status === 'expired' ? 'nobody (timed out)' : '—')}</span> },
    { key: 'at', header: 'When', width: '9rem', sortable: true, accessor: decidedAt, render: (a) => <When at={decidedAt(a)} /> },
    { key: 'comment', header: 'Comment', render: (a) => <span className="text-secondary">{a.comment || '—'}</span> },
  ]

  return (
    <>
      <PageHeader title="Approvals" subtitle="Requests waiting for a group you belong to, with what the step is about to do.">
        <Tabs
          ariaLabel="Approvals"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'waiting', label: 'Waiting', icon: 'approval', count: pending.data?.length },
            { id: 'history', label: 'History', icon: 'history' },
          ]}
        />
      </PageHeader>

      {tab === 'waiting' ? (
        pending.error && !pending.data ? (
          <ErrorState error={pending.error} title="Could not load the approvals" onRetry={() => void pending.refetch()} />
        ) : pending.loading ? (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2" role="status" aria-label="Loading">
            {[0, 1].map((i) => (
              <Skeleton key={i} className="h-56" />
            ))}
          </div>
        ) : (pending.data ?? []).length === 0 ? (
          <Card flush>
            <EmptyState icon="check" iconClassName="text-healthy" title="Nothing waiting for you" message="When a workflow asks a group you belong to, the request appears here with its preview and a countdown." />
          </Card>
        ) : (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
            {(pending.data ?? []).map((a) => (
              <ApprovalCard key={a.id} approval={a} showPreview onApprove={(c) => decide(a, 'approve', c)} onReject={(c) => decide(a, 'reject', c)} className="animate-fade-in" />
            ))}
          </div>
        )
      ) : history.error && !history.data ? (
        <ErrorState error={history.error} title="Could not load the history" onRetry={() => void history.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={history.data ?? []}
          rowKey={(a) => a.id}
          loading={history.loading}
          onRowClick={(a) => navigate(pathFor('job', { id: a.jobId }))}
          defaultSort={{ key: 'at', dir: 'desc' }}
          emptyIcon="history"
          emptyTitle="No decisions yet"
          emptyMessage="Approved, rejected and expired requests stay here, with who decided and why."
          caption="Approval history"
        />
      )}
    </>
  )
}
