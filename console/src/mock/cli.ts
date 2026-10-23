import type { ApiSession, DeviceGrant } from '../lib/types'
import { emptyConsoleState, idFrom, iso, journaled, pageOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'

/*
 * The BFF's device sign-in and API sessions (hub console-bff.yaml, WP 7.4), answered in mock mode:
 * the grants a CLI started (`WDJB-MJHT` waits for a decision, `QXRT-VNPL` comes from an address
 * the person will not know, `HZKD-FWBM` has expired), approving one (browser only, after a fresh
 * sign-in: past ten minutes it is the step-up 403, as a delete is), denying one, and the person's
 * API sessions, each listed newest first and revoked one at a time. An approval opens the API
 * session the CLI's next poll would redeem, so the CLI sign-ins page shows it at once. Decisions
 * and revocations are journaled (`cli.` commands), so a reload keeps them. Also the console
 * API's own OpenAPI document (`/console/v1/openapi.json`), which the developer portal lists.
 */

/** How long a browser sign-in counts as fresh for approving a grant (`console.auth.step-up.max-age`). */
export const STEP_UP_MAX_AGE_MS = 10 * 60_000
/** An API session lasts 12 hours from its approval (hub decision 31). */
export const API_SESSION_MS = 12 * 3_600_000

export interface MockGrant extends DeviceGrant {
  /** who decided it, once decided */
  decidedBy?: string
}

export interface MockApiSession extends ApiSession {
  userId: string
  revokedAt?: string
}

/** What these endpoints keep beside the dataset, made on first use. */
export interface CliState {
  grants: Record<string, MockGrant>
  sessions: MockApiSession[]
  /** the people whose seeded sessions were made */
  seeded: string[]
}

const CLI_AGENT = 'iaf/0.7.0 (darwin; arm64) node/22.11.0'

/** The grants waiting when the mock first answers, relative to that moment. */
function seedGrants(now: number): Record<string, MockGrant> {
  const grant = (userCode: string, clientId: string, ageMs: number, lifeMs: number, userAgent: string, ip: string): MockGrant => ({ userCode, clientId, status: 'pending', requestedAt: iso(now - ageMs), expiresAt: iso(now - ageMs + lifeMs), requestedFrom: { ip, userAgent } })
  return {
    'WDJB-MJHT': grant('WDJB-MJHT', 'console-cli', 40_000, 15 * 60_000, CLI_AGENT, '10.20.4.17'),
    'QXRT-VNPL': grant('QXRT-VNPL', 'console-cli', 90_000, 15 * 60_000, 'iaf/0.7.0 (linux; x64) node/22.11.0', '203.0.113.42'),
    'HZKD-FWBM': grant('HZKD-FWBM', 'console-sdk', 30 * 60_000, 10 * 60_000, 'iaf-sdk-ts/0.3.1 node/22.11.0 (linux; x64)', '10.20.9.3'),
  }
}

export function emptyCliState(now: number): CliState {
  return { grants: seedGrants(now), sessions: [], seeded: [] }
}

function stateOf(data: MockData, now: number): CliState {
  const st = (data.console ??= emptyConsoleState())
  return (st.cli ??= emptyCliState(now))
}

/** `aps_` and 26 lowercase letters or digits, the same for the same seed. */
export function apiSessionIdOf(seed: string): string {
  return `aps_${idFrom(seed, 26)}`
}

/** Every person signed a laptop CLI in this morning and has a CI job on an SDK. */
function seedSessions(st: CliState, userId: string, accountId: string, region: string, now: number) {
  if (st.seeded.includes(userId)) return
  st.seeded.push(userId)
  const session = (kind: string, clientId: string, ageMs: number, idleMs: number, userAgent: string, ip: string): MockApiSession => ({
    id: apiSessionIdOf(`${userId}:${kind}`),
    userId,
    clientId,
    createdAt: iso(now - ageMs),
    lastUsedAt: iso(now - idleMs),
    expiresAt: iso(now - ageMs + API_SESSION_MS),
    accountId,
    region,
    userAgent,
    ip,
  })
  st.sessions.push(session('laptop', 'console-cli', 3 * 3_600_000, 5 * 60_000, CLI_AGENT, '10.20.4.17'), session('ci', 'console-sdk', 50 * 60_000, 2 * 60_000, 'iaf-sdk-ts/0.3.1 node/22.11.0 (linux; x64)', '10.20.9.3'))
}

/** 'wdjbmjht', 'wdjb-mjht' → 'WDJB-MJHT', as the BFF matches a code; null when it is not one. */
export function codeKey(raw: string): string | null {
  if (!/^[A-Za-z]{4}-?[A-Za-z]{4}$/.test(raw)) return null
  const letters = raw.replace('-', '').toUpperCase()
  return `${letters.slice(0, 4)}-${letters.slice(4)}`
}

/** A grant as the BFF shows it: a pending one past its time reads expired. */
function shown(g: MockGrant, now: number): DeviceGrant {
  const { decidedBy: _decidedBy, ...rest } = g
  return g.status === 'pending' && now >= Date.parse(g.expiresAt) ? { ...rest, status: 'expired' } : rest
}

function view(s: MockApiSession): ApiSession {
  const { userId: _userId, revokedAt: _revokedAt, ...rest } = s
  return rest
}

function isOpen(s: MockApiSession, now: number): boolean {
  return !s.revokedAt && now < Date.parse(s.expiresAt)
}

/** Applies a journaled `cli.` command: a decision on a grant, or a revocation. */
export function applyCliCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = stateOf(data, cmd.at)
  const body = (cmd.body ?? {}) as { userCode?: string; apiSessionId?: string }
  if (cmd.kind === 'cli.approve' || cmd.kind === 'cli.deny') {
    const g = body.userCode ? st.grants[body.userCode] : undefined
    if (!g) return { status: 404, body: { error: 'Device grant not found' } }
    if (g.status !== 'pending') return { status: 409, body: { error: 'device-grant.decided', detail: `This code was already ${g.status}` } }
    if (cmd.at >= Date.parse(g.expiresAt)) return { status: 410, body: { error: 'device-grant.expired', detail: 'This code expired; run the sign-in again for a new one' } }
    g.status = cmd.kind === 'cli.approve' ? 'approved' : 'denied'
    g.decidedAt = iso(cmd.at)
    g.decidedBy = cmd.userId
    if (g.status === 'approved') {
      seedSessions(st, cmd.userId, cmd.accountId, cmd.region, cmd.at)
      st.sessions.push({ id: cmd.ids.requestId ?? apiSessionIdOf(`${cmd.userId}:${g.userCode}:${cmd.at}`), userId: cmd.userId, clientId: g.clientId, createdAt: iso(cmd.at), lastUsedAt: iso(cmd.at), expiresAt: iso(cmd.at + API_SESSION_MS), accountId: cmd.accountId, region: cmd.region, userAgent: g.requestedFrom?.userAgent, ip: g.requestedFrom?.ip })
    }
    return { status: 200, body: shown(g, cmd.at) }
  }
  if (cmd.kind === 'cli.revoke') {
    seedSessions(st, cmd.userId, cmd.accountId, cmd.region, cmd.at)
    const s = st.sessions.find((x) => x.id === body.apiSessionId && x.userId === cmd.userId && isOpen(x, cmd.at))
    if (!s) return { status: 404, body: { error: 'API session not found' } }
    s.revokedAt = iso(cmd.at)
    return { status: 204 }
  }
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}

