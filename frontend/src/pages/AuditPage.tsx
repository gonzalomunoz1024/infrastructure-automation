import { useMemo, useState } from 'react'
import { Button, Drawer, Field, Input, OverlayBody, OverlayHeader, PageHeader, Select, StateTag, Table, Tabs, Tag, isoDate, plural, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { AuditFilter, AuditSummary, EvidenceEntry, PlatformAuditFilter, SealResult } from '../lib/types'
import { downloadBlob, safeFilename } from '../components/download'
import { AGGREGATES, PlatformEntryDetails, PlatformEventTag, PlatformSubject, PlatformVerification, platformEvent, platformSummary } from '../components/PlatformAudit'
import { ErrorState, Mono, TextLink, When, shortJobId } from '../components/common'

type AuditTab = 'jobs' | 'platform'

/** What a seal did: how many closed records it sealed and open ones it checkpointed, and with which key. */
export function sealSummary({ sealed, checkpointed, keyId }: Pick<SealResult, 'sealed' | 'checkpointed' | 'keyId'>): string {
  const hourly = 'The seal also runs hourly.'
  if (sealed > 0 && checkpointed > 0) return `Sealed ${plural(sealed, 'closed record')} and checkpointed ${plural(checkpointed, 'open one')} with key ${keyId}. ${hourly}`
  if (sealed > 0) return `Sealed ${plural(sealed, 'closed record')} with key ${keyId}. ${hourly}`
  if (checkpointed > 0) return `Checkpointed ${plural(checkpointed, 'open record')} with key ${keyId}. ${hourly}`
  return `Nothing new to sign: every record was already sealed or checkpointed. ${hourly}`
}

export default function AuditPage({ route }: PageProps) {
  const toast = useToast()
  const [tab, setTab] = useState<AuditTab>(route.query.tab === 'platform' ? 'platform' : 'jobs')
  const [workflow, setWorkflow] = useState(route.query.workflow ?? '')
  const [requester, setRequester] = useState(route.query.requester ?? '')
  const [from, setFrom] = useState(route.query.from ?? '')
  const [to, setTo] = useState(route.query.to ?? '')
  const [busy, setBusy] = useState<'pack' | 'seal' | null>(null)
  const filter: AuditFilter = useMemo(() => ({ workflow: workflow || undefined, requester: requester.trim() || undefined, from: from || undefined, to: to || undefined }), [workflow, requester, from, to])
  const workflows = useQuery('workflows', () => api.workflows())
  const list = useQuery(['audit', filter.workflow ?? '', filter.requester ?? '', filter.from ?? '', filter.to ?? ''], () => api.audit(filter), { enabled: tab === 'jobs' })

  const switchTab = (t: AuditTab) => {
    setTab(t)
    navigate(pathFor('audit', {}, { tab: t === 'platform' ? 'platform' : undefined, workflow: workflow || undefined, requester: requester || undefined, from: from || undefined, to: to || undefined }), { replace: true })
  }

  const exportPack = async () => {
    setBusy('pack')
    try {
      const blob = await api.exportAuditPack(filter)
      downloadBlob(blob, `audit-pack-${safeFilename([filter.workflow, filter.from, filter.to].filter(Boolean).join('_') || isoDate(new Date()))}.zip`)
      toast.success('Evidence pack ready', 'A zip of every matching record, the platform stream and a signed manifest.')
    } catch (e) {
      toast.error('Could not export the pack', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const seal = async () => {
    setBusy('seal')
    try {
      const r = await api.sealAudit()
      toast.success(r.sealed + r.checkpointed > 0 ? 'Sealed' : 'Already sealed', sealSummary(r))
    } catch (e) {
      toast.error('Could not seal', errorMessage(e))
    } finally {
      setBusy(null)
    }
  }

  const columns: TableColumn<AuditSummary>[] = [
    {
      key: 'job',
      header: 'Job',
      width: '9rem',
      render: (a) => (
        <Mono className="text-primary" title={a.jobId}>
          {shortJobId(a.jobId)}
        </Mono>
      ),
    },
    {
      key: 'workflow',
      header: 'Workflow',
      sortable: true,
      accessor: (a) => a.workflow,
      render: (a) => (
        <TextLink to={pathFor('workflow', { name: a.workflow })} mono onClick={(e) => e.stopPropagation()}>
          {a.workflow}
        </TextLink>
      ),
    },
    { key: 'requester', header: 'Requester', sortable: true, accessor: (a) => a.requester, render: (a) => <span className="text-primary">{a.requester}</span> },
    {
      key: 'channel',
      header: 'Via',
      width: '6rem',
      render: (a) => (
        <Tag size="sm" outline>
          {a.channel}
        </Tag>
      ),
    },
    { key: 'status', header: 'Outcome', width: '10rem', sortable: true, accessor: (a) => a.status, render: (a) => <StateTag status={a.status} /> },
    { key: 'entries', header: 'Entries', width: '6rem', numeric: true, sortable: true, accessor: (a) => a.entries, render: (a) => <span className="tabular-nums text-primary">{a.entries}</span> },
    { key: 'at', header: 'When', width: '9rem', sortable: true, accessor: (a) => a.at, render: (a) => <When at={a.at} /> },
  ]

  const filtered = !!(filter.workflow || filter.requester || filter.from || filter.to)

  return (
    <>
      <PageHeader
        title="Audit"
        subtitle="Evidence for every run, and for every change to the platform itself: who asked, what was previewed, who approved and what changed, in a hash chain."
        actions={
          <>
            <Button icon="lock" loading={busy === 'seal'} onClick={() => void seal()}>
              Seal now
            </Button>
            <Button variant="primary" icon="download" loading={busy === 'pack'} disabled={tab === 'jobs' && !list.data?.length} onClick={() => void exportPack()}>
              Export pack
            </Button>
          </>
        }
      >
        <Tabs<AuditTab>
          ariaLabel="Evidence"
          value={tab}
          onChange={switchTab}
          className="mb-3"
          tabs={[
            { id: 'jobs', label: 'Job evidence', icon: 'job' },
            { id: 'platform', label: 'Platform events', icon: 'audit' },
          ]}
        />
        {tab === 'jobs' && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,14rem)_minmax(0,14rem)_10rem_10rem_auto]">
            <Field label="Workflow">
              <Select ariaLabel="Workflow" size="sm" mono value={workflow} onChange={setWorkflow} options={[{ value: '', label: 'Every workflow' }, ...(workflows.data ?? []).map((w) => ({ value: w.name, label: w.name, hint: w.title }))]} />
            </Field>
            <Field label="Requester" htmlFor="audit-requester">
              <Input id="audit-requester" size="sm" value={requester} onChange={(e) => setRequester(e.target.value)} placeholder="anyone" />
            </Field>
            <Field label="From" htmlFor="audit-from">
              <Input id="audit-from" size="sm" type="date" value={from} onChange={(e) => setFrom(e.target.value)} max={to || undefined} />
            </Field>
            <Field label="To" htmlFor="audit-to">
              <Input id="audit-to" size="sm" type="date" value={to} onChange={(e) => setTo(e.target.value)} min={from || undefined} />
            </Field>
            {filtered && (
              <div className="flex items-end">
                <Button size="sm" variant="ghost" icon="close" onClick={() => { setWorkflow(''); setRequester(''); setFrom(''); setTo('') }}>
                  Clear
                </Button>
              </div>
            )}
          </div>
        )}
      </PageHeader>

      {tab === 'platform' ? (
        <PlatformTab initial={{ aggregate: route.query.aggregate ?? '', actor: route.query.actor ?? '', from: route.query.from ?? '', to: route.query.to ?? '' }} />
      ) : list.error && !list.data ? (
        <ErrorState error={list.error} title="Could not load the evidence" onRetry={() => void list.refetch()} />
      ) : (
        <Table
          columns={columns}
          rows={list.data ?? []}
          rowKey={(a) => a.jobId}
          loading={list.loading}
          onRowClick={(a) => navigate(pathFor('audit-record', { jobId: a.jobId }))}
          defaultSort={{ key: 'at', dir: 'desc' }}
          emptyIcon="audit"
          emptyTitle={filtered ? 'No records match' : 'No evidence yet'}
          emptyMessage={filtered ? 'Widen the dates or clear the filters.' : 'Every run writes evidence as it goes; the first one appears here.'}
          caption="Evidence records"
        />
      )}
    </>
  )
}

/* ----------------------------------------------------------------------------
   The platform stream: commands that are not about a job, with their own filters
   ------------------------------------------------------------------------- */

function PlatformTab({ initial }: { initial: { aggregate: string; actor: string; from: string; to: string } }) {
  const [aggregate, setAggregate] = useState(initial.aggregate)
  const [actor, setActor] = useState(initial.actor)
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [open, setOpen] = useState<EvidenceEntry | null>(null)
  const filter: PlatformAuditFilter = useMemo(() => ({ aggregate: aggregate || undefined, actor: actor.trim() || undefined, from: from || undefined, to: to || undefined }), [aggregate, actor, from, to])
  const stream = useQuery(['audit-platform', filter.aggregate ?? '', filter.actor ?? '', filter.from ?? '', filter.to ?? ''], () => api.auditPlatform(filter))
  const filtered = !!(filter.aggregate || filter.actor || filter.from || filter.to)

  const columns: TableColumn<EvidenceEntry>[] = [
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
    { key: 'subject', header: 'Subject', width: '16rem', render: (e) => <PlatformSubject entry={e} /> },
    { key: 'actor', header: 'Actor', width: '8rem', sortable: true, accessor: (e) => e.actor ?? '', render: (e) => <span className="text-primary">{e.actor ?? '—'}</span> },
    { key: 'at', header: 'When', width: '9rem', sortable: true, accessor: (e) => e.at, render: (e) => <When at={e.at} /> },
    { key: 'hash', header: 'Hash', width: '7rem', render: (e) => <Mono title={e.hash}>{e.hash.length > 10 ? `${e.hash.slice(0, 8)}…` : e.hash}</Mono> },
  ]

  return (
    <div className="space-y-4" data-testid="platform-events">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,14rem)_minmax(0,14rem)_10rem_10rem_auto]">
        <Field label="Type">
          <Select ariaLabel="Type" size="sm" value={aggregate} onChange={setAggregate} options={[{ value: '', label: 'Every type' }, ...AGGREGATES]} />
        </Field>
        <Field label="Actor" htmlFor="platform-actor">
          <Input id="platform-actor" size="sm" value={actor} onChange={(e) => setActor(e.target.value)} placeholder="anyone" />
        </Field>
        <Field label="From" htmlFor="platform-from">
          <Input id="platform-from" size="sm" type="date" value={from} onChange={(e) => setFrom(e.target.value)} max={to || undefined} />
        </Field>
        <Field label="To" htmlFor="platform-to">
          <Input id="platform-to" size="sm" type="date" value={to} onChange={(e) => setTo(e.target.value)} min={from || undefined} />
        </Field>
        {filtered && (
          <div className="flex items-end">
            <Button size="sm" variant="ghost" icon="close" onClick={() => { setAggregate(''); setActor(''); setFrom(''); setTo('') }}>
              Clear
            </Button>
          </div>
        )}
      </div>

      {stream.error && !stream.data ? (
        <ErrorState error={stream.error} title="Could not load the platform stream" onRetry={() => void stream.refetch()} />
      ) : (
        <>
          {stream.data && <PlatformVerification verification={stream.data.verification} total={stream.data.total} />}
          <Table
            columns={columns}
            rows={stream.data?.entries ?? []}
            rowKey={(e) => String(e.seq)}
            loading={stream.loading}
            onRowClick={(e) => setOpen(e)}
            defaultSort={{ key: 'seq', dir: 'desc' }}
            emptyIcon="audit"
            emptyTitle={filtered ? 'No events match' : 'No platform events yet'}
            emptyMessage={filtered ? 'Widen the dates or clear the filters; a filter never hides a break in the chain.' : 'Publishing, change requests, provider changes, credentials and service accounts are recorded here.'}
            caption="Platform events"
          />
        </>
      )}

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
