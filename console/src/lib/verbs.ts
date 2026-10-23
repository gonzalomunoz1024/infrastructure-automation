import type { ConsoleAction, ConsoleType } from './descriptors'

/**
 * The verbs that run on one resource: every action of its type but the one that creates it. A
 * type made by a producer workflow (images by build-vm-image) has no `create` verb, so the
 * producer is left out by its workflow, not by its verb.
 */
export function verbsOf(type: Pick<ConsoleType, 'actions' | 'create'>): ConsoleAction[] {
  return type.actions.filter((a) => a.verb !== 'create' && a.workflow !== type.create?.workflow)
}
