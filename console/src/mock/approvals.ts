import type { BffApproval } from '../lib/types'
import { emptyConsoleState, pageOf, problems400, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { approvalsOf } from './jobs'

/*
 * `GET /console/v1/approvals` (hub contract, tag Approvals): the approvals waiting for the person
 * and the ones on requests they asked for, across the account's requests, newest first. Each is
 * the job's own approval as the request page shows it, `canDecide` included; deciding one goes
 * through the request's `approvals:decide`, as the request page decides it. The phone view
 * (WP 9.18) reads it.
 */

const STATUSES = new Set(['pending', 'approved', 'rejected', 'expired'])

export function approvalRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (path !== '/console/v1/approvals') return null
  if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  const status = params.get('status')
  if (status && !STATUSES.has(status)) return problems400([{ level: 'error', code: 'enum', field: '/status', message: 'The status is pending, approved, rejected or expired' }])
  const st = (data.console ??= emptyConsoleState())
  const s = ctx.session
  const out: BffApproval[] = []
  for (const job of data.jobs) {
    if (job.accountId && job.accountId !== s.accountId) continue
    const extra = st.jobs[job.id]
    const mine = job.requester === s.userId
    for (const a of approvalsOf(data, s, job, extra)) {
      if (status && a.status !== status) continue
      if (!mine && !a.canDecide) continue
      out.push({ ...a, jobId: a.jobId ?? job.id })
    }
  }
  out.sort((a, b) => (b.requestedAt ?? '').localeCompare(a.requestedAt ?? ''))
  return pageOf(out, params)
}
