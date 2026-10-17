/**
 * Testing a workflow, n8n's way: the "Test workflow" button at the bottom of the canvas (⌘↵),
 * a sheet that asks for the input, then a dry run whose path lights up step by step on the
 * canvas, and a pill that says how it went.
 */
import { useState, type ReactNode } from 'react'
import { Button, cx, duration, Icon, Kbd, Modal, modKeyLabel, OverlayBody, OverlayFooter, OverlayHeader, Segmented, Spinner, Textarea, useHotkey, useOverlayClose } from '@iaf/ui'
import type { JsonObject } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { sampleInput } from '../model/expressions'
import { useDesignerSelector, useDesignerStore, type TestRun } from '../model/store'
import { UiGlyph } from '../canvas/glyphs'
import { InputForm } from './InputForm'

function SheetBody() {
  const store = useDesignerStore()
  const close = useOverlayClose()
  const doc = useDesignerSelector((s) => s.doc)
  const [initial] = useState<JsonObject>(() => store.getState().lastInput ?? sampleInput(doc))
  const [mode, setMode] = useState<'form' | 'json'>('form')
  const [value, setValue] = useState<JsonObject>(initial)
  const [text, setText] = useState(() => JSON.stringify(initial, null, 2))
  const [error, setError] = useState<string | null>(null)
  const schema = doc.api?.input as Schema | undefined

  const switchMode = (m: 'form' | 'json') => {
    if (m === mode) return
    if (m === 'json') {
      setText(JSON.stringify(value, null, 2))
      setError(null)
    } else {
      try {
        const parsed = JSON.parse(text) as unknown
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('The input is a JSON object')
        setValue(parsed as JsonObject)
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Not valid JSON')
        return
      }
    }
    setMode(m)
  }

  const run = () => {
    let input = value
    if (mode === 'json') {
      try {
        const parsed = JSON.parse(text) as unknown
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('The input is a JSON object')
        input = parsed as JsonObject
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Not valid JSON')
        return
      }
    }
    void store.testRun(input)
    close()
  }

  useHotkey('mod+enter', run, { allowInInputs: true })

  return (
    <>
      <OverlayHeader title="Test workflow" subtitle="A dry run: every provider answers with its sample, nothing changes anywhere." icon="play" />
      <OverlayBody>
        <div className="mb-4 flex items-center justify-between gap-3">
          <p className="text-footnote font-medium text-secondary">Input</p>
          <Segmented<'form' | 'json'>
            size="sm"
            value={mode}
            onChange={switchMode}
            ariaLabel="Input editor"
            options={[
              { value: 'form', label: 'Form' },
              { value: 'json', label: 'JSON' },
            ]}
          />
        </div>
        {mode === 'form' ? (
          <InputForm schema={schema} value={value} onChange={setValue} idPrefix="test-input" />
        ) : (
          <div className="space-y-1.5">
            <Textarea mono rows={12} value={text} invalid={!!error} spellCheck={false} aria-label="Input as JSON" onChange={(e) => setText(e.target.value)} data-autofocus />
            {error && (
              <p className="text-caption2 text-critical" role="alert">
                {error}
              </p>
            )}
          </div>
        )}
      </OverlayBody>
      <OverlayFooter>
        <Button variant="secondary" onClick={close}>
          Cancel
        </Button>
        <Button variant="primary" onClick={run}>
          <UiGlyph name="flask" size={15} />
          Test workflow
          <Kbd className="border-white/30 text-white/80">{modKeyLabel()}↵</Kbd>
        </Button>
      </OverlayFooter>
    </>
  )
}

export function TestRunSheet({ onClose }: { onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Test workflow">
      <SheetBody />
    </Modal>
  )
}

function RunPill({ run, onClear }: { run: TestRun; onClear: () => void }) {
  const total = run.result?.path.length ?? 0
  const failed = run.result?.steps.find((s) => s.status.toLowerCase() === 'failed')
  const took = run.finishedAt ? duration(Math.max(0, run.finishedAt - run.startedAt)) : null
  let content: ReactNode
  if (run.status === 'running') {
    content = (
      <>
        <Spinner size={13} className="text-accent" />
        <span>{run.result ? `Running step ${Math.min(run.revealed + 1, total)} of ${total}` : 'Starting the dry run'}</span>
      </>
    )
  } else if (run.status === 'passed') {
    content = (
      <>
        <Icon name="check" size={14} strokeWidth={2.4} className="text-healthy" />
        <span>
          Test passed · {total} {total === 1 ? 'step' : 'steps'}
          {took ? ` · ${took}` : ''}
        </span>
      </>
    )
  } else {
    const message = run.error ?? failed?.error ?? run.result?.error ?? 'The dry run stopped'
    content = (
      <>
        <Icon name="close-circle" size={14} strokeWidth={2} className="text-critical" />
        <span className="max-w-[420px] truncate" title={message}>
          {failed ? `Failed at ${failed.name || failed.id}: ` : 'Test failed: '}
          {message}
        </span>
      </>
    )
  }
  return (
    <div
      role="status"
      aria-live="polite"
      className={cx('material-panel flex h-10 items-center gap-2 rounded-full pl-3.5 pr-1.5 text-footnote text-primary animate-fade-in', run.status === 'failed' && 'text-critical')}
    >
      {content}
      {run.status !== 'running' && (
        <button type="button" aria-label="Clear test results" title="Clear test results" onClick={onClear} className="flex h-7 w-7 items-center justify-center rounded-full text-tertiary hover:bg-surface-secondary hover:text-primary">
          <Icon name="close" size={13} />
        </button>
      )}
    </div>
  )
}

/** The bottom-centre controls of the canvas. */
export function RunBar({ onTest }: { onTest: () => void }) {
  const store = useDesignerStore()
  const run = useDesignerSelector((s) => s.testRun)
  const hasSteps = useDesignerSelector((s) => s.graph.nodes.some((n) => n.data.kind === 'step'))
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-5 z-10 flex justify-center px-48">
      <div className="pointer-events-auto flex items-center gap-2">
        {run && <RunPill run={run} onClear={() => store.clearTestRun()} />}
        <Button variant="primary" onClick={onTest} disabled={!hasSteps || run?.status === 'running'} className="h-10 rounded-full px-5 shadow-md" data-testid="test-workflow">
          {run?.status === 'running' ? <Spinner size={15} /> : <UiGlyph name="flask" size={16} />}
          Test workflow
          <Kbd className="border-white/30 text-white/80">{modKeyLabel()}↵</Kbd>
        </Button>
      </div>
    </div>
  )
}
