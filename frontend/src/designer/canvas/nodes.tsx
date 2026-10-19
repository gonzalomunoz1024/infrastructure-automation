/**
 * The canvas nodes, drawn by hand in n8n's shapes: a 100 px square body with the glyph in the
 * middle and the name underneath, a rounded-left trigger, the "+" stub on free outputs, the
 * hover toolbar, issue and run badges, the health dot of a provider step's provider, the End and
 * compensation nodes, the "Add first step" placeholder and the sticky notes.
 */
import { memo, useState, type MouseEvent, type ReactNode } from 'react'
import { Handle, NodeResizer, NodeToolbar, Position, type NodeProps } from '@xyflow/react'
import { cx, Icon, Spinner, StatusDot, type IconName } from '@iaf/ui'
import type { JsonValue } from '@iaf/workflow-checks'
import type { HealthStatus } from '../../lib/types'
import { healthDot } from '../../components/HealthBand'
import { findStepNode, NODE_SIZE } from '../model/graph'
import { useDesignerSelector, useDesignerStore } from '../model/store'
import type { Sticky } from '../model/stickies'
import { sampleInput } from '../model/expressions'
import { NodeGlyph, UiGlyph } from './glyphs'
import { useProviderHealth } from './providerHealth'
import type { EndFlowNode, Issues, PlaceholderFlowNode, RunState, StepFlowNode, StickyFlowNode, TriggerFlowNode, UndoFlowNode } from './flow'

const OUT_PITCH = 26

/** Taller bodies for many outputs, as n8n grows a Switch with many routes. */
export function stepNodeHeight(outputs: number): number {
  return outputs > 3 ? Math.max(NODE_SIZE, outputs * OUT_PITCH + 22) : NODE_SIZE
}

function handleTop(i: number, count: number): string {
  return `${((i + 1) / (count + 1)) * 100}%`
}

function IssueBadge({ issues }: { issues: Issues }) {
  const count = issues.errors + issues.warnings
  return (
    <span
      title={issues.messages.join('\n')}
      aria-label={`${count} ${count === 1 ? 'issue' : 'issues'}`}
      className={cx('flex items-center gap-0.5 rounded-full px-1 text-caption2 font-semibold', issues.errors > 0 ? 'text-critical' : 'bg-warning-tint text-warning')}
    >
      <Icon name="alert-triangle" size={13} strokeWidth={2} />
      {count}
    </span>
  )
}

function RunBadge({ run }: { run: RunState | null }) {
  switch (run) {
    case 'success':
      return <Icon name="check" size={16} strokeWidth={2.6} className="text-healthy" title="Succeeded" />
    case 'failure':
      return <Icon name="close-circle" size={15} strokeWidth={2} className="text-critical" title="Failed" />
    case 'running':
      return <Spinner size={12} className="text-accent" />
    case 'waiting':
      return <Icon name="clock" size={14} strokeWidth={2} className="text-warning" title="Waiting" />
    default:
      return null
  }
}

const HEALTH_LABEL: Record<HealthStatus, string> = { healthy: 'healthy', degraded: 'degraded', failing: 'failing', unknown: 'unknown' }

/** The provider a step calls, '' for a step that calls none. */
function stepProvider(step: { type: string; with?: unknown }): string {
  if (step.type !== 'provider' || !step.with || typeof step.with !== 'object') return ''
  const p = (step.with as Record<string, JsonValue>).provider
  return typeof p === 'string' ? p : ''
}

/** The health band of a provider step's provider, as the server judges it; nothing until it is known, or while it has no calls to judge. */
export function HealthDot({ provider }: { provider: string }) {
  const status = useProviderHealth(provider)
  if (!status || status === 'unknown') return null
  const label = `${provider} is ${HEALTH_LABEL[status]}`
  return (
    <span className="absolute right-2 top-2 flex" title={label} aria-label={label} role="img" data-health={status}>
      <StatusDot kind={healthDot(status)} pulse={status === 'failing'} />
    </span>
  )
}

