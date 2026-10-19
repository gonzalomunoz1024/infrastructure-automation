/**
 * The designer's state: one document, its graph, the selection the panels care about (the open
 * node, the node creator), undo and redo, the dirty flag, the live problems, the registry, the
 * last test run and the job an execution view shows. A plain external store read through
 * useSyncExternalStore; every change replaces the state object, so React re-renders only what
 * reads it.
 */
import { createContext, useContext, useSyncExternalStore } from 'react'
import { check, type CheckContext, type JsonObject, type JsonValue, type Problem, type StepBody, type StepDocument, type StepType, type WorkflowDocument } from '@iaf/workflow-checks'
import { api, errorMessage, isApiError, jobEvents, type Subscription } from '../../lib/api'
import type { DraftSaved, DryRunResult, JobDetail, RegistryCredential, ValidationResult, WorkflowDetail } from '../../lib/types'
import {
  addForkBranch,
  addSwitchCase,
  addTrigger,
  bodyFor,
  connectSwitch,
  duplicateSteps,
  insertStep,
  newStepId,
  pasteSteps,
  removeCatch,
  removeForkBranch,
  removeSteps,
  removeSwitchCase,
  removeTrigger,
  renameReferences,
  renameStep,
  setCompensate,
  setStepPositions,
  setTrigger,
  updateDocument,
  updateStep,
  type InsertPoint,
  type NewStepParams,
  type TriggerKind,
} from './edits'
import { applyFix, clone, fixOf } from './fixes'
import { API_TRIGGER_ID, COLUMN, ERROR_TRIGGER_ID, ROW, documentToGraph, findStepNode, graphToDocument, isStepNode, stepIds, stepNodeId, type Graph, type GraphEdge, type GraphNode, type Position } from './graph'
import { autoLayout, positionAfter } from './layout'
import { contextOf, EMPTY_REGISTRY, loadRegistry, rememberCredential, withCredential, withUnpublished, type Registry } from './registry'
import { draftFile, draftPlaybooks, draftProvider, draftsUsedBy, findProviderDraft, loadProviderDrafts, loadProvidersInReview, saveProviderDrafts, saveProvidersInReview, withDefaultOwner, type ProviderDraft, type ProviderInReview } from './providerDrafts'
import { loadStickies, saveStickies, type Sticky } from './stickies'
import { withoutEmptyKeys } from './nulls'
import type { StepOutputs } from './expressions'

export type DesignerTab = 'editor' | 'executions' | 'settings'

export type RunStatus = 'running' | 'passed' | 'failed'

export interface TestRun {
  status: RunStatus
  input: JsonObject
  result: DryRunResult | null
  /** how many steps of `result.path` the animation has revealed */
  revealed: number
  error: string | null
  startedAt: number
  /** when the result came back */
  finishedAt: number | null
  /** the step a "Test step" asked for, null for the whole workflow */
  focus: string | null
}

export interface ExecutionView {
  jobId: string
  job: JobDetail | null
  loading: boolean
  error: string | null
}

export interface CreatorState {
  /** where the new step goes; null means after the selection, else at the end of the flow */
  point: InsertPoint | null
  query: string
}

/**
 * What the Provider sheet was opened from: a node creator entry (the six provider types and
 * "Describe it"), a step's parameters ("New provider…", "Edit provider", "Save as provider
 * action"), the node detail view, or the publish dialog.
 */
export type ProviderSheetEntry = 'openapi' | 'curl' | 'ansible' | 'terraform' | 'idp' | 'sdk' | 'describe' | 'step' | 'node' | 'publish'

/** Where the action chosen in the Provider sheet goes as a step: a new step at a point (null: at the end), or an existing step it fills. */
export type ProviderSheetTarget = { kind: 'insert'; point: InsertPoint | null } | { kind: 'step'; step: string }

/** The open Provider sheet: the draft or published provider it shows, what it was opened from, and where its chosen action goes. */
export interface ProviderSheetState {
  entry: ProviderSheetEntry
  /** the id of the draft it edits (a key of `providerDrafts`), null while it has none */
  draft: string | null
  /** the published provider it shows or edits, null for a new one */
  provider: string | null
  /** where the chosen action goes as a step, null when it is not inserted */
  target: ProviderSheetTarget | null
}

/** What `openProviderSheet` takes: the entry, and the rest when there is one. */
export type ProviderSheetOptions = Pick<ProviderSheetState, 'entry'> & Partial<Omit<ProviderSheetState, 'entry'>>

/** The step `closeProviderSheet` makes of the chosen action, as the node creator's step items name one. */
export interface ProviderSheetStep {
  type: StepType
  params: NewStepParams
}

export type { ProviderInReview } from './providerDrafts'

export interface DesignerState {
  name: string | null
  isNew: boolean
  detail: WorkflowDetail | null
  doc: WorkflowDocument
  dirty: boolean
  graph: Graph
  registry: Registry | null
  registryLoading: boolean
  ctx: CheckContext
  problems: Problem[]
  canUndo: boolean
  canRedo: boolean
  tab: DesignerTab
  /** the node open in the node detail view */
  ndv: string | null
  creator: CreatorState | null
  testRun: TestRun | null
  pins: Record<string, JsonValue>
  execution: ExecutionView | null
  stickies: Sticky[]
  /** the providers this workflow uses before they are published, by draft id; kept in this browser, outside the document and undo */
  providerDrafts: Record<string, ProviderDraft>
  /** the Provider sheet, when open */
  providerSheet: ProviderSheetState | null
  /** the drafts a Publish opened change requests for, not merged yet; kept in this browser per workflow */
  providersInReview: ProviderInReview[]
  saving: boolean
  /** bumps on every document change */
  version: number
  /** the tab the node detail view opens on */
  ndvTab: NdvTab
  /** the canvas context menu */
  menu: CanvasMenu | null
  /** the step whose id/name the rename dialog edits */
  renaming: string | null
  /** the input of the last test run, to start the next one from */
  lastInput: JsonObject | null
}

export type NdvTab = 'parameters' | 'settings'

export type CanvasMenuTarget = { kind: 'node'; id: string } | { kind: 'pane'; flowX: number; flowY: number } | { kind: 'edge'; id: string } | { kind: 'selection'; ids: string[] }

