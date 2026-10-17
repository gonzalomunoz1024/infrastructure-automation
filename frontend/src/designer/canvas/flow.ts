/**
 * The canvas view of the model: graph nodes and edges become xyflow nodes and edges with what
 * the canvas draws on them (issues, test-run or job states, pins, free output handles, the
 * "+" insert point of an edge), plus the sticky notes and the "Add first step" placeholder.
 * Pure functions, so the mapping is testable without a DOM.
 */
import type { Edge, Node } from '@xyflow/react'
import { jobState } from '@iaf/ui'
import { STEP_TYPES, type JsonValue, type Problem } from '@iaf/workflow-checks'
import type { RegistryProvider } from '../../lib/types'
import { API_TRIGGER_ID, COLUMN, ERROR_TRIGGER_ID, NODE_SIZE, isStepNode, type EdgeRole, type Graph, type GraphEdge, type GraphNode, type OutputHandle, type TriggerType } from '../model/graph'
import type { InsertPoint } from '../model/edits'
import type { Sticky } from '../model/stickies'
import { stepGlyph, triggerGlyph, type Glyph } from './glyphs'

export type RunState = 'running' | 'success' | 'failure' | 'waiting' | 'skipped'

export type Issues = { errors: number; warnings: number; messages: string[] }

export type RunInfo = { run: RunState; items: number | null; error?: string | null }

export type StepNodeData = {
  kind: 'step'
  stepId: string
  name: string
  subtitle: string
  typeTitle: string
  glyph: Glyph
  outputs: OutputHandle[]
  /** output handles nothing leaves from: they get n8n's "+" stub */
  free: string[]
  hasUndo: boolean
  issues: Issues | null
  run: RunState | null
  items: number | null
  pinned: boolean
  readOnly: boolean
  container: boolean
}

export type TriggerNodeData = {
  kind: 'trigger'
  triggerType: TriggerType | 'error'
  index: number | null
  label: string
  subtitle: string
  glyph: Glyph
  free: boolean
  issues: Issues | null
  run: RunState | null
  readOnly: boolean
}

export type EndNodeData = { kind: 'end' }

export type UndoNodeData = { kind: 'undo'; stepId: string; names: string[]; readOnly: boolean }

export type PlaceholderNodeData = { kind: 'placeholder'; readOnly: boolean }

export type StickyNodeData = { kind: 'sticky'; sticky: Sticky; readOnly: boolean }

export type StepFlowNode = Node<StepNodeData, 'step'>
export type TriggerFlowNode = Node<TriggerNodeData, 'trigger'>
export type EndFlowNode = Node<EndNodeData, 'end'>
export type UndoFlowNode = Node<UndoNodeData, 'undo'>
export type PlaceholderFlowNode = Node<PlaceholderNodeData, 'placeholder'>
export type StickyFlowNode = Node<StickyNodeData, 'sticky'>

export type FlowNode = StepFlowNode | TriggerFlowNode | EndFlowNode | UndoFlowNode | PlaceholderFlowNode | StickyFlowNode

export type EdgeState = 'success' | 'running' | 'failure'

export type FlowEdgeData = {
  role: EdgeRole
  state: EdgeState | null
  items: number | null
  /** where the mid-point "+" inserts; null hides it */
  insert: InsertPoint | null
  readOnly: boolean
  highlighted: boolean
  placeholder: boolean
}

export type FlowEdge = Edge<FlowEdgeData, 'flow'>

export const PLACEHOLDER_ID = 'placeholder'
export const STICKY_PREFIX = 'sticky:'

export function stickyNodeId(id: string): string {
  return `${STICKY_PREFIX}${id}`
}

// ---------------------------------------------------------------------------------------------
// issues and run states

