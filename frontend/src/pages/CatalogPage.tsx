import { useMemo, useState } from 'react'
import { Button, Card, EmptyState, Input, PageHeader, Segmented, Skeleton, Table, Tag, useLocalStorage, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { CatalogEntry } from '../lib/types'
import { ApiLinks } from '../components/ApiLinks'
import { DeprecatedTag } from '../components/Deprecation'
import { ErrorState, Mono } from '../components/common'

type View = 'table' | 'cards'

function Ways({ entry, size = 'sm' }: { entry: CatalogEntry; size?: 'sm' | 'md' }) {
  return (
    <span className="inline-flex flex-wrap gap-1">
      {entry.previewPath && (
        <Tag size={size} tone="accent" title="A blast-radius preview before the run">
          Preview
        </Tag>
      )}
      {entry.batchPath && (
        <Tag size={size} title={`Bulk runs in waves, up to ${entry.batch.max} rows`}>
          Batch
        </Tag>
      )}
      {entry.mcpTool && (
        <Tag size={size} mono outline title={`MCP tool ${entry.mcpTool}`}>
          MCP
        </Tag>
      )}
    </span>
  )
}

function Endpoint({ entry }: { entry: CatalogEntry }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5">
      <Tag size="sm" mono outline>
        {entry.method.toUpperCase()}
      </Tag>
      <Mono className="truncate text-primary">{entry.path}</Mono>
    </span>
  )
}

export default function CatalogPage(_: PageProps) {
  const catalog = useQuery('catalog', () => api.catalog())
  const [view, setView] = useLocalStorage<View>('iaf-catalog-view', 'table')
  const [q, setQ] = useState('')

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (catalog.data ?? []).filter((e) => !needle || `${e.title} ${e.workflow} ${e.noun} ${e.verb} ${e.description ?? ''} ${e.owner} ${e.path}`.toLowerCase().includes(needle))
  }, [catalog.data, q])

  const columns: TableColumn<CatalogEntry>[] = [
    {
      key: 'title',
      header: 'Workflow',
      sortable: true,
      accessor: (e) => e.title,
      render: (e) => (
        <div className="min-w-0 max-w-md">
          <div className="flex flex-wrap items-center gap-2 font-medium text-primary">
            {e.title}
            {e.deprecated && <DeprecatedTag deprecated={e.deprecated} />}
          </div>
          {e.description && <div className="truncate text-caption text-secondary">{e.description}</div>}
        </div>
      ),
    },
    { key: 'endpoint', header: 'Endpoint', render: (e) => <Endpoint entry={e} /> },
    { key: 'version', header: 'Version', sortable: true, accessor: (e) => e.version, render: (e) => <Mono className="text-primary">{e.version}</Mono> },
    {
      key: 'owner',
      header: 'Owner',
      sortable: true,
      accessor: (e) => e.owner,
      render: (e) => (
        <Tag size="sm" outline>
          {e.owner}
        </Tag>
      ),
    },
    { key: 'ways', header: 'Ways in', render: (e) => <Ways entry={e} /> },
  ]

  const run = (e: CatalogEntry) => navigate(pathFor('catalog-run', { workflow: e.workflow }))

  return (
    <>
      <PageHeader title="Catalog" subtitle="Every published workflow, ready to run from a form, the API or an MCP tool.">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search the catalog" aria-label="Search the catalog" className="sm:max-w-xs" />
          <div className="flex flex-wrap items-center gap-3">
            <ApiLinks lead="The same operations:" />
            <Segmented
              ariaLabel="View"
              size="sm"
              value={view}
              onChange={setView}
              options={[
                { value: 'table', icon: 'list', title: 'Table' },
                { value: 'cards', icon: 'grid', title: 'Cards' },
              ]}
            />
          </div>
        </div>
      </PageHeader>

      {catalog.error && !catalog.data ? (
        <ErrorState error={catalog.error} title="Could not load the catalog" onRetry={() => void catalog.refetch()} />
      ) : view === 'table' ? (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(e) => e.workflow}
          loading={catalog.loading}
          onRowClick={run}
          defaultSort={{ key: 'title', dir: 'asc' }}
          emptyIcon="list"
          emptyTitle={catalog.data?.length ? 'Nothing matches' : 'Nothing published yet'}
          emptyMessage={catalog.data?.length ? 'Try other words.' : 'Publish a workflow and it appears here with its endpoint and its MCP tool.'}
          caption="Catalog"
        />
      ) : catalog.loading ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" role="status" aria-label="Loading">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-44" />
          ))}
        </div>
      ) : rows.length === 0 ? (
        <Card flush>
          <EmptyState icon="list" title={catalog.data?.length ? 'Nothing matches' : 'Nothing published yet'} message={catalog.data?.length ? 'Try other words.' : 'Publish a workflow and it appears here with its endpoint and its MCP tool.'} />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {rows.map((e) => (
            <Card key={e.workflow} interactive onClick={() => run(e)} className="flex flex-col" role="link" tabIndex={0} onKeyDown={(ev) => (ev.key === 'Enter' || ev.key === ' ') && run(e)}>
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h3 className="text-subheadline font-semibold text-primary">{e.title}</h3>
                  <p className="mt-0.5 text-caption text-tertiary">
                    {e.version} · {e.owner}
                    {e.persona && ` · for ${e.persona}`}
                  </p>
                </div>
                <span className="inline-flex flex-wrap justify-end gap-1">
                  {e.deprecated && <DeprecatedTag deprecated={e.deprecated} />}
                  <Ways entry={e} />
                </span>
              </div>
              {e.description && <p className="mt-3 line-clamp-3 text-sm leading-relaxed text-secondary">{e.description}</p>}
              <div className="mt-4 flex items-center justify-between gap-3 border-t border-stroke-light pt-3">
                <Endpoint entry={e} />
                <Button size="sm" variant="primary" icon="play" onClick={(ev) => { ev.stopPropagation(); run(e) }}>
                  Run
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </>
  )
}
