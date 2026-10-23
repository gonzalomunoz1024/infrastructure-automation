import { get, page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'
import type { ConsolePreview, Denial, Quota, QuotaRequest, QuotaRequestInput, QuotaRequestStatus } from './types'

/*
 * Service Quotas through the BFF (identity contract, tag Quotas, under `/console/v1/iam`): the
 * account's quotas and their use, the organization's auto-approval rules, the account's increase
 * requests with what the person may do with each, the ask, the platform team's decision and the
 * withdrawal. The rules are read the way the identity service reads them (the first rule that
 * covers a key and allows the ask approves it at once; once a rule raised a key, none raises it
 * again until its cooldown passes), so the request dialog says before sending what will happen.
 * The words the pages use for them live here too.
 */

const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/* ----------------------------------------------------------------------------
   The contract's shapes
   ------------------------------------------------------------------------- */

/** One auto-approval rule: the keys it covers (`*` is any run of characters), how much it gives at once, and how often. */
export interface QuotaRule {
  name: string
  keys: string[]
  /** the highest ask it approves, as a multiple of the current limit */
  maxFactor: number
  /** the highest limit it approves at all */
  maxLimit: number
  /** an ISO 8601 duration (`PT720H`): after a rule raised an account's key, no rule raises it again until it passes */
  cooldown?: string
}

/** `GET /console/v1/iam/quota-rules`: the rules in the order they are read, and who decides the rest. */
export interface QuotaRules {
  items: QuotaRule[]
  /** `platform-team` */
  approver: string
}

export type QuotaRequestVerb = 'decide' | 'withdraw'

/** A request as the BFF serves it to the console: the identity service's, with what this person may do with it and, for what they may not, why. */
export interface ConsoleQuotaRequest extends QuotaRequest {
  can?: QuotaRequestVerb[]
  refusals?: Partial<Record<QuotaRequestVerb, Denial>>
}

/** The body of `:decide`: approving grants the ask unless `limit` says otherwise. */
export interface QuotaDecisionInput {
  decision: 'approve' | 'reject'
  limit?: number
  comment?: string
}

export interface QuotaRequestListParams extends ListParams {
  status?: QuotaRequestStatus
  quotaKey?: string
}

const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const quotasApi = {
  list: () => page<Quota>(b('/iam/quotas')),
  rules: () => get<QuotaRules>(b('/iam/quota-rules')),
  /** the account's requests, newest first */
  requests: (params?: QuotaRequestListParams) => page<ConsoleQuotaRequest>(b('/iam/quota-requests'), params),
  request: (requestId: string) => get<ConsoleQuotaRequest>(b(`/iam/quota-requests/${enc(requestId)}`)),
  /** approved at once by a rule, or open and routed to the platform team; 409 while one is open for the key */
  requestIncrease: (quotaKey: string, body: QuotaRequestInput, key?: string) => request<QuotaRequest>('POST', b(`/iam/quotas/${enc(quotaKey)}:request-increase`), body, keyed(key)),
  /** the platform team's decision; never the requester's own */
  decide: (requestId: string, body: QuotaDecisionInput, key?: string) => request<ConsoleQuotaRequest>('POST', b(`/iam/quota-requests/${enc(requestId)}:decide`), body, keyed(key)),
  /** by its requester or an admin, while it is open */
  withdraw: (requestId: string) => request<void>('DELETE', b(`/iam/quota-requests/${enc(requestId)}`), undefined, keyed()),
}

/* ----------------------------------------------------------------------------
   The rules, read as the identity service reads them
   ------------------------------------------------------------------------- */

/** `PT720H`, `P30D`, `P1DT12H` → milliseconds; anything else is no cooldown. */
export function durationMs(iso: string | undefined): number {
  const m = iso ? /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(iso) : null
  if (!m) return 0
  return ((Number(m[1] ?? 0) * 24 + Number(m[2] ?? 0)) * 60 + Number(m[3] ?? 0)) * 60_000
}

/** `resources.*` covers `resources.vm`; a pattern without `*` covers its key alone. */
export function keyMatches(pattern: string, key: string): boolean {
  const parts = pattern.split('*')
  if (parts.length === 1) return pattern === key
  if (!key.startsWith(parts[0]) || !key.endsWith(parts[parts.length - 1])) return false
  let at = parts[0].length
  for (const part of parts.slice(1, -1)) {
    const i = key.indexOf(part, at)
    if (i < 0) return false
    at = i + part.length
  }
  return at <= key.length - parts[parts.length - 1].length
}

/** The first rule that covers a key: the one the page shows beside the quota. */
export function ruleCovering(rules: QuotaRules | undefined, key: string): QuotaRule | undefined {
  return rules?.items.find((r) => r.keys.some((p) => keyMatches(p, key)))
}

/** The most a rule gives at once from the current limit. */
export function ceilingOf(rule: QuotaRule, currentLimit: number): number {
  return Math.min(rule.maxLimit, Math.floor(currentLimit * rule.maxFactor))
}

/** The last time a rule raised the key, from the account's requests. */
export function lastRuleRaise(requests: readonly QuotaRequest[], key: string): QuotaRequest | undefined {
  return requests.filter((r) => r.quotaKey === key && r.status === 'approved' && r.decidedBy?.startsWith('rule:') && r.decidedAt).sort((x, y) => (y.decidedAt ?? '').localeCompare(x.decidedAt ?? ''))[0]
}

/** Until when no rule raises the key again, when a rule raised it within its cooldown. */
export function cooldownUntil(rule: QuotaRule, requests: readonly QuotaRequest[], key: string, now = Date.now()): number | undefined {
  const last = lastRuleRaise(requests, key)
  if (!last?.decidedAt) return undefined
  const until = Date.parse(last.decidedAt) + durationMs(rule.cooldown)
  return until > now ? until : undefined
}

/** The rule that approves an ask at once, if any: the first covering the key whose factor, ceiling and cooldown allow it. */
export function approvingRule(rules: QuotaRules | undefined, requests: readonly QuotaRequest[], key: string, currentLimit: number, asked: number, now = Date.now()): QuotaRule | undefined {
  for (const rule of rules?.items ?? []) {
    if (!rule.keys.some((p) => keyMatches(p, key))) continue
    if (asked > ceilingOf(rule, currentLimit)) continue
    if (cooldownUntil(rule, requests, key, now) !== undefined) continue
    return rule
  }
  return undefined
}

/* ----------------------------------------------------------------------------
   Words
   ------------------------------------------------------------------------- */

const n = (v: number) => v.toLocaleString('en-US')

/** 'resources.vm' → the type's title, 'Virtual machines' (a type the console does not know: 'Resources of type vm'); 'jobs-per-minute' → 'Jobs per minute'. */
export function quotaTitle(key: string, types: readonly { name: string; title: string }[] = []): string {
  if (key.startsWith('resources.')) {
    const name = key.slice('resources.'.length)
    return types.find((t) => t.name === name)?.title ?? `Resources of type ${name}`
  }
  return key.replace(/[-_]/g, ' ').replace(/^./, (c) => c.toUpperCase())
}

/** `PT720H` → '30 days', `PT168H` → '7 days', `PT12H` → '12 hours'. */
export function durationWords(iso: string | undefined): string | undefined {
  const ms = durationMs(iso)
  if (!ms) return undefined
  const hours = Math.round(ms / 3_600_000)
  if (hours % 24 === 0) return hours === 24 ? '1 day' : `${hours / 24} days`
  return hours === 1 ? '1 hour' : `${hours} hours`
}

function factorWords(f: number): string {
  if (f === 2) return 'twice'
  if (f === 1.5) return 'half as much again as'
  return `${f} times`
}

/** What a rule gives, in one sentence: 'Raised at once to up to twice the limit, 1,000 at most, once every 30 days.' */
export function ruleSentence(rule: QuotaRule): string {
  const every = durationWords(rule.cooldown)
  return `Raised at once to up to ${factorWords(rule.maxFactor)} the limit, ${n(rule.maxLimit)} at most${every ? `, once every ${every}` : ''}.`
}

/** 'platform-team' → 'the platform team'. */
export function approverWords(approver: string | undefined): string {
  return (approver ?? 'platform-team').replace(/-/g, ' ').replace(/^/, 'the ')
}

/** A request's status as a status word the badge colours, and its label. */
export function requestStatusOf(r: Pick<QuotaRequest, 'status' | 'routedTo'>): { status: string; label: string } {
  switch (r.status) {
    case 'open':
      return { status: 'waiting', label: `Waiting for ${approverWords(r.routedTo)}` }
    case 'approved':
      return { status: 'approved', label: 'Approved' }
    case 'rejected':
      return { status: 'rejected', label: 'Rejected' }
    default:
      return { status: 'cancelled', label: 'Withdrawn' }
  }
}

/** Who decided, in words: a rule, a person by name, or a service. */
export function decidedByWords(r: Pick<QuotaRequest, 'status' | 'decidedBy'>, nameOf: (userId: string) => string): string | undefined {
  const by = r.decidedBy
  if (!by) return undefined
  if (by.startsWith('rule:')) return `the rule ${by.slice('rule:'.length)}, at once`
  if (by.startsWith('svc:')) return `the ${by.slice('svc:'.length).replace(/-/g, ' ')} service`
  return nameOf(by)
}

/** A limit above the current one to offer first: what the covering rule gives at once, else half as much again in a round number. */
export function suggestedLimit(limit: number, rule?: QuotaRule, cooling = false): number {
  if (rule && !cooling) {
    const ceiling = ceilingOf(rule, limit)
    if (ceiling > limit) return ceiling
  }
  const step = limit >= 1000 ? 100 : limit >= 100 ? 10 : 1
  return Math.max(limit + 1, Math.ceil((limit * 1.5) / step) * step)
}

/** The quota key a create preview's quota rule refused, if it refused one: `resources.<type>`. */
export function quotaRefusalOf(preview: Pick<ConsolePreview, 'policy'> | undefined, typeName: string): { key: string; message: string } | null {
  const rule = preview?.policy.rules?.find((r) => r.id === 'quota.resources' && r.result === 'deny')
  return rule ? { key: `resources.${typeName}`, message: rule.message ?? '' } : null
}
