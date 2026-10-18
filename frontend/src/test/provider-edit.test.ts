import { describe, expect, it } from 'vitest'
import { bumpPatch, buildProviderDocument, stateFromProvider, stepIssues, withVersion } from '../pages/ProviderWizardPage'
import type { ProviderDetail } from '../lib/types'

const YAML = ['apiVersion: iaf.platform/v1', 'kind: Provider', 'metadata:', '  name: vsphere', '  title: VMware vSphere', '  owner: team-compute', '  version: 1.3.0', 'spec:', '  type: openapi', '  spec:', '    url: https://vcenter.example.com/openapi.json', '    version: 8.0.2', ''].join('\n')

const DETAIL: ProviderDetail = {
  name: 'vsphere',
  title: 'VMware vSphere',
  type: 'openapi',
  owner: 'team-compute',
  version: '1.3.0',
  description: 'Virtual machines in each vCenter.',
  environments: ['dev', 'prod'],
  endpoints: { prod: 'https://vsphere.example.com/rest', dev: 'https://vsphere-dev.example.com/rest', lab: 'https://vsphere-lab.example.com/rest' },
  auth: { type: 'header', credential: 'vault:vsphere/token', header: 'X-Api-Key' },
  actionCount: 2,
  health: { status: 'healthy', successRate: 99.9, p50Ms: 120, p95Ms: 400, calls: 10, errorTrend: [], reasons: [] },
  slo: { successRate: 99.5, p95Ms: 900 },
  alerts: { channel: '#compute-alerts' },
  yaml: YAML,
  actions: [
    { id: 'vm.get', title: 'Get VM', operation: 'getVm', effect: 'read', retry: 'safe', input: { type: 'object', properties: { id: { type: 'string' } } }, output: { type: 'object' }, sample: { id: 'vm-1', powerState: 'poweredOn' }, overridden: false },
    { id: 'vm.delete', title: 'Delete VM', operation: { method: 'delete', path: '/vms/{id}' }, effect: 'delete', retry: 'unsafe', approval: 'required', plan: 'vm.get', input: { type: 'object' }, output: { type: 'object' }, overridden: true },
  ],
  daily: [],
  perAction: [],
  changeRequests: [],
}

describe('editing an existing provider', () => {
  it('bumps the patch version and leaves anything else alone', () => {
    expect(bumpPatch('1.3.0')).toBe('1.3.1')
    expect(bumpPatch('2.0.9')).toBe('2.0.10')
    expect(bumpPatch('next')).toBe('next')
  })

  it('rewrites only metadata.version in the file, whatever else says version', () => {
    const out = withVersion(YAML, '1.3.1')
    expect(out).toContain('  version: 1.3.1\n')
    expect(out).toContain('    version: 8.0.2\n')
    expect(out.replace('  version: 1.3.1\n', '  version: 1.3.0\n')).toBe(YAML)
    expect(withVersion('metadata:\n  version: "1.3.0" # pinned\nspec: {}\n', '1.4.0')).toBe('metadata:\n  version: 1.4.0 # pinned\nspec: {}\n')
    // a file without a metadata version comes back as it was
    expect(withVersion('kind: Provider\nspec:\n  version: 1.0.0\n', '1.0.1')).toBe('kind: Provider\nspec:\n  version: 1.0.0\n')
    expect(withVersion('metadata:\n  labels:\n    version: 1.0.0\n  name: x\n', '1.0.1')).toBe('metadata:\n  labels:\n    version: 1.0.0\n  name: x\n')
  })

  it('seeds the wizard with the file at the next patch, and the endpoints, auth and actions its calls use', () => {
    const s = stateFromProvider(DETAIL)
    expect(s.editing).toBe('vsphere')
    expect(s.version).toBe('1.3.1')
    expect(s.draftYaml).toContain('  version: 1.3.1\n')
    expect(s.draftYaml).toContain('url: https://vcenter.example.com/openapi.json')
    expect(s).toMatchObject({ type: 'openapi', title: 'VMware vSphere', name: 'vsphere', nameTouched: true, owner: 'team-compute', ownerTouched: true, description: 'Virtual machines in each vCenter.' })
    // the declared environments first, in their order, then any other endpoint
    expect(s.environments.map((e) => [e.name, e.url])).toEqual([
      ['dev', 'https://vsphere-dev.example.com/rest'],
      ['prod', 'https://vsphere.example.com/rest'],
      ['lab', 'https://vsphere-lab.example.com/rest'],
    ])
    expect(s.auth).toEqual({ type: 'header', credential: 'vault:vsphere/token', header: 'X-Api-Key' })
    expect(s.actions.map((a) => [a.id, a.operation, a.method, a.path, a.approval, a.plan])).toEqual([
      ['vm.get', 'getVm', 'GET', '', false, ''],
      ['vm.delete', '', 'DELETE', '/vms/{id}', true, 'vm.get'],
    ])
    expect(JSON.parse(s.actions[0].inputText)).toEqual({ type: 'object', properties: { id: { type: 'string' } } })
    expect(s.samples).toEqual({ 'vm.get': { id: 'vm-1', powerState: 'poweredOn' } })
    expect(s.slo).toEqual({ successRate: '99.5', p95Ms: '900', channel: '#compute-alerts' })
    // the connect step asks nothing about the spec while editing: the file keeps its own
    expect(stepIssues(s, 1)).toEqual([])
    // and the document the test and try calls describe still carries the endpoints and the credential reference
    const doc = buildProviderDocument(s).spec as { endpoints: Record<string, string>; auth: { credential: string } }
    expect(doc.endpoints.dev).toBe('https://vsphere-dev.example.com/rest')
    expect(doc.auth.credential).toBe('vault:vsphere/token')
  })

  it('falls back to a blank environment and no auth when the provider declares none', () => {
    const s = stateFromProvider({ ...DETAIL, type: 'ansible', environments: [], endpoints: {}, auth: undefined, slo: undefined, alerts: undefined, description: undefined })
    expect(s.environments).toHaveLength(1)
    expect(s.environments[0].url).toBe('')
    expect(s.auth).toEqual({ type: 'none', credential: '', header: '' })
    expect(s.slo).toEqual({ successRate: '', p95Ms: '', channel: '' })
    expect(s.description).toBe('')
    expect(stepIssues(s, 1)).toEqual([])
  })
})
