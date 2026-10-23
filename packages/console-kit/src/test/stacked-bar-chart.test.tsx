// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { renderToString } from 'react-dom/server'
import { SERIES_CSS, SERIES_PALETTE, StackedBarChart, barDescription, segmentPath, seriesColor, stackSegments, type StackedBar, type StackedSeries } from '../index'

;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const usd = (v: number) => `$${v.toFixed(2)}`

const SERIES: StackedSeries[] = [
  { key: 'compute', label: 'Compute', slot: 1 },
  { key: 'database', label: 'Database', slot: 2 },
  { key: '__other', label: 'Other', slot: 'other' },
]
const BARS: StackedBar[] = [
  { key: '2026-10-21', label: 'Oct 21', title: 'October 21, 2026', values: { compute: 60, database: 30, __other: 10 } },
  { key: '2026-10-22', label: 'Oct 22', title: 'October 22, 2026', values: { compute: 50, database: 40 } },
  { key: '2026-10-23', label: 'Oct 23', title: 'October 23, 2026', values: { compute: 20, database: 5 }, provisional: true },
]

describe('the series palette', () => {
  it('serves eight slots for each theme and keeps the hex values out of the marks', () => {
    for (const [i, hex] of SERIES_PALETTE.light.entries()) expect(SERIES_CSS).toContain(`--series-${i + 1}:${hex};`)
    for (const [i, hex] of SERIES_PALETTE.dark.entries()) expect(SERIES_CSS).toContain(`--series-${i + 1}:${hex};`)
    expect(SERIES_CSS.indexOf(':root{')).toBeLessThan(SERIES_CSS.indexOf('.dark{'))
    expect(seriesColor(3)).toBe('var(--series-3)')
    // the folded rest, and anything past the eighth slot, is the tertiary ink: never a ninth hue
    expect(seriesColor('other')).toBe('var(--color-text-tertiary)')
    expect(seriesColor(9)).toBe('var(--color-text-tertiary)')
  })
})

describe('stacking', () => {
  const y = (v: number) => 100 - v

  it('leaves a 2px gap of the surface between neighbours and rounds only the top of the stack', () => {
    const segments = stackSegments({ key: 'b', label: 'b', values: { a: 50, b: 30, c: 0, d: 20 } }, ['a', 'b', 'c', 'd'].map((key, i) => ({ key, label: key, slot: i + 1 })), y)
    expect(segments.map((s) => [s.key, s.y, s.height, s.top])).toEqual([
      ['a', 50, 50, false],
      ['b', 20, 28, false],
      ['d', 0, 18, true],
    ])
  })

  it('leaves a part too thin to show past its gap out of the drawing, not out of the numbers', () => {
    const bar = { key: 'b', label: 'Oct 1', values: { a: 99, b: 1 } }
    const series = [
      { key: 'a', label: 'A', slot: 1 },
      { key: 'b', label: 'B', slot: 2 },
    ]
    expect(stackSegments(bar, series, y).map((s) => [s.key, s.top])).toEqual([['a', true]])
    expect(barDescription(bar, series, usd)).toBe('Oct 1: $100.00. A $99.00, B $1.00')
  })

  it('draws the baseline square and the data end rounded', () => {
    expect(segmentPath(0, 0, 20, 30, false)).toBe('M0 0 H20 V30 H0 Z')
    expect(segmentPath(0, 0, 20, 30, true)).toContain('Q0 0 4 0')
    expect(segmentPath(0, 0, 20, 0, true)).toBe('')
  })

  it('says a column in words: its total, then its parts largest first, and whether it is still open', () => {
    expect(barDescription(BARS[0], SERIES, usd)).toBe('October 21, 2026: $100.00. Compute $60.00, Database $30.00, Other $10.00')
    expect(barDescription(BARS[2], SERIES, usd, 'Provisional')).toBe('October 23, 2026: $25.00. Compute $20.00, Database $5.00. Provisional')
    expect(barDescription(BARS[0], [SERIES[0]], usd)).toBe('October 21, 2026: $100.00')
  })
})

describe('StackedBarChart', () => {
  it('names its series in a legend from two up, and every column for screen readers', () => {
    const html = text(renderToString(<StackedBarChart series={SERIES} bars={BARS} format={usd} label="Daily cost by service" />))
    expect(html).toContain('aria-label="Legend"')
    expect(html).toContain('aria-label="Daily cost by service"')
    expect(html.match(/data-bar=/g)).toHaveLength(3)
    expect(html).toContain('aria-label="October 22, 2026: $90.00. Compute $50.00, Database $40.00"')
    // one column in the tab order, the newest
    expect(html.match(/tabindex="0"/g)).toHaveLength(1)
    expect(html).toMatch(/tabindex="0"[^>]*data-bar="2026-10-23"/)
    expect(html).not.toMatch(/tabindex="0"[^>]*data-bar="2026-10-2[12]"/)
    expect(html).toContain('var(--series-1)')
    expect(html).not.toMatch(/#[0-9a-f]{6}"/)
  })

  it('needs no legend for one series, and says so when there is nothing to draw', () => {
    const one = text(renderToString(<StackedBarChart series={[SERIES[0]]} bars={BARS} format={usd} label="Daily cost" />))
    expect(one).not.toContain('aria-label="Legend"')
    const empty = text(renderToString(<StackedBarChart series={SERIES} bars={[]} format={usd} label="Daily cost" emptyLabel="No cost in this range" />))
    expect(empty).toContain('No cost in this range')
    expect(empty).not.toContain('data-bar')
  })

  it('steps from column to column with the arrow keys, Home and End, and shows the values of the one in focus', () => {
    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)
    act(() => root.render(<StackedBarChart series={SERIES} bars={BARS} format={usd} label="Daily cost by service" />))
    const column = (key: string) => container.querySelector<SVGGElement>(`[data-bar="${key}"]`)!
    const focusedBar = () => (document.activeElement as Element | null)?.getAttribute('data-bar')
    const press = (key: string) => act(() => void document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })))

    act(() => column('2026-10-23').focus())
    expect(focusedBar()).toBe('2026-10-23')
    expect(container.textContent).toContain('$25.00')
    expect(container.textContent).toContain('Provisional: the period is still open')
    press('ArrowLeft')
    expect(focusedBar()).toBe('2026-10-22')
    expect(column('2026-10-22').getAttribute('tabindex')).toBe('0')
    expect(column('2026-10-23').getAttribute('tabindex')).toBe('-1')
    press('Home')
    expect(focusedBar()).toBe('2026-10-21')
    expect(container.textContent).toContain('$100.00')
    press('End')
    expect(focusedBar()).toBe('2026-10-23')
    press('ArrowRight')
    expect(focusedBar()).toBe('2026-10-23')
    act(() => root.unmount())
    container.remove()
  })
})
