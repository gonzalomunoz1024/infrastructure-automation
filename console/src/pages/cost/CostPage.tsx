import { useState, type FormEvent } from 'react'
import { Card, Icon, IconButton, Input, PageHeader, Segmented, Select, Skeleton, Stat, Table, Tag, plural, type SelectOption, type TableColumn } from '@iaf/ui'
import { ErrorState, Section, SeriesSwatch, StackedBarChart, When, WfrnText, useNow } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { navigate, pathFor, type PageProps } from '../../app/router'
import { OTHER_KEY, costApi, type CostGranularity, type CostGroup, type CostPoint, type CostReport, type CostScope } from '../../lib/cost'
import { downloadCsv, toCsv } from '../../lib/csv'
import { ALLOWED } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { Control, EstimatesNote, RefusedState, SkippedAccounts, isRefused } from './common'
import {
  GROUP_DIMENSIONS,
  NAMED_GROUPS,
  RANGE_PRESETS,
  TAG_KEYS,
  addDays,
  chartModelOf,
  costQueryOf,
  dayLabel,
  explorerCsvRows,
  explorerFileName,
  explorerQueryOf,
  explorerRange,
  explorerStateOf,
  groupLabel,
  groupNoun,
  isDay,
  isoDay,
  longDayLabel,
  money,
  moneyTick,
  monthLabel,
  namedKeys,
  parseTag,
  rangeLabel,
  share,
  slotOf,
  stickySlots,
  type DateRange,
  type ExplorerGroup,
  type ExplorerState,
  type RangePreset,
} from './model'

/*
 * The Cost Explorer (WP 7.3), as the AWS console draws its own: a date range with presets, by day
 * or by month, grouped by service, type, account, region, cost centre or a tag, filtered by
 * service, type and a tag, for the session's account or every account the person may read cost
 * in. The total, the month-end forecast with its likely range, the run rate and what has no price
 * sit above a stacked chart; under it the groups, and the chart's numbers as a table on request.
 * Everything the page shows is in its URL, so a view is a link, and the CSV is the table. The
 * amounts are the cost service's, estimated from the platform's price lists: never invoices.
 */

interface Kept {
  /** the last answer, kept on screen (dimmed) while the next one loads */
  report?: CostReport
  /** each grouping's colours, so a group keeps its hue while the filters change */
  slots: Record<string, Record<string, number>>
}

export default function CostPage({ route }: PageProps) {
  const { account, session, services, types } = useConsole()
  const now = useNow(60_000)
  const today = isoDay(now)
  const state = explorerStateOf(route.query)
  const range = explorerRange(state, today)
  const query = costQueryOf(state, today)
  const report = useQuery(['cost', session.account.accountId, session.region, query], () => costApi.report(query))

  // a new answer: kept for the next fetch's frame, its new groups given their colours
  const [kept, setKept] = useState<Kept>({ slots: {} })
  if (report.data && report.data !== kept.report) {
    const dim = report.data.groupBy ?? 'none'
    setKept({ report: report.data, slots: { ...kept.slots, [dim]: stickySlots(kept.slots[dim] ?? {}, namedKeys(report.data)) } })
  }
  const shown = report.data ?? kept.report
  const slots = kept.slots[shown?.groupBy ?? 'none'] ?? {}
  const model = shown ? chartModelOf(shown, slots) : null

  const set = (patch: Partial<ExplorerState>) => navigate(pathFor('cost', {}, explorerQueryOf({ ...state, ...patch })), { replace: true })
  const failed = report.error && !report.fetching ? report.error : null
  const currency = shown?.currency ?? 'USD'
  const fmt = (v: number) => money(v, currency)
  const by = (shown?.granularity ?? range.granularity) === 'month' ? 'Monthly' : 'Daily'
  const shownGroup: ExplorerGroup = (shown?.groupBy as ExplorerGroup | undefined) ?? 'none'
  const subject = shownGroup === 'none' ? 'cost' : `cost by ${groupNoun(shownGroup)}`

  const exportCsv = () => {
    if (shown && model) downloadCsv(explorerFileName(shown), toCsv(explorerCsvRows(shown, model)))
  }

  return (
    <>
      <PageHeader
        title="Cost and usage"
        subtitle={`What ${state.scope === 'organization' ? 'your accounts' : account.name} cost by the platform's price lists, by day or by month.`}
        actions={
          <ActionButton decision={ALLOWED} what="Download CSV" icon="download" onClick={exportCsv} disabled={!shown || !!failed}>
            Download CSV
          </ActionButton>
        }
      />

      <ExplorerFilters
        state={state}
        today={today}
        range={range}
        set={set}
        services={services.map((s) => ({ value: s.id, label: s.title }))}
        types={types.filter((t) => !state.service || t.service === state.service).map((t) => ({ value: t.name, label: t.title, hint: t.name }))}
        accountName={account.name}
      />

      {failed && isRefused(failed) ? (
        <RefusedState error={failed} what="Read cost and usage" title={state.scope === 'organization' ? 'You cannot read cost in these accounts' : `You cannot read cost in ${account.name}`} />
      ) : failed ? (
        <ErrorState error={failed} title="Could not load the cost" onRetry={() => void report.refetch()} />
      ) : !shown || !model ? (
        <ExplorerSkeleton />
      ) : (
        <div className="space-y-8">
          <div className="space-y-2">
            <CostStats report={shown} now={now} />
            <SkippedAccounts ids={shown.skippedAccounts} action="cost:list" />
          </div>

          <Section
            title={`${by} ${subject}`}
            description={rangeLabel(shown.from, shown.to)}
            actions={
              <Segmented<'chart' | 'table'>
                size="sm"
                value={state.view}
                onChange={(view) => set({ view })}
                ariaLabel={`View: ${state.view === 'table' ? 'Table' : 'Chart'}`}
                options={[
                  { value: 'chart', label: 'Chart', icon: 'chart' },
                  { value: 'table', label: 'Table', icon: 'list' },
                ]}
              />
            }
          >
            <Card>
              {state.view === 'table' ? (
                <PeriodTable report={shown} series={model.series} fmt={fmt} stale={report.fetching} />
              ) : (
                <StackedBarChart
                  series={model.series}
                  bars={model.bars}
                  format={fmt}
                  tickFormat={(v) => moneyTick(v, currency)}
                  label={`${by} ${subject}, ${rangeLabel(shown.from, shown.to)}`}
                  height={260}
                  stale={report.fetching}
                  provisionalLabel={shown.granularity === 'month' ? 'Provisional: the month is still open' : 'Provisional: the day is still open'}
                  emptyLabel="No cost in this range"
                />
              )}
              <Freshness report={shown} now={now} />
            </Card>
          </Section>

          {shown.groups.length > 0 && <GroupTable report={shown} groupBy={shownGroup} slots={slots} fmt={fmt} />}
        </div>
      )}
    </>
  )
}

