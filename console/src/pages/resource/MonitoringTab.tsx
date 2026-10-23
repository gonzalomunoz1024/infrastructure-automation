import { useState } from 'react'
import { Button, EmptyState, IconButton, Icon, Segmented, Skeleton } from '@iaf/ui'
import { DotTag, Section, When, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ExplainedError } from '../../app/commands'
import { ActionButton, useRefusal } from '../../app/Refusal'
import { navigate, pathFor, useRoute } from '../../app/router'
import { isApiError } from '../../lib/api'
import { durationMs } from '../../lib/chart'
import { ALARM_STATE_WORDS, DEFAULT_RANGE, RANGES, alarmCondition, alarmName, formatMetric, monitoringApi, rangeOf, unitOfMetric, windowOf, type Alarm, type ResourceMetrics } from '../../lib/monitoring'
import { denialOf, type Decision } from '../../lib/permissions'
import { useQuery, type QueryResult } from '../../lib/queries'
import { AlarmDialog, DeleteAlarmDialog } from '../operations/AlarmDialog'
import { MetricChart } from './MetricChart'
import type { TabProps } from './tabs'

/*
 * The Monitoring tab (WP 9.21): the resource's series from its provider's metrics read, one
 * chart each, over a range kept in the page's address (`range`, a day when absent); then the
 * resource's alarms, each with its state in words, which those who may change the resource
 * create, edit and delete. A threshold of an alarm is drawn on its series' chart. The charts
 * read again every minute; while a new range loads, the last one stays on screen, faded.
 */

const REFRESH_MS = 60_000

export function MonitoringTab({ type, wfrn, detail }: TabProps) {
  const route = useRoute()
  const { decide } = useConsole()
  const range = rangeOf(route.query.range)
  const resourceName = detail?.name ?? wfrn.slice(wfrn.lastIndexOf('/') + 1)
  const setRange = (id: string) => navigate(pathFor('resource', route.params, { ...route.query, range: id === DEFAULT_RANGE ? undefined : id }, { accountId: route.accountId }), { replace: true })

  const metrics = useQuery(['console-metrics', type.name, wfrn, range.id], () => monitoringApi.metrics(type.name, wfrn, windowOf(range, Date.now())), { refetchInterval: REFRESH_MS })
  const alarms = useQuery(['console-alarms', type.name, wfrn], () => monitoringApi.alarmsOf(type.name, wfrn), { refetchInterval: REFRESH_MS })

  // the last answer for this resource stays on screen, faded, while another range loads
  const [held, setHeld] = useState<ResourceMetrics | undefined>(undefined)
  if (metrics.data && metrics.data !== held) setHeld(metrics.data)
  const shown = metrics.data ?? (held?.wfrn === wfrn && !metrics.error ? held : undefined)
  const stale = !metrics.data && !!shown

  const alarmList = alarms.data?.items ?? []
  const series = shown?.series ?? []
  const [editing, setEditing] = useState<Alarm | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Alarm | null>(null)
  const update = decide(type, 'update')

  return (
    <div className="space-y-8">
      <section aria-label="Metrics" className="space-y-4" aria-busy={metrics.fetching}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Segmented ariaLabel="Time range" size="sm" value={range.id} onChange={setRange} options={RANGES.map((r) => ({ value: r.id, label: r.label }))} />
          <div className="flex items-center gap-2 text-caption text-secondary">
            <span role="status" aria-live="polite" data-metrics-status>
              {metrics.fetching ? (
                'Reading the metrics'
              ) : shown ? (
                <>
                  Read <When at={shown.to} />, a point every {durationWords(shown.step)}
                </>
              ) : null}
            </span>
            <IconButton size="sm" label="Read the metrics again" onClick={() => void metrics.refetch()} disabled={metrics.fetching}>
              <Icon name="refresh" size={14} />
            </IconButton>
          </div>
        </div>
        <MetricsBody metrics={metrics} shown={shown} stale={stale} alarms={alarmList} typeTitle={type.title} resourceName={resourceName} />
      </section>

      <Section
        title="Alarms"
        description="An alarm compares the average of one series over its window with a threshold, every minute."
        actions={
          <ActionButton decision={update} what={`Create an alarm on ${resourceName}`} icon="plus" size="sm" onClick={() => setEditing('new')} verb="create-alarm">
            Create alarm
          </ActionButton>
        }
      >
        {alarms.error ? (
          <ExplainedError error={alarms.error} what="Load the alarms" onRetry={() => void alarms.refetch()} compact />
        ) : alarms.loading ? (
          <Skeleton className="h-16 w-full" />
        ) : alarmList.length === 0 ? (
          <EmptyState compact icon="bell" title="No alarms on this resource" message={update.allowed ? 'Create one to hear when a series crosses a line, such as CPU above 80% for 10 minutes.' : 'Someone who may change this resource can create one.'} />
        ) : (
          <ul className="divide-y divide-stroke-light rounded-lg border border-stroke-light" aria-label={`Alarms on ${resourceName}`}>
            {alarmList.map((a) => (
              <AlarmRow key={a.alarmId} alarm={a} resourceName={resourceName} decision={update} onEdit={() => setEditing(a)} onDelete={() => setDeleting(a)} />
            ))}
          </ul>
        )}
      </Section>

      {editing && <AlarmDialog wfrn={wfrn} resourceName={resourceName} series={series.map((s) => ({ name: s.name, unit: s.unit }))} alarm={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      {deleting && <DeleteAlarmDialog alarm={deleting} resourceName={resourceName} onClose={() => setDeleting(null)} />}
    </div>
  )
}

/** An ISO-8601 step in words: PT5M is "5 minutes", PT1H "hour". */
function durationWords(step: string): string {
  const ms = durationMs(step)
  if (!ms) return step
  const minutes = Math.round(ms / 60_000)
  if (minutes < 60) return minutes === 1 ? 'minute' : `${minutes} minutes`
  const hours = Math.round(minutes / 60)
  if (hours === 1) return 'hour'
  return hours % 24 === 0 ? (hours === 24 ? 'day' : `${hours / 24} days`) : `${hours} hours`
}

interface MetricsBodyProps {
  metrics: QueryResult<ResourceMetrics>
  shown: ResourceMetrics | undefined
  stale: boolean
  alarms: Alarm[]
  typeTitle: string
  resourceName: string
}

function MetricsBody({ metrics, shown, stale, alarms, typeTitle, resourceName }: MetricsBodyProps) {
  const refusal = useRefusal()
  const error = shown ? undefined : metrics.error
  if (error) {
    const denied = denialOf(error)
    if (isApiError(error) && error.status === 403 && denied) {
      return (
        <EmptyState
          icon="lock"
          title="You may not read this resource’s metrics"
          message={`Reading them needs ${denied.action} on the resource, and your roles in this account do not allow it.`}
          action={
            <Button size="sm" icon="help" onClick={() => refusal.show({ denied, what: 'Read the metrics' })}>
              Why not
            </Button>
          }
        />
      )
    }
    if (isApiError(error) && error.error === 'metrics.notSupported') return <EmptyState icon="chart" title="No metrics for this type" message={`The provider of ${typeTitle.toLowerCase()} publishes no metrics read, so there is nothing to draw. Drift and activity are on their own tabs.`} />
    if (isApiError(error) && error.status === 429) return <Busy retryAfter={error.retryAfter} onRetry={() => void metrics.refetch()} />
    return <ExplainedError error={error} what="Read the metrics" onRetry={() => void metrics.refetch()} />
  }
  if (!shown) {
    return (
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2" data-metrics-loading>
        <span className="sr-only">Loading the metrics</span>
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-64 w-full" />
        ))}
      </div>
    )
  }
  const from = Date.parse(shown.from)
  const to = Date.parse(shown.to)
  const step = durationMs(shown.step) ?? 300_000
  if (shown.series.every((s) => s.points.every((p) => p.value === null))) {
    return <EmptyState icon="chart" title="No data in this range" message={`The provider reported nothing for ${resourceName} in this range. A resource reports once it is running; try a longer range.`} />
  }
  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
      {shown.series.map((s) => (
        <MetricChart
          key={s.name}
          series={{ ...s, unit: s.unit ?? unitOfMetric(s.name) }}
          from={from}
          to={to}
          stepMs={step}
          stale={stale}
          thresholds={alarms.filter((a) => a.metric === s.name).map((a) => ({ value: a.threshold, label: `${alarmName(a)} (${ALARM_STATE_WORDS[a.state].text})` }))}
        />
      ))}
    </div>
  )
}

