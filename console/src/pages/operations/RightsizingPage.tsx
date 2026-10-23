import { useState, type FormEvent } from 'react'
import { Button, Field, Modal, OverlayBody, OverlayFooter, OverlayHeader, PageHeader, Segmented, Select, Stat, Table, Textarea, type TableColumn } from '@iaf/ui'
import { ConfirmDialog, CursorPager, DotTag, ErrorState, When, useCursorPaging } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { FailureNote, useCommand } from '../../app/commands'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, resourcePath, type PageProps } from '../../app/router'
import { partsOfWfrn, singularOf, type ConsoleType } from '../../lib/descriptors'
import { KIND_WORDS, STATUS_WORDS, formatMoney, formatPercent, monitoringApi, sizeWords, type Recommendation, type RecommendationKind, type RecommendationStatus } from '../../lib/monitoring'
import { useQuery } from '../../lib/queries'
import { useSubmissionKey } from '../../lib/submission'

/*
 * Rightsizing (WP 9.22): what the drift service's daily pass found idle (CPU and memory both
 * under 5% at the 95th percentile over 14 days: delete it) or oversized (both under 30%: the
 * smallest size that keeps them under 60%), the largest saving first, with what each would save
 * a month. Applying runs the type's update workflow with the proposed size, or its delete
 * workflow after the resource's name is typed, on the person's behalf, and the page links the
 * request it started; dismissing sets one aside for good or for a while.
 */

const STATUSES: readonly RecommendationStatus[] = ['open', 'applied', 'dismissed', 'superseded']
const DAY = 24 * 3_600_000

interface Started {
  rec: Recommendation
  jobId: string
}