/* ---------------------------------------------------------------- the filters */

interface FiltersProps {
  state: ExplorerState
  today: string
  range: DateRange
  set: (patch: Partial<ExplorerState>) => void
  services: SelectOption[]
  types: SelectOption[]
  accountName: string
}

/** One row of report parameters above everything they scope, the date range first. */
function ExplorerFilters({ state, today, range, set, services, types, accountName }: FiltersProps) {
  const preset = RANGE_PRESETS.find((p) => p.value === state.range) ?? RANGE_PRESETS[0]
  const groups: SelectOption[] = [...GROUP_DIMENSIONS, ...TAG_KEYS.map((k) => ({ value: `tag:${k}`, label: `Tag: ${k}` }))]
  if (!groups.some((g) => g.value === state.groupBy)) groups.push({ value: state.groupBy, label: groupLabel(state.groupBy) })
  const serviceOptions = [{ value: '', label: 'All services' }, ...services]
  if (state.service && !serviceOptions.some((o) => o.value === state.service)) serviceOptions.push({ value: state.service, label: state.service })
  const typeOptions = [{ value: '', label: 'All types' }, ...types]
  if (state.type && !typeOptions.some((o) => o.value === state.type)) typeOptions.push({ value: state.type, label: state.type })
  const optionLabel = (options: SelectOption[], value: string) => options.find((o) => o.value === value)?.label ?? value
  const lastDay = addDays(range.to, -1)

  // a preset brings its own granularity; a custom range starts from the days on screen
  const pickRange = (value: RangePreset) => set(value === 'custom' ? { range: value, from: range.from, to: lastDay, granularity: undefined } : { range: value, from: undefined, to: undefined, granularity: undefined })

  return (
    <div className="mb-8 space-y-3">
      <div className="flex flex-wrap items-start gap-x-3 gap-y-3" role="group" aria-label="Report parameters">
        <Control label="Date range">
          <Select size="sm" ariaLabel={`Date range: ${preset.label}`} value={preset.value} onChange={(v) => pickRange(v as RangePreset)} options={RANGE_PRESETS} />
        </Control>
        {state.range === 'custom' && (
          <>
            <Control label="From" htmlFor="cost-from">
              <Input id="cost-from" type="date" size="sm" value={state.from ?? range.from} max={today} onChange={(e) => isDay(e.target.value) && set({ from: e.target.value })} />
            </Control>
            <Control label="To" htmlFor="cost-to">
              <Input id="cost-to" type="date" size="sm" value={state.to ?? lastDay} max={today} onChange={(e) => isDay(e.target.value) && set({ to: e.target.value })} />
            </Control>
          </>
        )}
        <Control label="Granularity">
          <Segmented<CostGranularity>
            size="sm"
            value={range.granularity}
            onChange={(granularity) => set({ granularity })}
            ariaLabel={`Granularity: ${range.granularity === 'month' ? 'Monthly' : 'Daily'}`}
            options={[
              { value: 'day', label: 'Daily' },
              { value: 'month', label: 'Monthly' },
            ]}
          />
        </Control>
        <Control label="Group by">
          <Select size="sm" ariaLabel={`Group by: ${groupLabel(state.groupBy)}`} value={state.groupBy} onChange={(v) => set({ groupBy: v as ExplorerGroup })} options={groups} />
        </Control>
        <Control label="Service">
          <Select size="sm" ariaLabel={`Service: ${optionLabel(serviceOptions, state.service ?? '')}`} value={state.service ?? ''} onChange={(v) => set({ service: v || undefined, type: undefined })} options={serviceOptions} />
        </Control>
        <Control label="Resource type">
          <Select size="sm" ariaLabel={`Resource type: ${optionLabel(typeOptions, state.type ?? '')}`} value={state.type ?? ''} onChange={(v) => set({ type: v || undefined })} options={typeOptions} />
        </Control>
        <Control label="Tag" htmlFor="cost-tag">
          <TagFilter key={state.tag ? `${state.tag.key}=${state.tag.value}` : ''} value={state.tag} onChange={(tag) => set({ tag })} />
        </Control>
        <Control label="Scope">
          <Segmented<CostScope>
            size="sm"
            value={state.scope}
            onChange={(scope) => set({ scope })}
            ariaLabel={`Scope: ${state.scope === 'organization' ? 'Organization' : accountName}`}
            options={[
              { value: 'account', label: accountName },
              { value: 'organization', label: 'Organization' },
            ]}
          />
        </Control>
      </div>
      {state.wfrn && (
        <div className="flex min-w-0 flex-wrap items-center gap-2 text-caption text-secondary">
          <span>One resource:</span>
          <WfrnText wfrn={state.wfrn} max={48} copy={false} />
          <IconButton label="Show every resource" size="sm" onClick={() => set({ wfrn: undefined })}>
            <Icon name="close" size={13} />
          </IconButton>
        </div>
      )}
    </div>
  )
}

