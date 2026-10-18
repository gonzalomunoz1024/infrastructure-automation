import { parse as parseYaml } from 'yaml'
import { describe, expect, it } from 'vitest'
import type { WorkflowDocument } from '@iaf/workflow-checks'
import { API_TRIGGER_ID, ERROR_TRIGGER_ID, documentToGraph, findStepNode, graphToDocument, isStepNode, stepIds, type Graph } from './graph'

const SAMPLE_FILES = import.meta.glob('../../../../workflows/*/*.yaml', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

/** The nine sample workflows, loaded with the yaml package (the browser never does this). */
export function sampleDocuments(): { file: string; doc: WorkflowDocument }[] {
  return Object.entries(SAMPLE_FILES)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([path, text]) => ({ file: path.split('/').slice(-2).join('/'), doc: parseYaml(text) as WorkflowDocument }))
}

const minimal = (steps: unknown[], extra: Partial<WorkflowDocument> = {}): WorkflowDocument =>
  ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', noun: 'thing', verb: 'poke' }, workflow: { do: steps }, ...extra }) as WorkflowDocument

const edge = (g: Graph, source: string, handle: string) => g.edges.find((e) => e.source === source && e.sourceHandle === handle)

describe('round trips', () => {
  const samples = sampleDocuments()
  it('finds the sample workflows', () => {
    expect(samples.length).toBeGreaterThanOrEqual(9)
  })
  for (const { file, doc } of samples) {
    it(`graphToDocument(documentToGraph(doc)) deep-equals ${file}`, () => {
      const graph = documentToGraph(doc)
      expect(graph.problems).toEqual([])
      expect(graphToDocument(graph, doc)).toEqual(doc)
    })
  }
  it('keeps keys the canvas does not understand', () => {
    const doc = minimal([{ a: { notify: { channel: '#x', message: 'm', 'x-custom': { deep: [1, 2] } }, name: 'A', notes: 'keep me', with: { extra: true } } }], { 'x-extra': true } as Partial<WorkflowDocument>)
    expect(graphToDocument(documentToGraph(doc), doc)).toEqual(doc)
  })
  it('keeps how long a paused step waits for a decision', () => {
    const doc = minimal([
      { a: { notify: { channel: '#x', message: 'm' }, attentionTimeout: 'PT4H' } },
      { b: { notify: { channel: '#x', message: 'm' }, pauseOnFailure: false, attentionTimeout: 'P2D' } },
    ])
    const graph = documentToGraph(doc)
    expect(findStepNode(graph, 'a')?.data.step.attentionTimeout).toBe('PT4H')
    expect(graphToDocument(graph, doc)).toEqual(doc)
  })
  it("keeps an ansible step's inventory and limit in every shape the runner reads", () => {
    const ansible = (inventory: unknown, limit?: unknown) => ({ call: 'ansible', with: { playbook: 'ops/restart', inventory, ...(limit === undefined ? {} : { limit }) } })
    const doc = minimal([
      { a: ansible('dynamic', '{{ $vm.name }}') },
      { b: ansible('inventories/prod.ini', 'web*:!web3') },
      { c: ansible(['web1', 'web2'], ['web1']) },
      { d: ansible({ hosts: 'web1,web2', group: 'web', vars: { http_port: 8080 } }) },
      { e: { call: 'ansible', with: { playbook: 'ops/restart' } } },
    ])
    const graph = documentToGraph(doc)
    expect(findStepNode(graph, 'd')?.data.body.with).toEqual({ playbook: 'ops/restart', inventory: { hosts: 'web1,web2', group: 'web', vars: { http_port: 8080 } } })
    expect(graphToDocument(graph, doc)).toEqual(doc)
  })
  it('writes positions only for positioned nodes', () => {
    const doc = minimal([{ a: { notify: { channel: '#x', message: 'm' } } }, { b: { notify: { channel: '#x', message: 'm' }, position: { x: 10, y: 20 } } }])
    const graph = documentToGraph(doc)
    expect(findStepNode(graph, 'a')?.positioned).toBe(false)
    expect(findStepNode(graph, 'b')?.position).toEqual({ x: 10, y: 20 })
    const moved: Graph = { ...graph, nodes: graph.nodes.map((n) => (n.id === 'step:a' ? { ...n, position: { x: 300.4, y: 7.6 }, positioned: true } : n)) }
    const out = graphToDocument(moved, doc)
    expect(out.workflow?.do?.[0]).toEqual({ a: { notify: { channel: '#x', message: 'm' }, position: { x: 300, y: 8 } } })
  })
})

