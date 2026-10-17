import { useMemo, useState } from 'react'
import { Button, Input, PageHeader, Select, Skeleton, Table, Tabs, Tag, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { Resource } from '../lib/types'
import { ErrorState, Mono, TextLink, When, resourceTone, shortJobId } from '../components/common'

const ALL = '__all'

export default function ResourcesPage({ route }: PageProps) {
  const types = useQuery('resource-types', () => api.resourceTypes())
  const [type, setType] = useState<string>(route.query.type ?? ALL)
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [owner, setOwner] = useState('')
  const list = useQuery(['resources', type], () => api.resources(type === ALL ? undefined : { type }))

  const current = types.data?.find((t) => t.name === type)
  const statuses = useMemo(() => (current ? current.states : Array.from(new Set((list.data ?? []).map((r) => r.status))).sort()), [current, list.data])
  const owners = useMemo(() => Array.from(new Set((list.data ?? []).map((r) => r.owner))).sort(), [list.data])

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (list.data ?? []).filter((r) => {
      if (status && r.status !== status) return false
      if (owner && r.owner !== owner) return false
      return !needle || `${r.id} ${r.name ?? ''} ${r.type} ${r.createdBy.workflow} ${JSON.stringify(r.attributes)}`.toLowerCase().includes(needle)
    })
  }, [list.data, q, status, owner])

  const columns: TableColumn<Resource>[] = [
    {
      key: 'name',
      header: 'Resource',
      sortable: true,
      accessor: (r) => r.name ?? r.id,
      render: (r) => (
        <div className="min-w-0">
          <div className="font-medium text-primary">{r.name ?? r.id}</div>
          {r.name && <Mono className="text-tertiary">{r.id}</Mono>}
        </div>
      ),
    },
    ...(type === ALL
      ? [
          {
            key: 'type',
            header: 'Type',
            sortable: true,
            width: '8rem',
            accessor: (r: Resource) => r.type,
            render: (r: Resource) => (
              <Tag size="sm" outline mono>
                {r.type}
              </Tag>
            ),
          } satisfies TableColumn<Resource>,
        ]
      : []),
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      width: '8rem',
      accessor: (r) => r.status,
      render: (r) => (
        <Tag size="sm" tone={resourceTone(r.status)} dot>
          {r.status}
        </Tag>
      ),
    },
    { key: 'owner', header: 'Owner', sortable: true, width: '9rem', accessor: (r) => r.owner, render: (r) => <span className="text-primary">{r.owner}</span> },
    {
      key: 'visibility',
      header: 'Visibility',
      width: '7rem',
      render: (r) => (
        <Tag size="sm" outline>
          {r.visibility}
        </Tag>
      ),
    },
    {
      key: 'origin',
      header: 'Created by',
      render: (r) => (
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <TextLink to={pathFor('workflow', { name: r.createdBy.workflow })} mono onClick={(e) => e.stopPropagation()}>
            {r.createdBy.workflow}
          </TextLink>
          <span className="text-tertiary">·</span>
          <TextLink to={pathFor('job', { id: r.createdBy.jobId })} mono onClick={(e) => e.stopPropagation()}>
            {shortJobId(r.createdBy.jobId)}
          </TextLink>
        </span>
      ),
    },
    { key: 'built', header: 'Built from', width: '6rem', numeric: true, render: (r) => <span className={r.builtFrom.length ? 'text-primary' : 'text-tertiary'}>{r.builtFrom.length || '—'}</span> },
    { key: 'created', header: 'Created', sortable: true, width: '9rem', accessor: (r) => r.createdAt, render: (r) => <When at={r.createdAt} /> },
  ]

  const filtered = !!q || !!status || !!owner

  return (
    <>
      <PageHeader title="Resources" subtitle={current ? `${current.plural}: owned by ${current.owner}${current.producedBy ? `, created by ${current.producedBy}` : ''}.` : 'What workflows created, by type, with where each one came from.'}>
        <div className="space-y-3">
          {types.loading && !types.data ? (
            <Skeleton className="h-8 w-80" />
          ) : (
            <Tabs
              ariaLabel="Resource types"
              value={type}
              onChange={(v) => {
                setType(v)
                setStatus('')
              }}
              tabs={[{ id: ALL, label: 'All', icon: 'resource' }, ...(types.data ?? []).map((t) => ({ id: t.name, label: t.plural }))]}
            />
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search name, id or attribute" aria-label="Search resources" className="sm:max-w-xs" />
            <Select ariaLabel="Status" size="sm" value={status} onChange={setStatus} options={[{ value: '', label: 'Any status' }, ...statuses.map((s) => ({ value: s, label: s }))]} />
            <Select ariaLabel="Owner" size="sm" value={owner} onChange={setOwner} options={[{ value: '', label: 'Any owner' }, ...owners.map((o) => ({ value: o, label: o }))]} />
            {filtered && (
              <Button size="sm" variant="ghost" icon="close" onClick={() => { setQ(''); setStatus(''); setOwner('') }}>
                Clear
              </Button>
            )}
            {current?.producedBy && (
              <Button size="sm" icon="plus" className="ml-auto" onClick={() => navigate(pathFor('catalog-run', { workflow: current.producedBy! }))}>
                New {current.name}
              </Button>
            )}
          </div>
        </div>
      </PageHeader>

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the resources" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          loading={list.loading}
          onRowClick={(r) => navigate(pathFor('resource', { id: r.id }))}
          defaultSort={{ key: 'created', dir: 'desc' }}
          emptyIcon="resource"
          emptyTitle={filtered ? 'Nothing matches' : current ? `No ${current.plural} yet` : 'No resources yet'}
          emptyMessage={filtered ? 'Loosen the filters to see more.' : 'A workflow’s register step records what it created; it shows up here with its lineage.'}
          caption="Resources"
        />
      )}
    </>
  )
}
