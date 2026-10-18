/**
 * n8n's inline expression editor: monospace text where every `{{ … }}` is highlighted (red when
 * it does not resolve), growing with its content, taking fields dropped from the Input pane at
 * the drop point, with the live result underneath, evaluated the way the interpreter will.
 */
import { useLayoutEffect, useMemo, useRef, useState, type DragEvent, type FocusEvent, type KeyboardEvent } from 'react'
import { cx } from '@iaf/ui'
import type { ExpressionContext } from '@iaf/workflow-checks'
import { previewExpression, templateSegments } from '../model/expressions'
import { MAPPING_MIME, useNdv } from './context'

export interface ExpressionInputProps {
  id?: string
  value: string
  onChange: (value: string) => void
  /** the context previews evaluate in; the open node's by default */
  ctx?: ExpressionContext
  placeholder?: string
  invalid?: boolean
  ariaLabel?: string
  /** show the evaluated result under the editor (default true) */
  preview?: boolean
  /** one line: Enter does not insert a newline */
  singleLine?: boolean
  onBlur?: (e: FocusEvent<HTMLTextAreaElement>) => void
  onKeyDown?: (e: KeyboardEvent<HTMLTextAreaElement>) => void
  autoFocus?: boolean
  readOnly?: boolean
  className?: string
}

export function ExpressionInput({ id, value, onChange, ctx: ctxProp, placeholder, invalid, ariaLabel, preview = true, singleLine, onBlur, onKeyDown, autoFocus, readOnly, className }: ExpressionInputProps) {
  const ndv = useNdv()
  const ctx = ctxProp ?? ndv.ctx
  const ref = useRef<HTMLTextAreaElement>(null)
  const [over, setOver] = useState(false)

  useLayoutEffect(() => {
    const t = ref.current
    if (!t) return
    t.style.height = 'auto'
    t.style.height = `${t.scrollHeight}px`
  }, [value])

  // focus with the caret at the end (a fixed field that just became an expression)
  useLayoutEffect(() => {
    const t = ref.current
    if (!autoFocus || !t) return
    t.focus({ preventScroll: true })
    t.setSelectionRange(t.value.length, t.value.length)
  }, [autoFocus])

  const segments = useMemo(
    () =>
      templateSegments(value).map((s) => {
        if (!s.expression) return { ...s, ok: true }
        const r = previewExpression(s.text, ctx)
        return { ...s, ok: r.ok }
      }),
    [value, ctx],
  )
  const result = useMemo(() => (preview && value.includes('{{') ? previewExpression(value, ctx) : null), [preview, value, ctx])

  const accepts = (e: DragEvent) => !readOnly && e.dataTransfer.types.includes(MAPPING_MIME)
  const onDragOver = (e: DragEvent<HTMLDivElement>) => {
    if (!accepts(e)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    if (!over) setOver(true)
  }
  // the browser inserts the dragged text/plain at the drop point and fires an input event
  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    setOver(false)
    ndv.setDragging(false)
    if (!accepts(e)) return
    if (e.target !== ref.current) {
      // dropped on the padding: append
      e.preventDefault()
      const text = e.dataTransfer.getData('text/plain')
      onChange(value ? `${value}${value.endsWith(' ') ? '' : ' '}${text}` : text)
      ref.current?.focus()
    }
  }

  return (
    <div className={cx('min-w-0', className)}>
      <div
        className={cx(
          'iaf-expr rounded-md border bg-surface-secondary transition-colors focus-within:ring-2',
          invalid ? 'border-critical focus-within:ring-critical/20' : 'border-stroke-light focus-within:border-accent focus-within:ring-accent/25',
          ndv.dragging && !readOnly && 'iaf-drop-target',
          over && 'is-over',
        )}
        onDragOver={onDragOver}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
      >
        <div className="iaf-expr__mirror" aria-hidden="true">
          {segments.map((s, i) =>
            s.expression ? (
              <span key={i} className={cx('iaf-expr__token', !s.ok && 'iaf-expr__token--error')}>
                {s.text}
              </span>
            ) : (
              <span key={i}>{s.text}</span>
            ),
          )}
          {'\n'}
        </div>
        <textarea
          ref={ref}
          id={id}
          rows={1}
          value={value}
          spellCheck={false}
          readOnly={readOnly}
          aria-label={ariaLabel}
          aria-invalid={invalid || undefined}
          placeholder={placeholder}
          onChange={(e) => onChange(singleLine ? e.target.value.replace(/\n/g, ' ') : e.target.value)}
          onBlur={onBlur}
          onKeyDown={(e) => {
            if (singleLine && e.key === 'Enter' && !e.metaKey && !e.ctrlKey) e.preventDefault()
            onKeyDown?.(e)
          }}
          className="iaf-expr__input"
        />
      </div>
      {result && (
        <div className={cx('mt-1 flex min-w-0 items-baseline gap-2 rounded-sm px-2 py-1 text-caption', result.ok ? 'bg-surface-secondary' : 'bg-critical-tint')} aria-live="polite">
          <span className="shrink-0 text-caption2 font-semibold uppercase tracking-wide text-tertiary">Result</span>
          {result.ok ? (
            <span className={cx('min-w-0 truncate font-mono', result.value === null ? 'text-tertiary' : 'text-primary')} title={result.text}>
              {result.text === '' ? '(empty)' : result.text}
            </span>
          ) : (
            <span className="min-w-0 truncate text-critical" title={result.error}>
              {result.error}
            </span>
          )}
        </div>
      )}
    </div>
  )
}
