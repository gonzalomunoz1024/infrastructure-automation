/**
 * n8n's node detail view: a large sheet over the canvas with three panes. Input (what the
 * steps before produced: the job input or any earlier step, as Schema, Table or JSON, every
 * field draggable onto a parameter), the node itself (name, kind, Test step, then Parameters
 * and Settings with the live problems under their fields) and Output (the test run's output,
 * pinned data, or what the step is expected to return). ⌘↵ tests, Esc goes back to the canvas.
 */
import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { Button, cx, Icon, Kbd, Modal, modKeyLabel, Segmented, Select, Spinner, Tabs, Textarea, Tooltip, useHotkey, useOverlayClose, useToast } from '@iaf/ui'
import { displayName, type ExpressionContext, type JsonValue, type ParsedStep, type Problem } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { contextAt, expectedOutput, mappingExpression, sampleInput } from '../model/expressions'
import { findStepNode, type GraphNode, type StepNodeData, type TriggerNodeData } from '../model/graph'
import { findAction } from '../model/registry'
import { useDesignerSelector, useDesignerStore, type NdvTab } from '../model/store'
import { NodeGlyph, stepGlyph, triggerGlyph, UiGlyph } from '../canvas/glyphs'
import { NdvContext, type NdvContextValue } from './context'
import { DATA_MODES, DataView, type DataMode } from './DataView'
import { asObject, asString, isSettingsField, jsonText, parseJsonText } from './fields'
import { ProblemList } from './ParameterField'
import { looseProblems, StepParameters, stepKindLabel } from './StepParameters'
import { StepSettings } from './StepSettings'
import { triggerOutput, triggerProblems, TriggerParameters, type TriggerTarget } from './TriggerParameters'

const INPUT_SOURCE = '__input'

function PaneHeader({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="flex min-h-9 flex-wrap items-center gap-2 pb-2">
      <h3 className="text-caption2 font-semibold uppercase tracking-wider text-tertiary">{title}</h3>
      <span className="flex-1" />
      {children}
    </div>
  )
}

function ModeSwitch({ value, onChange, label }: { value: DataMode; onChange: (m: DataMode) => void; label: string }) {
  return <Segmented<DataMode> value={value} onChange={onChange} options={DATA_MODES} ariaLabel={label} size="sm" />
}

function Origin({ icon, children, tone = 'neutral' }: { icon: ReactNode; children: ReactNode; tone?: 'neutral' | 'accent' | 'warning' }) {
  return (
    <p className={cx('mb-2 flex items-center gap-1.5 rounded-sm px-2 py-1 text-caption', tone === 'accent' ? 'bg-accent-tint text-accent' : tone === 'warning' ? 'bg-warning-tint text-warning' : 'bg-surface text-tertiary')}>
      {icon}
      <span className="min-w-0">{children}</span>
    </p>
  )
}

function itemsLabel(v: JsonValue | undefined): string | null {
  if (v === undefined || v === null) return null
  const n = Array.isArray(v) ? v.length : 1
  return `${n} ${n === 1 ? 'item' : 'items'}`
}

function BackToCanvas() {
  const close = useOverlayClose()
  return (
    <button type="button" onClick={close} className="flex items-center gap-1.5 rounded-sm px-2 py-1 text-footnote font-medium text-secondary hover:bg-surface hover:text-primary">
      <Icon name="arrow-left" size={14} />
      Back to canvas
    </button>
  )
}

// ---------------------------------------------------------------------------------------------
// panes

interface InputPaneProps {
  outputs: Record<string, JsonValue>
  previous: ParsedStep | null
  input: JsonValue
  nameOf: (id: string) => string
  originOf: (id: string) => 'pinned' | 'run' | 'sample'
  inputFromRun: boolean
}

