/**
 * The canvas never writes an empty key. An empty YAML value (`notes:`) reads as null in the
 * TypeScript checks, and the Workflow schema, the first thing they judge, refuses null wherever it
 * names a type (`schema.invalid`); the backend's reader takes it for an empty string instead, so
 * the two would disagree about the file. A null the canvas would write (a JSON field cleared to
 * `null`, a document pasted with an empty value) is therefore left out wherever the schema types
 * the key. Under keys the schema leaves loose (a call step's `with`, `set`, `api.input`,
 * `run.input`, `register.attributes`) and where it allows null (the values of `workflow.outputs`)
 * a null stays: there it is a value.
 */
import { WORKFLOW_SCHEMA, type JsonObject, type WorkflowDocument } from '@iaf/workflow-checks'

type Schema = Record<string, unknown>

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** What a `#/$defs/name` reference names in the Workflow schema. */
function deref(ref: string): unknown {
  let at: unknown = WORKFLOW_SCHEMA
  for (const segment of ref.replace(/^#\/?/, '').split('/').filter(Boolean)) at = isObj(at) ? at[segment.replaceAll('~1', '/').replaceAll('~0', '~')] : undefined
  return at
}

/** The schema itself when it names a type or properties, else what its `$ref` names (the schema's references carry only a description beside them). */
function resolved(schema: unknown): Schema | undefined {
  let s = schema
  for (let i = 0; i < 16 && isObj(s) && s.type === undefined && s.properties === undefined && typeof s.$ref === 'string'; i++) s = deref(s.$ref)
  return isObj(s) ? s : undefined
}

/** True when the schema names a type and null is not one of them. */
function refusesNull(schema: unknown): boolean {
  const type = resolved(schema)?.type
  if (type === undefined) return false
  return Array.isArray(type) ? !type.includes('null') : type !== 'null'
}

/** The schema of a key of an object the schema describes: its property, else its additionalProperties when that is a schema. */
function childOf(schema: Schema | undefined, key: string): unknown {
  if (!schema) return undefined
  const props = schema.properties
  if (isObj(props) && Object.hasOwn(props, key)) return props[key]
  return isObj(schema.additionalProperties) ? schema.additionalProperties : undefined
}

function prune(value: unknown, schema: unknown): unknown {
  const s = resolved(schema)
  if (Array.isArray(value)) {
    const items = s?.items
    let changed = false
    const out: unknown[] = []
    for (const v of value) {
      if (v === null && items !== undefined && refusesNull(items)) {
        changed = true
        continue
      }
      const next = items === undefined ? v : prune(v, items)
      if (next !== v) changed = true
      out.push(next)
    }
    return changed ? out : value
  }
  if (!isObj(value)) return value
  let out: Record<string, unknown> | null = null
  for (const [key, v] of Object.entries(value)) {
    const child = childOf(s, key)
    if (v === null && child !== undefined && refusesNull(child)) {
      out ??= { ...value }
      delete out[key]
      continue
    }
    if (child === undefined || v === null || typeof v !== 'object') continue
    const next = prune(v, child)
    if (next !== v) {
      out ??= { ...value }
      out[key] = next
    }
  }
  return out ?? value
}

/**
 * The document without a null under a key the Workflow schema types: the same object when there
 * is none, a copy otherwise (only the objects on the way to a null are copied).
 */
export function withoutEmptyKeys(doc: WorkflowDocument): WorkflowDocument {
  return prune(doc as unknown as JsonObject, WORKFLOW_SCHEMA) as WorkflowDocument
}
