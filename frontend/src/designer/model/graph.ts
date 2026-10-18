/**
 * The canvas model: a `WorkflowDocument` (the YAML as JSON) becomes nodes and edges the way n8n
 * draws a flow, and the edges walk back into a document.
 *
 * Mapping (documentToGraph):
 * - the API trigger and every entry of `triggers` are trigger nodes feeding the first step;
 * - a plain step is one node with the input handle `in` and the output handle `main`;
 * - `switch` has one output per case (`case:<i>`, labelled with the `when` text) plus `default`;
 *   `then: <id>` is a `jump` edge to that node, `then: end` a `jump` edge to a small End node,
 *   a nested `do` a `branch` edge to its first step whose last step `join`s the step after the
 *   switch; a switch without a default case shows a muted `fallthrough` edge to that step;
 * - `fork` has one output per branch (`branch:<i>`), branches join on the next step;
 * - `for` has `done` (the next step) and `loop` (the body, whose last step `loop`s back);
 * - `try` has `try` and `catch`; both branches rejoin the next step;
 * - a `compensate` list is a dashed "undo" sub-node hanging off the step's `undo` handle;
 * - the workflow-level `catch` hangs off an "Error trigger" node.
 *
 * Every node keeps the step's raw body, so keys the canvas does not understand survive edits.
 * `join` and `fallthrough` edges carry `exits`: the container node ids whose branch ends on that
 * edge (innermost first), which is what lets graphToDocument know where a nested branch stops.
 */
import { parseDocument, parseSteps, WorkflowParseError, type JsonObject, type JsonValue, type ParsedStep, type ParsedSwitchCase, type Problem, type StepBody, type StepDocument, type WorkflowDocument } from '@iaf/workflow-checks'

export const NODE_SIZE = 100
export const H_GAP = 150
/** horizontal pitch between two nodes of a chain */
export const COLUMN = NODE_SIZE + H_GAP
/** vertical pitch between two stacked branches */
export const ROW = 170

export type TriggerType = 'api' | 'alert' | 'event' | 'other'

export interface OutputHandle {
  id: string
  label: string
}

export interface Position {
  x: number
  y: number
}

export interface TriggerNodeData {
  kind: 'trigger'
  triggerType: TriggerType
  /** the entry of `triggers` this node shows; null for the API trigger */
  index: number | null
  label: string
  subtitle: string
  spec: JsonValue
}

export interface StepNodeData {
  kind: 'step'
  stepId: string
  step: ParsedStep
  /** the raw step body as written, with every key */
  body: StepBody
  outputs: OutputHandle[]
  /** the action line under the name, e.g. `vsphere · vm.get` */
  subtitle: string
  /** document order, for stable tie breaks */
  seq: number
  /** inside the workflow-level catch */
  inCatch: boolean
}

export interface EndNodeData {
  kind: 'end'
}

export interface UndoNodeData {
  kind: 'undo'
  stepId: string
  steps: StepDocument[]
  names: string[]
}

export interface ErrorTriggerNodeData {
  kind: 'error-trigger'
}

export type GraphNodeData = TriggerNodeData | StepNodeData | EndNodeData | UndoNodeData | ErrorTriggerNodeData

export interface GraphNode {
  id: string
  data: GraphNodeData
  position: Position
  /** the position came from the document (or the user); false means "lay me out" */
  positioned: boolean
}

export type EdgeRole = 'trigger' | 'main' | 'branch' | 'join' | 'jump' | 'fallthrough' | 'loop' | 'undo'

export interface GraphEdge {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
  role: EdgeRole
  label?: string
  /** join/fallthrough: the container node ids whose branch ends here, innermost first */
  exits?: string[]
}

export interface Graph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  problems: Problem[]
}

export const API_TRIGGER_ID = 'trigger:api'
export const ERROR_TRIGGER_ID = 'error-trigger'
export const IN_HANDLE = 'in'
export const MAIN_HANDLE = 'main'
export const UNDO_HANDLE = 'undo'

export function triggerNodeId(index: number): string {
  return `trigger:${index}`
}

export function stepNodeId(stepId: string): string {
  return `step:${stepId}`
}

export function undoNodeId(stepId: string): string {
  return `undo:${stepId}`
}