function InputPane({ outputs, previous, input, nameOf, originOf, inputFromRun }: InputPaneProps) {
  const ids = Object.keys(outputs)
  const fallback = previous?.id ?? INPUT_SOURCE
  const [chosen, setChosen] = useState<string | null>(null)
  const source = chosen !== null && (chosen === INPUT_SOURCE || ids.includes(chosen)) ? chosen : fallback
  const [mode, setMode] = useState<DataMode>('schema')
  const options = [{ value: INPUT_SOURCE, label: 'Job input', hint: '$input' }, ...ids.map((id) => ({ value: id, label: nameOf(id), hint: id === previous?.id ? '$json' : `$('${nameOf(id)}')` }))]
  const value: JsonValue | undefined = source === INPUT_SOURCE ? input : outputs[source]
  const mapping = useCallback(
    (path: string) => {
      if (source === INPUT_SOURCE) return mappingExpression({ kind: 'input' }, path)
      if (source === previous?.id) return mappingExpression({ kind: 'previous' }, path)
      return mappingExpression({ kind: 'step', name: nameOf(source) }, path)
    },
    [source, previous, nameOf],
  )
  const origin = source === INPUT_SOURCE ? (inputFromRun ? 'run' : 'sample') : originOf(source)
  const empty = value === undefined || value === null || (typeof value === 'object' && Object.keys(value).length === 0)
  return (
    <section aria-label="Input" className="hidden min-w-0 flex-1 flex-col lg:flex" data-testid="ndv-input">
      <PaneHeader title="Input">
        <ModeSwitch value={mode} onChange={setMode} label="Input view" />
      </PaneHeader>
      <div className="mb-2">
        <Select value={source} onChange={setChosen} options={options} ariaLabel="Data from" size="sm" />
      </div>
      {origin === 'pinned' && <Origin icon={<UiGlyph name="pin" size={12} />} tone="accent">Pinned data</Origin>}
      {origin === 'run' && <Origin icon={<UiGlyph name="flask" size={12} />}>From the last test run</Origin>}
      {origin === 'sample' && (
        <Origin icon={<Icon name="info" size={12} />} tone="warning">
          {source === INPUT_SOURCE ? 'Sample input from the API schema' : 'Sample data: test the workflow to see real data'}
        </Origin>
      )}
      <div className="min-h-0 flex-1 overflow-auto pr-1">
        {empty ? (
          <p className="py-6 text-center text-caption text-tertiary">{source === INPUT_SOURCE ? 'The job input has no fields yet.' : 'This step outputs nothing yet.'}</p>
        ) : (
          <DataView value={value} mode={mode} mapping={mapping} label={`Input from ${source === INPUT_SOURCE ? 'the job input' : nameOf(source)}`} />
        )}
      </div>
      {!empty && (
        <p className="mt-2 flex items-center gap-1.5 text-caption2 text-tertiary">
          <UiGlyph name="drag" size={12} />
          Drag a field onto a parameter to map it
        </p>
      )}
    </section>
  )
}

function PinEditor({ initial, onSave, onCancel }: { initial: JsonValue | undefined; onSave: (v: JsonValue) => void; onCancel: () => void }) {
  const [text, setText] = useState(() => jsonText(initial ?? {}))
  const [error, setError] = useState<string | null>(null)
  const save = () => {
    const r = parseJsonText(text)
    if (!r.ok) return setError(r.error)
    if (r.value === undefined) return setError('Enter the data to pin')
    onSave(r.value)
  }
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      <Textarea mono rows={14} value={text} onChange={(e) => setText(e.target.value)} aria-label="Mock output" invalid={error !== null} className="min-h-0 flex-1" spellCheck={false} />
      {error && <p className="text-caption text-critical">Not valid JSON: {error}</p>}
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button size="sm" variant="primary" onClick={save}>
          Pin
        </Button>
      </div>
    </div>
  )
}

interface OutputPaneProps {
  stepId: string | null
  /** the trigger's job input, for trigger nodes */
  fixed?: JsonValue
  expected: JsonValue | null
  expectedSchema?: Schema | null
  onTest?: () => void
}

