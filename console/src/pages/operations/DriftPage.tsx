import { useMemo, useState, type ReactNode } from 'react'
import { Button, EmptyState, Icon, IconButton, PageHeader, Select, Stat, cx, formatNumber, plural } from '@iaf/ui'
import { CursorPager, DotTag, ErrorState, Mono, VirtualTable, When, useCursorPaging, type VirtualColumn } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote } from '../../app/commands'
import { Link, pathFor, resourcePath, type PageProps } from '../../app/router'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { RESOLUTION_WORDS, SEVERITIES, SEVERITY_TONE, SEVERITY_WORDS, decisionKeys, driftApi, driftInWords, minutesText, revertStateOf, type FindingListParams, type Severity } from '../../lib/drift'
import { explain } from '../../lib/plain'
import { usePoll } from '../../lib/poll'
import { invalidate, useQuery } from '../../lib/queries'
import type { Finding } from '../../lib/types'
import { AcceptDialog, DecisionButton, IgnoreDialog, LiveLine, decisionsOf, useRevert } from '../resource/DriftDecisions'

/*
 * The account's Drift page (WP 5.2): what the drift service found different across every type
 * it watches, with the account's numbers on top (open findings by severity and by type, when the
 * last scan ran, and the last 30 days: found, closed by how, the mean time to close) and every
 * finding below, filtered by type, severity and status. Each row links to the resource's Drift
 * tab, says what drifted in words and offers the three decisions, Revert in one click. Rows can
 * be selected and reverted together: one call per finding, counted live, with what failed named.
 */

const FOLLOW_MS = 2_000
const STATUSES = [
  { value: 'open', label: 'Open' },
  { value: 'closed', label: 'Closed' },
  { value: 'all', label: 'Open and closed' },
] as const
type Status = (typeof STATUSES)[number]['value']

/** The type a finding is of: its own `type`, else the wfrn's type segment. */
function typeOfFinding(types: readonly ConsoleType[], f: Finding): ConsoleType | undefined {
  const name = f.type ?? /:([a-z0-9-]+)\/[^/]+$/.exec(f.wfrn)?.[1]
  return types.find((t) => t.name === name)
}

/** The resource's id, the wfrn's last segment: findings carry no name. */
function idOf(wfrn: string): string {
  return wfrn.slice(wfrn.lastIndexOf('/') + 1)
}

/** Where a finding's resource shows its drift. */
function driftTabOf(type: ConsoleType | undefined, f: Finding): string | undefined {
  return type ? `${resourcePath(type.service, type.name, f.wfrn)}?tab=drift` : undefined
}

interface BulkState {
  total: number
  done: number
  started: number
  failed: { finding: Finding; why: string }[]
  running: boolean
}

