/**
 * Expression previews for the NDV: the context a step sees (the job input, the previous step's
 * output, every earlier step by name, aliases, variables), built from test-run outputs when
 * there are any and from provider samples and schemas otherwise; the live evaluation of a
 * parameter; and the expression a drag-to-map drop inserts.
 */
import { allSteps, displayName, evaluate, isTemplate, parseDocument, resolve, syntaxError, type ExpressionContext, type JsonObject, type JsonValue, type ParsedStep, type WorkflowDocument } from '@iaf/workflow-checks'
import type { Schema } from '../../lib/types'
import { exampleOf } from './schema'
import { findAction, type Registry } from './registry'

export type StepOutputs = Record<string, JsonValue>

/** The function's value, or the fallback when it throws. */
function attempt<T>(fn: () => T, fallback: T): T {
  try {
    return fn()
  } catch {
    return fallback
  }
}

export const SAMPLE_JOB: JsonObject = { id: 'job_test', seq: 1042, requester: 'you', startedAt: '2026-01-01T09:00:00Z', environment: 'test' }
export const SAMPLE_ENV: JsonObject = { name: 'test' }

function asObject(v: JsonValue | null | undefined): JsonObject | null {
  return v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v) ? v : null
}

/** Example job input: the input schema's defaults and placeholders. */
export function sampleInput(doc: WorkflowDocument): JsonObject {
  const schema = doc.api?.input as Schema | undefined
  const v = exampleOf(schema ?? { type: 'object' }, 'input')
  return asObject(v) ?? {}
}

/**
 * What a step is expected to output, when nobody ran it: the provider action's sample resolved
 * against the step's input, else an example of its output schema; a child workflow's output
 * schema; the loop's list; `set` values; nothing for the rest.
 */
export function expectedOutput(step: ParsedStep, registry: Registry | null, ctx: ExpressionContext): JsonValue | null {
  const w = asObject(step.with) ?? {}
  switch (step.type) {
    case 'provider': {
      const provider = typeof w.provider === 'string' ? w.provider : null
      const action = findAction(registry, provider, typeof w.action === 'string' ? w.action : null)
      if (!action) return null
      const input = attempt(() => resolve(w.input ?? null, ctx), w.input ?? null)
      if (action.sample !== undefined && action.sample !== null) {
        try {
          return resolve(action.sample as JsonValue, { ...ctx, input, json: input })
        } catch {
          return action.sample as JsonValue
        }
      }
      return action.output ? exampleOf(action.output, 'output') : null
    }
    case 'run': {
      const name = typeof w.workflow === 'string' ? w.workflow : null
      const wf = registry?.workflows.find((x) => x.name === name)
      return wf ? { outputs: exampleOf(wf.output, 'outputs') } : null
    }
    case 'set': {
      try {
        return resolve(w, ctx)
      } catch {
        return w
      }
    }
    case 'use':
    case 'register': {
      const type = typeof w.type === 'string' ? w.type : null
      const rt = registry?.resourceTypes.find((r) => r.name === type)
      const id = typeof w.id === 'string' ? w.id : `${type ?? 'resource'}-1`
      let resolvedId: JsonValue = id
      try {
        resolvedId = evaluate(id, ctx)
      } catch {
        /* keep the raw id */
      }
      return { id: resolvedId, type, status: step.type === 'register' ? ((w.state as JsonValue) ?? 'ready') : 'ready', attributes: rt ? exampleOf(rt.schema, 'attributes') : ((w.attributes as JsonValue) ?? {}) }
    }
    case 'approval':
      return { approved: true, by: 'approver', group: (w.group as JsonValue) ?? null }
    case 'script':
      return { result: null }
    case 'http':
      return { status: 200, body: {} }
    case 'ansible':
      return { ok: 1, changed: 0, failed: 0 }
    case 'terraform':
      return { outputs: {}, add: 0, change: 0, destroy: 0 }
    case 'idp': {
      const provider = registry?.providers.find((p) => p.type === 'idp' && p.actions.some((a) => a.id === w.tile))
      const action = provider?.actions.find((a) => a.id === w.tile)
      if (action?.sample !== undefined && action?.sample !== null) {
        try {
          return resolve(action.sample as JsonValue, { ...ctx, input: (w.input as JsonValue) ?? null })
        } catch {
          return action.sample as JsonValue
        }
      }
      return { id: 'ticket-1', state: 'fulfilled' }
    }
    default:
      return null
  }
}

export interface StepContext {
  ctx: ExpressionContext
  /** the step whose output `$json` is */
  previous: ParsedStep | null
  /** output by step id, name and alias, for every step before this one */
  outputs: StepOutputs
  inCatch: boolean
}

/**
 * The expression context at a step: everything the steps before it produced. `known` holds
 * outputs from a test run or a job (by step id); the rest is filled from samples and schemas.
 * With `stepId` null the context is the one after the last step (workflow outputs).
 */
