import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { API_TRIGGER_ID, COLUMN, ERROR_TRIGGER_ID, ROW, documentToGraph, stepNodeId } from './graph'
import { CANVAS_ORIGIN, autoLayout, positionAfter, tidy } from './layout'
import { sampleDocuments } from './graph.test'

const minimal = (steps: unknown[], extra: Partial<WorkflowDocument> = {}): WorkflowDocument =>
  ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', noun: 'thing', verb: 'poke' }, workflow: { do: steps }, ...extra }) as WorkflowDocument

const col = (x: number) => Math.round((x - CANVAS_ORIGIN.x) / COLUMN)
const row = (y: number) => (y - CANVAS_ORIGIN.y) / ROW

describe('autoLayout', () => {
  it('lays a chain out left to right, 250 px apart, the trigger in column 0', () => {
    const g = documentToGraph(minimal([{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }, { c: { wait: 'PT1M' } }]))
    const p = autoLayout(g)
    expect(col(p.get(API_TRIGGER_ID)!.x)).toBe(0)
    expect(['a', 'b', 'c'].map((id) => col(p.get(stepNodeId(id))!.x))).toEqual([1, 2, 3])
    expect(['a', 'b', 'c'].map((id) => row(p.get(stepNodeId(id))!.y))).toEqual([0, 0, 0])
    expect(p.get(stepNodeId('b'))!.x - p.get(stepNodeId('a'))!.x).toBe(250)
  })
  it('stacks branches vertically and puts the join after the widest branch', () => {
    const g = documentToGraph(minimal([{ f: { fork: { branches: [[{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }], [{ c: { wait: 'PT1M' } }]] } } }, { j: { wait: 'PT1M' } }]))
    const p = autoLayout(g)
    expect(col(p.get(stepNodeId('f'))!.x)).toBe(1)
    expect([col(p.get(stepNodeId('a'))!.x), row(p.get(stepNodeId('a'))!.y)]).toEqual([2, 0])
    expect([col(p.get(stepNodeId('b'))!.x), row(p.get(stepNodeId('b'))!.y)]).toEqual([3, 0])
    expect([col(p.get(stepNodeId('c'))!.x), row(p.get(stepNodeId('c'))!.y)]).toEqual([2, 1])
    expect([col(p.get(stepNodeId('j'))!.x), row(p.get(stepNodeId('j'))!.y)]).toEqual([4, 0])
  })
  it('hangs a loop body under its for node and the catch chain behind the error trigger', () => {
    const doc = minimal([{ l: { for: { each: 'i', in: '{{ $input.items }}', do: [{ a: { wait: 'PT1M' } }] } } }, { j: { wait: 'PT1M' } }], {
      workflow: { do: [{ l: { for: { each: 'i', in: '{{ $input.items }}', do: [{ a: { wait: 'PT1M' } }] } } }, { j: { wait: 'PT1M' } }], catch: { do: [{ tell: { wait: 'PT1M' } }] } },
    })
    const p = autoLayout(documentToGraph(doc))
    expect([col(p.get(stepNodeId('a'))!.x), row(p.get(stepNodeId('a'))!.y)]).toEqual([2, 1])
    expect(row(p.get(stepNodeId('j'))!.y)).toBe(0)
    expect(col(p.get(ERROR_TRIGGER_ID)!.x)).toBe(0)
    expect(row(p.get(ERROR_TRIGGER_ID)!.y)).toBeGreaterThan(row(p.get(stepNodeId('a'))!.y))
    expect(col(p.get(stepNodeId('tell'))!.x)).toBe(1)
    expect(p.get(stepNodeId('tell'))!.y).toBe(p.get(ERROR_TRIGGER_ID)!.y)
  })
  it('never puts two nodes on the same spot, for every sample', () => {
    for (const { file, doc } of sampleDocuments()) {
      const g = documentToGraph(doc)
      const p = autoLayout(g)
      expect(p.size, file).toBe(g.nodes.length)
      const spots = new Set([...p.values()].map((v) => `${v.x},${v.y}`))
      expect(spots.size, file).toBe(g.nodes.length)
    }
  })
  it('tidy marks every node positioned and positionAfter steps one column right', () => {
    const g = tidy(documentToGraph(minimal([{ a: { wait: 'PT1M' } }])))
    expect(g.nodes.every((n) => n.positioned)).toBe(true)
    const a = g.nodes.find((n) => n.id === stepNodeId('a'))!
    expect(positionAfter(g, a.id, 'main')).toEqual({ x: a.position.x + COLUMN, y: a.position.y })
  })
})
