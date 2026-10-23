import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import { ConsoleKitProvider, LineageGraph, lineageGraph, lineageLayout, type Lineage, type Resource } from '../index'

/* Ported from the LineageGraph section of the platform's frontend/src/test/composites.test.tsx at c9529d5. */

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const res = (id: string, type: string, name: string): Resource => ({
  id,
  type,
  name,
  status: 'ready',
  attributes: {},
  owner: 'team-platform',
  visibility: 'org',
  createdBy: { jobId: `job-${id}`, workflow: 'build-vm-image' },
  builtFrom: [],
  createdAt: '2026-10-01T00:00:00Z',
})

describe('LineageGraph', () => {
  it('draws what it was built from, the resource, and what depends on it, each a link', () => {
    const lineage: Lineage = { resource: res('img-77', 'image', 'ubuntu-2404-cis1'), builtFrom: [res('tpl-1', 'template', 'ubuntu-2404')], dependents: [res('vm-1042', 'vm', 'web-1042'), res('vm-1043', 'vm', 'web-1043')] }
    const html = text(renderToString(<LineageGraph lineage={lineage} />))
    expect(html).toContain('<svg')
    for (const name of ['ubuntu-2404-cis1', 'ubuntu-2404', 'web-1042', 'web-1043']) expect(html).toContain(name)
    expect(html).toContain('href="#/resources/vm-1042"')
    expect(html).toContain('href="#/resources/tpl-1"')
    expect(html.match(/<path/g)?.length).toBeGreaterThanOrEqual(3)
  })

  it('links nodes where the app around it says resources live', () => {
    const lineage: Lineage = { resource: res('img-77', 'image', 'ubuntu-2404-cis1'), builtFrom: [], dependents: [res('vm-1042', 'vm', 'web-1042')] }
    const html = text(
      renderToString(
        <ConsoleKitProvider links={{ resource: (r) => `#/console/compute/${r.type}/${r.id}` }}>
          <LineageGraph lineage={lineage} />
        </ConsoleKitProvider>,
      ),
    )
    expect(html).toContain('href="#/console/compute/vm/vm-1042"')
  })

  it('says when either side is empty', () => {
    const html = text(renderToString(<LineageGraph lineage={{ resource: res('vol-1', 'volume', 'data-1'), builtFrom: [], dependents: [] }} />))
    expect(html).toContain('not built from another resource')
    expect(html).toContain('nothing depends on it')
  })

  /* the backend's transitive answer: a base image, the image, two VMs from it, a DNS record from one VM */
  const BASE = res('img-60', 'image', 'ubuntu-2404')
  const IMG = res('img-77', 'image', 'ubuntu-2404-cis1')
  const VM1 = res('vm-1042', 'vm', 'web-1042')
  const VM2 = res('vm-1043', 'vm', 'web-1043')
  const DNS = res('dns-1042', 'dns-record', 'web-1042.prod')
  const TRANSITIVE: Lineage = {
    resource: IMG,
    builtFrom: [BASE],
    dependents: [VM1, VM2],
    nodes: [IMG, BASE, VM1, VM2, DNS],
    edges: [
      { from: 'img-60', to: 'img-77', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1042', via: 'create-vm' },
      { from: 'img-77', to: 'vm-1043', via: 'create-vm' },
      { from: 'vm-1042', to: 'dns-1042', via: 'register-dns' },
    ],
  }
  const ids = (layout: ReturnType<typeof lineageLayout>) => layout.columns.map((c) => [c.depth, c.nodes.map((n) => n.id)])

  it('draws the whole graph the backend answers, two hops away included, each edge saying how', () => {
    expect(ids(lineageLayout(TRANSITIVE))).toEqual([
      [-1, ['img-60']],
      [0, ['img-77']],
      [1, ['vm-1042', 'vm-1043']],
      [2, ['dns-1042']],
    ])
    const html = text(renderToString(<LineageGraph lineage={TRANSITIVE} />))
    expect(html).toContain('href="#/resources/dns-1042"')
    expect(html).toContain('web-1042.prod')
    expect(html.match(/<path/g)?.length).toBe(4)
    expect(html).toContain('<title>web-1042.prod was built from web-1042 by register-dns</title>')
    expect(html).toContain('aria-label="Lineage of ubuntu-2404-cis1: built from 1, 2 dependents; 1 upstream and 3 downstream in all"')
    expect(html).not.toContain('nothing depends on it')
  })

  it('reads the direct lists as the same graph when the backend sends no nodes and edges', () => {
    const direct: Lineage = { resource: IMG, builtFrom: [BASE], dependents: [VM1, VM2] }
    expect(lineageGraph(direct).edges).toEqual([
      { from: 'img-60', to: 'img-77', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1042', via: 'build-vm-image' },
      { from: 'img-77', to: 'vm-1043', via: 'build-vm-image' },
    ])
    expect(ids(lineageLayout(direct))).toEqual([
      [-1, ['img-60']],
      [0, ['img-77']],
      [1, ['vm-1042', 'vm-1043']],
    ])
    expect(text(renderToString(<LineageGraph lineage={direct} />))).toContain('aria-label="Lineage of ubuntu-2404-cis1: built from 1, 2 dependents"')
  })

  it('puts a resource on its longest chain, so every edge points right', () => {
    // the image was built from both the base and a hardened base, itself built from the base
    const HARD = res('img-61', 'image', 'ubuntu-2404-hard')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [BASE, HARD],
      dependents: [],
      nodes: [IMG, BASE, HARD],
      edges: [
        { from: 'img-60', to: 'img-77' },
        { from: 'img-61', to: 'img-77' },
        { from: 'img-60', to: 'img-61' },
      ],
    })
    expect(ids(layout)).toEqual([
      [-2, ['img-60']],
      [-1, ['img-61']],
      [0, ['img-77']],
    ])
    expect(layout.upstream).toBe(2)
    expect(layout.downstream).toBe(0)
  })

  it('stays finite on a cycle, drops edges to resources it was not given, and keeps each edge once', () => {
    const A = res('a', 'thing', 'a')
    const B = res('b', 'thing', 'b')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [],
      dependents: [A],
      nodes: [A, B],
      edges: [
        { from: 'img-77', to: 'a' },
        { from: 'a', to: 'b' },
        { from: 'b', to: 'a' },
        { from: 'a', to: 'b' },
        { from: 'b', to: 'ghost' },
      ],
    })
    expect(layout.columns.flatMap((c) => c.nodes.map((n) => n.id)).sort()).toEqual(['a', 'b', 'img-77'])
    expect(layout.columns.every((c) => Math.abs(c.depth) <= 3)).toBe(true)
    expect(layout.edges).toHaveLength(3)
    // the resource itself is placed even when the backend's nodes leave it out
    expect(layout.columns.find((c) => c.depth === 0)?.nodes.map((n) => n.id)).toEqual(['img-77'])
  })

  it('orders a column after the resources it hangs from, so edges do not cross', () => {
    const P1 = res('p1', 'thing', 'z-first-parent')
    const P2 = res('p2', 'thing', 'a-second-parent')
    const C1 = res('c1', 'thing', 'z-child-of-second')
    const C2 = res('c2', 'thing', 'a-child-of-first')
    const layout = lineageLayout({
      resource: IMG,
      builtFrom: [],
      dependents: [P1, P2],
      nodes: [IMG, P1, P2, C1, C2],
      edges: [
        { from: 'img-77', to: 'p1' },
        { from: 'img-77', to: 'p2' },
        { from: 'p1', to: 'c2' },
        { from: 'p2', to: 'c1' },
      ],
    })
    // the parents by name; each child under its own parent, though by name it would come first
    expect(ids(layout)).toEqual([
      [0, ['img-77']],
      [1, ['p2', 'p1']],
      [2, ['c1', 'c2']],
    ])
  })
})
