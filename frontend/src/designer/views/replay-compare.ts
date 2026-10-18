import type { JobStep, JobSummary, StepDiff } from '../../lib/types'
import { valueDiff } from '../../lib/value-diff'

/*
 * The canvas replay's comparison with the last successful run, the one the job page's time
 * travel makes on the server: which run to compare with, and this run's steps paired with that
 * run's, each pair with the fields of its output that differ.
 */

type Comparable = Pick<JobSummary, 'id' | 'workflow' | 'status' | 'startedAt' | 'kind' | 'parentId'>

/**
 * The run to compare with: the last successful run of the same workflow that started before this
 * one, of the same kind and, like it, top level or a child (the backend's `previousOf`). Never the
 * job itself, so the last successful run is compared with the one before it.
 */
export function lastSuccessfulBefore<T extends Comparable>(job: Comparable, jobs: readonly T[]): T | null {
  const at = Date.parse(job.startedAt)
  let best: T | null = null
  for (const j of jobs) {
    if (j.id === job.id || j.workflow !== job.workflow || j.status !== 'succeeded') continue
    if ((j.kind ?? 'single') !== (job.kind ?? 'single') || !j.parentId !== !job.parentId) continue
    const t = Date.parse(j.startedAt)
    if (!(t < at)) continue
    if (!best || t > Date.parse(best.startedAt)) best = j
  }
  return best
}

export type StepChange = 'same' | 'changed' | 'added' | 'removed'
export type StepPair = {
  key: string
  /** the step's name, this run's when it has the step */
  name: string
  /** the step's id: its key in the workflow */
  id: string
  change: StepChange
  current?: JobStep
  compared?: JobStep
  /** the status when it differs, then the output's fields that differ (`output.…`) */
  diff: StepDiff[]
}

function diffOf(current: JobStep, compared: JobStep): StepDiff[] {
  const status: StepDiff[] = current.status !== compared.status ? [{ field: 'status', before: compared.status, after: current.status }] : []
  return [...status, ...valueDiff(compared.output, current.output, 'output')]
}

/**
 * This run's steps against the compared run's, in this run's order. A step pairs with the step of
 * the same id (its key in the workflow), or failing that of the same name; a step only in this run
 * is `added`, one only in the compared run `removed`, listed after the step it followed there.
 */
export function pairSteps(current: readonly JobStep[], compared: readonly JobStep[]): StepPair[] {
  const match = new Map<number, number>()
  const taken = new Set<number>()
  const claim = (i: number, same: (c: JobStep) => boolean) => {
    const j = compared.findIndex((c, k) => !taken.has(k) && same(c))
    if (j < 0) return
    match.set(i, j)
    taken.add(j)
  }
  current.forEach((s, i) => claim(i, (c) => c.id === s.id))
  current.forEach((s, i) => {
    if (!match.has(i) && s.name) claim(i, (c) => c.name === s.name && !current.some((o) => o.id === c.id))
  })

  const out: StepPair[] = []
  const emitted = new Set<number>()
  const removed = (j: number) => {
    emitted.add(j)
    const c = compared[j]
    out.push({ key: `removed:${j}:${c.id}`, id: c.id, name: c.name || c.id, change: 'removed', compared: c, diff: [] })
  }
  const first = Math.min(compared.length, ...taken)
  for (let j = 0; j < first; j++) if (!taken.has(j)) removed(j)
  current.forEach((s, i) => {
    const j = match.get(i)
    if (j === undefined) {
      out.push({ key: `added:${i}:${s.id}`, id: s.id, name: s.name || s.id, change: 'added', current: s, diff: [] })
      return
    }
    const c = compared[j]
    const diff = diffOf(s, c)
    out.push({ key: `step:${i}:${s.id}`, id: s.id, name: s.name || c.name || s.id, change: diff.length ? 'changed' : 'same', current: s, compared: c, diff })
    // what followed it in the compared run and is gone now
    for (let k = j + 1; k < compared.length && !taken.has(k); k++) if (!emitted.has(k)) removed(k)
  })
  compared.forEach((_, j) => {
    if (!taken.has(j) && !emitted.has(j)) removed(j)
  })
  return out
}

/** "2 steps differ, 1 added, 2 removed", or that nothing differs. */
export function comparisonSummary(pairs: readonly StepPair[]): string {
  const count = (c: StepChange) => pairs.filter((p) => p.change === c).length
  const changed = count('changed')
  const parts = [changed ? `${changed} ${changed === 1 ? 'step differs' : 'steps differ'}` : '', count('added') ? `${count('added')} added` : '', count('removed') ? `${count('removed')} removed` : ''].filter(Boolean)
  return parts.length ? parts.join(', ') : 'nothing differs'
}
