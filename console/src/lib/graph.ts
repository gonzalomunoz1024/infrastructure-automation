import type { GraphNode, LineageEdge } from './state'

/*
 * The resource map's layout and keyboard (WP 9.4), apart from the drawing so they are tested on
 * their own. Resources stand in columns by how far down a chain of "built from" they are (what
 * nothing was built from at the left), each column in stacks of at most `rows` so a type with
 * hundreds of resources makes a block rather than a tower, ordered by type and then name. The
 * arrow keys move along the links (left to what it was built from, right to what was built from
 * it) and up and down within a column.
 */

export const NODE_W = 168
export const NODE_H = 40
const GAP_X = 72
const STACK_GAP = 16
const GAP_Y = 12
const PAD = 24

export interface PlacedNode {
  node: GraphNode
  x: number
  y: number
  column: number
  /** its place in its column, top to bottom, then stack by stack */
  index: number
}

export interface MapLayout {
  nodes: PlacedNode[]
  byWfrn: Map<string, PlacedNode>
  columns: PlacedNode[][]
  width: number
  height: number
  parents: Map<string, string[]>
  children: Map<string, string[]>
}

/** Each resource's column: the longest chain of parents above it among the drawn ones (a cycle stops at the node count). */
export function depthsOf(nodes: GraphNode[], edges: LineageEdge[]): Map<string, number> {
  const ids = new Set(nodes.map((n) => n.wfrn))
  const parents = new Map<string, string[]>()
  for (const e of edges) if (ids.has(e.from) && ids.has(e.to) && e.from !== e.to) parents.set(e.to, [...(parents.get(e.to) ?? []), e.from])
  const depth = new Map<string, number>()
  const visiting = new Set<string>()
  const of = (w: string): number => {
    const known = depth.get(w)
    if (known !== undefined) return known
    if (visiting.has(w)) return 0
    visiting.add(w)
    const d = Math.min(nodes.length, Math.max(-1, ...(parents.get(w) ?? []).map(of)) + 1)
    visiting.delete(w)
    depth.set(w, d)
    return d
  }
  for (const n of nodes) of(n.wfrn)
  return depth
}

export function layoutMap(nodes: GraphNode[], edges: LineageEdge[], rows = 16): MapLayout {
  const depth = depthsOf(nodes, edges)
  const parents = new Map<string, string[]>()
  const children = new Map<string, string[]>()
  const ids = new Set(nodes.map((n) => n.wfrn))
  for (const e of edges) {
    if (!ids.has(e.from) || !ids.has(e.to)) continue
    parents.set(e.to, [...(parents.get(e.to) ?? []), e.from])
    children.set(e.from, [...(children.get(e.from) ?? []), e.to])
  }
  const byColumn = new Map<number, GraphNode[]>()
  for (const n of nodes) {
    const d = depth.get(n.wfrn) ?? 0
    byColumn.set(d, [...(byColumn.get(d) ?? []), n])
  }
  const placed: PlacedNode[] = []
  const columns: PlacedNode[][] = []
  let x = PAD
  let height = 0
  for (const d of [...byColumn.keys()].sort((a, b) => a - b)) {
    const list = byColumn.get(d)!.sort((a, b) => a.type.localeCompare(b.type) || (a.name ?? a.wfrn).localeCompare(b.name ?? b.wfrn))
    const stacks = Math.ceil(list.length / rows)
    const column = list.map((node, i): PlacedNode => ({ node, column: columns.length, index: i, x: x + Math.floor(i / rows) * (NODE_W + STACK_GAP), y: PAD + (i % rows) * (NODE_H + GAP_Y) }))
    placed.push(...column)
    columns.push(column)
    height = Math.max(height, PAD * 2 + Math.min(rows, list.length) * (NODE_H + GAP_Y) - GAP_Y)
    x += stacks * (NODE_W + STACK_GAP) - STACK_GAP + GAP_X
  }
  return { nodes: placed, byWfrn: new Map(placed.map((p) => [p.node.wfrn, p])), columns, width: Math.max(x - GAP_X + PAD, NODE_W + 2 * PAD), height: Math.max(height, NODE_H + 2 * PAD), parents, children }
}

export type MapKey = 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight' | 'Home' | 'End'

