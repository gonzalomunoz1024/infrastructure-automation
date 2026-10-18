/**
 * One parameter of the Parameters pane, n8n style: the label with its required mark, the
 * Fixed / Expression toggle that shows on hover, the control the type asks for (or the
 * expression editor), the problems of the field right under it, and drops from the Input pane.
 * Also the JSON and key/value editors the object parameters use.
 */
import { useEffect, useId, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { cx, Icon, Input, Select, Switch, Textarea, Tooltip, type SelectOption } from '@iaf/ui'
import type { ExpressionContext, JsonObject, JsonValue, Problem } from '@iaf/workflow-checks'
import { fixOf } from '../model/fixes'
import type { FieldType } from '../model/schema'
import { ExpressionInput } from './ExpressionInput'
import { MAPPING_MIME, useNdv } from './context'
import { asObject, isExpression, jsonText, parseJsonText, problemsAt, toExpression, toFixed } from './fields'

export interface ParamSpec {
  /** the field path the checks use, relative to the step block */
  field: string
  label: string
  type: FieldType
  required?: boolean
  description?: string
  options?: SelectOption[]
  placeholder?: string
  /** may be an expression (default: every type but object and array) */
  expression?: boolean
  mono?: boolean
  multiline?: boolean
  /** the field to show the problems of, when it differs from `field` */
  problemField?: string
  /** more fields whose own problems (not their children's) show here */
  problemsExact?: string[]
  /** suggestions for a text field */
  suggestions?: string[]
  /** a different expression context (assert sees the step's own output) */
  ctx?: ExpressionContext
}

/** The problems of a field, under it: what is wrong and the one-click fix when there is one. */
export function ProblemList({ problems, className }: { problems: readonly Problem[]; className?: string }) {
  const { applyFix } = useNdv()
  if (problems.length === 0) return null
  return (
    <ul className={cx('space-y-0.5', className)}>
      {problems.map((p, i) => {
        const fix = fixOf(p)
        return (
          <li key={`${p.code}-${i}`} className={cx('flex items-start gap-1.5 text-caption', p.level === 'error' ? 'text-critical' : 'text-warning')} role={p.level === 'error' ? 'alert' : undefined}>
            <Icon name={p.level === 'error' ? 'alert-triangle' : 'warning'} size={12} className="mt-0.5 shrink-0" />
            <span className="min-w-0">
              {p.message}
              {fix && fix.kind !== 'connect' && (
                <button type="button" onClick={() => applyFix(p)} className="ml-1.5 font-medium text-accent hover:underline">
                  {fix.label}
                </button>
              )}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

/** The label row: name, required mark, the description behind an info glyph, controls at the right. */
export function FieldLabel({ htmlFor, label, required, description, right }: { htmlFor?: string; label: string; required?: boolean; description?: string; right?: ReactNode }) {
  return (
    <div className="flex min-h-6 items-center gap-1.5">
      <label htmlFor={htmlFor} className="text-caption font-medium text-secondary">
        {label}
        {required && (
          <span className="ml-0.5 text-critical" aria-hidden="true">
            *
          </span>
        )}
        {required && <span className="sr-only"> (required)</span>}
      </label>
      {description && (
        <Tooltip label={description} side="top">
          <span tabIndex={0} aria-label={description} className="inline-flex text-tertiary hover:text-secondary">
            <Icon name="info" size={12} />
          </span>
        </Tooltip>
      )}
      <span className="flex-1" />
      {right}
    </div>
  )
}

function ModeToggle({ expression, onChange, label }: { expression: boolean; onChange: (expression: boolean) => void; label: string }) {
  const base = 'px-2 py-0.5 text-caption2 font-medium transition-colors'
  return (
    <div role="group" aria-label={`${label}: fixed value or expression`} className={cx('flex overflow-hidden rounded-sm border border-stroke-light transition-opacity', expression ? 'opacity-100' : 'opacity-0 group-hover/param:opacity-100 group-focus-within/param:opacity-100')}>
      <button type="button" aria-pressed={!expression} onClick={() => onChange(false)} className={cx(base, !expression ? 'bg-surface-secondary text-primary' : 'text-tertiary hover:text-primary')}>
        Fixed
      </button>
      <button type="button" aria-pressed={expression} onClick={() => onChange(true)} className={cx(base, expression ? 'bg-accent-tint text-accent' : 'text-tertiary hover:text-primary')}>
        Expression
      </button>
    </div>
  )
}

/** A JSON value edited as text: parsed when the field loses focus, the parse error shown until then. */
export function JsonInput({ id, value, onChange, rows = 4, invalid, placeholder, ariaLabel, readOnly }: { id?: string; value: JsonValue | undefined; onChange: (v: JsonValue | undefined) => void; rows?: number; invalid?: boolean; placeholder?: string; ariaLabel?: string; readOnly?: boolean }) {
  const [text, setText] = useState(() => jsonText(value))
  const [error, setError] = useState<string | null>(null)
  const editing = useRef(false)
  useEffect(() => {
    if (!editing.current) {
      setText(jsonText(value))
      setError(null)
    }
  }, [value])
  const commit = () => {
    const r = parseJsonText(text)
    if (!r.ok) return setError(r.error)
    setError(null)
    if (JSON.stringify(r.value) !== JSON.stringify(value)) onChange(r.value)
  }
  return (
    <div className="flex flex-col gap-1">
      <Textarea
        id={id}
        mono
        rows={rows}
        value={text}
        readOnly={readOnly}
        aria-label={ariaLabel}
        placeholder={placeholder ?? '{ }'}
        invalid={invalid || error !== null}
        spellCheck={false}
        onChange={(e) => setText(e.target.value)}
        onFocus={() => {
          editing.current = true
        }}
        onBlur={() => {
          editing.current = false
          commit()
        }}
      />
      {error && <p className="text-caption2 text-critical">Not valid JSON: {error}</p>}
    </div>
  )
}

function textOfValue(v: JsonValue | undefined): string {
  if (v === undefined || v === null) return ''
  return typeof v === 'string' ? v : JSON.stringify(v)
}

/** What a typed value means: `true`, `5`, `[1]` and `{"a":1}` are JSON; anything else is text. */
function valueOfText(text: string, previous: JsonValue | undefined): JsonValue {
  if (typeof previous === 'string' || text.includes('{{')) return text
  try {
    const v = JSON.parse(text) as JsonValue
    return v
  } catch {
    return text
  }
}

/**
 * An object edited as rows of key and value (extra vars, headers, labels, set variables):
 * values may be expressions; a value that is JSON (`true`, `5`) stays typed.
 */
export function KeyValueEditor({
  value,
  onChange,
  keyLabel = 'Name',
  valueLabel = 'Value',
  addLabel = 'Add field',
  problemsOf,
  readOnly,
  keyPlaceholder = 'name',
  valuePlaceholder = 'value or {{ expression }}',
}: {
  value: JsonObject
  onChange: (v: JsonObject) => void
  keyLabel?: string
  valueLabel?: string
  addLabel?: string
  problemsOf?: (key: string) => Problem[]
  readOnly?: boolean
  keyPlaceholder?: string
  valuePlaceholder?: string
}) {
  const entries = Object.entries(value)
  const rename = (from: string, to: string): boolean => {
    const key = to.trim()
    if (!key || key === from) return key === from
    if (Object.hasOwn(value, key)) return false
    const next: JsonObject = {}
    for (const [k, v] of entries) next[k === from ? key : k] = v
    onChange(next)
    return true
  }
  const setValue = (key: string, v: JsonValue) => onChange({ ...value, [key]: v })
  const remove = (key: string) => {
    const next: JsonObject = { ...value }
    delete next[key]
    onChange(next)
  }
  const add = () => {
    let n = entries.length + 1
    let key = `field${n}`
    while (Object.hasOwn(value, key)) key = `field${++n}`
    onChange({ ...value, [key]: '' })
  }
  return (
    <div className="space-y-2">
      {entries.length === 0 && <p className="text-caption text-tertiary">Nothing yet.</p>}
      {entries.map(([k, v]) => (
        <div key={k} className="group/kv">
          <div className="flex items-start gap-2">
            <Input
              key={k}
              size="sm"
              mono
              defaultValue={k}
              readOnly={readOnly}
              aria-label={`${keyLabel} of ${k}`}
              placeholder={keyPlaceholder}
              className="!w-[38%] shrink-0"
              onBlur={(e) => {
                if (!rename(k, e.currentTarget.value)) e.currentTarget.value = k
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') e.currentTarget.blur()
              }}
            />
            <ExpressionInput
              value={textOfValue(v)}
              onChange={(t) => setValue(k, valueOfText(t, v))}
              ariaLabel={`${valueLabel} of ${k}`}
              placeholder={valuePlaceholder}
              singleLine
              readOnly={readOnly}
              className="flex-1"
            />
            {!readOnly && (
              <button type="button" onClick={() => remove(k)} aria-label={`Remove ${k}`} title="Remove" className="mt-1.5 rounded-sm p-1 text-tertiary hover:bg-surface-secondary hover:text-critical">
                <Icon name="trash" size={13} />
              </button>
            )}
          </div>
          {problemsOf && <ProblemList problems={problemsOf(k)} className="mt-1" />}
        </div>
      ))}
      {!readOnly && (
        <button type="button" onClick={add} className="flex items-center gap-1 text-caption font-medium text-accent hover:underline">
          <Icon name="plus" size={12} />
          {addLabel}
        </button>
      )}
    </div>
  )
}

export interface ParameterFieldProps {
  spec: ParamSpec
  value: JsonValue | undefined
  onChange: (v: JsonValue | undefined) => void
  readOnly?: boolean
}

/** One parameter: label, Fixed / Expression, the control, its problems. */
export function ParameterField({ spec, value, onChange, readOnly }: ParameterFieldProps) {
  const ndv = useNdv()
  const id = useId()
  const canExpress = spec.expression ?? (spec.type !== 'object' && spec.type !== 'array')
  const [mode, setMode] = useState<'fixed' | 'expression'>(() => (isExpression(value) ? 'expression' : 'fixed'))
  const [focusExpr, setFocusExpr] = useState(false)
  const [over, setOver] = useState(false)
  const expression = canExpress && (mode === 'expression' || isExpression(value))
  const exact = spec.problemsExact
  const problems = [...problemsAt(ndv.problems, spec.problemField ?? spec.field), ...(exact ? ndv.problems.filter((p) => p.field !== undefined && exact.includes(p.field)) : [])]
  const invalid = problems.some((p) => p.level === 'error')

  const switchMode = (toExpr: boolean) => {
    if (toExpr === expression) return
    if (toExpr) {
      setMode('expression')
      setFocusExpr(true)
      const v = toExpression(value)
      onChange(v === '' ? undefined : v)
    } else {
      setMode('fixed')
      onChange(toFixed(value, spec.type))
    }
  }

  // a fixed control takes a dropped field by turning into that expression
  const accepts = (e: DragEvent) => canExpress && !readOnly && e.dataTransfer.types.includes(MAPPING_MIME)
  const dropProps = expression
    ? {}
    : {
        onDragOver: (e: DragEvent<HTMLDivElement>) => {
          if (!accepts(e)) return
          e.preventDefault()
          e.dataTransfer.dropEffect = 'copy'
          if (!over) setOver(true)
        },
        onDragLeave: () => setOver(false),
        onDrop: (e: DragEvent<HTMLDivElement>) => {
          setOver(false)
          ndv.setDragging(false)
          if (!accepts(e)) return
          e.preventDefault()
          setMode('expression')
          setFocusExpr(true)
          onChange(e.dataTransfer.getData('text/plain'))
        },
      }

  let control: ReactNode
  if (expression) {
    control = (
      <ExpressionInput
        id={id}
        value={typeof value === 'string' ? value : toExpression(value)}
        onChange={(v) => onChange(v === '' ? undefined : v)}
        ctx={spec.ctx}
        invalid={invalid}
        placeholder={spec.placeholder ?? '{{ $json.field }}'}
        ariaLabel={spec.label}
        autoFocus={focusExpr}
        readOnly={readOnly}
      />
    )
  } else if (spec.options || spec.type === 'enum') {
    const options: SelectOption[] = spec.options ?? []
    const current = value === undefined || value === null ? '' : String(value)
    const all = [...(!spec.required || current === '' ? [{ value: '', label: '—' }] : []), ...options, ...(current && !options.some((o) => o.value === current) ? [{ value: current, label: current, hint: 'not in the list' }] : [])]
    control = <Select value={current} onChange={(v) => onChange(v === '' ? undefined : v)} options={all} ariaLabel={spec.label} mono={spec.mono} disabled={readOnly} placeholder={spec.placeholder} />
  } else if (spec.type === 'boolean') {
    control = <Switch id={id} checked={value === true} onChange={(c) => onChange(c)} label={value === true ? 'On' : 'Off'} disabled={readOnly} />
  } else if (spec.type === 'number') {
    control = (
      <Input
        id={id}
        type="number"
        value={typeof value === 'number' ? String(value) : ''}
        placeholder={spec.placeholder}
        invalid={invalid}
        readOnly={readOnly}
        onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
      />
    )
  } else if (spec.type === 'object' || spec.type === 'array' || spec.type === 'any') {
    control = <JsonInput id={id} value={value} onChange={onChange} invalid={invalid} ariaLabel={spec.label} readOnly={readOnly} placeholder={spec.type === 'array' ? '[ ]' : spec.type === 'object' ? '{ }' : spec.placeholder} />
  } else if (spec.multiline) {
    control = (
      <Textarea
        id={id}
        rows={3}
        mono={spec.mono}
        value={typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)}
        placeholder={spec.placeholder}
        invalid={invalid}
        readOnly={readOnly}
        onChange={(e) => {
          const t = e.target.value
          if (canExpress && t.includes('{{')) {
            setMode('expression')
            setFocusExpr(true)
          }
          onChange(t === '' ? undefined : t)
        }}
      />
    )
  } else {
    const listId = spec.suggestions && spec.suggestions.length > 0 ? `${id}-list` : undefined
    control = (
      <>
        <Input
          id={id}
          value={typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)}
          placeholder={spec.placeholder}
          mono={spec.mono}
          invalid={invalid}
          readOnly={readOnly}
          list={listId}
          onChange={(e) => {
            const t = e.target.value
            if (canExpress && t.includes('{{')) {
              setMode('expression')
              setFocusExpr(true)
            }
            onChange(t === '' ? undefined : t)
          }}
        />
        {listId && (
          <datalist id={listId}>
            {spec.suggestions?.map((s) => <option key={s} value={s} />)}
          </datalist>
        )}
      </>
    )
  }

  return (
    <div className="group/param" data-field={spec.field}>
      <FieldLabel htmlFor={spec.type === 'enum' || spec.options ? undefined : id} label={spec.label} required={spec.required} description={spec.description} right={canExpress && !readOnly ? <ModeToggle expression={expression} onChange={switchMode} label={spec.label} /> : null} />
      <div className={cx('mt-1', !expression && ndv.dragging && canExpress && !readOnly && 'iaf-drop-target', over && 'is-over')} {...dropProps}>
        {control}
      </div>
      <ProblemList problems={problems} className="mt-1" />
    </div>
  )
}

/** A group of parameters under a small heading, like n8n's collections. */
export function ParamSection({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2 border-b border-stroke-light pb-1.5">
        <h3 className="text-caption2 font-semibold uppercase tracking-wide text-tertiary">{title}</h3>
        <span className="flex-1" />
        {actions}
      </div>
      {children}
    </section>
  )
}

export { asObject }
