import { Card, ProgressBar, SkeletonRows, Stat } from '@iaf/ui'
import { ErrorState, Section, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor } from '../../app/router'
import { OTHER_KEY, costApi } from '../../lib/cost'
import { useQuery } from '../../lib/queries'
import { ESTIMATES, isRefused } from './common'
import { addDays, isoDay, money, monthLabel, monthStart, share } from './model'

/*
 * Console home's Cost and usage widget (WP 7.3): the month so far, the forecast for its end and
 * the three services that cost most, linking to the Cost Explorer, as the AWS console's home
 * does. One report of the month, grouped by service; a person who may not read cost here sees
 * no widget at all.
 */

const TOP = 3

export function HomeCostWidget() {
  const { account, session } = useConsole()
  const now = useNow(60_000)
  const today = isoDay(now)
  const from = monthStart(today)
  const to = addDays(today, 1)
  const report = useQuery(['cost', session.account.accountId, session.region, 'home', from, to], () => costApi.report({ from, to, granularity: 'month', groupBy: 'service', top: TOP }))
  if (isRefused(report.error)) return null
  const r = report.data
  const month = monthLabel(from)

  return (
    <Section
      title="Cost and usage"
      description={`${month} so far in ${account.name}. ${ESTIMATES}`}
      actions={
        <Link to={pathFor('cost')} className="text-caption font-medium text-accent transition-opacity hover:opacity-70">
          Cost Explorer
        </Link>
      }
    >
      {report.error ? (
        <ErrorState compact error={report.error} onRetry={() => void report.refetch()} title="Could not load the cost" />
      ) : !r ? (
        <SkeletonRows n={1} rowClassName="h-40" />
      ) : (
        <Card>
          <div className="grid grid-cols-2 gap-3">
            <Stat label="Month to date" value={money(r.total, r.currency)} />
            <Stat label="Forecast" value={r.forecast ? money(r.forecast.monthEnd, r.currency) : 'None yet'} hint={r.forecast ? `Likely ${money(r.forecast.low, r.currency)} to ${money(r.forecast.high, r.currency)}` : undefined} />
          </div>
          <h3 className="mb-2 mt-5 text-caption font-medium text-secondary">Top services</h3>
          {r.groups.filter((g) => g.key !== OTHER_KEY).length === 0 ? (
            <p className="text-sm text-secondary">Nothing has cost anything this month yet.</p>
          ) : (
            <ul className="space-y-3">
              {r.groups
                .filter((g) => g.key !== OTHER_KEY)
                .slice(0, TOP)
                .map((g) => (
                  <li key={g.key}>
                    <div className="flex items-baseline justify-between gap-3 text-sm">
                      <span className="truncate text-primary">{g.label}</span>
                      <span className="shrink-0 tabular-nums text-primary">{money(g.total, r.currency)}</span>
                    </div>
                    <ProgressBar value={r.total ? (g.total / r.total) * 100 : 0} max={100} size="sm" label={`${g.label}: ${share(g.total, r.total)} of the month so far`} className="mt-1" />
                  </li>
                ))}
            </ul>
          )}
        </Card>
      )}
    </Section>
  )
}
