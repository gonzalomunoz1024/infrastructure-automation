import { useMemo, useState } from 'react'
import { Button, Input, PageHeader, Segmented, Select, Sparkline, Stat, Table, Tag, duration, formatNumber, percent, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { HealthStatus, ProviderSummary } from '../lib/types'
import { HealthBand } from '../components/HealthBand'
import { ErrorState, Mono } from '../components/common'

type Band = 'all' | HealthStatus

const TYPE_LABEL: Record<string, string> = { openapi: 'OpenAPI', http: 'HTTP', ansible: 'Ansible', terraform: 'Terraform', idp: 'IDP tile', sdk: 'SDK' }

export default function ProvidersPage(_: PageProps) {
  const list = useQuery('providers', () => api.providers(), { refetchInterval: 30_000 })
  const [q, setQ] = useState('')
  const [type, setType] = useState('')
  const [band, setBand] = useState<Band>('all')

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (list.data ?? []).filter((p) => {
      if (type && p.type !== type) return false
      if (band !== 'all' && p.health.status !== band) return false
      return !needle || `${p.title} ${p.name} ${p.owner} ${p.type}`.toLowerCase().includes(needle)
    })
  }, [list.data, q, type, band])

  const counts = useMemo(() => {
    const c = { healthy: 0, degraded: 0, failing: 0, unknown: 0, calls: 0 }
    for (const p of list.data ?? []) {
      c[p.health.status] = (c[p.health.status] ?? 0) + 1
      c.calls += p.health.calls
    }
    return c
  }, [list.data])

  const columns: TableColumn<ProviderSummary>[] = [
    {
      key: 'title',
      header: 'Provider',
      sortable: true,
      accessor: (p) => p.title,
      render: (p) => (
        <div className="min-w-0">
          <div className="font-medium text-primary">{p.title}</div>
          <Mono className="text-tertiary">
            {p.name} · {p.version}
          </Mono>
        </div>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      sortable: true,
      accessor: (p) => p.type,
      width: '7rem',
      render: (p) => (
        <Tag size="sm" outline>
          {TYPE_LABEL[p.type] ?? p.type}
        </Tag>
      ),
    },
    { key: 'owner', header: 'Owner', sortable: true, accessor: (p) => p.owner, width: '9rem', render: (p) => <span className="text-primary">{p.owner}</span> },
    { key: 'health', header: 'Health', sortable: true, accessor: (p) => p.health.status, width: '8rem', render: (p) => <HealthBand health={p.health} /> },
    {
      key: 'success',
      header: 'Success',
      numeric: true,
      sortable: true,
      accessor: (p) => p.health.successRate,
      width: '6rem',
      render: (p) => <span className={p.slo && p.health.successRate < p.slo.successRate ? 'font-medium text-critical' : 'text-primary'}>{percent(p.health.successRate, { digits: 1 })}</span>,
    },
    {
      key: 'p95',
      header: 'p95',
      numeric: true,
      sortable: true,
      accessor: (p) => p.health.p95Ms,
      width: '6rem',
      render: (p) => <span className={p.slo && p.health.p95Ms > p.slo.p95Ms ? 'font-medium text-warning' : 'text-primary'}>{duration(p.health.p95Ms)}</span>,
    },
    { key: 'calls', header: 'Calls', numeric: true, sortable: true, accessor: (p) => p.health.calls, width: '6rem', render: (p) => <span className="text-primary">{formatNumber(p.health.calls, { compact: true })}</span> },
    {
      key: 'trend',
      header: 'Errors',
      width: '8rem',
      render: (p) =>
        p.health.errorTrend?.length > 1 ? (
          <span className={p.health.errorTrend[p.health.errorTrend.length - 1] > 0 ? 'text-critical' : 'text-tertiary'}>
            <Sparkline values={p.health.errorTrend} width={96} height={24} fill ariaLabel="Error trend" />
          </span>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
    { key: 'actions', header: 'Actions', numeric: true, width: '5rem', render: (p) => <span className="text-primary">{p.actionCount}</span> },
    {
      key: 'env',
      header: 'Environments',
      render: (p) => (
        <span className="inline-flex flex-wrap gap-1">
          {p.environments.map((e) => (
            <Tag key={e} size="sm" mono outline>
              {e}
            </Tag>
          ))}
        </span>
      ),
    },
    {
      key: 'slo',
      header: 'SLO',
      width: '9rem',
      render: (p) => (p.slo ? <Mono className="text-secondary">{`${percent(p.slo.successRate, { digits: 1 })} · ${duration(p.slo.p95Ms)}`}</Mono> : <span className="text-tertiary">none</span>),
    },
  ]

  const types = useMemo(() => Array.from(new Set((list.data ?? []).map((p) => p.type))).sort(), [list.data])
  const filtered = !!q || !!type || band !== 'all'

  return (
    <>
      <PageHeader
        title="Providers"
        subtitle="The systems workflows can call: where each one lives, how healthy it is and what it offers."
        actions={
          <Button variant="primary" icon="plus" onClick={() => navigate(pathFor('provider-new'))}>
            Add provider
          </Button>
        }
      >
        <div className="flex flex-wrap items-center gap-2">
          <Input leading="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search providers" aria-label="Search providers" className="sm:max-w-xs" />
          <Select ariaLabel="Type" size="sm" value={type} onChange={setType} options={[{ value: '', label: 'Any type' }, ...types.map((t) => ({ value: t, label: TYPE_LABEL[t] ?? t }))]} />
          <Segmented
            ariaLabel="Health"
            size="sm"
            value={band}
            onChange={setBand}
            options={[
              { value: 'all', label: 'All' },
              { value: 'healthy', label: 'Healthy' },
              { value: 'degraded', label: 'Degraded' },
              { value: 'failing', label: 'Failing' },
            ]}
          />
          {filtered && (
            <Button size="sm" variant="ghost" icon="close" onClick={() => { setQ(''); setType(''); setBand('all') }}>
              Clear
            </Button>
          )}
        </div>
      </PageHeader>

      {list.data && list.data.length > 0 && (
        <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
          <Stat label="Healthy" value={counts.healthy} icon="check" hint={`of ${list.data.length}`} />
          <Stat label="Degraded" value={counts.degraded} icon="warning" hint={counts.degraded ? 'outside the SLO' : 'none'} />
          <Stat label="Failing" value={counts.failing} icon="alert-triangle" hint={counts.failing ? 'needs a look' : 'none'} />
          <Stat label="Calls" value={formatNumber(counts.calls, { compact: true })} icon="pulse" hint="in the health window" />
        </div>
      )}

      {list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the providers" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={rows}
          rowKey={(p) => p.name}
          loading={list.loading}
          onRowClick={(p) => navigate(pathFor('provider', { name: p.name }))}
          defaultSort={{ key: 'title', dir: 'asc' }}
          emptyIcon="provider"
          emptyTitle={filtered ? 'No providers match' : 'No providers yet'}
          emptyMessage={filtered ? 'Loosen the filters to see more.' : 'Add the first one: an OpenAPI spec, a few HTTP endpoints, an Ansible repository or a Terraform module.'}
          caption="Providers"
        />
      )}
    </>
  )
}
