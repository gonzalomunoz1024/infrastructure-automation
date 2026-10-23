import { describe, expect, it } from 'vitest'
import { pathFor, routeFor } from './router'

/* The create wizard has a page of its own; its static last segment outranks a resource's wfrn. */

describe('the create route', () => {
  it('resolves to the wizard, not to a resource called create', () => {
    const route = routeFor('#/console/compute/vm/create')
    expect(route.name).toBe('create')
    expect(route.params).toEqual({ service: 'compute', type: 'vm' })
    expect(routeFor('#/console/compute/vm/wfrn%3Awf%3Acompute%3Aglobal%3A123456789012%3Avm%2Fvm-demo01').name).toBe('resource')
  })

  it('is built from the service and the type, under an account when one is named', () => {
    expect(pathFor('create', { service: 'compute', type: 'vm' })).toBe('/console/compute/vm/create')
    expect(pathFor('create', { service: 'compute', type: 'vm' }, undefined, { accountId: '123456789012' })).toBe('/account/123456789012/console/compute/vm/create')
    expect(routeFor('#/account/123456789012/console/compute/vm/create')).toMatchObject({ name: 'create', accountId: '123456789012' })
  })
})
