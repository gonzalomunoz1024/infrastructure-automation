import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { ReleaseGate } from '../lib/types'
import { gateFigures, spanOf } from '../pages/ReleasePage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')
const render = (v: unknown) => text(renderToString(<>{v as string}</>))

describe('release gates', () => {
  it('writes durations in words, not raw milliseconds', () => {
    expect(spanOf(340)).toBe('340 ms')
    expect(spanOf(2200)).toBe('2.2 s')
    expect(spanOf(2000)).toBe('2 s')
    expect(spanOf(2_200_000)).toBe('36 min 40 s')
    expect(spanOf(60_000)).toBe('1 min')
    expect(spanOf(7_500_000)).toBe('2 h 5 min')
    expect(spanOf(7_200_000)).toBe('2 h')
  })

  it('shows the p95 gate as durations', () => {
    const gate: ReleaseGate = { id: 'p95', label: 'p95 duration', passed: true, value: 2200, threshold: 2_200_000, detail: '2.2s on the candidate vs 1.8s on the current version (may be 20% slower)' }
    const f = gateFigures(gate)
    expect(render(f.value)).toBe('2.2 s')
    expect(render(f.threshold)).toBe('at most 36 min 40 s')
    expect(render(f.detail)).toContain('on the candidate')
  })

  it('shows the success-rate gate as percentages', () => {
    const gate: ReleaseGate = { id: 'success-rate', label: 'Success rate', passed: false, value: 97.5, threshold: 99, detail: '97.5% on the candidate vs 100.0% on the current version (may trail by 1 point)' }
    const f = gateFigures(gate)
    expect(render(f.value)).toBe('97.5%')
    expect(render(f.threshold)).toBe('at least 99.0%')
  })

  it('lists the error types new on the candidate', () => {
    const gate: ReleaseGate = { id: 'error-types', label: 'Error types', passed: false, value: 2, threshold: 0, detail: 'new on the candidate: timeout, upstream' }
    const f = gateFigures(gate)
    expect(render(f.value)).toBe('2 new')
    expect(render(f.threshold)).toBe('none new')
    const detail = render(f.detail)
    expect(detail).toContain('<ul')
    expect(detail).toContain('>timeout<')
    expect(detail).toContain('>upstream<')
    const none: ReleaseGate = { ...gate, passed: true, value: 0, detail: 'no failures on the candidate' }
    expect(render(gateFigures(none).detail)).toBe('no failures on the candidate')
  })

  it('shows the sample gate as jobs and leaves pending values as a dash', () => {
    const gate: ReleaseGate = { id: 'sample', label: 'Sample size', passed: null, value: 12, threshold: 20, detail: '12 of 20 jobs; 52m of the 2h minimum' }
    const f = gateFigures(gate)
    expect(render(f.value)).toBe('12 jobs')
    expect(render(f.threshold)).toBe('at least 20 jobs')
    expect(render(gateFigures({ ...gate, value: 1, threshold: 1 }).value)).toBe('1 job')
    expect(render(gateFigures({ ...gate, value: 1, threshold: 1 }).threshold)).toBe('at least 1 job')
    const pending: ReleaseGate = { id: 'p95', label: 'p95 duration', passed: null, threshold: 2400, detail: 'no successful jobs on the candidate yet' }
    expect(render(gateFigures(pending).value)).toContain('—')
    expect(render(gateFigures(pending).threshold)).toBe('at most 2.4 s')
  })
})
