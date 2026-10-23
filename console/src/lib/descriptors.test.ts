import { describe, expect, it } from 'vitest'
import rawTypes from '../mock/samples/resource-types.json'
import rawCatalog from '../mock/samples/catalog.json'
import { buildMockData } from '../mock/data'
import { describeType, describeTypes } from '../mock/types'
import { DEFAULT_TABS, composeType, composeTypes, countByLifecycle, findType, groupServices, idOfWfrn, partsOfWfrn, purposeOf, serviceOf, singularOf, titleOf, wfrnOf, type ConsoleType } from './descriptors'
import type { BffConsoleType, CatalogEntry, Resource, ResourceType } from './types'

/*
 * The composition from the BFF's descriptors (GET /console/v1/types), as the mock BFF describes
 * the platform's captured samples (platform-fake at c9529d5, no hints), then the mock's copy of
 * them where the vm type carries P-06 hints.
 */

const types = rawTypes as unknown as ResourceType[]
const catalog = rawCatalog as unknown as CatalogEntry[]
const composed = composeTypes(describeTypes({ resourceTypes: types, catalog }))
const byName = (name: string): ConsoleType => {
  const t = composed.find((x) => x.name === name)
  if (!t) throw new Error(`no type ${name}`)
  return t
}

describe('the defaults over the raw samples', () => {
  it('groups by the owner without its team- prefix when nothing says otherwise', () => {
    expect(byName('vm').service).toBe('compute')
    expect(byName('database').service).toBe('data')
    expect(byName('image').service).toBe('platform')
    expect(serviceOf({ ...types[0], owner: 'team-data', service: 'storage' })).toBe('storage')
    expect(serviceOf({ ...types[0], owner: 'team-data', metadata: { service: 'Edge' } } as ResourceType)).toBe('edge')
  })

  it('titles a type by its plural, capitalised', () => {
    expect(composed.map((t) => t.title)).toEqual(['Vms', 'Databases', 'Environments', 'Images', 'Volumes'])
    expect(titleOf({ ...types[0], plural: 'mongodb-clusters' })).toBe('Mongodb clusters')
  })

  it('picks an icon Atlas has, by the type name', () => {
    expect(byName('vm').icon).toBe('terminal')
    expect(byName('database').icon).toBe('database')
    expect(byName('image').icon).toBe('layers')
    expect(byName('volume').icon).toBe('folder')
    expect(byName('environment').icon).toBe('grid')
  })

  it('lists name, status, wfrn, the required scalars, owner and created, without repeating name', () => {
    expect(byName('vm').columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'owner', 'created'])
    expect(byName('database').columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'engine', 'owner', 'created'])
    expect(byName('volume').columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'sizeGb', 'owner', 'created'])
    expect(byName('environment').columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'stage', 'owner', 'created'])
    const engine = byName('database').columns.find((c) => c.key === 'engine')
    expect(engine).toMatchObject({ kind: 'field', field: 'engine', label: 'Engine' })
    expect(byName('vm').defaultSort).toBe('name')
  })

  it('folds every state onto a lifecycle class by its name', () => {
    expect(byName('vm').lifecycle).toEqual({ building: 'pending', ready: 'active', deprecated: 'active', retired: 'deleted' })
    expect(byName('image').lifecycle.failed).toBe('failed')
  })

  it('takes the actions whose noun is the type, create first and delete last', () => {
    const vm = byName('vm')
    expect(vm.actions.map((a) => a.verb)).toEqual(['create', 'patch', 'restart', 'delete'])
    expect(vm.actions[vm.actions.length - 1]).toMatchObject({ destructive: true, method: 'DELETE' })
    expect(vm.create?.workflow).toBe('create-vm')
    expect(byName('environment').create?.workflow).toBe('web-environment')
    expect(byName('image').actions.map((a) => a.workflow)).toEqual(['build-vm-image'])
  })

  it('keeps the default tabs when the descriptor lists none', () => {
    expect(byName('vm').tabs).toEqual(['Configuration', 'Drift', 'Activity', 'Relationships', 'Tags', 'Permissions', 'JSON'])
    expect(composed.every((t) => t.tabs.join() === DEFAULT_TABS.join())).toBe(true)
  })

  it('runs only the verbs that name a workflow: the read verbs the BFF decides alone stay out', () => {
    const described = describeType(types.find((t) => t.name === 'vm') as ResourceType, catalog)
    const withReads: BffConsoleType = { ...described, actions: [{ verb: 'list', title: 'List vms', method: 'GET', allowed: true }, { verb: 'get', title: 'View a vm', method: 'GET', allowed: true }, ...described.actions] }
    expect(composeType(withReads).actions.map((a) => a.verb)).toEqual(['create', 'patch', 'restart', 'delete'])
  })

  it('groups the types into services, sorted', () => {
    const services = groupServices(composed)
    expect(services.map((s) => s.id)).toEqual(['compute', 'data', 'platform'])
    expect(services.map((s) => s.title)).toEqual(['Compute', 'Data', 'Platform'])
    expect(services[2].types.map((t) => t.title)).toEqual(['Environments', 'Images', 'Volumes'])
    expect(services[2].owners).toEqual(['team-platform'])
    expect(services[0].icon).toBe('terminal')
  })

  it('finds a type by service and name, or by name alone', () => {
    expect(findType(composed, 'compute', 'vm')?.name).toBe('vm')
    expect(findType(composed, 'elsewhere', 'vm')?.name).toBe('vm')
    expect(findType(composed, 'compute', 'nope')).toBeUndefined()
  })
})

