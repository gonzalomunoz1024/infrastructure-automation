import { useMemo, useState, type ReactNode } from 'react'
import { Button, Checkbox, Field, Icon, IconButton, Input, Segmented, Select, Switch, Textarea, cx } from '@iaf/ui'
import { isValidWfrn } from '@iaf/wfrn'
import type { Problem, Schema, XConsole } from './types'
import { JsonEditor, parseJsonText } from './JsonEditor'
import { ResourcePicker } from './ResourcePicker'
import { fieldRoot, normalizeField, problemsFor } from './ProblemList'
import { groupFields, unitOf, xConsoleOf } from './xconsole'
import { ExternalLink, parseDuration } from './common'

/*
 * A form generated from a JSON Schema: strings, numbers, booleans, enums, lists of strings and
 * nested objects (as JSON) each get the fitting kit field; `x-resource` fields get the inventory
 * picker. Required markers, defaults and descriptions come from the schema; problems the server
 * names by field render under that field. Forked from the platform's components/SchemaForm.tsx
 * at c9529d5, plus the console's `x-console` keywords (P-06): `format` adds units, masks and
 * checks, `group` folds fields into fieldsets, `widget` picks a picker, slider, code or key-value
 * control, `help` links to a page, `mutable` decides what an update may change, and `sensitive`
 * masks the value.
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

/**
 * The two forms of an `x-resource` input with `orCreateWith`, as the catalog describes them:
 * `anyOf` the id of an existing resource, or `{ create: <the producing workflow's input> }` to
 * have one built first. Null for any other field.
 */
export interface CreateForm {
  /** the resource type (x-resource.type) */
  type: string
  /** the workflow that builds one */
  producer?: string
  /** the id branch: a string, with whatever pattern or enum the field had */
  idSchema: Schema
  /** the producer's input schema, the value of `create` */
  createSchema: Schema
  /** what the build branch says about itself */
  description?: string
}

export function createFormOf(prop: Schema | undefined): CreateForm | null {
  if (!prop || !Array.isArray(prop.anyOf)) return null
  const branches = prop.anyOf.filter((b): b is Schema => !!b && typeof b === 'object')
  const build = branches.find((b) => b.properties?.create && typeof b.properties.create === 'object')
  const createSchema = build?.properties?.create
  if (!build || !createSchema) return null
  const xr = prop['x-resource']
  return {
    type: xr?.type ?? 'resource',
    producer: xr?.orCreateWith,
    idSchema: branches.find((b) => b !== build) ?? { type: 'string' },
    createSchema,
    description: typeof build.description === 'string' ? build.description : undefined,
  }
}

