import { useState } from 'react'
import { PageHeader, Segmented, Table, type TableColumn } from '@iaf/ui'
import { ErrorState, When } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { accessWords, dashboardsApi, sharingWords, type Dashboard, type DashboardScope } from '../../lib/dashboards'
import { useQuery } from '../../lib/queries'
import { CreateDashboardDialog, PermitButton } from './DashboardDialogs'

/*
 * The account's dashboards this person reaches (WP 9.19): their own and those shared with them,
 * filtered in the address (`scope`), each with who sees it. Create is offered to those console
 * IAM lets create one; a viewer meets the refusal.
 */

const SCOPES: readonly { value: DashboardScope; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'mine', label: 'Mine' },
  { value: 'shared', label: 'Shared with me' },
]

export default function DashboardsPage({ route }: PageProps) {
  const { account } = useConsole()
  const scope: DashboardScope = SCOPES.some((s) => s.value === route.query.scope) ? (route.query.scope as DashboardScope) : 'all'
  const list = useQuery(['dashboards', account.id, scope], () => dashboardsApi.list({ scope }))
  const [creating, setCreating] = useState(false)
  const setScope = (v: DashboardScope) => navigate(pathFor('dashboards', {}, { scope: v === 'all' ? undefined : v }, { accountId: route.accountId }), { replace: true })

  const columns: TableColumn<Dashboard>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (d) => (
        <span className="block min-w-0">
          <Link to={pathFor('dashboard', { id: d.dashboardId })} className="font-medium text-accent underline-offset-2 hover:underline">
            {d.name}
          </Link>
          {d.description && <span className="block text-caption text-secondary">{d.description}</span>}
        </span>
      ),
    },
    { key: 'access', header: 'Yours or shared', render: (d) => <span className="text-caption text-primary">{accessWords(d)}</span>, width: '14rem' },
    { key: 'sharing', header: 'Who sees it', render: (d) => <span className="text-caption text-secondary">{sharingWords(d.sharing, account.name)}</span>, width: '16rem' },
    { key: 'widgets', header: 'Widgets', render: (d) => <span className="tabular-nums">{d.widgets.length}</span>, width: '6rem' },
    {
      key: 'updated',
      header: 'Changed',
      render: (d) => (
        <span className="text-caption text-secondary">
          <When at={d.updatedAt} />
          {d.updatedBy && <> by {d.updatedBy.name}</>}
        </span>
      ),
      width: '11rem',
    },
  ]

  return (
    <>
      <PageHeader
        title="Dashboards"
        subtitle={`Boards of charts and counts about ${account.name}, kept for yourself or shared with your team. Each widget shows only what its reader may see.`}
        actions={
          list.data && (
            <PermitButton permit={list.data.can.create} what="Create a dashboard" verb="create" variant="primary" icon="plus" onClick={() => setCreating(true)}>
              Create dashboard
            </PermitButton>
          )
        }
      />
      <div className="mb-4">
        <Segmented<DashboardScope> ariaLabel="Whose dashboards" size="sm" value={scope} onChange={setScope} options={SCOPES.map((s) => ({ value: s.value, label: s.label }))} />
      </div>
      {list.error ? (
        <ErrorState error={list.error} title="Could not load the dashboards" onRetry={() => void list.refetch()} />
      ) : (
        <Table<Dashboard>
          caption={`Dashboards of ${account.name}, ${SCOPES.find((s) => s.value === scope)?.label.toLowerCase()}`}
          columns={columns}
          rows={list.data?.items ?? []}
          rowKey={(d) => d.dashboardId}
          loading={list.loading}
          serverSort
          emptyIcon="grid"
          emptyTitle={scope === 'shared' ? 'Nothing is shared with you yet' : 'No dashboards yet'}
          emptyMessage={scope === 'shared' ? 'When someone shares a dashboard with you or one of your groups, it shows here.' : 'A dashboard puts the charts and counts you check every day on one page, for you or your team.'}
        />
      )}
      {creating && <CreateDashboardDialog onClose={() => setCreating(false)} />}
    </>
  )
}