/** A tag filter typed as key=value: Enter or leaving the field applies it, an empty field clears it. */
function TagFilter({ value, onChange }: { value?: { key: string; value: string }; onChange: (tag: { key: string; value: string } | undefined) => void }) {
  const [text, setText] = useState(value ? `${value.key}=${value.value}` : '')
  const [problem, setProblem] = useState(false)
  const apply = () => {
    const trimmed = text.trim()
    if (!trimmed) {
      setProblem(false)
      if (value) onChange(undefined)
      return
    }
    const tag = parseTag(trimmed)
    setProblem(!tag)
    if (tag && (tag.key !== value?.key || tag.value !== value?.value)) onChange(tag)
  }
  const submit = (e: FormEvent) => {
    e.preventDefault()
    apply()
  }
  return (
    <form onSubmit={submit} className="w-44">
      <Input id="cost-tag" size="sm" mono value={text} placeholder="key=value" invalid={problem} aria-describedby={problem ? 'cost-tag-problem' : undefined} onChange={(e) => setText(e.target.value)} onBlur={apply} />
      {problem && (
        <p id="cost-tag-problem" className="mt-1 text-caption2 text-critical" role="alert">
          Write a tag as key=value.
        </p>
      )}
    </form>
  )
}

/* ---------------------------------------------------------------- the figures */

function CostStats({ report, now }: { report: CostReport; now: number }) {
  const fmt = (v: number) => money(v, report.currency)
  const f = report.forecast
  return (
    <>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Total" value={fmt(report.total)} hint={rangeLabel(report.from, report.to)} />
        {f ? <Stat label={`Forecast for ${monthLabel(f.month ?? isoDay(now))}`} value={fmt(f.monthEnd)} hint={`Likely ${fmt(f.low)} to ${fmt(f.high)}`} /> : <Stat label="Forecast" value="None" hint="Only a range that holds today has one" />}
        <Stat label="Monthly run rate" value={fmt(report.runRate.monthly)} hint={report.runRate.resources !== undefined ? `${plural(report.runRate.resources, 'priced resource')} at today's prices` : "At today's prices"} />
        <Stat label="Not priced" value={plural(report.unpriced.resources, 'resource')} hint={report.unpriced.types?.length ? `No price for ${report.unpriced.types.join(', ')}` : 'Every resource has a price'} />
      </div>
      <EstimatesNote>
        {f ? 'The forecast is the month so far plus the run rate for the days left; its range comes from the last 14 closed days.' : null}
        {report.unpriced.resources ? ' A resource with no price counts as nothing here.' : null}
      </EstimatesNote>
    </>
  )
}