/** True for `{ create: {...} }`: the requester asks for the resource to be built first. */
export function isCreateRequest(v: unknown): v is { create: SchemaValue } {
  return !!v && typeof v === 'object' && !Array.isArray(v) && 'create' in v
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

/** An example wfrn for messages and placeholders. */
export const WFRN_EXAMPLE = 'wfrn:wf:compute:global:123456789012:vm/vm-web-01'

/** The client-side checks a form can make before asking the server: required, enum, type, range, pattern, and the x-console formats. */
export function validateAgainst(schema: Schema | undefined, value: SchemaValue): Problem[] {
  const problems: Problem[] = []
  const props = schema?.properties ?? {}
  for (const name of schema?.required ?? []) {
    if (isEmpty(value[name])) problems.push({ level: 'error', code: 'required', field: name, message: `${props[name]?.title ?? name} is required` })
  }
  for (const [name, prop] of Object.entries(props)) {
    const v = value[name]
    if (isEmpty(v)) continue
    const createForm = createFormOf(prop)
    if (createForm && isCreateRequest(v)) {
      // the build request is judged against the producer's input, like the start does
      const inner = v.create && typeof v.create === 'object' && !Array.isArray(v.create) ? v.create : {}
      for (const p of validateAgainst(createForm.createSchema, inner)) problems.push({ ...p, field: `${name}.create${p.field ? `.${p.field}` : ''}` })
      continue
    }
    // an input with two forms is judged by its id branch when it holds an id
    const spec = createForm ? createForm.idSchema : prop
    const type = schemaType(spec)
    const label = prop.title ?? name
    const x = xConsoleOf(prop)
    if (spec.enum && !spec.enum.some((e) => e === v || String(e) === String(v))) {
      problems.push({ level: 'error', code: 'enum', field: name, message: `${label} must be one of ${spec.enum.map(String).join(', ')}` })
      continue
    }
    if ((type === 'integer' || type === 'number') && typeof v !== 'number') {
      problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be a number` })
      continue
    }
    if (type === 'integer' && typeof v === 'number' && !Number.isInteger(v)) problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be a whole number` })
    if (typeof v === 'number' && spec.minimum !== undefined && v < spec.minimum) problems.push({ level: 'error', code: 'minimum', field: name, message: `${label} must be at least ${spec.minimum}` })
    if (typeof v === 'number' && spec.maximum !== undefined && v > spec.maximum) problems.push({ level: 'error', code: 'maximum', field: name, message: `${label} must be at most ${spec.maximum}` })
    if (typeof v === 'number' && x.format === 'percent' && spec.minimum === undefined && spec.maximum === undefined && (v < 0 || v > 100)) {
      problems.push({ level: 'error', code: 'format', field: name, message: `${label} must be between 0 and 100` })
    }
    if (type === 'boolean' && typeof v !== 'boolean') problems.push({ level: 'error', code: 'type', field: name, message: `${label} must be true or false` })
    if (type === 'string' && typeof v === 'string') {
      if (spec.pattern) {
        try {
          if (!new RegExp(spec.pattern).test(v)) problems.push({ level: 'error', code: 'pattern', field: name, message: `${label} must match ${spec.pattern}` })
        } catch {
          /* an unreadable pattern is the server's to judge */
        }
      }
      const minLength = typeof spec.minLength === 'number' ? spec.minLength : undefined
      const maxLength = typeof spec.maxLength === 'number' ? spec.maxLength : undefined
      if (minLength !== undefined && v.length < minLength) problems.push({ level: 'error', code: 'minLength', field: name, message: `${label} must be at least ${minLength} characters` })
      if (maxLength !== undefined && v.length > maxLength) problems.push({ level: 'error', code: 'maxLength', field: name, message: `${label} must be at most ${maxLength} characters` })
      if (x.format === 'wfrn' && !isValidWfrn(v)) problems.push({ level: 'error', code: 'format', field: name, message: `${label} must be a wfrn, like ${WFRN_EXAMPLE}` })
      if (x.format === 'duration' && parseDuration(v) === null) problems.push({ level: 'error', code: 'format', field: name, message: `${label} must be a duration, like 30m or 2h` })
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

/** The note under a field an update cannot change. */
export const IMMUTABLE_NOTE = 'Set when it was created and cannot be changed.'

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
  /** the inventory picker for x-resource fields (default true) */
  pickers?: boolean
  /** leave these properties out (path params the page fills itself) */
  omit?: string[]
  /** nothing is required: a defaults form */
  optional?: boolean
  /** `update` disables every field the type does not mark `x-console.mutable` */
  mode?: 'create' | 'update'
  /** fold fields with the same `x-console.group` into a fieldset (default true) */
  groups?: boolean
  className?: string
}

type Row = { key: string; value: string }

function rowsOf(value: unknown): Row[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  return Object.entries(value as Record<string, unknown>).map(([key, v]) => ({ key, value: v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v) }))
}

function objectOf(rows: Row[]): Record<string, string> | undefined {
  const out: Record<string, string> = {}
  for (const r of rows) if (r.key.trim()) out[r.key.trim()] = r.value
  return Object.keys(out).length ? out : undefined
}

/** The `key-value` widget: an object of strings, one pair per row. */
function KeyValueEditor({ id, value, onChange, disabled, invalid, label }: { id: string; value: unknown; onChange: (v: Record<string, string> | undefined) => void; disabled?: boolean; invalid?: boolean; label: string }) {
  const [prev, setPrev] = useState(value)
  const [rows, setRows] = useState<Row[]>(() => rowsOf(value))
  if (value !== prev) {
    // the value changed from outside (a reset, a loaded record): start from it again, but keep a half-typed row
    setPrev(value)
    if (JSON.stringify(value ?? null) !== JSON.stringify(objectOf(rows) ?? null)) setRows(rowsOf(value))
  }
  const update = (next: Row[]) => {
    setRows(next)
    onChange(objectOf(next))
  }
  return (
    <div className="space-y-2" role="group" aria-label={label} data-widget="key-value">
      {rows.length === 0 && <p className="text-caption text-tertiary">No pairs yet.</p>}
      {rows.map((row, i) => (
        <div key={i} className="flex items-center gap-2">
          <Input id={i === 0 ? id : undefined} mono aria-label={`Key ${i + 1}`} value={row.key} placeholder="key" disabled={disabled} invalid={invalid} autoComplete="off" spellCheck={false} onChange={(e) => update(rows.map((r, j) => (j === i ? { ...r, key: e.target.value } : r)))} />
          <Input aria-label={`Value ${i + 1}`} value={row.value} placeholder="value" disabled={disabled} invalid={invalid} autoComplete="off" onChange={(e) => update(rows.map((r, j) => (j === i ? { ...r, value: e.target.value } : r)))} />
          <IconButton label={`Remove pair ${i + 1}`} size="sm" disabled={disabled} onClick={() => update(rows.filter((_, j) => j !== i))}>
            <Icon name="trash" size={14} />
          </IconButton>
        </div>
      ))}
      <Button size="sm" variant="capsule" icon="plus" disabled={disabled} onClick={() => update([...rows, { key: '', value: '' }])}>
        Add a pair
      </Button>
    </div>
  )
}

/** A masked input with a show/hide button, for `x-console.sensitive` fields. */
function SensitiveInput({ id, value, onChange, disabled, invalid }: { id: string; value: string; onChange: (v: string | undefined) => void; disabled?: boolean; invalid?: boolean }) {
  const [shown, setShown] = useState(false)
  return (
    <div className="flex items-center gap-2" data-sensitive="true">
      <Input id={id} type={shown ? 'text' : 'password'} mono value={value} onChange={(e) => onChange(e.target.value || undefined)} disabled={disabled} invalid={invalid} autoComplete="off" spellCheck={false} className="min-w-0 flex-1" />
      <IconButton label={shown ? 'Hide value' : 'Show value'} size="sm" active={shown} aria-pressed={shown} onClick={() => setShown((s) => !s)}>
        <Icon name="eye" size={15} />
      </IconButton>
    </div>
  )
}

function Adorned({ unit, children }: { unit?: string; children: ReactNode }) {
  if (!unit) return <>{children}</>
  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">{children}</div>
      <span className="shrink-0 text-caption text-secondary" data-unit={unit}>
        {unit}
      </span>
    </div>
  )
}

