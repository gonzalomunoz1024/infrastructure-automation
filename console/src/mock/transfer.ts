import { serviceOf } from '../lib/descriptors'
import type { Denial } from '../lib/types'
import { busyOn, classOf, emptyConsoleState, isRecord, journaled, nounOf, problems400, refused, resourceByWfrn, settleConsole, typeOf, wfrnOfResource, wfrnPatternOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { accountOf, authorize, isMember, type Decision } from './directory'
import { accountOfResource, mintWfrn } from './resources'

/*
 * Moving a resource to another account (WP 7.7, hub decision 30), as the BFF answers it over the
 * state service's `:transfer` and the platform's re-home. The move needs `<service>:transfer` on
 * the resource in its own account and `<service>:create` on the type in the target account, each
 * decided there by the account's own roles and policies (the identity service's authorize: the
 * platform's role for either account is not asked, since the move and the re-home run under the
 * console's own scopes), `confirm: true` and a sign-in fresher than STEP_UP_MAX_AGE_MS. Only an idle, active
 * resource moves. The resource keeps its id and record; it takes the wfrn the target account
 * mints, its tags follow it, and the old wfrn answers 410 `resource.transferred` with
 * `transferredTo` for good. `GET .../transfer-targets` is the console's own read of the accounts
 * the person could move it to (not in the hub contract yet).
 */

/** How old a sign-in may be for a move: the BFF's `console.auth.step-up.max-age`, in the mock. */
export const STEP_UP_MAX_AGE_MS = 10 * 60_000

export interface TransferRecord {
  from: string
  to: string
  type: string
  resourceId: string
  fromAccountId: string
  toAccountId: string
  at: string
  by: string
  reason?: string
}

export interface TransferState {
  moves: TransferRecord[]
}

function stateOf(data: MockData): TransferState {
  const st = (data.console ??= emptyConsoleState())
  return (st.transfers ??= { moves: [] })
}

/** The latest move away from a wfrn the resource no longer lives at (a move back makes the old name live again). */
export function movedFrom(data: MockData, wfrn: string): TransferRecord | undefined {
  const moves = data.console?.transfers?.moves ?? []
  for (let i = moves.length - 1; i >= 0; i--) {
    const m = moves[i]
    if (m.from !== wfrn) continue
    const r = data.resources.find((x) => x.id === m.resourceId)
    const rt = r ? typeOf(data, r.type) : undefined
    const now = r && rt ? wfrnOfResource(data, { region: wfrnRegion(wfrn) }, rt, r) : undefined
    return now === wfrn ? undefined : m
  }
  return undefined
}

function wfrnRegion(wfrn: string): string {
  return wfrn.split(':')[3] || 'global'
}

/** A move's check in one account, by its own roles and policies (identity's authorize): a refusal of the platform's layer does not stop a move. */
function moveDecision(data: MockData, userId: string, accountId: string, action: string, pattern: string): Decision {
  const d = authorize(data.directory, { accountId, userId }, action, pattern)
  return d.allowed || d.denied?.layer === 'platform' ? { allowed: true } : d
}

function stepUpRefusal(action: string): RouteAnswer {
  return { status: 403, body: { error: 'step-up.required', detail: { stepUp: true, action, login: '/auth/login?stepUp=true' } } }
}

const ON_RESOURCE = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)(?::([a-z][a-z0-9-]{0,31}))?(?::preview)?(?:\/(transfer-targets|activity|drift|lineage|tags|blast-radius))?$/

