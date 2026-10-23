import { openStream, type Stream, type StreamOptions } from './sse'

/*
 * The BFF's live updates (GET /console/v1/events): server-sent events of the session's account,
 * each named after its type (`event: job.updated`, `id:` a cursor, `data:` the LiveEvent). The
 * BFF maps the platform's feed onto them (a job event becomes `job.updated`, a registry event
 * keeps its type, content and region changes become `types.changed` and `region.changed`) and
 * passes the console's own events through: the state service's (state.resources), the drift
 * service's findings, and the person's notifications and budget alerts (hub console-bff.yaml
 * LiveEvent, contracts/asyncapi/console-events.yaml). EventSource only hands a named event to a
 * listener registered under that name, so the feed listens to every one of them.
 */
export const LIVE_EVENT_TYPES = [
  // the platform's feed, as the BFF maps it
  'job.updated',
  'resource.registered',
  'resource.retired',
  'types.changed',
  'region.changed',
  // state.resources
  'ResourceRequested',
  'ProvisioningStarted',
  'ProvisioningSucceeded',
  'ProvisioningFailed',
  'UpdateRequested',
  'Observed',
  'DriftDetected',
  'DriftResolved',
  'Tagged',
  'DeleteRequested',
  'RetryRequested',
  'Transferred',
  'Deleted',
  'Imported',
  // drift.findings
  'FindingOpened',
  'FindingUpdated',
  'FindingClosed',
  // the person's own
  'notification',
  'budget.alert',
] as const

export type LiveEventType = (typeof LIVE_EVENT_TYPES)[number]

/** The account's live feed (or one resource's, with `?wfrn=`): `openStream` listening to every event type the BFF sends. */
export function openLiveStream<T = unknown>(url: string, onEvent: (event: T, raw: MessageEvent) => void, opts: StreamOptions = {}): Stream {
  return openStream<T>(url, onEvent, { ...opts, events: [...LIVE_EVENT_TYPES, ...(opts.events ?? [])] })
}
