import { describe, expect, it } from 'vitest'
import type { BudgetAlert } from '../../lib/cost'
import { BUDGET_ALERT, budgetAlertOf, budgetAlertsUrl, watchBudgetAlerts } from './alerts'

/** An EventSource that the test drives: it records its listeners and plays events to them. */
class FakeSource extends EventTarget {
  static last: FakeSource | null = null
  readyState = 1
  onmessage: ((e: MessageEvent) => void) | null = null
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  constructor(readonly url: string) {
    super()
    FakeSource.last = this
  }
  play(type: string, data: unknown, id = '1') {
    this.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(data), lastEventId: id }))
  }
  close() {
    this.closed = true
  }
}

const ALERT: BudgetAlert = { budgetId: 'bud_01jb2x3y4z5a6b7c8d9e0f1g2h', name: 'Production', threshold: 80, percent: 83.4, period: '2026-10' }

describe('budget alerts', () => {
  it('reads only a well-formed budget.alert event', () => {
    expect(budgetAlertOf({ type: BUDGET_ALERT, at: '2026-10-23T12:00:00Z', data: ALERT })).toEqual(ALERT)
    expect(budgetAlertOf({ type: 'job.updated', data: ALERT })).toBeNull()
    expect(budgetAlertOf({ type: BUDGET_ALERT, data: { ...ALERT, threshold: '80' } })).toBeNull()
    expect(budgetAlertOf({ type: BUDGET_ALERT })).toBeNull()
    expect(budgetAlertOf('budget.alert')).toBeNull()
    expect(budgetAlertOf(null)).toBeNull()
  })

  it('listens on the stream narrowed to budget alerts, under the event name the stream gives them', () => {
    const heard: BudgetAlert[] = []
    const stream = watchBudgetAlerts((a) => heard.push(a), { factory: (url) => new FakeSource(url) as unknown as EventSource })
    const source = FakeSource.last!
    expect(source.url).toBe(budgetAlertsUrl())
    expect(budgetAlertsUrl()).toBe('/console/v1/events?types=budget.alert')
    source.play(BUDGET_ALERT, { type: BUDGET_ALERT, at: '2026-10-23T12:00:00Z', data: ALERT })
    // another type on the same stream is not an alert, even if it reached the listener
    source.play('job.updated', { type: 'job.updated', data: {} })
    source.play(BUDGET_ALERT, { type: BUDGET_ALERT, data: { name: 'broken' } }, '2')
    expect(heard).toEqual([ALERT])
    expect(stream.lastId()).toBe('2')
    stream.close()
    expect(source.closed).toBe(true)
  })
})
