import { useState } from 'react'
import { Card, EmptyState, Icon, LineChart, PageHeader, ProgressBar, Segmented, Skeleton, SkeletonText, Table, plural, useToast, type TableColumn } from '@iaf/ui'
import { ConfirmDialog, CopyableId, DotTag, ErrorState, Facts, Section, TextLink, When, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton, useRefusal } from '../../app/Refusal'
import { href, navigate, pathFor, type PageProps } from '../../app/router'
import { isApiError } from '../../lib/api'
import { explainLine } from '../../lib/plain'
import { costApi, type BudgetAlert, type BudgetStatus, type ConsoleBudget } from '../../lib/cost'
import { ALLOWED, adminOnly, type Decision } from '../../lib/permissions'
import { invalidate, useQuery } from '../../lib/queries'
import { ADMIN_ROLE } from '../../lib/session'
import { BUDGETS_KEY, useBudgetAlerts } from './alerts'
import { BudgetDrawer } from './BudgetDrawer'
import { EstimatesNote, RefusedState, isRefused } from './common'
import { BUDGET_STATE, DEFAULT_EXPLORER, addDays, alertText, dayLabel, explorerQueryOf, isoDay, longDayLabel, money, moneyTick, monthLabel, monthStart, scopeText, thresholdsText } from './model'

/*
 * Budgets (WP 9.3): a monthly amount for the session's account or one tag within it, measured
 * against the same ledger as the Cost Explorer, forecast to the month's end, alerting as spending
 * crosses each threshold and, when asked, refusing creates in its scope while it is over. The
 * list shows each budget's month so far; a budget's page shows its month day by day against the
 * amount, the forecast, the alerts sent and how it did in the months before. Creating a budget is
 * the account's admins' (the console greys it for everyone else, and the BFF's answer wins);
 * changing and deleting follow what the BFF says each budget allows (`can`). Alerts arrive live.
 */

/** Says a live alert, and lets the budgets refresh behind it. */
function useAlertToasts() {
  const toast = useToast()
  useBudgetAlerts((a: BudgetAlert) => toast.toast({ title: 'Budget alert', message: alertText(a), tone: a.threshold >= 100 ? 'critical' : 'warning' }))
}

/** A per-budget decision from the BFF's `can`, with a refusal to explain a locked button. */
function canDo(allowed: boolean, action: string, budget: ConsoleBudget, roles: readonly string[]): Decision {
  if (allowed) return ALLOWED
  return {
    allowed: false,
    denied: {
      layer: 'console-iam',
      action,
      resource: `budget/${budget.budgetId}`,
      reason: `Your role does not allow ${action} on the budget ${budget.name}`,
      role: roles.length ? [...roles].sort().join(', ') : undefined,
      requestable: true,
      suggestedRole: ADMIN_ROLE,
    },
  }
}

function StateTag({ status, size }: { status: BudgetStatus; size?: 'sm' | 'md' }) {
  const s = BUDGET_STATE[status.state]
  return (
    <DotTag tone={s.tone} size={size}>
      {s.label}
    </DotTag>
  )
}

/** Spent against the amount: the sum, its percent, and a bar in the state's tone. */
function SpentBar({ budget }: { budget: ConsoleBudget }) {
  const { status } = budget
  return (
    <div className="min-w-[10rem]">
      <div className="flex items-baseline justify-between gap-3">
        <span className="tabular-nums text-primary">{money(status.spent, budget.currency)}</span>
        <span className="text-caption tabular-nums text-secondary">{status.percent}%</span>
      </div>
      <ProgressBar value={Math.min(100, status.percent)} max={100} tone={BUDGET_STATE[status.state].bar} size="sm" label={`${budget.name}: ${status.percent}% of the amount spent`} className="mt-1.5" />
    </div>
  )
}

/** "80%, 100%", or None. */
const sentText = (sent: readonly number[]) => (sent.length ? sent.map((t) => `${t}%`).join(', ') : <span className="text-tertiary">None</span>)

/* ---------------------------------------------------------------- the list */