export function isStepNode(n: GraphNode | undefined): n is GraphNode & { data: StepNodeData } {
  return n !== undefined && n.data.kind === 'step'
}

export function isTriggerNode(n: GraphNode | undefined): n is GraphNode & { data: TriggerNodeData } {
  return n !== undefined && n.data.kind === 'trigger'
}

/** The step ids of a graph, in document order. */
export function stepIds(graph: Graph): string[] {
  return graph.nodes
    .filter(isStepNode)
    .sort((a, b) => a.data.seq - b.data.seq)
    .map((n) => n.data.stepId)
}

export function findStepNode(graph: Graph, stepId: string): (GraphNode & { data: StepNodeData }) | undefined {
  return graph.nodes.filter(isStepNode).find((n) => n.data.stepId === stepId)
}

// ---------------------------------------------------------------------------------------------
// document → graph

const VERB_METHOD: Record<string, string> = { create: 'POST', read: 'GET', get: 'GET', list: 'GET', update: 'PUT', delete: 'DELETE' }

/** `POST /api/v1/vms/{vmId}:restart`, inferred from noun and verb when the api block says nothing. */
export function apiTriggerLabel(doc: WorkflowDocument): { method: string; path: string } {
  const verb = doc.metadata?.verb ?? ''
  const noun = doc.metadata?.noun ?? ''
  const method = doc.api?.method ?? VERB_METHOD[verb] ?? 'POST'
  const path = doc.api?.path ?? (noun ? `/api/v1/${noun}s${verb && !VERB_METHOD[verb] ? `:${verb}` : ''}` : '/api/v1/…')
  return { method: method.toUpperCase(), path }
}

export function truncate(s: string, max = 28): string {
  const t = s.replace(/\s+/g, ' ').trim()
  return t.length > max ? `${t.slice(0, max - 1)}…` : t
}

/** The label of a switch case output: the `when` text without the braces, truncated. */
export function caseLabel(c: ParsedSwitchCase, index: number): string {
  if (c.isDefault) return 'default'
  const when = (c.when ?? '').replace(/^\s*\{\{\s*/, '').replace(/\s*\}\}\s*$/, '')
  return when ? truncate(when, 24) : `case ${index + 1}`
}

export function outputHandlesOf(step: ParsedStep): OutputHandle[] {
  switch (step.type) {
    case 'switch': {
      const out: OutputHandle[] = []
      let hasDefault = false
      ;(step.cases ?? []).forEach((c, i) => {
        if (c.isDefault) hasDefault = true
        else out.push({ id: `case:${i}`, label: caseLabel(c, i) })
      })
      out.push({ id: 'default', label: hasDefault ? 'default' : 'default' })
      return out
    }
    case 'fork':
      return (step.branches ?? []).map((b, i) => ({ id: `branch:${i}`, label: b[0]?.name ?? b[0]?.id ?? `Branch ${i + 1}` }))
    case 'for':
      return [
        { id: 'done', label: 'done' },
        { id: 'loop', label: 'loop' },
      ]
    case 'try':
      return [
        { id: 'try', label: 'try' },
        { id: 'catch', label: 'catch' },
      ]
    default:
      return [{ id: MAIN_HANDLE, label: '' }]
  }
}

function text(v: JsonValue | undefined): string | null {
  return typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'boolean' ? String(v) : null
}

