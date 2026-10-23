import { Button, EmptyState, Skeleton } from '@iaf/ui'
import { DotTag, titleCase } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ExplainedError } from '../../app/commands'
import { useRefusal } from '../../app/Refusal'
import { Link, pathFor } from '../../app/router'
import { bff } from '../../lib/bff'
import { durationMs } from '../../lib/chart'
import { isOpenRequest, type AlarmsWidget, type CountWidget, type MetricWidget, type Widget } from '../../lib/dashboards'
import { partsOfWfrn } from '../../lib/descriptors'
import { SEVERITY_WORDS, driftApi } from '../../lib/drift'
import { ALARM_STATE_WORDS, alarmName, monitoringApi, unitOfMetric, windowOf, type MetricRange } from '../../lib/monitoring'
import { denialOf } from '../../lib/permissions'
import { useQuery, type QueryResult } from '../../lib/queries'
import { countLabel } from '../../lib/resourceList'
import type { ResourceFilters } from '../../lib/types'
import { MetricChart } from '../resource/MetricChart'

/*
 * What a dashboard widget shows, read from the routes the console already has: a resource's
 * metrics (drawn with the Monitoring tab's own chart), a type's counts, the findings summary, the
 * open requests and the alarms. Each reads again every minute, and says in words what it could
 * not read, a refusal with its Why not.
 */

const REFRESH_MS = 60_000
/** A row of the board, in pixels, and the gap between rows: a chart fills the rows it spans. */
export const ROW_PX = 120
export const GAP_PX = 16

export function WidgetBody({ widget, range }: { widget: Widget; range: MetricRange }) {
  switch (widget.kind) {
    case 'metric':
      return <MetricBody widget={widget} range={range} />
    case 'count':
      return <CountBody widget={widget} />
    case 'drift':
      return <DriftBody />
    case 'requests':
      return <RequestsBody />
    case 'alarms':
      return <AlarmsBody widget={widget} />
  }
}

/** A failed read in words: a refusal says who refused and offers Why not. */
function Failed({ error, what, query }: { error: unknown; what: string; query: QueryResult<unknown> }) {
  const refusal = useRefusal()
  const denied = denialOf(error)
  if (denied) {
    return (
      <EmptyState
        compact
        icon="lock"
        title="You may not read this"
        message={`It needs ${denied.action}, which your roles in this account do not allow.`}
        action={
          <Button size="sm" icon="help" onClick={() => refusal.show({ denied, what })}>
            Why not
          </Button>
        }
      />
    )
  }
  return <ExplainedError compact error={error} what={what} onRetry={() => void query.refetch()} />
}

function Big({ value, label }: { value: string; label: string }) {
  return (
    <p className="flex items-baseline gap-2">
      <span className="text-title2 font-semibold tabular-nums text-primary">{value}</span>
      <span className="text-caption text-secondary">{label}</span>
    </p>
  )
}

function MetricBody({ widget, range }: { widget: MetricWidget; range: MetricRange }) {
  const { types } = useConsole()
  const type = types.find((t) => t.name === widget.type)
  const q = useQuery(['console-metrics', widget.type, widget.wfrn, range.id], () => monitoringApi.metrics(widget.type, widget.wfrn, windowOf(range, Date.now())), { refetchInterval: REFRESH_MS })
  const id = partsOfWfrn(widget.wfrn)?.id ?? widget.wfrn
  if (q.error) return <Failed error={q.error} what={`Read the metrics of ${id}`} query={q} />
  if (!q.data) return <Skeleton className="h-full min-h-24 w-full" />
  const series = q.data.series.find((s) => s.name === widget.metric)
  if (!series) return <EmptyState compact icon="chart" title="No such series" message={`${id} reports no ${widget.metric} any more. Edit the dashboard to choose another.`} />
  const height = Math.max(110, widget.height * ROW_PX + (widget.height - 1) * GAP_PX - 150)
  return (
    <div className="space-y-2">
      <MetricChart framed={false} height={height} series={{ ...series, unit: series.unit ?? unitOfMetric(series.name) }} from={Date.parse(q.data.from)} to={Date.parse(q.data.to)} stepMs={durationMs(q.data.step) ?? 300_000} />
      {type && (
        <Link to={pathFor('resource', { service: type.service, type: type.name, wfrn: widget.wfrn }, { tab: 'monitoring' })} className="text-caption font-medium text-accent underline-offset-2 hover:underline">
          Open {id}’s monitoring
        </Link>
      )}
    </div>
  )
}