export default function BudgetsPage(_props: PageProps) {
  const { account, me } = useConsole()
  const [creating, setCreating] = useState(false)
  const budgets = useQuery([BUDGETS_KEY, account.id, 'list'], () => costApi.budgets.list())
  useAlertToasts()
  const rows = budgets.data?.items ?? []
  const over = rows.filter((b) => b.status.state === 'over').length
  const forecastOver = rows.filter((b) => b.status.state === 'forecast-over').length
  const create = adminOnly(me.roles ?? [], 'budgets:create', 'Create budget')

  const columns: TableColumn<ConsoleBudget>[] = [
    {
      key: 'name',
      header: 'Name',
      sortable: true,
      accessor: (b) => b.name,
      render: (b) => (
        <span className="block min-w-0">
          <TextLink to={pathFor('budget', { id: b.budgetId })} className="block truncate font-medium">
            {b.name}
          </TextLink>
          <span className="block truncate text-caption text-tertiary">{scopeText(b.scope, account.name)}</span>
        </span>
      ),
    },
    { key: 'amount', header: 'Amount', numeric: true, sortable: true, accessor: (b) => b.amount, render: (b) => money(b.amount, b.currency) },
    { key: 'spent', header: 'Spent this month', sortable: true, accessor: (b) => b.status.percent, render: (b) => <SpentBar budget={b} /> },
    { key: 'forecast', header: 'Forecast', numeric: true, sortable: true, accessor: (b) => b.status.forecast, render: (b) => money(b.status.forecast, b.currency) },
    { key: 'state', header: 'State', sortable: true, accessor: (b) => ['ok', 'forecast-over', 'over'].indexOf(b.status.state), render: (b) => <StateTag status={b.status} size="sm" /> },
    { key: 'alerts', header: 'Alerts sent', accessor: (b) => b.status.alertsSent.length, render: (b) => sentText(b.status.alertsSent) },
    {
      key: 'block',
      header: 'Blocks creates',
      accessor: (b) => (b.blockCreates ? 1 : 0),
      render: (b) =>
        b.blockCreates ? (
          <span className="inline-flex items-center gap-1">
            <Icon name="lock" size={12} className="text-tertiary" />
            Yes
          </span>
        ) : (
          <span className="text-tertiary">No</span>
        ),
    },
  ]

  return (
    <>
      <PageHeader
        title="Budgets"
        subtitle={`A monthly amount for ${account.name} or a tag within it, alerting as spending crosses each threshold.`}
        actions={
          <ActionButton decision={create} what="Create budget" variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Create budget
          </ActionButton>
        }
      />
      {budgets.error && isRefused(budgets.error) ? (
        <RefusedState error={budgets.error} what="Read budgets" title={`You cannot read the budgets of ${account.name}`} />
      ) : budgets.error ? (
        <ErrorState error={budgets.error} title="Could not load the budgets" onRetry={() => void budgets.refetch()} />
      ) : (
        <div className="space-y-3">
          {rows.length > 0 && (
            <p className="text-sm text-secondary" role="status">
              {plural(rows.length, 'budget')}
              {over ? `, ${over} over` : ''}
              {forecastOver ? `, ${forecastOver} forecast to go over` : ''}
              {!over && !forecastOver ? ', all on course' : ''}.
            </p>
          )}
          <Table<ConsoleBudget>
            caption={`Budgets of ${account.name}`}
            columns={columns}
            rows={rows}
            rowKey={(b) => b.budgetId}
            loading={budgets.loading}
            defaultSort={{ key: 'name', dir: 'asc' }}
            onRowClick={(b) => navigate(pathFor('budget', { id: b.budgetId }))}
            emptyIcon="bell"
            emptyTitle="No budgets yet"
            emptyMessage={create.allowed ? 'Create one to hear when spending crosses an amount you set.' : 'An admin of the account creates them.'}
          />
          <EstimatesNote>Spent and forecast are this month's, measured like the Cost Explorer.</EstimatesNote>
        </div>
      )}
      {creating && <BudgetDrawer onClose={() => setCreating(false)} />}
    </>
  )
}

