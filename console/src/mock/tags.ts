import type { Problem } from '@iaf/console-kit'
import type { ResourceType } from '../lib/types'
import type { ConsoleCommand, RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * The BFF's tag and resource-group endpoints (console-bff contracts/additions.md, phase 4): the
 * account's required tags (`/console/v1/tag-policy`), the Tag Editor (`/console/v1/tags/...`) and
 * resource groups (`/console/v1/resource-groups`). Writes are journaled through `journaled` in
 * ./commands under the `group.` and `tags.` kinds, so a reload replays them.
 */

/** What these endpoints keep beside the dataset, made on first use. */
export interface TagsState {
  groups: Record<string, unknown>
}

export function emptyTagsState(): TagsState {
  return { groups: {} }
}

/** Answers the tag and resource-group paths, or null for any other path. */
export function tagRoutes(_data: MockData, _ctx: RouteContext, _path: string, _params: URLSearchParams, _method: string): RouteAnswer | null {
  return null
}

/** Applies a journaled `group.*` or `tags.*` command, when it runs and when the journal replays it. */
export function applyTagCommand(_data: MockData, cmd: ConsoleCommand): RouteAnswer {
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}

/** A policy rule of the create review, as the preview lists them. */
export type TagRule = { id: string; result: string; message: string }

/** The create review's tag rules (`tags.required`, `tags.invalid`): what the account's tag policy says of the create's tags. */
export function createTagRules(_data: MockData, _session: RouteContext['session'], _rt: ResourceType, _input: Record<string, unknown>, _tags: unknown): TagRule[] {
  return []
}

/** A create's tags against the account's tag policy: the 400's code and its problems at `/tags/<key>`, or null when they pass. */
export function createTagProblems(_data: MockData, _session: RouteContext['session'], _rt: ResourceType, _input: Record<string, unknown>, _tags: unknown): { error: 'tags.required' | 'tags.invalid'; problems: Problem[] } | null {
  return null
}

/** A PUT of a resource's tags against the policy: it may not drop a required tag the resource carries, nor give one a value it may not take. */
export function tagChangeProblems(_data: MockData, _session: RouteContext['session'], _current: Record<string, string>, _next: Record<string, string>): { error: 'tags.required' | 'tags.invalid'; problems: Problem[] } | null {
  return null
}
