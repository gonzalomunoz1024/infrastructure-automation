import { get, page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'

/*
 * Access reviews (WP 9.11): campaigns in which the account's admins keep or remove who holds
 * which group. The identity service takes everyone's memberships when a campaign starts, one item
 * per person and group; an item nobody decided by the due date expires, which takes the group off
 * the person as a removal would. The BFF passes the identity service's bodies through under
 * /console/v1/iam/access-reviews (hub decision 34).
 */

export type AccessReviewStatus = 'open' | 'closed' | 'cancelled'
export type AccessReviewDecision = 'pending' | 'keep' | 'revoke' | 'expired'

export interface AccessReviewCounts {
  total: number
  kept: number
  revoked: number
  expired: number
  pending: number
}

export interface AccessReview {
  reviewId: string
  name: string
  status: AccessReviewStatus
  trigger: 'manual' | 'scheduled'
  /** a user id, or `system:access-review` for a scheduled campaign */
  startedBy: string
  startedAt: string
  dueAt: string
  closedAt?: string
  counts: AccessReviewCounts
  version: number
}

export interface AccessReviewItem {
  itemId: string
  reviewId: string
  userId: string
  email?: string
  name?: string
  groupId: string
  groupName: string
  /** the role names the group carried when the campaign started */
  roles: string[]
  decision: AccessReviewDecision
  decidedBy?: string
  decidedAt?: string
  comment?: string
}

export interface AccessReviewInput {
  name?: string
  /** 1 to 90, 14 by default */
  dueInDays?: number
}

export const SYSTEM_REVIEWER = 'system:access-review'
export const DEFAULT_DUE_DAYS = 14
export const MAX_DUE_DAYS = 90

const b = (path: string) => `/console/v1/iam${path}`
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const reviewsApi = {
  list: (params?: ListParams) => page<AccessReview>(b('/access-reviews'), params),
  get: (reviewId: string) => get<AccessReview>(b(`/access-reviews/${enc(reviewId)}`)),
  /** 409 names the open campaign in `detail.reviewId` */
  start: (body: AccessReviewInput, key?: string) => request<AccessReview>('POST', b('/access-reviews'), body, keyed(key)),
  items: (reviewId: string, params?: ListParams & { decision?: AccessReviewDecision }) => page<AccessReviewItem>(b(`/access-reviews/${enc(reviewId)}/items`), params),
  decide: (reviewId: string, itemId: string, body: { decision: 'keep' | 'revoke'; comment?: string }, key?: string) =>
    request<AccessReviewItem>('POST', b(`/access-reviews/${enc(reviewId)}/items/${enc(itemId)}:decide`), body, keyed(key)),
  cancel: (reviewId: string, key?: string) => request<AccessReview>('POST', b(`/access-reviews/${enc(reviewId)}:cancel`), undefined, keyed(key)),
}

/** Whether a campaign still takes decisions: open and not past its due date. */
export function isOpen(r: Pick<AccessReview, 'status' | 'dueAt'>, now = Date.now()): boolean {
  return r.status === 'open' && Date.parse(r.dueAt) > now
}

/** How far through a campaign is: the share decided, 0 to 100. */
export function progressOf(c: AccessReviewCounts): number {
  if (c.total === 0) return 100
  return Math.round(((c.total - c.pending) / c.total) * 100)
}

/** The due date in words, with what it does: "Due 6 November: anything not reviewed by then loses its access." */
export function dueWords(dueAt: string, locale?: string): string {
  return new Date(dueAt).toLocaleDateString(locale, { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
}

export const DECISION_WORDS: Record<AccessReviewDecision, { label: string; status: string }> = {
  pending: { label: 'Not reviewed', status: 'waiting' },
  keep: { label: 'Kept', status: 'succeeded' },
  revoke: { label: 'Removed', status: 'failed' },
  expired: { label: 'Expired', status: 'cancelled' },
}

export const REVIEW_STATUS_WORDS: Record<AccessReviewStatus, { label: string; status: string }> = {
  open: { label: 'Open', status: 'running' },
  closed: { label: 'Closed', status: 'succeeded' },
  cancelled: { label: 'Cancelled', status: 'cancelled' },
}

/** Who decided, in words: a person's name, or the due date. */
export function deciderWords(by: string | undefined, nameOf: (userId: string) => string): string {
  if (!by) return ''
  if (by === SYSTEM_REVIEWER) return 'the due date'
  return nameOf(by)
}

/** The items a person may decide: still pending, and never about themselves. */
export function decidableBy(items: readonly AccessReviewItem[], userId: string): AccessReviewItem[] {
  return items.filter((i) => i.decision === 'pending' && i.userId !== userId)
}
