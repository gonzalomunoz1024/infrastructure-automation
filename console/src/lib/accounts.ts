import type { AccountSummary } from './types'

/*
 * Accounts (P-04): the 12-digit ids the console scopes everything to, as the session names them.
 * The BFF lists the ones the signed-in person belongs to (GET /console/v1/accounts) and the
 * session carries the chosen one with the roles the person holds there.
 */

export interface Account {
  id: string
  name: string
  /** the team that owns the account */
  team?: string
  status?: string
  defaultRegion?: string
  /** the managed roles the signed-in person holds here */
  roles: string[]
  /** the groups the signed-in person is in here */
  groups: string[]
}

export function accountFromSummary(a: AccountSummary): Account {
  return { id: a.accountId, name: a.name, team: a.owningTeam, status: a.status, defaultRegion: a.defaultRegion, roles: a.roles ?? [], groups: a.groups ?? [] }
}

/** "1234-5678-9012": an account id as the header shows it. */
export function formatAccountId(id: string): string {
  return id.replace(/(\d{4})(?=\d)/g, '$1-')
}

export function isAccountId(s: unknown): s is string {
  return typeof s === 'string' && /^\d{12}$/.test(s)
}
