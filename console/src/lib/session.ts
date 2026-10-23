import { useSyncExternalStore } from 'react'
import type { Tone } from '@iaf/ui'
import type { AccountSummary, Me } from './types'

/*
 * The session: who is signed in, the account and region every page is read in, and the CSRF
 * token every write carries. The BFF keeps it behind the console_session cookie (HttpOnly, so
 * this module never sees the cookie): the browser reads GET /console/v1/session and keeps the
 * answer here, in memory, for the API client and the pages. Mock mode keeps the same shape
 * behind the mock server in this browser, so the pages do not know which they talk to.
 */

export const MOCK = import.meta.env.VITE_CONSOLE_MOCK === '1'

export interface SessionUser {
  userId: string
  email: string
  name?: string
  groups?: string[]
}

export type SessionAccount = AccountSummary

export interface Session {
  user: SessionUser
  account: SessionAccount
  region: string
  csrfToken: string
  expiresAt: string
  authTime?: string
}

export type SessionStatus = 'loading' | 'signed-in' | 'signed-out' | 'unreachable'

export interface SessionState {
  status: SessionStatus
  session: Session | null
  /** why the session could not be read (status unreachable) */
  error?: unknown
}

const INITIAL: SessionState = { status: 'loading', session: null }
let state: SessionState = INITIAL
const listeners = new Set<() => void>()

export function getSessionState(): SessionState {
  return state
}

export function getSession(): Session | null {
  return state.session
}

export function setSessionState(next: SessionState) {
  state = next
  listeners.forEach((l) => l())
}

/** Back to the start, for tests. */
export function resetSessionState() {
  setSessionState(INITIAL)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

const getServer = () => INITIAL

/** The session state, reactive. */
export function useSessionState(): SessionState {
  return useSyncExternalStore(subscribe, getSessionState, getServer)
}

export function useSession(): Session | null {
  return useSessionState().session
}

/* ----------------------------------------------------------------------------
   Roles: the managed roles a user holds in the account, from the groups they are in
   ------------------------------------------------------------------------- */

/** The four managed roles of every account (hub decision 21), the weakest first. */
export const MANAGED_ROLES = ['viewer', 'operator', 'account-admin', 'auditor'] as const
export type ManagedRole = (typeof MANAGED_ROLES)[number]

/** The role that runs an account; services decide admin-only things on it, never on a group name. */
export const ADMIN_ROLE: ManagedRole = 'account-admin'
/** the managed group that holds the account's admins: it keeps its name, the Admin role and at least one member, and cannot be deleted */
export const OWNERS_GROUP = 'owners'

const RANK: Record<string, number> = { 'account-admin': 4, operator: 3, auditor: 2, viewer: 1 }

/** How a managed role is shown; a custom role is shown by its name. */
export const ROLE_LABEL: Record<ManagedRole, string> = {
  viewer: 'Viewer',
  operator: 'Operator',
  'account-admin': 'Admin',
  auditor: 'Auditor',
}

/** What each managed role allows, in the identity service's words. */
export const ROLE_SUMMARY: Record<ManagedRole, string> = {
  viewer: 'Reads everything in the account.',
  operator: 'Reads, and every change that is not a delete. No IAM.',
  'account-admin': 'Everything in the account, IAM and deletes included.',
  auditor: 'Reads everything, and the audit trail.',
}

export function isManagedRole(role: string | null | undefined): role is ManagedRole {
  return !!role && (MANAGED_ROLES as readonly string[]).includes(role)
}

/** 'account-admin' → 'Admin'; a custom role keeps its name. */
export function roleLabel(role: string | null | undefined): string {
  if (!role) return 'no role'
  return isManagedRole(role) ? ROLE_LABEL[role] : role
}

/** The strongest of the roles: admin, then operator, auditor, viewer; a custom role last. */
export function primaryRole(roles: readonly string[] | undefined): string | null {
  if (!roles?.length) return null
  return [...roles].sort((a, b) => (RANK[b] ?? 0) - (RANK[a] ?? 0) || a.localeCompare(b))[0]
}

export function rolesOf(session: Session | null | undefined): string[] {
  return session?.account.roles ?? []
}

export function hasRole(session: Session | null | undefined, role: string): boolean {
  return rolesOf(session).includes(role)
}

export function isAdmin(session: Session | null | undefined): boolean {
  return hasRole(session, ADMIN_ROLE)
}

/** The colour a role is drawn in: admin warns, operator is the accent, auditor is calm, viewer and custom roles neutral. */
export function roleTone(role: string | null | undefined): Tone {
  switch (role) {
    case 'account-admin':
      return 'warning'
    case 'operator':
      return 'accent'
    case 'auditor':
      return 'healthy'
    default:
      return 'neutral'
  }
}

/** The session's person in the shape the phase-0 pages read (GET /api/v1/me). */
export function meOf(session: Session): Me {
  const roles = rolesOf(session)
  return {
    id: session.user.userId,
    name: session.user.name ?? session.user.email,
    email: session.user.email,
    roles,
    teams: session.account.groups ?? session.user.groups,
    admin: roles.includes(ADMIN_ROLE),
  }
}

/* ----------------------------------------------------------------------------
   Signing in: the browser goes to /auth/login and comes back to a hash route
   ------------------------------------------------------------------------- */

/** '/requests' → '/#/requests': the place /auth/login sends the browser back to. */
export function returnToOf(path: string): string {
  const p = path.startsWith('#') ? path.slice(1) : path
  return `/#${p.startsWith('/') ? p : `/${p}`}`
}

/** The BFF's sign-in URL; `login_hint` lets the demo identity provider sign a known person in without its picker. */
export function signInUrl(next = '/', loginHint?: string): string {
  const sp = new URLSearchParams({ returnTo: returnToOf(next) })
  if (loginHint) sp.set('login_hint', loginHint)
  return `/auth/login?${sp.toString()}`
}

/** Seconds until the session expires; null without one, 0 when it already has. */
export function secondsLeft(session: Session | null | undefined, now = Date.now()): number | null {
  if (!session) return null
  const at = Date.parse(session.expiresAt)
  if (Number.isNaN(at)) return null
  return Math.max(0, Math.floor((at - now) / 1000))
}
