import { describe, expect, it } from 'vitest'
import { fuzzyFilter, fuzzyMatch } from '@iaf/ui'

describe('fuzzyMatch', () => {
  it('matches a subsequence and returns its indices', () => {
    const m = fuzzyMatch('wf', 'Workflows')
    expect(m).not.toBeNull()
    expect(m!.indices).toEqual([0, 4])
  })

  it('returns null when a character is missing', () => {
    expect(fuzzyMatch('xyz', 'Workflows')).toBeNull()
  })

  it('matches everything with an empty query', () => {
    expect(fuzzyMatch('', 'anything')).toEqual({ score: 0, indices: [] })
  })

  it('is case-insensitive', () => {
    expect(fuzzyMatch('JOB', 'jobs')).not.toBeNull()
  })

  it('prefers consecutive and word-boundary matches', () => {
    const tight = fuzzyMatch('set', 'Settings')!
    const scattered = fuzzyMatch('set', 'Resources Table')!
    expect(tight.score).toBeGreaterThan(scattered.score)
  })
})

describe('fuzzyFilter', () => {
  const items = ['Approvals', 'Providers', 'Audit', 'Settings']

  it('keeps only matches, best first', () => {
    const hits = fuzzyFilter('pro', items, (s) => s)
    expect(hits.map((h) => h.item)).toEqual(['Providers', 'Approvals'])
  })

  it('keeps the input order for an empty query', () => {
    expect(fuzzyFilter('', items, (s) => s).map((h) => h.item)).toEqual(items)
  })
})
