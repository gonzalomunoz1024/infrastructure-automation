import { useMemo, useState } from 'react'
import { Button, Icon, Input, PageHeader, Table, Tag, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { Credential } from '../lib/types'
import { AddCredentialModal } from '../components/CredentialPicker'
import { ErrorState, Mono, When } from '../components/common'

/** The team a credential belongs to; one without an owner (seeded from content) is any team's to use. */
export function OwnerTag({ owner }: { owner?: string }) {
  if (!owner) return <span className="text-caption text-tertiary">any team</span>
  return (
    <Tag size="sm" outline mono>
      {owner}
    </Tag>
  )
}

/** The credentials whose name, type, reference or owner team contains the search text. */
export function filterCredentials(rows: Credential[], q: string): Credential[] {
  const needle = q.trim().toLowerCase()
  return rows.filter((c) => !needle || `${c.name} ${c.type} ${c.path} ${c.owner ?? ''}`.toLowerCase().includes(needle))
}

const COLUMNS: TableColumn<Credential>[] = [
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
  { key: 'owner', header: 'Owner', sortable: true, accessor: (c) => c.owner ?? '', width: '11rem', render: (c) => <OwnerTag owner={c.owner} /> },
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

/** The store's credentials: name, type, the team that may use each, the reference a file carries. Never a value. */
export function CredentialsTable({ rows, loading, q = '' }: { rows: Credential[]; loading?: boolean; q?: string }) {
  return (
    <Table
      columns={COLUMNS}
      rows={rows}
      rowKey={(c) => c.name}
      loading={loading}
      defaultSort={{ key: 'name', dir: 'asc' }}
      emptyIcon="key"
      emptyTitle={q ? 'Nothing matches' : 'No credentials yet'}
      emptyMessage={q ? 'Try another name or team.' : 'Add the token or password a provider needs; the Add provider wizard can do it too.'}
      caption="Credentials"
    />
  )
}

export default function CredentialsPage(_: PageProps) {
  const list = useQuery('credentials', () => api.credentials())
  const [q, setQ] = useState('')
  const [adding, setAdding] = useState(false)

  const rows = useMemo(() => filterCredentials(list.data ?? [], q), [list.data, q])

  return (
    <>
      <PageHeader
        title="Credentials"
        subtitle="What providers sign in with. A value is written once and referenced by name; nothing here can read it back. A credential belongs to a team: only its members, and admins, can send it anywhere."
        actions={
          <Button variant="primary" icon="plus" onClick={() => setAdding(true)}>
            Add credential
          </Button>
        }
      >
        <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name, type or team" aria-label="Search credentials" className="sm:max-w-xs" />
      </PageHeader>

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the credentials" onRetry={() => void list.refetch()} />
      ) : (
        <CredentialsTable rows={rows} loading={list.loading} q={q} />
      )}

      {adding && <AddCredentialModal onClose={() => setAdding(false)} />}
    </>
  )
}