/** Where a key moves the focus from a resource: along its links left and right, within its column up and down. */
export function moveFrom(layout: MapLayout, from: string | undefined, key: MapKey): string | undefined {
  const all = layout.nodes
  if (!all.length) return undefined
  const here = from ? layout.byWfrn.get(from) : undefined
  if (!here) return all[0].node.wfrn
  const column = layout.columns[here.column]
  const nearest = (candidates: PlacedNode[]) => [...candidates].sort((a, b) => Math.abs(a.y - here.y) - Math.abs(b.y - here.y) || Math.abs(a.x - here.x) - Math.abs(b.x - here.x))[0]?.node.wfrn
  switch (key) {
    case 'ArrowUp':
      return column[Math.max(0, here.index - 1)].node.wfrn
    case 'ArrowDown':
      return column[Math.min(column.length - 1, here.index + 1)].node.wfrn
    case 'Home':
      return column[0].node.wfrn
    case 'End':
      return column[column.length - 1].node.wfrn
    case 'ArrowLeft': {
      const linked = (layout.parents.get(here.node.wfrn) ?? []).map((w) => layout.byWfrn.get(w)).filter((p): p is PlacedNode => !!p)
      if (linked.length) return nearest(linked)
      return here.column > 0 ? nearest(layout.columns[here.column - 1]) : here.node.wfrn
    }
    case 'ArrowRight': {
      const linked = (layout.children.get(here.node.wfrn) ?? []).map((w) => layout.byWfrn.get(w)).filter((p): p is PlacedNode => !!p)
      if (linked.length) return nearest(linked)
      return here.column < layout.columns.length - 1 ? nearest(layout.columns[here.column + 1]) : here.node.wfrn
    }
  }
}

/* ----------------------------------------------------------------------------
   Colour: health, drift or cost, each with its words for the legend and the list
   ------------------------------------------------------------------------- */

export type ColourBy = 'health' | 'drift' | 'cost'
export type MapTone = 'healthy' | 'warning' | 'critical' | 'accent' | 'neutral' | 'cost-1' | 'cost-2' | 'cost-3' | 'cost-4'

export interface Shade {
  tone: MapTone
  words: string
}

export function healthOf(n: GraphNode): Shade {
  if (n.state === 'failed' || n.observedStatus === 'not_found' || n.observedStatus === 'error') return { tone: 'critical', words: n.state === 'failed' ? 'Failed' : 'Provider cannot find it' }
  if (n.state === 'pending' || n.state === 'updating' || n.state === 'deleting') return { tone: 'accent', words: n.state === 'pending' ? 'Being built' : n.state === 'updating' ? 'Changing' : 'Being deleted' }
  if (n.state === 'deleted') return { tone: 'neutral', words: 'Deleted' }
  return { tone: 'healthy', words: 'Healthy' }
}

export function driftShadeOf(n: GraphNode): Shade {
  if (!n.drift) return n.observedStatus ? { tone: 'healthy', words: 'In sync' } : { tone: 'neutral', words: 'Not watched' }
  if (n.drift.severity === 'critical') return { tone: 'critical', words: 'Drifted, critical' }
  if (n.drift.severity === 'info') return { tone: 'accent', words: 'Drifted, minor' }
  return { tone: 'warning', words: 'Drifted' }
}

/** Cost in four bands: under 25, under 100, under 250 and 250 or more a month. */
export function costShadeOf(n: GraphNode): Shade {
  const c = n.cost
  if (!c) return { tone: 'neutral', words: 'No price' }
  const money = `${c.monthly.toLocaleString(undefined, { style: 'currency', currency: c.currency, maximumFractionDigits: 0 })} a month`
  if (c.monthly < 25) return { tone: 'cost-1', words: money }
  if (c.monthly < 100) return { tone: 'cost-2', words: money }
  if (c.monthly < 250) return { tone: 'cost-3', words: money }
  return { tone: 'cost-4', words: money }
}

export function shadeOf(n: GraphNode, by: ColourBy): Shade {
  return by === 'health' ? healthOf(n) : by === 'drift' ? driftShadeOf(n) : costShadeOf(n)
}

export const LEGEND: Record<ColourBy, { tone: MapTone; words: string }[]> = {
  health: [
    { tone: 'healthy', words: 'Healthy' },
    { tone: 'accent', words: 'Being built or changed' },
    { tone: 'critical', words: 'Failed or missing' },
  ],
  drift: [
    { tone: 'healthy', words: 'In sync' },
    { tone: 'warning', words: 'Drifted' },
    { tone: 'critical', words: 'Drifted, critical' },
    { tone: 'neutral', words: 'Not watched' },
  ],
  cost: [
    { tone: 'cost-1', words: 'Under 25 a month' },
    { tone: 'cost-2', words: '25 to 100' },
    { tone: 'cost-3', words: '100 to 250' },
    { tone: 'cost-4', words: '250 or more' },
    { tone: 'neutral', words: 'No price' },
  ],
}
