import type { Lineage, Resource } from '@iaf/console-kit'
import { idOfWfrn } from './descriptors'
import type { BffLineage, ConsoleResource, ResourceRef } from './types'

/*
 * The BFF's answers in the shapes the kit's components draw (the picker's rows, the lineage
 * graph): a resource is named by its wfrn, its id the wfrn's last part, its status what the
 * provider last reported, else its lifecycle state.
 */

/** A resource the BFF names by wfrn, as a node of the kit's lineage graph (its id is the wfrn). */
export function nodeOf(ref: ResourceRef): Resource {
  return { id: ref.wfrn, wfrn: ref.wfrn, type: ref.type, name: ref.name, status: ref.state ?? '', attributes: {}, owner: '', visibility: '', createdBy: { jobId: '', workflow: '' }, builtFrom: [], createdAt: '' }
}

/** `GET /console/v1/r/{type}/{wfrn}/lineage` as the kit's Lineage: the whole graph, and the direct neighbours of the resource. */
export function lineageOf(l: BffLineage): Lineage {
  const root = nodeOf(l.resource)
  const nodes = [root, ...l.nodes.filter((n) => n.wfrn !== l.resource.wfrn).map(nodeOf)]
  const byWfrn = new Map(nodes.map((n) => [n.id, n]))
  const pick = (wfrns: string[]) => wfrns.map((w) => byWfrn.get(w)).filter((n): n is Resource => !!n)
  return {
    resource: root,
    builtFrom: pick(l.edges.filter((e) => e.to === l.resource.wfrn).map((e) => e.from)),
    dependents: pick(l.edges.filter((e) => e.from === l.resource.wfrn).map((e) => e.to)),
    nodes,
    edges: l.edges,
  }
}

/** A row of `GET /console/v1/r/{type}` as the picker lists it: the id a form's input takes, the name, the status. */
export function pickerRowOf(r: ConsoleResource): { id: string; type: string; name?: string; status: string; wfrn: string } {
  const reported = r.observed?.status
  return { id: idOfWfrn(r.wfrn) ?? r.wfrn, type: r.type, name: r.name, status: typeof reported === 'string' && reported ? reported : r.state, wfrn: r.wfrn }
}