describe('the hints of the mock copy', () => {
  const data = buildMockData()
  const hinted = composeTypes(describeTypes(data))
  const vm = hinted.find((t) => t.name === 'vm')
  const database = hinted.find((t) => t.name === 'database')

  it('reads the console block of the vm type', () => {
    expect(vm).toMatchObject({ title: 'Virtual machines', service: 'compute', icon: 'terminal', defaultSort: 'name' })
    expect(vm?.columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'cpu', 'memoryGb', 'environment', 'owner', 'created'])
    expect(vm?.columns.find((c) => c.key === 'memoryGb')?.x.format).toBe('gibibytes')
    expect(vm?.lifecycle).toEqual({ building: 'pending', ready: 'active', deprecated: 'active', retired: 'deleted' })
    expect(vm?.searchable).toEqual(['name', 'environment'])
  })

  it('leaves the other types on the defaults, and never shows a sensitive field', () => {
    expect(database).toMatchObject({ title: 'Databases', sensitive: ['secret'] })
    expect(database?.columns.map((c) => c.key)).toEqual(['name', 'status', 'wfrn', 'engine', 'owner', 'created'])
    expect(database?.columns.some((c) => c.key === 'secret')).toBe(false)
  })

  it('counts resources by lifecycle class for the tiles', () => {
    const vms = data.resources.filter((r) => r.type === 'vm')
    const counts = countByLifecycle(vms, vm as ConsoleType)
    expect(counts.active + counts.pending + counts.deleted + counts.failed + counts.other + counts.updating + counts.deleting).toBe(vms.length)
    expect(counts.active).toBeGreaterThan(counts.pending)
  })
})

describe('hints the samples do not carry', () => {
  const vm = types.find((t) => t.name === 'vm') as ResourceType
  const hinted = (console: unknown) => composeType(describeType({ ...vm, console: console as ResourceType['console'] }, catalog))

  it('reads a descending default sort, an alias, and falls back to the name for a key no column has', () => {
    const desc = hinted({ list: { defaultSort: '-created' } })
    expect([desc.defaultSort, desc.defaultSortDir]).toEqual(['created', 'desc'])
    const alias = hinted({ list: { defaultSort: 'createdAt' } })
    expect([alias.defaultSort, alias.defaultSortDir]).toEqual(['created', 'asc'])
    const unknown = hinted({ list: { defaultSort: '-nope' } })
    expect([unknown.defaultSort, unknown.defaultSortDir]).toEqual(['name', 'asc'])
    expect(composeType(describeType(vm, catalog)).defaultSortDir).toBe('asc')
  })

  it('labels the tabs a hint names by id, and keeps the defaults for an empty list', () => {
    expect(hinted({ detail: { tabs: ['configuration', 'drift', 'monitoring', 'json', 'cost-and-usage', 'drift'] } }).tabs).toEqual(['Configuration', 'Drift', 'Monitoring', 'JSON', 'Cost and usage'])
    expect(hinted({ detail: { tabs: [] } }).tabs).toEqual([...DEFAULT_TABS])
    expect(hinted({ detail: { tabs: ['', 7] } }).tabs).toEqual([...DEFAULT_TABS])
  })
})