/** The account's Drift page: its numbers, then every finding with its decisions. */
export default function DriftPage(_props: PageProps) {
  const { types, decide, account, region } = useConsole()
  const watched = useMemo(() => types.filter((t) => t.observe.supported), [types])
  const [type, setType] = useState('')
  const [severity, setSeverity] = useState<Severity | ''>('')
  const [status, setStatus] = useState<Status>('open')
  const [pageSize, setPageSize] = useState(50)
  const paging = useCursorPaging()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dialog, setDialog] = useState<{ kind: 'accept' | 'ignore'; finding: Finding } | null>(null)
  const [live, setLive] = useState('')
  const [bulk, setBulk] = useState<BulkState | null>(null)
  const [rowBusy, setRowBusy] = useState<string | null>(null)

  const scope = `${account.id}/${region.name}`
  const params: FindingListParams = { status, ...(type ? { type } : {}), ...(severity ? { severity } : {}), limit: pageSize, after: paging.after }
  const list = useQuery(['console-findings', 'list', scope, params], () => driftApi.findings.list(params))
  const summary = useQuery(['console-findings', 'summary', scope], () => driftApi.findings.summary())
  const stats = useQuery(['console-findings', 'stats', scope], () => driftApi.findings.stats())
  const rows = useMemo(() => list.data?.items ?? [], [list.data])
  const following = rows.some((f) => f.status === 'open' && f.revertStatus === 'running')
  usePoll(following, () => invalidate('console-findings'), FOLLOW_MS)

  const reverting = useRevert(watched[0] ?? { name: 'resource', plural: 'resources' })

  const reset = () => {
    paging.reset()
    setSelected(new Set())
  }

  const revertOne = async (f: Finding) => {
    const t = typeOfFinding(types, f)
    setRowBusy(f.findingId)
    const res = await reverting.revert(f)
    setRowBusy(null)
    if (t) for (const key of decisionKeys(t)) invalidate(key)
    if (res) setLive(`Reverting ${idOf(f.wfrn)}: request ${res.jobId} started.`)
  }

  const decisionsFor = (f: Finding) => {
    const t = typeOfFinding(types, f)
    return decisionsOf(t ? decide(t, 'update') : { allowed: false }, f)
  }

  const selectedRows = rows.filter((f) => selected.has(f.findingId))
  const revertable = selectedRows.filter((f) => decisionsFor(f).revert.allowed)

  /** Reverts the selected findings one call each, counting as it goes and keeping what failed. */
  const revertSelected = async () => {
    const queue = revertable
    if (!queue.length) return
    const state: BulkState = { total: queue.length, done: 0, started: 0, failed: [], running: true }
    setBulk({ ...state })
    setLive(`Reverting ${plural(queue.length, 'finding')}.`)
    for (const f of queue) {
      try {
        await driftApi.findings.revert(f.findingId)
        state.started += 1
      } catch (e) {
        const x = explain(e, 'Revert')
        state.failed.push({ finding: f, why: [x.title, x.message].filter(Boolean).join(': ') })
      }
      state.done += 1
      setBulk({ ...state, failed: [...state.failed] })
    }
    state.running = false
    setBulk({ ...state, failed: [...state.failed] })
    setSelected(new Set(state.failed.map((x) => x.finding.findingId)))
    setLive(state.failed.length ? `Started ${plural(state.started, 'revert')}; ${state.failed.length} could not start.` : `Started ${plural(state.started, 'revert')}.`)
    const touched = new Set(queue.map((f) => typeOfFinding(types, f)).filter((t): t is ConsoleType => !!t))
    for (const t of touched) for (const key of decisionKeys(t)) invalidate(key)
  }

  const s = summary.data
  const st = stats.data
  const byType = s ? Object.entries(s.byType).sort((a, b) => b[1] - a[1]) : []
  const typeTitle = (name: string) => types.find((t) => t.name === name)?.title ?? name

  const columns: VirtualColumn<Finding>[] = [
    {
      key: 'resource',
      header: 'Resource',
      width: 'minmax(9rem, 1fr)',
      minRem: 9,
      accessor: (f) => idOf(f.wfrn),
      render: (f) => {
        const t = typeOfFinding(types, f)
        const to = driftTabOf(t, f)
        return (
          <span className="min-w-0">
            {to ? (
              <Link to={to} className="block truncate font-mono text-caption font-medium text-accent hover:underline">
                {idOf(f.wfrn)}
              </Link>
            ) : (
              <Mono className="block truncate">{idOf(f.wfrn)}</Mono>
            )}
            <span className="block truncate text-caption2 text-tertiary">{t ? singularOf(t.title) : (f.type ?? 'Resource')}</span>
          </span>
        )
      },
    },
    {
      key: 'drift',
      header: 'What drifted',
      width: 'minmax(12rem, 2fr)',
      minRem: 12,
      accessor: (f) => driftInWords(f, typeOfFinding(types, f)),
      render: (f) => {
        const words = driftInWords(f, typeOfFinding(types, f))
        return (
          <span className="block truncate text-sm text-primary" title={words}>
            {words}
          </span>
        )
      },
    },
    { key: 'severity', header: 'Severity', width: '6.5rem', minRem: 6.5, accessor: (f) => SEVERITIES.indexOf(f.severity), render: (f) => <DotTag tone={SEVERITY_TONE[f.severity] ?? 'neutral'}>{SEVERITY_WORDS[f.severity] ?? f.severity}</DotTag> },
    { key: 'openedAt', header: 'Found', width: '6rem', minRem: 6, accessor: (f) => f.openedAt, render: (f) => <When at={f.openedAt} /> },
    { key: 'revert', header: 'Revert', width: '8rem', minRem: 8, accessor: (f) => f.revertStatus ?? '', render: (f) => <RevertCell finding={f} /> },
    {
      key: 'actions',
      header: 'Decide',
      width: '13.5rem',
      minRem: 13.5,
      accessor: () => '',
      render: (f) => {
        if (f.status === 'closed') return <span className="text-caption text-tertiary">{f.decidedBy ? `By ${f.decidedBy}` : 'Nothing to decide'}</span>
        const d = decisionsFor(f)
        const why = Array.from(new Set([d.revert.why, d.accept.why, d.ignore.why].filter(Boolean))).join(' ')
        return (
          <span className="flex items-center gap-1" title={why || undefined}>
            <DecisionButton decision={d.revert} verb="revert" variant="primary" onClick={() => void revertOne(f)} loading={rowBusy === f.findingId}>
              Revert
            </DecisionButton>
            <DecisionButton decision={d.accept} verb="accept" onClick={() => setDialog({ kind: 'accept', finding: f })}>
              Accept
            </DecisionButton>
            <DecisionButton decision={d.ignore} verb="ignore" variant="ghost" onClick={() => setDialog({ kind: 'ignore', finding: f })}>
              Ignore
            </DecisionButton>
            {why && <span className="sr-only">{why}</span>}
          </span>
        )
      },
    },
  ]

  const filtered = !!type || !!severity || status !== 'open'
  const dialogType = dialog ? typeOfFinding(types, dialog.finding) : undefined
  const onDone = (line: string) => {
    setDialog(null)
    setLive(line)
  }

  return (
    <>
      <PageHeader
        title="Drift"
        subtitle="Where what the providers report differs from what was asked for, in this account. The drift service compares every watched resource at least hourly and never changes anything by itself."
        actions={
          <IconButton label="Refresh" onClick={() => invalidate('console-findings')}>
            <Icon name="refresh" size={16} className={cx((list.fetching || summary.fetching) && 'animate-spin')} />
          </IconButton>
        }
      />

      <section aria-label="Open findings" className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Open findings" value={s ? formatNumber(s.open) : '…'} icon="pulse" hint={s ? severityLine(s.bySeverity) : summary.error ? 'Could not load' : undefined} />
        <Stat label="Types drifted" value={s ? formatNumber(byType.length) : '…'} icon="layers" hint={s ? (byType.length ? byType.map(([name, n]) => `${typeTitle(name)} ${n}`).join(', ') : 'No type has open findings') : undefined} />
        <Stat label="Last scan" value={s?.lastScanAt ? <When at={s.lastScanAt} /> : s ? 'Not yet' : '…'} icon="clock" hint="Every watched resource, at least hourly" />
        <Stat label="Mean time to close" value={st ? minutesText(st.meanMinutesToClose) : '…'} icon="history" hint="Over the last 30 days" />
      </section>

      <section aria-labelledby="drift-month" className="mb-8">
        <h2 id="drift-month" className="mb-3 text-subheadline font-semibold text-primary">
          The last 30 days
        </h2>
        {stats.error && !st ? (
          <FailureNote failure={explain(stats.error, 'Load the last 30 days')} compact />
        ) : (
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="Found" value={st ? formatNumber(st.opened) : '…'} icon="alert-triangle" hint={st ? severityLine(st.bySeverity) : undefined} />
            <Stat label="Closed" value={st ? formatNumber(st.closed.total) : '…'} icon="check" hint={st ? `${st.closed.ignored} ignored, ${st.closed.vanished} settled by themselves` : undefined} />
            <Stat label="Reverted" value={st ? formatNumber(st.closed.reverted) : '…'} icon="refresh" hint="Put back to what was asked for" />
            <Stat label="Accepted" value={st ? formatNumber(st.closed.accepted) : '…'} icon="edit" hint="The provider’s values made the desired ones" />
          </div>
        )}
      </section>

      <section aria-labelledby="drift-findings">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <h2 id="drift-findings" className="text-subheadline font-semibold text-primary">
            Findings
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <Select
              size="sm"
              ariaLabel={`Type: ${type ? typeTitle(type) : 'All types'}`}
              value={type}
              onChange={(v) => {
                setType(v)
                reset()
              }}
              options={[{ value: '', label: 'All types' }, ...watched.map((t) => ({ value: t.name, label: t.title }))]}
            />
            <Select
              size="sm"
              ariaLabel={`Severity: ${severity ? SEVERITY_WORDS[severity] : 'Any severity'}`}
              value={severity}
              onChange={(v) => {
                setSeverity(v as Severity | '')
                reset()
              }}
              options={[{ value: '', label: 'Any severity' }, ...SEVERITIES.map((v) => ({ value: v, label: SEVERITY_WORDS[v] }))]}
            />
            <Select
              size="sm"
              ariaLabel={`Status: ${STATUSES.find((x) => x.value === status)?.label ?? status}`}
              value={status}
              onChange={(v) => {
                setStatus(v as Status)
                reset()
              }}
              options={STATUSES.map((x) => ({ value: x.value, label: x.label }))}
            />
          </div>
        </div>

        {(selected.size > 0 || bulk) && (
          <BulkBar
            selected={selectedRows.length}
            revertable={revertable.length}
            bulk={bulk}
            onRevert={() => void revertSelected()}
            onClear={() => {
              setSelected(new Set())
              setBulk(null)
            }}
          />
        )}
        <FailureNote failure={reverting.failure} onDismiss={reverting.clear} compact className="mb-3" />

        {list.error && !list.data ? (
          <ErrorState error={list.error} title="Could not load the findings" onRetry={list.refetch} />
        ) : (
          <VirtualTable<Finding>
            columns={columns}
            rows={rows}
            rowKey={(f) => f.findingId}
            label="Drift findings"
            loading={list.loading}
            rowHeight={56}
            serverSort
            selectable={status !== 'closed'}
            rowLabel={(f) => idOf(f.wfrn)}
            selectedKeys={selected}
            onSelectionChange={setSelected}
            maxHeight="max(18rem, calc(100dvh - 26rem))"
            empty={
              filtered ? (
                <EmptyState
                  compact
                  icon="filter"
                  title="No findings match these filters"
                  message="Loosen a filter to see more."
                  action={
                    <Button
                      size="sm"
                      onClick={() => {
                        setType('')
                        setSeverity('')
                        setStatus('open')
                        reset()
                      }}
                    >
                      Clear filters
                    </Button>
                  }
                />
              ) : (
                <EmptyState compact icon="check" iconClassName="text-healthy" title="Nothing has drifted" message="Every watched resource matches what was asked for." />
              )
            }
            footer={
              <CursorPager
                count={rows.length}
                hasNext={!!list.data?.next}
                hasPrevious={paging.hasPrevious}
                onNext={() => list.data?.next && paging.next(list.data.next)}
                onPrevious={paging.previous}
                pageSize={pageSize}
                onPageSizeChange={(size) => {
                  setPageSize(size)
                  reset()
                }}
                page={paging.page}
                loading={list.fetching}
                noun="finding"
              />
            }
          />
        )}
        <LiveLine>{live}</LiveLine>
      </section>

      {dialog && dialogType && dialog.kind === 'accept' && <AcceptDialog finding={dialog.finding} type={dialogType} name={idOf(dialog.finding.wfrn)} onClose={() => setDialog(null)} onDone={onDone} />}
      {dialog && dialogType && dialog.kind === 'ignore' && <IgnoreDialog finding={dialog.finding} type={dialogType} name={idOf(dialog.finding.wfrn)} onClose={() => setDialog(null)} onDone={onDone} />}
    </>
  )
}

