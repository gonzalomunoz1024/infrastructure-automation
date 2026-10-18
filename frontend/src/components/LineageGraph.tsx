import { cx } from '@iaf/ui'
import { href, pathFor } from '../app/router'
import type { Lineage, LineageEdge, Resource } from '../lib/types'

/*
 * Where a resource came from and what is built from it, as a small SVG laid out left to right:
 * what it was built from at the left (further up the chain further left), the resource in the
 * accent, what is built from it at the right, hairline edges between them. The backend answers the
 * whole graph (`nodes`, `edges`, transitive); an older one answers only the direct neighbours
 * (`builtFrom`, `dependents`), drawn the same way. Every node is a link to its own page.
 */

const NODE_W = 168
const NODE_H = 46
const ROW_GAP = 14
const COL_GAP = 72
const PAD = 8

export interface LineageGraphProps {
  lineage: Lineage
  /** navigate instead of following the href (hash routing still works without it) */
  onNavigate?: (id: string) => void
  className?: string
}

/** The graph of a lineage, each resource once: the backend's nodes and edges, or the direct lists. */
export function lineageGraph(lineage: Lineage): { nodes: Resource[]; edges: LineageEdge[] } {
  const root = lineage.resource
  const nodes = new Map<string, Resource>([[root.id, root]])
  const transitive = !!lineage.nodes?.length && !!lineage.edges
  const listed = transitive ? (lineage.nodes ?? []) : [...lineage.builtFrom, ...lineage.dependents]
  for (const r of listed) if (!nodes.has(r.id)) nodes.set(r.id, r)
  const given: LineageEdge[] = transitive
    ? (lineage.edges ?? [])
    : [...lineage.builtFrom.map((r) => ({ from: r.id, to: root.id, via: root.createdBy?.workflow })), ...lineage.dependents.map((r) => ({ from: root.id, to: r.id, via: r.createdBy?.workflow }))]
  const seen = new Set<string>()
  const edges: LineageEdge[] = []
  for (const e of given) {
    const key = `${e.from}\u0000${e.to}`
    if (e.from === e.to || !nodes.has(e.from) || !nodes.has(e.to) || seen.has(key)) continue
    seen.add(key)
    edges.push(e)
  }
  return { nodes: [...nodes.values()], edges }
}

export type LineageColumn = { depth: number; nodes: Resource[] }
export type LineageLayout = { columns: LineageColumn[]; edges: LineageEdge[]; upstream: number; downstream: number }

function reach(from: string, next: Map<string, string[]>): Set<string> {
  const seen = new Set<string>()
  const queue = [from]
  while (queue.length) {
    for (const id of next.get(queue.shift()!) ?? []) {
      if (id === from || seen.has(id)) continue
      seen.add(id)
      queue.push(id)
    }
  }
  return seen
}

/**
 * Columns by depth: the resource at 0, what is built from it at 1, 2, … (the longest chain from
 * it, so every edge points right), what it was built from at -1, -2, …; a cycle cannot push a
 * resource further than there are resources. Within a column, resources sit near the ones they
 * connect to in the columns closer to the resource, to keep edges from crossing.
 */
export function lineageLayout(lineage: Lineage): LineageLayout {
  const { nodes, edges } = lineageGraph(lineage)
  const rootId = lineage.resource.id
  const out = new Map<string, string[]>()
  const into = new Map<string, string[]>()
  for (const e of edges) {
    out.set(e.from, [...(out.get(e.from) ?? []), e.to])
    into.set(e.to, [...(into.get(e.to) ?? []), e.from])
  }
  const down = reach(rootId, out)
  const up = reach(rootId, into)
  for (const id of down) up.delete(id)
  const limit = nodes.length
  const depth = new Map<string, number>([[rootId, 0]])
  for (const id of down) depth.set(id, 1)
  for (const id of up) depth.set(id, -1)
  for (let pass = 0; pass < limit; pass++) {
    let changed = false
    for (const e of edges) {
      const from = depth.get(e.from)
      const to = depth.get(e.to)
      if (from === undefined || to === undefined) continue
      if (down.has(e.to) && (e.from === rootId || down.has(e.from)) && from + 1 > to && from + 1 <= limit) {
        depth.set(e.to, from + 1)
        changed = true
      } else if (up.has(e.from) && (e.to === rootId || up.has(e.to)) && to - 1 < from && to - 1 >= -limit) {
        depth.set(e.from, to - 1)
        changed = true
      }
    }
    if (!changed) break
  }
  // a resource the backend listed with no path to this one: next to it, still a link
  for (const n of nodes) if (!depth.has(n.id)) depth.set(n.id, 1)

  const byDepth = new Map<number, Resource[]>()
  for (const n of nodes) byDepth.set(depth.get(n.id)!, [...(byDepth.get(depth.get(n.id)!) ?? []), n])
  const neighbours = (id: string) => [...(out.get(id) ?? []), ...(into.get(id) ?? [])]
  const position = new Map<string, number>([[rootId, 0.5]])
  const order = (d: number) => {
    const column = byDepth.get(d)
    if (!column) return
    const near = (r: Resource) => {
      const placed = neighbours(r.id).filter((id) => position.has(id) && Math.abs(depth.get(id)!) < Math.abs(d))
      return placed.length ? placed.reduce((sum, id) => sum + position.get(id)!, 0) / placed.length : 0.5
    }
    const keyed = column.map((r) => ({ r, key: near(r) }))
    keyed.sort((a, b) => a.key - b.key || (a.r.name ?? a.r.id).localeCompare(b.r.name ?? b.r.id))
    keyed.forEach(({ r }, i) => position.set(r.id, (i + 0.5) / keyed.length))
    byDepth.set(d, keyed.map(({ r }) => r))
  }
  const depths = [...byDepth.keys()]
  for (let d = 1; d <= Math.max(0, ...depths); d++) order(d)
  for (let d = -1; d >= Math.min(0, ...depths); d--) order(d)

  const columns = [...byDepth.entries()].sort(([a], [b]) => a - b).map(([d, rs]) => ({ depth: d, nodes: rs }))
  return { columns, edges, upstream: nodes.filter((n) => depth.get(n.id)! < 0).length, downstream: nodes.filter((n) => depth.get(n.id)! > 0).length }
}

