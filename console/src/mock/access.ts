import type { AccessReview, AccessReviewCounts, AccessReviewInput, AccessReviewItem } from '../lib/accessReviews'
import { SYSTEM_REVIEWER } from '../lib/accessReviews'
import type { BreakGlassInput, Elevation, ElevationInput, ElevationRules, ElevationStatus } from '../lib/elevations'
import { isoDurationMs } from '../lib/elevations'
import type { Denial } from '../lib/types'
import { pageOf, usedByTypeOf, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { ADMIN_ROLE, DirectoryError, OWNERS_GROUP, accountOf, authorize, canonicalRole, evaluateRoles, groupsOf, iamResource, personOf, rolesOf, servedRequest, type Directory } from './directory'

/*
 * The identity service's phase 7 and 9 access over time, in the mock (contracts/openapi/
 * console-identity-service.yaml at hub fce1187, through the BFF's /console/v1/iam and
 * /console/v1/organization paths, hub decision 34):
 *
 * - access reviews: a campaign takes everyone's memberships, one item per person and group; the
 *   account's admins keep or remove each (never one about themselves); at the due date what is
 *   still pending expires, which takes the group off the person, except the last owner's owners
 *   group, kept with a comment; one open campaign per account
 * - elevations: a person asks for a role for 5 to 480 minutes with a reason; the first rule that
 *   covers the role, the person's group roles and the duration approves it at once, else the
 *   account's admins (never the requester) decide within a day; while active the role decides
 *   after the person's group roles (`elevated` on what it alone allows), until `until`
 * - break-glass: an organization admin gives a person a role in an account for at most 4 hours,
 *   with a reason and a ticket, approved by the admin calling
 * - closing an account: for good, ending its keys, requests, review and elevations; the BFF
 *   refuses while the account holds resources (409 `account.hasResources`), since closing leaves
 *   them where they are
 *
 * The state lives in the directory (`dir.access`), which the browser keeps across reloads.
 */

export interface AccessState {
  /** by account, the newest first */
  reviews: Record<string, AccessReview[]>
  /** by review id, in the order they were made */
  items: Record<string, AccessReviewItem[]>
  /** by account, the newest first */
  elevations: Record<string, Elevation[]>
  counter: number
}

/** The identity service's elevation settings (application.yaml at console-identity-service--phase-7 e0d18b9). */
export const ELEVATION_RULES: ElevationRules = {
  defaultMinutes: 60,
  maxMinutes: 480,
  pendingFor: 'PT24H',
  breakGlassMaxMinutes: 240,
  items: [{ name: 'operators-admin-two-hours', roles: [ADMIN_ROLE], requesterRoles: ['operator'], maxMinutes: 120 }],
}

/** The people whose sign-in groups make them organization admins in the mock (`console.organization.admin-groups`). */
export const ORGANIZATION_ADMINS: readonly string[] = ['gonzalo']

export function isOrganizationAdmin(userId: string): boolean {
  return ORGANIZATION_ADMINS.includes(userId)
}

const MINUTE = 60_000
const DAY = 86_400_000
const SERVICE = 'svc:console-identity-service'

export function accessOf(dir: Directory): AccessState {
  return (dir.access ??= { reviews: {}, items: {}, elevations: {}, counter: 0 })
}

function nextId(dir: Directory, prefix: string): string {
  const st = accessOf(dir)
  return `${prefix}_${String(++st.counter).padStart(4, '0')}`
}

const iso = (ms: number) => new Date(ms).toISOString()

/* ----------------------------------------------------------------------------
   Access reviews
   ------------------------------------------------------------------------- */

export function countsOf(items: readonly AccessReviewItem[]): AccessReviewCounts {
  const c: AccessReviewCounts = { total: items.length, kept: 0, revoked: 0, expired: 0, pending: 0 }
  for (const i of items) {
    if (i.decision === 'keep') c.kept++
    else if (i.decision === 'revoke') c.revoked++
    else if (i.decision === 'expired') c.expired++
    else c.pending++
  }
  return c
}

function itemsOf(dir: Directory, reviewId: string): AccessReviewItem[] {
  return accessOf(dir).items[reviewId] ?? []
}

function withCounts(dir: Directory, r: AccessReview): AccessReview {
  return { ...r, counts: countsOf(itemsOf(dir, r.reviewId)) }
}

export function reviewsOf(dir: Directory, accountId: string): AccessReview[] {
  return (accessOf(dir).reviews[accountId] ?? []).map((r) => withCounts(dir, r))
}

function storedReview(dir: Directory, accountId: string, reviewId: string): AccessReview {
  const r = (accessOf(dir).reviews[accountId] ?? []).find((x) => x.reviewId === reviewId)
  if (!r) throw new DirectoryError(404, `no access review ${reviewId}`)
  return r
}

export function reviewOf(dir: Directory, accountId: string, reviewId: string): AccessReview {
  return withCounts(dir, storedReview(dir, accountId, reviewId))
}

function bumpUserOf(dir: Directory, accountId: string, userId: string) {
  const k = `${accountId}/${userId}`
  dir.userVersions[k] = (dir.userVersions[k] ?? 1) + 1
}

/** Takes the group off the person, as a removal of the group does; nothing when they no longer hold it. */
function takeGroup(dir: Directory, accountId: string, groupId: string, userId: string) {
  const g = (dir.groups[accountId] ?? []).find((x) => x.groupId === groupId)
  if (!g?.members?.includes(userId)) return
  g.members = g.members.filter((m) => m !== userId)
  g.version++
  bumpUserOf(dir, accountId, userId)
}

/** The item is the account's last owner's owners group: never taken away. */
function isLastOwner(dir: Directory, accountId: string, item: AccessReviewItem): boolean {
  if (item.groupName !== OWNERS_GROUP) return false
  const owners = (dir.groups[accountId] ?? []).find((g) => g.name === OWNERS_GROUP)
  const members = owners?.members ?? []
  return members.length <= 1 && members.includes(item.userId)
}

export function startReview(dir: Directory, accountId: string, by: string, input: AccessReviewInput, now: number): AccessReview {
  const account = accountOf(dir, accountId)
  if (!account) throw new DirectoryError(404, `no account ${accountId}`)
  if (account.status !== 'active') throw new DirectoryError(409, `the account is ${account.status}`)
  const name = input.name === undefined ? `Access review ${iso(now).slice(0, 10)}` : String(input.name).trim()
  if (!name || name.length > 200) throw new DirectoryError(400, 'name is 1 to 200 characters')
  const days = input.dueInDays ?? 14
  if (!Number.isInteger(days) || days < 1 || days > 90) throw new DirectoryError(400, 'dueInDays is a whole number from 1 to 90')
  const open = reviewsOf(dir, accountId).find((r) => r.status === 'open')
  if (open) throw new DirectoryError(409, `the account has an open access review: ${open.name}`, { reviewId: open.reviewId })
  const reviewId = nextId(dir, 'arev')
  const items: AccessReviewItem[] = []
  for (const g of dir.groups[accountId] ?? []) {
    for (const m of g.members ?? []) {
      const p = personOf(dir, m)
      if (!p) continue
      items.push({ itemId: nextId(dir, 'arevi'), reviewId, userId: p.id, email: p.email, name: p.name, groupId: g.groupId, groupName: g.name, roles: [...(g.roles ?? [])], decision: 'pending' })
    }
  }
  const review: AccessReview = {
    reviewId,
    name,
    status: items.length ? 'open' : 'closed',
    trigger: 'manual',
    startedBy: by,
    startedAt: iso(now),
    dueAt: iso(now + days * DAY),
    ...(items.length ? {} : { closedAt: iso(now) }),
    counts: countsOf(items),
    version: 1,
  }
  const st = accessOf(dir)
  st.items[reviewId] = items
  st.reviews[accountId] = [review, ...(st.reviews[accountId] ?? [])]
  return withCounts(dir, review)
}

export function decideItem(dir: Directory, accountId: string, reviewId: string, itemId: string, by: string, decision: 'keep' | 'revoke', comment: string | undefined, now: number): AccessReviewItem {
  const review = storedReview(dir, accountId, reviewId)
  if (comment !== undefined && comment.length > 2000) throw new DirectoryError(400, 'comment is at most 2000 characters')
  const item = itemsOf(dir, reviewId).find((i) => i.itemId === itemId)
  if (!item) throw new DirectoryError(404, `no item ${itemId}`)
  if (review.status !== 'open' || Date.parse(review.dueAt) <= now) throw new DirectoryError(409, `the access review is ${review.status === 'open' ? 'past its due date' : review.status}`)
  if (item.decision !== 'pending') throw new DirectoryError(409, 'this item is decided already')
  if (decision === 'revoke' && isLastOwner(dir, accountId, item)) throw new DirectoryError(409, 'the account’s last owner keeps the owners group')
  if (decision === 'revoke') takeGroup(dir, accountId, item.groupId, item.userId)
  item.decision = decision
  item.decidedBy = by
  item.decidedAt = iso(now)
  item.comment = comment?.trim() || undefined
  review.version++
  if (countsOf(itemsOf(dir, reviewId)).pending === 0) {
    review.status = 'closed'
    review.closedAt = iso(now)
  }
  return item
}

export function cancelReview(dir: Directory, accountId: string, reviewId: string, now: number): AccessReview {
  const review = storedReview(dir, accountId, reviewId)
  if (review.status !== 'open' || Date.parse(review.dueAt) <= now) throw new DirectoryError(409, `the access review is ${review.status === 'open' ? 'past its due date' : review.status}`)
  review.status = 'cancelled'
  review.closedAt = iso(now)
  review.version++
  return withCounts(dir, review)
}

/* ----------------------------------------------------------------------------
   Elevations and break-glass access
   ------------------------------------------------------------------------- */

/** An elevation as served: an active one past `until`, or a pending one past `pendingFor`, is expired. */
export function servedElevation(e: Elevation, now: number): Elevation {
  if (e.status === 'active' && e.until && Date.parse(e.until) <= now) return { ...e, status: 'expired' }
  const wait = isoDurationMs(ELEVATION_RULES.pendingFor) ?? DAY
  if (e.status === 'pending' && Date.parse(e.requestedAt) + wait <= now) return { ...e, status: 'expired', approvers: undefined }
  return e
}

export function elevationsOf(dir: Directory, accountId: string, now: number): Elevation[] {
  return (accessOf(dir).elevations[accountId] ?? []).map((e) => servedElevation(e, now))
}

function storedElevation(dir: Directory, accountId: string, elevationId: string): Elevation {
  const e = (accessOf(dir).elevations[accountId] ?? []).find((x) => x.elevationId === elevationId)
  if (!e) throw new DirectoryError(404, `no elevation ${elevationId}`)
  return e
}

export function elevationOf(dir: Directory, accountId: string, elevationId: string, now: number): Elevation {
  return servedElevation(storedElevation(dir, accountId, elevationId), now)
}

/** The same person's open ask or elevation for the role: the identity service answers 409 with it as the body. */
export class OpenElevationError extends Error {
  constructor(readonly elevation: Elevation) {
    super(`the same role is asked for or held already: ${elevation.elevationId}`)
    this.name = 'OpenElevationError'
  }
}

/** Who may decide an ask: the people whose group roles allow elevation:decide, never the requester. */
export function elevationApproversOf(dir: Directory, accountId: string, requesterId: string): string[] {
  const resource = iamResource(accountId, 'elevation')
  const out = new Set<string>()
  for (const g of dir.groups[accountId] ?? []) {
    if (evaluateRoles(dir, accountId, g.roles ?? [], 'elevation:decide', resource).effect !== 'allow') continue
    for (const m of g.members ?? []) if (m !== requesterId) out.add(m)
  }
  return [...out].sort()
}

function minutesOf(v: unknown, fallback: number, max: number): number {
  const n = v === undefined ? fallback : v
  if (typeof n !== 'number' || !Number.isInteger(n) || n < 5 || n > max) throw new DirectoryError(400, `durationMinutes is a whole number from 5 to ${max}`)
  return n
}

export function askElevation(dir: Directory, accountId: string, userId: string, input: Partial<ElevationInput>, now: number): Elevation {
  const person = personOf(dir, userId)
  if (!person) throw new DirectoryError(404, `no user ${userId}`)
  const role = typeof input.role === 'string' ? canonicalRole(input.role) : ''
  if (!role || !dir.roles.some((r) => r.name === role)) throw new DirectoryError(400, `'${input.role ?? ''}' is no role of this account`)
  const groupRoles = rolesOf(dir, accountId, userId)
  if (groupRoles.includes(role)) throw new DirectoryError(400, `you hold ${role} through a group already`)
  const reason = typeof input.reason === 'string' ? input.reason : ''
  if (!reason.trim() || reason.length > 2000) throw new DirectoryError(400, 'reason is 1 to 2000 characters')
  const minutes = minutesOf(input.durationMinutes, ELEVATION_RULES.defaultMinutes, ELEVATION_RULES.maxMinutes)
  const open = elevationsOf(dir, accountId, now).find((e) => e.user.userId === userId && e.role === role && e.kind === 'elevation' && (e.status === 'pending' || e.status === 'active'))
  if (open) throw new OpenElevationError(open)
  const rule = ELEVATION_RULES.items.find((r) => r.roles.includes(role) && r.requesterRoles.some((q) => groupRoles.includes(q)) && minutes <= r.maxMinutes)
  const base: Elevation = {
    elevationId: nextId(dir, 'elev'),
    accountId,
    kind: 'elevation',
    user: { userId, email: person.email, name: person.name },
    role,
    reason: reason.trim(),
    durationMinutes: minutes,
    status: 'pending',
    requestedAt: iso(now),
    requestedBy: userId,
  }
  const e: Elevation = rule
    ? { ...base, status: 'active', decidedAt: iso(now), decidedBy: `rule:${rule.name}`, startsAt: iso(now), until: iso(now + minutes * MINUTE) }
    : { ...base, approvers: elevationApproversOf(dir, accountId, userId) }
  const st = accessOf(dir)
  st.elevations[accountId] = [e, ...(st.elevations[accountId] ?? [])]
  return e
}

export function decideElevation(dir: Directory, accountId: string, elevationId: string, by: string, decision: 'approve' | 'reject', comment: string | undefined, now: number): Elevation {
  if (comment !== undefined && comment.length > 2000) throw new DirectoryError(400, 'comment is at most 2000 characters')
  const e = storedElevation(dir, accountId, elevationId)
  const status = servedElevation(e, now).status
  if (status !== 'pending') throw new DirectoryError(409, `the ask is ${status}`)
  e.decidedAt = iso(now)
  e.decidedBy = by
  e.comment = comment?.trim() || undefined
  e.approvers = undefined
  if (decision === 'approve') {
    e.status = 'active'
    e.startsAt = iso(now)
    e.until = iso(now + e.durationMinutes * MINUTE)
  } else {
    e.status = 'rejected'
  }
  return e
}

export function revokeElevation(dir: Directory, accountId: string, elevationId: string, by: string, comment: string | undefined, now: number): Elevation {
  const e = storedElevation(dir, accountId, elevationId)
  const status = servedElevation(e, now).status
  if (status !== 'pending' && status !== 'active') throw new DirectoryError(409, `it has ended already (${status})`)
  e.status = status === 'pending' ? 'cancelled' : 'revoked'
  e.endedAt = iso(now)
  e.endedBy = by
  e.endComment = comment?.trim() || undefined
  e.approvers = undefined
  return e
}

export function openBreakGlass(dir: Directory, accountId: string, approvedBy: string, input: Partial<BreakGlassInput>, now: number): Elevation {
  const account = accountOf(dir, accountId)
  if (!account) throw new DirectoryError(404, `no account ${accountId}`)
  const userId = typeof input.userId === 'string' ? input.userId.trim() : ''
  const person = personOf(dir, userId) ?? dir.people.find((p) => p.email.toLowerCase() === userId.toLowerCase())
  if (!person) throw new DirectoryError(404, `no console user ${userId || '(none named)'}`)
  if (person.id === approvedBy) throw new DirectoryError(400, 'the approver is never the person given access')
  const role = canonicalRole(typeof input.role === 'string' && input.role.trim() ? input.role.trim() : ADMIN_ROLE)
  if (!dir.roles.some((r) => r.name === role)) throw new DirectoryError(400, `'${role}' is no role of this account`)
  const reason = typeof input.reason === 'string' ? input.reason.trim() : ''
  if (!reason || reason.length > 2000) throw new DirectoryError(400, 'reason is 1 to 2000 characters')
  const ticket = typeof input.ticket === 'string' && input.ticket.trim() ? input.ticket.trim() : undefined
  if (ticket && ticket.length > 200) throw new DirectoryError(400, 'ticket is at most 200 characters')
  const minutes = minutesOf(input.durationMinutes, 60, ELEVATION_RULES.breakGlassMaxMinutes)
  if (account.status !== 'active') throw new DirectoryError(409, `the account is ${account.status}`)
  const open = elevationsOf(dir, accountId, now).find((e) => e.user.userId === person.id && e.role === role && e.kind === 'break-glass' && e.status === 'active')
  if (open) throw new OpenElevationError(open)
  const e: Elevation = {
    elevationId: nextId(dir, 'elev'),
    accountId,
    kind: 'break-glass',
    user: { userId: person.id, email: person.email, name: person.name },
    role,
    reason,
    ...(ticket ? { ticket } : {}),
    durationMinutes: minutes,
    status: 'active',
    requestedAt: iso(now),
    requestedBy: 'svc:console-bff',
    decidedAt: iso(now),
    decidedBy: approvedBy,
    startsAt: iso(now),
    until: iso(now + minutes * MINUTE),
  }
  const st = accessOf(dir)
  st.elevations[accountId] = [e, ...(st.elevations[accountId] ?? [])]
  return e
}

/* ----------------------------------------------------------------------------
   The sweep: due campaigns close, ended elevations are stored expired
   ------------------------------------------------------------------------- */

export function settleAccess(dir: Directory, now: number) {
  const st = dir.access
  if (!st) return
  for (const [accountId, reviews] of Object.entries(st.reviews)) {
    for (const r of reviews) {
      if (r.status !== 'open' || Date.parse(r.dueAt) > now) continue
      for (const item of st.items[r.reviewId] ?? []) {
        if (item.decision !== 'pending') continue
        item.decidedBy = SYSTEM_REVIEWER
        item.decidedAt = r.dueAt
        if (isLastOwner(dir, accountId, item)) {
          item.decision = 'keep'
          item.comment = 'Kept: the account’s last owner keeps the owners group.'
        } else {
          item.decision = 'expired'
          takeGroup(dir, accountId, item.groupId, item.userId)
        }
      }
      r.status = 'closed'
      r.closedAt = r.dueAt
      r.version++
    }
  }
  for (const list of Object.values(st.elevations)) {
    for (const e of list) {
      const served = servedElevation(e, now)
      if (served.status !== e.status) {
        e.status = served.status
        e.approvers = undefined
      }
    }
  }
}

/* ----------------------------------------------------------------------------
   Closing an account (the identity service's part)
   ------------------------------------------------------------------------- */

export function closeAccount(dir: Directory, accountId: string, reason: string, now: number) {
  const account = accountOf(dir, accountId)
  if (!account) throw new DirectoryError(404, `no account ${accountId}`)
  if (account.status === 'closed') throw new DirectoryError(409, 'the account is closed already')
  if (!reason.trim() || reason.length > 2000) throw new DirectoryError(400, 'reason is 1 to 2000 characters')
  for (const k of dir.accessKeys[accountId] ?? []) k.status = 'revoked'
  for (const r of dir.accessRequests[accountId] ?? []) if (servedRequest(r, now).status === 'pending') r.status = 'expired'
  for (const q of dir.quotaRequests[accountId] ?? []) {
    if (q.status !== 'open') continue
    q.status = 'withdrawn'
    q.decidedAt = iso(now)
    q.decidedBy = SERVICE
  }
  for (const r of accessOf(dir).reviews[accountId] ?? []) {
    if (r.status !== 'open') continue
    r.status = 'cancelled'
    r.closedAt = iso(now)
    r.version++
  }
  for (const e of accessOf(dir).elevations[accountId] ?? []) {
    const status = servedElevation(e, now).status
    if (status !== 'pending' && status !== 'active') continue
    e.status = status === 'pending' ? 'cancelled' : 'revoked'
    e.endedAt = iso(now)
    e.endedBy = SERVICE
    e.approvers = undefined
  }
  account.status = 'closed'
  account.closedAt = iso(now)
  account.closeReason = reason.trim()
}

/** An account as the organization admins' list shows it (identity `Account`). */
function organizationAccountOf(dir: Directory, accountId: string) {
  const a = accountOf(dir, accountId)!
  return {
    accountId: a.accountId,
    name: a.name,
    status: a.status,
    owningTeam: a.owningTeam,
    defaultRegion: a.defaultRegion,
    version: 1,
    createdAt: a.createdAt,
    ...(a.closedAt ? { closedAt: a.closedAt, closeReason: a.closeReason, updatedAt: a.closedAt } : {}),
  }
}

/* ----------------------------------------------------------------------------
   The BFF's routes
   ------------------------------------------------------------------------- */

function str(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined
}

function refusal(denied: Denial): RouteAnswer {
  return { status: 403, body: { error: denied.reason, denied, correlationId: `mock-access-${denied.action}` } }
}

function ownRefusal(action: string, resource: string, reason: string): RouteAnswer {
  return refusal({ layer: 'console-iam', action, resource, reason, requestable: false })
}

function caught(fn: () => RouteAnswer): RouteAnswer {
  try {
    return fn()
  } catch (e) {
    if (e instanceof OpenElevationError) return { status: 409, body: e.elevation }
    throw e
  }
}

/**
 * The BFF's routes for access reviews, elevations, break-glass and the organization's accounts,
 * or null when the path is someone else's. Errors of the directory propagate as `DirectoryError`,
 * which the server turns into the status and its words.
 */
export function accessRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!path.startsWith('/console/v1/iam/access-reviews') && !path.startsWith('/console/v1/iam/elevation') && !path.startsWith('/console/v1/organization/accounts')) return null
  const dir = data.directory
  const s = ctx.session
  const now = ctx.now
  const body = ctx.body
  const iam = (kind: string, id?: string) => iamResource(s.accountId, kind, id)
  const can = (action: string, resource: string) => authorize(dir, { accountId: s.accountId, userId: s.userId, now }, action, resource)
  settleAccess(dir, now)

  /* elevation rules: anyone signed in */
  if (path === '/console/v1/iam/elevation-rules' && method === 'GET') return { status: 200, body: ELEVATION_RULES }

  /* access reviews */
  if (path === '/console/v1/iam/access-reviews') {
    if (method === 'GET') {
      const d = can('access-review:list', iam('access-review'))
      return d.allowed ? pageOf(reviewsOf(dir, s.accountId), params) : refusal(d.denied!)
    }
    if (method === 'POST') {
      const d = can('access-review:create', iam('access-review'))
      if (!d.allowed) return refusal(d.denied!)
      const days = body.dueInDays
      return { status: 201, body: startReview(dir, s.accountId, s.userId, { name: str(body.name), dueInDays: days === undefined ? undefined : (days as number) }, now) }
    }
  }
  let m = /^\/console\/v1\/iam\/access-reviews\/([^/:]+)(?::(cancel))?$/.exec(path)
  if (m) {
    const reviewId = decodeURIComponent(m[1])
    if (method === 'GET' && !m[2]) {
      const d = can('access-review:get', iam('access-review', reviewId))
      return d.allowed ? { status: 200, body: reviewOf(dir, s.accountId, reviewId) } : refusal(d.denied!)
    }
    if (method === 'POST' && m[2] === 'cancel') {
      const d = can('access-review:cancel', iam('access-review', reviewId))
      return d.allowed ? { status: 200, body: cancelReview(dir, s.accountId, reviewId, now) } : refusal(d.denied!)
    }
  }
  m = /^\/console\/v1\/iam\/access-reviews\/([^/:]+)\/items$/.exec(path)
  if (m && method === 'GET') {
    const reviewId = decodeURIComponent(m[1])
    const d = can('access-review:get', iam('access-review', reviewId))
    if (!d.allowed) return refusal(d.denied!)
    reviewOf(dir, s.accountId, reviewId)
    const decision = params.get('decision')
    if (decision && !['pending', 'keep', 'revoke', 'expired'].includes(decision)) return { status: 400, body: { error: 'decision is pending, keep, revoke or expired' } }
    return pageOf(
      itemsOf(dir, reviewId).filter((i) => !decision || i.decision === decision),
      params,
    )
  }
  m = /^\/console\/v1\/iam\/access-reviews\/([^/:]+)\/items\/([^/:]+):decide$/.exec(path)
  if (m && method === 'POST') {
    const reviewId = decodeURIComponent(m[1])
    const itemId = decodeURIComponent(m[2])
    const resource = iam('access-review', reviewId)
    const d = can('access-review:decide', resource)
    if (!d.allowed) return refusal(d.denied!)
    const item = itemsOf(dir, reviewId).find((i) => i.itemId === itemId)
    if (item && item.userId === s.userId) return ownRefusal('access-review:decide', resource, 'nobody reviews their own access')
    const decision = body.decision === 'keep' ? 'keep' : body.decision === 'revoke' ? 'revoke' : null
    if (!decision) return { status: 400, body: { error: 'decision is keep or revoke' } }
    return { status: 200, body: decideItem(dir, s.accountId, reviewId, itemId, s.userId, decision, str(body.comment), now) }
  }

  /* elevations: everyone sees their own; the account's take elevation:list */
  if (path === '/console/v1/iam/elevations') {
    if (method === 'GET') {
      const status = params.get('status') as ElevationStatus | null
      const kind = params.get('kind')
      const wanted = params.get('userId') ?? undefined
      const reads = can('elevation:list', iam('elevation'))
      let only = wanted
      if (!reads.allowed) {
        if (wanted && wanted !== s.userId) return refusal(reads.denied!)
        only = s.userId
      }
      return pageOf(
        elevationsOf(dir, s.accountId, now).filter((e) => (!only || e.user.userId === only) && (!status || e.status === status) && (!kind || e.kind === kind)),
        params,
      )
    }
    if (method === 'POST') {
      const d = can('elevation:create', iam('elevation'))
      if (!d.allowed) return refusal(d.denied!)
      return caught(() => ({
        status: 201,
        body: askElevation(dir, s.accountId, s.userId, { role: str(body.role), reason: str(body.reason), durationMinutes: body.durationMinutes as number | undefined }, now),
      }))
    }
  }
  m = /^\/console\/v1\/iam\/elevations\/([^/:]+)(?::(decide|revoke))?$/.exec(path)
  if (m) {
    const elevationId = decodeURIComponent(m[1])
    const resource = iam('elevation', elevationId)
    const e = elevationOf(dir, s.accountId, elevationId, now)
    const own = e.user.userId === s.userId
    if (method === 'GET' && !m[2]) {
      if (!own) {
        const d = can('elevation:get', resource)
        if (!d.allowed) return refusal(d.denied!)
      }
      return { status: 200, body: e }
    }
    if (method === 'POST' && m[2] === 'decide') {
      const d = can('elevation:decide', resource)
      if (!d.allowed) return refusal(d.denied!)
      if (own) return ownRefusal('elevation:decide', resource, 'nobody decides their own elevation')
      const decision = body.decision === 'approve' ? 'approve' : body.decision === 'reject' ? 'reject' : null
      if (!decision) return { status: 400, body: { error: 'decision is approve or reject' } }
      return { status: 200, body: decideElevation(dir, s.accountId, elevationId, s.userId, decision, str(body.comment), now) }
    }
    if (method === 'POST' && m[2] === 'revoke') {
      if (!own) {
        const d = can('elevation:revoke', resource)
        if (!d.allowed) return refusal(d.denied!)
      }
      return { status: 200, body: revokeElevation(dir, s.accountId, elevationId, s.userId, str(body.comment), now) }
    }
  }

  /* the organization's accounts: organization admins only (404 for anyone else) */
  if (path.startsWith('/console/v1/organization/accounts')) {
    if (!isOrganizationAdmin(s.userId)) return { status: 404, body: { error: 'not found' } }
    if (path === '/console/v1/organization/accounts' && method === 'GET')
      return pageOf(
        dir.accounts.map((a) => organizationAccountOf(dir, a.accountId)),
        params,
      )
    m = /^\/console\/v1\/organization\/accounts\/(\d{12}):close$/.exec(path)
    if (m && method === 'POST') {
      const accountId = m[1]
      if (!accountOf(dir, accountId)) return { status: 404, body: { error: `no account ${accountId}` } }
      if (accountOf(dir, accountId)!.status === 'closed') return { status: 409, body: { error: 'the account is closed already' } }
      // the BFF's own rule (plan, WP 7.7): closing leaves resources where they are, so an account that holds any is refused
      const byType = usedByTypeOf(data, accountId)
      const total = Object.values(byType).reduce((a, b) => a + b, 0)
      if (total > 0) return { status: 409, body: { error: 'account.hasResources', detail: { total, byType } } }
      closeAccount(dir, accountId, str(body.reason) ?? '', now)
      return { status: 200, body: organizationAccountOf(dir, accountId) }
    }
    m = /^\/console\/v1\/organization\/accounts\/(\d{12})\/break-glass$/.exec(path)
    if (m && method === 'POST') {
      return caught(() => ({
        status: 201,
        body: openBreakGlass(
          dir,
          m![1],
          s.userId,
          { userId: str(body.userId), role: str(body.role), reason: str(body.reason), ticket: str(body.ticket), durationMinutes: body.durationMinutes as number | undefined },
          now,
        ),
      }))
    }
  }
  return null
}

/** Whether a person holds a group in an account, for the tests. */
export function holdsGroup(dir: Directory, accountId: string, userId: string, groupName: string): boolean {
  return groupsOf(dir, accountId, userId).some((g) => g.name === groupName)
}