function CountBody({ widget }: { widget: CountWidget }) {
  const { types } = useConsole()
  const type = types.find((t) => t.name === widget.type)
  const filters: ResourceFilters | undefined = widget.tag ? { [`tag.${widget.tag.key}`]: widget.tag.value } : undefined
  const q = useQuery(['console-resource-counts', widget.type, widget.tag?.key ?? '', widget.tag?.value ?? ''], () => bff.resources.counts(widget.type, filters), { refetchInterval: REFRESH_MS })
  const title = type?.title ?? widget.type
  if (q.error) return <Failed error={q.error} what={`Count the ${title.toLowerCase()}`} query={q} />
  if (!q.data) return <Skeleton className="h-full min-h-20 w-full" />
  const c = q.data
  const states = Object.entries(c.byState).filter(([, n]) => (n ?? 0) > 0) as [string, number][]
  const most = Math.max(1, ...states.map(([, n]) => n))
  return (
    <div className="space-y-3">
      <Big value={countLabel(c.total, c.cappedAt)} label={`${title.toLowerCase()}${widget.tag ? ` tagged ${widget.tag.key}: ${widget.tag.value}` : ''}${c.drift ? `, ${countLabel(c.drift, c.cappedAt)} drifting` : ''}`} />
      {states.length > 0 && (
        <ul className="space-y-1.5" aria-label={`${title} by state`}>
          {states.map(([state, n]) => (
            <li key={state} className="grid grid-cols-[6rem_1fr_auto] items-center gap-2 text-caption">
              <span className="truncate text-secondary">{titleCase(state)}</span>
              <span aria-hidden="true" className="h-1.5 rounded-full bg-surface-secondary">
                <span className="block h-1.5 rounded-full bg-accent" style={{ width: `${Math.max(4, (n / most) * 100)}%` }} />
              </span>
              <span className="tabular-nums text-primary">{countLabel(n, c.cappedAt)}</span>
            </li>
          ))}
        </ul>
      )}
      {type && (
        <Link to={pathFor('type', { service: type.service, type: type.name })} className="block text-caption font-medium text-accent underline-offset-2 hover:underline">
          Open {title.toLowerCase()}
        </Link>
      )}
    </div>
  )
}

function DriftBody() {
  const q = useQuery('console-findings-summary', () => driftApi.findings.summary(), { refetchInterval: REFRESH_MS })
  if (q.error) return <Failed error={q.error} what="Read the drift findings" query={q} />
  if (!q.data) return <Skeleton className="h-full min-h-20 w-full" />
  const s = q.data
  const parts = (['critical', 'warning', 'info'] as const).filter((k) => (s.bySeverity[k] ?? 0) > 0).map((k) => `${s.bySeverity[k]} ${SEVERITY_WORDS[k].toLowerCase()}`)
  return (
    <div className="space-y-2" data-drift-open={s.open}>
      <Big value={String(s.open)} label={s.open === 1 ? 'open finding' : 'open findings'} />
      <p className="text-caption text-secondary">{parts.length ? parts.join(', ') : 'Nothing has drifted.'}</p>
      <Link to={pathFor('drift')} className="block text-caption font-medium text-accent underline-offset-2 hover:underline">
        Open the Drift page
      </Link>
    </div>
  )
}

function RequestsBody() {
  const q = useQuery(['jobs', 'dashboard-open'], () => bff.jobs.list({ limit: 200 }), { refetchInterval: REFRESH_MS })
  if (q.error) return <Failed error={q.error} what="Read the requests" query={q} />
  if (!q.data) return <Skeleton className="h-full min-h-20 w-full" />
  const open = q.data.items.filter((j) => isOpenRequest(String(j.status)))
  const byStatus = new Map<string, number>()
  for (const j of open) byStatus.set(String(j.status), (byStatus.get(String(j.status)) ?? 0) + 1)
  const words = [...byStatus.entries()].map(([s, n]) => `${n} ${titleCase(s).toLowerCase()}`)
  return (
    <div className="space-y-2">
      <Big value={String(open.length)} label={`${open.length === 1 ? 'request' : 'requests'} open${q.data.next ? ' among the latest 200' : ''}`} />
      <p className="text-caption text-secondary">{words.length ? words.join(', ') : 'Nothing is running or waiting.'}</p>
      <Link to={pathFor('requests')} className="block text-caption font-medium text-accent underline-offset-2 hover:underline">
        Open the requests
      </Link>
    </div>
  )
}

function AlarmsBody({ widget }: { widget: AlarmsWidget }) {
  const { types } = useConsole()
  const q = useQuery(['alarms', 'dashboard', widget.widgetId], () => monitoringApi.alarms({ limit: 200 }), { refetchInterval: REFRESH_MS })
  if (q.error) return <Failed error={q.error} what="Read the alarms" query={q} />
  if (!q.data) return <Skeleton className="h-full min-h-20 w-full" />
  const alarms = q.data.items
  const firing = alarms.filter((a) => a.state === 'alarm')
  const count = (s: keyof typeof ALARM_STATE_WORDS) => alarms.filter((a) => a.state === s).length
  return (
    <div className="space-y-2">
      <Big value={String(firing.length)} label={`of ${alarms.length} ${alarms.length === 1 ? 'alarm' : 'alarms'} in alarm`} />
      <p className="flex flex-wrap gap-1.5">
        {(['alarm', 'insufficient', 'ok'] as const).map((s) => (
          <DotTag key={s} size="sm" tone={ALARM_STATE_WORDS[s].tone}>
            {ALARM_STATE_WORDS[s].text}: {count(s)}
          </DotTag>
        ))}
      </p>
      {firing.length > 0 && (
        <ul className="space-y-1 text-caption">
          {firing.slice(0, 3).map((a) => {
            const t = types.find((x) => x.name === a.type)
            return (
              <li key={a.alarmId} className="truncate">
                {t ? (
                  <Link to={pathFor('resource', { service: t.service, type: t.name, wfrn: a.wfrn }, { tab: 'monitoring' })} className="font-medium text-accent underline-offset-2 hover:underline">
                    {alarmName(a)}
                  </Link>
                ) : (
                  alarmName(a)
                )}
              </li>
            )
          })}
        </ul>
      )}
      <Link to={pathFor('alarms')} className="block text-caption font-medium text-accent underline-offset-2 hover:underline">
        Open the alarms
      </Link>
    </div>
  )
}
