import { EVENT_GROUPS, subscriptionProblems, type Delivery, type Subscription, type SubscriptionInput, type SubscriptionTarget, type TargetInput } from '../lib/subscriptions'
import { emptyConsoleState, idFrom, iso, isRecord, journaled, pageOf, refused, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { authorize, type Decision } from './directory'

/*
 * Event subscriptions (WP 9.14), as the console asks the BFF for them (no hub contract has them
 * yet, README decision 103): an account's events to a webhook, a Kafka topic or a Slack channel,
 * created, tested and deleted. Every write is a journaled command (`subscription.*`). A test sends
 * one sample event of the first type the subscription names and records the delivery: a webhook
 * whose host says it is unreachable (`invalid`, `unreachable`) fails to connect, one whose path
 * says `fail` answers 500, any other answers 200; a topic and a channel always take it. The
 * secret is kept sealed and only `secretSet` is ever read back.
 *
 * Who may: everyone in the account reads them (`event-subscriptions:list` and `get`), an operator
 * also creates and tests, only an admin deletes, as the managed roles' policies say; the platform's
 * role for the account never decides them.
 */

interface SubscriptionRecord extends Omit<Subscription, 'lastDelivery' | 'status'> {
  secret?: string
  deliveries: Delivery[]
}

export interface SubscriptionsState {
  items: Record<string, SubscriptionRecord>
}

export function emptySubscriptionsState(): SubscriptionsState {
  return { items: {} }
}

function stateOf(data: MockData): SubscriptionsState {
  const st = (data.console ??= emptyConsoleState())
  return (st.subscriptions ??= emptySubscriptionsState())
}

function view(rec: SubscriptionRecord): Subscription {
  const { secret: _secret, deliveries, ...rest } = rec
  const last = deliveries[0]
  return { ...rest, status: last && last.status === 'failed' ? 'failing' : 'active', ...(last ? { lastDelivery: last } : {}) }
}

function decisionOf(data: MockData, s: RouteContext['session'], verb: string, id = '*'): Decision {
  const resource = `wfrn:${data.partition}:events:${s.region}:${s.accountId}:subscription/${id}`
  const d = authorize(data.directory, { accountId: s.accountId, userId: s.userId }, `event-subscriptions:${verb}`, resource)
  return !d.allowed && d.denied?.layer === 'platform' ? { allowed: true } : d
}

const correlationOf = (now: number, path: string) => `mock-${idFrom(`${now}:${path}`, 8)}`

function targetOf(raw: unknown): TargetInput | null {
  if (!isRecord(raw)) return null
  if (raw.kind === 'webhook' && typeof raw.url === 'string') return { kind: 'webhook', url: raw.url, ...(typeof raw.secret === 'string' ? { secret: raw.secret } : {}) }
  if (raw.kind === 'kafka' && typeof raw.topic === 'string') return { kind: 'kafka', topic: raw.topic }
  if (raw.kind === 'slack' && typeof raw.channel === 'string') return { kind: 'slack', channel: raw.channel }
  return null
}

/** How a test delivery to the target goes, worked out from the address so it is the same every time. */
export function deliver(target: SubscriptionTarget, at: number, seed: string, eventType: string, test: boolean): Delivery {
  const base = { deliveryId: `dlv_${idFrom(`${seed}:${at}`, 10)}`, at: iso(at), test, eventType }
  if (target.kind === 'webhook') {
    let url: URL | null
    try {
      url = new URL(target.url)
    } catch {
      url = null
    }
    if (!url || /(^|\.)(invalid|unreachable)(\.|$)/.test(url.hostname)) return { ...base, status: 'failed', error: `Could not connect to ${url?.hostname ?? target.url}: the name does not resolve` }
    if (/fail/i.test(url.pathname)) return { ...base, status: 'failed', httpStatus: 500, latencyMs: 212, error: 'The endpoint answered 500 Internal Server Error' }
    return { ...base, status: 'delivered', httpStatus: 200, latencyMs: 40 + (parseInt(idFrom(seed, 2), 36) % 160) }
  }
  return { ...base, status: 'delivered', latencyMs: target.kind === 'kafka' ? 8 : 310 }
}

export function subscriptionRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  if (!path.startsWith('/console/v1/event-subscriptions')) return null
  const s = ctx.session
  const now = ctx.now
  const command = (kind: ConsoleCommand['kind'], body: unknown, id?: string): ConsoleCommand => ({ at: now, kind, userId: s.userId, accountId: s.accountId, region: s.region, type: '', body, ids: id ? { requestId: id } : {} })
  const key = ctx.headers['idempotency-key']
  if ((method === 'POST' || method === 'DELETE') && key && data.console?.replies[key]) return journaled(data, ctx, command('subscription.replayed', ctx.body))
  const mine = () => Object.values(stateOf(data).items).filter((x) => x.accountId === s.accountId)

  if (path === '/console/v1/event-subscriptions') {
    if (method === 'GET') {
      const d = decisionOf(data, s, 'list')
      if (!d.allowed) return refused(d, correlationOf(now, path))
      return pageOf(mine().sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(view), params)
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = decisionOf(data, s, 'create')
    if (!d.allowed) return refused(d, correlationOf(now, path))
    const b = ctx.body
    const target = targetOf(b.target)
    const types = new Set(data.resourceTypes.map((t) => t.name))
    const body: SubscriptionInput = { name: typeof b.name === 'string' ? b.name.trim() : '', events: Array.isArray(b.events) ? b.events.filter((e): e is string => typeof e === 'string') : [], resourceTypes: Array.isArray(b.resourceTypes) ? b.resourceTypes.filter((e): e is string => typeof e === 'string') : [], target: target ?? { kind: 'slack', channel: '' } }
    const problems = [...(target ? [] : [{ field: '/target', message: 'Send it to a webhook, a Kafka topic or a Slack channel' }]), ...(target ? subscriptionProblems(body) : []), ...(body.resourceTypes ?? []).filter((t) => !types.has(t)).map((t) => ({ field: '/resourceTypes', message: `No resource type ${t}` }))]
    if (problems.length) return { status: 400, body: { error: 'validation.failed', detail: problems.map((p) => p.message).join('; '), problems: problems.map((p) => ({ level: 'error', code: 'invalid', ...p })) } }
    if (mine().length >= 20) return { status: 409, body: { error: 'subscriptions.limit', message: 'An account has at most 20 subscriptions; delete one first' } }
    if (mine().some((x) => x.name.toLowerCase() === body.name.toLowerCase())) return { status: 409, body: { error: 'subscription.nameTaken', message: `The account already has a subscription named ${body.name}`, problems: [{ level: 'error', code: 'subscription.nameTaken', field: '/name', message: `The account already has a subscription named ${body.name}` }] } }
    return journaled(data, ctx, command('subscription.create', body))
  }

  const m = /^\/console\/v1\/event-subscriptions\/([^/:]+)(?::(test)|\/(deliveries))?$/.exec(path)
  if (!m) return null
  const id = decodeURIComponent(m[1])
  const rec = stateOf(data).items[id]
  if (!rec || rec.accountId !== s.accountId) return { status: 404, body: { error: 'subscription.notFound', detail: { subscriptionId: id } } }
  if (m[3]) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = decisionOf(data, s, 'get', id)
    return d.allowed ? pageOf(rec.deliveries, params) : refused(d, correlationOf(now, path))
  }
  if (m[2]) {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = decisionOf(data, s, 'test', id)
    if (!d.allowed) return refused(d, correlationOf(now, path))
    return journaled(data, ctx, command('subscription.test', { subscriptionId: id }))
  }
  if (method === 'GET') {
    const d = decisionOf(data, s, 'get', id)
    return d.allowed ? { status: 200, body: view(rec), headers: { ETag: `"${rec.version}"` } } : refused(d, correlationOf(now, path))
  }
  if (method === 'DELETE') {
    const d = decisionOf(data, s, 'delete', id)
    if (!d.allowed) return refused(d, correlationOf(now, path))
    return journaled(data, ctx, command('subscription.delete', { subscriptionId: id }))
  }
  return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
}