function bodyTone(run: RunState | null, selected: boolean): string {
  if (selected) return 'border-accent shadow-[0_0_0_4px_var(--halo-accent)]'
  switch (run) {
    case 'success':
      return 'border-healthy'
    case 'failure':
      return 'border-critical'
    case 'running':
      return 'border-accent animate-pulse-subtle'
    case 'waiting':
      return 'border-warning'
    case 'skipped':
      return 'border-stroke opacity-60'
    default:
      return 'border-stroke group-hover:border-tertiary'
  }
}

function ToolbarButton({ label, icon, onClick }: { label: string; icon: IconName; onClick: (e: MouseEvent<HTMLButtonElement>) => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={(e) => {
        e.stopPropagation()
        onClick(e)
      }}
      onDoubleClick={(e) => e.stopPropagation()}
      className="flex h-6 w-6 items-center justify-center rounded-sm text-tertiary transition-colors hover:bg-surface-secondary hover:text-primary"
    >
      <Icon name={icon} size={13} strokeWidth={2} />
    </button>
  )
}

function HoverToolbar({ children }: { children: ReactNode }) {
  return (
    <div className="nodrag nopan absolute bottom-full left-1/2 -translate-x-1/2 pb-2 opacity-0 transition-opacity duration-150 group-hover:opacity-100 focus-within:opacity-100">
      <div className="flex items-center gap-0.5 rounded-md border border-stroke-light bg-surface p-0.5 shadow-sm">{children}</div>
    </div>
  )
}

function PlusStub({ top, label, onClick }: { top: string; label: string; onClick: () => void }) {
  return (
    <div className="nodrag nopan absolute left-full flex -translate-y-1/2 items-center pl-1" style={{ top }}>
      <span className="h-[2px] w-9 bg-stroke" aria-hidden="true" />
      <button
        type="button"
        aria-label={label}
        title={label}
        onClick={(e) => {
          e.stopPropagation()
          onClick()
        }}
        onDoubleClick={(e) => e.stopPropagation()}
        className="flex h-6 w-6 items-center justify-center rounded-md border-2 border-stroke bg-surface text-tertiary transition-colors hover:border-accent hover:text-accent"
      >
        <Icon name="plus" size={12} strokeWidth={2.4} />
      </button>
    </div>
  )
}

function NameBlock({ name, subtitle }: { name: string; subtitle: string }) {
  return (
    <div className="pointer-events-none absolute left-1/2 top-full mt-2 w-[168px] -translate-x-1/2 text-center">
      <p className="line-clamp-2 text-footnote font-medium leading-tight text-primary">{name}</p>
      {subtitle && <p className="mt-0.5 truncate font-mono text-caption2 text-tertiary">{subtitle}</p>}
    </div>
  )
}

