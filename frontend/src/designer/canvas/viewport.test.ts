import { describe, expect, it } from 'vitest'
import { openingViewport, START_ZOOM } from './viewport'

const PANE = { width: 1400, height: 880 }

describe('openingViewport', () => {
  it('leaves a workflow that fits at a readable zoom to fitView', () => {
    expect(openingViewport({ x: 0, y: 0, width: 1600, height: 400 }, PANE)).toBeNull()
    expect(openingViewport({ x: -200, y: 100, width: 600, height: 300 }, PANE)).toBeNull()
  })

  it('opens a long chain at its start, readable and centred vertically', () => {
    const vp = openingViewport({ x: 0, y: 0, width: 13_000, height: 300 }, PANE)
    expect(vp).not.toBeNull()
    expect(vp?.zoom).toBe(START_ZOOM)
    // the left edge of the graph sits just inside the pane
    expect(vp?.x).toBe(64)
    // the graph's middle is the pane's middle
    expect((vp?.y ?? 0) + 150 * START_ZOOM).toBeCloseTo(PANE.height / 2)
  })

  it('anchors a graph taller than the pane to its top', () => {
    const vp = openingViewport({ x: 100, y: -500, width: 9_000, height: 4_000 }, PANE)
    expect(vp).toEqual({ x: 64 - 100 * START_ZOOM, y: 64 + 500 * START_ZOOM, zoom: START_ZOOM })
  })

  it('ignores empty bounds and an unmeasured pane', () => {
    expect(openingViewport({ x: 0, y: 0, width: 0, height: 0 }, PANE)).toBeNull()
    expect(openingViewport({ x: 0, y: 0, width: 13_000, height: 300 }, { width: 0, height: 0 })).toBeNull()
  })
})
