import { useMemo, useState } from 'react'
import { Button, Drawer, Input, OverlayBody, OverlayHeader, PageHeader, Segmented, Select, Sparkline, Stat, Table, Tabs, Tag, duration, formatNumber, percent, plural, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, useCurrentUser } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { EvidenceEntry, HealthStatus, Me, ProviderSummary } from '../lib/types'
import { HealthBand } from '../components/HealthBand'
import { PlatformEntryDetails, PlatformEventTag, PlatformSubject, PlatformVerification, platformEvent, platformSummary } from '../components/PlatformAudit'
import { ErrorState, Mono, TextLink, When } from '../components/common'

/*
 * The providers admin view: the list (with p50 beside p95), how each provider performs against
 * its SLO with the 14-day error trend, and the provider events of the platform audit stream.
 * The backend lists every provider to an admin and only their teams' (and the unowned ones) to
 * anyone else, which the page says when that leaves nothing.
 */

type Band = 'all' | HealthStatus
export type ProvidersTab = 'providers' | 'performance' | 'audit'

const TYPE_LABEL: Record<string, string> = { openapi: 'OpenAPI', http: 'HTTP', ansible: 'Ansible', terraform: 'Terraform', idp: 'IDP tile', sdk: 'SDK' }

export const tabOf = (q: string | undefined): ProvidersTab => (q === 'performance' || q === 'audit' ? q : 'providers')

/** Where a provider stands against its SLO: null where it declares none. */
export function sloVerdict(p: Pick<ProviderSummary, 'health' | 'slo'>): { success: boolean | null; p95: boolean | null; within: boolean | null } {
  if (!p.slo) return { success: null, p95: null, within: null }
  const success = p.health.successRate >= p.slo.successRate
  const p95 = p.health.p95Ms <= p.slo.p95Ms
  return { success, p95, within: success && p95 }
}

/** The failed calls of the trend (14 daily counts, oldest first) and of its last day. */
export function errorTotals(trend: readonly number[] | undefined): { total: number; last: number; days: number } {
  const values = (trend ?? []).filter((v) => Number.isFinite(v))
  return { total: values.reduce((s, v) => s + v, 0), last: values.length ? values[values.length - 1] : 0, days: values.length }
}

/** The provider events of the platform stream (`provider.*`). */
export function providerEvents(entries: readonly EvidenceEntry[]): EvidenceEntry[] {
  return entries.filter((e) => platformEvent(e).event.startsWith('provider.'))
}

/** What an empty list means: nothing yet, or (not an admin) nothing of the caller's teams. */
export function emptyProviders(me: Pick<Me, 'admin' | 'teams'> | undefined, filtered: boolean): { title: string; message: string } {
  if (filtered) return { title: 'No providers match', message: 'Loosen the filters to see more.' }
  if (me && !me.admin) {
    const teams = me.teams.length ? me.teams.join(', ') : 'none yet'
    return {
      title: 'No providers of your teams yet',
      message: `You see the providers your teams own (${teams}) and the ones no team owns; an admin sees every provider. Add one, or ask its owner team for access.`,
    }
  }
  return { title: 'No providers yet', message: 'Add the first one: an OpenAPI spec, a few HTTP endpoints, an Ansible repository or a Terraform module.' }
}

/** Actions and environments are also on each provider's page; below 1680px they give their room to the names, owners and SLOs, which stay on one line. */
const WIDE_ONLY = 'hidden min-[1680px]:table-cell'

function ProviderCell({ p }: { p: ProviderSummary }) {
  return (
    <div className="min-w-0">
      <div className="whitespace-nowrap font-medium text-primary">{p.title}</div>
      <Mono className="whitespace-nowrap text-tertiary">
        {p.name} · {p.version}
      </Mono>
    </div>
  )
}

function ErrorTrend({ trend, width = 96 }: { trend: number[]; width?: number }) {
  if (!(trend?.length > 1)) return <span className="text-tertiary">—</span>
  return (
    <span className={trend[trend.length - 1] > 0 ? 'text-critical' : 'text-tertiary'}>
      <Sparkline values={trend} width={width} height={24} fill ariaLabel={`Errors per day, last ${trend.length} days`} />
    </span>
  )
}

