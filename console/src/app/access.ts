import { bff } from '../lib/bff'
import { useQuery } from '../lib/queries'
import type { AccessRequest } from '../lib/types'

/*
 * The access requests waiting for an admin of the account: the bell, the sidebar badge and the
 * access requests page read the same query, so they agree and refetch together.
 */

export const PENDING_KEY = (accountId: string) => ['access-requests', 'pending', accountId] as const

export function usePendingAccessRequests(accountId: string, enabled: boolean) {
  return useQuery<{ items: AccessRequest[]; next: string | null }>(PENDING_KEY(accountId), () => bff.accessRequests.list({ scope: 'account', status: 'pending', limit: 50 }), { enabled, refetchInterval: enabled ? 30_000 : undefined })
}
