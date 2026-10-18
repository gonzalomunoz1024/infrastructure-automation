import { describe, expect, it } from 'vitest'
import type { Release } from '../lib/types'
import { gateFigures } from '../pages/ReleasePage'
import { currentRelease, releaseSubject, stageLabel } from '../pages/ReleasesPage'

/*
 * A release rolls out a workflow version, judged over jobs, or a provider version, judged over
 * its calls: where each one links, what its sample counts, and which one a provider page follows.
 */

const base = (over: Partial<Release>): Release => ({
  id: 'rel',
  to: '2.0.0',
  strategy: 'canary',
  status: 'canary',
  stageIndex: 0,
  stages: [10, 50, 100],
  stagePercent: 10,
  startedAt: '2026-10-18T08:00:00Z',
  stageStartedAt: '2026-10-18T08:00:00Z',
  gates: [],
  jobs: { current: 0, candidate: 0 },
  history: [],
  ...over,
})

describe('releaseSubject', () => {
  it('links a workflow release to the workflow and counts jobs', () => {
    expect(releaseSubject({ workflow: 'vm-restart' })).toEqual({ kind: 'workflow', name: 'vm-restart', href: '/workflows/vm-restart', unit: 'job' })
  })
  it('links a provider release to the provider and counts calls', () => {
    expect(releaseSubject({ provider: 'vsphere' })).toEqual({ kind: 'provider', name: 'vsphere', href: '/providers/vsphere', unit: 'call' })
  })
})

describe('gateFigures', () => {
  const sample = { id: 'sample', label: 'Sample size', passed: null, value: 12, threshold: 20, detail: '12 candidate calls since the stage started' }
  it('counts the sample in jobs for a workflow and in calls for a provider', () => {
    expect(gateFigures(sample).value).toBe('12 jobs')
    expect(gateFigures(sample, 'call').value).toBe('12 calls')
    expect(gateFigures(sample, 'call').threshold).toBe('at least 20 calls')
  })
})

describe('currentRelease', () => {
  it('prefers the release in flight to a newer finished one, else takes the newest', () => {
    const promoted = base({ id: 'rel-new', status: 'promoted', stagePercent: 100 })
    const canary = base({ id: 'rel-old', status: 'canary' })
    expect(currentRelease([promoted, canary])?.id).toBe('rel-old')
    expect(currentRelease([promoted, base({ id: 'rel-older', status: 'rolled-back' })])?.id).toBe('rel-new')
    expect(currentRelease([])).toBeUndefined()
    expect(currentRelease(undefined)).toBeUndefined()
  })
  it('names the stage of a provider release like a workflow one', () => {
    expect(stageLabel(base({ stageIndex: 1, provider: 'vsphere' }))).toBe('Stage 2 of 3')
    expect(stageLabel(base({ status: 'promoted' }))).toBe('Promoted')
  })
})
