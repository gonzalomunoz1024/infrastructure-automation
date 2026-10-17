import { useMemo, useState } from 'react'
import { Button, Field, Input, PageHeader, Select, StateTag, Table, Tag, isoDate, useToast, type TableColumn } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'
import { api, errorMessage } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { AuditFilter, AuditSummary } from '../lib/types'
import { downloadBlob, safeFilename } from '../components/download'
import { ErrorState, Mono, TextLink, When, shortJobId } from '../components/common'

export default function AuditPage({ route }: PageProps) {
  const toast = useToast()
  const [workflow, setWorkflow] = useState(route.query.workflow ?? '')
  const [requester, setRequester] = useState(route.query.requester ?? '')
  const [from, setFrom] = useState(route.query.from ?? '')
  const [to, setTo] = useState(route.query.to ?? '')
  const [busy, setBusy] = useState<'pack' | 'seal' | null>(null)
  const filter: AuditFilter = useMemo(() => ({ workflow: workflow || undefined, requester: requester.trim() || undefined, from: from || undefined, to: to || undefined }), [workflow, requester, from, to])
  const workflows = useQuery('workflows', () => api.workflows())
  const list = useQuery(['audit', filter.workflow ?? '', filter.requester ?? '', filter.from ?? '', filter.to ?? ''], () => api.audit(filter))

  const exportPack = async () => {
    setBusy('pack')
    try {
      const blob = await api.exportAuditPack(filter)
      downloadBlob(blob, `audit-pack-${safeFilename([filter.workflow, filter.from, filter.to].filter(Boolean).join('_') || isoDate(new Date()))}.zip`)
      toast.success('Evidence pack ready', 'A zip of every matching record with a signed manifest.')
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
      toast.success('Sealed', `Every record so far is signed (${r.signature.slice(0, 12)}…). The seal also runs hourly.`)
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
        subtitle="Evidence for every run: who asked, what was previewed, who approved and what changed, in a hash chain."
        actions={
          <>
            <Button icon="lock" loading={busy === 'seal'} onClick={() => void seal()}>
              Seal now
            </Button>
            <Button variant="primary" icon="download" loading={busy === 'pack'} disabled={!list.data?.length} onClick={() => void exportPack()}>
              Export pack
            </Button>
          </>
        }
      >
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
      </PageHeader>

      {list.error && !list.data ? (
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