export const StepNode = memo(function StepNode({ id, data, selected, isConnectable }: NodeProps<StepFlowNode>) {
  const store = useDesignerStore()
  const provider = useDesignerSelector((s) => {
    const node = findStepNode(s.graph, data.stepId)
    return node ? stepProvider(node.data.step) : ''
  })
  const k = data.outputs.length
  const height = stepNodeHeight(k)
  const testStep = () => {
    const st = store.getState()
    void store.testRun(st.lastInput ?? sampleInput(st.doc), data.stepId)
  }
  return (
    <div className="group relative" style={{ width: NODE_SIZE, height }} data-step={data.stepId} data-run={data.run ?? undefined}>
      {!data.readOnly && (
        <HoverToolbar>
          <ToolbarButton label="Test step" icon="play" onClick={testStep} />
          <ToolbarButton label="Delete" icon="trash" onClick={() => store.deleteNodes([id])} />
          <ToolbarButton label="More" icon="more-horizontal" onClick={(e) => store.openMenu({ x: e.clientX, y: e.clientY, target: { kind: 'node', id } })} />
        </HoverToolbar>
      )}
      <div className={cx('iaf-node-body relative flex h-full w-full items-center justify-center rounded-lg border-2 bg-surface shadow-sm transition-[border-color,box-shadow] duration-150', bodyTone(data.run, selected))}>
        <NodeGlyph glyph={data.glyph} size={40} />
        {data.container && <span className="absolute left-2 top-1.5 text-caption2 font-medium uppercase tracking-wide text-tertiary">{data.typeTitle.split(' ')[0]}</span>}
        {provider && !data.readOnly && <HealthDot provider={provider} />}
        <div className="absolute bottom-1.5 right-2 flex items-center gap-1">
          {data.pinned && (
            <span title="Output pinned">
              <UiGlyph name="pin" size={14} strokeWidth={2} className="text-accent" />
            </span>
          )}
          {data.issues && <IssueBadge issues={data.issues} />}
          <RunBadge run={data.run} />
        </div>
      </div>
      <Handle type="target" position={Position.Left} id="in" className="iaf-handle iaf-handle--in" isConnectable={isConnectable} />
      {data.outputs.map((o, i) => (
        <Handle key={o.id} type="source" position={Position.Right} id={o.id} style={{ top: handleTop(i, k) }} className="iaf-handle iaf-handle--out" isConnectable={isConnectable} />
      ))}
      {data.hasUndo && <Handle type="source" position={Position.Bottom} id="undo" className="iaf-handle iaf-handle--undo" isConnectable={false} />}
      {k > 1 &&
        data.outputs.map((o, i) => (
          <span key={`label-${o.id}`} className="pointer-events-none absolute left-full ml-2.5 max-w-[132px] -translate-y-full truncate pb-0.5 text-caption2 text-tertiary" style={{ top: handleTop(i, k) }}>
            {o.label}
          </span>
        ))}
      {!data.readOnly &&
        data.free.map((h) => {
          const i = data.outputs.findIndex((o) => o.id === h)
          if (i < 0) return null
          const out = data.outputs[i]
          return <PlusStub key={`plus-${h}`} top={handleTop(i, k)} label={k > 1 ? `Add a step on ${out.label}` : `Add a step after ${data.name}`} onClick={() => store.openCreator({ kind: 'after', step: data.stepId, handle: h })} />
        })}
      <NameBlock name={data.name} subtitle={data.subtitle} />
    </div>
  )
})

export const TriggerNode = memo(function TriggerNode({ id, data, selected, isConnectable }: NodeProps<TriggerFlowNode>) {
  const store = useDesignerStore()
  const removable = data.triggerType !== 'api'
  return (
    <div className="group relative" style={{ width: NODE_SIZE, height: NODE_SIZE }} data-trigger={data.triggerType} data-run={data.run ?? undefined}>
      {!data.readOnly && removable && (
        <HoverToolbar>
          <ToolbarButton label="Delete" icon="trash" onClick={() => store.deleteNodes([id])} />
          <ToolbarButton label="More" icon="more-horizontal" onClick={(e) => store.openMenu({ x: e.clientX, y: e.clientY, target: { kind: 'node', id } })} />
        </HoverToolbar>
      )}
      <span className={cx('pointer-events-none absolute -left-6 top-1/2 -translate-y-1/2', data.triggerType === 'error' ? 'text-critical' : 'text-warning')} aria-hidden="true">
        <Icon name="bolt" size={15} strokeWidth={2} />
      </span>
      <div className={cx('iaf-node-body relative flex h-full w-full items-center justify-center rounded-l-[36px] rounded-r-lg border-2 bg-surface shadow-sm transition-[border-color,box-shadow] duration-150', bodyTone(data.run, selected))}>
        <NodeGlyph glyph={data.glyph} size={38} />
        <div className="absolute bottom-1.5 right-2 flex items-center gap-1">
          {data.issues && <IssueBadge issues={data.issues} />}
          <RunBadge run={data.run} />
        </div>
      </div>
      <Handle type="source" position={Position.Right} id="main" className="iaf-handle iaf-handle--out" isConnectable={isConnectable} />
      {!data.readOnly && data.free && (
        <PlusStub top="50%" label={data.triggerType === 'error' ? 'Add a step to the error flow' : 'Add first step'} onClick={() => store.openCreator({ kind: 'start', flow: data.triggerType === 'error' ? 'catch' : 'main' })} />
      )}
      <NameBlock name={data.label} subtitle={data.subtitle} />
    </div>
  )
})