/* ---------------------------------------------------------------- one budget */

export function BudgetPage({ route }: PageProps) {
  const { account, me } = useConsole()
  const refusal = useRefusal()
  const toast = useToast()
  const now = useNow(60_000)
  const today = isoDay(now)
  const id = route.params.id ?? ''
  const budget = useQuery([BUDGETS_KEY, account.id, id], () => costApi.budgets.get(id))
  const periods = useQuery([BUDGETS_KEY, account.id, id, 'periods'], () => costApi.budgets.periods(id), { enabled: !!budget.data })
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  useAlertToasts()
  const back = { href: href(pathFor('budgets')), label: 'Budgets' }
  const b = budget.data

  if (budget.error) {
    return (
      <>
        <PageHeader back={back} title={id} />
        {isRefused(budget.error) ? (
          <RefusedState error={budget.error} what="Read budgets" title="You cannot read this budget" />
        ) : isApiError(budget.error) && budget.error.notFound ? (
          <EmptyState icon="search" title="No such budget" message="It may have been deleted, or it belongs to another account." action={<TextLink to={pathFor('budgets')}>See the budgets</TextLink>} />
        ) : (
          <ErrorState error={budget.error} title="Could not load this budget" onRetry={() => void budget.refetch()} />
        )}
      </>
    )
  }
  if (!b) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-48" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const roles = me.roles ?? []
  const s = b.status
  const remove = async () => {
    try {
      await costApi.budgets.remove(b.budgetId, b.version)
      invalidate(BUDGETS_KEY)
      toast.success('Budget deleted', `${b.name} no longer alerts or blocks.`)
      navigate(pathFor('budgets'), { replace: true })
    } catch (e) {
      if (refusal.showError(e, 'Delete budget')) return
      if (isApiError(e) && e.status === 409) {
        invalidate(BUDGETS_KEY)
        throw new Error('Someone changed this budget first. Its latest version is loading; look it over, then delete it again.', { cause: e })
      }
      throw new Error(explainLine(e, 'Delete budget'), { cause: e })
    }
  }
  const explorerLink = pathFor('cost', {}, explorerQueryOf({ ...DEFAULT_EXPLORER, tag: b.scope.tag }))

  return (
    <>
      <PageHeader
        back={back}
        title={b.name}
        subtitle={scopeText(b.scope, account.name)}
        actions={
          <>
            <ActionButton decision={canDo(b.can.update, 'budgets:update', b, roles)} what="Edit budget" icon="edit" onClick={() => setEditing(true)}>
              Edit
            </ActionButton>
            <ActionButton decision={canDo(b.can.delete, 'budgets:delete', b, roles)} what="Delete budget" icon="trash" onClick={() => setDeleting(true)}>
              Delete
            </ActionButton>
          </>
        }
      />

      <div className="space-y-8">
        <Card>
          <div className="mb-5 flex flex-wrap items-center gap-3">
            <StateTag status={s} />
            <p className="text-sm text-secondary">{BUDGET_STATE[s.state].sentence}</p>
          </div>
          <Facts
            columns={4}
            items={[
              { label: 'Amount', value: `${money(s.amount, b.currency)} a month` },
              { label: `Spent in ${monthLabel(s.period)}`, value: `${money(s.spent, b.currency)}, ${s.percent}%` },
              { label: 'Forecast', value: `${money(s.forecast, b.currency)}${s.forecastPercent !== undefined ? `, ${s.forecastPercent}%` : ''}` },
              { label: 'Alerts sent', value: s.alertsSent.length ? thresholdsText(s.alertsSent) : <span className="text-tertiary">None yet</span> },
              { label: 'Thresholds', value: thresholdsText(b.thresholds) },
              { label: 'Blocks creates', value: b.blockCreates ? 'Yes, while over' : 'No' },
              {
                label: 'Created',
                value: (
                  <span>
                    <When at={b.createdAt} now={now} />
                    {b.createdBy ? ` by ${b.createdBy}` : ''}
                  </span>
                ),
              },
              { label: 'Budget id', value: <CopyableId id={b.budgetId} /> },
            ]}
          />
        </Card>

        <SpendSection budget={b} today={today} />

        <Section title="Month by month" description="The amount in force each month, what was spent and the alerts it sent.">
          <PeriodsTable periods={periods.data?.items ?? []} loading={periods.loading} error={periods.error} currency={b.currency} today={today} onRetry={() => void periods.refetch()} />
        </Section>

        <Section title="What counts">
          <p className="max-w-3xl text-sm text-secondary">
            {b.scope.tag ? (
              <>
                The resources of {account.name} tagged <span className="font-mono text-primary">{`${b.scope.tag.key} = ${b.scope.tag.value}`}</span> count, priced as the Cost Explorer prices them; a resource without the tag does not.
              </>
            ) : (
              <>Every resource of {account.name} counts, priced as the Cost Explorer prices them.</>
            )}{' '}
            A resource with no price counts as nothing.{' '}
            <TextLink to={explorerLink} className="underline underline-offset-2">
              See this month in the Cost Explorer
            </TextLink>
          </p>
          <EstimatesNote className="mt-2" />
        </Section>
      </div>

      {editing && <BudgetDrawer budget={b} onClose={() => setEditing(false)} />}
      {deleting && (
        <ConfirmDialog
          title={`Delete ${b.name}?`}
          tone="danger"
          icon="trash"
          confirmLabel="Delete budget"
          onClose={() => setDeleting(false)}
          onConfirm={remove}
          message={b.blockCreates ? 'It stops alerting at once, and creates it blocks go ahead. Its months stay in the cost service; the budget cannot be brought back.' : 'It stops alerting at once. The budget cannot be brought back.'}
        />
      )}
    </>
  )
}

