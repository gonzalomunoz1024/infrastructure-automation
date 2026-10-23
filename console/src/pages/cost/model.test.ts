import { describe, expect, it } from 'vitest'
import { OTHER_KEY, type ChargebackRow, type CostReport } from '../../lib/cost'
import {
  DEFAULT_EXPLORER,
  alertText,
  chargebackFileName,
  chargebackGroupsOf,
  chargebackMonths,
  chartModelOf,
  costQueryOf,
  explorerCsvRows,
  explorerFileName,
  explorerQueryOf,
  explorerRange,
  explorerStateOf,
  groupLabel,
  money,
  moneyTick,
  parseAmount,
  parseTag,
  rangeLabel,
  rangeOf,
  scopeText,
  sha256Hex,
  share,
  shortMonthLabel,
  stickySlots,
  thresholdProblem,
  thresholdsText,
  type ExplorerState,
} from './model'

const TODAY = '2026-10-23'

describe('money', () => {
  it('is the currency with two decimals, never a negative zero', () => {
    expect(money(14365.71)).toBe('$14,365.71')
    expect(money(0.5)).toBe('$0.50')
    expect(money(-0)).toBe('$0.00')
    expect(money(1234, 'EUR')).toBe('€1,234.00')
  })

  it('is compact on an axis, from a thousand up', () => {
    expect([0, 500, 1500, 12_000, 2_500_000].map((v) => moneyTick(v))).toEqual(['$0', '$500', '$1.5K', '$12K', '$2.5M'])
  })

  it('gives a share to one decimal, and nothing of nothing', () => {
    expect(share(25, 100)).toBe('25%')
    expect(share(1, 3)).toBe('33.3%')
    expect(share(5, 0)).toBe('0%')
  })
})

describe('the date presets', () => {
  it('run to tomorrow when they hold today, in UTC days', () => {
    expect(rangeOf('mtd', TODAY)).toEqual({ from: '2026-10-01', to: '2026-10-24', granularity: 'day' })
    expect(rangeOf('last-month', TODAY)).toEqual({ from: '2026-09-01', to: '2026-10-01', granularity: 'day' })
    expect(rangeOf('30d', TODAY)).toEqual({ from: '2026-09-24', to: '2026-10-24', granularity: 'day' })
    expect(rangeOf('3m', TODAY)).toEqual({ from: '2026-08-01', to: '2026-10-24', granularity: 'month' })
    expect(rangeOf('12m', TODAY)).toEqual({ from: '2025-11-01', to: '2026-10-24', granularity: 'month' })
  })

  it('turn the year', () => {
    expect(rangeOf('last-month', '2026-01-15')).toEqual({ from: '2025-12-01', to: '2026-01-01', granularity: 'day' })
    expect(rangeOf('3m', '2026-01-15').from).toBe('2025-11-01')
    expect(rangeOf('30d', '2026-03-01').from).toBe('2026-01-31')
  })

  it('take a custom range with its last day included, clipped to today, by month past two months', () => {
    expect(rangeOf('custom', TODAY, { from: '2026-10-05', to: '2026-10-10' })).toEqual({ from: '2026-10-05', to: '2026-10-11', granularity: 'day' })
    expect(rangeOf('custom', TODAY, { from: '2026-10-20', to: '2026-12-31' })).toEqual({ from: '2026-10-20', to: '2026-10-24', granularity: 'day' })
    expect(rangeOf('custom', TODAY, { from: '2026-01-01', to: '2026-06-30' }).granularity).toBe('month')
    // backwards, unreal, or in the future: month to date
    expect(rangeOf('custom', TODAY, { from: '2026-10-10', to: '2026-10-05' })).toEqual(rangeOf('mtd', TODAY))
    expect(rangeOf('custom', TODAY, { from: '2026-02-30', to: '2026-03-02' })).toEqual(rangeOf('mtd', TODAY))
    expect(rangeOf('custom', TODAY, { from: '2026-11-01', to: '2026-11-02' })).toEqual(rangeOf('mtd', TODAY))
  })

  it('say a range in words, its last day included', () => {
    expect(rangeLabel('2026-10-01', '2026-10-24')).toBe('Oct 1 to Oct 23, 2026')
    expect(rangeLabel('2026-10-23', '2026-10-24')).toBe('Oct 23, 2026')
    expect(rangeLabel('2025-11-01', '2026-10-24')).toBe('Nov 1, 2025 to Oct 23, 2026')
    expect(shortMonthLabel('2026-01-01')).toBe('Jan 2026')
    expect(shortMonthLabel('2026-10')).toBe('Oct')
  })
})