/** `vsphere · vm.get`, `POST https://…`, `PT5M`: the line under the node name. */
export function stepSubtitle(step: ParsedStep): string {
  const w = (step.with !== null && typeof step.with === 'object' && !Array.isArray(step.with) ? step.with : {}) as JsonObject
  const t = (k: string) => text(w[k])
  switch (step.type) {
    case 'provider':
      return [t('provider'), t('action')].filter(Boolean).join(' · ') || 'provider'
    case 'http':
      return [t('method') ?? 'GET', t('url')].filter(Boolean).join(' ')
    case 'ansible': {
      const pb = w.playbook
      if (typeof pb === 'string') return pb
      if (pb && typeof pb === 'object' && !Array.isArray(pb)) {
        const git = pb.git
        if (git && typeof git === 'object' && !Array.isArray(git)) return text(git.path) ?? 'git playbook'
        if (pb.upload) return 'uploaded playbook'
        if (typeof pb.library === 'string') return pb.library
      }
      return 'playbook'
    }
    case 'terraform': {
      const m = w.module
      const src = m && typeof m === 'object' && !Array.isArray(m) ? text(m.source) : null
      return [t('action') ?? 'plan', src ? src.split('//').pop() : null].filter(Boolean).join(' · ')
    }
    case 'idp':
      return t('tile') ?? 'tile'
    case 'activity':
      return [t('taskQueue'), t('activity')].filter(Boolean).join(' · ') || 'activity'
    case 'run':
      return [t('workflow'), t('version')].filter(Boolean).join(' ') || 'workflow'
    case 'approval':
      return t('group') ?? 'approval'
    case 'switch':
      return `${step.cases?.length ?? 0} cases`
    case 'fork':
      return `${step.branches?.length ?? 0} branches`
    case 'for':
      return t('each') ? `each ${t('each')}` : 'loop'
    case 'try':
      return step.catchBlock ? `catch as ${step.catchBlock.as ?? 'error'}` : 'no catch'
    case 'wait':
      return t('duration') ?? (t('until') ? 'until …' : 'wait')
    case 'set':
      return Object.keys(w).join(', ') || 'variables'
    case 'raise':
      return t('error') ?? 'error'
    case 'use':
      return t('type') ?? 'resource'
    case 'register':
      return [t('type'), t('state')].filter(Boolean).join(' · ') || 'resource'
    case 'notify':
      return t('channel') ?? 'channel'
    case 'script':
      return t('language') ?? 'javascript'
    default:
      return ''
  }
}

interface Exit {
  target: string
  role: 'main' | 'join' | 'loop'
  exits: string[]
}

interface Incoming {
  source: string
  handle: string
  role: EdgeRole
}

class Emitter {
  readonly nodes: GraphNode[] = []
  readonly edges: GraphEdge[] = []
  private readonly usedIds = new Set<string>()
  private readonly nodeIdByStep = new Map<ParsedStep, string>()
  private readonly nodeIdByStepId = new Map<string, string>()
  private seq = 0

  constructor(private readonly doc: WorkflowDocument) {}

  /** First pass: a node id for every step at every depth, so jump targets resolve. */
  assignIds(steps: ParsedStep[]): void {
    for (const s of steps) {
      let id = stepNodeId(s.id)
      let n = 2
      while (this.usedIds.has(id)) id = `${stepNodeId(s.id)}#${n++}`
      this.usedIds.add(id)
      this.nodeIdByStep.set(s, id)
      if (!this.nodeIdByStepId.has(s.id)) this.nodeIdByStepId.set(s.id, id)
      s.branches?.forEach((b) => this.assignIds(b))
      s.cases?.forEach((c) => c.steps && this.assignIds(c.steps))
      if (s.children) this.assignIds(s.children)
      if (s.catchBlock) this.assignIds(s.catchBlock.steps)
    }
  }

  edge(source: string, handle: string, target: string, role: EdgeRole, extra: { label?: string; exits?: string[] } = {}): void {
    const id = `${source}:${handle}->${target}`
    if (this.edges.some((e) => e.id === id)) return
    this.edges.push({ id, source, sourceHandle: handle, target, targetHandle: IN_HANDLE, role, ...extra })
  }

  /** Emits a `do` list; returns the first node id (null when empty). */
  list(raw: StepDocument[] | undefined, parsed: ParsedStep[], incoming: Incoming | null, exit: Exit | null, inCatch: boolean): string | null {
    const rawList = Array.isArray(raw) ? raw : []
    if (parsed.length === 0) {
      if (incoming && exit && exit.role !== 'loop') this.edge(incoming.source, incoming.handle, exit.target, 'join', { exits: exit.exits })
      return null
    }
    const ids = parsed.map((s) => this.nodeIdByStep.get(s) as string)
    parsed.forEach((step, i) => {
      const body = (rawList[i]?.[step.id] ?? {}) as StepBody
      const nodeId = ids[i]
      const pos = step.position
      this.nodes.push({
        id: nodeId,
        data: { kind: 'step', stepId: step.id, step, body, outputs: outputHandlesOf(step), subtitle: stepSubtitle(step), seq: this.seq++, inCatch },
        position: pos ? { x: pos.x, y: pos.y } : { x: 0, y: 0 },
        positioned: pos !== null,
      })
      if (i === 0 && incoming) this.edge(incoming.source, incoming.handle, nodeId, incoming.role)
      const next = i + 1 < parsed.length ? ids[i + 1] : null
      const myExit: Exit | null = next ? { target: next, role: 'main', exits: [] } : exit
      this.outputs(nodeId, step, body, myExit, inCatch)
    })
    return ids[0]
  }