/** When the numbers were read, and which days are closed. */
function Freshness({ report, now }: { report: CostReport; now: number }) {
  const open = report.series.some((p) => p.provisional)
  return (
    <p className="mt-4 text-caption text-secondary">
      Updated <When at={report.asOf} now={now} />
      {report.closedThrough && <>. Closed through {dayLabel(report.closedThrough)}</>}
      {open && <>; {report.granularity === 'month' ? 'this month is' : 'today is'} provisional, drawn fainter</>}.
    </p>
  )
}

function ExplorerSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-[76px] rounded-md" />
        ))}
      </div>
      <Skeleton className="h-[340px] rounded-lg" />
    </div>
  )
}

/* ---------------------------------------------------------------- the tables */

/** The range's groups, each with its swatch, its total and its share. */
function GroupTable({ report, groupBy, slots, fmt }: { report: CostReport; groupBy: ExplorerGroup; slots: Record<string, number>; fmt: (v: number) => string }) {
  const columns: TableColumn<CostGroup>[] = [
    {
      key: 'label',
      header: groupLabel(groupBy),
      sortable: true,
      accessor: (g) => g.label,
      render: (g) => (
        <span className="inline-flex min-w-0 items-center gap-2">
          <SeriesSwatch slot={slotOf(g.key, slots)} />
          <span className="truncate">{g.label}</span>
        </span>
      ),
    },
    { key: 'total', header: 'Cost', numeric: true, sortable: true, accessor: (g) => g.total, render: (g) => fmt(g.total) },
    { key: 'share', header: 'Share', numeric: true, accessor: (g) => g.total, render: (g) => share(g.total, report.total) },
  ]
  return (
    <Section title={`By ${groupNoun(groupBy)}`} description={report.groups.some((g) => g.key === OTHER_KEY) ? `The ${NAMED_GROUPS} largest by name, the rest as Other.` : undefined}>
      <Table<CostGroup>
        caption={`Cost by ${groupNoun(groupBy)}, ${rangeLabel(report.from, report.to)}`}
        columns={columns}
        rows={report.groups}
        rowKey={(g) => g.key}
        defaultSort={{ key: 'total', dir: 'desc' }}
        footer={
          <span className="flex w-full justify-between gap-3 text-primary">
            <span>Total</span>
            <span className="font-medium tabular-nums">{fmt(report.total)}</span>
          </span>
        }
      />
    </Section>
  )
}

/** The chart as a table: a row per period, a column per group, the total last. */
function PeriodTable({ report, series, fmt, stale }: { report: CostReport; series: { key: string; label: string }[]; fmt: (v: number) => string; stale: boolean }) {
  const byMonth = report.granularity === 'month'
  const columns: TableColumn<CostPoint>[] = [
    {
      key: 'start',
      header: byMonth ? 'Month' : 'Day',
      sortable: true,
      accessor: (p) => p.start,
      render: (p) => (
        <span className="inline-flex items-center gap-2 whitespace-nowrap">
          <span title={byMonth ? undefined : longDayLabel(p.start)}>{byMonth ? monthLabel(p.start) : dayLabel(p.start)}</span>
          {p.provisional && (
            <Tag size="sm" outline>
              provisional
            </Tag>
          )}
        </span>
      ),
    },
    ...(report.groups.length
      ? series.map(
          (s): TableColumn<CostPoint> => ({
            key: `g-${s.key}`,
            header: s.label,
            numeric: true,
            accessor: (p) => p.groups[s.key] ?? 0,
            render: (p) => fmt(p.groups[s.key] ?? 0),
          }),
        )
      : []),
    { key: 'total', header: 'Total', numeric: true, sortable: true, accessor: (p) => p.total, render: (p) => <span className="font-medium">{fmt(p.total)}</span> },
  ]
  return (
    <div className={stale ? 'opacity-60 motion-safe:transition-opacity' : 'motion-safe:transition-opacity'} aria-busy={stale || undefined}>
      <Table<CostPoint>
        caption={`${byMonth ? 'Monthly' : 'Daily'} cost, ${rangeLabel(report.from, report.to)}`}
        columns={columns}
        rows={report.series}
        rowKey={(p) => p.start}
        defaultSort={{ key: 'start', dir: 'asc' }}
        dense
        footer={
          <span className="flex w-full justify-between gap-3 text-primary">
            <span>Total</span>
            <span className="font-medium tabular-nums">{fmt(report.total)}</span>
          </span>
        }
      />
    </div>
  )
}