/** The provider's observe budget is used up: when to try again, counted down. */
function Busy({ retryAfter, onRetry }: { retryAfter?: number; onRetry: () => void }) {
  const now = useNow(1000)
  const [since] = useState(now)
  const left = retryAfter ? Math.max(0, Math.ceil((since + retryAfter * 1000 - now) / 1000)) : 0
  const at = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(since + (retryAfter ?? 0) * 1000)
  return (
    <EmptyState
      icon="clock"
      title="The provider is busy"
      message={retryAfter ? `This account has read the provider’s metrics as often as its budget allows for now. Try again at ${at}${left > 0 ? `, in ${left} ${left === 1 ? 'second' : 'seconds'}` : ''}.` : 'This account has read the provider’s metrics as often as its budget allows for now. Try again in a minute.'}
      action={
        <Button size="sm" icon="refresh" onClick={onRetry} disabled={left > 0}>
          Try again
        </Button>
      }
    />
  )
}

const SINCE_WORDS: Record<Alarm['state'], string> = { ok: 'OK', alarm: 'in alarm', insufficient: 'without data' }

function AlarmRow({ alarm, resourceName, decision, onEdit, onDelete }: { alarm: Alarm; resourceName: string; decision: Decision; onEdit: () => void; onDelete: () => void }) {
  const words = ALARM_STATE_WORDS[alarm.state]
  const unit = unitOfMetric(alarm.metric)
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 px-4 py-3" data-alarm={alarm.alarmId}>
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <DotTag tone={words.tone}>{words.text}</DotTag>
          <span className="font-medium text-primary">{alarmName(alarm)}</span>
        </p>
        <p className="mt-1 text-caption text-secondary">
          {alarmCondition(alarm, unit)}. {alarm.state === 'insufficient' ? 'No points in the window' : `Last average ${formatMetric(alarm.lastValue, unit)}`}
          {alarm.stateSince && (
            <>
              , {SINCE_WORDS[alarm.state]} since <When at={alarm.stateSince} />
            </>
          )}
          .
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <ActionButton decision={decision} what={`Edit ${alarmName(alarm)}`} size="sm" icon="edit" onClick={onEdit} verb="edit-alarm">
          Edit
        </ActionButton>
        <ActionButton decision={decision} what={`Delete ${alarmName(alarm)} on ${resourceName}`} size="sm" variant="ghost" icon="trash" onClick={onDelete} verb="delete-alarm">
          Delete
        </ActionButton>
      </div>
    </li>
  )
}
