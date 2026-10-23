import { useEffect, useRef } from 'react'
import { BFF_BASE } from '../../lib/bff'
import type { BudgetAlert } from '../../lib/cost'
import { invalidate } from '../../lib/queries'
import { openStream, type Stream, type StreamOptions } from '../../lib/sse'

/*
 * Budget alerts as they happen. The BFF's live stream carries the cost service's
 * BudgetThresholdCrossed as `budget.alert` events (data: budgetId, name, threshold, percent,
 * period); the budget pages listen while they are open, read the budgets again and say the alert
 * once, in words. The stream resumes after the last event it saw when it drops (lib/sse.ts).
 */

/** The live event's type, and the name the stream gives it. */
export const BUDGET_ALERT = 'budget.alert'

/** The query key prefix of every budget read, refreshed by an alert. */
export const BUDGETS_KEY = 'budgets'

/** The stream of the session's account, narrowed to budget alerts. */
export const budgetAlertsUrl = () => `${BFF_BASE}/events?types=${BUDGET_ALERT}`

/** The alert a live event carries, or null for anything else. */
export function budgetAlertOf(event: unknown): BudgetAlert | null {
  if (!event || typeof event !== 'object') return null
  const e = event as { type?: unknown; data?: Record<string, unknown> | null }
  if (e.type !== BUDGET_ALERT || !e.data || typeof e.data !== 'object') return null
  const { budgetId, name, threshold, percent, period } = e.data
  if (typeof budgetId !== 'string' || typeof name !== 'string' || typeof threshold !== 'number' || typeof percent !== 'number' || typeof period !== 'string') return null
  return { budgetId, name, threshold, percent, period }
}

/** Opens the stream; each alert refreshes the budgets, then goes to `onAlert`. */
export function watchBudgetAlerts(onAlert: (alert: BudgetAlert) => void, opts: Pick<StreamOptions, 'factory'> = {}): Stream {
  return openStream(
    budgetAlertsUrl(),
    (event) => {
      const alert = budgetAlertOf(event)
      if (!alert) return
      invalidate(BUDGETS_KEY)
      onAlert(alert)
    },
    { events: [BUDGET_ALERT], factory: opts.factory },
  )
}

/** Listens for budget alerts while the calling page is open. */
export function useBudgetAlerts(onAlert: (alert: BudgetAlert) => void): void {
  const latest = useRef(onAlert)
  useEffect(() => {
    latest.current = onAlert
  })
  useEffect(() => {
    const stream = watchBudgetAlerts((a) => latest.current(a))
    return () => stream.close()
  }, [])
}
