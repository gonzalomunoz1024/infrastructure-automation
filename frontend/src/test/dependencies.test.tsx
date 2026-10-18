import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { Dependencies } from '../lib/types'
import { CyclesCallout, DependenciesView, describeCycle, linkNote } from '../pages/DependenciesPage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const DEPS: Dependencies = {
  resourceTypes: [
    {
      type: 'image',
      producers: [
        { workflow: 'build-vm-image', via: 'producedBy' },
        { workflow: 'build-vm-image', via: 'register', at: 'register-image' },
      ],
      consumers: [
        { workflow: 'create-vm', via: 'input', at: 'image' },
        { workflow: 'create-vm', via: 'use', at: 'use-image' },
        { workflow: 'web-environment', via: 'orCreateWith', at: 'image' },
      ],
    },
    { type: 'vm', producers: [{ workflow: 'create-vm', via: 'register', at: 'register-vm' }], consumers: [{ workflow: 'vm-restart', via: 'input', at: 'vmId' }] },
    { type: 'bucket', producers: [], consumers: [] },
  ],
  workflows: [
    {
      workflow: 'create-vm',
      version: '2.1.0',
      produces: ['vm'],
      consumes: ['image'],
      runs: [{ workflow: 'build-vm-image', via: 'orCreateWith', at: 'image' }],
      consumers: [{ workflow: 'web-environment', via: 'run', at: 'create', range: '^2' }],
    },
    { workflow: 'build-vm-image', version: '1.0.0', produces: ['image'], consumes: [], runs: [], consumers: [{ workflow: 'create-vm', via: 'orCreateWith', at: 'image' }] },
    { workflow: 'web-environment', version: '1.0.0', produces: [], consumes: ['image'], runs: [{ workflow: 'create-vm', via: 'run', at: 'create', range: '^2' }], consumers: [] },
  ],
  cycles: [],
}

describe('DependenciesPage', () => {
  it('says how each link is made, in plain words', () => {
    expect(linkNote({ workflow: 'w', via: 'producedBy' })).toBe('declared producer')
    expect(linkNote({ workflow: 'w', via: 'register', at: 'reg' })).toBe('registers one in step reg')
    expect(linkNote({ workflow: 'w', via: 'input', at: 'vmId' })).toBe('input vmId')
    expect(linkNote({ workflow: 'w', via: 'use', at: 'get' })).toBe('uses one in step get')
    expect(linkNote({ workflow: 'w', via: 'run', at: 'create' })).toBe('step create')
    expect(linkNote({ workflow: 'w', via: 'orCreateWith', at: 'image' })).toBe('builds one first for input image')
  })

  it('reads a cycle as who runs whom', () => {
    expect(describeCycle(['a', 'b', 'a'])).toBe('a runs b, and b runs a again')
    expect(describeCycle(['a', 'b', 'c', 'a'])).toBe('a runs b, b runs c, and c runs a again')
    expect(describeCycle(['a', 'a'])).toBe('a runs a again')
  })

  it('lists every resource type with its producers and consumers, linked to their pages', () => {
    const html = text(renderToString(<DependenciesView data={DEPS} />))
    for (const t of ['image', 'vm', 'bucket']) expect(html).toContain(`href="#/resources?type=${t}"`)
    expect(html).toContain('href="#/workflows/build-vm-image"')
    expect(html).toContain('declared producer')
    expect(html).toContain('registers one in step register-image')
    expect(html).toContain('input image')
    expect(html).toContain('uses one in step use-image')
    expect(html).toContain('builds one first for input image')
    // a type nothing touches says so instead of showing an empty cell
    expect(html).toContain('nothing produces it yet')
    expect(html).toContain('nothing consumes it')
  })

  it('shows what each workflow produces, consumes and runs, with the version range of a run step', () => {
    const html = text(renderToString(<DependenciesView data={DEPS} />))
    expect(html).toContain('data-testid="dependencies-create-vm"')
    expect(html).toContain('2.1.0')
    expect(html).toContain('^2')
    expect(html).toContain('step create')
    expect(html).toContain('href="#/workflows/web-environment"')
    expect(html).not.toContain('in a loop')
    expect(html).not.toContain('Cycles among runs')
  })

  it('calls out cycles among runs plainly and marks the workflows in them', () => {
    const cycles = [['create-vm', 'web-environment', 'create-vm']]
    const html = text(renderToString(<DependenciesView data={{ ...DEPS, cycles }} />))
    expect(html).toContain('These workflows run each other in a loop')
    expect(html).toContain('create-vm runs web-environment, and web-environment runs create-vm again')
    expect(html).toContain('in a loop')
    const callout = text(renderToString(<CyclesCallout cycles={[...cycles, ['a', 'b', 'a']]} />))
    expect(callout).toContain('2 loops among run steps')
    expect(callout).toContain('href="#/workflows/a"')
  })

  it('renders nothing for the cycles when there are none', () => {
    expect(renderToString(<CyclesCallout cycles={[]} />)).toBe('')
  })
})