/** The form. An empty schema says so instead of rendering nothing. */
export function SchemaForm({ schema, value, onChange, problems, idPrefix = 'f', disabled, columns = 1, pickers = true, omit, optional, mode = 'create', groups = true, className }: SchemaFormProps) {
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

  const renderField = (name: string): ReactNode => {
    const prop = props[name]
    const id = `${idPrefix}-${name}`
    const fieldProblems = problemsFor(problems, name)
    const createForm = pickers ? createFormOf(prop) : null
    // a problem inside a build request (`image.create.os`) belongs to the inner form
    const createKey = `${name}.create`
    const own = createForm ? fieldProblems.filter((p) => normalizeField(p.field) === name || normalizeField(p.field) === createKey) : fieldProblems
    const error = own.find((p) => p.level === 'error')?.message ?? own[0]?.message
    const label = prop.title ?? name
    const type = schemaType(prop)
    const isReq = required.has(name)
    const x: XConsole = xConsoleOf(prop)
    const immutable = mode === 'update' && x.mutable !== true
    const fieldDisabled = disabled || immutable
    const common = { id, disabled: fieldDisabled, invalid: !!error }
    const v = value[name]
    const numeric = type === 'integer' || type === 'number'
    const unit = numeric ? unitOf(x.format, x.currency) : undefined
    const wide = type === 'object' || x.widget === 'key-value' || x.widget === 'code' || x.format === 'code' || (type === 'array' && !prop.items?.enum && (!prop.items || !['string', 'number', 'integer'].includes(schemaType(prop.items))))
    const hint: ReactNode =
      prop.description || x.help || immutable ? (
        <>
          {prop.description}
          {immutable && <span className={cx(prop.description && 'ml-1')}>{IMMUTABLE_NOTE}</span>}
          {x.help && (
            <>
              {' '}
              <ExternalLink href={x.help} ariaLabel={`Help for ${label}`} className="inline-flex items-center gap-0.5">
                Learn more
                <Icon name="external" size={10} />
              </ExternalLink>
            </>
          )}
        </>
      ) : undefined
    const stringValue = v === undefined || v === null ? '' : String(v)
    const pickerType = x.widget === 'picker' ? (x.pickerType ?? prop['x-resource']?.type) : undefined

    let control: ReactNode
    // a listbox or a group names itself (aria-label); the label points only at real inputs
    let labelFor: string | undefined = id
    if (createForm) {
      labelFor = undefined
      const building = isCreateRequest(v)
      const inner = building && v.create && typeof v.create === 'object' && !Array.isArray(v.create) ? v.create : {}
      const nested = fieldProblems
        .filter((p) => !own.includes(p))
        .map((p) => {
          const f = normalizeField(p.field) ?? ''
          return { ...p, field: f.startsWith(`${createKey}.`) ? f.slice(createKey.length + 1) : f }
        })
      control = (
        <div className="space-y-2" data-testid={`${id}-forms`}>
          <Segmented<'existing' | 'create'>
            ariaLabel={label}
            size="sm"
            value={building ? 'create' : 'existing'}
            onChange={(m) => {
              if ((m === 'create') === building) return
              set(name, m === 'create' ? { create: defaultsFor(createForm.createSchema) } : undefined)
            }}
            options={[
              { value: 'existing', label: `Use an existing ${createForm.type}` },
              { value: 'create', label: 'Build one first' },
            ]}
            className="max-w-full"
          />
          {building ? (
            <div className="space-y-3 rounded-md border border-stroke-light bg-surface-secondary/40 p-3">
              <p className="text-caption text-secondary">{createForm.description ?? `A new ${createForm.type} is built${createForm.producer ? ` with ${createForm.producer}` : ''} first; the job continues with its id.`}</p>
              <SchemaForm schema={createForm.createSchema} value={inner} onChange={(next) => set(name, { create: next })} problems={nested} idPrefix={`${id}-create`} disabled={fieldDisabled} columns={columns} />
            </div>
          ) : (
            <ResourcePicker id={id} type={createForm.type} value={typeof v === 'string' ? v : ''} onChange={(s) => set(name, s || undefined)} ariaLabel={`${label} id`} required={isReq} disabled={fieldDisabled} />
          )}
        </div>
      )
    } else if ((prop['x-resource'] || pickerType) && pickers) {
      labelFor = undefined
      const xr = (prop['x-resource'] ?? { type: pickerType! }) as { type: string; orCreateWith?: string; createWith?: string }
      control = <ResourcePicker id={id} type={pickerType ?? xr.type} value={typeof v === 'string' ? v : ''} onChange={(s) => set(name, s || undefined)} createWith={xr.orCreateWith ?? xr.createWith} ariaLabel={label} required={isReq} disabled={fieldDisabled} />
    } else if (prop.enum) {
      labelFor = undefined
      const options = prop.enum.map((e) => ({ value: String(e), label: String(e) }))
      control = (
        <Select
          ariaLabel={label}
          value={stringValue}
          onChange={(s) => {
            const raw = prop.enum?.find((e) => String(e) === s)
            set(name, s === '' ? undefined : (raw ?? s))
          }}
          options={isReq ? options : [{ value: '', label: 'Not set' }, ...options]}
          placeholder="Choose…"
          disabled={fieldDisabled}
        />
      )
    } else if (type === 'boolean') {
      control = (
        <div className="flex items-center gap-3 py-1">
          <Switch id={id} label={label} checked={v === true} onChange={(c) => set(name, c)} disabled={fieldDisabled} />
          <span className="text-sm text-secondary">{v === true ? 'Yes' : 'No'}</span>
        </div>
      )
    } else if (numeric && x.widget === 'slider') {
      const min = prop.minimum ?? 0
      const max = prop.maximum ?? 100
      const step = typeof prop.multipleOf === 'number' ? prop.multipleOf : type === 'integer' ? 1 : 'any'
      const n = typeof v === 'number' ? v : typeof prop.default === 'number' ? prop.default : min
      control = (
        <div className="flex items-center gap-3 py-1" data-widget="slider">
          <input
            id={id}
            type="range"
            min={min}
            max={max}
            step={step}
            value={n}
            disabled={fieldDisabled}
            aria-valuetext={`${n}${unit ? ` ${unit}` : ''}`}
            aria-invalid={error ? true : undefined}
            onChange={(e) => set(name, Number(e.target.value))}
            className="h-1.5 min-w-0 flex-1 cursor-pointer accent-accent disabled:cursor-not-allowed disabled:opacity-50"
          />
          <output htmlFor={id} className="w-20 shrink-0 text-right font-mono text-caption tabular-nums text-primary">
            {n}
            {unit ? ` ${unit}` : ''}
          </output>
        </div>
      )
    } else if (numeric) {
      control = (
        <Adorned unit={unit}>
          <Input
            {...common}
            type="number"
            inputMode={type === 'integer' ? 'numeric' : 'decimal'}
            step={x.format === 'cost' ? 0.01 : type === 'integer' ? 1 : 'any'}
            min={prop.minimum ?? (x.format === 'percent' ? 0 : undefined)}
            max={prop.maximum ?? (x.format === 'percent' ? 100 : undefined)}
            value={stringValue}
            onChange={(e) => {
              const t = e.target.value
              if (t === '') set(name, undefined)
              else {
                const num = Number(t)
                set(name, Number.isFinite(num) ? num : t)
              }
            }}
            placeholder={prop.default !== undefined ? String(prop.default) : undefined}
          />
        </Adorned>
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
                disabled={fieldDisabled}
                checked={chosen.includes(s)}
                onChange={(c) => {
                  const next = c ? [...chosen, s] : chosen.filter((y) => y !== s)
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
            const t = e.target.value
            if (!t.trim()) set(name, undefined)
            else set(name, t.split(',').map((s) => (schemaType(items) === 'string' ? s.trim() : coerceScalar(items, s))))
          }}
          placeholder="one, two, three"
        />
      )
    } else if (x.widget === 'key-value' && (type === 'object' || type === 'string')) {
      labelFor = undefined
      control = <KeyValueEditor id={id} label={label} value={v} onChange={(next) => set(name, next)} disabled={fieldDisabled} invalid={!!error} />
    } else if (type === 'array' || type === 'object') {
      control = (
        <JsonEditor
          id={id}
          value={typeof v === 'string' ? v : v === undefined ? '' : JSON.stringify(v, null, 2)}
          onChange={(t) => {
            const parsed = parseJsonText(t)
            set(name, parsed.empty ? undefined : parsed.error ? t : parsed.value)
          }}
          objectOnly={type === 'object'}
          rows={x.widget === 'code' ? 8 : 4}
          placeholder={type === 'object' ? '{ }' : '[ ]'}
          disabled={fieldDisabled}
          error={error}
          hint={hint ?? (type === 'object' ? 'A JSON object' : 'A JSON list')}
          required={isReq}
          label={label}
        />
      )
      return (
        <div key={name} className={cx(wide && columns === 2 && 'md:col-span-2')}>
          {control}
        </div>
      )
    } else if (x.sensitive === true || prop.format === 'password' || prop['x-secret'] === true) {
      control = <SensitiveInput id={id} value={typeof v === 'string' ? v : ''} onChange={(s) => set(name, s)} disabled={fieldDisabled} invalid={!!error} />
    } else if (x.widget === 'code' || x.format === 'code') {
      control = <Textarea {...common} mono rows={6} value={stringValue} onChange={(e) => set(name, e.target.value || undefined)} placeholder={prop.default !== undefined ? String(prop.default) : undefined} spellCheck={false} autoComplete="off" data-widget="code" />
    } else if (x.format === 'wfrn') {
      control = <Input {...common} mono value={stringValue} onChange={(e) => set(name, e.target.value || undefined)} placeholder={WFRN_EXAMPLE} autoComplete="off" spellCheck={false} data-format="wfrn" />
    } else if (x.format === 'duration') {
      control = <Input {...common} value={stringValue} onChange={(e) => set(name, e.target.value || undefined)} placeholder="30m, 2h, 1d" autoComplete="off" spellCheck={false} data-format="duration" />
    } else if (prop.format === 'date' || x.format === 'date') {
      control = <Input {...common} type="date" value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} />
    } else if (prop.format === 'date-time') {
      control = <Input {...common} type="datetime-local" value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} />
    } else if (prop['x-multiline'] === true || (typeof prop.maxLength === 'number' && prop.maxLength > 200)) {
      control = <Textarea {...common} rows={3} value={typeof v === 'string' ? v : ''} onChange={(e) => set(name, e.target.value || undefined)} placeholder={prop.description} />
    } else {
      control = (
        <Input
          {...common}
          type={prop.format === 'email' ? 'email' : prop.format === 'uri' || prop.format === 'url' ? 'url' : 'text'}
          value={stringValue}
          onChange={(e) => set(name, e.target.value || undefined)}
          placeholder={prop.default !== undefined ? String(prop.default) : prop.pattern ? String(prop.pattern) : undefined}
          autoComplete="off"
          spellCheck={false}
        />
      )
    }

    return (
      <Field key={name} label={label} required={isReq} htmlFor={labelFor} error={error} hint={hint} className={cx(wide && columns === 2 && 'md:col-span-2')}>
        {control}
      </Field>
    )
  }

  const grouped = groups ? groupFields(schema, names) : names.map((n) => ({ group: null, names: [n] }))

  return (
    <div className={cx('grid grid-cols-1 gap-4', columns === 2 && 'md:grid-cols-2', className)}>
      {grouped.map((g) =>
        g.group === null ? (
          renderField(g.names[0])
        ) : (
          <fieldset key={`group:${g.group}`} className={cx('min-w-0 rounded-md border border-stroke-light p-4', columns === 2 && 'md:col-span-2')} data-group={g.group}>
            <legend className="px-1 text-caption font-semibold uppercase tracking-wide text-secondary">{g.group}</legend>
            <div className={cx('grid grid-cols-1 gap-4', columns === 2 && 'md:grid-cols-2')}>{g.names.map(renderField)}</div>
          </fieldset>
        ),
      )}
    </div>
  )
}

/** The problems a 400 carried, keyed for the form (those on a field, or inside one like `image.create.os`) and the rest. */
export function splitProblems(problems: Problem[] | undefined, schema: Schema | undefined): { fielded: Problem[]; general: Problem[] } {
  const names = fieldNames(schema)
  const fielded: Problem[] = []
  const general: Problem[] = []
  for (const p of problems ?? []) {
    const f = fieldRoot(p.field)
    if (f && names.includes(f)) fielded.push(p)
    else general.push(p)
  }
  return { fielded, general }
}
