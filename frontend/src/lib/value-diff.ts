import type { StepDiff } from './types'

/*
 * Field-by-field differences between two JSON values, the way the backend's time travel computes
 * them (runs/domain/JsonDiff): every leaf by dotted path (`output.vm.name`, arrays as `items[0]`),
 * an empty object or list a leaf of its own, at most 12 levels deep. A field missing on one side
 * and null on the other is no difference, and an empty `{}` or `[]` facing fields under the same
 * path is left to the rows of those fields.
 */

const MAX_DEPTH = 12

function collect(value: unknown, path: string, out: Map<string, unknown>, depth: number) {
  if (value === undefined || depth > MAX_DEPTH) return
  if (Array.isArray(value) && value.length > 0) {
    value.forEach((v, i) => collect(v, `${path}[${i}]`, out, depth + 1))
  } else if (value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0) {
    for (const [k, v] of Object.entries(value)) collect(v, path ? `${path}.${k}` : k, out, depth + 1)
  } else {
    out.set(path, value)
  }
}

/** Every leaf of a value by dotted path, under `prefix`. */
export function flattenValue(value: unknown, prefix = ''): Map<string, unknown> {
  const out = new Map<string, unknown>()
  collect(value, prefix, out, 0)
  return out
}

const same = (a: unknown, b: unknown) => (a ?? null) === (b ?? null) || JSON.stringify(a ?? null) === JSON.stringify(b ?? null)

/** The other side has fields under this one: an empty `{}` or `[]` here is said by those rows. */
const hasChildren = (leaves: Map<string, unknown>, field: string) => [...leaves.keys()].some((k) => k.startsWith(`${field}.`) || k.startsWith(`${field}[`))

/** The leaves that differ, `before` from the first value, `after` from the second, in first-seen order. */
export function valueDiff(before: unknown, after: unknown, prefix = ''): StepDiff[] {
  const b = flattenValue(before, prefix)
  const a = flattenValue(after, prefix)
  const out: StepDiff[] = []
  for (const field of new Set([...b.keys(), ...a.keys()])) {
    if ((!b.has(field) && hasChildren(b, field)) || (!a.has(field) && hasChildren(a, field))) continue
    if (!same(b.get(field), a.get(field))) out.push({ field, before: b.get(field), after: a.get(field) })
  }
  return out
}