/** Problems by step id; document-level problems land on the trigger they concern. */
export function issuesByNode(graph: Graph, problems: readonly Problem[]): Map<string, Issues> {
  const out = new Map<string, Issues>()
  const stepNode = new Map<string, string>()
  for (const n of graph.nodes) if (isStepNode(n) && !stepNode.has(n.data.stepId)) stepNode.set(n.data.stepId, n.id)
  const add = (nodeId: string, p: Problem) => {
    const cur = out.get(nodeId) ?? { errors: 0, warnings: 0, messages: [] }
    if (p.level === 'error') cur.errors++
    else cur.warnings++
    if (cur.messages.length < 6) cur.messages.push(p.message)
    out.set(nodeId, cur)
  }
  for (const p of problems) {
    if (p.step) {
      const n = stepNode.get(p.step)
      if (n) add(n, p)
      continue
    }
    const trig = p.field ? /^triggers\[(\d+)\]/.exec(p.field) : null
    if (trig) {
      add(`trigger:${trig[1]}`, p)
      continue
    }
    if (p.field && (p.field.startsWith('api') || p.field.startsWith('metadata') || p.field.startsWith('policy') || p.field.startsWith('rollout'))) add(API_TRIGGER_ID, p)
    else if (p.code === 'workflow.empty') add(API_TRIGGER_ID, p)
  }
  return out
}

/** How many items an output is: a list's length, else one (nothing for null). */
export function itemsOf(output: unknown): number | null {
  if (output === undefined || output === null) return null
  if (Array.isArray(output)) return output.length
  return 1
}

/** A step status word (job, dry run) as a canvas state. */
export function runStateOf(status: string): RunState | null {
  const s = status.toLowerCase()
  if (s === 'pending' || s === 'queued') return null
  if (s === 'compensated' || s === 'skipped') return 'skipped'
  const j = jobState(s)
  switch (j) {
    case 'running':
      return 'running'
    case 'waiting':
      return 'waiting'
    case 'succeeded':
      return 'success'
    case 'failed':
      return 'failure'
    default:
      return 'skipped'
  }
}

export interface RunStep {
  id: string
  status: string
  output?: unknown
  error?: unknown
}

/** States of the steps a dry run walked, revealed up to `revealed`; the next one is running while `animating`. */
export function testRunStates(path: readonly string[], steps: readonly RunStep[], revealed: number, animating: boolean): Map<string, RunInfo> {
  const out = new Map<string, RunInfo>()
  const byId = new Map(steps.map((s) => [s.id, s]))
  path.slice(0, revealed).forEach((id) => {
    const s = byId.get(id)
    const run = s ? (runStateOf(s.status) ?? 'success') : 'success'
    out.set(id, { run, items: itemsOf(s?.output), error: typeof s?.error === 'string' ? s.error : null })
  })
  if (animating && revealed < path.length) out.set(path[revealed], { run: 'running', items: null })
  return out
}

/** States of a job's steps (the executions view). `limit` replays them one by one. */
export function jobRunStates(steps: readonly RunStep[], limit: number | null = null): Map<string, RunInfo> {
  const out = new Map<string, RunInfo>()
  const shown = limit === null ? steps : steps.slice(0, limit)
  for (const s of shown) {
    const run = runStateOf(s.status)
    if (!run) continue
    const err = s.error && typeof s.error === 'object' && 'message' in s.error ? String((s.error as { message: unknown }).message) : typeof s.error === 'string' ? s.error : null
    out.set(s.id, { run, items: itemsOf(s.output), error: err })
  }
  if (limit !== null && limit < steps.length) out.set(steps[limit].id, { run: 'running', items: null })
  return out
}

// ---------------------------------------------------------------------------------------------
// insert points

/** Where the "+" in the middle of an edge inserts a step; null where it cannot (jumps, end). */
export function insertPointOf(edge: GraphEdge, byId: ReadonlyMap<string, GraphNode>): InsertPoint | null {
  if (edge.role === 'jump') return null
  const src = byId.get(edge.source)
  if (!src) return null
  if (src.data.kind === 'trigger') return { kind: 'start', flow: 'main' }
  if (src.data.kind === 'error-trigger') return { kind: 'start', flow: 'catch' }
  if (src.data.kind === 'step') return { kind: 'after', step: src.data.stepId, handle: edge.sourceHandle }
  return null
}

/** The insert point of a free output handle ("+" stub). */
export function insertPointOfHandle(node: GraphNode, handle: string): InsertPoint | null {
  if (node.data.kind === 'step') return { kind: 'after', step: node.data.stepId, handle }
  if (node.data.kind === 'trigger') return { kind: 'start', flow: 'main' }
  if (node.data.kind === 'error-trigger') return { kind: 'start', flow: 'catch' }
  return null
}

