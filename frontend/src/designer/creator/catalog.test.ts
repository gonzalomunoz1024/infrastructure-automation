import { describe, expect, it } from 'vitest'
import { check, EMPTY_CONTEXT } from '@iaf/workflow-checks'
import type { Registry } from '../model/registry'
import { blankAction } from '../../components/provider-editor/state'
import { buildCatalog, CONNECT_ENTRIES, draftAsProvider, leavesOf, providersWithDrafts, ROOT_VIEW, searchCatalog } from './catalog'
import { placementOf } from './NodeCreator'
import { documentToGraph } from '../model/graph'
import { bodyFor } from '../model/edits'
import { newProviderDraft, type ProviderDraft } from '../model/providerDrafts'

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

/*
 * The node creator as "Add provider": the Providers view connects a new system through the
 * Provider sheet, lists the workflow's drafts tagged "draft" beside the published providers, and
 * shows each published provider's health band.
 */
describe('connecting a new system from the node creator', () => {
  const backup: ProviderDraft = newProviderDraft('http', {
    title: 'Backup API',
    name: 'backup',
    owner: 'team-storage',
    actions: [
      blankAction({ id: 'jobs.list', title: 'List backup jobs', method: 'GET', path: '/jobs', exampleText: '{"items":[{"id":"b-1"}]}' }),
      blankAction({ id: 'jobs.cancel', title: 'Cancel a job', method: 'DELETE', path: '/jobs/{id}', effect: 'delete', retry: 'unsafe', approval: true }),
      blankAction({ id: 'unselected', selected: false }),
      blankAction({ id: '' }),
    ],
  })
  // the next patch of a published provider: its actions stand in for the published ones
  const vsphereNext: ProviderDraft = {
    ...newProviderDraft('openapi', { title: 'VMware vSphere', name: 'vsphere', owner: 'team-compute', actions: [blankAction({ id: 'vm.get', title: 'Get VM', operation: 'getVm' }), blankAction({ id: 'vm.power', title: 'Power a VM', operation: 'powerVm', effect: 'change', retry: 'unsafe' })] }),
    origin: 'edit',
    publishedVersion: '1.3.0',
  }
  const unnamed = newProviderDraft('openapi', { title: 'Not named yet' })
  const tfDraft = newProviderDraft('terraform', { title: 'DNS zones', name: 'dns', terraform: { source: 'git::https://github.com/acme/tf//dns', version: '1.0.0' } })
  const playbooks = newProviderDraft('ansible', { title: 'Ops playbooks', name: 'ops-next', version: '0.1.0', actions: [blankAction({ id: 'disk.grow', playbook: 'disk/grow.yml' })] })
  const drafts = { [backup.id]: backup, [vsphereNext.id]: vsphereNext, [unnamed.id]: unnamed, [tfDraft.id]: tfDraft, [playbooks.id]: playbooks }
  const catalog = buildCatalog(registry, { metadata: { name: 'vm-restart' } }, { drafts, health: { vsphere: 'degraded', portal: 'healthy' } })

  it('has the seven ways to connect a new system in the Providers view, each opening the Provider sheet but cURL, which is pasted into a new HTTP step', () => {
    const providers = catalog.get('providers')
    expect(providers?.sections.map((s) => s.title)).toEqual([undefined, 'Connect a new system', 'Run workflow'])
    const connect = providers?.sections[1].items ?? []
    expect(connect.map((i) => i.title)).toEqual(['API from an OpenAPI spec', 'HTTP request (cURL)', 'Ansible repository', 'Terraform module', 'IDP tile', 'SDK worker', 'Describe it (Ask Devin)'])
    expect(connect.map((i) => i.action)).toEqual([
      { kind: 'provider-sheet', entry: 'openapi' },
      { kind: 'step', type: 'http', importCurl: true },
      ...['ansible', 'terraform', 'idp', 'sdk', 'describe'].map((entry) => ({ kind: 'provider-sheet', entry })),
    ])
    // it says what it does: a step whose cURL is pasted, then saved as an action
    expect(connect[1].description).toBe('An HTTP step from a pasted cURL command, then saved as a provider action')
    expect(CONNECT_ENTRIES).toHaveLength(7)
    // also with no provider registered at all
    const empty = buildCatalog(null, { metadata: { name: 'x' } })
    expect(empty.get('providers')?.sections[1].items).toHaveLength(7)
    expect(empty.get('providers')?.sections[0].items[0].description).toContain('connect a new system below')
  })

  it('finds the entries by search, so "curl" or "swagger" gets there from anywhere', () => {
    expect(searchCatalog(catalog, 'curl')[0].items[0]).toMatchObject({ title: 'HTTP request (cURL)', action: { kind: 'step', type: 'http', importCurl: true } })
    expect(searchCatalog(catalog, 'swagger').flatMap((s) => s.items.map((i) => i.id))).toContain('connect:openapi')
    expect(searchCatalog(catalog, 'devin').flatMap((s) => s.items.map((i) => i.id))).toContain('connect:describe')
    expect(new Set(leavesOf(catalog).map((l) => l.item.id)).size).toBe(leavesOf(catalog).length)
  })

  it('lists the drafts with the published providers, tagged draft, and their actions as steps', () => {
    const rows = catalog.get('providers')?.sections[0].items ?? []
    expect(rows.map((r) => r.title)).toEqual(['Backup API', 'VMware vSphere'])
    expect(rows.map((r) => r.tags?.map((t) => t.label))).toEqual([['draft'], ['draft']])
    const view = catalog.get('provider:backup')
    expect(view?.subtitle).toBe('backup · draft, not published yet')
    const actions = view?.sections[0].items ?? []
    expect(actions.map((a) => a.title)).toEqual(['List backup jobs', 'Cancel a job'])
    expect(actions[1].tags?.map((t) => t.label)).toEqual(['delete', 'approval', 'draft'])
    expect(actions[0].action).toEqual({ kind: 'step', type: 'provider', params: { provider: 'backup', action: 'jobs.list', name: 'List backup jobs' } })
    // the next patch of vsphere stands in its place, with the actions it will have
    expect(catalog.get('provider:vsphere')?.sections[0].items.map((a) => a.title)).toEqual(['Get VM', 'Power a VM'])
  })

  it('puts terraform and ansible drafts where their kind of step is added', () => {
    const dns = catalog.get('terraform')?.sections.find((s) => s.title === 'DNS zones')
    expect(dns?.items[0].action).toMatchObject({ type: 'terraform', params: { module: { source: 'git::https://github.com/acme/tf//dns', version: '1.0.0' }, terraformAction: 'plan' } })
    expect(dns?.items[0].tags?.map((t) => t.label)).toContain('draft')
    const lib = catalog.get('ansible')?.sections.find((s) => s.title === 'Library')?.items ?? []
    expect(lib.map((i) => i.title)).toEqual(['ops-playbooks/vm.restart', 'ops-next/disk.grow'])
    expect(lib[1]).toMatchObject({ tags: [{ label: 'draft' }], action: { type: 'ansible', params: { playbook: 'ops-next/disk.grow' } } })
  })

  it("shows each published provider's health band, and none on a provider without one", () => {
    const rows = catalog.get('providers')?.sections[0].items ?? []
    expect(rows.find((r) => r.id === 'provider:vsphere')?.health).toBe('degraded')
    expect(rows.find((r) => r.id === 'provider:backup')?.health).toBeUndefined()
    expect(buildCatalog(registry, { metadata: {} }).get('providers')?.sections[0].items[0].health).toBeUndefined()
  })

  it('makes a draft a registry provider: its selected, named actions, schemas from the example, never listed twice', () => {
    expect(draftAsProvider(unnamed)).toBeNull()
    const p = draftAsProvider(backup)
    expect(p).toMatchObject({ name: 'backup', title: 'Backup API', type: 'http', owner: 'team-storage', draft: true })
    expect(p?.actions.map((a) => a.id)).toEqual(['jobs.list', 'jobs.cancel'])
    expect(p?.actions[0]).toMatchObject({ approval: 'none', input: null, output: { type: 'object', properties: { items: { type: 'array' } } }, sample: { items: [{ id: 'b-1' }] } })
    expect(p?.actions[1]).toMatchObject({ effect: 'delete', approval: 'required', output: null })
    // a registry that already lists the draft (as the canvas's own merge may) still lists it once
    const merged = providersWithDrafts({ ...registry, providers: [...registry.providers, { name: 'backup', title: 'Backup API', type: 'http', actions: [] }] }, [backup])
    expect(merged.filter((x) => x.name === 'backup')).toHaveLength(1)
    expect(merged.find((x) => x.name === 'backup')?.actions).toHaveLength(2)
    expect(providersWithDrafts(null)).toEqual([])
  })
})
