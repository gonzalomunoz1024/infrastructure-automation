import type { BffJobDetail, JobSteer } from './types'

/*
 * What a person may do with a job, from the BFF's `can`: the verbs it lists (`cancel` while the
 * job runs, `retry`, `skip` and `abort` while a step waits, `retry` again once it ended badly).
 * An early mock answered an object of booleans; both spellings read the same.
 */

export function mayJob(job: Pick<BffJobDetail, 'can'> | undefined, steer: JobSteer): boolean | undefined {
  const can = job?.can
  if (!can) return undefined
  if (Array.isArray(can)) return can.includes(steer)
  return can[steer] === true
}
