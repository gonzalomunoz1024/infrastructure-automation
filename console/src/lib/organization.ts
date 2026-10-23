import { page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'

/*
 * The organization admins' accounts (hub decision 34, WP 7.7): every account of the organization,
 * closed ones included, and closing one. The BFF calls the identity service as the console's own
 * principal naming the admin, answers 404 to anyone who is not an organization admin, and asks
 * for a fresh sign-in before a write. Closing is for good: keys are revoked, open requests,
 * reviews and elevations end, nobody signs in to it again. An account that still holds resources
 * is refused (409 `account.hasResources`, with the count of each type), since closing leaves
 * them where they are: they are deleted or moved to another account first.
 */

export interface OrganizationAccount {
  accountId: string
  name: string
  status: 'active' | 'suspended' | 'closed'
  owningTeam: string
  costCentre?: string
  defaultRegion?: string
  version: number
  createdAt: string
  updatedAt?: string
  closedAt?: string
  closeReason?: string
}

/** The body of a refused close while resources remain. */
export interface ResourcesRemain {
  error: 'account.hasResources'
  detail: { total: number; byType: Record<string, number> }
}

const b = (path: string) => `/console/v1/organization${path}`
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const organizationApi = {
  /** organization admins only: 404 for anyone else */
  accounts: (params?: ListParams) => page<OrganizationAccount>(b('/accounts'), params),
  close: (accountId: string, body: { reason: string }, key?: string) => request<OrganizationAccount>('POST', b(`/accounts/${enc(accountId)}:close`), body, keyed(key)),
}

/** The resources a refused close named, when it named them. */
export function resourcesRemainOf(e: unknown): ResourcesRemain['detail'] | null {
  if (!e || typeof e !== 'object') return null
  const err = e as { status?: number; error?: string; detail?: unknown }
  if (err.status !== 409 || err.error !== 'account.hasResources') return null
  const d = err.detail as Partial<ResourcesRemain['detail']> | undefined
  return d && typeof d.total === 'number' && d.byType && typeof d.byType === 'object' ? { total: d.total, byType: d.byType } : null
}

/** What closing does, in the order it happens, for the dialog. */
export const CLOSE_EFFECTS: readonly string[] = [
  'Every access key of the account stops working.',
  'Access requests still waiting expire, and quota requests are withdrawn.',
  'An open access review is cancelled, and every elevation and break-glass access ends.',
  'Nobody can sign in to the account or act in it again. Closing cannot be undone.',
  'Its resources stay where they are, so the account must hold none: delete them or move them to another account first.',
]
