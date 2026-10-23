import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { ChartFigure } from '../index'

/* A chart with its table twin: the caption names both, the table sits under a native disclosure. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const rows = [
  { week: '12 Oct', requests: 5 },
  { week: '19 Oct', requests: 45 },
]
const columns = [
  { key: 'week', header: 'Week' },
  { key: 'requests', header: 'Requests', numeric: true },
]

describe('ChartFigure', () => {
  it('captions the chart and keeps the same values as a table behind a disclosure', () => {
    const html = text(
      renderToString(
        <ChartFigure title="Requests" description="Weeks start on Monday" columns={columns} rows={rows} rowKey={(r) => r.week} tableFooter="2 weeks">
          <svg role="img" aria-label="Requests by week" />
        </ChartFigure>,
      ),
    )
    expect(html).toMatch(/^<figure/)
    expect(html).toContain('<figcaption')
    expect(html).toContain('Weeks start on Monday')
    expect(html).toContain('aria-label="Requests by week"')
    expect(html).toMatch(/<details[^>]*><summary[^>]*>.*Show as a table<\/summary>/)
    expect(html).toContain('<caption class="sr-only">Requests</caption>')
    expect(html).toContain('19 Oct')
    expect(html).toContain('>45<')
    expect(html).toContain('2 weeks')
  })

  it('takes its own words for the disclosure', () => {
    const html = text(
      renderToString(
        <ChartFigure title="Spend" tableLabel="Show the amounts" columns={columns} rows={rows} rowKey={(r) => r.week}>
          {null}
        </ChartFigure>,
      ),
    )
    expect(html).toContain('Show the amounts')
  })
})