/** "2 critical, 5 warning, 1 info", or that none are. */
function severityLine(counts: Partial<Record<Severity, number>>): string {
  const parts = SEVERITIES.filter((s) => counts[s]).map((s) => `${counts[s]} ${SEVERITY_WORDS[s].toLowerCase()}`)
  return parts.length ? parts.join(', ') : 'None'
}

/** A row's revert: running with its request, failed, or how the finding closed. */
function RevertCell({ finding }: { finding: Finding }) {
  const state = revertStateOf(finding)
  if (finding.status === 'closed') {
    const words = finding.resolution ? RESOLUTION_WORDS[finding.resolution] : undefined
    return <DotTag tone={words?.tone ?? 'neutral'}>{words?.label ?? 'Closed'}</DotTag>
  }
  const link = (label: ReactNode, text: string) =>
    finding.revertJobId ? (
      <Link to={pathFor('request', { id: finding.revertJobId })} className="hover:underline" aria-label={`${text}: open its request`}>
        {label}
      </Link>
    ) : (
      label
    )
  if (state === 'running')
    return link(
      <DotTag tone="accent" pulse>
        Reverting
      </DotTag>,
      'Reverting',
    )
  if (state === 'failed') return link(<DotTag tone="critical">Revert failed</DotTag>, 'Revert failed')
  return <span className="text-caption text-tertiary">Not asked for</span>
}

