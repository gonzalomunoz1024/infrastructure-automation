import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { RegistryPlaybook, RegistryProvider } from '../lib/types'
import { IMPORT_SOURCES, importChoices, importField, importRequest } from '../components/workflow-import'

/*
 * The New workflow dialog's Import: every source offers what POST /workflows:import resolves as
 * its `ref` (an IDP tile's id, `<provider>/<playbook>`, a provider's name) and sends exactly that.
 */

const PROVIDERS: RegistryProvider[] = [
  {
    name: 'idp-tiles',
    title: 'IDP tiles',
    type: 'idp',
    owner: 'team-platform',
    actions: [
      { id: 'block-storage-request', title: 'Request block storage', effect: 'create', retry: 'unsafe' },
      { id: 'dns-record-request', title: 'Request a DNS record', effect: 'create', retry: 'unsafe' },
    ],
  },
  { name: 'portal-eu', title: 'Portal EU', type: 'idp', actions: [{ id: 'dns-record-request', title: 'Request a DNS record (EU)', effect: 'create', retry: 'unsafe' }] },
  { name: 'terraform-modules', title: 'Terraform modules', type: 'terraform', actions: [{ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }, { id: 'apply', title: 'Apply', effect: 'create', retry: 'unsafe' }] },
  { name: 'vsphere', title: 'VMware vSphere', type: 'openapi', actions: [{ id: 'vm.create', title: 'Create VM', effect: 'create', retry: 'unsafe' }] },
  { name: 'backup', title: 'Backup service', type: 'http', actions: [] },
]

const PLAYBOOKS: RegistryPlaybook[] = [{ name: 'ops-playbooks/vm.restart', version: '3.1.0', path: 'vm/restart.yml', owner: 'team-platform' }]

type Fetch = typeof fetch

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the import form', () => {
  it('offers the tiles of the idp providers for an IDP import, by tile id, once each', () => {
    const tiles = importChoices('idp', PLAYBOOKS, PROVIDERS)
    expect(tiles.map((t) => t.value)).toEqual(['block-storage-request', 'dns-record-request'])
    expect(tiles[0]).toEqual({ value: 'block-storage-request', label: 'Request block storage', hint: 'IDP tiles · block-storage-request' })
    expect(importField('idp')).toBe('Tile')
    // the provider's name is never the reference of an IDP import: the backend resolves tiles
    expect(tiles.some((t) => t.value === 'idp-tiles')).toBe(false)
  })

  it('offers library playbooks as <provider>/<playbook>, and providers of the source by name', () => {
    expect(importChoices('ansible', PLAYBOOKS, PROVIDERS)).toEqual([{ value: 'ops-playbooks/vm.restart', label: 'ops-playbooks/vm.restart', hint: 'vm/restart.yml · 3.1.0' }])
    expect(importChoices('terraform', PLAYBOOKS, PROVIDERS).map((o) => o.value)).toEqual(['terraform-modules'])
    expect(importChoices('openapi', PLAYBOOKS, PROVIDERS).map((o) => o.value)).toEqual(['vsphere'])
    expect(importChoices('openapi', PLAYBOOKS, PROVIDERS)[0].hint).toBe('vsphere · 1 action')
    expect(importField('ansible')).toBe('Playbook')
    expect(importField('terraform')).toBe('Provider')
  })

  it('builds the request of each of the four sources from the first option', async () => {
    const calls: { url: string; body: unknown }[] = []
    const f: Fetch = async (input, init) => {
      calls.push({ url: String(input), body: JSON.parse(String(init?.body)) })
      return new Response(JSON.stringify({ yaml: 'kind: Workflow\n', notes: [] }), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    vi.stubGlobal('fetch', f)
    for (const { value: source } of IMPORT_SOURCES) {
      const first = importChoices(source, PLAYBOOKS, PROVIDERS)[0]
      const body = importRequest(source, first.value)
      await api.importWorkflow(body.source, body.ref)
    }
    expect(calls.map((c) => c.url)).toEqual(Array(4).fill('/api/v1/workflows:import'))
    expect(calls.map((c) => c.body)).toEqual([
      { source: 'idp', ref: 'block-storage-request' },
      { source: 'ansible', ref: 'ops-playbooks/vm.restart' },
      { source: 'terraform', ref: 'terraform-modules' },
      { source: 'openapi', ref: 'vsphere' },
    ])
  })

  it('has nothing to offer when the registry has nothing of the kind', () => {
    expect(importChoices('idp', [], [])).toEqual([])
    expect(importChoices('ansible', [], PROVIDERS)).toEqual([])
    expect(importRequest('terraform', '  terraform-modules ')).toEqual({ source: 'terraform', ref: 'terraform-modules' })
  })
})
