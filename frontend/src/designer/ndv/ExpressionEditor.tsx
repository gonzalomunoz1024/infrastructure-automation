/**
 * The expanded expression editor: the parameter's text in Monaco with its `{{ … }}` highlighted,
 * the ones that do not resolve marked with why, and the live result underneath, evaluated the
 * way the interpreter will. Edits go to the parameter as they are typed; Done or Escape closes.
 */
import { useMemo } from 'react'
import { Button, Modal, OverlayBody, OverlayFooter, OverlayHeader, cx } from '@iaf/ui'
import type { ExpressionContext } from '@iaf/workflow-checks'
import { CodeEditor, EXPRESSION_LANGUAGE } from '../../components/code-editor/CodeEditor'
import { expressionMarkers } from '../../components/code-editor/markers'
import { previewExpression } from '../model/expressions'

export interface ExpressionEditorProps {
  label: string
  value: string
  onChange: (value: string) => void
  ctx: ExpressionContext
  /** one line: a newline typed or pasted becomes a space */
  singleLine?: boolean
  onClose: () => void
}

export function ExpressionEditor({ label, value, onChange, ctx, singleLine, onClose }: ExpressionEditorProps) {
  const markers = useMemo(
    () =>
      expressionMarkers(value, (e) => {
        const r = previewExpression(e, ctx)
        return r.ok ? null : r.error
      }),
    [value, ctx],
  )
  const result = useMemo(() => (value.includes('{{') ? previewExpression(value, ctx) : null), [value, ctx])

  return (
    <Modal onClose={onClose} width="max-w-3xl" label={`Edit ${label}`} raised>
      <OverlayHeader title={label} subtitle="Text with {{ … }} expressions, like {{ $('lookup').json.id }}; the result is what the interpreter will send." icon="edit" />
      <OverlayBody className="space-y-3">
        <CodeEditor
          language={EXPRESSION_LANGUAGE}
          ariaLabel={`${label} expression`}
          testId="expression-editor"
          value={value}
          markers={markers}
          autoFocus
          onChange={(v) => onChange(singleLine ? v.replace(/\n/g, ' ') : v)}
          className="h-56"
        />
        <div className={cx('flex min-w-0 items-baseline gap-2 rounded-md px-3 py-2 text-caption', !result || result.ok ? 'bg-surface-secondary' : 'bg-critical-tint')} aria-live="polite" data-testid="expression-result">
          <span className="shrink-0 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Result</span>
          {!result ? (
            <span className="min-w-0 break-words font-mono text-primary">{value === '' ? '(empty)' : value}</span>
          ) : result.ok ? (
            <span className={cx('min-w-0 break-words font-mono', result.value === null ? 'text-tertiary' : 'text-primary')}>{result.text === '' ? '(empty)' : result.text}</span>
          ) : (
            <span className="min-w-0 break-words text-critical">{result.error}</span>
          )}
        </div>
      </OverlayBody>
      <OverlayFooter>
        <Button variant="primary" onClick={onClose}>
          Done
        </Button>
      </OverlayFooter>
    </Modal>
  )
}