describe("the explorer's state in the URL", () => {
  it('reads every unknown or malformed value as its default', () => {
    expect(explorerStateOf({})).toEqual(DEFAULT_EXPLORER)
    expect(explorerStateOf({ range: 'forever', groupBy: 'colour', scope: 'galaxy', view: 'pie', granularity: 'hour', tag: 'nonsense' })).toEqual(DEFAULT_EXPLORER)
    expect(explorerStateOf({ groupBy: 'tag:Bad Key' }).groupBy).toBe('service')
    // a custom range's days are kept only for a custom range
    expect(explorerStateOf({ from: '2026-10-01', to: '2026-10-05' })).toEqual(DEFAULT_EXPLORER)
  })

  it('writes only what differs from the defaults, and reads it back the same', () => {
    expect(explorerQueryOf(DEFAULT_EXPLORER)).toEqual(Object.fromEntries(Object.keys(explorerQueryOf(DEFAULT_EXPLORER)).map((k) => [k, undefined])))
    const s: ExplorerState = {
      range: 'custom',
      from: '2026-09-01',
      to: '2026-10-10',
      granularity: 'month',
      groupBy: 'tag:environment',
      service: 'compute',
      type: 'vm',
      tag: { key: 'owner', value: 'team-compute' },
      scope: 'organization',
      wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-web-01',
      view: 'table',
    }
    const query = Object.fromEntries(Object.entries(explorerQueryOf(s)).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]))
    expect(query.tag).toBe('owner=team-compute')
    expect(explorerStateOf(query)).toEqual(s)
  })

  it('parses a tag filter as key=value', () => {
    expect(parseTag('environment=prod')).toEqual({ key: 'environment', value: 'prod' })
    expect(parseTag(' Owner = team a ')).toEqual({ key: 'owner', value: 'team a' })
    expect(parseTag('a=b=c')).toEqual({ key: 'a', value: 'b=c' })
    for (const bad of ['', 'prod', '=prod', 'environment=', 'bad key=x']) expect(parseTag(bad)).toBeUndefined()
  })

  it('asks the cost service for seven named groups and Other, and nothing it cannot draw by day', () => {
    expect(costQueryOf(DEFAULT_EXPLORER, TODAY)).toEqual({ from: '2026-10-01', to: '2026-10-24', granularity: 'day', groupBy: 'service', top: 7, scope: 'account', service: undefined, type: undefined, wfrn: undefined })
    const none = costQueryOf({ ...DEFAULT_EXPLORER, groupBy: 'none', tag: { key: 'environment', value: 'prod' } }, TODAY)
    expect(none.groupBy).toBeUndefined()
    expect(none.top).toBeUndefined()
    expect(none.tags).toEqual({ environment: 'prod' })
    expect(explorerRange({ ...DEFAULT_EXPLORER, range: '12m', granularity: 'day' }, TODAY).granularity).toBe('day')
    expect(explorerRange({ ...DEFAULT_EXPLORER, range: 'custom', from: '2025-01-01', to: '2026-10-01', granularity: 'day' }, TODAY).granularity).toBe('month')
    expect(groupLabel('tag:environment')).toBe('Tag: environment')
    expect(groupLabel('costCentre')).toBe('Cost centre')
  })
})

