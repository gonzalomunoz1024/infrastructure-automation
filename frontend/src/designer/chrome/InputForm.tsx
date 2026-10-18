/** A form for a JSON object against its schema: the workflow input of a test run, a trigger's input. */
import { useState } from 'react'
import { Field, Input, Select, Switch, Textarea } from '@iaf/ui'
import type { JsonObject, JsonValue } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { fieldsOf, type FieldSpec } from '../model/schema'

function serialize(value: JsonValue | undefined): string {
  return value === undefined ? '' : JSON.stringify(value, null, 2)
}

/** Whether the text already says the value, or is mid-edit (not valid JSON yet, so it must not be lost). */
function describes(text: string, value: JsonValue | undefined): boolean {
  if (!text.trim()) return false
  try {
    return JSON.stringify(JSON.parse(text)) === JSON.stringify(value ?? null)
  } catch {
    return true
  }
}

export function JsonField({ id, label, value, onChange, required, hint, rows = 4 }: { id: string; label: string; value: JsonValue | undefined; onChange: (v: JsonValue | undefined) => void; required?: boolean; hint?: string; rows?: number }) {
  const [text, setText] = useState(() => serialize(value))
  const [error, setError] = useState<string | null>(null)
  // a value changed from outside replaces the text (adjusted during render, React's derived-state pattern)
  const [shown, setShown] = useState(value)
  if (value !== shown) {
    setShown(value)
    if (!describes(text, value)) setText(serialize(value))
  }
  return (
    <Field label={label} required={required} hint={hint} error={error} htmlFor={id}>
      <Textarea
        id={id}
        mono
        rows={rows}
        value={text}
        invalid={!!error}
        spellCheck={false}
        onChange={(e) => {
          const t = e.target.value
          setText(t)
          if (!t.trim()) {
            setError(null)
            onChange(undefined)
            return
          }
          try {
            onChange(JSON.parse(t) as JsonValue)
            setError(null)
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Not valid JSON')
          }
        }}
      />
    </Field>
  )
}

function InputField({ spec, value, onChange, id }: { spec: FieldSpec; value: JsonValue | undefined; onChange: (v: JsonValue | undefined) => void; id: string }) {
  const hint = spec.description ?? (spec.resource ? `The id of a ${spec.resource.type}` : undefined)
  switch (spec.type) {
    case 'enum':
      return (
        <Field label={spec.title} required={spec.required} hint={hint}>
          <Select ariaLabel={spec.title} value={value === null || value === undefined ? '' : String(value)} onChange={(v) => onChange(v)} options={(spec.enum ?? []).map((e) => ({ value: e, label: e }))} placeholder="Choose…" />
        </Field>
      )
    case 'boolean':
      return (
        <Field label={spec.title} hint={hint} inline>
          <Switch checked={value === true} onChange={(v) => onChange(v)} label={spec.title} />
        </Field>
      )
    case 'number':
      return (
        <Field label={spec.title} required={spec.required} hint={hint} htmlFor={id}>
          <Input id={id} type="number" value={typeof value === 'number' ? value : ''} onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))} />
        </Field>
      )
    case 'object':
    case 'array':
    case 'any':
      return <JsonField id={id} label={spec.title} value={value} onChange={onChange} required={spec.required} hint={hint} rows={3} />
    default:
      return (
        <Field label={spec.title} required={spec.required} hint={hint} htmlFor={id}>
          <Input id={id} mono={!!spec.resource} value={value === null || value === undefined ? '' : typeof value === 'string' ? value : JSON.stringify(value)} onChange={(e) => onChange(e.target.value)} />
        </Field>
      )
  }
}

export function InputForm({ schema, value, onChange, idPrefix }: { schema: Schema | undefined; value: JsonObject; onChange: (v: JsonObject) => void; idPrefix: string }) {
  const fields = fieldsOf(schema)
  if (fields.length === 0) return <p className="text-footnote text-tertiary">This workflow takes no input.</p>
  return (
    <div className="space-y-4">
      {fields.map((f) => (
        <InputField
          key={f.key}
          id={`${idPrefix}-${f.key}`}
          spec={f}
          value={value[f.key]}
          onChange={(v) => {
            const next: JsonObject = { ...value }
            if (v === undefined) delete next[f.key]
            else next[f.key] = v
            onChange(next)
          }}
        />
      ))}
    </div>
  )
}
