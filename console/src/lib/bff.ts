import { HEALTH_PATH, cachedGet, clearDescriptorCache, get, page, qs, request, type ListParams } from './api'
import { isApiError } from './api'
import { clearRecent } from './recent'
import { clearQueryCache, invalidate } from './queries'
import { MOCK, getSession, setSessionState, signInUrl, type Session, type SessionState } from './session'
import type { AccessKey, AccessKeyCreated, AccessRequest, AccessRequestInput, AccessRequestStatus, AccountSummary, ActivityEntry, ApprovalsDecided, BatchAccepted, BatchInput, BatchPreview, BffConsoleType, BffJobDetail, BffLineage, BffJobSummary, BffRegion, BlastRadius, CommandAccepted, CommandInput, ConsolePreview, ConsoleResource, ConsoleResourceDetail, DemoPerson, Finding, Health, IamGroup, IamGroupInput, IamPolicy, IamRole, IamUser, Preferences, Quota, QuotaRequest, QuotaRequestInput, ResourceCounts, ResourceFilters, RetryFix, SearchHit, Suggestion } from './types'

/*
 * The BFF client (hub: contracts/openapi/console-bff.yaml): the session and sign-in, the
 * accounts and regions a person may pick, the types with their decisions, the identity
 * service's resources served under /console/v1/iam, access requests, resources, jobs and the
 * live feed. The browser talks to nothing else: no platform path, no identity header of its
 * own (the session cookie names the person). Every write carries
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

/** `GET /console/v1/r/{type}`: the filters, a sort column (`-` for descending) and a page. */
export interface ResourceListParams extends ListParams, ResourceFilters {
  sort?: string
}

export interface IamListParams extends ListParams {
  q?: string
}

export interface JobListParams extends ListParams {
  wfrn?: string
  status?: string
  workflow?: string
  requester?: string
}

/** The write headers with a caller-chosen Idempotency-Key: one key per form submission, kept across retries of the same body. */
const keyed = (key?: string, version?: number) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey(), ...(version !== undefined ? { 'If-Match': String(version) } : {}) } })