export interface CanvasMenu {
  /** viewport coordinates of the pointer */
  x: number
  y: number
  target: CanvasMenuTarget
}

interface Snapshot {
  doc: WorkflowDocument
  overlay: Map<string, Position>
}

const UNDO_LIMIT = 100
const COALESCE_MS = 900
const CHECK_DELAY_MS = 150
export const REVEAL_STEP_MS = 120

export interface DesignerStoreInit {
  name: string | null
  doc: WorkflowDocument
  detail?: WorkflowDetail | null
  isNew?: boolean
  /** skip the registry (tests) */
  registry?: Registry | null
}

/** A warning when a step of the edited graph is reachable from no trigger (it will run last). */
function orphanWarning(g: Graph, source: string, target: string): string | null {
  const reached = new Set<string>()
  const walk = (id: string) => {
    if (reached.has(id)) return
    reached.add(id)
    for (const e of g.edges) if (e.source === id) walk(e.target)
  }
  walk(API_TRIGGER_ID)
  walk(ERROR_TRIGGER_ID)
  for (const n of g.nodes) if (n.data.kind === 'trigger') walk(n.id)
  const orphan = g.nodes.find((n) => isStepNode(n) && !reached.has(n.id) && n.id !== source && n.id !== target)
  return orphan ? 'Steps without a connection still run, at the end of the workflow; delete them if they are not needed' : null
}

export function isAutoLayout(doc: WorkflowDocument): boolean {
  const g = documentToGraph(doc)
  return !g.nodes.some((n) => isStepNode(n) && n.positioned)
}

/** The graph of a document with every node placed: document positions, the overlay, then the layout. */
export function placeGraph(doc: WorkflowDocument, overlay: ReadonlyMap<string, Position>): Graph {
  const g = documentToGraph(doc)
  const auto = autoLayout(g)
  const byId = new Map<string, GraphNode>()
  const placed: GraphNode[] = g.nodes.map((n) => {
    const o = overlay.get(n.id)
    const node = n.positioned ? n : o ? { ...n, position: o } : { ...n, position: auto.get(n.id) ?? n.position }
    byId.set(node.id, node)
    return node
  })
  // nodes the document cannot position follow the step they hang off, when that step is positioned
  const anyPositioned = placed.some((n) => isStepNode(n) && n.positioned)
  if (anyPositioned) {
    const firstStep = (sourceId: string): GraphNode | undefined => {
      const e = g.edges.find((x) => x.source === sourceId)
      return e ? byId.get(e.target) : undefined
    }
    for (const n of placed) {
      if (n.positioned || overlay.has(n.id)) continue
      if (n.data.kind === 'trigger') {
        const first = firstStep(n.id)
        if (first && isStepNode(first) && first.positioned) {
          const i = n.data.index === null ? 0 : n.data.index + 1
          n.position = { x: first.position.x - COLUMN, y: first.position.y + i * ROW }
        }
      } else if (n.data.kind === 'error-trigger') {
        const first = firstStep(n.id)
        if (first && isStepNode(first) && first.positioned) n.position = { x: first.position.x - COLUMN, y: first.position.y }
      } else if (n.data.kind === 'undo') {
        const step = findStepNode({ ...g, nodes: placed }, n.data.stepId)
        if (step && step.positioned) n.position = { x: step.position.x, y: step.position.y + ROW }
      } else if (n.data.kind === 'end') {
        const e = g.edges.find((x) => x.target === n.id)
        const src = e ? byId.get(e.source) : undefined
        if (e && src && isStepNode(src) && src.positioned) n.position = positionAfter({ ...g, nodes: placed }, src.id, e.sourceHandle)
      }
    }
  }
  return { ...g, nodes: placed }
}

export class DesignerStore {
  private state: DesignerState
  private readonly listeners = new Set<() => void>()
  private past: Snapshot[] = []
  private future: Snapshot[] = []
  private overlay = new Map<string, Position>()
  private lastCoalesce: { key: string; at: number } | null = null
  private checkTimer: ReturnType<typeof setTimeout> | null = null
  /** the backend's problems for one version of the document (Validate) */
  private validated: { version: number; problems: Problem[] } | null = null
  /** bumps whenever the registry the canvas reads changes: a Validate answer is about the providers it was asked with */
  private registryVersion = 0
  private revealTimer: ReturnType<typeof setTimeout> | null = null
  private eventSub: Subscription | null = null
  private refetchTimer: ReturnType<typeof setTimeout> | null = null
  private savedJson: string
  /** the registry as loaded, without the workflow's unpublished providers (`state.registry` has them) */
  private loaded: Registry | null
  /** the provider change requests whose merge reloaded the registry */
  private readonly mergedChanges = new Set<string>()
  /** the first load of the registry, while it runs: a reload on a merge waits for it, so the older answer cannot land last */
  private registryLoad: Promise<void> | null = null
  private outputsCache: { version: number; testRun: TestRun | null; pins: Record<string, JsonValue>; execution: ExecutionView | null; value: StepOutputs } | null = null

  constructor(init: DesignerStoreInit) {
    const doc = clone(init.doc)
    this.savedJson = init.isNew ? '' : JSON.stringify(doc)
    const registry = init.registry === undefined ? null : init.registry
    this.loaded = registry
    const providerDrafts = init.name ? loadProviderDrafts(init.name) : {}
    const providersInReview = init.name ? loadProvidersInReview(init.name) : []
    const merged = this.registryFor(doc, providerDrafts, providersInReview)
    this.state = {
      name: init.name,
      isNew: init.isNew ?? false,
      detail: init.detail ?? null,
      doc,
      dirty: init.isNew ?? false,
      graph: placeGraph(doc, this.overlay),
      registry: merged,
      registryLoading: init.registry === undefined,
      ctx: contextOf(merged),
      problems: [],
      canUndo: false,
      canRedo: false,
      tab: 'editor',
      ndv: null,
      creator: null,
      testRun: null,
      pins: {},
      execution: null,
      stickies: init.name ? loadStickies(init.name) : [],
      providerDrafts,
      providerSheet: null,
      providersInReview,
      saving: false,
      version: 0,
      ndvTab: 'parameters',
      menu: null,
      renaming: null,
      lastInput: null,
    }
    this.runChecks()
    if (init.registry === undefined) {
      this.registryLoad = loadRegistry()
        .then((r) => {
          this.loaded = r
          this.setRegistry({ registryLoading: false })
        })
        .catch(() => {
          this.loaded = EMPTY_REGISTRY
          this.setRegistry({ registryLoading: false })
        })
    }
    // a change request may have merged or closed since: the canvas follows
    if (providersInReview.length > 0) void this.refreshProvidersInReview()
  }