  private outputs(nodeId: string, step: ParsedStep, body: StepBody, exit: Exit | null, inCatch: boolean): void {
    const nested = (): Exit | null => (exit ? { target: exit.target, role: exit.role === 'loop' ? 'loop' : 'join', exits: exit.role === 'loop' ? [] : [nodeId, ...exit.exits] } : null)
    switch (step.type) {
      case 'switch': {
        const rawCases = Array.isArray(body.switch) ? body.switch : []
        let hasDefault = false
        ;(step.cases ?? []).forEach((c, i) => {
          const handle = c.isDefault ? 'default' : `case:${i}`
          if (c.isDefault) hasDefault = true
          const rc = rawCases[i]
          const rawDo = c.isDefault ? (rc && typeof rc.default === 'object' && rc.default !== null ? rc.default.do : undefined) : rc?.case?.do
          if (c.steps !== null) {
            this.list(rawDo, c.steps, { source: nodeId, handle, role: 'branch' }, nested(), inCatch)
          } else if (c.then === 'end') {
            const endId = `end:${nodeId}:${i}`
            this.nodes.push({ id: endId, data: { kind: 'end' }, position: { x: 0, y: 0 }, positioned: false })
            this.edge(nodeId, handle, endId, 'jump')
          } else if (c.then !== null) {
            const target = this.nodeIdByStepId.get(c.then)
            if (target) this.edge(nodeId, handle, target, 'jump')
          }
        })
        if (!hasDefault && exit && exit.role !== 'loop') this.edge(nodeId, 'default', exit.target, 'fallthrough', { exits: exit.exits })
        else if (!hasDefault && exit && exit.role === 'loop') this.edge(nodeId, 'default', exit.target, 'loop')
        break
      }
      case 'fork': {
        const rawBranches = body.fork && Array.isArray(body.fork.branches) ? body.fork.branches : []
        ;(step.branches ?? []).forEach((b, i) => {
          this.list(rawBranches[i], b, { source: nodeId, handle: `branch:${i}`, role: 'branch' }, nested(), inCatch)
        })
        break
      }
      case 'for': {
        this.list(body.for?.do, step.children ?? [], { source: nodeId, handle: 'loop', role: 'branch' }, { target: nodeId, role: 'loop', exits: [] }, inCatch)
        if (exit) this.exitEdge(nodeId, 'done', exit)
        break
      }
      case 'try': {
        this.list(body.try?.do, step.children ?? [], { source: nodeId, handle: 'try', role: 'branch' }, nested(), inCatch)
        if (step.catchBlock) this.list(body.try?.catch?.do, step.catchBlock.steps, { source: nodeId, handle: 'catch', role: 'branch' }, nested(), true)
        break
      }
      default:
        if (exit) this.exitEdge(nodeId, MAIN_HANDLE, exit)
    }
    if (step.compensate !== null && step.compensate.length > 0) {
      const undoId = undoNodeId(step.id)
      const steps = Array.isArray(body.compensate) ? body.compensate : []
      this.nodes.push({
        id: undoId,
        data: { kind: 'undo', stepId: step.id, steps, names: step.compensate.map((s) => s.name ?? s.id) },
        position: { x: 0, y: 0 },
        positioned: false,
      })
      this.edge(nodeId, UNDO_HANDLE, undoId, 'undo')
    }
  }

  private exitEdge(nodeId: string, handle: string, exit: Exit): void {
    if (exit.role === 'loop') this.edge(nodeId, handle, exit.target, 'loop')
    else this.edge(nodeId, handle, exit.target, exit.role, exit.role === 'join' ? { exits: exit.exits } : {})
  }

