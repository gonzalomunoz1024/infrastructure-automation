import { describe, expect, it } from 'vitest'
import { accessWords, moveTarget, moveWidget, resizeWidget, sharingWords, sizeWords, stepSize, WIDTHS, type Widget } from './dashboards'
import { objectiveWords, percentText, platformWord, readingWords, safeHref, summaryWords, type HealthService, type Slo } from './health'

const w = (id: string): Widget => ({ widgetId: id, kind: 'drift', title: id, width: 4, height: 2 })

describe('the dashboard layout', () => {
  it('moves a widget one place at a time, clamped to the board', () => {
    const ws = [w('a'), w('b'), w('c')]
    expect(moveWidget(ws, 2, 1).map((x) => x.widgetId)).toEqual(['a', 'c', 'b'])
    expect(moveWidget(ws, 0, -1).map((x) => x.widgetId)).toEqual(['a', 'b', 'c'])
    expect(moveWidget(ws, 0, 9).map((x) => x.widgetId)).toEqual(['b', 'c', 'a'])
    expect(ws.map((x) => x.widgetId)).toEqual(['a', 'b', 'c'])
  })

  it('reads the arrows, Home and End as places', () => {
    expect(moveTarget('ArrowLeft', 1, 3)).toBe(0)
    expect(moveTarget('ArrowUp', 0, 3)).toBe(0)
    expect(moveTarget('ArrowRight', 1, 3)).toBe(2)
    expect(moveTarget('ArrowDown', 2, 3)).toBe(2)
    expect(moveTarget('Home', 2, 3)).toBe(0)
    expect(moveTarget('End', 0, 3)).toBe(2)
    expect(moveTarget('Enter', 0, 3)).toBeNull()
  })

  it('resizes in steps and stops at the ends', () => {
    expect(stepSize(WIDTHS, 4, 1)).toBe(6)
    expect(stepSize(WIDTHS, 12, 1)).toBe(12)
    expect(stepSize(WIDTHS, 3, -1)).toBe(3)
    const [r] = resizeWidget([w('a')], 0, { width: 1, height: -1 })
    expect([r.width, r.height]).toEqual([6, 1])
    expect(sizeWords(r)).toBe('half the width, 1 row tall')
  })

  it('says who a dashboard is shared with and how this person reaches it', () => {
    expect(sharingWords({ account: 'none', groups: [] }, 'Compute')).toBe('Only its owner')
    expect(sharingWords({ account: 'view', groups: [{ group: 'members', title: 'team-compute', access: 'edit' }] }, 'Compute')).toBe('Everyone in Compute to view and team-compute to edit')
    expect(accessWords({ access: 'owner', owner: { userId: 'dana', name: 'Dana Reyes' } })).toBe('Yours')
    expect(accessWords({ access: 'view', owner: { userId: 'dana', name: 'Dana Reyes' } })).toBe('Shared with you by Dana Reyes to view')
  })
})

describe('service health words', () => {
  const slo: Slo = { kind: 'latency', objective: 99, thresholdMs: 400, windows: { '24h': { value: 97.8, p95Ms: 1350, met: false, budgetLeft: 0 }, '7d': { value: 99.25, p95Ms: 210, met: true, budgetLeft: 0.25 } } }

  it('says the objectives and their readings in sentences', () => {
    expect(objectiveWords(slo)).toBe('99% of requests answer within 400 ms')
    expect(objectiveWords({ ...slo, kind: 'availability', objective: 99.95 })).toBe('99.95% of requests succeed')
    expect(readingWords(slo, '24h')).toBe('97.8% in the last 24 hours, 95th percentile 1350 ms: missed, no error budget left')
    expect(readingWords(slo, '7d')).toBe('99.25% in the last 7 days, 95th percentile 210 ms: met, 25% of the error budget left')
    expect(percentText(99.995)).toBe('99.995%')
    expect(percentText(100)).toBe('100%')
  })

  it('sums up the services and names the platform’s state for the footer', () => {
    const s = (id: string, status: HealthService['status']): HealthService => ({ serviceId: id, title: id, description: '', status, slos: [], days: [] })
    expect(summaryWords([s('a', 'operational')])).toBe('Every service is operational.')
    expect(summaryWords([s('Search', 'degraded'), s('b', 'operational')])).toBe('One service is not fully working: Search (degraded). The others are operational.')
    expect(platformWord('operational')).toBe('up')
    expect(platformWord('outage')).toBe('down')
    expect(platformWord(undefined)).toBe('unknown')
  })

  it('follows a link from the data only when it is https or a path here', () => {
    expect(safeHref('https://status.example.com/x')).toBe('https://status.example.com/x')
    expect(safeHref('/#/health')).toBe('/#/health')
    expect(safeHref('javascript:alert(1)')).toBeNull()
    expect(safeHref('http://status.example.com')).toBeNull()
    expect(safeHref('//evil.example.com')).toBeNull()
    expect(safeHref('/\\evil.example.com')).toBeNull()
    expect(safeHref('data:text/html,hi')).toBeNull()
    expect(safeHref('https://user:pw@example.com')).toBeNull()
    expect(safeHref(undefined)).toBeNull()
  })
})
