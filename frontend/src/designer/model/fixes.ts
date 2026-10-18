/**
 * The one-click fixes of a Problem: `replace` writes the value where the problem points (the
 * field's value, a name inside an expression, a misspelt key, the x-resource of an input),
 * `insert-approval` puts an approval step before the step, `connect` is answered by the canvas
 * (it opens the node creator in front of the step), so applyFix returns null for it. A step that
 * never runs gets a fix of the designer's own: remove it (the store applies that one).
 */
import { COMMON_KEYS, STEP_TYPES, type Fix, type JsonObject, type JsonValue, type Problem, type StepBody, type StepDocument, type WorkflowDocument } from '@iaf/workflow-checks'
import { findStepLocation } from './graph'

export function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
}

/** A fix only the designer knows: remove the step the problem names. */
export interface RemoveStepFix {
  label: string
  kind: 'remove-step'
}

export type DesignerFix = Fix | RemoveStepFix

/** The fix offered for a problem: the check's own, else the designer's for a step that never runs. */
export function fixOf(problem: Problem): DesignerFix | null {
  if (problem.fix) return problem.fix
  if (problem.code === 'step.unreachable' && problem.step) return { label: `Remove '${problem.step}'`, kind: 'remove-step' }
  return null
}

type PathPart = string | number

/** `api.input.vmId`, `switch[0].when`, `triggers[1].input` → segments. */
export function parsePath(path: string): PathPart[] {
  const out: PathPart[] = []
  for (const seg of path.split('.')) {
    if (!seg) continue
    const m = /^([^[]*)((?:\[\d+\])*)$/.exec(seg)
    if (!m) {
      out.push(seg)
      continue
    }
    if (m[1]) out.push(m[1])
    for (const idx of m[2].matchAll(/\[(\d+)\]/g)) out.push(Number(idx[1]))
  }
  return out
}

/** Sets a value at a path inside an object/array tree, creating objects on the way. */
export function setAtPath(root: JsonObject, path: PathPart[], value: JsonValue): void {
  let cur: JsonObject | JsonValue[] = root
  for (let i = 0; i < path.length - 1; i++) {
    const key = path[i]
    const nextKey = path[i + 1]
    const container = cur as Record<string | number, JsonValue | undefined>
    let next = container[key]
    if (next === undefined || next === null || typeof next !== 'object') {
      next = typeof nextKey === 'number' ? [] : {}
      container[key] = next
    }
    cur = next as JsonObject | JsonValue[]
  }
  ;(cur as Record<string | number, JsonValue>)[path[path.length - 1]] = value
}

export function getAtPath(root: JsonValue | undefined, path: PathPart[]): JsonValue | undefined {
  let cur: JsonValue | undefined = root
  for (const key of path) {
    if (cur === null || cur === undefined || typeof cur !== 'object') return undefined
    cur = (cur as Record<string | number, JsonValue | undefined>)[key]
  }
  return cur
}

/** Where a step problem's field lives: in the body (common keys) or in the step's own block. */
export function stepFieldPath(body: StepBody, field: string): PathPart[] {
  const parts = parsePath(field)
  const first = parts[0]
  if (typeof first === 'string' && COMMON_KEYS.includes(first) && first !== 'with') return parts
  const type = stepTypeOfBody(body)
  if (type === null) return ['with', ...parts]
  const info = STEP_TYPES[type]
  if (info.isCall) return ['with', ...parts]
  if (type === 'switch' && first === 'switch' && typeof parts[1] === 'number') {
    const i = parts[1]
    const entry = Array.isArray(body.switch) ? body.switch[i] : undefined
    const rest = parts.slice(2)
    if (entry && entry.default !== undefined) return ['switch', i, 'default', ...rest]
    return ['switch', i, 'case', ...rest]
  }
  if (type === 'wait' && typeof body.wait === 'string') return ['wait']
  return [info.yamlKey, ...parts]
}

export function stepTypeOfBody(body: StepBody): keyof typeof STEP_TYPES | null {
  for (const k of Object.keys(body)) {
    if (COMMON_KEYS.includes(k)) continue
    if (k === 'call') {
      const kind = body.call
      return typeof kind === 'string' && kind in STEP_TYPES ? (kind as keyof typeof STEP_TYPES) : null
    }
    if (k in STEP_TYPES) return k as keyof typeof STEP_TYPES
  }
  return null
}

/** The step id an approval inserted before `stepId` gets. */
export function approvalIdFor(stepId: string, taken: Set<string>): string {
  const base = `approve${stepId.charAt(0).toUpperCase()}${stepId.slice(1)}`
  let id = base
  let n = 2
  while (taken.has(id)) id = `${base}${n++}`
  return id
}

/** The document with the fix applied, or null when the fix is not one the model can apply. */
const isObject = (v: unknown): v is JsonObject => typeof v === 'object' && v !== null && !Array.isArray(v)

const escapeRegExp = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The quoted name a message ends with: "The previous step's output has no field 'nmae'" → nmae. */
function quoted(message: string): string | null {
  const m = /'([^']+)'\s*$/.exec(message)
  return m ? m[1] : null
}

/**
 * What a `replace` fix means, by the code of its problem. Most replace the field's value; the
 * expression checks name a token inside the field's text (a did-you-mean keeps the rest of the
 * expression); the unknown-input checks name a key to rename; the api.input checks point at the
 * property whose x-resource the value belongs to.
 */
type Replacement = { kind: 'value' } | { kind: 'token'; pattern: RegExp; replace: (match: string, ...groups: string[]) => string } | { kind: 'rename' } | { kind: 'x-resource'; key: 'type' | 'orCreateWith' }