describe('triggers', () => {
  it('draws the API trigger with method and path, one node per trigger, all feeding the first step', () => {
    const doc = minimal([{ a: { wait: 'PT5M' } }], {
      api: { method: 'POST', path: '/api/v1/things/{id}:poke' },
      triggers: [{ alert: { match: { source: 'grafana' } } }, { event: { topic: 'thing.created' } }],
    })
    const g = documentToGraph(doc)
    const api = g.nodes.find((n) => n.id === API_TRIGGER_ID)
    expect(api?.data).toMatchObject({ kind: 'trigger', triggerType: 'api', subtitle: 'POST /api/v1/things/{id}:poke' })
    expect(g.nodes.filter((n) => n.data.kind === 'trigger').map((n) => (n.data.kind === 'trigger' ? n.data.triggerType : ''))).toEqual(['api', 'alert', 'event'])
    expect(g.edges.filter((e) => e.role === 'trigger').map((e) => e.target)).toEqual(['step:a', 'step:a', 'step:a'])
  })
  it('infers the method from the verb when the api block is absent', () => {
    const g = documentToGraph(minimal([], { metadata: { name: 'x', noun: 'vm', verb: 'delete' } }))
    expect(g.nodes[0].data).toMatchObject({ subtitle: 'DELETE /api/v1/vms' })
  })
})

describe('switch', () => {
  const doc = minimal([
    {
      s: {
        switch: [
          { case: { when: '{{ $input.a }}', then: 'c' } },
          { case: { when: '{{ $input.b }}', then: 'end' } },
          { case: { when: '{{ $input.d }}', do: [{ nested: { set: { x: 1 } } }] } },
          { default: 'c' },
        ],
      },
    },
    { c: { wait: 'PT1M' } },
  ])
  const g = documentToGraph(doc)
  it('has one output per case plus default, labelled with the when text', () => {
    const s = findStepNode(g, 's')
    expect(s?.data.outputs.map((o) => o.label)).toEqual(['$input.a', '$input.b', '$input.d', 'default'])
  })
  it('jumps, ends, branches and joins', () => {
    expect(edge(g, 'step:s', 'case:0')).toMatchObject({ target: 'step:c', role: 'jump' })
    expect(edge(g, 'step:s', 'case:1')).toMatchObject({ role: 'jump' })
    expect(g.nodes.find((n) => n.id === edge(g, 'step:s', 'case:1')?.target)?.data.kind).toBe('end')
    expect(edge(g, 'step:s', 'case:2')).toMatchObject({ target: 'step:nested', role: 'branch' })
    expect(edge(g, 'step:nested', 'main')).toMatchObject({ target: 'step:c', role: 'join', exits: ['step:s'] })
    expect(edge(g, 'step:s', 'default')).toMatchObject({ target: 'step:c', role: 'jump' })
  })
  it('round-trips', () => {
    expect(graphToDocument(g, doc)).toEqual(doc)
  })
  it('shows a fallthrough edge when there is no default case and rebuilds the same document', () => {
    const d = minimal([{ s: { switch: [{ case: { when: '{{ 1 }}', then: 'end' } }] } }, { c: { wait: 'PT1M' } }])
    const gg = documentToGraph(d)
    expect(edge(gg, 'step:s', 'default')).toMatchObject({ target: 'step:c', role: 'fallthrough' })
    expect(graphToDocument(gg, d)).toEqual(d)
  })
  it('reconnecting a case to another step rewrites then', () => {
    const changed: Graph = { ...g, edges: g.edges.map((e) => (e.source === 'step:s' && e.sourceHandle === 'case:0' ? { ...e, target: 'step:nested' } : e)) }
    const out = graphToDocument(changed, doc)
    expect(out.workflow?.do?.[0]?.s?.switch?.[0]).toEqual({ case: { when: '{{ $input.a }}', then: 'nested' } })
  })
})

