import { describe, expect, it } from 'vitest'
import { DEFAULT_ROLLOUT, describeRollout, rolloutOf, rolloutProblems, waveSizesOf } from './rollout'

describe('a bulk run’s rollout', () => {
  it('takes the person’s choice over the workflow’s own, field by field', () => {
    const own = { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 }
    expect(rolloutOf(undefined, own)).toEqual(own)
    expect(rolloutOf({ maxParallel: 2 }, own)).toEqual({ ...own, maxParallel: 2 })
    expect(rolloutOf({ waves: [] }, own).waves).toEqual([5, 25, 100])
    expect(rolloutOf(undefined, undefined)).toEqual(DEFAULT_ROLLOUT)
  })

  it('releases the cumulative share of rows each wave, rounded up, leaving out a wave that adds none', () => {
    expect(waveSizesOf(50, [5, 25, 100])).toEqual([
      { percent: 5, size: 3 },
      { percent: 25, size: 10 },
      { percent: 100, size: 37 },
    ])
    expect(waveSizesOf(2, [5, 25, 100])).toEqual([
      { percent: 5, size: 1 },
      { percent: 100, size: 1 },
    ])
    expect(waveSizesOf(1, [5, 25, 100])).toEqual([{ percent: 5, size: 1 }])
    expect(waveSizesOf(10, [50]).reduce((n, w) => n + w.size, 0)).toBe(10)
  })

  it('says what a rollout gets wrong at its own field', () => {
    expect(rolloutProblems(undefined)).toEqual([])
    expect(rolloutProblems({ waves: [10, 50, 100], maxParallel: 5, stopIfFailedPercent: 0 })).toEqual([])
    expect(rolloutProblems([1]).map((p) => p.field)).toEqual(['/rollout'])
    expect(rolloutProblems({ waves: [] }).map((p) => p.field)).toEqual(['/rollout/waves'])
    expect(rolloutProblems({ waves: [50, 25, 100] })[0].message).toMatch(/larger share/)
    expect(rolloutProblems({ waves: [10, 50] })[0].message).toMatch(/end the waves at 100/)
    expect(rolloutProblems({ maxParallel: 0, stopIfFailedPercent: 101 }).map((p) => p.field)).toEqual(['/rollout/maxParallel', '/rollout/stopIfFailedPercent'])
  })

  it('reads as one sentence', () => {
    expect(describeRollout({ waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 })).toBe('3 waves (5%, 25%, then the rest), 10 at a time, stopping when more than 5% fail')
    expect(describeRollout(DEFAULT_ROLLOUT)).toBe('all at once, 10 at a time, stopping at the first failure')
  })
})