// ---------------------------------------------------------------------------------------------
// the mapping

export interface FlowInput {
  graph: Graph
  problems: readonly Problem[]
  runs: ReadonlyMap<string, RunInfo>
  pins: Readonly<Record<string, JsonValue>>
  stickies: readonly Sticky[]
  providers: readonly RegistryProvider[]
  readOnly: boolean
}

export interface Flow {
  nodes: FlowNode[]
  edges: FlowEdge[]
}

function stepNodeData(n: GraphNode & { data: { kind: 'step' } }, edgesFrom: ReadonlyMap<string, Set<string>>, issues: Issues | undefined, run: RunInfo | undefined, input: FlowInput): StepNodeData {
  const d = n.data
  const handles = edgesFrom.get(n.id) ?? new Set<string>()
  const free = d.outputs.map((o) => o.id).filter((h) => !handles.has(h))
  return {
    kind: 'step',
    stepId: d.stepId,
    name: d.step.name ?? d.stepId,
    subtitle: d.subtitle,
    typeTitle: STEP_TYPES[d.step.type].title,
    glyph: stepGlyph(d.step, input.providers),
    outputs: d.outputs,
    free,
    hasUndo: handles.has('undo'),
    issues: issues ?? null,
    run: run?.run ?? null,
    items: run?.items ?? null,
    pinned: input.pins[d.stepId] !== undefined,
    readOnly: input.readOnly,
    container: STEP_TYPES[d.step.type].isContainer || d.step.type === 'switch',
  }
}