function OutputPane({ stepId, fixed, expected, expectedSchema, onTest }: OutputPaneProps) {
  const store = useDesignerStore()
  const toast = useToast()
  const testRun = useDesignerSelector((s) => s.testRun)
  const pinned = useDesignerSelector((s) => (stepId ? s.pins[stepId] : undefined))
  const [mode, setMode] = useState<DataMode>('json')
  const [editing, setEditing] = useState(false)
  const runStep = stepId ? testRun?.result?.steps.find((s) => s.id === stepId) : undefined
  const ran = runStep !== undefined && (testRun?.result?.path.includes(stepId ?? '') ?? false)
  const waiting = testRun?.status === 'running' && !testRun.result
  const runOutput = runStep?.output as JsonValue | undefined
  const shown: JsonValue | undefined = fixed !== undefined ? fixed : pinned !== undefined ? pinned : ran ? runOutput : undefined
  const count = itemsLabel(shown)

  let body: ReactNode
  if (editing && stepId) {
    body = (
      <PinEditor
        initial={pinned ?? runOutput ?? expected ?? {}}
        onCancel={() => setEditing(false)}
        onSave={(v) => {
          store.setPin(stepId, v)
          setEditing(false)
          toast.toast({ title: 'Output pinned', message: 'Tests and the steps after it use this data until you unpin it', tone: 'neutral' })
        }}
      />
    )
  } else if (fixed !== undefined) {
    body = <DataView value={fixed} mode={mode} label="Output" />
  } else if (pinned !== undefined) {
    body = (
      <>
        <div className="mb-2 flex items-center gap-2 rounded-sm bg-accent-tint px-2 py-1.5 text-caption text-accent">
          <UiGlyph name="pin" size={12} />
          <span className="flex-1">This data is pinned</span>
          <button type="button" onClick={() => setEditing(true)} className="font-medium hover:underline">
            Edit
          </button>
          <button type="button" onClick={() => stepId && store.setPin(stepId, undefined)} className="font-medium hover:underline">
            Unpin
          </button>
        </div>
        <DataView value={pinned} mode={mode} label="Output" />
      </>
    )
  } else if (waiting) {
    body = (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 py-10 text-caption text-secondary">
        <Spinner size={20} />
        Running the test…
      </div>
    )
  } else if (testRun?.result && runStep && runStep.status === 'failed') {
    body = (
      <div className="rounded-md border border-critical/40 bg-critical-tint p-3 text-caption text-critical" role="alert">
        <p className="font-semibold">The step failed</p>
        <p className="mt-1 whitespace-pre-wrap font-mono">{runStep.error ?? testRun.result.error ?? 'No error message'}</p>
      </div>
    )
  } else if (ran) {
    body = runOutput === undefined || runOutput === null ? <p className="py-6 text-center text-caption text-tertiary">The step ran and output nothing.</p> : <DataView value={runOutput} mode={mode} label="Output" />
  } else {
    body = (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-surface text-tertiary">
          <UiGlyph name="flask" size={22} />
        </span>
        <div>
          <p className="text-footnote font-medium text-primary">{testRun?.result ? 'This step did not run in the last test' : 'No output yet'}</p>
          <p className="mt-0.5 text-caption text-tertiary">{testRun?.result ? 'The test took another path; change the input or the conditions.' : 'Test this step to see what it returns.'}</p>
        </div>
        {onTest && (
          <div className="flex items-center gap-2">
            <Button size="sm" variant="primary" onClick={onTest}>
              Test step
            </Button>
            {stepId && (
              <Button size="sm" variant="capsule" onClick={() => setEditing(true)}>
                Set mock data
              </Button>
            )}
          </div>
        )}
        {expected !== null && expected !== undefined && (
          <div className="mt-2 w-full text-left">
            <p className="mb-1 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Expected output</p>
            <div className="rounded-md border border-dashed border-stroke-light p-2 opacity-80">
              <DataView value={expected} schema={expectedSchema} mode="schema" label="Expected output" />
            </div>
          </div>
        )}
      </div>
    )
  }

  const canPin = stepId !== null && fixed === undefined && !editing && (pinned !== undefined || (ran && runOutput !== undefined && runOutput !== null))
  return (
    <section aria-label="Output" className="hidden min-w-0 flex-1 flex-col md:flex" data-testid="ndv-output">
      <PaneHeader title="Output">
        {count && <span className="text-caption2 text-tertiary">{count}</span>}
        {stepId && fixed === undefined && (
          <Tooltip label={pinned !== undefined ? 'Unpin the output' : 'Pin the output: tests and the steps after it use it'}>
            <button
              type="button"
              aria-label={pinned !== undefined ? 'Unpin output' : 'Pin output'}
              aria-pressed={pinned !== undefined}
              disabled={!canPin}
              onClick={() => stepId && store.togglePin(stepId, runOutput)}
              className={cx('flex h-7 w-7 items-center justify-center rounded-sm', pinned !== undefined ? 'bg-accent-tint text-accent' : 'text-tertiary hover:bg-surface hover:text-primary')}
            >
              <UiGlyph name="pin" size={14} />
            </button>
          </Tooltip>
        )}
        <ModeSwitch value={mode} onChange={setMode} label="Output view" />
      </PaneHeader>
      <div className="flex min-h-0 flex-1 flex-col overflow-auto pr-1">{body}</div>
    </section>
  )
}

