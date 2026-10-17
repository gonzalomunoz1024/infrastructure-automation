import { useMemo, type ReactNode } from 'react'
import { Checkbox, Field, Input, Select, Switch, Textarea, cx } from '@iaf/ui'
import type { Problem, Schema } from '../lib/types'
import { JsonEditor, parseJsonText } from './JsonEditor'
import { ResourcePicker } from './ResourcePicker'
import { normalizeField, problemsFor } from './ProblemList'

/*
 * A form generated from a JSON Schema: the catalog run form, the provider "Try action" drawer,
 * the wizard's input step and time travel all use it. Strings, numbers, booleans, enums, lists
 * of strings and nested objects (as JSON) each get the fitting kit field; `x-resource` fields
 * get the registry picker. Required markers, defaults and descriptions come from the schema;
 * problems the server names by field render under that field.
 */

export type SchemaValue = Record<string, unknown>

/** The first declared type, 'string' when the schema says nothing. */
export function schemaType(s: Schema): string {
  const t = Array.isArray(s.type) ? s.type.find((x) => x !== 'null') : s.type
  if (t) return t
  if (s.enum) return 'string'
  if (s.properties) return 'object'
  if (s.items) return 'array'
  return 'string'
}

/** The values a fresh form starts with: every property's `default`. */
export function defaultsFor(schema: Schema | undefined): SchemaValue {
  const out: SchemaValue = {}
  for (const [name, prop] of Object.entries(schema?.properties ?? {})) if (prop.default !== undefined) out[name] = prop.default
  return out
}

/** Property names in schema order. */
export function fieldNames(schema: Schema | undefined): string[] {
  return Object.keys(schema?.properties ?? {})
}

/** A CSV cell (text) as the typed value the schema wants. */
export function coerceScalar(prop: Schema | undefined, raw: string): unknown {
  const type = prop ? schemaType(prop) : 'string'
  const text = raw.trim()
  if (type === 'integer' || type === 'number') {
    if (text === '') return undefined
    const n = Number(text)
    return Number.isFinite(n) ? n : raw
  }
  if (type === 'boolean') {
    if (/^(true|yes|1|on)$/i.test(text)) return true
    if (/^(false|no|0|off)$/i.test(text)) return false
    return text === '' ? undefined : raw
  }
  if (type === 'array') {
    if (text === '') return undefined
    if (text.startsWith('[')) {
      const parsed = parseJsonText(text)
      if (!parsed.error) return parsed.value
    }
    const itemType = prop?.items ? schemaType(prop.items) : 'string'
    return text.split(/[,;|]/).map((s) => (itemType === 'string' ? s.trim() : coerceScalar(prop?.items, s)))
  }
  if (type === 'object') {
    const parsed = parseJsonText(text)
    return parsed.error ? raw : parsed.value
  }
  return raw
}

/** Every cell of a CSV row, typed by the schema. */
export function coerceBySchema(schema: Schema | undefined, row: Record<string, string>): SchemaValue {
  const out: SchemaValue = {}
  for (const [k, v] of Object.entries(row)) {
    const value = coerceScalar(schema?.properties?.[k], v)
    if (value !== undefined) out[k] = value
  }
  return out
}

function isEmpty(v: unknown): boolean {
  return v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)
}