export const bff = {
  /* the session */
  session: () => get<Session>(b('/session')),
  updateSession: (body: { accountId?: string; region?: string }) => request<Session>('PUT', b('/session'), body),
  logout: () => request<void>('POST', '/auth/logout'),
  accounts: () => page<AccountSummary>(b('/accounts')),
  regions: () => page<BffRegion>(b('/regions')),
  /** the people the demo identity provider knows (mock mode's sign-in page) */
  people: () => page<DemoPerson>('/demo-idp/people'),

  /*
   * the types, each action with its decision for this person, account and region; the copy the
   * ETag revalidates is kept per person, account and region, and forgotten at sign-out
   */
  types: async (): Promise<BffConsoleType[]> => {
    const s = getSession()
    const scope = s ? `${s.user.userId}/${s.account.accountId}/${s.region}` : 'nobody'
    return (await cachedGet<{ items: BffConsoleType[] }>(b('/types'), {}, `${scope}:${b('/types')}`)).items
  },
  /** the BFF's health: UP, or the 503's body when it is down */
  health: async (): Promise<Health> => {
    try {
      return await get<Health>(HEALTH_PATH)
    } catch (e) {
      if (isApiError(e) && e.status === 503 && e.body && typeof e.body.status === 'string') return e.body as unknown as Health
      throw e
    }
  },
  /** the account's live updates (server-sent events), scoped to the session's account */
  eventsUrl: (params?: { wfrn?: string; types?: string }) => `${b('/events')}${qs(params)}`,

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
      get: (quotaKey: string) => get<Quota>(b(`/iam/quotas/${enc(quotaKey)}`)),
      /** opens an ask the platform team decides; 409 while one is open for the key */
      requestIncrease: (quotaKey: string, body: QuotaRequestInput) => request<QuotaRequest>('POST', b(`/iam/quotas/${enc(quotaKey)}:request-increase`), body, writeHeaders()),
    },
    quotaRequests: {
      get: (requestId: string) => get<QuotaRequest>(b(`/iam/quota-requests/${enc(requestId)}`)),
      /** by its requester or an admin; 409 once decided */
      withdraw: (requestId: string) => request<void>('DELETE', b(`/iam/quota-requests/${enc(requestId)}`), undefined, writeHeaders()),
    },
  },

  /*
   * resources, from the state service through the BFF: one page per call, counts by lifecycle
   * state; creates, updates and verbs (phase 3) answer 202 with the job that carries them out
   */
  resources: {
    list: (type: string, params?: ResourceListParams) => page<ConsoleResource>(b(`/r/${enc(type)}`), params),
    /** how many match the filters, by lifecycle state; the counts stop at `cappedAt` */
    counts: (type: string, filters?: ResourceFilters) => get<ResourceCounts>(`${b(`/r/${enc(type)}:counts`)}${qs(filters)}`),
    get: (type: string, wfrn: string) => get<ConsoleResourceDetail>(b(`/r/${enc(type)}/${enc(wfrn)}`)),
    /** POST /r/{type}: the key is the form submission's, so a retry of the same body answers the first result (P-21) */
    create: (type: string, body: CommandInput, key?: string) => request<CommandAccepted>('POST', b(`/r/${enc(type)}`), body, keyed(key)),
    previewCreate: (type: string, body: CommandInput) => request<ConsolePreview>('POST', b(`/r/${enc(type)}:preview`), body),
    /** PATCH /r/{type}/{wfrn}: only the type's mutable fields, through its update workflow */
    update: (type: string, wfrn: string, body: CommandInput, key?: string, version?: number) => request<CommandAccepted>('PATCH', b(`/r/${enc(type)}/${enc(wfrn)}`), body, keyed(key, version)),
    /** POST /r/{type}/{wfrn}:{verb}: a destructive verb needs `confirm: true` in the body */
    run: (type: string, wfrn: string, verb: string, body: CommandInput, key?: string) => request<CommandAccepted>('POST', b(`/r/${enc(type)}/${enc(wfrn)}:${verb}`), body, keyed(key)),
    preview: (type: string, wfrn: string, verb: string, body: CommandInput) => request<ConsolePreview>('POST', b(`/r/${enc(type)}/${enc(wfrn)}:${verb}:preview`), body),
    activity: (type: string, wfrn: string, params?: ListParams) => page<ActivityEntry>(b(`/r/${enc(type)}/${enc(wfrn)}/activity`), params),
    drift: (type: string, wfrn: string) => get<{ items: Finding[] }>(b(`/r/${enc(type)}/${enc(wfrn)}/drift`)),
    lineage: (type: string, wfrn: string) => get<BffLineage>(b(`/r/${enc(type)}/${enc(wfrn)}/lineage`)),
    setTags: (type: string, wfrn: string, tags: Record<string, string>) => request<Record<string, string>>('PUT', b(`/r/${enc(type)}/${enc(wfrn)}/tags`), tags, keyed()),
    /** what depends on the resource, within `depth` hops (1 to 5), counted across accounts */
    blastRadius: (type: string, wfrn: string, depth?: number) => get<BlastRadius>(`${b(`/r/${enc(type)}/${enc(wfrn)}/blast-radius`)}${qs({ depth })}`),
    /** POST /r/{type}:batch: one request, one row per resource, released in waves */
    batch: (type: string, body: BatchInput, key?: string) => request<BatchAccepted>('POST', b(`/r/${enc(type)}:batch`), body, keyed(key)),
    /** every row judged before anything runs */
    previewBatch: (type: string, body: BatchInput) => request<BatchPreview>('POST', b(`/r/${enc(type)}:batch:preview`), body),
  },
  search: (params: { q: string; type?: string } & ListParams) => page<SearchHit>(b('/search'), params),
  suggest: (q: string, limit = 8) => get<{ items: Suggestion[] }>(`${b('/suggest')}${qs({ q, limit })}`),
  preferences: {
    get: () => get<Preferences>(b('/preferences')),
    put: (body: Preferences) => request<Preferences>('PUT', b('/preferences'), body, writeHeaders()),
  },

  /* access requests: from a refusal to the account's approvers */
  accessRequests: {
    /** everyone's own (requester = them); the account's queue (no requester, or someone else's) needs access-request:list */
    list: (params?: { requester?: string; status?: AccessRequestStatus } & ListParams) => page<AccessRequest>(b('/access-requests'), params),
    get: (requestId: string) => get<AccessRequest>(b(`/access-requests/${enc(requestId)}`)),
    create: (body: AccessRequestInput) => request<AccessRequest>('POST', b('/access-requests'), body, writeHeaders()),
    decide: (requestId: string, body: { decision: 'approve' | 'reject'; comment?: string }) => request<AccessRequest>('POST', b(`/access-requests/${enc(requestId)}:decide`), body, writeHeaders()),
  },

  /* jobs as the BFF lists them, scoped to the session's account */
  jobs: {
    list: (params?: JobListParams) => page<BffJobSummary>(b('/jobs'), params),
    get: (jobId: string) => get<BffJobDetail>(b(`/jobs/${enc(jobId)}`)),
    cancel: (jobId: string, key?: string) => request<{ jobId: string; status: string }>('POST', b(`/jobs/${enc(jobId)}:cancel`), undefined, keyed(key)),
    /** a paused step resumes in the same job; an ended job runs again as a new one (its id in the answer); the fix's input merges over the job's own */
    retry: (jobId: string, body?: RetryFix, key?: string) => request<CommandAccepted>('POST', b(`/jobs/${enc(jobId)}:retry`), body && (body.input || body.fromStep) ? body : undefined, keyed(key)),
    /** goes on past the step the job waits on (or the one named) */
    skip: (jobId: string, step?: string, key?: string) => request<CommandAccepted>('POST', b(`/jobs/${enc(jobId)}:skip`), step ? { step } : undefined, keyed(key)),
    /** fails the paused step for good: the completed steps' compensations run */
    abort: (jobId: string, key?: string) => request<CommandAccepted>('POST', b(`/jobs/${enc(jobId)}:abort`), undefined, keyed(key)),
    /** decides every pending approval of the request and its rows this person may decide, in one call per wave */
    decideApprovals: (jobId: string, body: { decision: 'approve' | 'reject'; comment?: string }, key?: string) => request<ApprovalsDecided>('POST', b(`/jobs/${enc(jobId)}/approvals:decide`), body, keyed(key)),
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
  // what this browser kept about the person goes with them: the next person on the device starts clean
  clearRecent()
  clearDescriptorCache()
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