// ---------------------------------------------------------------------------------------------
// the node

function NodeHeader({ glyph, title, kind, editable, onRename, placeholder, onTest, testing }: { glyph: ReactNode; title: string; kind: string; editable: boolean; onRename?: (name: string) => void; placeholder?: string; onTest?: () => void; testing?: boolean }) {
  return (
    <div className="flex items-center gap-3 border-b border-stroke-light px-4 py-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border border-stroke-light bg-surface">{glyph}</span>
      <div className="min-w-0 flex-1">
        {editable ? (
          <input
            value={title}
            placeholder={placeholder}
            aria-label="Step name"
            onChange={(e) => onRename?.(e.target.value)}
            className="no-focus-ring w-full truncate rounded-sm bg-transparent px-1 -mx-1 text-subheadline font-semibold text-primary hover:bg-surface-secondary focus:bg-surface-secondary focus:outline-none focus:ring-2 focus:ring-accent/25"
          />
        ) : (
          <h2 className="truncate text-subheadline font-semibold text-primary">{title}</h2>
        )}
        <p className="truncate text-caption text-tertiary">{kind}</p>
      </div>
      {onTest && (
        <Button size="sm" variant="primary" onClick={onTest} loading={testing} data-testid="ndv-test-step">
          <span className="flex items-center gap-1.5">
            {!testing && <UiGlyph name="flask" size={14} />}
            Test step
            <Kbd className="border-white/30 text-white/80">{`${modKeyLabel()}↵`}</Kbd>
          </span>
        </Button>
      )}
    </div>
  )
}

function ProblemsBox({ problems }: { problems: readonly Problem[] }) {
  if (problems.length === 0) return null
  const errors = problems.some((p) => p.level === 'error')
  return (
    <div className={cx('mb-4 rounded-md border px-3 py-2', errors ? 'border-critical/30 bg-critical-tint' : 'border-warning/30 bg-warning-tint')}>
      <ProblemList problems={problems} />
    </div>
  )
}

