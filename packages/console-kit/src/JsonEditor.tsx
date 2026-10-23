import { useId, useMemo, type ReactNode } from 'react'
import { Field, Textarea } from '@iaf/ui'

/*
 * A mono textarea that says when its JSON does not parse. Forked from the platform's
 * components/JsonEditor.tsx at c9529d5. Pair with parseJsonText() to read it.
 */

/** Parse what someone typed; an empty text is "nothing", not an error. */
export function parseJsonText(text: string): { value: unknown; error?: string; empty: boolean } {
  if (!text.trim()) return { value: undefined, empty: true }
  try {
    return { value: JSON.parse(text) as unknown, empty: false }
  } catch (e) {
    return { value: undefined, error: e instanceof SyntaxError ? e.message.replace(/^JSON\.parse: /, '') : String(e), empty: false }
  }
}

export interface JsonEditorProps {
  value: string
  onChange: (text: string) => void
  label?: ReactNode
  hint?: ReactNode
  required?: boolean
  rows?: number
  placeholder?: string
  disabled?: boolean
  id?: string
  /** the value must be an object (not a list or a scalar) */
  objectOnly?: boolean
  /** an error from outside (the server) shown instead of the parse error */
  error?: ReactNode
  className?: string
}

export function JsonEditor({ value, onChange, label, hint, required, rows = 6, placeholder = '{ }', disabled, id, objectOnly, error, className }: JsonEditorProps) {
  const auto = useId()
  const inputId = id ?? auto
  const parsed = useMemo(() => parseJsonText(value), [value])
  const localError = parsed.error ?? (objectOnly && !parsed.empty && (typeof parsed.value !== 'object' || parsed.value === null || Array.isArray(parsed.value)) ? 'Expected a JSON object' : undefined)
  return (
    <Field label={label} hint={hint ?? 'JSON'} error={error ?? localError} required={required} htmlFor={inputId} className={className}>
      <Textarea
        id={inputId}
        mono
        rows={rows}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        invalid={!!(error ?? localError)}
        spellCheck={false}
        autoComplete="off"
      />
    </Field>
  )
}
