import { openStream, type Stream, type StreamOptions } from './sse'

/*
 * The platform's event types, as the hub's AsyncAPI names them (contracts/platform/asyncapi-target.yaml,
 * pinned in contracts.lock). The platform's stream names every server-sent event after its type
 * (`event: job.started`, `id:` the sequence, `data:` the envelope), and EventSource only hands a
 * named event to a listener registered under that name, so a consumer of the feed must know them
 * all; events.test.ts fails when this list and the contract differ.
 */
export const PLATFORM_EVENT_TYPES = [
  'job.started',
  'job.step',
  'job.attention',
  'job.attention-resolved',
  'job.approval-requested',
  'job.approval-decided',
  'job.approval-escalated',
  'job.cancelled',
  'job.finished',
  'batch.wave',
  'resource.registered',
  'resource.retired',
  'resource-type.changed',
  'workflow.published',
  'workflow.deleted',
  'provider.published',
  'provider.updated',
  'region.changed',
  'content.published',
  'release.started',
  'release.promoted',
  'release.rolled-back',
  'audit.sealed',
  'audit.shared',
] as const

export type PlatformEventType = (typeof PLATFORM_EVENT_TYPES)[number]

/** The platform feed (or a job's stream): `openStream` listening to every event type the platform names. */
export function openPlatformStream<T = unknown>(url: string, onEvent: (event: T, raw: MessageEvent) => void, opts: StreamOptions = {}): Stream {
  return openStream<T>(url, onEvent, { ...opts, events: [...PLATFORM_EVENT_TYPES, ...(opts.events ?? [])] })
}
