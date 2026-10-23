import { get, isApiError, request } from './api'
import { idempotencyKey } from './bff'
import { partsOfWfrn } from './descriptors'
import type { Decision, Denial } from './permissions'

/*
 * Moving a resource to another account (WP 7.7, hub decision 30): the BFF's
 * `POST /console/v1/r/{type}/{wfrn}:transfer` and what the page needs to offer it. The move
 * needs `<service>:transfer` on the resource in its own account and `<service>:create` on the type
 * in the target account, `confirm: true` and a fresh sign-in. The answer is the resource under its
 * new wfrn; from then on the old wfrn answers 410 `resource.transferred` with `transferredTo`, and
 * its page says where the resource went.
 */

/** An account the resource could move to, and whether this person may create the type there. */
export interface TransferTarget {
  accountId: string
  name: string
  /** the account the resource is in now: never a target */
  current: boolean
  allowed: boolean
  denied?: Denial
}

/** `GET /console/v1/r/{type}/{wfrn}/transfer-targets`: the source's decision and the person's other accounts. */
export interface TransferOptions {
  wfrn: string
  name?: string
  /** `<service>:transfer` on the resource, in its own account */
  source: Decision
  targets: TransferTarget[]
}

/** The body of `:transfer` (hub TransferInput). */
export interface TransferInput {
  toAccountId: string
  reason?: string
  confirm: true
}

/** The 201: the resource under its new wfrn, as the state service answers it. */
export interface TransferAnswer {
  wfrn: string
  accountId?: string
  name?: string
  transferredFrom?: string
  createdAt?: string
}

/** What a 410 `resource.transferred` says: the wfrn the resource lives at now. */
export interface MovedTo {
  wfrn?: string
  transferredTo: string
  accountId: string
  accountName?: string
  at?: string
}

const BASE = '/console/v1'
const enc = encodeURIComponent

export const transferApi = {
  targets: (type: string, wfrn: string) => get<TransferOptions>(`${BASE}/r/${enc(type)}/${enc(wfrn)}/transfer-targets`),
  /** one Idempotency-Key per confirmed move, kept across a retry after the step-up */
  move: (type: string, wfrn: string, body: TransferInput, key?: string) => request<TransferAnswer>('POST', `${BASE}/r/${enc(type)}/${enc(wfrn)}:transfer`, body, { headers: { 'Idempotency-Key': key ?? idempotencyKey() } }),
}

/** The account a wfrn names, when it is a 12-digit account id. */
export function accountOfWfrn(wfrn: string | undefined): string | null {
  const account = wfrn ? partsOfWfrn(wfrn)?.account : undefined
  return account && /^\d{12}$/.test(account) ? account : null
}

/** The new home a 410 names, or null for any other failure (a plain delete's 410 included). */
export function movedOf(e: unknown): MovedTo | null {
  if (!isApiError(e) || e.status !== 410) return null
  const body = (e.body ?? {}) as Record<string, unknown>
  const detail = (body.detail && typeof body.detail === 'object' ? body.detail : {}) as Record<string, unknown>
  const text = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
  const to = text(detail.transferredTo) ?? text(body.transferredTo)
  if (!to) return null
  // the link is built from the wfrn's own parts, so a wfrn that does not parse is not followed
  const accountId = accountOfWfrn(to)
  if (!accountId || !partsOfWfrn(to)) return null
  return { wfrn: text(detail.wfrn) ?? text(body.wfrn), transferredTo: to, accountId, accountName: text(detail.toAccountName) ?? text(body.toAccountName), at: text(detail.transferredAt) ?? text(body.transferredAt) }
}

/** What the move takes along, in plain words. */
export const WHAT_MOVES: readonly string[] = [
  'The resource itself, as it runs now: nothing is stopped or rebuilt.',
  'Its configuration, its tags and what it was built from.',
  'Its history, which stays readable under the new name.',
]

/** What stays behind or starts again, in plain words. */
export const WHAT_STAYS: readonly string[] = [
  'It gets a new wfrn in the other account. The old one keeps pointing to the new one.',
  'Who may use it follows the other account’s groups and policies, not this one’s.',
  'Its cost is counted in the other account from the moment it moves.',
  'Open drift findings close here and are found again under the new name.',
]

/** The words for a move the BFF refused, by its code; anything else is said by the general explainer. */
export const TRANSFER_CODES: Record<string, { title: string; message: string; next?: string }> = {
  'resource.busy': { title: 'A request is changing this resource', message: 'A resource moves only while nothing runs on it.', next: 'Wait for the request to finish, then move it.' },
  'resource.notActive': { title: 'Only an active resource moves', message: 'This resource is not active, so it cannot move now.', next: 'Move it once it is active again.' },
  'account.shredded': { title: 'That account is closed', message: 'The account you picked was closed and its data removed, so nothing can move into it.', next: 'Pick another account.' },
  'budget.exceeded': { title: 'The other account is over its budget', message: 'Moving this resource would put the account you picked over its budget.', next: 'Ask that account’s admins to raise the budget, or pick another account.' },
  'resource.rehomed': { title: 'This resource already moved', message: 'Someone moved it since this page was read.', next: 'Reload the page to see where it is now.' },
  'resource.transferred': { title: 'This resource already moved', message: 'It was moved to another account since this page was read.', next: 'Reload the page to see where it is now.' },
  'transfer.sameAccount': { title: 'It is already in that account', message: 'Pick an account other than the one the resource is in.' },
}

/** The code a transfer refusal carries: its `error`, or the stable code it opens with. */
export function transferCodeOf(e: unknown): string | null {
  if (!isApiError(e)) return null
  if (e.status === 400 && /same account/i.test(`${e.error} ${typeof e.detail === 'string' ? e.detail : ''}`)) return 'transfer.sameAccount'
  const code = Object.keys(TRANSFER_CODES).find((c) => e.error === c || e.error.startsWith(`${c} `) || e.error.startsWith(`${c}:`))
  return code ?? null
}

/** The draft of a move kept across the step-up's round trip to the identity provider (sessionStorage, per wfrn). */
export interface TransferDraft {
  toAccountId: string
  reason: string
}

const DRAFT_PREFIX = 'console-transfer-draft:'

export function readTransferDraft(wfrn: string, storage: Pick<Storage, 'getItem'> | undefined = safeSession()): TransferDraft | null {
  try {
    const raw = storage?.getItem(DRAFT_PREFIX + wfrn)
    if (!raw) return null
    const d = JSON.parse(raw) as Partial<TransferDraft>
    return typeof d.toAccountId === 'string' && typeof d.reason === 'string' ? { toAccountId: d.toAccountId, reason: d.reason } : null
  } catch {
    return null
  }
}

export function writeTransferDraft(wfrn: string, draft: TransferDraft | null, storage: Pick<Storage, 'setItem' | 'removeItem'> | undefined = safeSession()) {
  try {
    if (draft) storage?.setItem(DRAFT_PREFIX + wfrn, JSON.stringify(draft))
    else storage?.removeItem(DRAFT_PREFIX + wfrn)
  } catch {
    /* storage unavailable: the form starts empty after the sign-in */
  }
}

function safeSession(): Storage | undefined {
  try {
    return typeof sessionStorage === 'undefined' ? undefined : sessionStorage
  } catch {
    return undefined
  }
}
