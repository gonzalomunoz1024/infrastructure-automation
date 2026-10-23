import type { ReactNode } from 'react'
import { BarChart, Button, Card, EmptyState, LineChart, Skeleton, SkeletonRows, Stat, Table, formatNumber, type IconName, type TableColumn } from '@iaf/ui'
import { ChartFigure, DotTag, ErrorState } from '@iaf/console-kit'
import type { CostReport } from '../../lib/cost'
import type { Insights } from '../../lib/insights'
import type { Denial } from '../../lib/permissions'
import { PERIOD_LABEL, dayWords, headlineOf, listWords, money, moneyTick, shareWords, spendNotes, spendRows, type SpendRow } from './words'

/*
 * The executive home's parts (WP 9.5): the headline tiles, adoption week by week, managed
 * resources by service, spend by team, and the page's skeleton. Each takes the answers as they
 * came, so the page decides only which state to show; every chart sits in a ChartFigure or over
 * a table, so its numbers are readable without the picture.
 */

/** Spend as the page holds it: the cost service's report, or why there is none. */
export type Spend = { status: 'loading' } | { status: 'ready'; report: CostReport } | { status: 'refused'; denied: Denial } | { status: 'failed'; error: unknown }

const ESTIMATES = 'Estimates from list prices, not invoices'

type Week = Insights['adoption'][number]
type ServiceCount = NonNullable<Insights['managed']['byService']>[number]

/** The six headline numbers: managed, self-served, lead time, hours saved, drift fixed, spend. */
export function HeadlineStats({ insights, spend }: { insights: Insights; spend: Spend }) {
  const h = headlineOf(insights)
  const spent =
    spend.status === 'ready'
      ? { value: money(spend.report.total, spend.report.currency), hint: ESTIMATES }
      : spend.status === 'loading'
        ? { value: <Skeleton className="mt-1 h-6 w-28" />, hint: 'Loading' }
        : { value: 'Not available', hint: spend.status === 'refused' ? 'Your role may not read cost' : 'Could not load spend' }
  const tiles: { label: string; icon: IconName; value: ReactNode; hint: ReactNode }[] = [
    { label: 'Resources managed', icon: 'resource', ...h.managed },
    { label: 'Self-served', icon: 'bolt', ...h.selfServed },
    { label: 'Median lead time', icon: 'clock', ...h.leadTime },
    { label: 'Hours saved', icon: 'history', value: h.hoursSaved.value, hint: h.hoursSaved.hint },
    {
      label: 'Drift fixed',
      icon: 'shield',
      value: h.drift.value,
      hint: (
        <>
          <DotTag size="sm" tone={h.drift.open ? 'warning' : 'healthy'}>
            {h.drift.openWords}
          </DotTag>
          {h.drift.note && <span>{h.drift.note}</span>}
        </>
      ),
    },
    { label: 'Spend in the period', icon: 'chart', ...spent },
  ]
  return (
    <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label="Headline numbers">
      {tiles.map((t) => (
        <li key={t.label} className="min-w-0">
          <Stat label={t.label} icon={t.icon} value={t.value} hint={t.hint} className="h-full" />
        </li>
      ))}
    </ul>
  )
}

const weekColumns: TableColumn<Week>[] = [
  { key: 'week', header: 'Week', accessor: (w) => dayWords(w.weekStart) },
  { key: 'requests', header: 'Requests', numeric: true, accessor: (w) => w.requests },
  { key: 'selfServed', header: 'Self-served', numeric: true, accessor: (w) => w.selfServed },
  { key: 'share', header: 'Share', numeric: true, accessor: (w) => shareWords(w.selfServed, w.requests) },
]
const peopleColumns: TableColumn<Week>[] = [
  { key: 'week', header: 'Week', accessor: (w) => dayWords(w.weekStart) },
  { key: 'people', header: 'People', numeric: true, accessor: (w) => w.activeUsers },
]

/** Requests and self-served requests week by week, and the people making them, as two charts: two units never share an axis. */
export function AdoptionCard({ insights }: { insights: Insights }) {
  const weeks = insights.adoption
  const labels = weeks.map((w) => dayWords(w.weekStart))
  const span = labels.length ? `weeks of ${labels[0]} to ${labels[labels.length - 1]}` : ''
  const dots = weeks.length <= 8
  return (
    <Card title="Adoption" subtitle="Week by week; weeks start on Monday (UTC), and the first and last count only the days inside the period.">
      {weeks.every((w) => w.requests === 0) ? (
        <EmptyState
          compact
          icon="pulse"
          title={`No requests in the last ${PERIOD_LABEL[insights.period]}`}
          message="Requests appear here week by week as people create, change and delete resources."
        />
      ) : (
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
          <ChartFigure title="Requests" description="Every request, and those that ran with no one stepping in" columns={weekColumns} rows={weeks} rowKey={(w) => w.weekStart}>
            <LineChart
              series={[
                { id: 'requests', label: 'Requests', values: weeks.map((w) => w.requests) },
                { id: 'self-served', label: 'Self-served', values: weeks.map((w) => w.selfServed) },
              ]}
              labels={labels}
              dots={dots}
              height={180}
              ariaLabel={`Requests and self-served requests by week, ${span}`}
            />
          </ChartFigure>
          <ChartFigure title="Active people" description="People who started at least one request" columns={peopleColumns} rows={weeks} rowKey={(w) => w.weekStart}>
            <LineChart values={weeks.map((w) => w.activeUsers)} labels={labels} dots={dots} height={180} ariaLabel={`Active people by week, ${span}`} />
          </ChartFigure>
        </div>
      )}
    </Card>
  )
}

