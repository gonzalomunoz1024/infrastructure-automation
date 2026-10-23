import type { Finding, Resource, ResourceType } from '../lib/types'
import type { ConsoleCommand, RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * The BFF's drift routes (the drift thread's /mnt/project-files/console-drift/drift-bff-routes.md,
 * over the drift service of hub PR #17): the account's findings with their summary and stats, a
 * finding's revert, accept and ignore, discovery runs and the unmanaged resources they find, with
 * import and Assign to team. Decisions are journaled through `journaled` in ./commands under the
 * `drift.` and `unmanaged.` kinds, so a reload replays them.
 */

/** What these routes keep beside the dataset, made on first use. */
export interface DriftState {
  decisions: Record<string, unknown>
}

export function emptyDriftState(): DriftState {
  return { decisions: {} }
}

/** Answers the findings, discovery and unmanaged paths, or null for any other path. */
export function driftRoutes(_data: MockData, _ctx: RouteContext, _path: string, _params: URLSearchParams, _method: string): RouteAnswer | null {
  return null
}

/** Applies a journaled `drift.*` or `unmanaged.*` command, when it runs and when the journal replays it. */
export function applyDriftCommand(_data: MockData, cmd: ConsoleCommand): RouteAnswer {
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}

/** The open findings of a resource after what people decided: the scan's (./commands findingsOf) with reverts, accepts and ignores laid over them. */
export function openFindingsOf(_data: MockData, _session: RouteContext['session'], _rt: ResourceType, _r: Resource, _now: number, scanned: Finding[]): Finding[] {
  return scanned
}

/** The closed findings of a resource, newest first, for its Drift tab's history. */
export function closedFindingsOf(_data: MockData, _session: RouteContext['session'], _rt: ResourceType, _r: Resource, _now: number): Finding[] {
  return []
}
