import { get, page, request, type ListParams } from './api'
import { idempotencyKey } from './bff'

/*
 * Event subscriptions (WP 9.14): an account's resource events sent on to a webhook, a Kafka topic
 * or a Slack channel, like EventBridge rules. No hub contract has them yet, so the console calls
 * routes it defines under /console/v1/event-subscriptions with the BFF's conventions (the session's
 * account, an Idempotency-Key on every write, a 403 with the Denial), listed as a contract gap in
 * the README (decision 103). The events are the console's own topics in
 * contracts/asyncapi/console-events.yaml (state.resources, state.stacks, drift.findings,
 * drift.scorecards), named by their message types; a webhook's secret signs each delivery and is
 * never read back.
 */

export type TargetKind = 'webhook' | 'kafka' | 'slack'

export type SubscriptionTarget = { kind: 'webhook'; url: string; secretSet?: boolean } | { kind: 'kafka'; topic: string } | { kind: 'slack'; channel: string }

/** What a create sends: a webhook's secret travels once and is kept sealed. */
export type TargetInput = { kind: 'webhook'; url: string; secret?: string } | { kind: 'kafka'; topic: string } | { kind: 'slack'; channel: string }

export interface Delivery {
  deliveryId: string
  at: string
  /** a test delivery, or a real event */
  test: boolean
  eventType: string
  status: 'delivered' | 'failed'
  /** a webhook's answer */
  httpStatus?: number
  latencyMs?: number
  error?: string
}

export interface Subscription {
  subscriptionId: string
  name: string
  accountId: string
  events: string[]
  /** only these resource types; every type when empty */
  resourceTypes: string[]
  target: SubscriptionTarget
  status: 'active' | 'failing'
  createdAt: string
  createdBy: string
  lastDelivery?: Delivery
  version: number
}

export interface SubscriptionInput {
  name: string
  events: string[]
  resourceTypes?: string[]
  target: TargetInput
}

const BASE = '/console/v1/event-subscriptions'
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const subscriptionsApi = {
  list: (params?: ListParams) => page<Subscription>(BASE, params),
  get: (id: string) => get<Subscription>(`${BASE}/${enc(id)}`),
  create: (body: SubscriptionInput, key?: string) => request<Subscription>('POST', BASE, body, keyed(key)),
  remove: (id: string, key?: string) => request<void>('DELETE', `${BASE}/${enc(id)}`, undefined, keyed(key)),
  /** sends one sample event now and answers how it went */
  test: (id: string, key?: string) => request<Delivery>('POST', `${BASE}/${enc(id)}:test`, {}, keyed(key)),
  deliveries: (id: string, params?: ListParams) => page<Delivery>(`${BASE}/${enc(id)}/deliveries`, params),
}

/* ----------------------------------------------------------------------------
   The events a subscription may name, and the form's rules
   ------------------------------------------------------------------------- */

export interface EventGroup {
  topic: string
  title: string
  events: { type: string; label: string }[]
}

export const EVENT_GROUPS: EventGroup[] = [
  {
    topic: 'state.resources',
    title: 'Resources',
    events: [
      { type: 'ResourceRequested', label: 'A resource is asked for' },
      { type: 'ProvisioningSucceeded', label: 'A resource is ready' },
      { type: 'ProvisioningFailed', label: 'A resource failed to build' },
      { type: 'UpdateRequested', label: 'A change is asked for' },
      { type: 'Tagged', label: 'Tags change' },
      { type: 'DeleteRequested', label: 'A delete is asked for' },
      { type: 'Deleted', label: 'A resource is deleted' },
      { type: 'Imported', label: 'A resource is imported' },
      { type: 'Transferred', label: 'A resource moves to another account' },
    ],
  },
  {
    topic: 'drift.findings',
    title: 'Drift',
    events: [
      { type: 'FindingOpened', label: 'Drift is found' },
      { type: 'FindingClosed', label: 'A drift finding closes' },
    ],
  },
  {
    topic: 'state.stacks',
    title: 'Stacks',
    events: [
      { type: 'StackLaunched', label: 'A stack is launched' },
      { type: 'StackStepFailed', label: 'A stack’s step fails' },
      { type: 'StackDeleted', label: 'A stack is torn down' },
    ],
  },
  {
    topic: 'drift.scorecards',
    title: 'Compliance',
    events: [{ type: 'EvaluationChanged', label: 'A rule starts or stops failing' }],
  },
]

export const EVENT_TYPES: string[] = EVENT_GROUPS.flatMap((g) => g.events.map((e) => e.type))

export function eventLabel(type: string): string {
  return EVENT_GROUPS.flatMap((g) => g.events).find((e) => e.type === type)?.label ?? type
}

export const TARGET_WORDS: Record<TargetKind, { label: string; icon: 'link' | 'layers' | 'bell' }> = {
  webhook: { label: 'Webhook', icon: 'link' },
  kafka: { label: 'Kafka topic', icon: 'layers' },
  slack: { label: 'Slack channel', icon: 'bell' },
}

export const SUBSCRIPTION_NAME = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$/
export const KAFKA_TOPIC = /^[a-z0-9][a-z0-9._-]{2,248}$/
export const SLACK_CHANNEL = /^#[a-z0-9][a-z0-9_-]{0,79}$/

/** Where a target sends, in one line. */
export function targetLine(t: SubscriptionTarget): string {
  return t.kind === 'webhook' ? t.url : t.kind === 'kafka' ? t.topic : t.channel
}

/** What is wrong with a subscription before it is sent, by field (`/name`, `/events`, `/target/url`…). */
export function subscriptionProblems(body: SubscriptionInput): { field: string; message: string }[] {
  const out: { field: string; message: string }[] = []
  if (!SUBSCRIPTION_NAME.test(body.name)) out.push({ field: '/name', message: 'A name is 1 to 64 letters, digits, spaces, dots, dashes or underscores' })
  if (!body.events.length) out.push({ field: '/events', message: 'Pick at least one event' })
  const unknown = body.events.filter((e) => !EVENT_TYPES.includes(e))
  if (unknown.length) out.push({ field: '/events', message: `No event ${unknown.join(', ')}` })
  const t = body.target
  if (t.kind === 'webhook') {
    let url: URL | null
    try {
      url = new URL(t.url)
    } catch {
      url = null
    }
    if (!url || url.protocol !== 'https:') out.push({ field: '/target/url', message: 'A webhook is an https:// address' })
    if (t.secret !== undefined && t.secret !== '' && t.secret.length < 16) out.push({ field: '/target/secret', message: 'A signing secret is at least 16 characters' })
  } else if (t.kind === 'kafka') {
    if (!KAFKA_TOPIC.test(t.topic)) out.push({ field: '/target/topic', message: 'A topic is 3 to 249 lowercase letters, digits, dots, dashes or underscores' })
  } else if (!SLACK_CHANNEL.test(t.channel)) out.push({ field: '/target/channel', message: 'A channel starts with # and has lowercase letters, digits, dashes or underscores' })
  return out
}
