/**
 * The canvas, on @xyflow/react, behaving like n8n's: dot grid, fit on open, rubber-band
 * selection, wheel pans and ⌘-wheel or pinch zooms, connections drawn from the output dots,
 * a connection dropped on empty canvas opens the node creator, the right-click menu, and n8n's
 * keys (Tab, Enter, F2, P, arrows, Delete, ⌘A/C/X/V/D/Z, ⇧S, ⇧⌥T, 0, 1, +, −).
 *
 * Nodes are local state (xyflow drags them); the document is the truth, so every finished
 * gesture (a drag, a connection, a delete) goes to the store, which re-derives the graph.
 */
import '@xyflow/react/dist/base.css'
import '../designer.css'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  applyNodeChanges,
  MiniMap,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  useReactFlow,
  type Connection,
  type EdgeChange,
  type FinalConnectionState,
  type IsValidConnection,
  type NodeChange,
  type Viewport,
} from '@xyflow/react'
import { ConfirmDialog, cx, Icon, isEditableTarget, modKeyLabel, Tooltip, useToast } from '@iaf/ui'
import type { JsonValue, Problem, StepDocument } from '@iaf/workflow-checks'
import type { RegistryProvider } from '../../lib/types'
import { API_TRIGGER_ID, stepNodeId, type Graph, type Position } from '../model/graph'
import { sampleInput } from '../model/expressions'
import { newSticky, type Sticky } from '../model/stickies'
import { useDesignerSelector, useDesignerStore, type CanvasMenuTarget, type InsertPoint } from '../model/store'
import { ContextMenu, type MenuEntry } from './ContextMenu'
import { EDGE_TYPES } from './FlowEdge'
import { insertPointOfHandle, mergeNodes, PLACEHOLDER_ID, STICKY_PREFIX, stickyNodeId, toFlow, type FlowEdge, type FlowNode, type RunInfo } from './flow'
import { UiGlyph } from './glyphs'
import { NODE_TYPES } from './nodes'
import { RenameDialog } from './RenameDialog'

export interface CanvasProps {
  graph: Graph
  problems: readonly Problem[]
  runs: ReadonlyMap<string, RunInfo>
  pins: Readonly<Record<string, JsonValue>>
  stickies: readonly Sticky[]
  providers: readonly RegistryProvider[]
  readOnly?: boolean
  /** double-click or Enter on a node */
  onOpen: (nodeId: string) => void
  /** what floats over the canvas (the test button, the run pill) */
  children?: ReactNode
  label?: string
}

const GRID = 20
const FIT = { padding: 0.25, maxZoom: 1, minZoom: 0.2 }

/** Steps copied in this tab, for the menu's Paste (the system clipboard needs a paste event). */
let clipboard: StepDocument[] | null = null

/** A clipboard text as step entries: `[{ "<id>": { … } }]` or one entry. */
export function parseClipboard(text: string): StepDocument[] | null {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    return null
  }
  const list = Array.isArray(value) ? value : [value]
  const out: StepDocument[] = []
  for (const item of list) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null
    const keys = Object.keys(item)
    if (keys.length !== 1) return null
    const body = (item as Record<string, unknown>)[keys[0]]
    if (!body || typeof body !== 'object' || Array.isArray(body)) return null
    out.push(item as StepDocument)
  }
  return out.length > 0 ? out : null
}

function modalOpen(): boolean {
  return document.querySelector('[aria-modal="true"]') !== null
}

function ControlButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <Tooltip label={label} side="top">
      <button
        type="button"
        aria-label={label}
        onClick={onClick}
        className="flex h-9 w-9 items-center justify-center rounded-sm border border-stroke bg-surface text-secondary shadow-sm transition-colors hover:border-tertiary hover:text-primary"
      >
        {children}
      </button>
    </Tooltip>
  )
}

const MINI_CLASS = (n: FlowNode): string => (n.type === 'trigger' ? 'iaf-mini-trigger' : n.type === 'sticky' ? 'iaf-mini-sticky' : '')