function StepDetail({ node }: { node: GraphNode & { data: StepNodeData } }) {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  const graph = useDesignerSelector((s) => s.graph)
  const registry = useDesignerSelector((s) => s.registry)
  const problems = useDesignerSelector((s) => s.problems)
  const tab = useDesignerSelector((s) => s.ndvTab)
  const lastInput = useDesignerSelector((s) => s.lastInput)
  const testing = useDesignerSelector((s) => s.testRun?.status === 'running')
  const testRun = useDesignerSelector((s) => s.testRun)
  const pins = useDesignerSelector((s) => s.pins)
  useDesignerSelector((s) => s.version)
  const { stepId, body, step } = node.data
  const known = store.knownOutputs()
  const sc = useMemo(() => contextAt(doc, stepId, known, registry, lastInput ?? undefined), [doc, stepId, known, registry, lastInput])
  const expected = useMemo(() => expectedOutput(step, registry, sc.ctx), [step, registry, sc])
  const ownOutput: JsonValue | null = known[stepId] !== undefined ? known[stepId] : expected
  const ownCtx: ExpressionContext = useMemo(() => ({ ...sc.ctx, json: ownOutput }), [sc, ownOutput])
  const [dragging, setDragging] = useState(false)
  const stepProblems = useMemo(() => problems.filter((p) => p.step === stepId), [problems, stepId])
  const ndv: NdvContextValue = useMemo(() => ({ ctx: sc.ctx, problems: stepProblems, dragging, setDragging, applyFix: (p) => void store.applyFix(p) }), [sc, stepProblems, dragging, store])
  const paramCount = stepProblems.filter((p) => !isSettingsField(p.field)).length
  const settingsCount = stepProblems.filter((p) => isSettingsField(p.field)).length
  const nameOf = useCallback((id: string) => {
    const n = findStepNode(graph, id)
    return n ? displayName(n.data.step) : id
  }, [graph])
  const originOf = useCallback(
    (id: string): 'pinned' | 'run' | 'sample' => {
      if (pins[id] !== undefined) return 'pinned'
      if (testRun?.result?.steps.some((s) => s.id === id && s.output !== undefined && s.output !== null)) return 'run'
      return 'sample'
    },
    [pins, testRun],
  )
  const test = useCallback(() => {
    const st = store.getState()
    if (st.testRun?.status === 'running') return
    void store.testRun(st.lastInput ?? sampleInput(st.doc), stepId)
  }, [store, stepId])
  useHotkey('mod+enter', test, { allowInInputs: true })

  const w = asObject(step.with as JsonValue | undefined)
  const action = step.type === 'provider' ? findAction(registry, asString(w.provider), asString(w.action)) : undefined
  return (
    <NdvContext.Provider value={ndv}>
      <InputPane key={stepId} outputs={sc.outputs} previous={sc.previous} input={sc.ctx.input ?? null} nameOf={nameOf} originOf={originOf} inputFromRun={lastInput !== null} />
      <section aria-label={`${displayName(step)} parameters`} className="flex min-h-0 w-full shrink-0 flex-col overflow-hidden rounded-lg border border-stroke-light bg-surface shadow-md lg:w-[min(520px,40%)]" data-testid="ndv-parameters">
        <NodeHeader
          glyph={<NodeGlyph glyph={stepGlyph(step, registry?.providers ?? [])} size={24} />}
          title={body.name ?? ''}
          placeholder={stepId}
          kind={stepKindLabel(step, registry)}
          editable
          onRename={(name) => store.setStepName(stepId, name)}
          onTest={test}
          testing={testing}
        />
        <div className="border-b border-stroke-light px-3 py-2">
          <Tabs<NdvTab>
            value={tab}
            onChange={(t) => store.setNdvTab(t)}
            ariaLabel="Step details"
            tabs={[
              { id: 'parameters', label: 'Parameters', count: paramCount > 0 ? paramCount : undefined },
              { id: 'settings', label: 'Settings', count: settingsCount > 0 ? settingsCount : undefined },
            ]}
          />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
          {tab === 'parameters' ? (
            <>
              <ProblemsBox problems={looseProblems(step, body, stepProblems)} />
              <StepParameters stepId={stepId} body={body} step={step} registry={registry} doc={doc} />
            </>
          ) : (
            <StepSettings stepId={stepId} body={body} step={step} doc={doc} ownCtx={ownCtx} allProblems={problems} />
          )}
        </div>
      </section>
      <OutputPane stepId={stepId} expected={expected} expectedSchema={action?.output ?? null} onTest={test} />
    </NdvContext.Provider>
  )
}