/** Applies a journaled `subscription.*` command, when it runs and when the journal replays it. */
export function applySubscriptionCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = stateOf(data)
  const body = (isRecord(cmd.body) ? cmd.body : {}) as Partial<SubscriptionInput> & { subscriptionId?: string }
  if (cmd.kind === 'subscription.create') {
    const id = cmd.ids.requestId ?? `sub_${idFrom(`${cmd.accountId}:${body.name}:${cmd.at}`, 10)}`
    cmd.ids = { ...cmd.ids, requestId: id }
    const t = body.target!
    const target: SubscriptionTarget = t.kind === 'webhook' ? { kind: 'webhook', url: t.url, secretSet: !!t.secret } : t
    st.items[id] = { subscriptionId: id, name: body.name ?? id, accountId: cmd.accountId, events: body.events ?? [], resourceTypes: body.resourceTypes ?? [], target, createdAt: iso(cmd.at), createdBy: cmd.userId, version: 1, deliveries: [], ...(t.kind === 'webhook' && t.secret ? { secret: t.secret } : {}) }
    return { status: 201, body: view(st.items[id]) }
  }
  const rec = body.subscriptionId ? st.items[body.subscriptionId] : undefined
  if (!rec) return { status: 404, body: { error: 'subscription.notFound' } }
  if (cmd.kind === 'subscription.delete') {
    delete st.items[rec.subscriptionId]
    return { status: 204 }
  }
  if (cmd.kind === 'subscription.test') {
    const eventType = rec.events[0] ?? EVENT_GROUPS[0].events[0].type
    const d = deliver(rec.target, cmd.at, `${rec.subscriptionId}:${rec.deliveries.length}`, eventType, true)
    rec.deliveries.unshift(d)
    rec.deliveries = rec.deliveries.slice(0, 50)
    rec.version += 1
    return { status: 200, body: d }
  }
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}