export default function RightsizingPage({ route }: PageProps) {
  const { types, account } = useConsole()
  const status: RecommendationStatus = STATUSES.includes(route.query.status as RecommendationStatus) ? (route.query.status as RecommendationStatus) : 'open'
  const kind = route.query.kind === 'idle' || route.query.kind === 'oversized' ? (route.query.kind as RecommendationKind) : undefined
  const watched = types.filter((t) => t.observe.metrics)
  const typeName = watched.some((t) => t.name === route.query.type) ? route.query.type : undefined
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(50)
  const setQuery = (q: Record<string, string | undefined>) => {
    paging.reset()
    navigate(pathFor('rightsizing', {}, { ...route.query, ...q }, { accountId: route.accountId }), { replace: true })
  }

  const summary = useQuery(['recommendations', 'summary', account.id], () => monitoringApi.summary())
  const list = useQuery(['recommendations', 'list', account.id, status, kind ?? '', typeName ?? '', paging.after ?? '', pageSize], () => monitoringApi.recommendations({ status, kind, type: typeName, after: paging.after, limit: pageSize }))
  const rows = list.data?.items ?? []
  const typeOf = (name: string) => types.find((t) => t.name === name)

  const [applying, setApplying] = useState<Recommendation | null>(null)
  const [dismissing, setDismissing] = useState<Recommendation | null>(null)
  const [started, setStarted] = useState<Started | null>(null)
  const cmd = useCommand()
  const keyFor = useSubmissionKey()

  const apply = async (rec: Recommendation) => {
    const body = rec.action === 'delete' ? { confirm: true } : {}
    const key = keyFor({ id: rec.recommendationId, ...body })
    const done = await cmd.run(rec.action === 'delete' ? `Delete ${rec.name ?? 'the resource'}` : `Resize ${rec.name ?? 'the resource'}`, () => monitoringApi.apply(rec.recommendationId, body, key), { invalidates: ['recommendations'], quiet: true })
    if (done) setStarted({ rec, jobId: done.jobId })
  }

  const columns: TableColumn<Recommendation>[] = [
    {
      key: 'resource',
      header: 'Resource',
      render: (r) => <ResourceCell rec={r} type={typeOf(r.type)} />,
    },
    {
      key: 'use',
      header: 'Use over 14 days',
      render: (r) => (
        <span className="block text-caption">
          <span className="block text-primary">
            CPU {formatPercent(r.utilisation.cpuP95)}, memory {formatPercent(r.utilisation.memoryP95)}
          </span>
          <span className="block text-tertiary">95th percentile</span>
        </span>
      ),
      width: '11rem',
    },
    {
      key: 'change',
      header: 'Recommended',
      render: (r) =>
        r.action === 'resize' ? (
          <span className="block text-caption text-primary">
            Resize from {sizeWords(r.current)} to {sizeWords(r.proposed)}
          </span>
        ) : (
          <span className="block text-caption text-primary">Delete it{r.current && Object.keys(r.current).length ? ` (${sizeWords(r.current)})` : ''}</span>
        ),
    },
    { key: 'cost', header: 'Costs a month', numeric: true, render: (r) => <span className="tabular-nums">{formatMoney(r.monthlyCost)}</span>, width: '7rem' },
    { key: 'saving', header: 'Saves a month', numeric: true, render: (r) => <span className="font-semibold tabular-nums text-primary">{formatMoney(r.monthlySaving)}</span>, width: '7rem' },
    {
      key: 'actions',
      header: status === 'open' ? 'Actions' : 'Decided',
      render: (r) => <Decide rec={r} type={typeOf(r.type)} onApply={() => setApplying(r)} onDismiss={() => setDismissing(r)} />,
      width: status === 'open' ? '13rem' : '14rem',
    },
  ]

  const s = summary.data
  return (
    <>
      <PageHeader title="Rightsizing" subtitle={`Idle and oversized resources in ${account.name} from the last 14 days of their metrics, with what a resize or delete would save.`} />

      <section aria-label="Summary" className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Open recommendations" value={s ? String(s.open) : '…'} hint={s?.lastPassAt ? <>Last pass <When at={s.lastPassAt} /></> : 'A pass runs every day'} icon="chart" />
        <Stat label="Idle" value={s ? String(s.byKind.idle ?? 0) : '…'} hint="Delete them" />
        <Stat label="Oversized" value={s ? String(s.byKind.oversized ?? 0) : '…'} hint="Resize them" />
        <Stat label="Saving a month" value={s ? formatMoney(s.monthlySaving) : '…'} hint="If every open one is applied, at list prices" />
      </section>

      {started && (
        <div role="status" className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-stroke-light bg-surface-secondary px-4 py-3 text-sm" data-started={started.jobId}>
          <p className="text-primary">
            {started.rec.action === 'resize' ? 'Resize' : 'Delete'} of {started.rec.name ?? 'the resource'} requested.{' '}
            <Link to={pathFor('request', { id: started.jobId })} className="font-medium text-accent underline-offset-2 hover:underline">
              View request {started.jobId}
            </Link>
          </p>
          <Button size="sm" variant="ghost" onClick={() => setStarted(null)}>
            Dismiss
          </Button>
        </div>
      )}
      <FailureNote failure={cmd.failure} onDismiss={cmd.clear} className="mb-4" />

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Segmented<RecommendationStatus> ariaLabel="Status" size="sm" value={status} onChange={(v) => setQuery({ status: v === 'open' ? undefined : v })} options={STATUSES.map((v) => ({ value: v, label: STATUS_WORDS[v] }))} />
        <Select
          size="sm"
          className="w-40"
          ariaLabel={`Kind: ${kind ? KIND_WORDS[kind].text : 'Every kind'}`}
          value={kind ?? ''}
          onChange={(v) => setQuery({ kind: v || undefined })}
          options={[
            { value: '', label: 'Every kind' },
            { value: 'idle', label: 'Idle' },
            { value: 'oversized', label: 'Oversized' },
          ]}
        />
        <Select size="sm" className="w-48" ariaLabel={`Type: ${typeName ? (typeOf(typeName)?.title ?? typeName) : 'Every type'}`} value={typeName ?? ''} onChange={(v) => setQuery({ type: v || undefined })} options={[{ value: '', label: 'Every type' }, ...watched.map((t) => ({ value: t.name, label: t.title }))]} />
      </div>

      {list.error ? (
        <ErrorState error={list.error} title="Could not load the recommendations" onRetry={() => void list.refetch()} />
      ) : (
        <>
          <Table<Recommendation>
            caption={`${STATUS_WORDS[status]} recommendations, the largest saving first`}
            columns={columns}
            rows={rows}
            rowKey={(r) => r.recommendationId}
            loading={list.loading}
            serverSort
            emptyIcon="chart"
            emptyTitle={status === 'open' ? 'Nothing to rightsize' : `No ${STATUS_WORDS[status].toLowerCase()} recommendations`}
            emptyMessage={status === 'open' ? 'Every resource the last pass read is busy enough for its size. The next pass runs tomorrow at 05:00 UTC.' : 'Recommendations land here once someone applies or dismisses them.'}
          />
          <CursorPager className="mt-3" count={rows.length} hasNext={!!list.data?.next} hasPrevious={paging.hasPrevious} onNext={() => list.data?.next && paging.next(list.data.next)} onPrevious={paging.previous} page={paging.page} pageSize={pageSize} onPageSizeChange={(n) => { paging.reset(); setPageSize(n) }} loading={list.fetching} noun="recommendation" />
        </>
      )}

      {applying && applying.action === 'resize' && (
        <ConfirmDialog
          title={`Resize ${applying.name ?? 'this resource'}?`}
          icon="edit"
          confirmLabel="Resize"
          message={`From ${sizeWords(applying.current)} to ${sizeWords(applying.proposed)}, which saves about ${formatMoney(applying.monthlySaving)} a month. Its update workflow runs on your behalf; a production change waits for an approval first.`}
          onClose={() => setApplying(null)}
          onConfirm={() => apply(applying)}
        />
      )}
      {applying && applying.action === 'delete' && (
        <ConfirmDialog
          title={`Delete ${applying.name ?? 'this resource'}?`}
          tone="danger"
          icon="trash"
          confirmLabel="Delete"
          requireText={applying.name ?? applying.wfrn}
          message={`It has been idle for 14 days: CPU at ${formatPercent(applying.utilisation.cpuP95)} and memory at ${formatPercent(applying.utilisation.memoryP95)} at the 95th percentile. Deleting it saves about ${formatMoney(applying.monthlySaving)} a month, waits for an approval, and cannot be undone.`}
          onClose={() => setApplying(null)}
          onConfirm={() => apply(applying)}
        />
      )}
      {dismissing && <DismissDialog rec={dismissing} onClose={() => setDismissing(null)} />}
    </>
  )
}