export const EndNode = memo(function EndNode({ isConnectable }: NodeProps<EndFlowNode>) {
  return (
    <div className="relative flex h-10 w-10 items-center justify-center rounded-full border-2 border-stroke bg-surface shadow-sm">
      <NodeGlyph glyph={{ kind: 'end', tone: 'neutral' }} size={16} />
      <Handle type="target" position={Position.Left} id="in" className="iaf-handle iaf-handle--in" isConnectable={isConnectable} />
      <span className="pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 text-caption2 text-tertiary">End</span>
    </div>
  )
})

export const UndoNode = memo(function UndoNode({ data, selected }: NodeProps<UndoFlowNode>) {
  const shown = data.names.slice(0, 2).join(', ')
  return (
    <div className="relative" style={{ width: NODE_SIZE }}>
      <Handle type="target" position={Position.Top} id="in" className="iaf-handle iaf-handle--hidden" isConnectable={false} />
      <div
        className={cx(
          'iaf-node-body flex h-16 flex-col items-center justify-center gap-0.5 rounded-xl border-2 border-dashed bg-warning-tint px-2 text-center',
          selected ? 'border-accent' : 'border-warning/50',
        )}
        title="Runs when a later step fails, to undo this step"
      >
        <NodeGlyph glyph={{ kind: 'undo', tone: 'warning' }} size={20} />
        <span className="text-caption2 font-medium text-warning">Compensate</span>
      </div>
      <p className="pointer-events-none absolute left-1/2 top-full mt-1.5 w-[168px] -translate-x-1/2 truncate text-center text-caption2 text-tertiary">
        {shown}
        {data.names.length > 2 ? ` +${data.names.length - 2}` : ''}
      </p>
    </div>
  )
})

export const PlaceholderNode = memo(function PlaceholderNode({ data }: NodeProps<PlaceholderFlowNode>) {
  const store = useDesignerStore()
  return (
    <div className="relative" style={{ width: NODE_SIZE, height: NODE_SIZE }}>
      <Handle type="target" position={Position.Left} id="in" className="iaf-handle iaf-handle--hidden" isConnectable={false} />
      {/*
        React Flow gives a node that is neither selectable nor draggable pointer-events: none, and
        this one is neither: without pointer-events-auto a real click fell through to the pane
        (selection on drag) and missed the button; nopan keeps a press on it from starting a selection.
      */}
      <button
        type="button"
        disabled={data.readOnly}
        onClick={() => store.openCreator({ kind: 'start', flow: 'main' })}
        aria-label="Add first step"
        className="nodrag nopan pointer-events-auto flex h-full w-full items-center justify-center rounded-lg border-2 border-dashed border-stroke bg-surface/60 text-tertiary transition-colors hover:border-accent hover:text-accent"
      >
        <Icon name="plus" size={30} strokeWidth={1.8} />
      </button>
      <p className="pointer-events-none absolute left-1/2 top-full mt-2 w-[160px] -translate-x-1/2 text-center text-footnote font-medium text-secondary">Add first step</p>
    </div>
  )
})

const STICKY_TONES = [
  'bg-warning-tint border-warning/30',
  'bg-accent-tint border-accent/30',
  'bg-healthy-tint border-healthy/30',
  'bg-critical-tint border-critical/30',
  'bg-surface border-stroke',
]
const STICKY_DOTS = ['bg-warning', 'bg-accent', 'bg-healthy', 'bg-critical', 'bg-stroke']