function replacementOf(problem: Problem, value: string): Replacement | null {
  const { code, message } = problem
  const token = (pattern: RegExp, replace: (match: string, ...groups: string[]) => string): Replacement => ({ kind: 'token', pattern, replace })
  switch (code) {
    case 'expr.unknownField': {
      // "$vm has no field 'nmae'": the wrong path and whatever follows it, under the root the message names
      const wrong = quoted(message)
      if (!wrong) return null
      const root = message.startsWith('$') ? escapeRegExp(message.split(' ')[0]) : ''
      return token(new RegExp(`(${root})\\.${escapeRegExp(wrong)}(?:\\.\\w+)*(?!\\w)`), (_m, r) => `${r}.${value}`)
    }
    case 'expr.unknownReference': {
      // "Unknown reference $vmm; …" → the fix carries the $-prefixed alias
      const m = /\$(\w+)/.exec(message)
      if (!m) return null
      return token(new RegExp(`\\$${escapeRegExp(m[1])}(?!\\w)`), () => value)
    }
    case 'expr.unknownStep': {
      // "No step named 'nope'" inside $('nope')
      const wrong = quoted(message)
      if (!wrong) return null
      return token(new RegExp(`(['"])${escapeRegExp(wrong)}\\1`), (_m, q) => `${q}${value}${q}`)
    }
    case 'expr.unknownInput': {
      const wrong = quoted(message)
      if (!wrong) return null
      return token(new RegExp(`\\$input\\.${escapeRegExp(wrong)}(?!\\w)`), () => `$input.${value}`)
    }
    case 'expr.unknownVar': {
      const m = /\$vars\.(\w+)/.exec(message)
      if (!m) return null
      return token(new RegExp(`\\$vars\\.${escapeRegExp(m[1])}(?!\\w)`), () => `$vars.${value}`)
    }
    case 'step.unknownInput':
    case 'contract.unknownInput':
      return { kind: 'rename' }
    case 'resource.unknownType':
      return !problem.step && /^api\.input\./.test(problem.field ?? '') ? { kind: 'x-resource', key: 'type' } : { kind: 'value' }
    case 'run.unknownWorkflow':
      return !problem.step && /^api\.input\./.test(problem.field ?? '') ? { kind: 'x-resource', key: 'orCreateWith' } : { kind: 'value' }
    default:
      return { kind: 'value' }
  }
}

/** Applies a replace fix in place; false when the document has nothing where the problem points. */
function replaceIn(next: WorkflowDocument, problem: Problem, value: string | undefined): boolean {
  if (value === undefined || !problem.field) return false
  let root: JsonObject
  let path: PathPart[]
  if (problem.step) {
    const loc = findStepLocation(next, problem.step)
    if (!loc) return false
    root = loc.body as JsonObject
    path = stepFieldPath(loc.body, problem.field)
  } else {
    root = next as JsonObject
    path = parsePath(problem.field)
  }
  const how = replacementOf(problem, value)
  if (!how || path.length === 0) return false
  switch (how.kind) {
    case 'value':
      setAtPath(root, path, value)
      return true
    case 'token': {
      const current = getAtPath(root, path)
      if (typeof current !== 'string') return false
      const replaced = current.replace(how.pattern, how.replace)
      if (replaced === current) return false
      setAtPath(root, path, replaced)
      return true
    }
    case 'rename': {
      const parent = getAtPath(root, path.slice(0, -1))
      const wrong = path[path.length - 1]
      if (!isObject(parent) || typeof wrong !== 'string' || !(wrong in parent)) return false
      const kept = parent[wrong]
      delete parent[wrong]
      if (!(value in parent)) parent[value] = kept
      return true
    }
    case 'x-resource': {
      const prop = getAtPath(root, ['api', 'input', 'properties', path[path.length - 1]])
      if (!isObject(prop)) return false
      setAtPath(prop, ['x-resource', how.key], value)
      return true
    }
  }
}

export function applyFix(doc: WorkflowDocument, problem: Problem): WorkflowDocument | null {
  const fix = problem.fix
  if (!fix) return null
  const next = clone(doc)
  if (fix.kind === 'replace') return replaceIn(next, problem, fix.value) ? next : null
  if (fix.kind === 'insert-approval') {
    if (!problem.step) return null
    const loc = findStepLocation(next, problem.step)
    if (!loc) return null
    const taken = new Set<string>()
    for (const l of walkIds(next)) taken.add(l)
    const id = approvalIdFor(problem.step, taken)
    const name = loc.body.name ?? problem.step
    const group = next.policy?.approval?.group ?? ''
    const approval: StepDocument = { [id]: { name: `Approve ${name}`, approval: { group, message: `Approve "${name}"?`, timeout: '4h' } } }
    loc.list.splice(loc.index, 0, approval)
    return next
  }
  return null
}

function* walkIds(doc: WorkflowDocument): Generator<string> {
  const walk = function* (list: StepDocument[] | undefined): Generator<string> {
    if (!Array.isArray(list)) return
    for (const entry of list) {
      for (const id of Object.keys(entry)) {
        yield id
        const body = entry[id]
        if (!body || typeof body !== 'object') continue
        if (body.fork?.branches) for (const b of body.fork.branches) yield* walk(b)
        if (Array.isArray(body.switch))
          for (const c of body.switch) {
            if (c.case?.do) yield* walk(c.case.do)
            if (typeof c.default === 'object' && c.default?.do) yield* walk(c.default.do)
          }
        if (body.for?.do) yield* walk(body.for.do)
        if (body.try?.do) yield* walk(body.try.do)
        if (body.try?.catch?.do) yield* walk(body.try.catch.do)
        if (Array.isArray(body.compensate)) yield* walk(body.compensate)
      }
    }
  }
  yield* walk(doc.workflow?.do)
  yield* walk(doc.workflow?.catch?.do)
}

export { walkIds }
