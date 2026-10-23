import { get, page, request, type ListParams } from './api'
import { isApiError } from './api'
import { clearQueryCache, invalidate } from './queries'
import { MOCK, getSession, setSessionState, signInUrl, type Session, type SessionState } from './session'
import type { AccessKey, AccessKeyCreated, AccessRequest, AccessRequestInput, AccountSummary, BffConsoleType, BffRegion, DemoPerson, IamGroup, IamGroupInput, IamPolicy, IamRole, IamUser, Quota } from './types'

/*
 * The BFF client (hub: contracts/openapi/console-bff.yaml): the session and sign-in, the
 * accounts and regions a person may pick, the types with their decisions, the identity
 * service's resources served under /console/v1/iam, and access requests. Every write carries
 * X-Csrf-Token (the API client adds it) and, where the identity service asks, If-Match and an
 * Idempotency-Key.
 */

export const BFF_BASE = '/console/v1'
const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/** A fresh key per form submission (P-21): the same key with the same body answers the first result. */
export function idempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const writeHeaders = (version?: number) => ({ headers: { 'Idempotency-Key': idempotencyKey(), ...(version !== undefined ? { 'If-Match': String(version) } : {}) } })

export interface IamListParams extends ListParams {
  q?: string
}

export const bff = {
  /* the session */
  session: () => get<Session>(b('/session')),
  updateSession: (body: { accountId?: string; region?: string }) => request<Session>('PUT', b('/session'), body),
  logout: () => request<void>('POST', '/auth/logout'),
  accounts: () => page<AccountSummary>(b('/accounts')),
  regions: () => page<BffRegion>(b('/regions')),
  /** the people the demo identity provider knows (mock mode's sign-in page) */
  people: () => page<DemoPerson>('/demo-idp/people'),

  /* the types, each action with its decision for this person, account and region */
  types: () => get<BffConsoleType[]>(b('/types')),

  /* identity and access, scoped to the session's account */
  iam: {
    users: {
      list: (params?: IamListParams) => page<IamUser>(b('/iam/users'), params),
      get: (userId: string) => get<IamUser>(b(`/iam/users/${enc(userId)}`)),
      add: (body: { userId?: string; email?: string; name?: string; groups?: string[] }) => request<IamUser>('POST', b('/iam/users'), body, writeHeaders()),
      update: (userId: string, body: { groups?: string[]; status?: 'active' | 'suspended' }, version?: number) => request<IamUser>('PATCH', b(`/iam/users/${enc(userId)}`), body, writeHeaders(version)),
      remove: (userId: string) => request<void>('DELETE', b(`/iam/users/${enc(userId)}`), undefined, writeHeaders()),
    },
    groups: {
      list: (params?: IamListParams) => page<IamGroup>(b('/iam/groups'), params),
      get: (groupId: string) => get<IamGroup>(b(`/iam/groups/${enc(groupId)}`)),
      create: (body: IamGroupInput) => request<IamGroup>('POST', b('/iam/groups'), body, writeHeaders()),
      update: (groupId: string, body: IamGroupInput, version?: number) => request<IamGroup>('PATCH', b(`/iam/groups/${enc(groupId)}`), body, writeHeaders(version)),
      remove: (groupId: string) => request<void>('DELETE', b(`/iam/groups/${enc(groupId)}`), undefined, writeHeaders()),
    },
    roles: {
      list: (params?: IamListParams) => page<IamRole>(b('/iam/roles'), params),
      get: (roleId: string) => get<IamRole>(b(`/iam/roles/${enc(roleId)}`)),
    },
    policies: {
      list: (params?: IamListParams) => page<IamPolicy>(b('/iam/policies'), params),
      get: (policyId: string) => get<IamPolicy>(b(`/iam/policies/${enc(policyId)}`)),
    },
    accessKeys: {
      list: (params?: IamListParams & { userId?: string }) => page<AccessKey>(b('/iam/access-keys'), params),
      get: (keyId: string) => get<AccessKey>(b(`/iam/access-keys/${enc(keyId)}`)),
      create: (body: { userId?: string; description?: string; expiresAt?: string }) => request<AccessKeyCreated>('POST', b('/iam/access-keys'), body, writeHeaders()),
      revoke: (keyId: string) => request<void>('DELETE', b(`/iam/access-keys/${enc(keyId)}`), undefined, writeHeaders()),
      rotate: (keyId: string) => request<AccessKeyCreated>('POST', b(`/iam/access-keys/${enc(keyId)}:rotate`), undefined, writeHeaders()),
    },
    quotas: {
      list: () => page<Quota>(b('/iam/quotas')),
    },
  },

  /* access requests: from a refusal to the account's admins */
  accessRequests: {
    list: (params?: { scope?: 'mine' | 'account'; status?: string } & ListParams) => page<AccessRequest>(b('/access-requests'), params),
    get: (requestId: string) => get<AccessRequest>(b(`/access-requests/${enc(requestId)}`)),
    create: (body: AccessRequestInput) => request<AccessRequest>('POST', b('/access-requests'), body, writeHeaders()),
    decide: (requestId: string, body: { decision: 'approve' | 'reject'; comment?: string }) => request<AccessRequest>('POST', b(`/access-requests/${enc(requestId)}:decide`), body, writeHeaders()),
  },
}