describe('the chart', () => {
  it("keeps a group's colour while the filters change which groups show", () => {
    const first = stickySlots({}, ['compute', 'database', 'storage'])
    expect(first).toEqual({ compute: 1, database: 2, storage: 3 })
    // compute leaves: the others keep theirs, a newcomer takes a slot nobody held, compute's is kept for its return
    const second = stickySlots(first, ['database', 'storage', 'network'])
    expect(second).toEqual({ database: 2, storage: 3, network: 4, compute: 1 })
    expect(stickySlots(second, ['compute', 'network'])).toEqual({ compute: 1, network: 4, database: 2, storage: 3 })
  })

  it('gives a slot away only when every slot has been held', () => {
    const held = stickySlots({}, ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'])
    const next = stickySlots(held, ['a', 'i'])
    expect(next.a).toBe(1)
    expect(next.i).toBe(2)
    expect(next.b).toBeUndefined()
    expect(Object.values(next).filter((s) => s === 2)).toHaveLength(1)
  })

  const report: CostReport = {
    currency: 'USD',
    from: '2026-10-21',
    to: '2026-10-24',
    granularity: 'day',
    groupBy: 'service',
    total: 160,
    groups: [
      { key: 'compute', label: 'Compute', total: 120 },
      { key: 'database', label: 'Database', total: 30 },
      { key: OTHER_KEY, label: 'Other', total: 10 },
    ],
    series: [
      { start: '2026-10-21', total: 60, groups: { compute: 50, database: 10 } },
      { start: '2026-10-22', total: 70, groups: { compute: 50, database: 15, [OTHER_KEY]: 5 } },
      { start: '2026-10-23', total: 30, groups: { compute: 20, database: 5, [OTHER_KEY]: 5 }, provisional: true },
    ],
    runRate: { monthly: 1500 },
    asOf: '2026-10-23T12:00:00Z',
    closedThrough: '2026-10-22',
    source: 'list-prices',
    unpriced: { resources: 0 },
  }

  it('stacks the largest group at the base and Other on top, in the tertiary ink', () => {
    const model = chartModelOf(report, { compute: 3, database: 1 })
    expect(model.series).toEqual([
      { key: 'compute', label: 'Compute', slot: 3 },
      { key: 'database', label: 'Database', slot: 1 },
      { key: OTHER_KEY, label: 'Other', slot: 'other' },
    ])
    expect(model.bars.map((b) => [b.label, b.title, b.total, !!b.provisional])).toEqual([
      ['Oct 21', 'Wednesday, October 21, 2026', 60, false],
      ['Oct 22', 'Thursday, October 22, 2026', 70, false],
      ['Oct 23', 'Friday, October 23, 2026', 30, true],
    ])
  })

  it('draws one series when nothing is grouped', () => {
    const model = chartModelOf({ ...report, groupBy: undefined, groups: [], granularity: 'month', series: [{ start: '2026-10-01', total: 160, groups: {} }] }, {})
    expect(model.series).toEqual([{ key: '__total', label: 'Cost', slot: 1 }])
    expect(model.bars[0]).toMatchObject({ label: 'Oct', title: 'October 2026', values: { __total: 160 } })
  })

  it('exports what the chart shows, a row per day with the totals last', () => {
    const rows = explorerCsvRows(report, chartModelOf(report, { compute: 1, database: 2 }))
    expect(rows[0]).toEqual(['Day', 'Compute', 'Database', 'Other', 'Total', 'Currency', 'Provisional'])
    expect(rows[2]).toEqual(['2026-10-22', '50.00', '15.00', '5.00', '70.00', 'USD', 'no'])
    expect(rows[3][6]).toBe('yes')
    expect(rows[4]).toEqual(['Total', '120.00', '30.00', '10.00', '160.00', 'USD', ''])
    expect(explorerFileName(report)).toBe('cost-2026-10-21-to-2026-10-23-by-service.csv')
    expect(explorerFileName({ ...report, groupBy: 'tag:environment' })).toBe('cost-2026-10-21-to-2026-10-23-by-tag-environment.csv')
  })
})

describe('budgets in words', () => {
  it('names the scope, the thresholds and an alert', () => {
    expect(scopeText({ accountId: '123456789012' }, 'Compute')).toBe('Every resource in Compute')
    expect(scopeText({ accountId: '123456789012', tag: { key: 'environment', value: 'prod' } })).toBe('Resources tagged environment = prod')
    expect(thresholdsText([50, 80, 100])).toBe('50%, 80% and 100%')
    expect(thresholdsText([80])).toBe('80%')
    expect(alertText({ budgetId: 'bud_1', name: 'Production', threshold: 80, percent: 83.4, period: '2026-10' })).toBe('Production crossed 80% of its amount for October 2026, at 83.4%.')
  })

  it('checks thresholds and amounts as the cost service does', () => {
    expect(thresholdProblem(['80', '100'])).toBeNull()
    expect(thresholdProblem(['', ' '])).toBe('Add at least one threshold.')
    expect(thresholdProblem(['1', '2', '3', '4', '5', '6'])).toBe('At most five thresholds.')
    expect(thresholdProblem(['0'])).toBe('Each threshold is a whole percentage from 1 to 1000.')
    expect(thresholdProblem(['80.5'])).toBe('Each threshold is a whole percentage from 1 to 1000.')
    expect(thresholdProblem(['80', '80'])).toBe('Each threshold once.')
    expect(parseAmount('1,250.50')).toBe(1250.5)
    expect(parseAmount('$900')).toBe(900)
    for (const bad of ['', '0', '-5', '12.345', 'ten', '1000000001']) expect(parseAmount(bad)).toBeNull()
  })
})

describe('chargeback', () => {
  const row = (costCentre: string, accountId: string, service: string, amount: number): ChargebackRow => ({ costCentre, accountId, accountName: accountId === '1' ? 'Compute' : 'Data', service, amount })

  it('subtotals by cost centre, then account, to the cent', () => {
    const groups = chargebackGroupsOf([row('CC-4100', '1', 'compute', 0.1), row('CC-4100', '1', 'database', 0.2), row('CC-4200', '2', 'data', 5)])
    expect(groups.map((c) => [c.costCentre, c.total, c.accounts.map((a) => [a.accountName, a.total, a.rows.length])])).toEqual([
      ['CC-4100', 0.3, [['Compute', 0.3, 2]]],
      ['CC-4200', 5, [['Data', 5, 1]]],
    ])
  })

  it('offers this month, still provisional, and the twelve before it', () => {
    const months = chargebackMonths(TODAY)
    expect(months).toHaveLength(13)
    expect(months[0]).toEqual({ value: '2026-10', label: 'October 2026 (so far)' })
    expect(months[1]).toEqual({ value: '2026-09', label: 'September 2026' })
    expect(months[12].value).toBe('2025-10')
  })

  it("names the file as the BFF does, and checks it with the browser's own SHA-256", async () => {
    expect(chargebackFileName('2026-09', 'account', '123456789012')).toBe('chargeback-2026-09-123456789012.csv')
    expect(chargebackFileName('2026-09', 'organization', '123456789012', 'environment')).toBe('chargeback-2026-09-organization-by-environment.csv')
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
})
