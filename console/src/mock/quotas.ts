import type { ConsoleQuotaRequest, QuotaRequestVerb } from '../lib/quotas'
import type { Denial, QuotaRequest, QuotaRequestStatus } from '../lib/types'
import { idFrom, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { DirectoryError, QUOTA_RULES, authorize, decideQuotaRequest, iamResource, isPlatformTeam, quotaRequestOf, quotaRequestsOf, type Decision } from './directory'

/*
 * Service Quotas beside the identity routes the mock server already answers (the quotas, the ask
 * and the withdrawal): the organization's auto-approval rules (`/console/v1/iam/quota-rules`),
 * the account's increase requests with what the person may do with each
 * (`/console/v1/iam/quota-requests`, one of them by id), and the platform team's decision
 * (`:decide`). The identity service keeps `:decide` to its internal principal; the BFF calls it
 * for a member of the platform team, never for the requester, and says so on every request it
 * serves (`can`, and `refusals` with the reason in words). The requests live in the directory,
 * so the browser keeps them with the rest of identity.
 */

const STATUSES: readonly QuotaRequestStatus[] = ['open', 'approved', 'rejected', 'withdrawn']

export function quotaRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!path.startsWith('/console/v1/iam/quota-')) return null
  const dir = data.directory
  const s = ctx.session
  const now = ctx.now
  const iam = (kind: string, id?: string) => iamResource(s.accountId, kind, id)
  const can = (action: string, resource: string) => authorize(dir, { accountId: s.accountId, userId: s.userId }, action, resource)
  const refused = (d: Decision): RouteAnswer => ({ status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(`${now}:${path}`, 8)}` } })

  /** why this person may not decide a request, when they may not */
  const decideRefusal = (r: QuotaRequest): Denial | undefined => {
    const resource = iam('quota-request', r.requestId)
    if (r.requestedBy === s.userId) return { layer: 'console-iam', action: 'quota:decide', resource, reason: 'Nobody decides their own quota request', requestable: false }
    if (!isPlatformTeam(s.userId)) return { layer: 'console-iam', action: 'quota:decide', resource, reason: 'The platform team decides the quota requests no rule approves, and you are not on it', requestable: false }
    return undefined
  }
  const withdrawRefusal = (r: QuotaRequest): Denial | undefined => {
    if (r.requestedBy === s.userId) return undefined
    const d = can('quota:set', iam('quota', r.quotaKey))
    return d.allowed ? undefined : d.denied
  }
  /** a request as the BFF serves it: the identity service's, with `can` and the refusals */
  const served = (r: QuotaRequest): ConsoleQuotaRequest => {
    if (r.status !== 'open') return { ...r, can: [] }
    const refusals: Partial<Record<QuotaRequestVerb, Denial>> = {}
    const decide = decideRefusal(r)
    const withdraw = withdrawRefusal(r)
    if (decide) refusals.decide = decide
    if (withdraw) refusals.withdraw = withdraw
    return { ...r, can: (['decide', 'withdraw'] as const).filter((v) => !refusals[v]), ...(Object.keys(refusals).length ? { refusals } : {}) }
  }

  if (path === '/console/v1/iam/quota-rules' && method === 'GET') return { status: 200, body: QUOTA_RULES }

  if (path === '/console/v1/iam/quota-requests' && method === 'GET') {
    const d = can('quota:list', iam('quota'))
    if (!d.allowed) return refused(d)
    const status = params.get('status')
    if (status && !STATUSES.includes(status as QuotaRequestStatus)) return { status: 400, body: { error: 'status is open, approved, rejected or withdrawn' } }
    const key = params.get('quotaKey')
    const all = quotaRequestsOf(dir, s.accountId).filter((r) => (!status || r.status === status) && (!key || r.quotaKey === key))
    const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
    const start = Number(/^c(\d+)$/.exec(params.get('after') ?? '')?.[1] ?? 0)
    return { status: 200, body: { items: all.slice(start, start + limit).map(served), next: start + limit < all.length ? `c${start + limit}` : null } }
  }

  const m = /^\/console\/v1\/iam\/quota-requests\/([^/:]+)(?::(decide))?$/.exec(path)
  if (!m) return null
  const requestId = decodeURIComponent(m[1])
  if (method === 'GET' && !m[2]) {
    const d = can('quota:get', iam('quota', requestId))
    if (!d.allowed) return refused(d)
    const r = quotaRequestOf(dir, s.accountId, requestId)
    return r ? { status: 200, body: served(r) } : { status: 404, body: { error: `Quota request ${requestId} not found` } }
  }
  if (method === 'POST' && m[2] === 'decide') {
    const r = quotaRequestOf(dir, s.accountId, requestId)
    if (!r) return { status: 404, body: { error: `Quota request ${requestId} not found` } }
    const denied = decideRefusal(r)
    if (denied) return refused({ allowed: false, denied })
    const body = ctx.body
    const decision = body.decision === 'approve' || body.decision === 'reject' ? body.decision : null
    if (!decision) return { status: 400, body: { error: 'decision is approve or reject' } }
    const limit = body.limit
    if (limit !== undefined && (typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1)) return { status: 400, body: { error: 'limit is an integer of at least 1' } }
    const comment = typeof body.comment === 'string' ? body.comment : undefined
    if (comment && comment.length > 2000) return { status: 400, body: { error: 'comment is 2000 characters at most' } }
    if (r.status !== 'open') throw new DirectoryError(409, `the request is ${r.status}`)
    return { status: 202, body: served(decideQuotaRequest(dir, s.accountId, requestId, decision, s.userId, { limit: typeof limit === 'number' ? limit : undefined, comment }, now)) }
  }
  return null
}
