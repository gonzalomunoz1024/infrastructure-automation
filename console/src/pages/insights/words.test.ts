import { describe, expect, it } from 'vitest'
import { OTHER_KEY, type CostReport } from '../../lib/cost'
import type { Insights } from '../../lib/insights'
import { buildMockData } from '../../mock/data'
import { insightsOf } from '../../mock/insights'
import {
  asOfWords,
  changeWords,
  dayWords,
  durationWords,
  headlineOf,
  hoursWords,
  leftOut,
  listWords,
  money,
  moneyTick,
  monthWords,
  shareWords,
  spendNotes,
  spendRows,
  viewOf,
  viewWords,
} from './words'

/*
 * The executive home's words: the view a query names, durations and shares as people say them,
 * the headline of the mock's own answers, and the cost centres of spend by team.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = '123456789012'
const DATA = '210987654321'
const data = buildMockData()
const answer = (userId: string, accountIds: string[], scope: Insights['scope'] = 'organization') =>
  insightsOf(data, { userId, accountId: accountIds[0], region: 'global' }, accountIds, '30d', scope, NOW)

describe('the view', () => {
  it('reads the period and the scope from the query, the contract’s defaults standing in', () => {
    expect(viewOf({})).toEqual({ period: '30d', scope: 'organization' })
    expect(viewOf({ period: '7d', scope: 'account' })).toEqual({ period: '7d', scope: 'account' })
    expect(viewOf({ period: '1y', scope: 'everything' })).toEqual({ period: '30d', scope: 'organization' })
  })

  it('says what the numbers cover', () => {
    expect(viewWords({ period: '7d', scope: 'account' }, 'Compute')).toBe('the last 7 days in Compute')
    expect(viewWords({ period: '90d', scope: 'organization' }, 'Compute')).toBe('the last 90 days in all your accounts')
  })
})

describe('the words', () => {
  it('give a duration in the largest unit that reads well', () => {
    expect(durationWords(5158)).toBe('5 s')
    expect(durationWords(739_406)).toBe('12 min')
    expect(durationWords(3_570_000)).toBe('1 h')
    expect(durationWords(5 * 3_600_000)).toBe('5 h')
    expect(durationWords(86_400_000)).toBe('1 day')
    expect(durationWords(2 * 86_400_000)).toBe('2 days')
    expect(durationWords(1.5 * 86_400_000)).toBe('1.5 days')
  })

  it('give a share that never reads as all or none when it is neither', () => {
    expect(shareWords(46, 50)).toBe('92%')
    expect(shareWords(999, 1000)).toBe('99%')
    expect(shareWords(1, 1000)).toBe('<1%')
    expect(shareWords(0, 4)).toBe('0%')
    expect(shareWords(3, 3)).toBe('100%')
    expect(shareWords(0, 0)).toBe('—')
  })

  it('say how a count moved since the period began, in UTC days', () => {
    expect(changeWords(223, 0, '2026-09-24T00:00:00.000Z')).toBe('+223 since 24 Sept')
    expect(changeWords(220, 223, '2026-10-17T00:00:00.000Z')).toBe('−3 since 17 Oct')
    expect(changeWords(5, 5, '2026-10-17T00:00:00.000Z')).toBe('No change since 17 Oct')
    expect(dayWords('2026-10-19T00:00:00.000Z')).toBe('19 Oct')
  })

  it('name hours, lists, amounts and moments', () => {
    expect(hoursWords(1933.2)).toBe('1,933 hours')
    expect(hoursWords(1)).toBe('1 hour')
    expect(hoursWords(0.46)).toBe('0.5 hours')
    expect(listWords(['Compute'])).toBe('Compute')
    expect(listWords(['Compute', 'Data'])).toBe('Compute and Data')
    expect(listWords(['Compute', 'Data', 'Storage'])).toBe('Compute, Data and Storage')
    expect(money(1234.5, 'USD')).toBe('$1,234.50')
    expect(money(0.004, 'EUR')).toBe('€0.00')
    expect(moneyTick(12_345.67, 'USD')).toBe('$12.3K')
    expect(asOfWords('2026-10-23T10:00:00.000Z')).toBe('23 Oct, 10:00 UTC')
    expect(monthWords('2026-10')).toBe('October')
  })

  it('name the memberships the numbers leave out', () => {
    const memberships = [
      { accountId: COMPUTE, name: 'Compute' },
      { accountId: DATA, name: 'Data' },
    ]
    expect(leftOut(memberships, [{ accountId: DATA }])).toEqual([{ accountId: COMPUTE, name: 'Compute' }])
    expect(leftOut(memberships, memberships)).toEqual([])
  })
})

describe('the headline', () => {
  it('reads the mock’s answer for every account: lead time against the tickets, hours saved, drift', () => {
    const h = headlineOf(answer('gonzalo', [COMPUTE, DATA]))
    expect(h.managed).toEqual({ value: '223', hint: '+223 since 24 Sept' })
    expect(h.selfServed).toEqual({ value: '92%', hint: '46 of 50 requests' })
    expect(h.leadTime).toEqual({ value: '12 min', hint: 'against 2 days by ticket' })
    expect(h.hoursSaved).toEqual({ value: '1,933 hours', hint: 'Against the ticket export, September 2026', needsBaseline: false })
    expect(h.drift).toEqual({ value: '0 of 19', open: 19, openWords: '19 open now', note: 'none fixed yet' })
  })

  it('shows the lead time alone where no ticket baseline covers the requests, and says why hours saved are missing', () => {
    const h = headlineOf(answer('raj', [DATA]))
    expect(h.leadTime).toEqual({ value: '5 s', hint: 'No ticket baseline; 90% within 7 min' })
    expect(h.hoursSaved).toMatchObject({ value: 'Needs a baseline', needsBaseline: true, hint: expect.stringContaining('No ticket export covers these requests') })
    expect(h.drift).toEqual({ value: 'None found', open: 0, openWords: 'Nothing open now', note: '' })
  })

  it('has words for a period with nothing in it, and for fixes of drift found before it', () => {
    const quiet: Insights = {
      ...answer('raj', [DATA]),
      requests: { total: 0, selfServed: 0, failed: 0 },
      leadTime: { p50Ms: 0, p90Ms: 0 },
      drift: { found: 1, fixed: 3, open: 2, meanTimeToFixMs: 3 * 3_600_000 },
    }
    const h = headlineOf(quiet)
    expect(h.selfServed).toEqual({ value: 'No requests', hint: 'None started in the period' })
    expect(h.leadTime.value).toBe('None yet')
    expect(h.hoursSaved.hint).toBe('No self-served request to compare with a ticket')
    expect(h.drift).toEqual({ value: '3 fixed', open: 2, openWords: '2 open now', note: '1 found, fixed in 3 h on average' })
  })
})

describe('spend by team', () => {
  const report: CostReport = {
    currency: 'USD',
    from: '2026-09-24',
    to: '2026-10-24',
    granularity: 'day',
    groupBy: 'costCentre',
    total: 1000,
    groups: [
      { key: 'CC-4100', label: 'CC-4100', total: 750 },
      { key: 'CC-4200', label: 'CC-4200', total: 200 },
      { key: OTHER_KEY, label: 'Other', total: 50 },
    ],
    series: [],
    runRate: { monthly: 900 },
    asOf: '2026-10-23T10:00:00Z',
    source: 'list-prices',
    unpriced: { resources: 0 },
    accounts: [
      { accountId: COMPUTE, name: 'Compute', costCentre: 'CC-4100' },
      { accountId: DATA, name: 'Data', costCentre: 'CC-4200' },
      { accountId: '333333333333', name: 'Sandbox', costCentre: 'CC-9000' },
    ],
  }

  it('joins each cost centre with its accounts and their owning teams, and its share of the total', () => {
    const teams: Record<string, string> = { [COMPUTE]: 'team-compute', [DATA]: 'team-data' }
    expect(spendRows(report, (id) => teams[id])).toEqual([
      { key: 'CC-4100', label: 'CC-4100', accounts: ['Compute'], teams: ['team-compute'], amount: 750, share: 0.75 },
      { key: 'CC-4200', label: 'CC-4200', accounts: ['Data'], teams: ['team-data'], amount: 200, share: 0.2 },
      { key: OTHER_KEY, label: 'Other', accounts: ['Sandbox'], teams: [], amount: 50, share: 0.05 },
    ])
  })

  it('notes the month’s forecast, what no price covers and the accounts left out, and nothing when there is nothing to say', () => {
    const names: Record<string, string> = { [DATA]: 'Data' }
    expect(
      spendNotes(
        {
          ...report,
          forecast: { month: '2026-10', monthToDate: 14_441.01, monthEnd: 21_287.04, low: 20_861.3, high: 21_712.79, method: 'run-rate' },
          unpriced: { resources: 1 },
          skippedAccounts: [DATA],
        },
        (id) => names[id] ?? id,
      ),
    ).toEqual([
      'October so far: $14,441.01. Expected by the month’s end: $21,287.04, between $20,861.30 and $21,712.79.',
      '1 resource with no list price is not counted.',
      'Left out: Data, where your role may not read cost.',
    ])
    expect(spendNotes(report, (id) => id)).toEqual([])
  })

  it('has no share of nothing', () => {
    expect(spendRows({ ...report, total: 0, groups: [{ key: 'CC-4100', label: 'CC-4100', total: 0 }] }, () => undefined)[0].share).toBe(0)
  })
})
