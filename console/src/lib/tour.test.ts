import { describe, expect, it } from 'vitest'
import { buildMockData } from '../mock/data'
import { describeTypes } from '../mock/types'
import { composeTypes, groupServices, type ConsoleService, type ConsoleType } from './descriptors'
import { dependenciesOf, orderTypes, tourFor } from './tour'
import { verbsOf } from './verbs'

/* Getting started (WP 3.7): a tour per service, generated from what its providers publish. */

const data = buildMockData()
const types = composeTypes(describeTypes(data))
const services = groupServices(types)
const service = (id: string) => services.find((s) => s.id === id) as ConsoleService
const type = (name: string) => types.find((t) => t.name === name) as ConsoleType

describe('the order to create things in', () => {
  it('reads what a create needs from its x-resource inputs', () => {
    expect(dependenciesOf(type('vm').create)).toEqual(['image'])
    expect(dependenciesOf(type('volume').create)).toEqual(['vm'])
    expect(dependenciesOf(type('database').create)).toEqual([])
    expect(dependenciesOf(null)).toEqual([])
  })

  it('creates what a create needs before it, keeping the alphabetical order otherwise', () => {
    expect(orderTypes(service('platform').types).map((t) => t.name)).toEqual(['image', 'environment', 'volume'])
    expect(orderTypes([type('vm'), type('image')]).map((t) => t.name)).toEqual(['image', 'vm'])
  })
})

describe('a service’s tour', () => {
  it('walks compute: create a VM after its image, operate it, follow requests, see access', () => {
    const steps = tourFor(service('compute'), types)
    expect(steps.map((s) => s.id)).toEqual(['create-vm', 'operate-vm', 'follow', 'access'])
    expect(steps[0]).toMatchObject({ kind: 'create', title: 'Create a virtual machine', href: '/console/compute/vm/create' })
    expect(steps[0].detail).toBe('The Create VM workflow asks for VM name, image and size; it needs an image from Platform first.')
    expect(steps[1].detail).toContain('the Actions menu offers Patch CVE, VM restart and Delete VM')
    expect(steps[1].detail).toContain('Delete VM asks you to type the name and waits for an approval')
    expect(steps[2].href).toBe('/requests')
  })

  it('never offers a type’s producer as something to do to one of its resources', () => {
    expect(verbsOf(type('image'))).toEqual([])
    expect(verbsOf(type('vm')).map((a) => a.verb)).toEqual(['patch', 'restart', 'delete'])
    const steps = tourFor(service('platform'), types)
    expect(steps.map((s) => s.id)).toEqual(['create-image', 'create-environment', 'create-volume', 'follow', 'access'])
    expect(steps[0].title).toBe('Create an image')
    expect(steps[2].detail).toContain('it needs a virtual machine from Compute first')
  })

  it('points to the list of a type nothing creates', () => {
    const readOnly: ConsoleType = { ...type('database'), create: null, actions: [] }
    const steps = tourFor({ ...service('data'), types: [readOnly] }, types)
    expect(steps[0]).toMatchObject({ id: 'browse-database', title: 'Browse databases', href: '/console/data/database' })
  })
})
