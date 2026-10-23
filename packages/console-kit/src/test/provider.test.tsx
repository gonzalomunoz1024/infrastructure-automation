// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { ConsoleKitProvider, ResourceLineage, ResourcePicker, type Lineage, type Resource } from '../index'

;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const res = (id: string, type: string, name: string): Resource => ({ id, type, name, status: 'ready', attributes: {}, owner: 'team-compute', visibility: 'org', createdBy: { jobId: `job-${id}`, workflow: 'create-vm' }, builtFrom: [], createdAt: '2026-10-01T00:00:00Z' })

async function mount(ui: React.ReactNode) {
  const container = document.createElement('div')
  document.body.appendChild(container)
  let root: Root
  await act(async () => {
    root = createRoot(container)
    root.render(ui)
  })
  // let the provider's promises settle
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0))
  })
  return { container, unmount: () => act(() => root.unmount()) }
}

describe('ConsoleKitProvider', () => {
  it('feeds the picker the resource types and the resources of a type', async () => {
    const calls: string[] = []
    const t = await mount(
      <ConsoleKitProvider
        data={{
          resourceTypes: async () => [{ name: 'vm', plural: 'virtual machines' }],
          resourcesOf: async (type) => {
            calls.push(type)
            return [{ id: 'vm-1', type: 'vm', name: 'web-1', status: 'ready' }]
          },
        }}
      >
        <ResourcePicker type="vm" value="" onChange={() => {}} ariaLabel="VM" />
      </ConsoleKitProvider>,
    )
    expect(calls).toEqual(['vm'])
    expect(t.container.textContent).toContain('virtual machines from the inventory')
    t.unmount()
  })

  it('says when nothing is registered yet, and renders without a provider at all', async () => {
    const t = await mount(<ResourcePicker type="vm" value="" onChange={() => {}} ariaLabel="VM" createWith="create-vm" />)
    expect(t.container.textContent).toContain('No vms registered yet')
    expect(t.container.querySelector('a')?.getAttribute('href')).toBe('#/catalog/create-vm')
    t.unmount()
  })

  it('fetches a lineage through the provider and draws it, or shows why it could not', async () => {
    const lineage: Lineage = { resource: res('vm-1', 'vm', 'web-1'), builtFrom: [res('img-1', 'image', 'ubuntu')], dependents: [] }
    const ok = await mount(
      <ConsoleKitProvider data={{ lineage: async () => lineage }} links={{ resource: (r) => `#/console/compute/${r.type}/${r.id}` }}>
        <ResourceLineage id="vm-1" />
      </ConsoleKitProvider>,
    )
    expect(ok.container.querySelector('svg')?.getAttribute('aria-label')).toBe('Lineage of web-1: built from 1, 0 dependents')
    expect(ok.container.querySelector('a[href="#/console/compute/image/img-1"]')).not.toBeNull()
    ok.unmount()

    const failed = await mount(
      <ConsoleKitProvider data={{ lineage: async () => Promise.reject({ status: 404, error: 'No such resource', message: 'No such resource' }) }}>
        <ResourceLineage id="vm-9" />
      </ConsoleKitProvider>,
    )
    expect(failed.container.textContent).toContain('Not found')
    failed.unmount()
  })
})
