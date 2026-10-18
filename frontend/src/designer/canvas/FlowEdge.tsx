/**
 * n8n's connection: a hairline bezier with an arrowhead, routed under the source when it runs
 * backwards (loops, jumps), a hover toolbar in the middle with "+" (insert a step here) and the
 * trash, and the item count after a run. Colours come from state classes in designer.css.
 */
import { memo, useEffect, useRef, useState } from 'react'
import { EdgeLabelRenderer, getBezierPath, getSmoothStepPath, Position, type EdgeProps } from '@xyflow/react'
import { cx, Icon, useToast } from '@iaf/ui'
import { useDesignerStore } from '../model/store'
import type { FlowEdge } from './flow'

const PADDING_BOTTOM = 130
const PADDING_X = 40
const RADIUS = 16
const HANDLE = 20
const ARROW = 8

interface PathInput {
  sourceX: number
  sourceY: number
  targetX: number
  targetY: number
  sourcePosition: Position
  targetPosition: Position
}

/** The path, as n8n draws it: a bezier forwards, two smooth-step segments under the source backwards. */
export function edgePath(p: PathInput): { path: string; labelX: number; labelY: number } {
  const backwards = p.sourcePosition === Position.Right && p.sourceX - HANDLE > p.targetX
  if (!backwards) {
    const [path, labelX, labelY] = getBezierPath(p)
    return { path, labelX, labelY }
  }
  const midX = (p.sourceX + p.targetX) / 2
  const midY = Math.max(p.sourceY, p.targetY) + PADDING_BOTTOM
  const [first] = getSmoothStepPath({ sourceX: p.sourceX, sourceY: p.sourceY, sourcePosition: p.sourcePosition, targetX: midX, targetY: midY, targetPosition: Position.Right, borderRadius: RADIUS, offset: PADDING_X })
  const [second] = getSmoothStepPath({ sourceX: midX, sourceY: midY, sourcePosition: Position.Left, targetX: p.targetX, targetY: p.targetY, targetPosition: p.targetPosition, borderRadius: RADIUS, offset: PADDING_X })
  return { path: `${first} ${second}`, labelX: midX, labelY: midY }
}

function arrowPath(x: number, y: number, side: Position): string {
  const h = ARROW / 2 + 0.5
  switch (side) {
    case Position.Top:
      return `M ${x} ${y} L ${x - h} ${y - ARROW} L ${x + h} ${y - ARROW} Z`
    case Position.Bottom:
      return `M ${x} ${y} L ${x - h} ${y + ARROW} L ${x + h} ${y + ARROW} Z`
    case Position.Right:
      return `M ${x} ${y} L ${x + ARROW} ${y - h} L ${x + ARROW} ${y + h} Z`
    default:
      return `M ${x} ${y} L ${x - ARROW} ${y - h} L ${x - ARROW} ${y + h} Z`
  }
}

function EdgeButton({ label, icon, onClick, danger }: { label: string; icon: 'plus' | 'trash'; onClick: () => void; danger?: boolean }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={(e) => {
        e.stopPropagation()
        onClick()
      }}
      className={cx(
        'flex h-7 w-7 items-center justify-center rounded-sm border border-stroke bg-surface text-secondary shadow-sm transition-colors',
        danger ? 'hover:border-critical hover:text-critical' : 'hover:border-accent hover:text-accent',
      )}
    >
      <Icon name={icon} size={13} strokeWidth={2.2} />
    </button>
  )
}

export const FlowEdgeView = memo(function FlowEdgeView({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data, selected }: EdgeProps<FlowEdge>) {
  const store = useDesignerStore()
  const toast = useToast()
  const [hover, setHover] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )
  const enter = () => {
    if (timer.current) clearTimeout(timer.current)
    setHover(true)
  }
  const leave = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => setHover(false), 140)
  }
  const { path, labelX, labelY } = edgePath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  const role = data?.role ?? 'main'
  const editable = !!data && !data.readOnly && !data.placeholder
  const insert = data?.insert ?? null
  const state = data?.state ?? null
  const items = data?.items ?? null
  const cls = cx(
    'iaf-edge',
    hover && editable && 'is-hover',
    selected && 'is-selected',
    data?.highlighted && 'is-highlighted',
    state === 'success' && 'is-success',
    state === 'running' && 'is-running',
    data?.placeholder && 'is-muted',
    role === 'undo' && 'is-undo',
  )
  const remove = () => {
    setHover(false)
    const message = store.deleteEdge(id)
    if (message) toast.toast({ title: 'Connection', message, tone: 'neutral' })
  }
  return (
    <>
      <g className={cls} onMouseEnter={enter} onMouseLeave={leave} data-edge={id}>
        <path d={path} className="iaf-edge-path" />
        <path d={arrowPath(targetX, targetY, targetPosition)} className="iaf-edge-arrow" />
        <path d={path} className="react-flow__edge-interaction" fill="none" stroke="currentColor" strokeOpacity={0} strokeWidth={22} />
      </g>
      {(editable && hover) || items !== null ? (
        <EdgeLabelRenderer>
          <div
            className="nodrag nopan absolute"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`, pointerEvents: 'all' }}
            onMouseEnter={editable ? enter : undefined}
            onMouseLeave={editable ? leave : undefined}
          >
            {editable && hover ? (
              <div className="flex items-center gap-1 animate-scale-in">
                {insert && <EdgeButton label="Add a step here" icon="plus" onClick={() => store.openCreator(insert)} />}
                <EdgeButton label="Delete connection" icon="trash" danger onClick={remove} />
              </div>
            ) : (
              items !== null && (
                <span className={cx('-translate-y-3 block whitespace-nowrap rounded-full bg-surface px-1.5 text-caption2 font-medium', state === 'running' ? 'text-accent' : 'text-healthy')}>
                  {items} {items === 1 ? 'item' : 'items'}
                </span>
              )
            )}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  )
})

export const EDGE_TYPES = { flow: FlowEdgeView }