export const STICKY_TONE_COUNT = STICKY_TONES.length

function StickyText({ text }: { text: string }) {
  return (
    <div className="space-y-1">
      {text.split('\n').map((line, i) => {
        const plain = line.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/\*\*([^*]+)\*\*/g, '$1')
        if (/^#\s/.test(line)) return <p key={i} className="text-subheadline font-semibold text-primary">{plain.replace(/^#\s/, '')}</p>
        if (/^##+\s/.test(line)) return <p key={i} className="text-footnote font-semibold text-primary">{plain.replace(/^##+\s/, '')}</p>
        if (!line.trim()) return <div key={i} className="h-1" />
        return (
          <p key={i} className="text-footnote text-secondary">
            {plain}
          </p>
        )
      })}
    </div>
  )
}

export const StickyNode = memo(function StickyNode({ data, selected }: NodeProps<StickyFlowNode>) {
  const store = useDesignerStore()
  const s = data.sticky
  const [editing, setEditing] = useState(false)
  // the draft exists only while editing; it starts from the note's text when editing begins
  const [text, setText] = useState(s.text)
  const update = (patch: Partial<Sticky>) => store.setStickies(store.getState().stickies.map((x) => (x.id === s.id ? { ...x, ...patch } : x)))
  const remove = () => store.setStickies(store.getState().stickies.filter((x) => x.id !== s.id))
  const tone = STICKY_TONES[(s.color - 1 + STICKY_TONES.length) % STICKY_TONES.length]
  return (
    <>
      <NodeResizer isVisible={selected && !data.readOnly} minWidth={150} minHeight={80} onResizeEnd={(_, p) => update({ x: Math.round(p.x), y: Math.round(p.y), width: Math.round(p.width), height: Math.round(p.height) })} />
      <NodeToolbar isVisible={selected && !data.readOnly && !editing} position={Position.Top} offset={8}>
        <div className="flex items-center gap-1 rounded-md border border-stroke-light bg-surface p-1 shadow-sm">
          {STICKY_DOTS.map((dot, i) => (
            <button
              key={dot}
              type="button"
              aria-label={`Colour ${i + 1}`}
              onClick={() => update({ color: i + 1 })}
              className={cx('h-4 w-4 rounded-full border border-stroke-light', dot, s.color === i + 1 && 'ring-2 ring-accent ring-offset-1')}
            />
          ))}
          <span className="mx-0.5 h-4 w-px bg-stroke-light" />
          <button type="button" aria-label="Delete note" title="Delete note" onClick={remove} className="flex h-5 w-5 items-center justify-center rounded text-tertiary hover:text-critical">
            <Icon name="trash" size={12} />
          </button>
        </div>
      </NodeToolbar>
      <div
        className={cx('iaf-sticky-drag relative h-full w-full overflow-hidden rounded-md border p-3 shadow-sm', tone, selected && 'border-accent')}
        onDoubleClick={(e) => {
          e.stopPropagation()
          if (!data.readOnly) {
            setText(s.text)
            setEditing(true)
          }
        }}
      >
        <span className="absolute right-2 top-2 text-tertiary" title="Sticky notes are kept in this browser only: a workflow file has no field for them">
          <Icon name="info" size={12} />
        </span>
        {editing ? (
          <textarea
            autoFocus
            aria-label="Note"
            className="nodrag nowheel h-full w-full resize-none bg-transparent text-footnote text-primary outline-none"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onBlur={() => {
              setEditing(false)
              if (text !== s.text) update({ text })
            }}
            onKeyDown={(e) => {
              e.stopPropagation()
              if (e.key === 'Escape') e.currentTarget.blur()
            }}
          />
        ) : (
          <StickyText text={s.text} />
        )}
      </div>
    </>
  )
})

export const NODE_TYPES = {
  step: StepNode,
  trigger: TriggerNode,
  end: EndNode,
  undo: UndoNode,
  placeholder: PlaceholderNode,
  sticky: StickyNode,
}