export function contextAt(doc: WorkflowDocument, stepId: string | null, known: StepOutputs, registry: Registry | null, input?: JsonObject): StepContext {
  const jobInput = input ?? sampleInput(doc)
  const ctx: ExpressionContext = { input: jobInput, job: SAMPLE_JOB, env: SAMPLE_ENV, steps: {}, aliases: {}, vars: {} }
  const steps: Record<string, JsonValue> = {}
  const aliases: Record<string, JsonValue> = {}
  const vars: JsonObject = {}
  const outputs: StepOutputs = {}
  let previous: ParsedStep | null = null
  let inCatch = false
  let parsed
  try {
    parsed = parseDocument(doc)
  } catch {
    return { ctx, previous: null, outputs, inCatch: false }
  }
  const main = parsed.workflow.steps.flatMap(flattenOrdered)
  const catchSteps = parsed.workflow.catchFlow?.steps.flatMap(flattenOrdered) ?? []
  // the workflow outputs (stepId null) see the main flow only
  const all = stepId === null ? main : [...main, ...catchSteps]
  const target = all.find((s) => s.id === stepId)
  if (target && catchSteps.includes(target)) inCatch = true
  const container = (s: ParsedStep) => s.type === 'switch' || s.type === 'fork' || s.type === 'for' || s.type === 'try'
  for (const s of all) {
    if (s === target) break
    if (s.type === 'for' && target && isInside(s, target)) {
      const w = asObject(s.with)
      const each = typeof w?.each === 'string' ? w.each : 'item'
      const source = typeof w?.in === 'string' ? w.in : null
      const list = source === null ? null : attempt(() => evaluate(source, { ...ctx, json: previous ? outputs[previous.id] ?? null : null }), null)
      aliases[each] = Array.isArray(list) && list.length > 0 ? list[0] : { index: 0 }
      ctx.aliases = aliases
      continue
    }
    if (s.type === 'try' && target && s.catchBlock && s.catchBlock.steps.some((c) => c === target || isInside(c, target))) {
      aliases[s.catchBlock.as ?? 'error'] = { message: 'Something failed', class: 'step_failed', step: s.children?.[0]?.id ?? s.id }
      inCatch = true
      ctx.aliases = aliases
      continue
    }
    if (container(s)) continue
    const stepCtx: ExpressionContext = { ...ctx, json: previous ? outputs[previous.id] ?? null : null, steps, aliases, vars }
    const out = known[s.id] !== undefined ? known[s.id] : expectedOutput(s, registry, stepCtx)
    const value: JsonValue = out === undefined ? null : out
    outputs[s.id] = value
    steps[s.id] = value
    steps[displayName(s)] = value
    if (s.output) aliases[s.output.as] = value
    if (s.type === 'set' && value && typeof value === 'object' && !Array.isArray(value)) Object.assign(vars, value)
    previous = s
  }
  ctx.steps = steps
  ctx.aliases = aliases
  ctx.vars = vars
  ctx.json = previous ? outputs[previous.id] ?? null : null
  if (inCatch) ctx.error = { message: 'Something failed', class: 'step_failed', step: previous?.id ?? null }
  return { ctx, previous, outputs, inCatch }
}

/** The step and its nested steps in run order (containers first, then their children). */
function flattenOrdered(step: ParsedStep): ParsedStep[] {
  const out: ParsedStep[] = [step]
  step.cases?.forEach((c) => c.steps?.forEach((s) => out.push(...flattenOrdered(s))))
  step.branches?.forEach((b) => b.forEach((s) => out.push(...flattenOrdered(s))))
  step.children?.forEach((s) => out.push(...flattenOrdered(s)))
  step.catchBlock?.steps.forEach((s) => out.push(...flattenOrdered(s)))
  return out
}

function isInside(container: ParsedStep, step: ParsedStep): boolean {
  return flattenOrdered(container).slice(1).includes(step)
}

export type Preview = { ok: true; value: JsonValue; text: string } | { ok: false; error: string }

/** Evaluate a parameter the way the interpreter will; a non-template string is itself. */
export function previewExpression(template: string, ctx: ExpressionContext): Preview {
  if (!isTemplate(template)) return { ok: true, value: template, text: template }
  const err = syntaxError(template)
  if (err) return { ok: false, error: err }
  try {
    const value = evaluate(template, ctx)
    return { ok: true, value, text: previewText(value) }
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) }
  }
}

export function previewText(value: JsonValue | undefined): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'string') return value
  if (typeof value === 'object') {
    const s = JSON.stringify(value)
    return s.length > 160 ? `${s.slice(0, 159)}…` : s
  }
  return String(value)
}

export type MappingSource = { kind: 'input' } | { kind: 'previous' } | { kind: 'step'; name: string } | { kind: 'alias'; alias: string }

/** The expression a drop from the Input pane inserts: `{{ $('Step').json.x }}`, `{{ $json.x }}`, `{{ $input.x }}`. */
export function mappingExpression(source: MappingSource, path: string): string {
  const tail = path ? (path.startsWith('[') ? path : `.${path}`) : ''
  switch (source.kind) {
    case 'input':
      return `{{ $input${tail} }}`
    case 'previous':
      return `{{ $json${tail} }}`
    case 'alias':
      return `{{ $${source.alias}${tail} }}`
    case 'step':
      return `{{ $('${source.name.replace(/'/g, "\\'")}').json${tail} }}`
  }
}

/** The pieces of a template for highlighting: text and `{{ expression }}` spans. */
export function templateSegments(template: string): { text: string; expression: boolean }[] {
  const out: { text: string; expression: boolean }[] = []
  let i = 0
  while (i < template.length) {
    const open = template.indexOf('{{', i)
    if (open < 0) {
      out.push({ text: template.slice(i), expression: false })
      break
    }
    const close = template.indexOf('}}', open + 2)
    if (close < 0) {
      out.push({ text: template.slice(i), expression: false })
      break
    }
    if (open > i) out.push({ text: template.slice(i, open), expression: false })
    out.push({ text: template.slice(open, close + 2), expression: true })
    i = close + 2
  }
  return out.filter((s) => s.text.length > 0)
}

export { allSteps }