type Session = RouteContext['session'] & { authTime?: string }

const stepUp = (action: string): RouteAnswer => ({ status: 403, body: { error: 'step-up.required', detail: { stepUp: true, action, login: '/auth/login?stepUp=true' } } })

export function cliRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session as Session
  const now = ctx.now
  if (path === '/console/v1/openapi.json' && method === 'GET') return { status: 200, body: CONSOLE_API_DOCUMENT }

  const m = /^\/console\/v1\/device-grants\/([^/:]+)(?::(approve|deny))?$/.exec(path)
  if (m) {
    const st = stateOf(data, now)
    const code = codeKey(decodeURIComponent(m[1]))
    const g = code ? st.grants[code] : undefined
    if (!code) return { status: 400, body: { error: 'userCode.invalid', detail: 'A code is eight letters, written XXXX-XXXX' } }
    const action = m[2]
    if (!action) {
      if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on a device grant` } }
      return g ? { status: 200, body: shown(g, now) } : { status: 404, body: { error: 'Device grant not found', detail: 'No sign-in waits under this code; check it against your terminal' } }
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on :${action}` } }
    if ((ctx.headers.authorization ?? '').toLowerCase().startsWith('bearer ')) return { status: 403, body: { error: 'browser.only', detail: 'Device grants are decided in a signed-in browser' } }
    if (!g) return { status: 404, body: { error: 'Device grant not found' } }
    // approving signs a client in as the person: as a delete, it needs a fresh sign-in
    if (action === 'approve' && (!s.authTime || now - Date.parse(s.authTime) > STEP_UP_MAX_AGE_MS)) return stepUp('device-grant:approve')
    return journaled(data, ctx, { at: now, kind: action === 'approve' ? 'cli.approve' : 'cli.deny', userId: s.userId, accountId: s.accountId, region: s.region, type: '', body: { userCode: code }, ids: { requestId: action === 'approve' ? apiSessionIdOf(`${s.userId}:${code}:${now}`) : undefined } })
  }

  if (path === '/console/v1/api-sessions') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on the API sessions` } }
    const st = stateOf(data, now)
    seedSessions(st, s.userId, s.accountId, s.region, now)
    const mine = st.sessions.filter((x) => x.userId === s.userId && isOpen(x, now)).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return pageOf(mine.map(view), params)
  }
  const one = /^\/console\/v1\/api-sessions\/([^/]+)$/.exec(path)
  if (one) {
    if (method !== 'DELETE') return { status: 405, body: { error: `${method} is not allowed on an API session` } }
    const id = decodeURIComponent(one[1])
    if (!/^aps_[0-9a-z]{26}$/.test(id)) return { status: 404, body: { error: 'API session not found' } }
    return journaled(data, ctx, { at: now, kind: 'cli.revoke', userId: s.userId, accountId: s.accountId, region: s.region, type: '', body: { apiSessionId: id }, ids: {} })
  }
  return null
}

/* ----------------------------------------------------------------------------
   The console API's own description, as the BFF would serve it
   ------------------------------------------------------------------------- */

type Op = [method: string, path: string, tag: string, summary: string]

const OPERATIONS: Op[] = [
  ['get', '/console/v1/session', 'Session', 'Who is signed in, in which account and region'],
  ['put', '/console/v1/session', 'Session', 'Switch the account or the region'],
  ['get', '/console/v1/accounts', 'Session', 'The accounts the person belongs to'],
  ['get', '/console/v1/types', 'Types', 'Every resource type, with what the person may do on each'],
  ['get', '/console/v1/r/{type}', 'Resources', 'List the resources of a type, filtered and sorted on the server'],
  ['post', '/console/v1/r/{type}', 'Resources', 'Create a resource through its create workflow'],
  ['post', '/console/v1/r/{type}:preview', 'Resources', 'What a create would do, cost and need, before it runs'],
  ['post', '/console/v1/r/{type}:batch', 'Resources', 'Run a workflow on many resources in waves'],
  ['get', '/console/v1/r/{type}/{wfrn}', 'Resources', 'One resource, as the console shows it'],
  ['patch', '/console/v1/r/{type}/{wfrn}', 'Resources', 'Change the fields a type lets change'],
  ['post', '/console/v1/r/{type}/{wfrn}:{verb}', 'Resources', 'Run a workflow on a resource'],
  ['get', '/console/v1/r/{type}/{wfrn}/activity', 'Resources', 'What happened to a resource'],
  ['get', '/console/v1/jobs', 'Jobs', 'The account’s requests, newest first'],
  ['get', '/console/v1/jobs/{jobId}', 'Jobs', 'One request: its steps, approvals and what it may do next'],
  ['post', '/console/v1/jobs/{jobId}:cancel', 'Jobs', 'Stop a request that is still running'],
  ['get', '/console/v1/search', 'Search', 'Find resources of every type'],
  ['get', '/console/v1/iam/{collection}', 'Identity and access', 'Users, groups, roles, policies and access keys'],
  ['post', '/console/v1/access-requests', 'Identity and access', 'Ask the account’s approvers for access'],
  ['post', '/auth/device', 'Device sign-in', 'Start signing the CLI or an SDK in'],
  ['post', '/auth/token', 'Device sign-in', 'Redeem an approved sign-in, or refresh'],
  ['post', '/auth/revoke', 'Device sign-in', 'Sign the CLI or an SDK out'],
  ['get', '/console/v1/device-grants/{userCode}', 'Device sign-in', 'A sign-in waiting for the person’s decision'],
  ['post', '/console/v1/device-grants/{userCode}:approve', 'Device sign-in', 'Sign the CLI or SDK in as this person'],
  ['post', '/console/v1/device-grants/{userCode}:deny', 'Device sign-in', 'Refuse a sign-in'],
  ['get', '/console/v1/api-sessions', 'API sessions', 'The person’s CLI and SDK sign-ins'],
  ['delete', '/console/v1/api-sessions/{apiSessionId}', 'API sessions', 'Sign one CLI or SDK out'],
]

/** An OpenAPI 3.1 outline of the console API: its tags and operations, no schemas. */
export const CONSOLE_API_DOCUMENT = {
  openapi: '3.1.0',
  info: { title: 'Console API', version: '1.0.0', description: 'The API the console, the CLI and the SDKs share. The full document, with every schema, is contracts/openapi/console-bff.yaml on the hub.' },
  tags: [...new Set(OPERATIONS.map((o) => o[2]))].map((name) => ({ name })),
  paths: OPERATIONS.reduce<Record<string, Record<string, { tags: string[]; summary: string }>>>((paths, [method, path, tag, summary]) => {
    ;(paths[path] ??= {})[method] = { tags: [tag], summary }
    return paths
  }, {}),
}