/** The month so far, day by day, against the amount, with the forecast to the month's end. */
function SpendSection({ budget, today }: { budget: ConsoleBudget; today: string }) {
  const { account, session } = useConsole()
  const [view, setView] = useState<'chart' | 'table'>('chart')
  const from = monthStart(today)
  const to = addDays(today, 1)
  const end = monthStart(today, 1)
  const tags = budget.scope.tag ? { [budget.scope.tag.key]: budget.scope.tag.value } : undefined
  const report = useQuery([BUDGETS_KEY, account.id, budget.budgetId, 'spend', session.region, from, to], () => costApi.report({ from, to, granularity: 'day', tags }))
  const s = budget.status
  const fmt = (v: number) => money(v, budget.currency)

  const days: string[] = []
  for (let d = from; d < end; d = addDays(d, 1)) days.push(d)
  const daily = new Map((report.data?.series ?? []).map((p) => [p.start, p.total]))
  let running = 0
  const spent = days.map((d) => {
    if (d > today) return Number.NaN
    running = Math.round((running + (daily.get(d) ?? 0)) * 100) / 100
    return running
  })
  const at = days.indexOf(today)
  const last = days.length - 1
  const forecast = days.map((_, i) => (at < 0 || i < at ? Number.NaN : at === last ? s.forecast : spent[at] + ((s.forecast - spent[at]) * (i - at)) / (last - at)))
  const amount = days.map(() => s.amount)

  return (
    <Section
      title={`${monthLabel(s.period)} so far`}
      description="Spent by the end of each day, the forecast to the month's end, and the amount."
      actions={
        <Segmented<'chart' | 'table'>
          size="sm"
          value={view}
          onChange={setView}
          ariaLabel={`View: ${view === 'table' ? 'Table' : 'Chart'}`}
          options={[
            { value: 'chart', label: 'Chart', icon: 'chart' },
            { value: 'table', label: 'Table', icon: 'list' },
          ]}
        />
      }
    >
      <Card>
        {report.error ? (
          isRefused(report.error) ? (
            <RefusedState error={report.error} what="Read cost and usage" title="You cannot read the cost behind this budget" compact />
          ) : (
            <ErrorState error={report.error} title="Could not load the month's spend" onRetry={() => void report.refetch()} compact />
          )
        ) : !report.data ? (
          <Skeleton className="h-[220px] rounded-md" />
        ) : view === 'chart' ? (
          <LineChart
            height={220}
            labels={days.map(dayLabel)}
            yFormat={(v) => moneyTick(v, budget.currency)}
            tooltip={(i) => (
              <div className="space-y-0.5">
                <div className="text-caption2 text-tertiary">{longDayLabel(days[i])}</div>
                {Number.isFinite(spent[i]) && <TipRow value={fmt(spent[i])} label="spent so far" />}
                {Number.isFinite(forecast[i]) && i > at && <TipRow value={fmt(forecast[i])} label="forecast" />}
                <TipRow value={fmt(s.amount)} label="amount" />
              </div>
            )}
            ariaLabel={`Spent so far in ${monthLabel(s.period)}: ${fmt(s.spent)} of ${fmt(s.amount)}, forecast ${fmt(s.forecast)} by the month's end`}
            series={[
              { id: 'spent', label: 'Spent so far', values: spent },
              { id: 'amount', label: 'Amount', values: amount },
              { id: 'forecast', label: 'Forecast', values: forecast },
            ]}
          />
        ) : (
          <Table<{ day: string; spent: number; total: number }>
            caption={`Spend by day in ${monthLabel(s.period)}`}
            dense
            columns={[
              { key: 'day', header: 'Day', sortable: true, accessor: (r) => r.day, render: (r) => <span title={longDayLabel(r.day)}>{dayLabel(r.day)}</span> },
              { key: 'spent', header: 'That day', numeric: true, accessor: (r) => r.spent, render: (r) => fmt(r.spent) },
              { key: 'total', header: 'So far', numeric: true, accessor: (r) => r.total, render: (r) => fmt(r.total) },
            ]}
            rows={days.filter((d) => d <= today).map((d, i) => ({ day: d, spent: daily.get(d) ?? 0, total: spent[i] }))}
            rowKey={(r) => r.day}
            defaultSort={{ key: 'day', dir: 'asc' }}
          />
        )}
      </Card>
    </Section>
  )
}

