/**
 * n8n's "tidy up", written by hand: left to right, 100 px nodes, 150 px horizontal gaps, the
 * branches of a container stacked vertically to its right, the join on the container's row.
 * Triggers sit in column 0, the catch chain under the main flow behind its Error trigger.
 */
import { API_TRIGGER_ID, COLUMN, ERROR_TRIGGER_ID, IN_HANDLE, MAIN_HANDLE, ROW, UNDO_HANDLE, isStepNode, type Graph, type GraphEdge, type GraphNode, type Position } from './graph'

export const CANVAS_ORIGIN: Position = { x: 60, y: 60 }

interface Placed {
  positions: Map<string, Position>
}

class Layout {
  readonly positions = new Map<string, Position>()
  private readonly byId = new Map<string, GraphNode>()
  private readonly outs = new Map<string, Map<string, GraphEdge[]>>()
  private readonly placed = new Set<string>()

  constructor(private readonly graph: Graph) {
    for (const n of graph.nodes) this.byId.set(n.id, n)
    for (const e of graph.edges) {
      let m = this.outs.get(e.source)
      if (!m) {
        m = new Map()
        this.outs.set(e.source, m)
      }
      const list = m.get(e.sourceHandle) ?? []
      list.push(e)
      m.set(e.sourceHandle, list)
    }
  }

  private out(nodeId: string, handle: string): GraphEdge | undefined {
    return this.outs.get(nodeId)?.get(handle)?.[0]
  }

  private handles(nodeId: string, prefix: string): string[] {
    const m = this.outs.get(nodeId)
    if (!m) return []
    return [...m.keys()].filter((h) => h.startsWith(prefix)).sort((a, b) => Number(a.slice(prefix.length)) - Number(b.slice(prefix.length)))
  }

  private put(id: string, col: number, row: number): void {
    this.positions.set(id, { x: CANVAS_ORIGIN.x + col * COLUMN, y: CANVAS_ORIGIN.y + row * ROW })
    this.placed.add(id)
  }

  /**
   * Lays out a chain from `start`, stopping at an edge that leaves the chain (join, loop,
   * fallthrough) or at a node already placed. Returns the width in columns and height in rows.
   */
  chain(start: string | null | undefined, col: number, row: number): { width: number; height: number } {
    let width = 0
    let height = 1
    let cur = start ?? null
    while (cur) {
      const node = this.byId.get(cur)
      if (!node || this.placed.has(cur)) break
      const b = this.block(node, col + width, row)
      width += b.width
      height = Math.max(height, b.height)
      cur = b.next
    }
    return { width, height }
  }

  /** One node and, for containers, its branches; returns how far the chain continues. */
  private block(node: GraphNode, col: number, row: number): { width: number; height: number; next: string | null } {
    this.put(node.id, col, row)
    let width = 1
    let height = 1
    let next: string | null = null
    const undo = this.out(node.id, UNDO_HANDLE)
    if (undo) {
      this.put(undo.target, col, row + 1)
      height = 2
    }
    if (!isStepNode(node)) {
      const e = this.out(node.id, MAIN_HANDLE)
      return { width, height, next: e ? e.target : null }
    }
    const type = node.data.step.type
    const stack = (handles: string[], firstRow: number): { depth: number; rows: number } => {
      let depth = 0
      let rows = 0
      for (const h of handles) {
        const e = this.out(node.id, h)
        if (!e) continue
        if (e.role === 'branch') {
          const r = this.chain(e.target, col + 1, firstRow + rows)
          depth = Math.max(depth, r.width)
          rows += r.height
        } else if (e.role === 'jump') {
          const target = this.byId.get(e.target)
          if (target && target.data.kind === 'end' && !this.placed.has(e.target)) {
            this.put(e.target, col + 1, firstRow + rows)
            depth = Math.max(depth, 1)
            rows += 1
          }
        }
      }
      return { depth, rows }
    }
    const joinTarget = (handles: string[]): string | null => {
      for (const h of handles) {
        const e = this.out(node.id, h)
        if (!e) continue
        if (e.role === 'fallthrough' || e.role === 'join') return e.target
        if (e.role === 'branch') {
          const t = this.chainExit(e.target)
          if (t) return t
        }
      }
      return null
    }
    switch (type) {
      case 'switch': {
        const handles = [...this.handles(node.id, 'case:'), 'default']
        const s = stack(handles, row)
        width = 1 + s.depth
        height = Math.max(height, s.rows)
        next = joinTarget(handles)
        if (!next) {
          // only jumps: continue at the earliest unplaced jump target
          const targets = handles
            .map((h) => this.out(node.id, h))
            .filter((e): e is GraphEdge => !!e && e.role === 'jump')
            .map((e) => this.byId.get(e.target))
            .filter(isStepNode)
            .filter((n) => !this.placed.has(n.id))
            .sort((a, b) => a.data.seq - b.data.seq)
          next = targets[0]?.id ?? null
        }
        break
      }
      case 'fork': {
        const handles = this.handles(node.id, 'branch:')
        const s = stack(handles, row)
        width = 1 + s.depth
        height = Math.max(height, s.rows)
        next = joinTarget(handles)
        break
      }
      case 'try': {
        const handles = ['try', 'catch']
        const s = stack(handles, row)
        width = 1 + s.depth
        height = Math.max(height, s.rows)
        next = joinTarget(handles)
        break
      }
      case 'for': {
        const loop = this.out(node.id, 'loop')
        if (loop && loop.role === 'branch') {
          const r = this.chain(loop.target, col + 1, row + 1)
          width = 1 + r.width
          height = Math.max(height, 1 + r.height)
        }
        const done = this.out(node.id, 'done')
        next = done ? done.target : null
        break
      }
      default: {
        const e = this.out(node.id, MAIN_HANDLE)
        if (e && e.role === 'main') next = e.target
      }
    }
    return { width, height, next }
  }