describe('wfrns', () => {
  const ctx = { partition: 'wf', region: 'global', account: '123456789012' }
  const base: Resource = { id: 'database-demo01', type: 'database', status: 'ready', attributes: {}, owner: 'team-data', visibility: 'team', createdBy: { jobId: 'job_1', workflow: 'create-database' }, builtFrom: [], createdAt: '2026-01-01T00:00:00Z' }

  it('keeps the wfrn the platform minted', () => {
    expect(wfrnOf({ ...base, wfrn: 'wfrn:wf:data:global:210987654321:database/database-demo01' }, { name: 'database', service: 'data' }, ctx)).toEqual({ wfrn: 'wfrn:wf:data:global:210987654321:database/database-demo01', derived: false })
  })

  it('derives one from the context, the resource account first', () => {
    expect(wfrnOf(base, { name: 'database', service: 'data' }, ctx)).toEqual({ wfrn: 'wfrn:wf:data:global:123456789012:database/database-demo01', derived: true })
    expect(wfrnOf({ ...base, accountId: '210987654321', region: 'eu-west' }, { name: 'database', service: 'data' }, ctx).wfrn).toBe('wfrn:wf:data:eu-west:210987654321:database/database-demo01')
    expect(wfrnOf({ ...base, id: 'Odd Id!' }, { name: 'database', service: 'Team Data' }, ctx).wfrn).toBe('wfrn:wf:team-data:global:123456789012:database/odd-id')
  })

  it('reads the parts and the id back', () => {
    expect(partsOfWfrn('wfrn:wf:compute:global:123456789012:vm/vm-demo01')).toEqual({ partition: 'wf', service: 'compute', region: 'global', account: '123456789012', type: 'vm', id: 'vm-demo01' })
    expect(idOfWfrn('wfrn:wf:compute:global:123456789012:vm/vm-demo01')).toBe('vm-demo01')
    expect(idOfWfrn('wfrn:wf:compute:global:123456789012:vm/nested/id')).toBe('nested/id')
    expect(idOfWfrn('not a wfrn')).toBeNull()
  })
})

describe('words', () => {
  it('singularises titles', () => {
    expect(singularOf('Virtual machines')).toBe('Virtual machine')
    expect(singularOf('Databases')).toBe('Database')
    expect(singularOf('Policies')).toBe('Policy')
    expect(singularOf('Addresses')).toBe('Address')
    expect(singularOf('Status')).toBe('Status')
    expect(singularOf('Vms')).toBe('Vm')
  })

  it('says what a type is for', () => {
    expect(purposeOf({ title: 'Vms', description: ' A machine. ', create: null, owner: 'team-compute', states: [] })).toBe('A machine.')
    expect(purposeOf({ title: 'Vms', create: { title: 'Create a VM' } as ConsoleType['create'], owner: 'team-compute', states: ['building', 'ready', 'retired'] })).toBe('Vms are created by the "Create a VM" workflow and tracked by the platform from building to retired.')
    expect(purposeOf({ title: 'Vms', create: null, owner: 'team-compute', states: [] })).toBe("Vms are created by team-compute's workflows and tracked by the platform.")
  })
})

describe('what the BFF’s descriptor says of a type', () => {
  const vm = types.find((t) => t.name === 'vm') as ResourceType
  const bare = describeType({ ...vm, observe: undefined }, catalog)

  it('decides the Monitoring tab and discovery', () => {
    expect(composeType(bare).tabs).not.toContain('Monitoring')
    expect(composeType(bare).observe).toEqual({ supported: false, interval: undefined, metrics: false, discover: false })
    const described = composeType({ ...bare, observe: { supported: true, interval: '1h', metrics: true, discover: true } })
    expect(described.observe).toEqual({ supported: true, interval: '1h', metrics: true, discover: true })
    expect(described.tabs).toContain('Monitoring')
  })

  it('reads what the descriptor leaves out as not offered', () => {
    expect(composeType({ ...bare, observe: { supported: true, interval: '6h', metrics: true } }).observe).toEqual({ supported: true, interval: '6h', metrics: true, discover: false })
    expect(composeTypes([{ ...bare, key: 'data/database', type: 'database', service: 'data', observe: { metrics: false } }])[0].observe.metrics).toBe(false)
  })

  it('takes the producer of a type as its create, by the effect the descriptor gives it', () => {
    const image = composeType(describeType(types.find((t) => t.name === 'image') as ResourceType, catalog))
    expect(image.create?.workflow).toBe('build-vm-image')
    expect(image.create?.verb).toBe('build')
  })

  it('reads the platform’s own block only in the mock BFF that describes it', () => {
    const own = { ...vm, observe: { provider: 'vsphere', action: 'vm.get', interval: '6h', metrics: { action: 'vm.metrics' } } } as ResourceType
    expect(composeType(describeType(own, catalog)).observe).toEqual({ supported: true, interval: '6h', metrics: true, discover: false })
  })
})
