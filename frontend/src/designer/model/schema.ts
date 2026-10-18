/**
 * JSON Schema helpers for the NDV: the fields of a parameter form, example values for the Input
 * pane, a schema inferred from a sample, and the flat path list the Schema/Table views show.
 */
import type { JsonObject, JsonValue } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'

export type FieldType = 'string' | 'number' | 'boolean' | 'enum' | 'object' | 'array' | 'any'

export interface FieldSpec {
  key: string
  title: string
  type: FieldType
  required: boolean
  description?: string
  enum?: string[]
  default?: JsonValue
  schema: Schema
  resource?: { type: string; orCreateWith?: string }
  format?: string
}

function typeOf(schema: Schema): FieldType {
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return 'enum'
  const t = Array.isArray(schema.type) ? schema.type.find((x) => x !== 'null') : schema.type
  switch (t) {
    case 'string':
      return 'string'
    case 'number':
    case 'integer':
      return 'number'
    case 'boolean':
      return 'boolean'
    case 'object':
      return 'object'
    case 'array':
      return 'array'
    default:
      return schema.properties ? 'object' : 'any'
  }
}

/** The form fields of an object schema, in schema order, required ones flagged. */
export function fieldsOf(schema: Schema | null | undefined): FieldSpec[] {
  if (!schema || typeof schema !== 'object' || !schema.properties) return []
  const required = new Set(Array.isArray(schema.required) ? schema.required : [])
  return Object.entries(schema.properties).map(([key, s]) => {
    const sub = (s && typeof s === 'object' ? s : {}) as Schema
    const spec: FieldSpec = { key, title: typeof sub.title === 'string' ? sub.title : humanize(key), type: typeOf(sub), required: required.has(key), schema: sub }
    if (typeof sub.description === 'string') spec.description = sub.description
    if (Array.isArray(sub.enum)) spec.enum = sub.enum.map((e) => String(e))
    if (sub.default !== undefined) spec.default = sub.default as JsonValue
    if (sub['x-resource'] && typeof sub['x-resource'] === 'object') spec.resource = sub['x-resource']
    if (typeof sub.format === 'string') spec.format = sub.format
    return spec
  })
}

/** `vmId` → `Vm id`, `max_parallel` → `Max parallel`. */
export function humanize(key: string): string {
  const s = key.replace(/[_-]+/g, ' ').replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** An example value for a schema: its default, its first enum value, else a typed placeholder. */
export function exampleOf(schema: Schema | null | undefined, key = 'value', depth = 0): JsonValue {
  if (!schema || typeof schema !== 'object') return null
  if (schema.default !== undefined) return schema.default as JsonValue
  if (schema.example !== undefined) return schema.example as JsonValue
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0] as JsonValue
  const t = typeOf(schema)
  switch (t) {
    case 'string':
      if (schema['x-resource'] && typeof schema['x-resource'] === 'object') return `${schema['x-resource'].type}-1`
      if (schema.format === 'date-time') return '2026-01-01T00:00:00Z'
      return key
    case 'number':
      return typeof schema.minimum === 'number' ? schema.minimum : 1
    case 'boolean':
      return true
    case 'array':
      return depth > 3 ? [] : [exampleOf(schema.items, key, depth + 1)]
    case 'object': {
      const out: JsonObject = {}
      if (depth > 3) return out
      for (const [k, s] of Object.entries(schema.properties ?? {})) out[k] = exampleOf(s, k, depth + 1)
      return out
    }
    default:
      return null
  }
}

/** A schema inferred from a value (the Schema view of a sample output). */
export function schemaOfValue(value: JsonValue | undefined): Schema {
  if (value === null || value === undefined) return { type: 'null' }
  if (Array.isArray(value)) return { type: 'array', items: value.length > 0 ? schemaOfValue(value[0]) : {} }
  if (typeof value === 'object') {
    const properties: Record<string, Schema> = {}
    for (const [k, v] of Object.entries(value)) properties[k] = schemaOfValue(v)
    return { type: 'object', properties }
  }
  if (typeof value === 'number') return { type: Number.isInteger(value) ? 'integer' : 'number' }
  return { type: typeof value as 'string' | 'boolean' }
}

export interface PathEntry {
  /** dotted path, `items[0].id` */
  path: string
  key: string
  type: string
  value: JsonValue | undefined
  depth: number
}

/** Every path of a value, depth first, for the Schema and Table views (arrays show their first item). */
export function flattenPaths(value: JsonValue | undefined, prefix = '', depth = 0, max = 400): PathEntry[] {
  const out: PathEntry[] = []
  const walk = (v: JsonValue | undefined, path: string, key: string, d: number) => {
    if (out.length >= max) return
    const type = v === null || v === undefined ? 'null' : Array.isArray(v) ? 'array' : typeof v
    if (path) out.push({ path, key, type, value: v, depth: d })
    if (Array.isArray(v)) {
      if (v.length > 0) walk(v[0], `${path}[0]`, '[0]', d + 1)
    } else if (v && typeof v === 'object') {
      for (const [k, c] of Object.entries(v)) walk(c, path ? `${path}.${k}` : k, k, d + (path ? 1 : 0))
    }
  }
  walk(value, prefix, '', depth)
  return out
}

/** The paths of a schema (when no sample exists yet): `items[0].id: string`. */
export function schemaPaths(schema: Schema | null | undefined, prefix = '', depth = 0): PathEntry[] {
  const out: PathEntry[] = []
  const walk = (s: Schema | null | undefined, path: string, key: string, d: number) => {
    if (!s || typeof s !== 'object' || out.length > 400) return
    const t = typeOf(s)
    if (path) out.push({ path, key, type: t === 'enum' ? 'string' : t === 'any' ? 'unknown' : t, value: undefined, depth: d })
    if (t === 'object') for (const [k, c] of Object.entries(s.properties ?? {})) walk(c, path ? `${path}.${k}` : k, k, d + (path ? 1 : 0))
    if (t === 'array') walk(s.items, `${path}[0]`, '[0]', d + 1)
  }
  walk(schema, prefix, '', depth)
  return out
}

/** The columns and rows of the Table view: an array of objects becomes rows, an object one row. */
export function tableOf(value: JsonValue | undefined): { columns: string[]; rows: JsonValue[][] } {
  const rows = Array.isArray(value) ? value : value === undefined ? [] : [value]
  const columns: string[] = []
  for (const r of rows) {
    if (r && typeof r === 'object' && !Array.isArray(r)) for (const k of Object.keys(r)) if (!columns.includes(k)) columns.push(k)
  }
  if (columns.length === 0) return { columns: ['value'], rows: rows.map((r) => [r ?? null]) }
  return { columns, rows: rows.map((r) => columns.map((c) => (r && typeof r === 'object' && !Array.isArray(r) ? (r[c] ?? null) : null))) }
}

/** Short text of a value for a cell. */
export function cellText(v: JsonValue | undefined): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}
