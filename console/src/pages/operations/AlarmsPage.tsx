import { useState } from 'react'
import { PageHeader, Segmented, Table, type TableColumn } from '@iaf/ui'
import { CursorPager, DotTag, ErrorState, When, useCursorPaging } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { partsOfWfrn, singularOf, type ConsoleType } from '../../lib/descriptors'
import { ALARM_STATE_WORDS, alarmCondition, alarmName, formatMetric, monitoringApi, rangeOf, unitOfMetric, windowOf, type Alarm, type AlarmState } from '../../lib/monitoring'
import { ALLOWED } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { AlarmDialog, DeleteAlarmDialog } from './AlarmDialog'

/*
 * The account's alarms (WP 9.21), firing first, then those without data, then the quiet ones,
 * as the drift service evaluates them every minute, with a filter by state kept in the address.
 * Each leads to its resource's Monitoring tab, where its threshold is drawn on the chart, and
 * those who may change the resource edit or delete it here.
 */

type Filter = 'all' | AlarmState
const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'alarm', label: 'In alarm' },
  { value: 'insufficient', label: 'Not enough data' },
  { value: 'ok', label: 'OK' },
]

/** The resource an alarm watches: its service, for the page's path, and its id, from the wfrn. */
function resourceOf(alarm: Alarm, type: ConsoleType | undefined) {
  const parts = partsOfWfrn(alarm.wfrn)
  return { service: type?.service ?? parts?.service ?? 'platform', id: parts?.id ?? alarm.wfrn }
}