function ResourceCell({ rec, type }: { rec: Recommendation; type: ConsoleType | undefined }) {
  const service = type?.service ?? partsOfWfrn(rec.wfrn)?.service ?? 'platform'
  const words = KIND_WORDS[rec.kind]
  return (
    <span className="block min-w-0">
      <Link to={resourcePath(service, rec.type, rec.wfrn)} className="block truncate font-medium text-accent underline-offset-2 hover:underline">
        {rec.name ?? rec.wfrn}
      </Link>
      <span className="mt-1 flex flex-wrap items-center gap-1.5 text-caption text-tertiary">
        <DotTag tone={words.tone} size="sm">
          {words.text}
        </DotTag>
        {type ? singularOf(type.title) : rec.type}
      </span>
    </span>
  )
}

/** What a person may do with an open recommendation, or what was decided about a closed one. */
function Decide({ rec, type, onApply, onDismiss }: { rec: Recommendation; type: ConsoleType | undefined; onApply: () => void; onDismiss: () => void }) {
  const { decide } = useConsole()
  if (rec.status === 'applied') {
    return (
      <span className="block text-caption text-secondary">
        Applied by {rec.decidedBy ?? 'someone'} <When at={rec.updatedAt} />
        {rec.jobId && (
          <Link to={pathFor('request', { id: rec.jobId })} className="block font-medium text-accent underline-offset-2 hover:underline">
            View request {rec.jobId}
          </Link>
        )}
      </span>
    )
  }
  if (rec.status !== 'open') {
    return (
      <span className="block text-caption text-secondary">
        {rec.status === 'superseded' ? 'Dismissed, and no longer found' : 'Dismissed'} by {rec.decidedBy ?? 'someone'} <When at={rec.updatedAt} />
        <span className="block">{rec.dismissedUntil ? <>Until <When at={rec.dismissedUntil} /></> : 'For good'}</span>
        {rec.reason && <span className="block text-tertiary">{rec.reason}</span>}
      </span>
    )
  }
  const verb = rec.action === 'delete' ? 'delete' : 'update'
  const policy = type ? decide(type, verb) : { allowed: true }
  const dismissPolicy = type ? decide(type, 'update') : { allowed: true }
  const mayApply = rec.can.includes('apply')
  const label = rec.action === 'delete' ? 'Delete' : 'Resize'
  const what = `${label} ${rec.name ?? 'the resource'}`
  const why = !mayApply && policy.allowed ? (rec.action === 'delete' && type && !type.actions.some((a) => a.verb === 'delete') ? `No workflow deletes ${type.title.toLowerCase()}, so the console cannot carry this out.` : 'The console cannot carry this out as recommended.') : null
  return (
    <span className="block">
      <span className="flex flex-wrap items-center gap-2">
        {mayApply || !policy.allowed ? (
          <ActionButton decision={mayApply ? { allowed: true } : policy} what={what} size="sm" variant={rec.action === 'delete' ? 'secondary' : 'primary'} icon={rec.action === 'delete' ? 'trash' : 'edit'} onClick={onApply} verb={`apply-${rec.action}`}>
            {label}
          </ActionButton>
        ) : null}
        <ActionButton decision={rec.can.includes('dismiss') ? { allowed: true } : dismissPolicy} what={`Dismiss the recommendation for ${rec.name ?? 'the resource'}`} size="sm" variant="ghost" onClick={onDismiss} verb="dismiss">
          Dismiss
        </ActionButton>
      </span>
      {why && <span className="mt-1 block text-caption text-secondary">{why}</span>}
    </span>
  )
}

