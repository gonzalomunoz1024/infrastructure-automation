import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { CostReport } from '../../lib/cost'
import type { Insights } from '../../lib/insights'
import type { Denial } from '../../lib/permissions'
import { buildMockData } from '../../mock/data'
import { createMockServer, csrfTokenOf, memorySessionStore } from '../../mock/server'
import { AdoptionCard, HeadlineStats, InsightsSkeleton, ServicesCard, SpendCard, type Spend } from './parts'

/*
 * The executive home's parts, drawn from the mock BFF's own answers: the six tiles, adoption with
 * its tables, resources by service, and spend by team in each of its states.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')

function answers(person: string) {
  const server = createMockServer(buildMockData(), memorySessionStore(), () => NOW)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const get = <T,>(url: string) => server.handle({ method: 'GET', url, headers: { 'x-csrf-token': csrfTokenOf(person) } }).body as T
  const numbers = get<Insights>('/console/v1/insights?period=30d')
  const report = get<CostReport>(`/console/v1/cost?from=${numbers.from.slice(0, 10)}&to=${numbers.to.slice(0, 10)}&granularity=day&groupBy=costCentre&scope=organization`)
  return { numbers, report }
}

const text = (html: string) =>
  html
    .replace(/<!--.*?-->/g, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/\s+/g, ' ')
const gonzalo = answers('gonzalo')
const denied: Denial = { layer: 'console-iam', action: 'cost:list', reason: 'no policy allows cost:list', requestable: true }
const spendCard = (spend: Spend) =>
  renderToString(
    <SpendCard
      spend={spend}
      period="30d"
      teamOf={(id) => (id === '123456789012' ? 'team-compute' : 'team-data')}
      nameOf={(id) => (id === '210987654321' ? 'Data' : id)}
      onRetry={() => {}}
      onSeeWhy={() => {}}
    />,
  )

describe('the headline tiles', () => {
  it('draws the six numbers with their quiet lines, spend from the cost report', () => {
    const html = renderToString(<HeadlineStats insights={gonzalo.numbers} spend={{ status: 'ready', report: gonzalo.report }} />)
    const words = text(html)
    expect(html).toContain('aria-label="Headline numbers"')
    for (const label of ['Resources managed', 'Self-served', 'Median lead time', 'Hours saved', 'Drift fixed', 'Spend in the period']) expect(words).toContain(label)
    expect(words).toContain('223 +223 since 24 Sept')
    expect(words).toContain('92% 46 of 50 requests')
    expect(words).toContain('12 min against 2 days by ticket')
    expect(words).toContain('1,933 hours')
    expect(words).toContain('0 of 19 19 open now none fixed yet')
    expect(words).toContain(`$${gonzalo.report.total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`)
    expect(words).toContain('Estimates from list prices, not invoices')
  })

  it('says when spend is on its way, or not to be had', () => {
    expect(renderToString(<HeadlineStats insights={gonzalo.numbers} spend={{ status: 'loading' }} />)).toContain('skeleton')
    expect(text(renderToString(<HeadlineStats insights={gonzalo.numbers} spend={{ status: 'refused', denied }} />))).toContain('Not available Your role may not read cost')
  })
})

describe('adoption', () => {
  it('draws requests and people as two charts, each with its table', () => {
    const html = renderToString(<AdoptionCard insights={gonzalo.numbers} />)
    expect(html).toContain('aria-label="Requests and self-served requests by week, weeks of 21 Sept to 19 Oct"')
    expect(html).toContain('aria-label="Active people by week, weeks of 21 Sept to 19 Oct"')
    expect(html.match(/<details/g)).toHaveLength(2)
    expect(text(html)).toContain('19 Oct 45 41 91%')
  })

  it('says so when nobody asked for anything', () => {
    const quiet = { ...gonzalo.numbers, period: '7d' as const, adoption: gonzalo.numbers.adoption.map((w) => ({ ...w, requests: 0, selfServed: 0, activeUsers: 0 })) }
    expect(text(renderToString(<AdoptionCard insights={quiet} />))).toContain('No requests in the last 7 days')
  })
})

describe('resources by service', () => {
  it('ranks the services in a bar chart that names its values, over their table', () => {
    const html = renderToString(<ServicesCard insights={gonzalo.numbers} />)
    expect(html).toContain('aria-label="Resources by service: Compute 216, Platform 5, Data 2"')
    expect(text(html)).toContain('Compute 216 97%')
  })
})

describe('spend by team', () => {
  it('lists each cost centre with its accounts, teams, amount and share, and the total', () => {
    const words = text(spendCard({ status: 'ready', report: gonzalo.report }))
    expect(words).toContain('Estimates from list prices, not invoices')
    expect(words).toContain('CC-4100 Compute · team-compute')
    expect(words).toContain('CC-4200 Data · team-data')
    expect(words).toContain(`Total $${gonzalo.report.total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`)
    expect(words).toMatch(/October so far: \$[\d,]+\.\d\d\. Expected by the month’s end: \$[\d,]+\.\d\d, between/)
  })

  it('names the accounts the report left out and the resources no price covers', () => {
    const words = text(spendCard({ status: 'ready', report: { ...gonzalo.report, skippedAccounts: ['210987654321'], unpriced: { resources: 2, types: ['volume'] } } }))
    expect(words).toContain('Left out: Data, where your role may not read cost.')
    expect(words).toContain('2 resources with no list price are not counted.')
  })

  it('shows its loading, refused, failed and empty states', () => {
    expect(spendCard({ status: 'loading' })).toContain('aria-label="Loading"')
    expect(text(spendCard({ status: 'refused', denied }))).toContain('You can’t read spend here no policy allows cost:list See why')
    expect(text(spendCard({ status: 'failed', error: new Error('The cost service did not answer') }))).toContain('Could not load spend The cost service did not answer Try again')
    expect(text(spendCard({ status: 'ready', report: { ...gonzalo.report, total: 0, groups: [] } }))).toContain('No spend in the period')
  })
})

describe('the skeleton', () => {
  it('stands in for the tiles and the cards while the first answer is on its way', () => {
    const html = renderToString(<InsightsSkeleton />)
    expect(html).toContain('role="status"')
    expect(html).toContain('aria-label="Loading the numbers"')
    expect(html.match(/skeleton/g)?.length).toBeGreaterThanOrEqual(9)
  })
})