export const LIST_COLUMNS: TableColumn<ProviderSummary>[] = [
  { key: 'title', header: 'Provider', sortable: true, accessor: (p) => p.title, render: (p) => <ProviderCell p={p} /> },
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
  { key: 'owner', header: 'Owner', sortable: true, accessor: (p) => p.owner, width: '9rem', render: (p) => <span className="whitespace-nowrap text-primary">{p.owner}</span> },
  { key: 'health', header: 'Health', sortable: true, accessor: (p) => p.health.status, width: '8rem', render: (p) => <HealthBand health={p.health} /> },
  {
    key: 'success',
    header: 'Success',
    numeric: true,
    sortable: true,
    accessor: (p) => p.health.successRate,
    width: '6rem',
    render: (p) => <span className={sloVerdict(p).success === false ? 'font-medium text-critical' : 'text-primary'}>{percent(p.health.successRate, { digits: 1 })}</span>,
  },
  { key: 'p50', header: 'p50', numeric: true, sortable: true, accessor: (p) => p.health.p50Ms, width: '5.5rem', render: (p) => <span className="text-primary">{duration(p.health.p50Ms)}</span> },
  {
    key: 'p95',
    header: 'p95',
    numeric: true,
    sortable: true,
    accessor: (p) => p.health.p95Ms,
    width: '5.5rem',
    render: (p) => <span className={sloVerdict(p).p95 === false ? 'font-medium text-warning' : 'text-primary'}>{duration(p.health.p95Ms)}</span>,
  },
  { key: 'calls', header: 'Calls', numeric: true, sortable: true, accessor: (p) => p.health.calls, width: '6rem', render: (p) => <span className="text-primary">{formatNumber(p.health.calls, { compact: true })}</span> },
  { key: 'trend', header: 'Errors', width: '8rem', render: (p) => <ErrorTrend trend={p.health.errorTrend} /> },
  { key: 'actions', header: 'Actions', numeric: true, width: '5rem', className: WIDE_ONLY, headerClassName: WIDE_ONLY, render: (p) => <span className="text-primary">{p.actionCount}</span> },
  {
    key: 'env',
    header: 'Environments',
    className: WIDE_ONLY,
    headerClassName: WIDE_ONLY,
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
    render: (p) => (p.slo ? <Mono className="whitespace-nowrap text-secondary">{`${percent(p.slo.successRate, { digits: 1 })} · ${duration(p.slo.p95Ms)}`}</Mono> : <span className="text-tertiary">none</span>),
  },
]

/** A measured value with the SLO it is judged against under it. */
function AgainstSlo({ value, slo, ok, tone }: { value: string; slo?: string; ok: boolean | null; tone: 'critical' | 'warning' }) {
  return (
    <div className="flex flex-col items-end leading-tight">
      <span className={ok === false ? `font-medium ${tone === 'critical' ? 'text-critical' : 'text-warning'}` : 'text-primary'}>{value}</span>
      <span className="text-caption2 text-tertiary">{slo ? `SLO ${slo}` : 'no SLO'}</span>
    </div>
  )
}

export const PERFORMANCE_COLUMNS: TableColumn<ProviderSummary>[] = [
  { key: 'title', header: 'Provider', sortable: true, accessor: (p) => p.title, render: (p) => <ProviderCell p={p} /> },
  { key: 'health', header: 'Health', sortable: true, accessor: (p) => p.health.status, width: '8rem', render: (p) => <HealthBand health={p.health} /> },
  { key: 'calls', header: 'Calls', numeric: true, sortable: true, accessor: (p) => p.health.calls, width: '6rem', render: (p) => <span className="text-primary">{formatNumber(p.health.calls)}</span> },
  {
    key: 'success',
    header: 'Success rate',
    numeric: true,
    sortable: true,
    accessor: (p) => p.health.successRate,
    width: '7.5rem',
    render: (p) => <AgainstSlo value={percent(p.health.successRate, { digits: 2 })} slo={p.slo && `≥ ${percent(p.slo.successRate, { digits: 1 })}`} ok={sloVerdict(p).success} tone="critical" />,
  },
  { key: 'p50', header: 'p50', numeric: true, sortable: true, accessor: (p) => p.health.p50Ms, width: '5.5rem', render: (p) => <span className="text-primary">{duration(p.health.p50Ms)}</span> },
  {
    key: 'p95',
    header: 'p95',
    numeric: true,
    sortable: true,
    accessor: (p) => p.health.p95Ms,
    width: '7rem',
    render: (p) => <AgainstSlo value={duration(p.health.p95Ms)} slo={p.slo && `≤ ${duration(p.slo.p95Ms)}`} ok={sloVerdict(p).p95} tone="warning" />,
  },
  {
    key: 'slo',
    header: 'Against the SLO',
    sortable: true,
    accessor: (p) => (sloVerdict(p).within === null ? 1 : sloVerdict(p).within ? 2 : 0),
    width: '9rem',
    render: (p) => {
      const v = sloVerdict(p)
      if (v.within === null) return <span className="text-tertiary">no SLO</span>
      return (
        <Tag size="sm" tone={v.within ? 'healthy' : 'critical'} dot>
          {v.within ? 'within' : v.success === false && v.p95 === false ? 'both missed' : v.success === false ? 'success missed' : 'p95 missed'}
        </Tag>
      )
    },
  },
  {
    key: 'errors',
    header: 'Errors, 14 days',
    sortable: true,
    accessor: (p) => errorTotals(p.health.errorTrend).total,
    width: '11rem',
    render: (p) => {
      const t = errorTotals(p.health.errorTrend)
      return (
        <div className="flex items-center gap-2">
          <ErrorTrend trend={p.health.errorTrend} width={88} />
          <span className="tabular-nums text-caption text-secondary" title={`${t.last} on the last day`}>
            {formatNumber(t.total)}
          </span>
        </div>
      )
    },
  },
]

