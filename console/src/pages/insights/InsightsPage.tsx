import { useState } from 'react'
import { Button, EmptyState, PageHeader, Segmented, cx } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { useRefusal } from '../../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { costApi } from '../../lib/cost'
import { INSIGHTS_PERIODS, insightsApi, insightsKey, type InsightsPeriod, type InsightsScope } from '../../lib/insights'
import { denialOf, type Denial } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { AdoptionCard, HeadlineStats, InsightsSkeleton, ServicesCard, SpendCard, type Spend } from './parts'
import { PERIOD_LABEL, SCOPE_LABEL, asOfWords, leftOut, listWords, viewOf, viewWords, type InsightsView } from './words'

/*
 * The executive home (WP 9.5, #/insights): what self-service did for the person's accounts over
 * a period. The BFF's numbers (GET /console/v1/insights) give the headline, adoption week by week
 * and resources by service; spend by team is the cost service's report by cost centre over the
 * same days (GET /console/v1/cost?groupBy=costCentre, from and to read off the numbers' answer).
 * The period and the scope live in the query, so a view can be linked to. A change of view keeps
 * the last numbers on screen, dimmed, until the next arrive. Nothing here changes anything: a
 * viewer reads it as an admin does.
 */

const SCOPES: InsightsScope[] = ['account', 'organization']

/** The last answer while the next loads: a new period redraws in place instead of flashing the skeleton. */
function useHeld<T>(value: T | undefined): T | undefined {
  const [held, setHeld] = useState(value)
  if (value !== undefined && value !== held) setHeld(value)
  return value ?? held
}

export default function InsightsPage({ route }: PageProps) {
  const { session, account } = useConsole()
  const refusal = useRefusal()
  const view = viewOf(route.query)
  const userId = session.user.userId

  const numbers = useQuery(insightsKey(userId, account.id, view.period, view.scope), () => insightsApi.get(view))
  const memberships = useQuery(['accounts', userId], () => bff.accounts())
  // the same days as the numbers: their answer's from and to, which are whole UTC days
  const days = numbers.data ? { from: numbers.data.from.slice(0, 10), to: numbers.data.to.slice(0, 10) } : undefined
  const cost = useQuery(
    ['insights-spend', userId, account.id, view.scope, days?.from ?? '', days?.to ?? ''],
    () => costApi.report({ from: days!.from, to: days!.to, granularity: 'day', groupBy: 'costCentre', scope: view.scope }),
    { enabled: !!days },
  )

  const shown = useHeld(numbers.data)
  const denied = denialOf(numbers.error)
  const busy = !numbers.data && numbers.fetching
  // the last report stays only beside the last numbers; new numbers wait for their own spend
  const heldReport = useHeld(cost.data)
  const report = cost.data ?? (busy ? heldReport : undefined)
  const costDenied = denialOf(cost.error)
  const spend: Spend = costDenied ? { status: 'refused', denied: costDenied } : cost.error ? { status: 'failed', error: cost.error } : report ? { status: 'ready', report } : { status: 'loading' }

  const accounts = memberships.data?.items ?? []
  const teams = new Map<string, string | undefined>([...accounts.map((a) => [a.accountId, a.owningTeam] as const), ...(shown?.accounts ?? []).map((a) => [a.accountId, a.owningTeam] as const)])
  const nameOf = (id: string) => accounts.find((a) => a.accountId === id)?.name ?? shown?.accounts.find((a) => a.accountId === id)?.name ?? id
  const outside = shown && view.scope === 'organization' ? leftOut(accounts, shown.accounts) : []

  const go = (next: Partial<InsightsView>) => navigate(pathFor('insights', {}, { ...view, ...next }, { accountId: route.accountId }), { replace: true })
  const seeWhy = (d: Denial, what: string) => refusal.show({ denied: d, what })

  return (
    <>
      <PageHeader title="Executive home" subtitle="What self-service did for your teams: resources managed, requests self-served, lead time, drift fixed and spend." />
      <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-3">
        <Segmented<InsightsPeriod>
          size="sm"
          ariaLabel={`Period: ${PERIOD_LABEL[view.period]}`}
          value={view.period}
          onChange={(period) => go({ period })}
          options={INSIGHTS_PERIODS.map((p) => ({ value: p, label: PERIOD_LABEL[p] }))}
        />
        <Segmented<InsightsScope>
          size="sm"
          ariaLabel={`Scope: ${SCOPE_LABEL[view.scope]}`}
          value={view.scope}
          onChange={(scope) => go({ scope })}
          options={SCOPES.map((s) => ({ value: s, label: SCOPE_LABEL[s] }))}
        />
        {shown && !denied && (
          <p className="text-caption text-tertiary sm:ml-auto">
            As of <time dateTime={shown.asOf}>{asOfWords(shown.asOf)}</time>
          </p>
        )}
      </div>
      <p role="status" className="sr-only">
        {numbers.data ? `Showing ${viewWords(view, account.name)}.` : busy ? 'Loading the numbers.' : ''}
      </p>

      {denied ? (
        <EmptyState
          icon="lock"
          title="You can’t read the executive home here"
          message={denied.reason}
          action={
            <Button size="sm" variant="primary" onClick={() => seeWhy(denied, 'Read the executive home')}>
              See why
            </Button>
          }
        />
      ) : numbers.error && !numbers.data ? (
        <ErrorState error={numbers.error} title="Could not load the numbers" onRetry={numbers.refetch} />
      ) : !shown ? (
        <InsightsSkeleton />
      ) : (
        <div aria-busy={busy} className={cx('space-y-6 motion-safe:transition-opacity', busy && 'opacity-60')}>
          <p className="text-sm text-secondary">
            Covers {listWords(shown.accounts.map((a) => a.name))}.{outside.length > 0 && ` Left out: ${listWords(outside.map((a) => a.name))}, where your role may not read these numbers.`}
          </p>
          <HeadlineStats insights={shown} spend={spend} />
          <AdoptionCard insights={shown} />
          <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
            <ServicesCard insights={shown} />
            <SpendCard
              spend={spend}
              period={shown.period}
              teamOf={(id) => teams.get(id)}
              nameOf={nameOf}
              onRetry={() => void cost.refetch()}
              onSeeWhy={(d) => seeWhy(d, 'Read cost')}
              action={
                <Link to={pathFor('cost')} className="text-caption font-medium text-accent transition-opacity hover:opacity-70">
                  Cost and usage
                </Link>
              }
            />
          </div>
        </div>
      )}
    </>
  )
}
