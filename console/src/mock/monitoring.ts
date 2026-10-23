import type { ConsoleCommand, RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * The BFF's monitoring and rightsizing routes (the drift thread's
 * /mnt/project-files/console-drift/drift-bff-routes.md): a resource's metric series, alarms per
 * resource and per account, and the idle and oversized recommendations with apply and dismiss.
 * Writes are journaled through `journaled` in ./commands under the `alarm.` and
 * `recommendation.` kinds, so a reload replays them.
 */

/** What these routes keep beside the dataset, made on first use. */
export interface MonitoringState {
  alarms: Record<string, unknown>
}

export function emptyMonitoringState(): MonitoringState {
  return { alarms: {} }
}

/** Answers the metrics, alarms and recommendations paths, or null for any other path. */
export function monitoringRoutes(_data: MockData, _ctx: RouteContext, _path: string, _params: URLSearchParams, _method: string): RouteAnswer | null {
  return null
}

/** Applies a journaled `alarm.*` or `recommendation.*` command, when it runs and when the journal replays it. */
export function applyMonitoringCommand(_data: MockData, cmd: ConsoleCommand): RouteAnswer {
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}
