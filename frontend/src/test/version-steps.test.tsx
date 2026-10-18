import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { WorkflowDefinition } from '../lib/types'
import { VersionStatus, VersionStepList, versionSteps } from '../pages/WorkflowVersionsPage'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

const DOC: WorkflowDefinition = {
  workflow: {
    do: [
      { check: { name: 'Check the image', call: 'provider', with: { provider: 'vsphere', action: 'image.get' } } },
      { create: { name: 'Create the VM', run: { workflow: 'create-vm', version: '^2', input: {} } } },
      { wire: { run: { workflow: 'web-environment' } } },
      { loop: { name: 'Each disk', for: { each: 'disk', in: '{{ $input.disks }}', do: [{ attach: { name: 'Attach', run: { workflow: 'attach-disk', version: '~1.4' } } }] } } },
    ],
  },
}

describe('a version\'s steps', () => {
  it('lists every step with its kind, and run steps with their version range', () => {
    const rows = versionSteps(DOC)
    expect(rows.map((r) => `${r.id}:${r.type}:${r.depth}`)).toEqual(['check:provider:0', 'create:run:0', 'wire:run:0', 'loop:for:0', 'attach:run:1'])
    expect(rows[1].runs).toEqual({ workflow: 'create-vm', range: '^2' })
    expect(rows[2].runs).toEqual({ workflow: 'web-environment', range: undefined })
    expect(rows[4].runs).toEqual({ workflow: 'attach-disk', range: '~1.4' })
    expect(rows[0].runs).toBeUndefined()
    expect(versionSteps(undefined)).toEqual([])
  })

  it('renders the range next to the workflow a run step runs', () => {
    const html = text(renderToString(<VersionStepList definition={DOC} />))
    expect(html).toContain('Create the VM')
    expect(html).toContain('href="#/workflows/create-vm"')
    expect(html).toContain('>^2<')
    expect(html).toContain('>~1.4<')
    expect(html).toContain('any version')
    expect(text(renderToString(<VersionStepList definition={{ workflow: { do: [] } }} />))).toContain('no steps')
  })
})

describe("a version's status", () => {
  it('says when a release rollback took the version out', () => {
    const rolledBack = text(renderToString(<VersionStatus version={{ status: 'retired', rolledBack: true }} />))
    expect(rolledBack).toContain('retired')
    expect(rolledBack).toContain('rolled back')
    expect(text(renderToString(<VersionStatus version={{ status: 'retired' }} />))).not.toContain('rolled back')
  })
})