describe('fork, for and try', () => {
  it('fork branches run in parallel and join on the next step', () => {
    const doc = minimal([{ f: { fork: { branches: [[{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }], [{ c: { wait: 'PT1M' } }], []] } } }, { j: { wait: 'PT1M' } }])
    const g = documentToGraph(doc)
    expect(findStepNode(g, 'f')?.data.outputs.map((o) => o.id)).toEqual(['branch:0', 'branch:1', 'branch:2'])
    expect(edge(g, 'step:f', 'branch:0')).toMatchObject({ target: 'step:a', role: 'branch' })
    expect(edge(g, 'step:a', 'main')).toMatchObject({ target: 'step:b', role: 'main' })
    expect(edge(g, 'step:b', 'main')).toMatchObject({ target: 'step:j', role: 'join', exits: ['step:f'] })
    expect(edge(g, 'step:c', 'main')).toMatchObject({ target: 'step:j', role: 'join' })
    expect(edge(g, 'step:f', 'branch:2')).toMatchObject({ target: 'step:j', role: 'join' })
    expect(graphToDocument(g, doc)).toEqual(doc)
  })
  it('for has loop and done outputs and the body loops back', () => {
    const doc = minimal([{ l: { for: { each: 'i', in: '{{ $input.items }}', do: [{ a: { wait: 'PT1M' } }, { b: { wait: 'PT1M' } }] } } }, { j: { wait: 'PT1M' } }])
    const g = documentToGraph(doc)
    expect(findStepNode(g, 'l')?.data.outputs.map((o) => o.id)).toEqual(['done', 'loop'])
    expect(edge(g, 'step:l', 'loop')).toMatchObject({ target: 'step:a', role: 'branch' })
    expect(edge(g, 'step:b', 'main')).toMatchObject({ target: 'step:l', role: 'loop' })
    expect(edge(g, 'step:l', 'done')).toMatchObject({ target: 'step:j', role: 'main' })
    expect(graphToDocument(g, doc)).toEqual(doc)
  })
  it('try and catch rejoin the next step', () => {
    const doc = minimal([{ t: { try: { do: [{ a: { wait: 'PT1M' } }], catch: { as: 'e', do: [{ c: { wait: 'PT1M' } }] } } } }, { j: { wait: 'PT1M' } }])
    const g = documentToGraph(doc)
    expect(edge(g, 'step:t', 'try')).toMatchObject({ target: 'step:a', role: 'branch' })
    expect(edge(g, 'step:t', 'catch')).toMatchObject({ target: 'step:c', role: 'branch' })
    expect(edge(g, 'step:a', 'main')).toMatchObject({ target: 'step:j', role: 'join', exits: ['step:t'] })
    expect(edge(g, 'step:c', 'main')).toMatchObject({ target: 'step:j', role: 'join', exits: ['step:t'] })
    expect(graphToDocument(g, doc)).toEqual(doc)
  })
  it('a container last inside another container exits through both', () => {
    const doc = minimal([
      { f: { fork: { branches: [[{ s: { switch: [{ case: { when: '{{ 1 }}', do: [{ a: { wait: 'PT1M' } }] } }] } }]] } } },
      { j: { wait: 'PT1M' } },
      { k: { wait: 'PT1M' } },
    ])
    const g = documentToGraph(doc)
    expect(edge(g, 'step:a', 'main')).toMatchObject({ target: 'step:j', role: 'join', exits: ['step:s', 'step:f'] })
    expect(edge(g, 'step:s', 'default')).toMatchObject({ target: 'step:j', role: 'fallthrough', exits: ['step:f'] })
    expect(graphToDocument(g, doc)).toEqual(doc)
    expect(stepIds(g)).toEqual(['f', 's', 'a', 'j', 'k'])
  })
})

describe('compensate and the error trigger', () => {
  it('shows compensate as an undo node and the catch flow behind an error trigger', () => {
    const doc = minimal([{ a: { wait: 'PT1M', compensate: [{ undoA: { notify: { channel: '#x', message: 'undo' } } }] } }], {
      workflow: { do: [{ a: { wait: 'PT1M', compensate: [{ undoA: { notify: { channel: '#x', message: 'undo' } } }] } }], catch: { do: [{ tell: { notify: { channel: '#x', message: 'failed' } } }] } },
    })
    const g = documentToGraph(doc)
    const undo = g.nodes.find((n) => n.data.kind === 'undo')
    expect(undo?.data).toMatchObject({ kind: 'undo', stepId: 'a', names: ['undoA'] })
    expect(edge(g, 'step:a', 'undo')).toMatchObject({ target: undo?.id, role: 'undo' })
    expect(g.nodes.some((n) => n.id === ERROR_TRIGGER_ID)).toBe(true)
    expect(edge(g, ERROR_TRIGGER_ID, 'main')).toMatchObject({ target: 'step:tell', role: 'trigger' })
    expect(g.nodes.filter(isStepNode).find((n) => n.data.stepId === 'tell')?.data.inCatch).toBe(true)
    expect(graphToDocument(g, doc)).toEqual(doc)
  })
  it('dropping the undo node removes compensate; dropping the error trigger removes catch', () => {
    const doc = minimal([{ a: { wait: 'PT1M', compensate: [{ u: { wait: 'PT1M' } }] } }], {
      workflow: { do: [{ a: { wait: 'PT1M', compensate: [{ u: { wait: 'PT1M' } }] } }], catch: { do: [] } },
    })
    const g = documentToGraph(doc)
    const trimmed: Graph = { ...g, nodes: g.nodes.filter((n) => n.data.kind !== 'undo' && n.id !== ERROR_TRIGGER_ID), edges: g.edges.filter((e) => e.role !== 'undo' && e.source !== ERROR_TRIGGER_ID) }
    expect(graphToDocument(trimmed, doc)).toEqual(minimal([{ a: { wait: 'PT1M' } }]))
  })
  it('reports a document that does not parse as one problem', () => {
    const g = documentToGraph({ kind: 'Workflow', metadata: { name: 'x' }, workflow: { do: [{ a: { loop: {} } }] } } as unknown as WorkflowDocument)
    expect(g.problems).toEqual([{ level: 'error', code: 'yaml.invalid', message: "Step 'a': unknown key 'loop'" }])
    expect(g.nodes.map((n) => n.id)).toEqual([API_TRIGGER_ID])
  })
})