/** The client-side checks a form can make before asking the server: required, enum, type, range, pattern. */
export function validateAgainst(schema: Schema | undefined, value: SchemaValue): Problem[] {
  const problems: Problem[] = []
  const props = schema?.properties ?? {}
  for (const name of schema?.required ?? []) {
    if (isEmpty(value[name])) problems.push({ level: 'error', code: 'required', field: name, message: `${props[name]?.title ?? name} is required` })
  }
  for (const [name, prop] of Object.entries(props)) {
    const v = value[name]
    if (isEmpty(v)) continue
    const type = schemaType(prop)
    const label = prop.title ?? name
    if (prop.enum && !prop.enum.some((e) => e === v || String(e) === String(v))) {
      problems.push({ level: 'error', code: 'enum', field: name, message: `${label} must be one of ${prop.enum.map(String).join(', ')}` })
      continue
    }
    if ((type === 'integer' || type === 'number') && typeof v !== 'number') {
      problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be a number` })
      continue
    }
    if (type === 'integer' && typeof v === 'number' && !Number.isInteger(v)) problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be a whole number` })
    if (typeof v === 'number' && prop.minimum !== undefined && v < prop.minimum) problems.push({ level: 'error', code: 'minimum', field: name, message: `${label} must be at least ${prop.minimum}` })
    if (typeof v === 'number' && prop.maximum !== undefined && v > prop.maximum) problems.push({ level: 'error', code: 'maximum', field: name, message: `${label} must be at most ${prop.maximum}` })
    if (type === 'boolean' && typeof v !== 'boolean') problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be true or false` })
    if (type === 'string' && typeof v === 'string') {
      if (prop.pattern) {
        try {
          if (!new RegExp(prop.pattern).test(v)) problems.push({ level: 'error', code: 'pattern', field: name, message: `${label} must match ${prop.pattern}` })
        } catch {
          /* an unreadable pattern is the server's to judge */
        }
      }
      const minLength = typeof prop.minLength === 'number' ? prop.minLength : undefined
      const maxLength = typeof prop.maxLength === 'number' ? prop.maxLength : undefined
      if (minLength !== undefined && v.length < minLength) problems.push({ level: 'error', code: 'minLength', field: name, message: `${label} must be at least ${minLength} characters` })
      if (maxLength !== undefined && v.length > maxLength) problems.push({ level: 'error', code: 'maxLength', field: name, message: `${label} must be at most ${maxLength} characters` })
    }
    if (type === 'array' && !Array.isArray(v)) problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be a list` })
    if (type === 'object' && (typeof v !== 'object' || Array.isArray(v))) problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be an object` })
  }
  return problems
}

/** True when every required field has a value. */
export function isComplete(schema: Schema | undefined, value: SchemaValue): boolean {
  return (schema?.required ?? []).every((name) => !isEmpty(value[name]))
}

export interface SchemaFormProps {
  schema: Schema | undefined
  value: SchemaValue
  onChange: (next: SchemaValue) => void
  /** problems from the server (or validateAgainst); the ones naming a field show under it */
  problems?: Problem[]
  idPrefix?: string
  disabled?: boolean
  /** two columns on wide screens */
  columns?: 1 | 2
  /** the registry picker for x-resource fields (default true) */
  pickers?: boolean
  /** leave these properties out (path params the page fills itself) */
  omit?: string[]
  /** nothing is required: a defaults form */
  optional?: boolean
  className?: string
}

/** The form. An empty schema says so instead of rendering nothing. */
export function SchemaForm({ schema, value, onChange, problems, idPrefix = 'f', disabled, columns = 1, pickers = true, omit, optional, className }: SchemaFormProps) {
  const props = schema?.properties ?? {}
  const required = useMemo(() => new Set(optional ? [] : (schema?.required ?? [])), [schema, optional])
  const names = Object.keys(props).filter((n) => !omit?.includes(n))

  const set = (name: string, v: unknown) => {
    const next = { ...value }
    if (v === undefined) delete next[name]
    else next[name] = v
    onChange(next)
  }

  if (names.length === 0) {
    return <p className={cx('text-caption text-tertiary', className)}>This workflow takes no input.</p>
  }

  return (
    <div className={cx('grid grid-cols-1 gap-4', columns === 2 && 'md:grid-cols-2', className)}>
      {names.map((name) => {
        const prop = props[name]
        const id = `${idPrefix}-${name}`
        const fieldProblems = problemsFor(problems, name)
        const error = fieldProblems.find((p) => p.level === 'error')?.message ?? fieldProblems[0]?.message
        const label = prop.title ?? name
        const type = schemaType(prop)
        const isReq = required.has(name)
        const common = { id, disabled, invalid: !!error }
        const v = value[name]
        const wide = type === 'object' || (type === 'array' && !prop.items?.enum && (!prop.items || !['string', 'number', 'integer'].includes(schemaType(prop.items))))

        let control: ReactNode
        // a listbox or a group names itself (aria-label); the label points only at real inputs
        let labelFor: string | undefined = id
        if (prop['x-resource'] && pickers) {
          labelFor = undefined
          const xr = prop['x-resource'] as { type: string; orCreateWith?: string; createWith?: string }
          control = <ResourcePicker id={id} type={xr.type} value={typeof v === 'string' ? v : ''} onChange={(s) => set(name, s || undefined)} createWith={xr.orCreateWith ?? xr.createWith} ariaLabel={label} required={isReq} disabled={disabled} />
        } else if (prop.enum) {
          labelFor = undefined
          const options = prop.enum.map((e) => ({ value: String(e), label: String(e) }))
          control = (
            <Select
              ariaLabel={label}
              value={v === undefined || v === null ? '' : String(v)}
              onChange={(s) => {
                const raw = prop.enum?.find((e) => String(e) === s)
                set(name, s === '' ? undefined : (raw ?? s))
              }}
              options={isReq ? options : [{ value: '', label: 'Not set' }, ...options]}
              placeholder="Choose…"
              disabled={disabled}
            />
          )
        } else if (type === 'boolean') {
          control = (
            <div className="flex items-center gap-3 py-1">
              <Switch id={id} label={label} checked={v === true} onChange={(c) => set(name, c)} disabled={disabled} />
              <span className="text-sm text-secondary">{v === true ? 'Yes' : 'No'}</span>
            </div>
          )
        } else if (type === 'integer' || type === 'number') {
          control = (
            <Input
              {...common}
              type="number"
              inputMode={type === 'integer' ? 'numeric' : 'decimal'}
              step={type === 'integer' ? 1 : 'any'}
              min={prop.minimum}
              max={prop.maximum}
              value={v === undefined || v === null ? '' : String(v)}
              onChange={(e) => {
                const t = e.target.value
                if (t === '') set(name, undefined)
                else {
                  const n = Number(t)
                  set(name, Number.isFinite(n) ? n : t)
                }
              }}
              placeholder={prop.default !== undefined ? String(prop.default) : undefined}
            />
          )
        } else if (type === 'array' && prop.items?.enum) {
          const chosen = Array.isArray(v) ? v.map(String) : []
          labelFor = undefined
          control = (
            <div className="flex flex-wrap gap-x-5 gap-y-2 py-1" role="group" aria-label={label}>
              {prop.items.enum.map((e) => {
                const s = String(e)
                return (
                  <Checkbox
                    key={s}
                    label={s}
                    disabled={disabled}
                    checked={chosen.includes(s)}
                    onChange={(c) => {
                      const next = c ? [...chosen, s] : chosen.filter((x) => x !== s)
                      set(name, next.length ? next : undefined)
                    }}
                  />
                )
              })}
            </div>
          )
        } else if (type === 'array' && prop.items && ['string', 'number', 'integer'].includes(schemaType(prop.items))) {
          const items = prop.items
          control = (
            <Input
              {...common}
              value={Array.isArray(v) ? v.join(', ') : typeof v === 'string' ? v : ''}
              onChange={(e) => {
                const text = e.target.value
                if (!text.trim()) set(name, undefined)
                else set(name, text.split(',').map((s) => (schemaType(items) === 'string' ? s.trim() : coerceScalar(items, s))))
              }}
              placeholder="one, two, three"
            />
          )
        } else if (type === 'array' || type === 'object') {
          control = (
            <JsonEditor
              id={id}
              value={typeof v === 'string' ? v : v === undefined ? '' : JSON.stringify(v, null, 2)}
              onChange={(text) => {
                const parsed = parseJsonText(text)
                set(name, parsed.empty ? undefined : parsed.error ? text : parsed.value)
              }}
              objectOnly={type === 'object'}
              rows={4}
              placeholder={type === 'object' ? '{ }' : '[ ]'}
              disabled={disabled}
              error={error}
              hint={prop.description ?? (type === 'object' ? 'A JSON object' : 'A JSON list')}
              required={isReq}
              label={label}
            />
          )
          return (
            <div key={name} className={cx(wide && columns === 2 && 'md:col-span-2')}>
              {control}
            </div>
          )
        } else if (prop.format === 'date') {
          control = <Input {...common} type="date" value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} />
        } else if (prop.format === 'date-time') {
          control = <Input {...common} type="datetime-local" value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} />
        } else if (prop.format === 'password' || prop['x-secret'] === true) {
          control = <Input {...common} type="password" value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} autoComplete="off" />
        } else if (prop['x-multiline'] === true || (typeof prop.maxLength === 'number' && prop.maxLength > 200)) {
          control = <Textarea {...common} rows={3} value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} placeholder={prop.description} />
        } else {
          control = (
            <Input
              {...common}
              type={prop.format === 'email' ? 'email' : prop.format === 'uri' || prop.format === 'url' ? 'url' : 'text'}
              value={v === undefined || v === null ? '' : String(v)}
              onChange={(e) => set(name, e.target.value || undefined)}
              placeholder={prop.default !== undefined ? String(prop.default) : prop.pattern ? String(prop.pattern) : undefined}
              autoComplete="off"
              spellCheck={false}
            />
          )
        }

        return (
          <Field key={name} label={label} required={isReq} htmlFor={labelFor} error={error} hint={prop.description} className={cx(wide && columns === 2 && 'md:col-span-2')}>
            {control}
          </Field>
        )
      })}
    </div>
  )
}

/** The problems a 400 carried, keyed for the form (those with a field) and the rest. */
export function splitProblems(problems: Problem[] | undefined, schema: Schema | undefined): { fielded: Problem[]; general: Problem[] } {
  const names = fieldNames(schema)
  const fielded: Problem[] = []
  const general: Problem[] = []
  for (const p of problems ?? []) {
    const f = normalizeField(p.field)
    if (f && names.includes(f)) fielded.push(p)
    else general.push(p)
  }
  return { fielded, general }
}