export type Bff = typeof bff

/* ----------------------------------------------------------------------------
   The session's life: read it, sign in, switch, sign out
   ------------------------------------------------------------------------- */

/** Reads the session from the BFF and keeps it: signed in, signed out (401), or unreachable. */
export async function loadSession(): Promise<SessionState> {
  let next: SessionState
  try {
    const session = await bff.session()
    next = session && session.user && session.account ? { status: 'signed-in', session } : { status: 'signed-out', session: null }
  } catch (e) {
    next = isApiError(e) && e.status === 401 ? { status: 'signed-out', session: null } : { status: 'unreachable', session: null, error: e }
  }
  setSessionState(next)
  return next
}

/**
 * Starts the sign-in. Live, the browser goes to the BFF's /auth/login and comes back to `next`
 * with the cookie set. In mock mode the mock answers the same URL in place, so the session is
 * read again and the caller navigates; it returns false then, true when the browser left.
 */
export async function beginSignIn(next = '/', loginHint?: string): Promise<boolean> {
  const url = signInUrl(next, loginHint)
  if (!MOCK) {
    if (typeof window !== 'undefined') window.location.assign(url)
    return true
  }
  await request<unknown>('GET', url)
  clearQueryCache()
  await loadSession()
  return false
}

/** Ends the session at the BFF and in this browser; the caller sends the person to the door. */
export async function signOut(): Promise<void> {
  try {
    await bff.logout()
  } catch {
    /* the cookie may already be gone: the browser's copy goes anyway */
  }
  clearQueryCache()
  setSessionState({ status: 'signed-out', session: null })
}

/** Signs out and, live, goes straight back to the identity provider so another person can sign in. */
export async function switchIdentity(next = '/'): Promise<void> {
  await signOut()
  if (!MOCK && typeof window !== 'undefined') window.location.assign(signInUrl(next))
}

function keep(session: Session) {
  setSessionState({ status: 'signed-in', session })
  // everything read so far was read in the old scope
  clearQueryCache()
  invalidate()
}

/** PUT /console/v1/session with another account; everything on screen is read again in it. */
export async function switchAccount(accountId: string): Promise<Session> {
  const current = getSession()
  if (current && current.account.accountId === accountId) return current
  const session = await bff.updateSession({ accountId })
  keep(session)
  return session
}

export async function switchRegion(region: string): Promise<Session> {
  const current = getSession()
  if (current && current.region === region) return current
  const session = await bff.updateSession({ region })
  keep(session)
  return session
}