/** The selection's bar: how many are selected, how many may be reverted, and how a bulk revert is going. */
function BulkBar({ selected, revertable, bulk, onRevert, onClear }: { selected: number; revertable: number; bulk: BulkState | null; onRevert: () => void; onClear: () => void }) {
  return (
    <div className="mb-3 space-y-2 rounded-lg border border-stroke-light bg-surface-raised px-4 py-3" data-bulk>
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-sm text-primary">{plural(selected, 'finding')} selected</span>
        {(selected > 0 || bulk?.running) && (
          <Button size="sm" variant="primary" icon="refresh" onClick={onRevert} disabled={!revertable || !!bulk?.running} loading={bulk?.running}>
            {`Revert ${revertable}`}
          </Button>
        )}
        {selected > revertable && <span className="text-caption text-secondary">{plural(selected - revertable, 'selected finding')} cannot be reverted here.</span>}
        <Button size="sm" variant="ghost" onClick={onClear} disabled={!!bulk?.running}>
          {selected > 0 ? 'Clear selection' : 'Dismiss'}
        </Button>
      </div>
      {bulk && (
        <div className="space-y-1" role="status" aria-live="polite" data-bulk-status>
          <p className="text-sm text-primary">{bulk.running ? `Reverting: ${bulk.done} of ${bulk.total} sent, ${bulk.started} started` : `${plural(bulk.started, 'revert')} started${bulk.failed.length ? `, ${bulk.failed.length} could not start` : ''}.`}</p>
          {bulk.failed.length > 0 && (
            <ul className="list-inside list-disc text-caption text-secondary">
              {bulk.failed.map((x) => (
                <li key={x.finding.findingId}>
                  <Mono>{idOf(x.finding.wfrn)}</Mono>: {x.why}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
