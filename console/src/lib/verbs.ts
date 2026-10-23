import type { ConsoleAction, ConsoleType } from './descriptors'

/**
 * The verbs that run on one resource: every action of its type but the one that creates it and
 * its update, which Edit configuration runs. A type made by a producer workflow (images by
 * build-vm-image) has no `create` verb, so the producer is left out by its workflow, not by its
 * verb.
 */
export function verbsOf(type: Pick<ConsoleType, 'actions' | 'create'>): ConsoleAction[] {
  return type.actions.filter((a) => a.verb !== 'create' && a.verb !== 'update' && a.workflow !== type.create?.workflow)
}

/** The verbs that run on many resources at once: `bulk` in the BFF's descriptor. */
export function bulkVerbsOf(type: Pick<ConsoleType, 'actions' | 'create'>): ConsoleAction[] {
  return verbsOf(type).filter((a) => a.bulk)
}