function triggerTarget(node: GraphNode): TriggerTarget | null {
  if (node.data.kind === 'error-trigger') return { kind: 'error' }
  if (node.data.kind !== 'trigger') return null
  const d: TriggerNodeData = node.data
  if (d.index === null) return { kind: 'api' }
  if (d.triggerType === 'alert' || d.triggerType === 'event') return { kind: d.triggerType, index: d.index }
  return null
}

function TriggerDetail({ node, target }: { node: GraphNode; target: TriggerTarget }) {
  const store = useDesignerStore()
  const doc = useDesignerSelector((s) => s.doc)
  const problems = useDesignerSelector((s) => s.problems)
  const lastInput = useDesignerSelector((s) => s.lastInput)
  const [dragging, setDragging] = useState(false)
  const own = useMemo(() => triggerProblems(problems, target), [problems, target])
  const ndv: NdvContextValue = useMemo(() => ({ ctx: {}, problems: own, dragging, setDragging, applyFix: (p) => void store.applyFix(p) }), [own, dragging, store])
  const label = node.data.kind === 'trigger' ? node.data.label : 'Error trigger'
  const kind = target.kind === 'api' ? 'API trigger · the workflow’s endpoint and MCP tool' : target.kind === 'alert' ? 'Alert trigger' : target.kind === 'event' ? 'Event trigger' : 'Error trigger · runs the error flow'
  const glyph = triggerGlyph(target.kind === 'error' ? 'error' : target.kind)
  const output = useMemo(() => triggerOutput(doc, target, lastInput), [doc, target, lastInput])
  return (
    <NdvContext.Provider value={ndv}>
      <section aria-label={`${label} parameters`} className="flex min-h-0 w-full shrink-0 flex-col overflow-hidden rounded-lg border border-stroke-light bg-surface shadow-md md:w-[min(560px,48%)]" data-testid="ndv-parameters">
        <NodeHeader glyph={<NodeGlyph glyph={glyph} size={24} />} title={label} kind={kind} editable={false} />
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
          <TriggerParameters target={target} doc={doc} />
        </div>
      </section>
      <OutputPane stepId={null} fixed={output} expected={null} />
    </NdvContext.Provider>
  )
}

export function NodeDetailView({ nodeId }: { nodeId: string }) {
  const store = useDesignerStore()
  const node = useDesignerSelector((s) => s.graph.nodes.find((n) => n.id === nodeId) ?? null)
  const close = useCallback(() => store.closeNdv(), [store])
  const target = useMemo(() => (node ? triggerTarget(node) : null), [node])
  if (!node) return null
  if (node.data.kind !== 'step' && !target) return null
  const label = node.data.kind === 'step' ? displayName(node.data.step) : node.data.kind === 'trigger' ? node.data.label : 'Error trigger'
  return (
    <Modal onClose={close} width="max-w-[90vw]" label={`${label}: node details`} panelClassName="h-[85vh]">
      <div className="flex h-full min-h-0 flex-col bg-surface-secondary" data-testid="ndv" data-autofocus tabIndex={-1} style={{ outline: 'none' }}>
        <div className="flex items-center gap-2 px-3 pt-2">
          <BackToCanvas />
          <span className="flex-1" />
          <span className="hidden text-caption2 text-tertiary sm:inline">
            <Kbd>esc</Kbd> back to canvas
          </span>
        </div>
        <div className="flex min-h-0 flex-1 gap-4 px-4 pb-4 pt-2">
          {node.data.kind === 'step' ? <StepDetail node={node as GraphNode & { data: StepNodeData }} /> : target ? <TriggerDetail node={node} target={target} /> : null}
        </div>
      </div>
    </Modal>
  )
}