function CanvasInner({ graph, problems, runs, pins, stickies, providers, readOnly = false, onOpen, children, label = 'Workflow canvas' }: CanvasProps) {
  const store = useDesignerStore()
  const toast = useToast()
  const flowApi = useReactFlow<FlowNode, FlowEdge>()
  const wrapRef = useRef<HTMLDivElement>(null)
  const menu = useDesignerSelector((s) => s.menu)
  const renaming = useDesignerSelector((s) => s.renaming)
  const ndv = useDesignerSelector((s) => s.ndv)
  const [confirm, setConfirm] = useState<{ count: number; run: () => void } | null>(null)

  const flow = useMemo(() => toFlow({ graph, problems, runs, pins, stickies, providers, readOnly }), [graph, problems, runs, pins, stickies, providers, readOnly])

  // xyflow's node list: derived from the flow, keeping selection, measurements and drags
  const [nodes, setNodes] = useState<FlowNode[]>(flow.nodes)
  const [base, setBase] = useState(flow.nodes)
  if (base !== flow.nodes) {
    setBase(flow.nodes)
    setNodes((prev) => mergeNodes(prev, flow.nodes))
  }
  const [edgeSel, setEdgeSel] = useState<ReadonlySet<string>>(() => new Set())
  const edges = useMemo(() => (edgeSel.size === 0 ? flow.edges : flow.edges.map((e) => (edgeSel.has(e.id) ? { ...e, selected: true } : e))), [flow.edges, edgeSel])

  const nodesRef = useRef(nodes)
  const edgesRef = useRef(edges)
  const edgeSelRef = useRef(edgeSel)
  useLayoutEffect(() => {
    nodesRef.current = nodes
    edgesRef.current = edges
    edgeSelRef.current = edgeSel
  })

  // a selection asked for before its nodes exist (paste, duplicate, a new sticky)
  const pendingSelect = useRef<Set<string> | null>(null)
  useEffect(() => {
    const sel = pendingSelect.current
    if (!sel || !nodes.some((n) => sel.has(n.id))) return
    pendingSelect.current = null
    setNodes((nds) => nds.map((n) => (!!n.selected === sel.has(n.id) ? n : { ...n, selected: sel.has(n.id) })))
  }, [nodes])

  const onNodesChange = useCallback((changes: NodeChange<FlowNode>[]) => {
    setNodes((nds) => applyNodeChanges(changes.filter((c) => c.type !== 'remove' && c.type !== 'add' && c.type !== 'replace'), nds))
  }, [])

  const onEdgesChange = useCallback((changes: EdgeChange<FlowEdge>[]) => {
    setEdgeSel((prev) => {
      let next: Set<string> | null = null
      for (const c of changes) {
        if (c.type !== 'select' || c.selected === prev.has(c.id)) continue
        next ??= new Set(prev)
        if (c.selected) next.add(c.id)
        else next.delete(c.id)
      }
      return next ?? prev
    })
  }, [])

  // ------------------------------------------------------------------ selection helpers

  const selectedIds = useCallback(() => nodesRef.current.filter((n) => n.selected).map((n) => n.id), [])

  const reveal = useCallback(
    (id: string) => {
      const el = wrapRef.current?.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`)
      el?.focus({ preventScroll: true })
      const n = flowApi.getNode(id)
      const wrap = wrapRef.current
      if (!n || !wrap) return
      const { x, y, zoom } = flowApi.getViewport()
      const w = n.measured?.width ?? 100
      const h = n.measured?.height ?? 100
      const sx = n.position.x * zoom + x
      const sy = n.position.y * zoom + y
      const rect = wrap.getBoundingClientRect()
      if (sx < 40 || sy < 40 || sx + w * zoom > rect.width - 40 || sy + h * zoom > rect.height - 40) {
        void flowApi.setCenter(n.position.x + w / 2, n.position.y + h / 2, { zoom, duration: 200 })
      }
    },
    [flowApi],
  )

  const selectOnly = useCallback(
    (id: string | null, show = false) => {
      setNodes((nds) => (nds.every((n) => !!n.selected === (n.id === id)) ? nds : nds.map((n) => (!!n.selected === (n.id === id) ? n : { ...n, selected: n.id === id }))))
      setEdgeSel((prev) => (prev.size === 0 ? prev : new Set()))
      if (id && show) requestAnimationFrame(() => reveal(id))
    },
    [reveal],
  )

  const selectAll = useCallback(() => {
    setNodes((nds) => nds.map((n) => (n.selected || n.selectable === false ? n : { ...n, selected: true })))
  }, [])

  // the node detail view's node is the selected one
  useEffect(() => {
    if (ndv) selectOnly(ndv)
  }, [ndv, selectOnly])

  // ------------------------------------------------------------------ viewport

  const syncBackground = useCallback((vp: Viewport) => {
    const el = wrapRef.current
    if (!el) return
    const size = GRID * vp.zoom
    el.style.backgroundSize = `${size}px ${size}px`
    el.style.backgroundPosition = `${vp.x}px ${vp.y}px`
  }, [])

  const fit = useCallback(() => void flowApi.fitView({ ...FIT, duration: 250 }), [flowApi])
  const zoomIn = useCallback(() => void flowApi.zoomIn({ duration: 200 }), [flowApi])
  const zoomOut = useCallback(() => void flowApi.zoomOut({ duration: 200 }), [flowApi])
  const resetZoom = useCallback(() => void flowApi.zoomTo(1, { duration: 200 }), [flowApi])
  const tidy = useCallback(() => {
    store.tidyUp()
    setTimeout(() => void flowApi.fitView({ ...FIT, duration: 300 }), 60)
  }, [store, flowApi])

  // ------------------------------------------------------------------ actions

  const pointFromSelection = useCallback((): InsertPoint | null => {
    const sel = nodesRef.current.filter((n) => n.selected && (n.type === 'step' || n.type === 'trigger'))
    if (sel.length !== 1) return null
    const gn = store.getState().graph.nodes.find((n) => n.id === sel[0].id)
    if (!gn) return null
    const only = sel[0]
    const handle = only.type === 'step' ? (only.data.free[0] ?? only.data.outputs[0]?.id ?? 'main') : 'main'
    return insertPointOfHandle(gn, handle)
  }, [store])

  const notify = useCallback((message: string | null) => {
    if (message) toast.toast({ title: 'Connection', message })
  }, [toast])

  const deleteNodes = useCallback(
    (ids: string[], withEdges: string[] = []) => {
      const stickyIds = new Set(ids.filter((id) => id.startsWith(STICKY_PREFIX)).map((id) => id.slice(STICKY_PREFIX.length)))
      const graphIds = ids.filter((id) => !id.startsWith(STICKY_PREFIX) && id !== PLACEHOLDER_ID)
      const run = () => {
        if (stickyIds.size > 0) store.setStickies(store.getState().stickies.filter((s) => !stickyIds.has(s.id)))
        if (graphIds.length > 0) store.deleteNodes(graphIds)
        else for (const e of withEdges) notify(store.deleteEdge(e))
        setEdgeSel(new Set())
      }
      const steps = graphIds.filter((id) => store.stepOf(id) !== null).length
      if (steps > 3) setConfirm({ count: steps, run })
      else run()
    },
    [store, notify],
  )

  const copy = useCallback(
    (ids: string[], cut = false) => {
      const stepNodes = ids.filter((id) => store.stepOf(id) !== null)
      if (stepNodes.length === 0) return
      const entries = store.copyNodes(stepNodes)
      clipboard = entries
      void navigator.clipboard?.writeText(JSON.stringify(entries, null, 2)).catch(() => undefined)
      toast.toast({ title: cut ? 'Cut' : 'Copied', message: `${entries.length} ${entries.length === 1 ? 'step' : 'steps'}`, duration: 1800 })
      if (cut) store.deleteNodes(stepNodes)
    },
    [store, toast],
  )

  const paste = useCallback(
    (entries: StepDocument[]) => {
      const ids = store.paste(entries, pointFromSelection())
      if (ids.length > 0) pendingSelect.current = new Set(ids.map(stepNodeId))
    },
    [store, pointFromSelection],
  )

  const duplicate = useCallback(
    (ids: string[]) => {
      const out = store.duplicateNodes(ids)
      if (out.length > 0) pendingSelect.current = new Set(out.map(stepNodeId))
    },
    [store],
  )

  const addSticky = useCallback(
    (at?: Position) => {
      let p = at
      if (!p) {
        const rect = wrapRef.current?.getBoundingClientRect()
        p = rect ? flowApi.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }) : { x: 0, y: 0 }
        p = { x: p.x - 120, y: p.y - 80 }
      }
      const s = newSticky(Math.round(p.x), Math.round(p.y))
      store.setStickies([...store.getState().stickies, s])
      pendingSelect.current = new Set([stickyNodeId(s.id)])
    },
    [store, flowApi],
  )

  const testStep = useCallback(
    (stepId: string) => {
      const st = store.getState()
      void store.testRun(st.lastInput ?? sampleInput(st.doc), stepId)
    },
    [store],
  )

  const togglePin = useCallback(
    (nodeId: string) => {
      const stepId = store.stepOf(nodeId)
      if (!stepId) return
      const pinned = store.getState().pins[stepId] !== undefined
      const output = store.knownOutputs()[stepId]
      if (!pinned && output === undefined) {
        toast.toast({ title: 'Nothing to pin yet', message: 'Test the workflow first, then pin the step’s output' })
        return
      }
      store.togglePin(stepId, output)
    },
    [store, toast],
  )

  const openMenuAt = useCallback(
    (target: CanvasMenuTarget, x: number, y: number) => {
      if (!readOnly) store.openMenu({ x, y, target })
    },
    [store, readOnly],
  )

  // ------------------------------------------------------------------ gestures

  const onConnect = useCallback((c: Connection) => notify(store.connect(c.source, c.sourceHandle ?? 'main', c.target)), [store, notify])

  const isValidConnection = useCallback<IsValidConnection<FlowEdge>>((c) => {
    if (c.source === c.target) return false
    const t = nodesRef.current.find((n) => n.id === c.target)
    return !!t && (t.type === 'step' || t.type === 'end')
  }, [])

  const onConnectEnd = useCallback(
    (_: MouseEvent | TouchEvent, state: FinalConnectionState) => {
      if (readOnly || state.isValid || !state.fromNode || !state.fromHandle || state.toNode) return
      const gn = store.getState().graph.nodes.find((n) => n.id === state.fromNode?.id)
      if (!gn) return
      if (state.fromHandle.type === 'source') {
        const point = insertPointOfHandle(gn, state.fromHandle.id ?? 'main')
        if (point) store.openCreator(point)
      } else if (gn.data.kind === 'step') store.openCreator({ kind: 'before', step: gn.data.stepId })
    },
    [store, readOnly],
  )

  const onNodeDragStop = useCallback(
    (_: unknown, __: FlowNode, dragged: FlowNode[]) => {
      const moves = new Map<string, Position>()
      const stickyMoves = new Map<string, Position>()
      for (const n of dragged) {
        const p = { x: Math.round(n.position.x), y: Math.round(n.position.y) }
        const before = base.find((b) => b.id === n.id)
        if (before && before.position.x === p.x && before.position.y === p.y) continue
        if (n.type === 'sticky') stickyMoves.set(n.data.sticky.id, p)
        else if (n.type === 'step' || n.type === 'trigger' || n.type === 'undo') moves.set(n.id, p)
      }
      if (stickyMoves.size > 0) store.setStickies(store.getState().stickies.map((s) => ({ ...s, ...(stickyMoves.get(s.id) ?? {}) })))
      if (moves.size > 0) store.moveNodes(moves)
    },
    [store, base],
  )

  // ------------------------------------------------------------------ keyboard and clipboard

  const navigateSelection = useCallback(
    (key: string) => {
      const all = nodesRef.current
      const sel = all.filter((n) => n.selected && n.type !== 'sticky')
      const pickable = (id: string) => all.some((n) => n.id === id && n.selectable !== false && n.type !== 'placeholder')
      if (sel.length !== 1) {
        const first = all.find((n) => n.id === API_TRIGGER_ID) ?? all.find((n) => n.type === 'step')
        if (first) selectOnly(first.id, true)
        return
      }
      const cur = sel[0]
      const pos = (id: string) => all.find((n) => n.id === id)?.position ?? { x: 0, y: 0 }
      const es = edgesRef.current
      let candidates: string[]
      if (key === 'ArrowRight') candidates = es.filter((e) => e.source === cur.id).map((e) => e.target)
      else if (key === 'ArrowLeft') candidates = es.filter((e) => e.target === cur.id).map((e) => e.source)
      else {
        const parents = new Set(es.filter((e) => e.target === cur.id).map((e) => e.source))
        const siblings = [...new Set(es.filter((e) => parents.has(e.source)).map((e) => e.target))]
        const pool = siblings.length > 1 ? siblings : all.filter((n) => Math.abs(n.position.x - cur.position.x) < 60).map((n) => n.id)
        candidates = key === 'ArrowDown' ? pool.filter((id) => pos(id).y > cur.position.y) : pool.filter((id) => pos(id).y < cur.position.y)
      }
      const next = candidates.filter(pickable).sort((a, b) => Math.abs(pos(a).y - cur.position.y) - Math.abs(pos(b).y - cur.position.y))[0]
      if (next) selectOnly(next, true)
    },
    [selectOnly],
  )

  const onCanvas = useCallback((target: EventTarget | null) => {
    const el = target as HTMLElement | null
    if (!el || el === document.body || el === document.documentElement) return true
    const wrap = wrapRef.current
    if (!wrap || !wrap.contains(el)) return false
    return !el.closest('button, a, input, textarea, select, [role="menu"]')
  }, [])

  /** n8n's canvas keys; plain keys only while the canvas (or nothing) has focus. */
  const handleKey = (e: KeyboardEvent): void => {
    if (e.defaultPrevented || isEditableTarget(e.target) || modalOpen()) return
    const wrap = wrapRef.current
    if (!wrap || !wrap.isConnected || wrap.offsetParent === null) return
    const mod = e.metaKey || e.ctrlKey
    const k = e.key
    const plain = onCanvas(e.target)
    const act = (fn: () => void) => {
      e.preventDefault()
      fn()
    }
    const single = (): string | null => {
      const sel = selectedIds()
      return sel.length === 1 ? sel[0] : null
    }

    if (!mod && !e.altKey && plain) {
      if (k === '1') return act(fit)
      if (k === '0') return act(resetZoom)
      if (k === '+' || k === '=') return act(zoomIn)
      if (k === '-' || k === '_') return act(zoomOut)
      if (k === 'ArrowRight' || k === 'ArrowLeft' || k === 'ArrowUp' || k === 'ArrowDown') return act(() => navigateSelection(k))
      if (k === 'Enter') {
        const focused = (e.target as HTMLElement | null)?.closest?.('.react-flow__node')?.getAttribute('data-id') ?? null
        const id = focused ?? single()
        if (id && !id.startsWith(STICKY_PREFIX) && id !== PLACEHOLDER_ID) act(() => onOpen(id))
        return
      }
      if (k === 'Escape') {
        const st = store.getState()
        if (st.menu) store.closeMenu()
        else if (st.creator) store.closeCreator()
        else selectOnly(null)
        return
      }
    }
    if (readOnly) return

    if (mod && !e.altKey) {
      const lower = k.toLowerCase()
      if (lower === 'z') return act(() => (e.shiftKey ? store.redo() : store.undo()))
      if (lower === 'y') return act(() => store.redo())
      if (!plain) return
      if (lower === 'a') return act(selectAll)
      const steps = selectedIds().filter((id) => store.stepOf(id) !== null)
      if (lower === 'c' && steps.length > 0) return act(() => copy(steps))
      if (lower === 'x' && steps.length > 0) return act(() => copy(steps, true))
      if (lower === 'd' && steps.length > 0) return act(() => duplicate(steps))
      return
    }
    if (!plain) return
    if (e.shiftKey && e.altKey && e.code === 'KeyT') return act(tidy)
    if (e.altKey) return
    if (k === 'Tab' && !e.shiftKey) return act(() => store.openCreator(pointFromSelection()))
    if (k === 'Delete' || k === 'Backspace') {
      const ids = selectedIds()
      const edgeIds = [...edgeSelRef.current]
      if (ids.length > 0 || edgeIds.length > 0) act(() => deleteNodes(ids, edgeIds))
      return
    }
    if (k === 'F2') {
      const id = single()
      const stepId = id ? store.stepOf(id) : null
      if (stepId) act(() => store.startRename(stepId))
      return
    }
    if ((k === 'p' || k === 'P') && !e.shiftKey) {
      const id = single()
      if (id) act(() => togglePin(id))
      return
    }
    if (k === 'S' && e.shiftKey) return act(() => addSticky())
    if (k === 'ContextMenu' || (k === 'F10' && e.shiftKey)) {
      const id = single()
      const el = id ? wrap.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`) : null
      const r = (el ?? wrap).getBoundingClientRect()
      const x = r.left + r.width / 2
      const y = r.top + r.height / 2
      const p = flowApi.screenToFlowPosition({ x, y })
      act(() => openMenuAt(id ? { kind: 'node', id } : { kind: 'pane', flowX: p.x, flowY: p.y }, x, y))
    }
  }

  const keyHandler = useRef(handleKey)
  useLayoutEffect(() => {
    keyHandler.current = handleKey
  })
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => keyHandler.current(e)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    if (readOnly) return
    const onPaste = (e: ClipboardEvent) => {
      if (isEditableTarget(e.target) || modalOpen() || !onCanvas(e.target)) return
      const wrap = wrapRef.current
      if (!wrap || wrap.offsetParent === null) return
      const text = e.clipboardData?.getData('text/plain') ?? ''
      const entries = parseClipboard(text) ?? (text.trim() === '' ? clipboard : null)
      if (!entries) return
      e.preventDefault()
      paste(entries)
    }
    document.addEventListener('paste', onPaste)
    return () => document.removeEventListener('paste', onPaste)
  }, [readOnly, paste, onCanvas])

  // ------------------------------------------------------------------ the menu

  const entriesFor = (target: CanvasMenuTarget): MenuEntry[] => {
    const m = modKeyLabel()
    const common: MenuEntry[] = [
      { id: 'select-all', label: 'Select all', icon: 'grid', shortcut: `${m}A`, run: selectAll },
      { id: 'sticky', label: 'Add sticky note', glyph: 'sticky', shortcut: '⇧S', run: () => addSticky(target.kind === 'pane' ? { x: target.flowX, y: target.flowY } : undefined) },
      { id: 'tidy', label: 'Tidy up workflow', glyph: 'tidy', shortcut: '⇧⌥T', run: tidy },
    ]
    if (target.kind === 'pane' || target.kind === 'edge') {
      return [
        { id: 'add', label: 'Add step', icon: 'plus', shortcut: 'Tab', run: () => store.openCreator(null) },
        { id: 'paste', label: 'Paste', icon: 'copy', shortcut: `${m}V`, disabled: !clipboard, run: () => clipboard && paste(clipboard) },
        'separator',
        ...common,
        { id: 'fit', label: 'Zoom to fit', glyph: 'fit', shortcut: '1', run: fit },
      ]
    }
    const ids = target.kind === 'node' ? [target.id] : target.ids
    const single = ids.length === 1 ? ids[0] : null
    const stepId = single ? store.stepOf(single) : null
    const steps = ids.filter((id) => store.stepOf(id) !== null)
    const node = single ? nodesRef.current.find((n) => n.id === single) : undefined
    const deletable = ids.some((id) => {
      const n = nodesRef.current.find((x) => x.id === id)
      return !!n && (n.type === 'step' || n.type === 'sticky' || n.type === 'undo' || (n.type === 'trigger' && n.data.triggerType !== 'api'))
    })
    const out: MenuEntry[] = []
    if (single && node && node.type !== 'sticky') out.push({ id: 'open', label: 'Open', icon: 'arrow-right', shortcut: '↵', run: () => onOpen(single) })
    if (stepId && single) {
      const pinned = store.getState().pins[stepId] !== undefined
      out.push({ id: 'test', label: 'Test step', icon: 'play', run: () => testStep(stepId) })
      out.push({ id: 'rename', label: 'Rename', icon: 'edit', shortcut: 'F2', run: () => store.startRename(stepId) })
      out.push({ id: 'pin', label: pinned ? 'Unpin output' : 'Pin output', glyph: 'pin', shortcut: 'P', run: () => togglePin(single) })
    }
    if (steps.length > 0) {
      out.push({ id: 'copy', label: 'Copy', icon: 'copy', shortcut: `${m}C`, run: () => copy(steps) })
      out.push({ id: 'duplicate', label: 'Duplicate', icon: 'layers', shortcut: `${m}D`, run: () => duplicate(steps) })
    }
    if (out.length > 0) out.push('separator')
    out.push(...common, 'separator', { id: 'delete', label: 'Delete', icon: 'trash', shortcut: 'Del', danger: true, disabled: !deletable, run: () => deleteNodes(ids) })
    return out
  }

  const closeMenu = useCallback(() => store.closeMenu(), [store])

  return (
    <section
      ref={wrapRef}
      aria-label={label}
      data-testid="designer-canvas"
      className={cx('iaf-canvas bg-dot-grid relative h-full w-full overflow-hidden', readOnly && 'iaf-canvas--readonly')}
      onFocus={(e) => {
        const el = e.target as HTMLElement
        if (!el.classList.contains('react-flow__node') || !el.matches(':focus-visible')) return
        const id = el.getAttribute('data-id')
        if (id && !nodesRef.current.some((n) => n.id === id && n.selected)) selectOnly(id)
      }}
    >
      <ReactFlow<FlowNode, FlowEdge>
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onConnectEnd={onConnectEnd}
        isValidConnection={isValidConnection}
        onNodeDragStop={onNodeDragStop}
        onNodeDoubleClick={(_, node) => {
          if (node.type === 'step' || node.type === 'trigger' || node.type === 'undo') onOpen(node.id)
        }}
        onNodeContextMenu={(e, node) => {
          e.preventDefault()
          if (node.type === 'end' || node.type === 'placeholder') return
          const sel = selectedIds()
          if (sel.length > 1 && sel.includes(node.id)) openMenuAt({ kind: 'selection', ids: sel }, e.clientX, e.clientY)
          else {
            selectOnly(node.id)
            openMenuAt({ kind: 'node', id: node.id }, e.clientX, e.clientY)
          }
        }}
        onSelectionContextMenu={(e, sel) => {
          e.preventDefault()
          openMenuAt({ kind: 'selection', ids: sel.map((n) => n.id) }, e.clientX, e.clientY)
        }}
        onPaneContextMenu={(e) => {
          e.preventDefault()
          const p = flowApi.screenToFlowPosition({ x: e.clientX, y: e.clientY })
          openMenuAt({ kind: 'pane', flowX: p.x, flowY: p.y }, e.clientX, e.clientY)
        }}
        onPaneClick={() => store.closeMenu()}
        onMove={(_, vp) => syncBackground(vp)}
        onInit={(inst) => syncBackground(inst.getViewport())}
        fitView
        fitViewOptions={FIT}
        minZoom={0.15}
        maxZoom={4}
        snapToGrid
        snapGrid={[10, 10]}
        nodeDragThreshold={2}
        selectionOnDrag
        selectionMode={SelectionMode.Partial}
        panOnDrag={[1]}
        panOnScroll
        zoomOnScroll={false}
        zoomOnDoubleClick={false}
        deleteKeyCode={null}
        multiSelectionKeyCode={['Meta', 'Control', 'Shift']}
        disableKeyboardA11y
        edgesFocusable={false}
        nodesConnectable={!readOnly}
        nodesDraggable={!readOnly}
        elementsSelectable
        connectionRadius={28}
        connectionLineStyle={{ stroke: 'var(--color-neutral)', strokeWidth: 2 }}
        attributionPosition="top-right"
        className={cx(readOnly && 'iaf-readonly')}
      >
        <Panel position="bottom-left">
          <div role="toolbar" aria-label="Canvas controls" className="flex items-center gap-1.5">
            <ControlButton label="Zoom to fit (1)" onClick={fit}>
              <UiGlyph name="fit" size={16} />
            </ControlButton>
            <ControlButton label="Zoom in (+)" onClick={zoomIn}>
              <Icon name="plus" size={16} />
            </ControlButton>
            <ControlButton label="Zoom out (−)" onClick={zoomOut}>
              <Icon name="minus" size={16} />
            </ControlButton>
            <ControlButton label="Reset zoom (0)" onClick={resetZoom}>
              <UiGlyph name="zoom-reset" size={16} />
            </ControlButton>
            {!readOnly && (
              <ControlButton label="Tidy up (⇧⌥T)" onClick={tidy}>
                <UiGlyph name="tidy" size={16} />
              </ControlButton>
            )}
          </div>
        </Panel>
        <MiniMap<FlowNode> position="bottom-right" pannable zoomable ariaLabel="Mini map" nodeClassName={MINI_CLASS} nodeBorderRadius={8} style={{ width: 180, height: 112 }} />
      </ReactFlow>
      {children}
      {menu && !readOnly && <ContextMenu x={menu.x} y={menu.y} entries={entriesFor(menu.target)} onClose={closeMenu} />}
      {renaming && !readOnly && <RenameDialog stepId={renaming} onClose={() => store.stopRename()} />}
      {confirm && (
        <ConfirmDialog
          title={`Delete ${confirm.count} steps?`}
          message="The steps, and what they contain, leave the workflow. Undo brings them back."
          confirmLabel="Delete"
          tone="danger"
          onConfirm={confirm.run}
          onClose={() => setConfirm(null)}
        />
      )}
    </section>
  )
}

/** The canvas with its own xyflow provider. */
export function Canvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}
