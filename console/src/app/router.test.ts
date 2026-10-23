import { describe, expect, it } from 'vitest'
import { ROUTES, href, matchRoute, parseHash, pathFor, resolve, resourcePath, routeFor, splitAccount } from './router'

const WFRN = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'

describe('parseHash', () => {
  it('reads the path and the query', () => {
    expect(parseHash('#/console/compute?tab=all&x=1')).toEqual({ path: '/console/compute', query: { tab: 'all', x: '1' } })
  })

  it('normalises an empty hash, a missing slash and a trailing slash', () => {
    expect(parseHash('')).toEqual({ path: '/', query: {} })
    expect(parseHash('#')).toEqual({ path: '/', query: {} })
    expect(parseHash('#requests')).toEqual({ path: '/requests', query: {} })
    expect(parseHash('#/requests/')).toEqual({ path: '/requests', query: {} })
  })
})

describe('splitAccount', () => {
  it('reads a 12-digit account prefix off the path', () => {
    expect(splitAccount('/account/123456789012/console/compute')).toEqual({ accountId: '123456789012', path: '/console/compute' })
    expect(splitAccount('/account/123456789012')).toEqual({ accountId: '123456789012', path: '/' })
  })

  it('leaves other paths alone', () => {
    expect(splitAccount('/console/compute')).toEqual({ accountId: null, path: '/console/compute' })
    expect(splitAccount('/account/12345/x')).toEqual({ accountId: null, path: '/account/12345/x' })
    expect(splitAccount('/accounts/123456789012')).toEqual({ accountId: null, path: '/accounts/123456789012' })
  })
})

describe('matchRoute', () => {
  it('decodes params, so a wfrn travels as one segment', () => {
    expect(matchRoute('/console/:service/:type/:wfrn', `/console/compute/vm/${encodeURIComponent(WFRN)}`)).toEqual({ service: 'compute', type: 'vm', wfrn: WFRN })
  })

  it('rejects a different length or a static mismatch', () => {
    expect(matchRoute('/console/:service', '/console/compute/vm')).toBeNull()
    expect(matchRoute('/requests/:id', '/jobs/1')).toBeNull()
    expect(matchRoute('/', '/')).toEqual({})
  })
})

describe('resolve', () => {
  it('prefers static segments over params, whatever the table order', () => {
    expect(resolve('/requests').def?.name).toBe('requests')
    expect(resolve('/requests/job_1').def?.name).toBe('request')
    expect(resolve('/console/compute').def?.name).toBe('service')
    expect(resolve('/console/compute/vm').def?.name).toBe('type')
    expect(resolve('/nope/x/y/z').def).toBeNull()
  })
})

describe('routeFor', () => {
  it('matches every pattern of the table', () => {
    for (const def of ROUTES) {
      const sample = def.pattern.replace(/:[a-z]+/g, 'x')
      expect(routeFor(`#${sample}`).name).toBe(def.name)
    }
  })

  it('reads the account prefix and keeps the rest as the path', () => {
    expect(routeFor('#/account/123456789012/console/compute/vm')).toMatchObject({ name: 'type', accountId: '123456789012', path: '/console/compute/vm', params: { service: 'compute', type: 'vm' } })
  })

  it('is not-found for a path nothing matches', () => {
    expect(routeFor('#/nothing/here/at/all')).toMatchObject({ name: 'not-found', def: null, params: {} })
  })
})

describe('pathFor', () => {
  it('builds a path, encodes params and drops empty query values', () => {
    expect(pathFor('type', { service: 'compute', type: 'vm' })).toBe('/console/compute/vm')
    expect(pathFor('signin', {}, { next: '/requests', empty: '', gone: undefined, none: null })).toBe('/signin?next=%2Frequests')
    expect(pathFor('home', {}, undefined, { accountId: '123456789012' })).toBe('/account/123456789012')
    expect(pathFor('requests', {}, undefined, { accountId: '123456789012' })).toBe('/account/123456789012/requests')
  })

  it('round-trips a wfrn through resourcePath', () => {
    const path = resourcePath('compute', 'vm', WFRN)
    expect(path).toBe(`/console/compute/vm/${encodeURIComponent(WFRN)}`)
    expect(routeFor(href(path)).params.wfrn).toBe(WFRN)
  })
})

describe('href', () => {
  it('makes a hash link from any spelling', () => {
    expect(href('/requests')).toBe('#/requests')
    expect(href('requests')).toBe('#/requests')
    expect(href('#/requests')).toBe('#/requests')
  })
})
