import { describe, expect, it } from 'vitest'
import { check, EMPTY_CONTEXT } from '@iaf/workflow-checks'
import type { Registry } from '../model/registry'
import { buildCatalog, leavesOf, ROOT_VIEW, searchCatalog } from './catalog'
import { placementOf } from './NodeCreator'
import { documentToGraph } from '../model/graph'
import { bodyFor } from '../model/edits'

const registry: Registry = {
  stepTypes: [],
  providers: [
    { name: 'vsphere', title: 'VMware vSphere', type: 'openapi', owner: 'team-compute', actions: [{ id: 'vm.get', title: 'Get VM', effect: 'read', retry: 'safe' }, { id: 'vm.delete', title: 'Delete VM', effect: 'delete', retry: 'unsafe', approval: 'required' }] },
    {
      name: 'tf',
      title: 'Terraform modules',
      type: 'terraform',
      module: { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' },
      actions: [
        { id: 'apply', title: 'Apply', effect: 'change', retry: 'unsafe' },
        { id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' },
      ],
    },
    { name: 'tf-bare', title: 'Bare modules', type: 'terraform', actions: [{ id: 'plan', title: 'Plan', effect: 'read', retry: 'safe' }] },
    { name: 'portal', title: 'Portal', type: 'idp', actions: [{ id: 'db.request', title: 'Request a database', effect: 'create', retry: 'unsafe' }] },
  ],
  workflows: [
    { name: 'vm-restart', title: 'VM restart', version: '1.2.0', input: {}, output: {} },
    { name: 'vm-patch', title: 'VM patch', version: '2.0.1', input: {}, output: {} },
  ],
  resourceTypes: [{ name: 'vm', plural: 'vms', owner: 'team-compute', schema: {}, states: ['ready'] }],
  playbooks: [{ name: 'ops-playbooks/vm.restart', version: '3.1.0', path: 'vm/restart.yml', owner: 'team-platform' }],
  credentials: [],
  failed: [],
}

describe('node creator catalog', () => {
  const catalog = buildCatalog(registry, { metadata: { name: 'vm-restart' } })

  it('opens on the categories, then the providers and their actions', () => {
    const root = catalog.get(ROOT_VIEW)
    expect(root?.sections[0].items.map((i) => i.title)).toEqual(['Providers', 'Flow', 'Human', 'Resources', 'Code', 'Ansible', 'Terraform', 'IDP tiles'])
    const vsphere = catalog.get('provider:vsphere')
    const del = vsphere?.sections[0].items.find((i) => i.title === 'Delete VM')
    expect(del?.tags?.map((t) => t.label)).toEqual(['delete', 'approval'])
    expect(del?.action).toEqual({ kind: 'step', type: 'provider', params: { provider: 'vsphere', action: 'vm.delete', name: 'Delete VM' } })
  })

  it('offers other workflows to run, never the workflow itself', () => {
    const runs = catalog.get('workflows')?.sections[0].items ?? []
    expect(runs.map((i) => i.title)).toEqual(['VM patch'])
    expect(runs[0].action).toMatchObject({ type: 'run', params: { workflow: 'vm-patch', version: '^2' } })
  })

  it('adds library playbooks by their <provider>/<playbook> reference', () => {
    const lib = catalog.get('ansible')?.sections.find((s) => s.title === 'Library')
    expect(lib?.items[0].action).toMatchObject({ type: 'ansible', params: { playbook: 'ops-playbooks/vm.restart' } })
  })

  it('maps terraform actions and IDP tiles to their steps', () => {
    const apply = catalog.get('terraform')?.sections.find((s) => s.title === 'Terraform modules')?.items[0]
    expect(apply?.action).toMatchObject({ type: 'terraform', params: { terraformAction: 'apply' } })
    const tile = catalog.get('idp')?.sections[0].items[0]
    expect(tile?.action).toMatchObject({ type: 'idp', params: { tile: 'db.request' } })
  })

  it("inserts a terraform provider's action with the provider's own module, so the step passes the module check", () => {
    const tf = catalog.get('terraform')?.sections.find((s) => s.title === 'Terraform modules')
    const plan = tf?.items.find((i) => i.title === 'Plan')
    expect(plan?.action).toEqual({
      kind: 'step',
      type: 'terraform',
      params: { name: 'Terraform modules: Plan', module: { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' }, terraformAction: 'plan' },
    })
    expect(plan?.description).toBe('Terraform modules · plan · git::https://github.com/acme/tf-modules//lb @ 2.3.0')
    // the step body the store adds carries the module; the checks find nothing to say about it
    const params = plan?.action?.kind === 'step' ? plan.action.params : undefined
    const body = bodyFor('terraform', params)
    expect(body.with).toEqual({ module: { source: 'git::https://github.com/acme/tf-modules//lb', version: '2.3.0' }, action: 'plan', vars: {} })
    const problems = check({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x', title: 'X', owner: 't', version: '0.1.0' }, workflow: { do: [{ plan: body }] } }, EMPTY_CONTEXT)
    expect(problems.filter((p) => p.code.startsWith('terraform.'))).toEqual([])
    // a provider whose module is not known yet still inserts a step to fill in
    const bare = catalog.get('terraform')?.sections.find((s) => s.title === 'Bare modules')?.items[0]
    expect(bare?.action).toMatchObject({ params: { module: { source: '', version: '' } } })
  })

  it('finds steps by fuzzy search, best match first', () => {
    const results = searchCatalog(catalog, 'wait')
    expect(results[0].items[0].title).toBe('Wait')
    expect(searchCatalog(catalog, 'get vm').flatMap((s) => s.items.map((i) => i.title))).toContain('Get VM')
    expect(searchCatalog(catalog, '   ')).toEqual([])
    expect(new Set(leavesOf(catalog).map((l) => l.item.id)).size).toBe(leavesOf(catalog).length)
  })

  it('says where the step goes', () => {
    const graph = documentToGraph({ metadata: { name: 'x' }, workflow: { do: [{ a: { name: 'Alpha', wait: 'PT1M' } }] } })
    expect(placementOf(null, graph)).toBe('Adds a step at the end of the workflow')
    expect(placementOf({ kind: 'after', step: 'a', handle: 'main' }, graph)).toBe('Adds a step after Alpha')
    expect(placementOf({ kind: 'before', step: 'a' }, graph)).toBe('Adds a step before Alpha')
    expect(placementOf({ kind: 'start', flow: 'catch' }, graph)).toBe('Adds the first step of the error flow')
  })
})