export default function AlarmsPage({ route }: PageProps) {
  const { types, account, decide } = useConsole()
  const filter: Filter = FILTERS.some((f) => f.value === route.query.state) ? (route.query.state as Filter) : 'all'
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(50)
  const list = useQuery(['alarms', account.id, filter, paging.after ?? '', pageSize], () => monitoringApi.alarms({ state: filter === 'all' ? undefined : filter, after: paging.after, limit: pageSize }), { refetchInterval: 60_000 })
  const rows = list.data?.items ?? []
  const typeOf = (name: string) => types.find((t) => t.name === name)
  const [editing, setEditing] = useState<Alarm | null>(null)
  const [deleting, setDeleting] = useState<Alarm | null>(null)
  const setFilter = (v: Filter) => {
    paging.reset()
    navigate(pathFor('alarms', {}, { ...route.query, state: v === 'all' ? undefined : v }, { accountId: route.accountId }), { replace: true })
  }

  const columns: TableColumn<Alarm>[] = [
    {
      key: 'state',
      header: 'State',
      render: (a) => (
        <span className="block">
          <DotTag tone={ALARM_STATE_WORDS[a.state].tone}>{ALARM_STATE_WORDS[a.state].text}</DotTag>
          {a.stateSince && (
            <span className="mt-1 block text-caption text-tertiary">
              since <When at={a.stateSince} />
            </span>
          )}
        </span>
      ),
      width: '10rem',
    },
    {
      key: 'alarm',
      header: 'Alarm',
      render: (a) => (
        <span className="block min-w-0">
          <span className="block font-medium text-primary">{alarmName(a)}</span>
          <span className="block text-caption text-secondary">{alarmCondition(a, unitOfMetric(a.metric))}</span>
        </span>
      ),
    },
    {
      key: 'resource',
      header: 'Resource',
      render: (a) => {
        const type = typeOf(a.type)
        const { service, id } = resourceOf(a, type)
        return (
          <span className="block min-w-0">
            <Link to={pathFor('resource', { service, type: a.type, wfrn: a.wfrn }, { tab: 'monitoring' })} className="block truncate font-mono text-caption font-medium text-accent underline-offset-2 hover:underline">
              {id}
            </Link>
            <span className="block text-caption text-tertiary">{type ? singularOf(type.title) : a.type}</span>
          </span>
        )
      },
      width: '12rem',
    },
    {
      key: 'value',
      header: 'Last average',
      render: (a) => (
        <span className="block text-caption">
          <span className="block tabular-nums text-primary">{a.state === 'insufficient' ? 'No points' : formatMetric(a.lastValue, unitOfMetric(a.metric))}</span>
          {a.lastEvaluatedAt && (
            <span className="block text-tertiary">
              checked <When at={a.lastEvaluatedAt} />
            </span>
          )}
        </span>
      ),
      width: '9rem',
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (a) => {
        const type = typeOf(a.type)
        const decision = type ? decide(type, 'update') : ALLOWED
        return (
          <span className="flex flex-wrap items-center gap-2">
            <ActionButton decision={decision} what={`Edit ${alarmName(a)}`} size="sm" icon="edit" onClick={() => setEditing(a)} verb="edit-alarm">
              Edit
            </ActionButton>
            <ActionButton decision={decision} what={`Delete ${alarmName(a)}`} size="sm" variant="ghost" icon="trash" onClick={() => setDeleting(a)} verb="delete-alarm">
              Delete
            </ActionButton>
          </span>
        )
      },
      width: '11rem',
    },
  ]

  return (
    <>
      <PageHeader title="Alarms" subtitle={`Metric alarms on the resources of ${account.name}, firing ones first. Each compares the average of one series over its window with a threshold, every minute.`} />
      <div className="mb-4">
        <Segmented<Filter> ariaLabel="State" size="sm" value={filter} onChange={setFilter} options={FILTERS.map((f) => ({ value: f.value, label: f.label }))} />
      </div>
      {list.error ? (
        <ErrorState error={list.error} title="Could not load the alarms" onRetry={() => void list.refetch()} />
      ) : (
        <>
          <Table<Alarm>
            caption={`Alarms of ${account.name}${filter === 'all' ? '' : `, ${FILTERS.find((f) => f.value === filter)?.label.toLowerCase()}`}, firing first`}
            columns={columns}
            rows={rows}
            rowKey={(a) => a.alarmId}
            loading={list.loading}
            serverSort
            emptyIcon="bell"
            emptyTitle={filter === 'all' ? 'No alarms yet' : 'No alarms in this state'}
            emptyMessage={filter === 'all' ? 'Create one on a resource’s Monitoring tab: a series, a threshold and a window.' : 'Every alarm of the account is in another state.'}
          />
          <CursorPager
            className="mt-3"
            count={rows.length}
            hasNext={!!list.data?.next}
            hasPrevious={paging.hasPrevious}
            onNext={() => list.data?.next && paging.next(list.data.next)}
            onPrevious={paging.previous}
            page={paging.page}
            pageSize={pageSize}
            onPageSizeChange={(n) => {
              paging.reset()
              setPageSize(n)
            }}
            loading={list.fetching}
            noun="alarm"
          />
        </>
      )}
      {editing && <EditAlarm alarm={editing} onClose={() => setEditing(null)} />}
      {deleting && <DeleteAlarmDialog alarm={deleting} resourceName={resourceOf(deleting, typeOf(deleting.type)).id} onClose={() => setDeleting(null)} />}
    </>
  )
}

/** The alarm dialog with the series the resource's metrics read answers, read over the last hour. */
function EditAlarm({ alarm, onClose }: { alarm: Alarm; onClose: () => void }) {
  const metrics = useQuery(['console-metrics-series', alarm.type, alarm.wfrn], () => monitoringApi.metrics(alarm.type, alarm.wfrn, windowOf(rangeOf('1h'), Date.now())))
  const series = (metrics.data?.series ?? []).map((s) => ({ name: s.name, unit: s.unit }))
  return <AlarmDialog wfrn={alarm.wfrn} resourceName={partsOfWfrn(alarm.wfrn)?.id ?? alarm.wfrn} series={series} alarm={alarm} onClose={onClose} />
}