  /** Where a chain starting at `start` leaves to (the target of its join edge), without placing anything. */
  private chainExit(start: string): string | null {
    const seen = new Set<string>()
    let cur: string | null = start
    while (cur && !seen.has(cur)) {
      seen.add(cur)
      const node = this.byId.get(cur)
      if (!isStepNode(node)) return null
      const t = node.data.step.type
      const handles = t === 'switch' ? [...this.handles(cur, 'case:'), 'default'] : t === 'fork' ? this.handles(cur, 'branch:') : t === 'try' ? ['try', 'catch'] : t === 'for' ? ['done'] : [MAIN_HANDLE]
      let next: string | null = null
      for (const h of handles) {
        const e = this.out(cur, h)
        if (!e) continue
        if (e.role === 'join' || e.role === 'fallthrough') return e.target
        if (e.role === 'main') next = e.target
        if (e.role === 'branch') {
          const inner = this.chainExit(e.target)
          if (inner) return inner
        }
      }
      cur = next
    }
    return null
  }

  run(): Placed {
    const triggers = this.graph.nodes.filter((n) => n.data.kind === 'trigger').sort((a, b) => (a.id === API_TRIGGER_ID ? -1 : b.id === API_TRIGGER_ID ? 1 : a.id.localeCompare(b.id)))
    triggers.forEach((t, i) => this.put(t.id, 0, i))
    const first = this.out(API_TRIGGER_ID, MAIN_HANDLE)?.target ?? triggers.map((t) => this.out(t.id, MAIN_HANDLE)?.target).find(Boolean) ?? null
    const main = this.chain(first, 1, 0)
    let row = Math.max(main.height, triggers.length)
    // chains nobody reached (dead code after a jump): keep them visible under the main flow
    for (const n of this.graph.nodes) {
      if (!isStepNode(n) || this.placed.has(n.id) || n.data.inCatch) continue
      const r = this.chain(n.id, 1, row)
      row += r.height
    }
    const error = this.byId.get(ERROR_TRIGGER_ID)
    if (error) {
      this.put(ERROR_TRIGGER_ID, 0, row + 0.5)
      const firstCatch = this.out(ERROR_TRIGGER_ID, MAIN_HANDLE)?.target
      const r = this.chain(firstCatch, 1, row + 0.5)
      row += r.height + 0.5
    }
    for (const n of this.graph.nodes) {
      if (this.placed.has(n.id)) continue
      this.put(n.id, 1, row++)
    }
    return { positions: this.positions }
  }
}

/** A position for every node of the graph; the graph itself is not changed. */
export function autoLayout(graph: Graph): Map<string, Position> {
  return new Layout(graph).run().positions
}

/** The graph with every node moved to its tidy position (and marked positioned when `mark`). */
export function tidy(graph: Graph, mark = true): Graph {
  const positions = autoLayout(graph)
  return {
    ...graph,
    nodes: graph.nodes.map((n) => {
      const p = positions.get(n.id)
      return p ? { ...n, position: p, positioned: mark || n.positioned } : n
    }),
  }
}

/** A position right after a node, for a step inserted on one of its handles. */
export function positionAfter(graph: Graph, nodeId: string, handle: string): Position {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return CANVAS_ORIGIN
  const index = isStepNode(node) ? node.data.outputs.findIndex((o) => o.id === handle) : 0
  const base = { x: node.position.x + COLUMN, y: node.position.y + Math.max(0, index) * ROW }
  const taken = (p: Position) => graph.nodes.some((n) => Math.abs(n.position.x - p.x) < 40 && Math.abs(n.position.y - p.y) < 40)
  let p = base
  let guard = 0
  while (taken(p) && guard++ < 20) p = { x: p.x, y: p.y + ROW }
  return p
}

export { IN_HANDLE }
