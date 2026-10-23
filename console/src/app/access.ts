import { isApiError } from '../lib/api'
import { ADMIN_ROLE } from '../lib/session'
import { bff } from '../lib/bff'
import { useQuery } from '../lib/queries'
import type { AccessRequest } from '../lib/types'
import { useConsole } from './console'

/*
 * The account's queue of pending access requests: the bell, the sidebar badge, the identity
 * overview and the access requests page read the same query, so they agree and refetch together.
 * Reading the account's requests takes access-request:list, which the account-admin and auditor
 * policies grant and any group an admin gives it (the approver groups). The identity service
 * answers anyone else with their own requests only (and 403 when they ask for someone else's),
 * so the hook knows the queue is readable from the roles that read it, or from what came back:
 * someone else's request, or one that names this person among its approvers. What a person may
 * decide is narrower: the pending requests that name them among the approvers, other than their own.
 */

export const PENDING_KEY = (accountId: string, userId: string, roles: readonly string[]) => ['access-requests', 'pending', accountId, userId, [...roles].sort().join(',')] as const

/** the managed roles whose policies read the account's requests */
const READING_ROLES: readonly string[] = [ADMIN_ROLE, 'auditor']

/** the queues that answered 403, by the key above, so the hook stops polling them */
const refused = new Set<string>()

export interface AccessRequestQueue {
  /** the pending requests the BFF answered with: the account's, or only this person's own */
  pending: AccessRequest[]
  /** those this person may decide */
  toDecide: AccessRequest[]
  /** whether this person reads the account's queue: null until the BFF has answered */
  readable: boolean | null
  loading: boolean
  error: unknown
  refetch: () => Promise<void>
}

export function useAccessRequestQueue({ enabled = true }: { enabled?: boolean } = {}): AccessRequestQueue {
  const { account, session } = useConsole()
  const userId = session.user.userId
  const roles = session.account.roles ?? []
  const key = PENDING_KEY(account.id, userId, roles)
  const id = key.join('|')
  const q = useQuery<{ items: AccessRequest[]; refused: boolean }>(
    key,
    async () => {
      try {
        const page = await bff.accessRequests.list({ status: 'pending', limit: 200 })
        refused.delete(id)
        return { items: page.items, refused: false }
      } catch (e) {
        if (!isApiError(e) || e.status !== 403) throw e
        refused.add(id)
        return { items: [], refused: true }
      }
    },
    { enabled, refetchInterval: enabled && !refused.has(id) ? 30_000 : undefined },
  )
  const pending = q.data?.items ?? []
  return {
    pending,
    toDecide: pending.filter((r) => mayDecide(r, userId)),
    readable: q.data ? readsQueue(roles, userId, q.data) : null,
    loading: q.loading,
    error: q.error,
    refetch: q.refetch,
  }
}

/** Whether the answer shows the account's queue rather than only the person's own requests. */
export function readsQueue(roles: readonly string[], userId: string, answer: { items: readonly AccessRequest[]; refused: boolean }): boolean {
  if (answer.refused) return false
  if (roles.some((r) => READING_ROLES.includes(r))) return true
  return answer.items.some((r) => r.requester.userId !== userId || (r.approvers ?? []).includes(userId))
}

/** A request this person may decide: pending, naming them among its approvers, and not their own. */
export function mayDecide(r: AccessRequest, userId: string): boolean {
  return r.status === 'pending' && r.requester.userId !== userId && (r.approvers ?? []).includes(userId)
}