  triggers(): string[] {
    const ids: string[] = []
    const { method, path } = apiTriggerLabel(this.doc)
    this.nodes.push({
      id: API_TRIGGER_ID,
      data: { kind: 'trigger', triggerType: 'api', index: null, label: 'API trigger', subtitle: `${method} ${path}`, spec: (this.doc.api as JsonValue) ?? null },
      position: { x: 0, y: 0 },
      positioned: false,
    })
    ids.push(API_TRIGGER_ID)
    const triggers = Array.isArray(this.doc.triggers) ? this.doc.triggers : []
    triggers.forEach((t, i) => {
      if (!t || typeof t !== 'object' || Array.isArray(t)) return
      const keys = Object.keys(t).filter((k) => t[k] !== undefined)
      const type = keys.length === 1 ? keys[0] : typeof t.type === 'string' ? t.type : 'other'
      const spec = (keys.length === 1 ? t[keys[0]] : t) as JsonValue
      const specObj = spec && typeof spec === 'object' && !Array.isArray(spec) ? spec : {}
      let subtitle = type
      let triggerType: TriggerType = 'other'
      if (type === 'alert') {
        triggerType = 'alert'
        const match = specObj.match
        const src = match && typeof match === 'object' && !Array.isArray(match) ? text(match.source) : null
        subtitle = src ? `alert · ${src}` : 'alert'
      } else if (type === 'event') {
        triggerType = 'event'
        subtitle = text(specObj.topic) ? `event · ${text(specObj.topic)}` : 'event'
      }
      this.nodes.push({
        id: triggerNodeId(i),
        data: { kind: 'trigger', triggerType, index: i, label: triggerType === 'alert' ? 'Alert trigger' : triggerType === 'event' ? 'Event trigger' : `${type} trigger`, subtitle, spec },
        position: { x: 0, y: 0 },
        positioned: false,
      })
      ids.push(triggerNodeId(i))
    })
    return ids
  }
}

/** The nodes and edges of a document. A document that does not parse yields the trigger alone and one problem. */
export function documentToGraph(doc: WorkflowDocument): Graph {
  const emitter = new Emitter(doc)
  const triggerIds = emitter.triggers()
  let parsed
  try {
    parsed = parseDocument(doc)
  } catch (e) {
    if (e instanceof WorkflowParseError) {
      return { nodes: emitter.nodes, edges: [], problems: [{ level: 'error', code: 'yaml.invalid', message: e.message }] }
    }
    throw e
  }
  const flow = parsed.workflow
  emitter.assignIds(flow.steps)
  if (flow.catchFlow) emitter.assignIds(flow.catchFlow.steps)
  const first = emitter.list(doc.workflow?.do, flow.steps, null, null, false)
  if (first) for (const t of triggerIds) emitter.edge(t, MAIN_HANDLE, first, 'trigger')
  if (flow.catchFlow) {
    emitter.nodes.push({ id: ERROR_TRIGGER_ID, data: { kind: 'error-trigger' }, position: { x: 0, y: 0 }, positioned: false })
    const firstCatch = emitter.list(doc.workflow?.catch?.do, flow.catchFlow.steps, null, null, true)
    if (firstCatch) emitter.edge(ERROR_TRIGGER_ID, MAIN_HANDLE, firstCatch, 'trigger')
  }
  return { nodes: emitter.nodes, edges: emitter.edges, problems: [] }
}

// ---------------------------------------------------------------------------------------------
// graph → document

function clone<T>(v: T): T {
  return v === undefined ? v : (JSON.parse(JSON.stringify(v)) as T)
}

function remainderAfter(edge: GraphEdge, containerId: string): string[] {
  const exits = edge.exits ?? []
  const k = exits.indexOf(containerId)
  return k >= 0 ? exits.slice(k + 1) : exits
}

interface Rebuilt {
  body: StepBody
  next: string | null
  exitEdge: GraphEdge | null
}

class Walker {
  readonly byId = new Map<string, GraphNode>()
  readonly outs = new Map<string, Map<string, GraphEdge[]>>()
  readonly visited = new Set<string>()

