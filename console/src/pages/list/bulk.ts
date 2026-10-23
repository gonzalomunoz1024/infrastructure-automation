import type { Schema, SchemaValue } from '@iaf/console-kit'
import { plural } from '@iaf/ui'
import type { ConsoleAction, ConsoleType } from '../../lib/descriptors'
import { describeRollout, rolloutOf, waveSizesOf, type FullRollout } from '../../lib/rollout'
import type { BatchInput, BatchPreview, Rollout } from '../../lib/types'
import { answeredInputs } from '../resource/ActionDrawer'

/*
 * What the list's bulk actions are worked out from (WP 4.2): which verbs run in bulk, the rollout
 * the person picks against the workflow's own, the body one request sends, the rows the preview
 * says cannot run, and the words the confirmation asks for. Pure, so the rules are tested.
 */

/** The verbs a list's selection can run in one request: the type's actions with a batch route, create aside. */
export function bulkVerbsOf(type: ConsoleType): ConsoleAction[] {
  return type.actions.filter((a) => a.verb !== 'create' && !!a.batchPath)
}

export type RolloutPreset = 'own' | 'two' | 'all'

/** The waves of each choice: the workflow's own, two waves (a tenth first), or every row at once. */
export function presetWaves(preset: RolloutPreset, own: readonly number[] | undefined): number[] {
  if (preset === 'two') return [10, 100]
  if (preset === 'all') return [100]
  return endAtAll(own?.length ? own : [100])
}

/** Waves always end by releasing every row: a workflow whose own waves stop short gets the rest as a last wave. */
export function endAtAll(waves: readonly number[]): number[] {
  return waves[waves.length - 1] === 100 ? [...waves] : [...waves, 100]
}

/**
 * The rollout a request names: nothing when the person kept the workflow's own (the BFF applies
 * it), else every field, so what the drawer showed is what runs.
 */
export function chosenRollout(preset: RolloutPreset, maxParallel: number, stopIfFailedPercent: number, own: Rollout | undefined): Rollout | undefined {
  const base = rolloutOf(undefined, own)
  if (preset === 'own' && maxParallel === base.maxParallel && stopIfFailedPercent === base.stopIfFailedPercent) return undefined
  return { waves: presetWaves(preset, own?.waves), maxParallel, stopIfFailedPercent }
}

/** The rollout that will run, in full, and how many rows each wave releases. */
export function plannedRollout(chosen: Rollout | undefined, own: Rollout | undefined, rows: number): { rollout: FullRollout; waves: { percent: number; size: number }[]; words: string } {
  const rollout = rolloutOf(chosen, own)
  return { rollout, waves: waveSizesOf(rows, rollout.waves), words: describeRollout(rollout) }
}

/** The waves in words: "3, then 10, then 37 rows". */
export function wavesLine(waves: { size: number }[]): string {
  if (waves.length === 0) return 'No rows'
  if (waves.length === 1) return `${plural(waves[0].size, 'row')} in one wave`
  return `${waves.map((w) => w.size).join(', then ')} rows`
}

/**
 * The inputs every selected row answers from its own attributes (an environment, a size): each
 * row keeps its own, so the shared form leaves them out. One row that does not answer it makes
 * the input shared again, the same for every row.
 */
export function ownInputsOf(schema: Schema, subject: string | undefined, rows: readonly { attributes: Record<string, unknown> }[]): string[] {
  if (rows.length === 0) return []
  const answered = rows.map((r) => answeredInputs(schema, subject, r.attributes))
  return Object.keys(schema.properties ?? {}).filter((name) => answered.every((a) => name in a))
}

/** A row's own input: what its attributes answer of the inputs each row keeps. */
export function rowInputOf(schema: Schema, subject: string | undefined, own: readonly string[], attributes: Record<string, unknown>): SchemaValue {
  const answered = answeredInputs(schema, subject, attributes)
  return Object.fromEntries(own.filter((k) => k in answered).map((k) => [k, answered[k]]))
}

/** One request's body: a row per resource with its own input, the shared input as defaults, the rollout and the reason when given. */
export function batchBodyOf(verb: string, items: readonly { wfrn: string; input: Record<string, unknown> }[], defaults: Record<string, unknown>, rollout: Rollout | undefined, reason: string | undefined): BatchInput {
  const body: BatchInput = { verb, items: items.map((i) => ({ wfrn: i.wfrn, input: { ...i.input } })) }
  if (Object.keys(defaults).length) body.defaults = defaults
  if (rollout) body.rollout = rollout
  const said = reason?.trim()
  if (said) body.reason = said
  return body
}

export interface Unrunnable {
  wfrn: string
  name: string
  reasons: string[]
}

/**
 * The rows the preview says cannot run as they are: gone, in the wrong state, or built on.
 * A missing shared field is the form's to show, not a row's, so it is left out here.
 */
export function unrunnableOf(preview: BatchPreview | undefined, wfrns: readonly string[]): Unrunnable[] {
  if (!preview) return []
  const out: Unrunnable[] = []
  for (const item of preview.items) {
    const reasons = item.problems.filter((p) => !(p.field ?? '').includes('/input')).map((p) => p.message)
    if (!reasons.length) continue
    const wfrn = item.wfrn ?? wfrns[item.index]
    if (!wfrn) continue
    out.push({ wfrn, name: item.name ?? wfrn, reasons })
  }
  return out
}

/** What the person types to confirm a destructive bulk run: the verb and how many, "delete 12". */
export function confirmTextOf(verb: string, rows: number): string {
  return `${verb} ${rows}`
}