function label(r: Resource): string {
  const name = r.name || r.id
  return name.length > 20 ? `${name.slice(0, 19)}…` : name
}

function Node({ r, x, y, accent, onNavigate }: { r: Resource; x: number; y: number; accent?: boolean; onNavigate?: (id: string) => void }) {
  const to = pathFor('resource', { id: r.id })
  return (
    <a
      href={href(to)}
      onClick={(e) => {
        if (!onNavigate || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
        e.preventDefault()
        onNavigate(r.id)
      }}
      className="group/node outline-none"
      aria-label={`${r.name ?? r.id}, ${r.type}, ${r.status}`}
    >
      <rect
        x={x}
        y={y}
        width={NODE_W}
        height={NODE_H}
        rx={10}
        className={cx('transition-colors', accent ? 'fill-accent-tint stroke-accent' : 'fill-surface stroke-stroke group-hover/node:stroke-accent group-focus-visible/node:stroke-accent')}
        strokeWidth={accent ? 1.5 : 1}
      />
      <text x={x + 12} y={y + 19} className={cx('text-caption font-medium', accent ? 'fill-accent' : 'fill-primary')}>
        {label(r)}
      </text>
      <text x={x + 12} y={y + 35} className="fill-tertiary text-caption2">
        {r.type} · {r.status}
      </text>
    </a>
  )
}

export function LineageGraph({ lineage, onNavigate, className }: LineageGraphProps) {
  const layout = lineageLayout(lineage)
  const root = lineage.resource
  // keep a column either side of the resource, for the sentence when that side is empty
  const minDepth = Math.min(-1, ...layout.columns.map((c) => c.depth))
  const maxDepth = Math.max(1, ...layout.columns.map((c) => c.depth))
  const cols = maxDepth - minDepth + 1
  const rows = Math.max(1, ...layout.columns.map((c) => c.nodes.length))
  const height = PAD * 2 + rows * NODE_H + (rows - 1) * ROW_GAP
  const width = PAD * 2 + NODE_W * cols + COL_GAP * (cols - 1)
  const xOf = (d: number) => PAD + (d - minDepth) * (NODE_W + COL_GAP)
  const yOf = (i: number, n: number) => PAD + (height - PAD * 2 - (n * NODE_H + (n - 1) * ROW_GAP)) / 2 + i * (NODE_H + ROW_GAP)
  const midY = yOf(0, 1) + NODE_H / 2

  const at = new Map<string, { x: number; y: number; r: Resource }>()
  for (const c of layout.columns) c.nodes.forEach((r, i) => at.set(r.id, { x: xOf(c.depth), y: yOf(i, c.nodes.length), r }))

  const edge = (x1: number, y1: number, x2: number, y2: number) => {
    const dx = Math.max(COL_GAP / 2, Math.abs(x2 - x1) / 2)
    return `M${x1} ${y1} C${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
  }
  const name = (r: Resource) => r.name ?? r.id

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      className={cx('mx-auto block max-w-none', className)}
      role="img"
      aria-label={`Lineage of ${name(root)}: built from ${lineage.builtFrom.length}, ${lineage.dependents.length} dependents${layout.upstream > lineage.builtFrom.length || layout.downstream > lineage.dependents.length ? `; ${layout.upstream} upstream and ${layout.downstream} downstream in all` : ''}`}
    >
      <g className="stroke-stroke" fill="none" strokeWidth={1}>
        {layout.edges.map((e) => {
          const from = at.get(e.from)
          const to = at.get(e.to)
          if (!from || !to) return null
          return (
            <path key={`${e.from}>${e.to}`} d={edge(from.x + NODE_W, from.y + NODE_H / 2, to.x, to.y + NODE_H / 2)} data-edge={`${e.from}>${e.to}`}>
              <title>{`${name(to.r)} was built from ${name(from.r)}${e.via ? ` by ${e.via}` : ''}`}</title>
            </path>
          )
        })}
      </g>
      {layout.upstream === 0 && (
        <text x={xOf(-1) + NODE_W / 2} y={midY + 4} textAnchor="middle" className="fill-tertiary text-caption">
          not built from another resource
        </text>
      )}
      {layout.downstream === 0 && (
        <text x={xOf(1) + NODE_W / 2} y={midY + 4} textAnchor="middle" className="fill-tertiary text-caption">
          nothing depends on it
        </text>
      )}
      {[...at.values()].map(({ r, x, y }) => (
        <Node key={r.id} r={r} x={x} y={y} accent={r.id === root.id} onNavigate={onNavigate} />
      ))}
    </svg>
  )
}