export default function ProvidersPage({ route }: PageProps) {
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  const list = useQuery('providers', () => api.providers(), { refetchInterval: 30_000 })
  // the address holds the view, so a link, a reload or Back lands on it
  const tab = tabOf(route.query.tab)
  const [q, setQ] = useState('')
  const [type, setType] = useState('')
  const [band, setBand] = useState<Band>('all')

  const switchTab = (t: ProvidersTab) => navigate(pathFor('providers', {}, { tab: t === 'providers' ? undefined : t }), { replace: true })

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (list.data ?? []).filter((p) => {
      if (type && p.type !== type) return false
      if (band !== 'all' && p.health.status !== band) return false
      return !needle || `${p.title} ${p.name} ${p.owner} ${p.type}`.toLowerCase().includes(needle)
    })
  }, [list.data, q, type, band])

  const counts = useMemo(() => {
    const c = { healthy: 0, degraded: 0, failing: 0, unknown: 0, calls: 0, within: 0, withSlo: 0 }
    for (const p of list.data ?? []) {
      c[p.health.status] = (c[p.health.status] ?? 0) + 1
      c.calls += p.health.calls
      const v = sloVerdict(p)
      if (v.within !== null) c.withSlo++
      if (v.within) c.within++
    }
    return c
  }, [list.data])

  const types = useMemo(() => Array.from(new Set((list.data ?? []).map((p) => p.type))).sort(), [list.data])
  const filtered = !!q || !!type || band !== 'all'
  const empty = emptyProviders(me.data, filtered)

  return (
    <>
      <PageHeader
        title="Providers"
        subtitle="The systems workflows can call: where each one lives, how healthy it is and what it offers."
        actions={
          <>
            <Button icon="terminal" onClick={() => navigate(pathFor('provider-new', {}, { import: 'ansible' }))} title="The platform reads the playbooks of a repository and drafts the provider">
              Import an Ansible repository
            </Button>
            <Button icon="layers" onClick={() => navigate(pathFor('provider-new', {}, { import: 'terraform' }))} title="The platform reads a module from Git or the registry and drafts the provider">
              Import a Terraform module
            </Button>
            <Button variant="primary" icon="plus" onClick={() => navigate(pathFor('provider-new'))}>
              Add provider
            </Button>
          </>
        }
      >
        <Tabs<ProvidersTab>
          ariaLabel="Providers views"
          value={tab}
          onChange={switchTab}
          className="mb-3"
          tabs={[
            { id: 'providers', label: 'Providers', icon: 'provider', count: list.data?.length },
            { id: 'performance', label: 'Performance', icon: 'chart' },
            { id: 'audit', label: 'Audit', icon: 'audit' },
          ]}
        />
        {tab !== 'audit' && (
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
        )}
      </PageHeader>

      {tab === 'audit' ? (
        <ProviderAudit />
      ) : list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the providers" onRetry={() => void list.refetch()} />
      ) : tab === 'performance' ? (
        <>
          {list.data && list.data.length > 0 && (
            <p className="mb-3 text-caption text-secondary" data-testid="slo-summary">
              {counts.withSlo === 0 ? 'No provider declares an SLO yet.' : `${counts.within} of ${plural(counts.withSlo, 'provider')} with an SLO ${counts.within === 1 ? 'is' : 'are'} within it`} · calls, success rate and latency over the health window; errors per day over the last 14 days.
            </p>
          )}
          <Table
            columns={PERFORMANCE_COLUMNS}
            rows={rows}
            rowKey={(p) => p.name}
            loading={list.loading}
            onRowClick={(p) => navigate(pathFor('provider', { name: p.name }))}
            defaultSort={{ key: 'slo', dir: 'asc' }}
            emptyIcon="chart"
            emptyTitle={empty.title}
            emptyMessage={empty.message}
            caption="Provider performance"
          />
        </>
      ) : (
        <>
          {list.data && list.data.length > 0 && (
            <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
              <Stat label="Healthy" value={counts.healthy} icon="check" hint={`of ${list.data.length}`} />
              <Stat label="Degraded" value={counts.degraded} icon="warning" hint={counts.degraded ? 'outside the SLO' : 'none'} />
              <Stat label="Failing" value={counts.failing} icon="alert-triangle" hint={counts.failing ? 'needs a look' : 'none'} />
              <Stat label="Calls" value={formatNumber(counts.calls, { compact: true })} icon="pulse" hint="in the health window" />
            </div>
          )}
          <Table
            columns={LIST_COLUMNS}
            rows={rows}
            rowKey={(p) => p.name}
            loading={list.loading}
            onRowClick={(p) => navigate(pathFor('provider', { name: p.name }))}
            defaultSort={{ key: 'title', dir: 'asc' }}
            emptyIcon="provider"
            emptyTitle={empty.title}
            emptyMessage={empty.message}
            caption="Providers"
          />
        </>
      )}
    </>
  )
}

