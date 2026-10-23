import { get, page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'
import { ADMIN_ROLE } from './session'
import type { Denial, HeldElevation } from './types'

/*
 * Just-in-time elevation (WP 9.12) and break-glass access (WP 7.7): a role held for a while. A
 * person asks for a role with a reason and a duration; the first auto-approval rule that covers
 * the role, the roles the person holds through their groups and the duration approves it at once,
 * otherwise an account admin who is not the person decides. While active the role decides as one
 * from a group does, and the decision says `elevated` when only the held role allowed it. An
 * organization admin gives break-glass access for support or an emergency, at most four hours,
 * with a ticket; it shows on the account's own trail. Both end at `until`, or earlier by hand.
 */

export type ElevationStatus = 'pending' | 'active' | 'rejected' | 'cancelled' | 'revoked' | 'expired'
export type ElevationKind = 'elevation' | 'break-glass'

export interface Elevation {
  elevationId: string
  accountId: string
  kind: ElevationKind
  user: { userId: string; email?: string; name?: string }
  role: string
  reason: string
  ticket?: string
  durationMinutes: number
  status: ElevationStatus
  requestedAt: string
  /** the person, or `svc:<name>` for break-glass access */
  requestedBy: string
  decidedAt?: string
  /** a user, `rule:<name>`, or the approver of break-glass access */
  decidedBy?: string
  comment?: string
  startsAt?: string
  until?: string
  endedAt?: string
  endedBy?: string
  endComment?: string
  /** while pending: who may decide it, never the requester */
  approvers?: string[]
}

export interface ElevationRule {
  name: string
  roles: string[]
  requesterRoles: string[]
  maxMinutes: number
}

export interface ElevationRules {
  defaultMinutes: number
  maxMinutes: number
  /** ISO 8601, such as PT24H */
  pendingFor: string
  breakGlassMaxMinutes: number
  items: ElevationRule[]
}

export interface ElevationInput {
  role: string
  reason: string
  durationMinutes?: number
}

export interface BreakGlassInput {
  userId: string
  role?: string
  reason: string
  durationMinutes?: number
  ticket?: string
}

export const MIN_MINUTES = 5
export const DEFAULT_MINUTES = 60
export const RULE_PREFIX = 'rule:'

const b = (path: string) => `/console/v1${path}`
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const elevationsApi = {
  rules: () => get<ElevationRules>(b('/iam/elevation-rules')),
  /** a person always sees their own; the account's need elevation:list */
  list: (params?: ListParams & { status?: ElevationStatus; kind?: ElevationKind; userId?: string }) => page<Elevation>(b('/iam/elevations'), params),
  get: (elevationId: string) => get<Elevation>(b(`/iam/elevations/${enc(elevationId)}`)),
  /** 409 answers the open one for the same role */
  ask: (body: ElevationInput, key?: string) => request<Elevation>('POST', b('/iam/elevations'), body, keyed(key)),
  decide: (elevationId: string, body: { decision: 'approve' | 'reject'; comment?: string }, key?: string) =>
    request<Elevation>('POST', b(`/iam/elevations/${enc(elevationId)}:decide`), body, keyed(key)),
  /** ends a pending ask (cancelled) or an active one (revoked) now */
  revoke: (elevationId: string, body?: { comment?: string }, key?: string) => request<Elevation>('POST', b(`/iam/elevations/${enc(elevationId)}:revoke`), body ?? {}, keyed(key)),
  /** organization admins only, after a fresh sign-in; the BFF sets approvedBy to the caller */
  breakGlass: (accountId: string, body: BreakGlassInput, key?: string) => request<Elevation>('POST', b(`/organization/accounts/${enc(accountId)}/break-glass`), body, keyed(key)),
}

/** `admin` reads as account-admin, as the identity service reads a role named in an ask. */
export function canonicalRole(role: string): string {
  return role === 'admin' ? ADMIN_ROLE : role
}

/** The rule that would approve an ask at once, if any: the first whose roles hold the role, whose requester roles meet the person's own, and whose limit covers the duration. */
export function ruleFor(rules: ElevationRules | undefined, role: string, groupRoles: readonly string[], minutes: number): ElevationRule | undefined {
  const r = canonicalRole(role)
  return rules?.items.find((x) => x.roles.includes(r) && x.requesterRoles.some((q) => groupRoles.includes(q)) && minutes <= x.maxMinutes)
}

/** The roles a person holds through their groups: the session's roles less the ones held for a while. */
export function groupRolesOf(roles: readonly string[] | undefined, held: readonly HeldElevation[] | undefined): string[] {
  const timed = new Set((held ?? []).map((h) => h.role))
  return (roles ?? []).filter((r) => !timed.has(r))
}

/** The role a refusal suggests asking for a while, when one can be: a console IAM refusal by a policy that names a role the person does not hold. */
export function elevatableRole(d: Denial, roles: readonly string[] | undefined): string | null {
  if (d.layer !== 'console-iam' || !d.requestable || !d.suggestedRole) return null
  // a boundary or a guardrail refuses whatever role is held (the BFF's `refusedBy`, decision 34)
  const by = (d as Denial & { refusedBy?: string }).refusedBy
  if (by === 'boundary' || by === 'guardrail') return null
  if ((roles ?? []).includes(d.suggestedRole)) return null
  return d.suggestedRole
}

/** An ISO 8601 duration such as PT24H or P1D in milliseconds; null when unreadable. */
export function isoDurationMs(iso: string | undefined): number | null {
  const m = iso ? /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso) : null
  if (!m || (!m[1] && !m[2] && !m[3] && !m[4])) return null
  return (Number(m[1] ?? 0) * 24 * 60 * 60 + Number(m[2] ?? 0) * 60 * 60 + Number(m[3] ?? 0) * 60 + Number(m[4] ?? 0)) * 1000
}

