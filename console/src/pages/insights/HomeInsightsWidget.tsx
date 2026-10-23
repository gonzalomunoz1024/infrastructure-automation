import { Card, Icon, SkeletonRows } from '@iaf/ui'
import { ErrorState, Section } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor } from '../../app/router'
import { isApiError } from '../../lib/api'
import { insightsApi, insightsKey, type InsightsPeriod, type InsightsScope } from '../../lib/insights'
import { useQuery } from '../../lib/queries'
import { headlineOf } from './words'

/*
 * The console home's card from the executive home (WP 9.5): three numbers for the last 30 days in
 * the session's account (resources managed, the share of requests self-served, drift fixed) and
 * a link to the same view on the executive home, which reads the same answer from the cache. A
 * person who may not read the numbers sees nothing at all: the home has no hole where it was.
 */

const PERIOD: InsightsPeriod = '30d'
const SCOPE: InsightsScope = 'account'

export function HomeInsightsWidget() {
  const { session, account } = useConsole()
  const numbers = useQuery(insightsKey(session.user.userId, account.id, PERIOD, SCOPE), () => insightsApi.get({ period: PERIOD, scope: SCOPE }))
  if (isApiError(numbers.error) && numbers.error.status === 403) return null

  const h = numbers.data && headlineOf(numbers.data)
  const figures = h && [
    { label: 'Resources managed', value: h.managed.value, hint: h.managed.hint },
    { label: 'Self-served', value: h.selfServed.value, hint: h.selfServed.hint },
    { label: 'Drift fixed', value: h.drift.value, hint: h.drift.openWords },
  ]

  return (
    <Section
      title="Last 30 days"
      description={`What self-service did in ${account.name}.`}
      actions={
        <Link to={pathFor('insights', {}, { period: PERIOD, scope: SCOPE })} className="inline-flex items-center gap-1 text-caption font-medium text-accent transition-opacity hover:opacity-70">
          Executive home
          <Icon name="chevron-right" size={12} />
        </Link>
      }
    >
      <Card>
        {figures ? (
          <dl className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            {figures.map((f) => (
              <div key={f.label} className="min-w-0">
                <dt className="text-caption text-tertiary">{f.label}</dt>
                <dd className="mt-0.5 text-title3 font-semibold tabular-nums text-primary">{f.value}</dd>
                <dd className="text-caption text-tertiary">{f.hint}</dd>
              </div>
            ))}
          </dl>
        ) : numbers.error ? (
          <ErrorState compact error={numbers.error} title="Could not load the numbers" onRetry={numbers.refetch} />
        ) : (
          <SkeletonRows n={1} rowClassName="h-14" />
        )}
      </Card>
    </Section>
  )
}