/** A tooltip's row: the value first, strong, then what it is. */
function TipRow({ value, label }: { value: string; label: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className="font-medium tabular-nums text-primary">{value}</span>
      <span className="text-secondary">{label}</span>
    </div>
  )
}

function PeriodsTable({ periods, loading, error, currency, today, onRetry }: { periods: BudgetStatus[]; loading: boolean; error: unknown; currency: string; today: string; onRetry: () => void }) {
  if (error) return <ErrorState error={error} title="Could not load the months" onRetry={onRetry} compact />
  const current = today.slice(0, 7)
  const columns: TableColumn<BudgetStatus>[] = [
    { key: 'period', header: 'Month', sortable: true, accessor: (p) => p.period, render: (p) => (p.period === current ? `${monthLabel(p.period)} (so far)` : monthLabel(p.period)) },
    { key: 'amount', header: 'Amount', numeric: true, accessor: (p) => p.amount, render: (p) => money(p.amount, currency) },
    { key: 'spent', header: 'Spent', numeric: true, accessor: (p) => p.spent, render: (p) => money(p.spent, currency) },
    { key: 'percent', header: 'Of the amount', numeric: true, accessor: (p) => p.percent, render: (p) => `${p.percent}%` },
    { key: 'state', header: 'State', accessor: (p) => p.state, render: (p) => <StateTag status={p} size="sm" /> },
    { key: 'alerts', header: 'Alerts sent', accessor: (p) => p.alertsSent.length, render: (p) => sentText(p.alertsSent) },
  ]
  return <Table<BudgetStatus> caption="The budget month by month" columns={columns} rows={periods} rowKey={(p) => p.period} loading={loading} defaultSort={{ key: 'period', dir: 'desc' }} emptyIcon="calendar" emptyTitle="No months yet" />
}