/** Minutes in words: "45 minutes", "1 hour", "2 hours 30 minutes". */
export function minutesWords(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  const hours = h ? `${h} hour${h === 1 ? '' : 's'}` : ''
  const mins = m ? `${m} minute${m === 1 ? '' : 's'}` : ''
  return [hours, mins].filter(Boolean).join(' ') || '0 minutes'
}

/** What is left before `until`, in words: "52 minutes left", "1 hour 5 minutes left", "less than a minute left". */
export function timeLeftWords(until: string, now: number): string {
  const ms = Date.parse(until) - now
  if (ms <= 0) return 'ended'
  if (ms < 60_000) return 'less than a minute left'
  return `${minutesWords(Math.floor(ms / 60_000))} left`
}

/** The status as the reader should see it: an active one past `until` has ended. */
export function servedStatus(e: Pick<Elevation, 'status' | 'until'>, now: number): ElevationStatus {
  return e.status === 'active' && e.until && Date.parse(e.until) <= now ? 'expired' : e.status
}

export const ELEVATION_STATUS_WORDS: Record<ElevationStatus, { label: string; status: string }> = {
  pending: { label: 'Waiting for approval', status: 'waiting' },
  active: { label: 'Active', status: 'running' },
  rejected: { label: 'Rejected', status: 'failed' },
  cancelled: { label: 'Cancelled', status: 'cancelled' },
  revoked: { label: 'Revoked', status: 'cancelled' },
  expired: { label: 'Expired', status: 'cancelled' },
}

/** Who approved, in words: a person by name, or the rule that approved it at once. */
export function approvedByWords(by: string | undefined, nameOf: (userId: string) => string): string {
  if (!by) return ''
  if (by.startsWith(RULE_PREFIX)) return `the rule ${by.slice(RULE_PREFIX.length)}`
  if (by.startsWith('svc:')) return 'the console'
  return nameOf(by)
}

/** The earliest time a held role ends, when the session holds any. */
export function earliestEnd(held: readonly HeldElevation[] | undefined): number | null {
  const ends = (held ?? []).map((h) => Date.parse(h.until)).filter((t) => Number.isFinite(t))
  return ends.length ? Math.min(...ends) : null
}