type Duration = 'good' | '7' | '30' | '90'

function DismissDialog({ rec, onClose }: { rec: Recommendation; onClose: () => void }) {
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const [duration, setDuration] = useState<Duration>('good')
  const [reason, setReason] = useState('')
  const tooLong = reason.length > 1000
  const name = rec.name ?? 'this resource'

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (tooLong) return
    const body = { ...(reason.trim() ? { reason: reason.trim() } : {}), ...(duration === 'good' ? {} : { until: new Date(Date.now() + Number(duration) * DAY).toISOString() }) }
    const done = await cmd.run(`Dismiss the recommendation for ${name}`, () => monitoringApi.dismiss(rec.recommendationId, body, keyFor({ id: rec.recommendationId, duration, reason: body.reason })), {
      invalidates: ['recommendations'],
      quiet: true,
      done: () => ({ title: 'Recommendation dismissed', message: duration === 'good' ? `${name} is left as it is for good.` : `${name} is left as it is for ${duration} days; a later pass may bring it back after that.` }),
    })
    if (done) onClose()
  }

  return (
    <Modal onClose={onClose} label={`Dismiss the recommendation for ${name}`} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)}>
        <OverlayHeader title={`Dismiss the recommendation for ${name}`} subtitle={rec.action === 'resize' ? `Keep it at ${sizeWords(rec.current)} instead of ${sizeWords(rec.proposed)}.` : 'Keep it instead of deleting it.'} icon="chart" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="For how long">
              <Segmented<Duration>
                ariaLabel="For how long to dismiss it"
                value={duration}
                onChange={setDuration}
                full
                options={[
                  { value: 'good', label: 'For good' },
                  { value: '7', label: '7 days' },
                  { value: '30', label: '30 days' },
                  { value: '90', label: '90 days' },
                ]}
              />
            </Field>
            <Field label="Reason" htmlFor="dismiss-reason" hint="Optional, up to 1,000 characters. People who read the recommendation later see it." error={tooLong ? 'Up to 1,000 characters' : undefined}>
              <Textarea id="dismiss-reason" data-autofocus rows={3} value={reason} onChange={(e) => setReason(e.target.value)} invalid={tooLong} placeholder="A sale is coming and the headroom is wanted" />
            </Field>
            <FailureNote failure={cmd.failure} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy} disabled={tooLong}>
            Dismiss
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}