/** xyflow nodes and edges for a graph and its overlays. */
export function toFlow(input: FlowInput): Flow {
  const { graph } = input
  const issues = issuesByNode(graph, input.problems)
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const edgesFrom = new Map<string, Set<string>>()
  for (const e of graph.edges) {
    const set = edgesFrom.get(e.source) ?? new Set<string>()
    set.add(e.sourceHandle)
    edgesFrom.set(e.source, set)
  }
  const runOfNode = new Map<string, RunInfo>()
  const nodes: FlowNode[] = []
  for (const n of graph.nodes) {
    const position = { x: n.position.x, y: n.position.y }
    switch (n.data.kind) {
      case 'step': {
        const run = input.runs.get(n.data.stepId)
        if (run) runOfNode.set(n.id, run)
        nodes.push({ id: n.id, type: 'step', position, data: stepNodeData(n as GraphNode & { data: { kind: 'step' } }, edgesFrom, issues.get(n.id), run, input), draggable: !input.readOnly, connectable: !input.readOnly })
        break
      }
      case 'trigger': {
        const d = n.data
        nodes.push({
          id: n.id,
          type: 'trigger',
          position,
          data: { kind: 'trigger', triggerType: d.triggerType, index: d.index, label: d.label, subtitle: d.subtitle, glyph: triggerGlyph(d.triggerType), free: !edgesFrom.has(n.id), issues: issues.get(n.id) ?? null, run: null, readOnly: input.readOnly },
          draggable: !input.readOnly,
          connectable: !input.readOnly,
        })
        break
      }
      case 'error-trigger':
        nodes.push({
          id: n.id,
          type: 'trigger',
          position,
          data: { kind: 'trigger', triggerType: 'error', index: null, label: 'Error trigger', subtitle: 'when a step fails', glyph: triggerGlyph('error'), free: !edgesFrom.has(n.id), issues: null, run: null, readOnly: input.readOnly },
          draggable: !input.readOnly,
          connectable: !input.readOnly,
        })
        break
      case 'end':
        nodes.push({ id: n.id, type: 'end', position: { x: position.x + NODE_SIZE / 2 - 20, y: position.y + NODE_SIZE / 2 - 20 }, data: { kind: 'end' }, draggable: false, connectable: !input.readOnly, selectable: false })
        break
      case 'undo':
        nodes.push({ id: n.id, type: 'undo', position, data: { kind: 'undo', stepId: n.data.stepId, names: n.data.names, readOnly: input.readOnly }, draggable: !input.readOnly, connectable: false })
        break
    }
  }
  // the triggers light up when something they started ran
  const firstRan = graph.edges.some((e) => e.role === 'trigger' && e.source !== ERROR_TRIGGER_ID && runOfNode.has(e.target))
  const catchRan = graph.edges.some((e) => e.role === 'trigger' && e.source === ERROR_TRIGGER_ID && runOfNode.has(e.target))
  for (const node of nodes) {
    if (node.type !== 'trigger') continue
    const ran = node.data.triggerType === 'error' ? catchRan : node.id === API_TRIGGER_ID && firstRan
    if (ran) node.data = { ...node.data, run: 'success' }
  }
  const edges: FlowEdge[] = graph.edges.map((e) => {
    const src = runOfNode.get(e.source)
    const tgt = runOfNode.get(e.target)
    const fromTrigger = e.role === 'trigger'
    let state: EdgeState | null = null
    if ((src?.run === 'success' || (fromTrigger && (e.source === API_TRIGGER_ID || e.source === ERROR_TRIGGER_ID))) && tgt) state = tgt.run === 'running' ? 'running' : tgt.run === 'failure' ? 'success' : tgt.run === 'skipped' ? null : 'success'
    if (fromTrigger && e.source !== API_TRIGGER_ID && e.source !== ERROR_TRIGGER_ID) state = null
    return {
      id: e.id,
      type: 'flow',
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle,
      targetHandle: e.targetHandle,
      selectable: false,
      data: {
        role: e.role,
        state,
        items: state ? (fromTrigger ? 1 : (src?.items ?? null)) : null,
        insert: input.readOnly ? null : insertPointOf(e, byId),
        readOnly: input.readOnly,
        highlighted: false,
        placeholder: false,
      },
    }
  })
  // "Add first step"
  const hasSteps = graph.nodes.some((n) => isStepNode(n) && !n.data.inCatch)
  if (!hasSteps) {
    const api = byId.get(API_TRIGGER_ID)
    if (api) {
      nodes.push({ id: PLACEHOLDER_ID, type: 'placeholder', position: { x: api.position.x + COLUMN, y: api.position.y }, data: { kind: 'placeholder', readOnly: input.readOnly }, draggable: false, selectable: false, connectable: false })
      for (const t of graph.nodes) {
        if (t.data.kind !== 'trigger') continue
        edges.push({
          id: `${t.id}:main->${PLACEHOLDER_ID}`,
          type: 'flow',
          source: t.id,
          target: PLACEHOLDER_ID,
          sourceHandle: 'main',
          targetHandle: 'in',
          selectable: false,
          data: { role: 'trigger', state: null, items: null, insert: null, readOnly: true, highlighted: false, placeholder: true },
        })
      }
    }
  }
  for (const s of input.stickies) {
    nodes.push({
      id: stickyNodeId(s.id),
      type: 'sticky',
      position: { x: s.x, y: s.y },
      width: s.width,
      height: s.height,
      zIndex: -1,
      data: { kind: 'sticky', sticky: s, readOnly: input.readOnly },
      draggable: !input.readOnly,
      connectable: false,
      dragHandle: '.iaf-sticky-drag',
    })
  }
  return { nodes, edges }
}

// ---------------------------------------------------------------------------------------------
// keeping xyflow's node objects stable

function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true
  return JSON.stringify(a) === JSON.stringify(b)
}

/**
 * The next node list for xyflow: selection, measurements and in-flight drags come from the
 * previous list, and a node whose data and position did not change keeps its object, so
 * xyflow does not re-render it.
 */
export function mergeNodes(prev: readonly FlowNode[], next: readonly FlowNode[]): FlowNode[] {
  const before = new Map(prev.map((n) => [n.id, n]))
  return next.map((n) => {
    const p = before.get(n.id)
    if (!p || p.type !== n.type) return n
    const data = sameJson(p.data, n.data) ? p.data : n.data
    const position = p.dragging ? p.position : n.position
    const merged = { ...n, data, position, selected: p.selected, measured: p.measured, dragging: p.dragging } as FlowNode
    if (
      p.data === data &&
      p.position.x === position.x &&
      p.position.y === position.y &&
      p.width === n.width &&
      p.height === n.height &&
      p.draggable === n.draggable &&
      p.connectable === n.connectable
    )
      return p
    return merged
  })
}
