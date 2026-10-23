import type { Problem } from '@iaf/console-kit'
import type { Rollout } from './types'

/*
 * A bulk run's rollout (`POST /console/v1/r/{type}:batch`, WP 4.2): the waves as cumulative
 * percentages of the rows, how many rows run at once, and the share of failures that stops it.
 * The console and the mock share these rules, so the drawer shows the waves the BFF will run.
 */

export interface FullRollout {
  waves: number[]
  maxParallel: number
  stopIfFailedPercent: number
}

/** What a workflow without a rollout of its own runs: everything in one wave, ten at a time, stopping at the first failure. */
export const DEFAULT_ROLLOUT: FullRollout = { waves: [100], maxParallel: 10, stopIfFailedPercent: 0 }

/** The person's rollout over the workflow's own, field by field. */
export function rolloutOf(chosen: Rollout | undefined, own: Partial<FullRollout> | undefined): FullRollout {
  return {
    waves: chosen?.waves?.length ? chosen.waves : own?.waves?.length ? own.waves : DEFAULT_ROLLOUT.waves,
    maxParallel: chosen?.maxParallel ?? own?.maxParallel ?? DEFAULT_ROLLOUT.maxParallel,
    stopIfFailedPercent: chosen?.stopIfFailedPercent ?? own?.stopIfFailedPercent ?? DEFAULT_ROLLOUT.stopIfFailedPercent,
  }
}

/** How many rows each wave releases: the cumulative share rounded up, a wave that would add none left out. */
export function waveSizesOf(total: number, waves: readonly number[]): { percent: number; size: number }[] {
  const out: { percent: number; size: number }[] = []
  let released = 0
  for (const percent of waves) {
    const upTo = Math.min(total, Math.max(1, Math.ceil((total * percent) / 100)))
    if (upTo <= released) continue
    out.push({ percent, size: upTo - released })
    released = upTo
  }
  if (released < total) out.push({ percent: 100, size: total - released })
  return out
}

const isInteger = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v)

/** What a rollout gets wrong, as problems at `/rollout/...`: waves rising from 1 to 100 and ending at 100, at least one at a time, a share from 0 to 100. */
export function rolloutProblems(rollout: unknown): Problem[] {
  if (rollout === undefined || rollout === null) return []
  if (typeof rollout !== 'object' || Array.isArray(rollout)) return [{ level: 'error', code: 'type', field: '/rollout', message: 'The rollout is an object of waves, maxParallel and stopIfFailedPercent' }]
  const r = rollout as Record<string, unknown>
  const problems: Problem[] = []
  if (r.waves !== undefined) {
    const waves = r.waves
    if (!Array.isArray(waves) || waves.length === 0 || waves.length > 10 || !waves.every(isInteger)) problems.push({ level: 'error', code: 'type', field: '/rollout/waves', message: 'Waves are 1 to 10 whole percentages' })
    else if (waves.some((w, i) => w < 1 || w > 100 || (i > 0 && w <= waves[i - 1]))) problems.push({ level: 'error', code: 'pattern', field: '/rollout/waves', message: 'Each wave releases a larger share than the one before, from 1 to 100 percent' })
    else if (waves[waves.length - 1] !== 100) problems.push({ level: 'error', code: 'pattern', field: '/rollout/waves', message: 'The last wave releases every row: end the waves at 100 percent' })
  }
  if (r.maxParallel !== undefined && (!isInteger(r.maxParallel) || r.maxParallel < 1 || r.maxParallel > 500)) problems.push({ level: 'error', code: 'minimum', field: '/rollout/maxParallel', message: 'Run between 1 and 500 rows at once' })
  if (r.stopIfFailedPercent !== undefined && (!isInteger(r.stopIfFailedPercent) || r.stopIfFailedPercent < 0 || r.stopIfFailedPercent > 100)) problems.push({ level: 'error', code: 'maximum', field: '/rollout/stopIfFailedPercent', message: 'The share of failures that stops the run is a whole percentage from 0 to 100' })
  return problems
}

/** The rollout in words: "3 waves (5%, 25%, then the rest), 10 at a time, stopping when more than 5% fail". */
export function describeRollout(r: FullRollout): string {
  const waves = r.waves.length === 1 ? 'all at once' : `${r.waves.length} waves (${r.waves.slice(0, -1).map((w) => `${w}%`).join(', ')}, then the rest)`
  const stop = r.stopIfFailedPercent === 0 ? 'stopping at the first failure' : `stopping when more than ${r.stopIfFailedPercent}% fail`
  return `${waves}, ${r.maxParallel} at a time, ${stop}`
}