export function transferRoutes(data: MockData, ctx: RouteContext, path: string, _params: URLSearchParams, method: string): RouteAnswer | null {
  const m = ON_RESOURCE.exec(path)
  if (!m) return null
  const rt = typeOf(data, m[1])
  if (!rt) return null
  const wfrnParam = decodeURIComponent(m[2])
  const verb = m[3]
  const sub = m[4]
  const s = ctx.session
  const service = serviceOf(rt)
  const noun = nounOf(rt).toLowerCase()

  // a moved resource's old name: every read and write says where it went
  const moved = movedFrom(data, wfrnParam)
  if (moved) {
    const d = authorize(data.directory, { accountId: moved.fromAccountId, userId: s.userId }, `${service}:get`, moved.from)
    if (!d.allowed) return refused(d, `mock-moved-${moved.resourceId}`)
    return { status: 410, body: { error: 'resource.transferred', detail: { wfrn: moved.from, transferredTo: moved.to, transferredAt: moved.at, toAccountId: moved.toAccountId, toAccountName: accountOf(data.directory, moved.toAccountId)?.name } } }
  }
  if (sub !== 'transfer-targets' && (verb !== 'transfer' || sub)) return null

  const r = resourceByWfrn(data, rt, wfrnParam)
  if (!r || accountOfResource(r, data.directory.accounts.map((a) => a.accountId)) !== s.accountId) return { status: 404, body: { error: 'resource.not-found', detail: `No ${noun} lives at ${wfrnParam} in this account` } }
  const wfrn = wfrnOfResource(data, s, rt, r)
  const source = moveDecision(data, s.userId, s.accountId, `${service}:transfer`, wfrn)

  if (sub === 'transfer-targets') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const read = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `${service}:get`, wfrn)
    if (!read.allowed) return refused(read, `mock-targets-${r.id}`)
    const targets = data.directory.accounts
      .filter((a) => isMember(data.directory, a.accountId, s.userId))
      .map((a) => {
        const d = moveDecision(data, s.userId, a.accountId, `${service}:create`, wfrnPatternOf(data, { ...s, accountId: a.accountId }, rt))
        return { accountId: a.accountId, name: a.name, current: a.accountId === s.accountId, allowed: d.allowed && a.status === 'active', ...(d.denied ? { denied: d.denied } : {}) }
      })
    return { status: 200, body: { wfrn, name: r.name ?? r.id, source: source.allowed ? { allowed: true } : { allowed: false, denied: source.denied }, targets } }
  }

  if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  const body = ctx.body
  const toAccountId = typeof body.toAccountId === 'string' ? body.toAccountId : ''
  if (!/^\d{12}$/.test(toAccountId)) return problems400([{ level: 'error', code: 'required', field: '/toAccountId', message: 'Name the account to move it to, a 12-digit account id' }])
  if (body.reason !== undefined && (typeof body.reason !== 'string' || body.reason.length > 500)) return problems400([{ level: 'error', code: 'maxLength', field: '/reason', message: 'The reason is at most 500 characters' }])
  if (body.confirm !== true) return problems400([{ level: 'error', code: 'confirm.required', field: '/confirm', message: 'Moving a resource needs confirm: true' }])
  if (toAccountId === s.accountId) return { status: 400, body: { error: 'transfer.sameAccount', detail: `The ${noun} is already in this account; pick another one` } }
  if (!source.allowed) return refused(source, `mock-transfer-${r.id}`)
  const target = accountOf(data.directory, toAccountId)
  if (!target) return { status: 404, body: { error: 'account.not-found', detail: `No account ${toAccountId}` } }
  const there = isMember(data.directory, toAccountId, s.userId)
    ? moveDecision(data, s.userId, toAccountId, `${service}:create`, wfrnPatternOf(data, { ...s, accountId: toAccountId }, rt))
    : { allowed: false, denied: { layer: 'console-iam', action: `${service}:create`, resource: wfrnPatternOf(data, { ...s, accountId: toAccountId }, rt), reason: `not a member of ${target.name}`, requestable: false } as Denial }
  if (!there.allowed) return refused(there, `mock-transfer-${r.id}-${toAccountId}`)
  const authTime = Date.parse(s.authTime ?? '')
  if (!Number.isFinite(authTime) || ctx.now - authTime > STEP_UP_MAX_AGE_MS) return stepUpRefusal(`${service}:transfer`)
  if (target.status !== 'active') return { status: 409, body: { error: 'account.shredded', detail: `${target.name} is closed; nothing moves into it` } }
  if (classOf(rt, r.status) !== 'active') return { status: 409, body: { error: 'resource.notActive', detail: `Only an active ${noun} moves; this one is ${r.status}` } }
  if (busyOn(data, r)) return { status: 409, body: { error: 'resource.busy', detail: `A request is changing this ${noun}; it moves once nothing runs on it` } }
  return journaled(data, ctx, { at: ctx.now, kind: 'transfer.move', userId: s.userId, accountId: s.accountId, region: s.region, type: rt.name, wfrn, body: { toAccountId, ...(typeof body.reason === 'string' && body.reason ? { reason: body.reason } : {}), confirm: true }, ids: {} })
}

/** Applies a move when it runs and when the journal replays it: the new wfrn, the account, the tags, and the old name's pointer. */
export function applyTransferCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  settleConsole(data, cmd.at)
  const rt = typeOf(data, cmd.type)
  const r = rt && cmd.wfrn ? resourceByWfrn(data, rt, cmd.wfrn) : undefined
  if (!rt || !r || !cmd.wfrn) return { status: 404, body: { error: 'resource.not-found' } }
  const body = isRecord(cmd.body) ? cmd.body : {}
  const toAccountId = String(body.toAccountId ?? '')
  const to = mintWfrn({ ...r, wfrn: undefined }, rt, { partition: data.partition, region: cmd.region, accountId: toAccountId })
  const st = data.console!
  if (st.tags[cmd.wfrn]) {
    st.tags[to] = st.tags[cmd.wfrn]
    delete st.tags[cmd.wfrn]
  }
  r.accountId = toAccountId
  r.wfrn = to
  const at = new Date(cmd.at).toISOString()
  r.updatedAt = at
  stateOf(data).moves.push({ from: cmd.wfrn, to, type: rt.name, resourceId: r.id, fromAccountId: cmd.accountId, toAccountId, at, by: cmd.userId, ...(typeof body.reason === 'string' ? { reason: body.reason } : {}) })
  return { status: 201, body: { wfrn: to, accountId: toAccountId, name: r.name ?? r.id, type: rt.name, registryId: r.id, transferredFrom: cmd.wfrn, createdAt: at, source: 'transfer' } }
}