/** Live resources by service, largest first. */
export function ServicesCard({ insights }: { insights: Insights }) {
  const rows = insights.managed.byService ?? []
  const total = insights.managed.resources
  const columns: TableColumn<ServiceCount>[] = [
    { key: 'service', header: 'Service', accessor: (s) => s.title ?? s.service },
    { key: 'resources', header: 'Resources', numeric: true, accessor: (s) => s.resources },
    { key: 'share', header: 'Share', numeric: true, accessor: (s) => shareWords(s.resources, total) },
  ]
  return (
    <Card title="Managed resources by service" subtitle="Live resources now; deleted ones are left out.">
      {rows.length === 0 ? (
        <EmptyState compact icon="resource" title="No resources yet" message="Resources appear here as the accounts create them." />
      ) : (
        <ChartFigure title="Resources by service" columns={columns} rows={rows} rowKey={(s) => s.service}>
          <BarChart
            horizontal
            showValues
            values={rows.map((s) => s.resources)}
            labels={rows.map((s) => s.title ?? s.service)}
            height={rows.length * 36 + 28}
            ariaLabel={`Resources by service: ${rows.map((s) => `${s.title ?? s.service} ${formatNumber(s.resources)}`).join(', ')}`}
          />
        </ChartFigure>
      )}
    </Card>
  )
}

export interface SpendCardProps {
  spend: Spend
  period: Insights['period']
  /** the team that owns an account, from the memberships and the numbers */
  teamOf: (accountId: string) => string | undefined
  /** an account's name, for those left out */
  nameOf: (accountId: string) => string
  onRetry: () => void
  onSeeWhy: (denied: Denial) => void
  /** the link to Cost and usage, in the card's header */
  action?: ReactNode
}

/** What each cost centre was charged over the period, with its accounts and teams, as a bar chart over its table. */
export function SpendCard({ spend, period, teamOf, nameOf, onRetry, onSeeWhy, action }: SpendCardProps) {
  return (
    <Card title="Spend by team" subtitle={`${ESTIMATES}. Each cost centre with the accounts charged to it.`} actions={action}>
      {spend.status === 'loading' ? (
        <SkeletonRows n={3} rowClassName="h-10" />
      ) : spend.status === 'refused' ? (
        <EmptyState
          compact
          icon="lock"
          title="You can’t read spend here"
          message={spend.denied.reason}
          action={
            <Button size="sm" onClick={() => onSeeWhy(spend.denied)}>
              See why
            </Button>
          }
        />
      ) : spend.status === 'failed' ? (
        <ErrorState compact error={spend.error} title="Could not load spend" onRetry={onRetry} />
      ) : (
        <SpendBody report={spend.report} period={period} teamOf={teamOf} nameOf={nameOf} />
      )}
    </Card>
  )
}

function SpendBody({ report, period, teamOf, nameOf }: { report: CostReport; period: Insights['period']; teamOf: SpendCardProps['teamOf']; nameOf: SpendCardProps['nameOf'] }) {
  const rows = spendRows(report, teamOf)
  const cur = report.currency
  const notes = spendNotes(report, nameOf)
  if (rows.length === 0)
    return (
      <>
        <EmptyState compact icon="chart" title="No spend in the period" message={`Nothing was charged in these accounts in the last ${PERIOD_LABEL[period]}.`} />
        <Notes notes={notes} />
      </>
    )
  const columns: TableColumn<SpendRow>[] = [
    {
      key: 'label',
      header: 'Cost centre',
      accessor: (r) => r.label,
      render: (r) => (
        <span className="block min-w-0">
          <span className="block text-primary">{r.label}</span>
          {r.accounts.length > 0 && <span className="block text-caption text-tertiary">{[listWords(r.accounts), listWords(r.teams)].filter(Boolean).join(' · ')}</span>}
        </span>
      ),
    },
    { key: 'amount', header: 'Amount', numeric: true, accessor: (r) => r.amount, render: (r) => money(r.amount, cur) },
    { key: 'share', header: 'Share', numeric: true, accessor: (r) => r.share, render: (r) => shareWords(r.amount, report.total) },
  ]
  return (
    <div className="space-y-4">
      <div className="min-w-0 overflow-hidden">
        <BarChart
          horizontal
          values={rows.map((r) => r.amount)}
          labels={rows.map((r) => r.label)}
          yFormat={(v) => moneyTick(v, cur)}
          height={rows.length * 36 + 28}
          tooltip={(i) => (
            <span className="block">
              <span className="block text-caption2 text-tertiary">{rows[i].label}</span>
              <span className="block font-medium tabular-nums text-primary">{money(rows[i].amount, cur)}</span>
            </span>
          )}
          ariaLabel={`Spend by cost centre: ${rows.map((r) => `${r.label} ${money(r.amount, cur)}`).join(', ')}`}
        />
      </div>
      <Table<SpendRow>
        columns={columns}
        rows={rows}
        rowKey={(r) => r.key}
        caption="Spend by team"
        dense
        stickyHeader={false}
        footer={
          <>
            <span>Total</span>
            <span className="font-medium tabular-nums text-primary">{money(report.total, cur)}</span>
          </>
        }
      />
      <Notes notes={notes} />
    </div>
  )
}

function Notes({ notes }: { notes: string[] }) {
  if (!notes.length) return null
  return (
    <ul className="space-y-1 text-caption text-tertiary">
      {notes.map((n) => (
        <li key={n}>{n}</li>
      ))}
    </ul>
  )
}

/** The page while its first answer is on the way: the tiles and the cards as they will stand. */
export function InsightsSkeleton() {
  return (
    <div role="status" aria-label="Loading the numbers" className="space-y-6">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-[76px]" />
        ))}
      </div>
      <Skeleton className="h-80" />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <Skeleton className="h-64" />
        <Skeleton className="h-64" />
      </div>
    </div>
  )
}
