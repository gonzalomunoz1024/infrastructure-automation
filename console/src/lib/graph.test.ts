import { describe, expect, it } from 'vitest'
import { costShadeOf, depthsOf, driftShadeOf, healthOf, layoutMap, moveFrom } from './graph'
import type { GraphNode } from './state'

const node = (wfrn: string, type: string, extra: Partial<GraphNode> = {}): GraphNode => ({ wfrn, type, name: wfrn, state: 'active', accountId: '111111111111', region: 'eu-west-1', ...extra })

const nodes = [node('img', 'image'), node('vm-a', 'vm'), node('vm-b', 'vm'), node('vol', 'volume'), node('alone', 'bucket')]
const edges = [
  { from: 'img', to: 'vm-a' },
  { from: 'img', to: 'vm-b' },
  { from: 'vm-a', to: 'vol' },
]

describe('the map layout', () => {
  it('puts each resource in the column of its longest chain of parents', () => {
    const d = depthsOf(nodes, edges)
    expect(Object.fromEntries(d)).toEqual({ img: 0, 'vm-a': 1, 'vm-b': 1, vol: 2, alone: 0 })
  })

  it('survives a cycle', () => {
    const d = depthsOf([node('a', 'vm'), node('b', 'vm')], [{ from: 'a', to: 'b' }, { from: 'b', to: 'a' }])
    expect(d.size).toBe(2)
  })

  it('stacks a long column', () => {
    const many = Array.from({ length: 5 }, (_, i) => node(`n${i}`, 'vm'))
    const l = layoutMap(many, [], 2)
    expect(new Set(l.nodes.map((p) => p.x)).size).toBe(3)
    expect(l.columns).toHaveLength(1)
  })

  it('moves along the links and within a column', () => {
    const l = layoutMap(nodes, edges)
    expect(moveFrom(l, undefined, 'ArrowDown')).toBe(l.nodes[0].node.wfrn)
    expect(moveFrom(l, 'vm-a', 'ArrowLeft')).toBe('img')
    expect(moveFrom(l, 'vm-a', 'ArrowRight')).toBe('vol')
    expect(moveFrom(l, 'vm-a', 'ArrowDown')).toBe('vm-b')
    expect(moveFrom(l, 'vm-b', 'ArrowDown')).toBe('vm-b')
    expect(moveFrom(l, 'vol', 'ArrowRight')).toBe('vol')
  })
})

describe('the colours', () => {
  it('says health, drift and cost in words', () => {
    expect(healthOf(node('x', 'vm', { state: 'failed' })).tone).toBe('critical')
    expect(healthOf(node('x', 'vm')).words).toBe('Healthy')
    expect(driftShadeOf(node('x', 'vm')).words).toBe('Not watched')
    expect(driftShadeOf(node('x', 'vm', { drift: { findingId: 'f', severity: 'critical' } })).tone).toBe('critical')
    expect(costShadeOf(node('x', 'vm')).tone).toBe('neutral')
    expect(costShadeOf(node('x', 'vm', { cost: { monthly: 300, currency: 'USD' } })).tone).toBe('cost-4')
    expect(costShadeOf(node('x', 'vm', { cost: { monthly: 10, currency: 'USD' } })).tone).toBe('cost-1')
  })
})
