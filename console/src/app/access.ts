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
 * answers anyone else's list with their own requests only, and refuses them someone else's with
 * a 403, so the hook asks once for the requests of a requester nobody is: a 200 says the person
 * reads the queue, a 403 that they see their own only. What a person may decide is narrower: the
 * pending requests that name them among the approvers, which never name the requester.
 */

export const PENDING_KEY = (accountId: string, userId: string, roles: readonly string[]) => ['access-requests', 'pending', accountId, userId, [...roles].sort().join(',')] as const
const READABLE_KEY = (accountId: string, userId: string, roles: readonly string[]) => ['access-requests', 'readable', accountId, userId, [...roles].sort().join(',')] as const

/** the managed roles whose policies read the account's requests: no need to ask */
const READING_ROLES: readonly string[] = [ADMIN_ROLE, 'auditor']

/** a requester no person is (user ids never hold a tilde): asking for their requests tells a reader from everyone else */
export const PROBE_REQUESTER = '~someone-else'

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
  const reads = useQuery<boolean>(READABLE_KEY(account.id, userId, roles), () => readsQueue(roles, (params) => bff.accessRequests.list(params)), { enabled })
  const pending = q.data?.items ?? []
  return {
    pending,
    toDecide: pending.filter((r) => mayDecide(r, userId)),
    readable: q.data?.refused ? false : (reads.data ?? null),
    loading: q.loading || reads.loading,
    error: q.error ?? reads.error,
    refetch: async () => {
      await Promise.all([q.refetch(), reads.refetch()])
    },
  }
}

/**
 * Whether the person reads the account's queue rather than only their own requests: one of the
 * managed roles that read it, or a 200 to a list of someone else's requests (a 403 says no).
 */
export async function readsQueue(roles: readonly string[], list: (params: { requester: string; limit: number }) => Promise<unknown>): Promise<boolean> {
  if (roles.some((r) => READING_ROLES.includes(r))) return true
  try {
    await list({ requester: PROBE_REQUESTER, limit: 1 })
    return true
  } catch (e) {
    if (isApiError(e) && e.status === 403) return false
    throw e
  }
}

/** A request this person may decide: pending, naming them among its approvers, and not their own. */
export function mayDecide(r: AccessRequest, userId: string): boolean {
  return r.status === 'pending' && r.requester.userId !== userId && (r.approvers ?? []).includes(userId)
}
