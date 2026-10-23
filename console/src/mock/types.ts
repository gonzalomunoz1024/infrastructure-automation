import { platformObserveOf, serviceOf, titleOf } from '../lib/descriptors'
import type { BffConsoleAction, BffConsoleType, CatalogEntry, ResourceType } from '../lib/types'

/*
 * The BFF's type descriptors, for mock mode: one hub ConsoleType per platform resource type,
 * built from the type and its catalog entries as the BFF's Descriptors does (P-06, P-08). The
 * mock passes the type's own hints through and leaves the list and detail empty where the type
 * gives none, so the console's defaults draw them. The decisions (`allowed`, `denied`) are the
 * server's to add per session (./server).
 */

/** One catalog entry as a descriptor verb: the producer of the type has the effect `create`. */
export function describeAction(e: CatalogEntry, rt: Pick<ResourceType, 'producedBy'>): BffConsoleAction {
  const effect = e.verb === 'create' || e.workflow === rt.producedBy ? 'create' : e.verb === 'delete' ? 'delete' : 'change'
  return {
    verb: e.verb,
    title: e.title,
    description: e.description,
    method: e.method,
    destructive: e.destructive ?? e.verb === 'delete',
    approval: typeof e.approval === 'object' ? !!e.approval.required : !!e.approval,
    bulk: !!e.batchPath || !!e.batch,
    preview: !!e.previewPath,
    effect,
    subjectInput: e.subject ?? null,
    input: e.input,
    workflow: e.workflow,
    version: e.version,
    deprecated: e.deprecated,
    batch: e.batch,
  }
}

/** A platform type and its catalog as the BFF describes them, without this person's decisions. */
export function describeType(rt: ResourceType, catalog: readonly CatalogEntry[]): BffConsoleType {
  const service = serviceOf(rt)
  const hints = rt.console
  return {
    key: `${service}/${rt.name}`,
    type: rt.name,
    service,
    plural: rt.plural,
    title: titleOf(rt),
    icon: typeof hints?.icon === 'string' ? hints.icon : undefined,
    description: rt.description,
    schema: rt.schema,
    states: rt.states ?? [],
    list: { columns: Array.isArray(hints?.list?.columns) ? hints.list.columns : [], defaultSort: hints?.list?.defaultSort },
    detail: { tabs: Array.isArray(hints?.detail?.tabs) ? hints.detail.tabs : [] },
    lifecycle: { ...(hints?.lifecycle ?? {}) },
    observe: platformObserveOf(rt),
    actions: catalog.filter((e) => e.noun === rt.name).map((e) => describeAction(e, rt)),
    owner: rt.owner,
    visibility: rt.visibility,
    etag: '',
  }
}

/** Every type of the dataset as the BFF describes it. */
export function describeTypes(data: { resourceTypes: readonly ResourceType[]; catalog: readonly CatalogEntry[] }): BffConsoleType[] {
  return data.resourceTypes.map((rt) => describeType(rt, data.catalog))
}
