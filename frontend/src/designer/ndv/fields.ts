/**
 * Reading and writing one parameter of a step body by the field path the checks use (`url`,
 * `input.vmId`, `playbook.git.repo`, `switch[0].when`, `retry.maxAttempts`), the problems that
 * sit on a field, and the conversions behind the Fixed / Expression toggle.
 */
import { isTemplate, type JsonObject, type JsonValue, type Problem, type StepBody } from '@iaf/workflow-checks'
import { getAtPath, parsePath, setAtPath, stepFieldPath, stepTypeOfBody } from '../model/fixes'

type PathPart = string | number

/** The value of a step field, undefined when it is not written. */
export function readField(body: StepBody, field: string): JsonValue | undefined {
  if (stepTypeOfBody(body) === 'wait' && typeof body.wait === 'string') return field === 'duration' ? body.wait : undefined
  return getAtPath(body as unknown as JsonValue, stepFieldPath(body, field))
}

/** Removes the value at a path; empty objects left behind on the way stay (the YAML keeps its shape). */
export function deleteAtPath(root: JsonObject, path: PathPart[]): void {
  if (path.length === 0) return
  const parent = getAtPath(root, path.slice(0, -1))
  if (parent === null || parent === undefined || typeof parent !== 'object') return
  const last = path[path.length - 1]
  if (Array.isArray(parent)) {
    if (typeof last === 'number') parent.splice(last, 1)
  } else {
    delete (parent as JsonObject)[String(last)]
  }
}

/** Writes (or with undefined, removes) a step field in place; used inside store.updateStep. */
export function writeField(body: StepBody, field: string, value: JsonValue | undefined): void {
  if (stepTypeOfBody(body) === 'wait' && typeof body.wait === 'string') body.wait = { duration: body.wait }
  const path = stepFieldPath(body, field)
  if (value === undefined) deleteAtPath(body as unknown as JsonObject, path)
  else setAtPath(body as unknown as JsonObject, path, value)
}

/** A document-level field (`api.method`, `triggers[0].alert.match.source`). */
export function readDocField(root: JsonValue | undefined, field: string): JsonValue | undefined {
  return getAtPath(root, parsePath(field))
}

export function writeDocField(root: JsonObject, field: string, value: JsonValue | undefined): void {
  const path = parsePath(field)
  if (value === undefined) deleteAtPath(root, path)
  else setAtPath(root, path, value)
}

/** The problems on a field or inside it (`input` holds `input.vmId`). */
export function problemsAt(problems: readonly Problem[], field: string): Problem[] {
  return problems.filter((p) => p.field !== undefined && (p.field === field || p.field.startsWith(`${field}.`) || p.field.startsWith(`${field}[`)))
}

/** The first key of a field path: `input.vmId` → `input`, `switch[0].when` → `switch`. */
export function rootOf(field: string): string {
  const m = /^[^.[]+/.exec(field)
  return m ? m[0] : field
}

/** A `use` step asks policy about its resource unless it opts out: on unless the file says `checkAccess: false`. */
export function checkAccessOn(value: JsonValue | undefined): boolean {
  return value !== false
}

/** What the file keeps for the Check access switch: `false` to opt out, nothing for the default (never `checkAccess: true`). */
export function checkAccessValue(on: JsonValue | undefined): false | undefined {
  return on === false ? false : undefined
}

/** The keys the Settings tab edits; their problems are counted there. */
export const SETTINGS_KEYS = ['name', 'output', 'assert', 'retry', 'timeout', 'pauseOnFailure', 'attentionTimeout', 'notes', 'compensate', 'position'] as const

export function isSettingsField(field: string | undefined): boolean {
  return field !== undefined && (SETTINGS_KEYS as readonly string[]).includes(rootOf(field))
}

export function isExpression(v: JsonValue | undefined): boolean {
  return typeof v === 'string' && isTemplate(v)
}

/** Fixed → Expression: strings stay as they are, other values become `{{ <literal> }}`. */
export function toExpression(v: JsonValue | undefined): string {
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  return `{{ ${JSON.stringify(v)} }}`
}

/**
 * Expression → Fixed for a field of a type: `{{ 5 }}` becomes 5 for a number, a string stays
 * text, anything that cannot be kept becomes undefined (the field is cleared).
 */
export function toFixed(v: JsonValue | undefined, type: 'string' | 'number' | 'boolean' | 'enum' | 'object' | 'array' | 'any'): JsonValue | undefined {
  if (typeof v !== 'string') return v
  const m = /^\s*\{\{\s*([\s\S]*?)\s*\}\}\s*$/.exec(v)
  let literal: JsonValue | undefined
  if (m) {
    try {
      literal = JSON.parse(m[1]) as JsonValue
    } catch {
      literal = undefined
    }
  }
  switch (type) {
    case 'string':
    case 'enum':
      return typeof literal === 'string' ? literal : isTemplate(v) ? undefined : v
    case 'number':
      return typeof literal === 'number' ? literal : undefined
    case 'boolean':
      return typeof literal === 'boolean' ? literal : undefined
    case 'object':
      return literal !== null && typeof literal === 'object' && !Array.isArray(literal) ? literal : undefined
    case 'array':
      return Array.isArray(literal) ? literal : undefined
    default:
      return literal !== undefined ? literal : v
  }
}

/** A value as editable JSON text. */
export function jsonText(v: JsonValue | undefined): string {
  if (v === undefined) return ''
  return JSON.stringify(v, null, 2)
}

export type ParsedJson = { ok: true; value: JsonValue | undefined } | { ok: false; error: string }

export function parseJsonText(text: string): ParsedJson {
  if (!text.trim()) return { ok: true, value: undefined }
  try {
    return { ok: true, value: JSON.parse(text) as JsonValue }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message.replace(/^JSON\.parse: /, '') : 'Not valid JSON' }
  }
}

export function asObject(v: JsonValue | undefined): JsonObject {
  return v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v) ? v : {}
}

export function asString(v: JsonValue | undefined): string {
  return typeof v === 'string' ? v : v === undefined || v === null ? '' : String(v)
}