  constructor(readonly graph: Graph) {
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

  out(nodeId: string, handle: string): GraphEdge | undefined {
    return this.outs.get(nodeId)?.get(handle)?.[0]
  }

  stepIdOf(nodeId: string): string | null {
    const n = this.byId.get(nodeId)
    return isStepNode(n) ? n.data.stepId : null
  }

  chain(start: string | null | undefined): { items: StepDocument[]; exitEdge: GraphEdge | null } {
    const items: StepDocument[] = []
    let exitEdge: GraphEdge | null = null
    let cur = start ?? null
    while (cur) {
      const node = this.byId.get(cur)
      if (!isStepNode(node) || this.visited.has(cur)) break
      this.visited.add(cur)
      const r = this.rebuild(node)
      items.push({ [node.data.stepId]: r.body })
      if (r.exitEdge) {
        exitEdge = r.exitEdge
        break
      }
      cur = r.next
    }
    return { items, exitEdge }
  }

  /** Follows a branch handle: the nested steps and the edge the branch leaves on. */
  private branch(nodeId: string, handle: string): { items: StepDocument[] | null; joinEdge: GraphEdge | null; edge: GraphEdge | undefined } {
    const e = this.out(nodeId, handle)
    if (!e) return { items: null, joinEdge: null, edge: undefined }
    if (e.role === 'branch') {
      const w = this.chain(e.target)
      return { items: w.items, joinEdge: w.exitEdge, edge: e }
    }
    if (e.role === 'join' || e.role === 'fallthrough') return { items: [], joinEdge: e, edge: e }
    return { items: null, joinEdge: null, edge: e }
  }

  private continueAfter(nodeId: string, joinEdge: GraphEdge | null): { next: string | null; exitEdge: GraphEdge | null } {
    if (!joinEdge) return { next: null, exitEdge: null }
    if (joinEdge.role === 'loop') return { next: null, exitEdge: null }
    return remainderAfter(joinEdge, nodeId).length > 0 ? { next: null, exitEdge: joinEdge } : { next: joinEdge.target, exitEdge: null }
  }

  private rebuild(node: GraphNode & { data: StepNodeData }): Rebuilt {
    const body = clone(node.data.body)
    const step = node.data.step
    const id = node.id
    // positions and compensate
    if (node.positioned) body.position = { x: Math.round(node.position.x), y: Math.round(node.position.y) }
    const undo = this.out(id, UNDO_HANDLE)
    if (undo) {
      const u = this.byId.get(undo.target)
      if (u && u.data.kind === 'undo') body.compensate = clone(u.data.steps)
    } else if (step.compensate !== null && step.compensate.length > 0) {
      delete body.compensate
    }
    let next: string | null = null
    let exitEdge: GraphEdge | null = null
    switch (step.type) {
      case 'switch': {
        const cases = Array.isArray(body.switch) ? body.switch : []
        let joinEdge: GraphEdge | null = null
        const jumpTargets: string[] = []
        let defaultIndex = -1
        ;(step.cases ?? []).forEach((c, i) => {
          if (c.isDefault) defaultIndex = i
          const handle = c.isDefault ? 'default' : `case:${i}`
          const entry = cases[i] ?? (c.isDefault ? { default: c.then ?? 'end' } : { case: {} })
          cases[i] = entry
          const b = this.branch(id, handle)
          if (b.edge && b.edge.role === 'jump') {
            const target = this.byId.get(b.edge.target)
            const then = target?.data.kind === 'end' ? 'end' : (this.stepIdOf(b.edge.target) ?? c.then ?? 'end')
            if (target?.data.kind === 'step') jumpTargets.push(b.edge.target)
            this.setCase(entry, c.isDefault, { then })
          } else if (b.items !== null) {
            this.setCase(entry, c.isDefault, { do: b.items })
            if (b.joinEdge && !joinEdge) joinEdge = b.joinEdge
          } else if (c.steps !== null) {
            this.setCase(entry, c.isDefault, { do: [] })
          }
        })
        if (defaultIndex < 0) {
          const b = this.branch(id, 'default')
          if (b.edge && b.edge.role === 'jump') {
            const target = this.byId.get(b.edge.target)
            cases.push({ default: target?.data.kind === 'end' ? 'end' : (this.stepIdOf(b.edge.target) ?? 'end') })
            if (target?.data.kind === 'step') jumpTargets.push(b.edge.target)
          } else if (b.edge && b.edge.role === 'branch') {
            cases.push({ default: { do: b.items ?? [] } })
            if (b.joinEdge && !joinEdge) joinEdge = b.joinEdge
          } else if (b.edge && (b.edge.role === 'fallthrough' || b.edge.role === 'join')) {
            joinEdge = joinEdge ?? b.edge
          } else if (b.edge && b.edge.role === 'loop') {
            joinEdge = joinEdge ?? b.edge
          }
        }
        body.switch = cases
        if (joinEdge) {
          const c = this.continueAfter(id, joinEdge)
          next = c.next
          exitEdge = c.exitEdge
        } else {
          const candidates = jumpTargets
            .filter((t) => !this.visited.has(t))
            .map((t) => this.byId.get(t))
            .filter(isStepNode)
            .sort((a, b) => a.data.seq - b.data.seq)
          next = candidates[0]?.id ?? null
        }
        break
      }
      case 'fork': {
        const fork = body.fork && typeof body.fork === 'object' ? body.fork : {}
        const count = Math.max(step.branches?.length ?? 0, this.handleCount(id, 'branch:'))
        const branches: StepDocument[][] = []
        let joinEdge: GraphEdge | null = null
        for (let i = 0; i < count; i++) {
          const b = this.branch(id, `branch:${i}`)
          branches.push(b.items ?? [])
          if (b.joinEdge && !joinEdge) joinEdge = b.joinEdge
        }
        body.fork = { ...fork, branches }
        const c = this.continueAfter(id, joinEdge)
        next = c.next
        exitEdge = c.exitEdge
        break
      }
      case 'for': {
        const f = body.for && typeof body.for === 'object' ? body.for : {}
        const loop = this.out(id, 'loop')
        const items = loop && loop.role === 'branch' ? this.chain(loop.target).items : []
        body.for = { ...f, do: items }
        const done = this.out(id, 'done')
        if (done?.role === 'main') next = done.target
        else if (done?.role === 'join') exitEdge = done
        break
      }
      case 'try': {
        const t = body.try && typeof body.try === 'object' ? body.try : {}
        const tryBranch = this.branch(id, 'try')
        const catchBranch = this.branch(id, 'catch')
        const out: NonNullable<StepBody['try']> = { ...t, do: tryBranch.items ?? [] }
        if (catchBranch.items !== null) out.catch = { ...(t.catch ?? {}), do: catchBranch.items }
        else if (step.catchBlock !== null) out.catch = { ...(t.catch ?? {}), do: [] }
        body.try = out
        const c = this.continueAfter(id, tryBranch.joinEdge ?? catchBranch.joinEdge)
        next = c.next
        exitEdge = c.exitEdge
        break
      }
      default: {
        const e = this.out(id, MAIN_HANDLE)
        if (e?.role === 'main') next = e.target
        else if (e?.role === 'join') exitEdge = e
      }
    }
    return { body, next, exitEdge }
  }

  private handleCount(nodeId: string, prefix: string): number {
    const m = this.outs.get(nodeId)
    if (!m) return 0
    let max = 0
    for (const h of m.keys()) if (h.startsWith(prefix)) max = Math.max(max, Number(h.slice(prefix.length)) + 1)
    return max
  }

  private setCase(entry: { case?: { when?: string; then?: string; do?: StepDocument[] }; default?: string | { then?: string; do?: StepDocument[] } }, isDefault: boolean, v: { then?: string; do?: StepDocument[] }): void {
    if (isDefault) {
      if (v.then !== undefined) {
        if (typeof entry.default === 'object' && entry.default !== null) {
          entry.default = { ...entry.default, then: v.then }
          delete entry.default.do
        } else entry.default = v.then
      } else if (v.do !== undefined) {
        const prev = typeof entry.default === 'object' && entry.default !== null ? entry.default : {}
        entry.default = { ...prev, do: v.do }
        delete entry.default.then
      }
      return
    }
    const c = entry.case ?? {}
    if (v.then !== undefined) {
      c.then = v.then
      delete c.do
    } else if (v.do !== undefined) {
      c.do = v.do
      delete c.then
    }
    entry.case = c
  }

  /** Step nodes the walks never reached, as chains, in document order. */
  orphans(): StepDocument[] {
    const unvisited = this.graph.nodes.filter(isStepNode).filter((n) => !this.visited.has(n.id))
    const incomingMain = new Set(this.graph.edges.filter((e) => e.role === 'main' && unvisited.some((n) => n.id === e.source)).map((e) => e.target))
    const heads = unvisited.filter((n) => !incomingMain.has(n.id)).sort((a, b) => a.data.seq - b.data.seq)
    const out: StepDocument[] = []
    for (const h of heads) out.push(...this.chain(h.id).items)
    for (const n of unvisited) if (!this.visited.has(n.id)) out.push(...this.chain(n.id).items)
    return out
  }
}

/**
 * The document behind a graph: `workflow.do` and `workflow.catch` walked from the trigger nodes,
 * everything else from `base`. Node positions are written only for nodes marked `positioned`.
 */
export function graphToDocument(graph: Graph, base: WorkflowDocument): WorkflowDocument {
  const w = new Walker(graph)
  const triggerEdge = graph.edges.find((e) => e.source === API_TRIGGER_ID && e.role === 'trigger') ?? graph.edges.find((e) => e.role === 'trigger' && e.source !== ERROR_TRIGGER_ID)
  const main = w.chain(triggerEdge?.target).items
  const errorTrigger = w.byId.get(ERROR_TRIGGER_ID)
  const catchEdge = graph.edges.find((e) => e.source === ERROR_TRIGGER_ID && e.role === 'trigger')
  const catchItems = errorTrigger ? w.chain(catchEdge?.target).items : null
  main.push(...w.orphans())
  const flow: NonNullable<WorkflowDocument['workflow']> = { ...(base.workflow ?? {}), do: main }
  if (catchItems !== null) flow.catch = { ...(base.workflow?.catch ?? {}), do: catchItems }
  else delete flow.catch
  return { ...base, workflow: flow }
}

// ---------------------------------------------------------------------------------------------
// small document helpers the store and the editors share

/** The raw body of a step anywhere in the document, with the list it sits in and its index. */
export interface StepLocation {
  list: StepDocument[]
  index: number
  id: string
  body: StepBody
  /** the step ids of the containers above, outermost first */
  parents: string[]
}

export function* walkDocumentSteps(list: StepDocument[] | undefined, parents: string[] = []): Generator<StepLocation> {
  if (!Array.isArray(list)) return
  for (let index = 0; index < list.length; index++) {
    const entry = list[index]
    if (!entry || typeof entry !== 'object') continue
    const id = Object.keys(entry)[0]
    if (id === undefined) continue
    const body = entry[id]
    if (!body || typeof body !== 'object') continue
    yield { list, index, id, body, parents }
    const nested = [...parents, id]
    if (body.fork?.branches) for (const b of body.fork.branches) yield* walkDocumentSteps(b, nested)
    if (Array.isArray(body.switch))
      for (const c of body.switch) {
        if (c.case?.do) yield* walkDocumentSteps(c.case.do, nested)
        if (typeof c.default === 'object' && c.default?.do) yield* walkDocumentSteps(c.default.do, nested)
      }
    if (body.for?.do) yield* walkDocumentSteps(body.for.do, nested)
    if (body.try?.do) yield* walkDocumentSteps(body.try.do, nested)
    if (body.try?.catch?.do) yield* walkDocumentSteps(body.try.catch.do, nested)
    if (Array.isArray(body.compensate)) yield* walkDocumentSteps(body.compensate, nested)
  }
}

/** Every step location of a document: the main flow first, then the catch flow. */
export function allStepLocations(doc: WorkflowDocument): StepLocation[] {
  return [...walkDocumentSteps(doc.workflow?.do), ...walkDocumentSteps(doc.workflow?.catch?.do)]
}

export function findStepLocation(doc: WorkflowDocument, stepId: string): StepLocation | undefined {
  for (const loc of allStepLocations(doc)) if (loc.id === stepId) return loc
  return undefined
}

/** Parses the steps of a list without throwing: the ids and bodies that look like steps. */
export function safeParseSteps(list: StepDocument[] | undefined): ParsedStep[] {
  try {
    return parseSteps(list, 'do')
  } catch {
    return []
  }
}
