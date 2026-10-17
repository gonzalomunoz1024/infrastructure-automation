import { useMemo, useState } from 'react'
import { Button, Icon, Input, PageHeader, Table, Tag, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { Credential } from '../lib/types'
import { AddCredentialModal } from '../components/CredentialPicker'
import { ErrorState, Mono, When } from '../components/common'

export default function CredentialsPage(_: PageProps) {
  const list = useQuery('credentials', () => api.credentials())
  const [q, setQ] = useState('')
  const [adding, setAdding] = useState(false)

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (list.data ?? []).filter((c) => !needle || `${c.name} ${c.type} ${c.path}`.toLowerCase().includes(needle))
  }, [list.data, q])

  const columns: TableColumn<Credential>[] = [
    { key: 'name', header: 'Name', sortable: true, accessor: (c) => c.name, render: (c) => <Mono className="text-primary">{c.name}</Mono> },
    {
      key: 'type',
      header: 'Type',
      sortable: true,
      accessor: (c) => c.type,
      width: '10rem',
      render: (c) => (
        <Tag size="sm" outline>
          {c.type}
        </Tag>
      ),
    },
    { key: 'path', header: 'Reference in a provider file', render: (c) => <Mono>{c.path}</Mono> },
    { key: 'updated', header: 'Updated', sortable: true, accessor: (c) => c.updatedAt, width: '9rem', render: (c) => <When at={c.updatedAt} /> },
    {
      key: 'value',
      header: 'Value',
      width: '9rem',
      render: () => (
        <span className="inline-flex items-center gap-1 text-caption text-tertiary">
          <Icon name="lock" size={12} />
          never shown
        </span>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Credentials"
        subtitle="What providers sign in with. A value is written once and referenced by name; nothing here can read it back."
        actions={
          <Button variant="primary" icon="plus" onClick={() => setAdding(true)}>
            Add credential
          </Button>
        }
      >
        <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name or type" aria-label="Search credentials" className="sm:max-w-xs" />
      </PageHeader>

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the credentials" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(c) => c.name}
          loading={list.loading}
          defaultSort={{ key: 'name', dir: 'asc' }}
          emptyIcon="key"
          emptyTitle={q ? 'Nothing matches' : 'No credentials yet'}
          emptyMessage={q ? 'Try another name.' : 'Add the token or password a provider needs; the Add provider wizard can do it too.'}
          caption="Credentials"
        />
      )}

      {adding && <AddCredentialModal onClose={() => setAdding(false)} />}
    </>
  )
}