/* ----------------------------------------------------------------------------
   Audit: the provider events of the platform stream
   ------------------------------------------------------------------------- */

export const AUDIT_COLUMNS: TableColumn<EvidenceEntry>[] = [
  { key: 'seq', header: '#', width: '4rem', numeric: true, sortable: true, accessor: (e) => e.seq, render: (e) => <span className="tabular-nums text-tertiary">{e.seq}</span> },
  {
    key: 'event',
    header: 'Event',
    sortable: true,
    accessor: (e) => platformEvent(e).event,
    render: (e) => (
      <div className="min-w-0">
        <PlatformEventTag event={platformEvent(e).event} />
        <div className="mt-0.5 text-sm text-primary">{platformSummary(e)}</div>
      </div>
    ),
  },
  { key: 'subject', header: 'Provider', width: '14rem', render: (e) => <PlatformSubject entry={e} /> },
  { key: 'actor', header: 'Actor', width: '8rem', sortable: true, accessor: (e) => e.actor ?? '', render: (e) => <span className="text-primary">{e.actor ?? '—'}</span> },
  { key: 'at', header: 'When', width: '9rem', sortable: true, accessor: (e) => e.at, render: (e) => <When at={e.at} /> },
]

function ProviderAudit() {
  // the stream filters by aggregate; the provider.* events are the provider aggregate's
  const stream = useQuery(['audit-platform', 'provider'], () => api.auditPlatform({ aggregate: 'provider' }))
  const [open, setOpen] = useState<EvidenceEntry | null>(null)
  const entries = useMemo(() => providerEvents(stream.data?.entries ?? []), [stream.data])

  if (stream.error && !stream.data) return <ErrorState error={stream.error} title="Could not load the provider events" onRetry={() => void stream.refetch()} />
  return (
    <div className="space-y-4" data-testid="provider-audit">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-caption text-secondary">Provider changes on the platform audit stream: change requests, publishes and updates, with who made them. A credential appears by name only.</p>
        <TextLink to={pathFor('audit', {}, { tab: 'platform', aggregate: 'provider' })}>Open in Audit</TextLink>
      </div>
      {stream.data && <PlatformVerification verification={stream.data.verification} total={stream.data.total} />}
      <Table
        columns={AUDIT_COLUMNS}
        rows={entries}
        rowKey={(e) => String(e.seq)}
        loading={stream.loading}
        onRowClick={(e) => setOpen(e)}
        defaultSort={{ key: 'seq', dir: 'desc' }}
        emptyIcon="audit"
        emptyTitle="No provider events yet"
        emptyMessage="Publishing a provider, its change requests and its updates are recorded here as they happen."
        caption="Provider events"
      />
      {open && (
        <Drawer onClose={() => setOpen(null)} width="max-w-lg" label={`Platform event ${open.seq}`}>
          <OverlayHeader title={platformSummary(open)} subtitle={<PlatformEventTag event={platformEvent(open).event} />} icon="audit" />
          <OverlayBody>
            <PlatformEntryDetails entry={open} />
          </OverlayBody>
        </Drawer>
      )}
    </div>
  )
}