  // ------------------------------------------------------------------ plumbing

  getState = (): DesignerState => this.state

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  dispose(): void {
    if (this.checkTimer) clearTimeout(this.checkTimer)
    if (this.revealTimer) clearTimeout(this.revealTimer)
    if (this.refetchTimer) clearTimeout(this.refetchTimer)
    this.eventSub?.close()
    this.eventSub = null
  }

  private set(patch: Partial<DesignerState>): void {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l())
  }

  /**
   * What the canvas reads as the registry: the loaded one with the providers the workflow has not
   * published, its drafts (under the workflow's owner when they name none) and the ones in review,
   * each in place of a published provider of its name. Null while the registry loads.
   */
  private registryFor(doc: WorkflowDocument, drafts: Readonly<Record<string, ProviderDraft>>, review: readonly ProviderInReview[]): Registry | null {
    if (!this.loaded) return null
    const owner = doc.metadata?.owner
    const inReview = 'in review' as const
    const unpublished = [...Object.values(drafts).map((d) => draftProvider(d, owner)), ...review.map((r) => ({ ...draftProvider(r.draft), unpublished: inReview }))]
    const playbooks = [...Object.values(drafts).flatMap((d) => draftPlaybooks(d, owner)), ...review.flatMap((r) => draftPlaybooks(r.draft).map((b) => ({ ...b, unpublished: inReview })))]
    return withUnpublished(this.loaded, unpublished, playbooks)
  }

  /**
   * Applies `patch` with the registry and the check context it leads to, and runs the checks
   * again. The last Validate answer goes: it judged the workflow with other providers.
   */
  private setRegistry(patch: Partial<DesignerState> = {}): void {
    const next = { ...this.state, ...patch }
    const registry = this.registryFor(next.doc, next.providerDrafts, next.providersInReview)
    this.validated = null
    this.registryVersion++
    this.set({ ...patch, registry, ctx: contextOf(registry) })
    this.runChecks()
  }

  private runChecks(): void {
    if (this.checkTimer) clearTimeout(this.checkTimer)
    this.checkTimer = setTimeout(() => {
      this.checkTimer = null
      this.set({ problems: this.computeProblems() })
    }, CHECK_DELAY_MS)
  }

  private computeProblems(): Problem[] {
    const { doc, ctx, graph } = this.state
    // the backend's verdict on this very document (Validate) stands until the next edit or registry change
    if (this.validated && this.validated.version === this.state.version) return this.validated.problems
    if (graph.problems.length > 0) return graph.problems
    try {
      return check(doc, ctx)
    } catch (e) {
      return [{ level: 'error', code: 'yaml.invalid', message: errorMessage(e) }]
    }
  }

  /**
   * Every check as the backend runs them on this document (POST /workflows:validate): the same
   * checks as the editor's, with its view of providers and credentials, plus the publish checks
   * (`publish.*`: an unpinned playbook, a route another workflow answers, a breaking change
   * without a new major). The answer replaces the live problems until the document changes.
   * The files of the providers the workflow uses before they are published go along
   * (`providerFiles`), so the backend judges the workflow with them.
   */
  async validate(): Promise<ValidationResult> {
    const version = this.state.version
    const registryVersion = this.registryVersion
    const result = await api.validateWorkflow({ definition: this.state.doc }, this.providerFiles())
    // edited meanwhile, or the providers changed: the answer is about an older document
    if (this.state.version !== version || this.registryVersion !== registryVersion) return result
    this.validated = { version, problems: result.problems }
    if (this.checkTimer) {
      clearTimeout(this.checkTimer)
      this.checkTimer = null
    }
    this.set({ problems: result.problems })
    return result
  }

  /** Problems right now, without the debounce (tests, the save toast). */
  checkNow(): Problem[] {
    if (this.checkTimer) {
      clearTimeout(this.checkTimer)
      this.checkTimer = null
    }
    const problems = this.computeProblems()
    this.set({ problems })
    return problems
  }

  // ------------------------------------------------------------------ document

  /**
   * Replaces the document (and the overlay) as one undoable step. With `coalesce`, repeated
   * commits with the same key within a second fold into one undo entry (typing in a field).
   */
  commit(next: WorkflowDocument, opts: { coalesce?: string; overlay?: Map<string, Position>; resetTest?: boolean } = {}): void {
    // never an empty key: a null under a key the Workflow schema types is left out (model/nulls.ts)
    const doc = withoutEmptyKeys(next)
    const now = Date.now()
    const fold = opts.coalesce !== undefined && this.lastCoalesce !== null && this.lastCoalesce.key === opts.coalesce && now - this.lastCoalesce.at < COALESCE_MS
    if (!fold) {
      this.past.push({ doc: this.state.doc, overlay: new Map(this.overlay) })
      if (this.past.length > UNDO_LIMIT) this.past.shift()
      this.future = []
    }
    this.lastCoalesce = opts.coalesce !== undefined ? { key: opts.coalesce, at: now } : null
    if (opts.overlay) this.overlay = opts.overlay
    this.apply(doc, opts.resetTest !== false)
  }

  private apply(doc: WorkflowDocument, resetTest: boolean): void {
    const graph = placeGraph(doc, this.overlay)
    const json = JSON.stringify(doc)
    // a draft that names no owner takes the workflow's: the registry follows a new owner
    const owner = (doc.metadata?.owner ?? '') !== (this.state.doc.metadata?.owner ?? '')
    const patch: Partial<DesignerState> = {
      doc,
      graph,
      dirty: this.state.isNew || json !== this.savedJson,
      canUndo: this.past.length > 0,
      canRedo: this.future.length > 0,
      version: this.state.version + 1,
    }
    if (resetTest && this.state.testRun && this.state.testRun.status !== 'running') patch.testRun = null
    const ids = new Set(stepIds(graph))
    if (this.state.ndv && !graph.nodes.some((n) => n.id === this.state.ndv)) patch.ndv = null
    const pins: Record<string, JsonValue> = {}
    let pinsChanged = false
    for (const [k, v] of Object.entries(this.state.pins)) {
      if (ids.has(k)) pins[k] = v
      else pinsChanged = true
    }
    if (pinsChanged) patch.pins = pins
    if (owner && Object.keys(this.state.providerDrafts).length > 0) {
      const registry = this.registryFor(doc, this.state.providerDrafts, this.state.providersInReview)
      patch.registry = registry
      patch.ctx = contextOf(registry)
    }
    this.set(patch)
    this.runChecks()
  }

  undo(): void {
    const prev = this.past.pop()
    if (!prev) return
    this.future.push({ doc: this.state.doc, overlay: new Map(this.overlay) })
    this.overlay = prev.overlay
    this.lastCoalesce = null
    this.apply(prev.doc, true)
  }

  redo(): void {
    const next = this.future.pop()
    if (!next) return
    this.past.push({ doc: this.state.doc, overlay: new Map(this.overlay) })
    this.overlay = next.overlay
    this.lastCoalesce = null
    this.apply(next.doc, true)
  }

  /** The whole document, e.g. from the YAML drawer or a template. */
  setDocument(doc: WorkflowDocument): void {
    this.commit(clone(doc))
  }

  update(fn: (d: WorkflowDocument) => void, coalesce?: string): void {
    this.commit(updateDocument(this.state.doc, fn), { coalesce })
  }

  updateStep(stepId: string, fn: (body: StepBody) => void, coalesce?: string): void {
    const next = updateStep(this.state.doc, stepId, fn)
    if (next !== this.state.doc) this.commit(next, { coalesce })
  }

  /** The node id of a step in the current graph. */
  nodeOf(stepId: string): string | null {
    return findStepNode(this.state.graph, stepId)?.id ?? null
  }

  stepOf(nodeId: string): string | null {
    const n = this.state.graph.nodes.find((x) => x.id === nodeId)
    return n && isStepNode(n) ? n.data.stepId : null
  }

  /** Inserts a new step and opens it. Returns its id. */
  addStep(type: StepType, params: NewStepParams = {}, point: InsertPoint | null = null): string {
    const doc = this.state.doc
    const base = params.name ?? (type === 'provider' && params.action ? params.action : type === 'idp' && params.tile ? params.tile : type === 'run' && params.workflow ? `run ${params.workflow}` : type)
    const id = newStepId(doc, base)
    const body = bodyFor(type, { ...params, group: params.group ?? doc.policy?.approval?.group })
    const at: InsertPoint = point ?? { kind: 'end' }
    if (!isAutoLayout(doc)) {
      const p = this.positionForInsert(at)
      if (p) body.position = { x: Math.round(p.x), y: Math.round(p.y) }
    }
    const next = insertStep(doc, at, id, body)
    this.commit(next)
    const nodeId = this.nodeOf(id)
    this.set({ creator: null, ndv: nodeId })
    return id
  }

  /** Where a step inserted at a point lands on a positioned canvas. */
  private positionForInsert(point: InsertPoint): Position | null {
    const g = this.state.graph
    switch (point.kind) {
      case 'start':
        return positionAfter(g, point.flow === 'catch' ? ERROR_TRIGGER_ID : API_TRIGGER_ID, 'main')
      case 'after': {
        const n = this.nodeOf(point.step)
        return n ? positionAfter(g, n, point.handle) : null
      }
      case 'before': {
        const n = this.nodeOf(point.step)
        if (!n) return null
        const into = g.edges.find((e) => e.target === n && e.role !== 'jump')
        if (into) return positionAfter(g, into.source, into.sourceHandle)
        const node = g.nodes.find((x) => x.id === n)
        return node ? { x: node.position.x - COLUMN, y: node.position.y } : null
      }
      case 'end': {
        const main = this.state.doc.workflow?.do
        const last = Array.isArray(main) && main.length > 0 ? Object.keys(main[main.length - 1])[0] : undefined
        const n = last ? this.nodeOf(last) : null
        return positionAfter(g, n ?? API_TRIGGER_ID, 'main')
      }
    }
  }

  /** Deletes nodes: steps (with what they contain), alert and event triggers, the error trigger, compensations. */
  deleteNodes(nodeIds: readonly string[]): void {
    let doc = this.state.doc
    const steps: string[] = []
    const triggers: number[] = []
    let dropCatch = false
    const undos: string[] = []
    for (const id of nodeIds) {
      const n = this.state.graph.nodes.find((x) => x.id === id)
      if (!n) continue
      if (n.data.kind === 'step') steps.push(n.data.stepId)
      else if (n.data.kind === 'trigger' && n.data.index !== null) triggers.push(n.data.index)
      else if (n.data.kind === 'error-trigger') dropCatch = true
      else if (n.data.kind === 'undo') undos.push(n.data.stepId)
    }
    if (steps.length === 0 && triggers.length === 0 && !dropCatch && undos.length === 0) return
    if (steps.length > 0) doc = removeSteps(doc, steps)
    for (const u of undos) doc = setCompensate(doc, u, null)
    for (const t of [...triggers].sort((a, b) => b - a)) doc = removeTrigger(doc, t)
    if (dropCatch) doc = removeCatch(doc)
    this.commit(doc)
  }

  renameStepId(stepId: string, newId: string): boolean {
    if (stepId === newId) return true
    const next = renameStep(this.state.doc, stepId, newId)
    if (next === this.state.doc) return false
    // the open node detail view follows the step to its new node id
    const oldNode = this.nodeOf(stepId)
    if (oldNode !== null && this.state.ndv === oldNode) this.state = { ...this.state, ndv: stepNodeId(newId) }
    const pin = this.state.pins[stepId]
    if (pin !== undefined) {
      const pins = { ...this.state.pins, [newId]: pin }
      delete pins[stepId]
      this.state = { ...this.state, pins }
    }
    this.commit(next)
    return true
  }

  setStepName(stepId: string, name: string): void {
    const node = findStepNode(this.state.graph, stepId)
    const oldName = node?.data.step.name ?? null
    let next = updateStep(this.state.doc, stepId, (body) => {
      if (name.trim()) body.name = name
      else delete body.name
    })
    if (oldName && name.trim() && oldName !== name) next = renameReferences(next, oldName, name)
    this.commit(next, { coalesce: `name:${stepId}` })
  }

  duplicateNodes(nodeIds: readonly string[]): string[] {
    const steps = nodeIds.map((id) => this.stepOf(id)).filter((s): s is string => s !== null)
    if (steps.length === 0) return []
    const g = this.state.graph
    const { doc, ids } = duplicateSteps(this.state.doc, steps, (id) => findStepNode(g, id)?.position)
    this.commit(doc)
    return ids
  }

  /** The clipboard payload of nodes: their document entries. */
  copyNodes(nodeIds: readonly string[]): StepDocument[] {
    const out: StepDocument[] = []
    for (const id of nodeIds) {
      const n = this.state.graph.nodes.find((x) => x.id === id)
      if (n && isStepNode(n)) out.push({ [n.data.stepId]: clone(n.data.body) })
    }
    return out
  }

  paste(entries: readonly StepDocument[], point: InsertPoint | null): string[] {
    const at: InsertPoint = point ?? { kind: 'end' }
    const { doc, ids } = pasteSteps(this.state.doc, entries, at, { x: 40, y: 40 })
    if (ids.length === 0) return []
    this.commit(doc)
    return ids
  }

  /** Drag end: writes positions. The first move of an auto-laid-out document freezes every position. */
  moveNodes(moves: ReadonlyMap<string, Position>): void {
    if (moves.size === 0) return
    const g = this.state.graph
    const stepMoves = new Map<string, Position>()
    const overlay = new Map(this.overlay)
    const freeze = isAutoLayout(this.state.doc)
    for (const n of g.nodes) {
      const moved = moves.get(n.id)
      if (isStepNode(n)) {
        if (moved) stepMoves.set(n.data.stepId, moved)
        else if (freeze) stepMoves.set(n.data.stepId, n.position)
      } else if (moved) overlay.set(n.id, moved)
      else if (freeze) overlay.set(n.id, n.position)
    }
    const doc = setStepPositions(this.state.doc, stepMoves)
    this.commit(doc, { overlay, resetTest: false })
  }

  /** n8n's Tidy up: every node to its layout position. */
  tidyUp(): void {
    const g = documentToGraph(this.state.doc)
    const auto = autoLayout(g)
    const stepMoves = new Map<string, Position>()
    const overlay = new Map<string, Position>()
    for (const n of g.nodes) {
      const p = auto.get(n.id)
      if (!p) continue
      if (isStepNode(n)) stepMoves.set(n.data.stepId, p)
      else overlay.set(n.id, p)
    }
    this.commit(setStepPositions(this.state.doc, stepMoves), { overlay, resetTest: false })
  }

  /** A connection drawn on the canvas. Returns a message when it could not be represented. */
  connect(source: string, sourceHandle: string, target: string): string | null {
    const g = this.state.graph
    const src = g.nodes.find((n) => n.id === source)
    const tgt = g.nodes.find((n) => n.id === target)
    if (!src || !tgt || source === target) return null
    if (tgt.data.kind !== 'step' && tgt.data.kind !== 'end') return 'Only steps take connections'
    if (isStepNode(src) && src.data.step.type === 'switch' && (sourceHandle.startsWith('case:') || sourceHandle === 'default')) {
      const then = tgt.data.kind === 'end' ? 'end' : tgt.data.stepId
      this.commit(connectSwitch(this.state.doc, src.data.stepId, sourceHandle, then))
      return null
    }
    if (tgt.data.kind === 'end') return 'Only a switch case can end the job'
    if (sourceHandle === 'undo') return 'Compensation steps are edited from the step settings'
    const container = isStepNode(src) && (sourceHandle.startsWith('branch:') || sourceHandle === 'loop' || sourceHandle === 'try' || sourceHandle === 'catch')
    const role: GraphEdge['role'] = src.data.kind === 'trigger' || src.data.kind === 'error-trigger' ? 'trigger' : container ? 'branch' : 'main'
    const structural = new Set<GraphEdge['role']>(['main', 'branch', 'join', 'fallthrough', 'loop', 'trigger'])
    const edges = g.edges.filter((e) => !(e.source === source && e.sourceHandle === sourceHandle)).filter((e) => !(e.target === target && structural.has(e.role)))
    edges.push({ id: `${source}:${sourceHandle}->${target}`, source, sourceHandle, target, targetHandle: 'in', role })
    const edited: Graph = { ...g, edges }
    const warning = orphanWarning(edited, source, target)
    const before = stepIds(g).length
    const next = graphToDocument(edited, this.state.doc)
    this.commit(next)
    const after = stepIds(this.state.graph).length
    if (after !== before) return 'Some steps could not be kept'
    return warning
  }

  /** Removes a connection; most edges of a flow cannot go, the message says why. */
  deleteEdge(edgeId: string): string | null {
    const g = this.state.graph
    const e = g.edges.find((x) => x.id === edgeId)
    if (!e) return null
    const src = g.nodes.find((n) => n.id === e.source)
    switch (e.role) {
      case 'jump':
        if (src && isStepNode(src)) this.commit(connectSwitch(this.state.doc, src.data.stepId, e.sourceHandle, 'end'))
        return null
      case 'undo':
        if (src && isStepNode(src)) this.commit(setCompensate(this.state.doc, src.data.stepId, null))
        return null
      case 'main':
      case 'branch': {
        const next = graphToDocument({ ...g, edges: g.edges.filter((x) => x.id !== edgeId) }, this.state.doc)
        this.commit(next)
        return 'Steps in a chain always run in order: the steps after the removed connection now run at the end of the workflow'
      }
      case 'trigger':
        return 'A trigger always starts the first step; delete the trigger instead'
      case 'join':
      case 'fallthrough':
        return 'A branch always rejoins the step after its container'
      case 'loop':
        return 'A loop body always returns to its loop'
    }
  }

  // ------------------------------------------------------------------ step blocks

  addSwitchCase(stepId: string, isDefault = false): void {
    this.commit(addSwitchCase(this.state.doc, stepId, isDefault))
  }

  removeSwitchCase(stepId: string, index: number): void {
    this.commit(removeSwitchCase(this.state.doc, stepId, index))
  }

  addForkBranch(stepId: string): void {
    this.commit(addForkBranch(this.state.doc, stepId))
  }

  removeForkBranch(stepId: string, index: number): void {
    this.commit(removeForkBranch(this.state.doc, stepId, index))
  }

  setCompensate(stepId: string, steps: StepDocument[] | null): void {
    this.commit(setCompensate(this.state.doc, stepId, steps), { coalesce: `compensate:${stepId}` })
  }

  addTrigger(kind: TriggerKind): void {
    this.commit(addTrigger(this.state.doc, kind))
  }

  removeTrigger(index: number): void {
    this.commit(removeTrigger(this.state.doc, index))
  }

  setTrigger(index: number, spec: JsonObject): void {
    this.commit(setTrigger(this.state.doc, index, spec), { coalesce: `trigger:${index}` })
  }

  /** A one-click fix; `connect` fixes open the creator in front of the step, `remove-step` deletes the step. */
  applyFix(problem: Problem): boolean {
    const fix = fixOf(problem)
    if (!fix) return false
    if (fix.kind === 'connect') {
      if (!problem.step) return false
      this.openCreator({ kind: 'before', step: problem.step })
      return true
    }
    if (fix.kind === 'remove-step') {
      const node = problem.step ? this.nodeOf(problem.step) : null
      if (!node) return false
      if (this.state.ndv === node) this.closeNdv()
      this.deleteNodes([node])
      return true
    }
    const next = applyFix(this.state.doc, problem)
    if (!next) return false
    this.commit(next)
    return true
  }

  // ------------------------------------------------------------------ panels

  setTab(tab: DesignerTab): void {
    if (tab !== 'executions' && this.state.execution) this.closeExecution()
    this.set({ tab })
  }

  openNdv(nodeId: string | null, tab: NdvTab = 'parameters'): void {
    this.set({ ndv: nodeId, ndvTab: tab, creator: null, menu: null })
  }

  setNdvTab(tab: NdvTab): void {
    if (this.state.ndvTab !== tab) this.set({ ndvTab: tab })
  }

  closeNdv(): void {
    if (this.state.ndv !== null) this.set({ ndv: null })
  }

  openCreator(point: InsertPoint | null = null, query = ''): void {
    this.set({ creator: { point, query }, ndv: null, menu: null })
  }

  closeCreator(): void {
    if (this.state.creator) this.set({ creator: null })
  }

  openMenu(menu: CanvasMenu): void {
    this.set({ menu })
  }

  closeMenu(): void {
    if (this.state.menu) this.set({ menu: null })
  }

  startRename(stepId: string): void {
    this.set({ renaming: stepId, menu: null })
  }

  stopRename(): void {
    if (this.state.renaming !== null) this.set({ renaming: null })
  }

  /** The workflow as the server knows it (versions, status), without touching the document. */
  setDetail(detail: WorkflowDetail | null): void {
    this.set({ detail })
  }

  /**
   * A credential stored from a step's credential field: its name and type join the registry at
   * once, and the next canvas of the session lists it too. Its value never reaches the designer.
   */
  addCredential(credential: RegistryCredential): void {
    rememberCredential(credential)
    const add = () => {
      if (!this.loaded) return
      this.loaded = withCredential(this.loaded, credential)
      this.setRegistry()
    }
    // a registry still loading takes it once it lands: by then the session's registry lists it
    if (this.state.registryLoading) void loadRegistry().catch(() => undefined).then(add)
    else add()
  }

  // ------------------------------------------------------------------ pins, stickies

  togglePin(stepId: string, output: JsonValue | undefined): void {
    const pins = { ...this.state.pins }
    if (pins[stepId] !== undefined) delete pins[stepId]
    else if (output !== undefined) pins[stepId] = output
    else return
    this.set({ pins })
  }

  /** Pinned data set by hand ("Set mock data"), or removed with undefined. */
  setPin(stepId: string, value: JsonValue | undefined): void {
    const pins = { ...this.state.pins }
    if (value === undefined) delete pins[stepId]
    else pins[stepId] = value
    this.set({ pins })
  }

  setStickies(stickies: Sticky[]): void {
    this.set({ stickies })
    if (this.state.name) saveStickies(this.state.name, stickies)
  }

  // ------------------------------------------------------------------ provider drafts, the Provider sheet

  /**
   * Adds or replaces a provider draft (by its id), kept in this browser as the stickies are; not
   * a document edit, so outside undo and the dirty flag. A draft whose owner nobody set takes the
   * workflow's (`metadata.owner`), as the wizard's takes the signed-in user's team. An open sheet
   * without a draft takes it when it is a new provider's or the draft is of the sheet's provider.
   * The canvas reads the draft as a published provider at once.
   */
  upsertProviderDraft(draft: ProviderDraft): void {
    const d = draft.ownerTouched ? draft : withDefaultOwner(draft, this.state.doc.metadata?.owner)
    const providerDrafts = { ...this.state.providerDrafts, [d.id]: d }
    const sheet = this.state.providerSheet
    const takes = sheet !== null && sheet.draft === null && (sheet.provider === null || sheet.provider === d.name)
    this.setRegistry(takes ? { providerDrafts, providerSheet: { ...sheet, draft: d.id } } : { providerDrafts })
    this.persistProviderDrafts()
  }

  /** Drops a provider draft (once published, or discarded), and closes the sheet that edits it. */
  removeProviderDraft(id: string): void {
    if (!(id in this.state.providerDrafts)) return
    const providerDrafts = { ...this.state.providerDrafts }
    delete providerDrafts[id]
    this.setRegistry(this.state.providerSheet?.draft === id ? { providerDrafts, providerSheet: null } : { providerDrafts })
    this.persistProviderDrafts()
  }

  /**
   * A draft whose publish opened `changeRequest`: the draft is dropped (as `removeProviderDraft`),
   * and the provider stays in review, kept in this browser, until that change request merges.
   */
  providerDraftPublished(id: string, changeRequest: string): void {
    const draft = this.state.providerDrafts[id]
    if (!draft) return
    const published = withDefaultOwner(draft, this.state.doc.metadata?.owner)
    const providerDrafts = { ...this.state.providerDrafts }
    delete providerDrafts[id]
    const providersInReview = [...this.state.providersInReview.filter((r) => r.draft.name !== draft.name), { changeRequest, draft: published, yaml: draftFile(published, null) }]
    this.setRegistry({ providerDrafts, providersInReview, ...(this.state.providerSheet?.draft === id ? { providerSheet: null } : {}) })
    this.persistProviderDrafts()
    this.persistProvidersInReview()
  }

  /**
   * A provider change request merged (the provider is published): the registry is loaded again so
   * the canvas reads the published provider, and what was kept in review for it goes. Once per
   * change request.
   */
  async providerChangeMerged(changeRequest: string): Promise<void> {
    if (this.mergedChanges.has(changeRequest)) return
    this.mergedChanges.add(changeRequest)
    try {
      await this.registryLoad
      this.loaded = await loadRegistry(true)
    } catch {
      /* the registry as it was: what is in review stays the canvas's view of the provider, until asked again */
      this.mergedChanges.delete(changeRequest)
      return
    }
    this.setRegistry({ providersInReview: this.state.providersInReview.filter((r) => r.changeRequest !== changeRequest) })
    this.persistProvidersInReview()
  }

  /**
   * How each provider in review stands now (GET /change-requests/{id}), for a workflow opened
   * again or a Publish about to require them: merged, the provider is published
   * (`providerChangeMerged`); closed, or no longer there, its draft is the workflow's again
   * (`providerChangeClosed`); open, it stays, with the file its change request publishes. One that
   * does not answer stays as it was.
   */
  async refreshProvidersInReview(): Promise<void> {
    await Promise.all(
      this.state.providersInReview.map(async ({ changeRequest }) => {
        try {
          const cr = await api.changeRequest(changeRequest)
          if (cr.status === 'merged') await this.providerChangeMerged(changeRequest)
          else if (cr.status === 'closed') this.providerChangeClosed(changeRequest)
          else if (cr.yaml?.trim()) this.set({ providersInReview: this.state.providersInReview.map((r) => (r.changeRequest === changeRequest ? { ...r, yaml: cr.yaml } : r)) })
        } catch (e) {
          if (isApiError(e) && e.status === 404) this.providerChangeClosed(changeRequest)
        }
      }),
    )
  }

  /**
   * A provider change request closed without merging: the provider is no longer in review (no
   * later Publish requires that change request), and the draft it was published from is the
   * workflow's again, unless the workflow has a draft of that provider by now, so it can be fixed
   * and published again. Returns whether it was in review.
   */
  providerChangeClosed(changeRequest: string): boolean {
    const entry = this.state.providersInReview.find((r) => r.changeRequest === changeRequest)
    if (!entry) return false
    const providersInReview = this.state.providersInReview.filter((r) => r !== entry)
    const providerDrafts = findProviderDraft(this.state.providerDrafts, entry.draft.name) ? this.state.providerDrafts : { ...this.state.providerDrafts, [entry.draft.id]: entry.draft }
    this.setRegistry({ providerDrafts, providersInReview })
    this.persistProviderDrafts()
    this.persistProvidersInReview()
    return true
  }

  /** The drafts the workflow uses, under its owner when they name none: the ones Publish opens a change request for. */
  usedProviderDrafts(): ProviderDraft[] {
    const owner = this.state.doc.metadata?.owner
    return draftsUsedBy(this.state.doc, this.state.providerDrafts, this.loaded?.providers).map((d) => withDefaultOwner(d, owner))
  }

  /** The providers in review the workflow uses (none of its drafts has their name): the change requests its next Publish requires. */
  usedProvidersInReview(): ProviderInReview[] {
    const drafts = new Set(this.usedProviderDrafts().map((d) => d.name))
    const byId = Object.fromEntries(this.state.providersInReview.map((r) => [r.draft.id, r.draft]))
    const used = new Set(draftsUsedBy(this.state.doc, byId, this.loaded?.providers).map((d) => d.name))
    return this.state.providersInReview.filter((r) => used.has(r.draft.name) && !drafts.has(r.draft.name))
  }

  /** The files of the providers the workflow uses before they are published (its drafts, then the ones in review): what a dry run and Validate send as `providers`. */
  providerFiles(): string[] {
    const owner = this.state.doc.metadata?.owner
    return [...this.usedProviderDrafts().map((d) => draftFile(d, owner)), ...this.usedProvidersInReview().map((r) => r.yaml)]
  }

  /**
   * Opens the Provider sheet, closing the node creator and the canvas menu. On a published
   * provider without a draft named, it opens the workflow's draft of that provider if there is one.
   */
  openProviderSheet(options: ProviderSheetOptions): void {
    const provider = options.provider ?? null
    const draft = options.draft ?? findProviderDraft(this.state.providerDrafts, provider)?.id ?? null
    this.set({ providerSheet: { entry: options.entry, draft, provider, target: options.target ?? null }, creator: null, menu: null })
  }

  /**
   * Closes the Provider sheet. With the chosen action as a step, it goes where the sheet was
   * opened for: inserted at its point and opened (as `addStep`), or filling the step it was opened
   * from (its `call` and `with`; the step keeps its id and name). Returns that step's id, else null.
   */
  closeProviderSheet(step?: ProviderSheetStep): string | null {
    const target = this.state.providerSheet?.target ?? null
    if (this.state.providerSheet) this.set({ providerSheet: null })
    if (!step || !target) return null
    if (target.kind === 'insert') return this.addStep(step.type, step.params, target.point)
    if (!this.nodeOf(target.step)) return null
    const body = bodyFor(step.type, step.params)
    this.updateStep(target.step, (b) => {
      b.call = body.call
      b.with = body.with
    })
    return target.step
  }

  private persistProviderDrafts(): void {
    if (this.state.name) saveProviderDrafts(this.state.name, this.state.providerDrafts)
  }

  private persistProvidersInReview(): void {
    if (this.state.name) saveProvidersInReview(this.state.name, this.state.providersInReview)
  }

  // ------------------------------------------------------------------ save, test, publish

  async save(): Promise<DraftSaved> {
    const { doc, name, isNew } = this.state
    this.set({ saving: true })
    try {
      if (isNew || !name) {
        const newName = doc.metadata?.name ?? name ?? ''
        await api.createWorkflow({ definition: doc })
        this.savedJson = JSON.stringify(doc)
        this.set({ isNew: false, name: newName, dirty: false, problems: this.checkNow() })
        if (this.state.stickies.length > 0) saveStickies(newName, this.state.stickies)
        if (Object.keys(this.state.providerDrafts).length > 0) saveProviderDrafts(newName, this.state.providerDrafts)
        if (this.state.providersInReview.length > 0) saveProvidersInReview(newName, this.state.providersInReview)
        return { name: newName, version: doc.metadata?.version ?? '0.1.0', problems: this.state.problems }
      }
      const saved = await api.saveDraft(name, { definition: doc })
      this.savedJson = JSON.stringify(doc)
      // the backend judges the saved draft with the published providers only: a workflow that
      // calls unpublished ones keeps the live checks (or Validate's answer, which had them)
      const problems = saved.problems.length > 0 && this.providerFiles().length === 0 ? saved.problems : this.checkNow()
      this.set({ dirty: false, problems })
      return { ...saved, problems }
    } finally {
      this.set({ saving: false })
    }
  }

  /** Marks the current document as the saved one (after a reload from the server). */
  markSaved(detail: WorkflowDetail | null): void {
    this.savedJson = JSON.stringify(this.state.doc)
    this.set({ dirty: false, detail: detail ?? this.state.detail })
  }

  /** A dry run of the document (or of a step, `focus`), with the files of the providers it uses before they are published (`providerFiles`). */
  async testRun(input: JsonObject, focus: string | null = null): Promise<DryRunResult | null> {
    if (this.revealTimer) clearTimeout(this.revealTimer)
    this.set({ testRun: { status: 'running', input, result: null, revealed: 0, error: null, startedAt: Date.now(), finishedAt: null, focus }, lastInput: input })
    try {
      const result = await api.dryRunWorkflow({ definition: this.state.doc }, input, this.providerFiles())
      const run = this.state.testRun
      if (!run || run.status !== 'running') return result
      this.set({ testRun: { ...run, result, finishedAt: Date.now() } })
      this.reveal()
      return result
    } catch (e) {
      const run = this.state.testRun
      if (run) this.set({ testRun: { ...run, status: 'failed', error: errorMessage(e), finishedAt: Date.now() } })
      return null
    }
  }

  private reveal(): void {
    const run = this.state.testRun
    if (!run || !run.result) return
    const total = run.result.path.length
    if (run.revealed >= total) {
      this.set({ testRun: { ...run, revealed: total, status: run.result.passed ? 'passed' : 'failed' } })
      return
    }
    this.set({ testRun: { ...run, revealed: run.revealed + 1 } })
    this.revealTimer = setTimeout(() => this.reveal(), REVEAL_STEP_MS)
  }

  clearTestRun(): void {
    if (this.revealTimer) clearTimeout(this.revealTimer)
    this.set({ testRun: null })
  }

  /** "Debug in editor": a job's step outputs become pinned data on the editor's nodes. */
  pinOutputs(outputs: Record<string, JsonValue>): void {
    const ids = new Set(stepIds(this.state.graph))
    const pins = { ...this.state.pins }
    for (const [k, v] of Object.entries(outputs)) if (ids.has(k)) pins[k] = v
    this.set({ pins })
  }

  /** Outputs by step id: pinned ones, then the test run's, then the job's in an execution view. */
  knownOutputs(): StepOutputs {
    const { version, testRun, pins, execution } = this.state
    const c = this.outputsCache
    if (c && c.version === version && c.testRun === testRun && c.pins === pins && c.execution === execution) return c.value
    const out: StepOutputs = {}
    if (execution?.job) for (const s of execution.job.steps) if (s.output !== undefined && s.status === 'succeeded') out[s.id] = s.output as JsonValue
    if (testRun?.result) for (const s of testRun.result.steps) if (s.output !== undefined && s.output !== null) out[s.id] = s.output as JsonValue
    for (const [k, v] of Object.entries(pins)) out[k] = v
    this.outputsCache = { version, testRun, pins, execution, value: out }
    return out
  }

  // ------------------------------------------------------------------ executions

  openExecution(jobId: string): void {
    this.closeExecution()
    this.set({ execution: { jobId, job: null, loading: true, error: null }, tab: 'executions' })
    void this.fetchJob(jobId)
  }

  private async fetchJob(jobId: string): Promise<void> {
    try {
      const job = await api.job(jobId)
      const ex = this.state.execution
      if (!ex || ex.jobId !== jobId) return
      this.set({ execution: { ...ex, job, loading: false, error: null } })
      const live = job.status === 'queued' || job.status === 'running' || job.status === 'waiting-approval' || job.status === 'needs-attention'
      if (live && !this.eventSub) {
        this.eventSub = jobEvents(jobId, () => {
          if (this.refetchTimer) clearTimeout(this.refetchTimer)
          this.refetchTimer = setTimeout(() => void this.fetchJob(jobId), 250)
        })
      } else if (!live && this.eventSub) {
        this.eventSub.close()
        this.eventSub = null
      }
    } catch (e) {
      const ex = this.state.execution
      if (ex && ex.jobId === jobId) this.set({ execution: { ...ex, loading: false, error: errorMessage(e) } })
    }
  }

  closeExecution(): void {
    this.eventSub?.close()
    this.eventSub = null
    if (this.refetchTimer) clearTimeout(this.refetchTimer)
    if (this.state.execution) this.set({ execution: null })
  }
}

// ---------------------------------------------------------------------------------------------
// React bindings

export const DesignerContext = createContext<DesignerStore | null>(null)

export function useDesignerStore(): DesignerStore {
  const store = useContext(DesignerContext)
  if (!store) throw new Error('useDesignerStore needs a DesignerContext')
  return store
}

export function useDesigner(): DesignerState {
  const store = useDesignerStore()
  return useSyncExternalStore(store.subscribe, store.getState, store.getState)
}

export function useDesignerSelector<T>(select: (s: DesignerState) => T): T {
  const store = useDesignerStore()
  return useSyncExternalStore(store.subscribe, () => select(store.getState()), () => select(store.getState()))
}

export type { InsertPoint }
