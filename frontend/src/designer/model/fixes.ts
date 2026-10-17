/**
 * The one-click fixes of a Problem: `replace` writes the value into the field the problem names,
 * `insert-approval` puts an approval step before the step, `connect` is answered by the canvas
 * (it opens the node creator in front of the step), so applyFix returns null for it.
 */
import { COMMON_KEYS, STEP_TYPES, type JsonObject, type JsonValue, type Problem, type StepBody, type StepDocument, type WorkflowDocument } from '@iaf/workflow-checks'
import { findStepLocation } from './graph'

export function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T
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
export function applyFix(doc: WorkflowDocument, problem: Problem): WorkflowDocument | null {
  const fix = problem.fix
  if (!fix) return null
  const next = clone(doc)
  if (fix.kind === 'replace') {
    if (fix.value === undefined) return null
    if (problem.step) {
      const loc = findStepLocation(next, problem.step)
      if (!loc || !problem.field) return null
      const path = stepFieldPath(loc.body, problem.field)
      setAtPath(loc.body as JsonObject, path, fix.value)
      return next
    }
    if (!problem.field) return null
    setAtPath(next as JsonObject, parsePath(problem.field), fix.value)
    return next
  }
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
