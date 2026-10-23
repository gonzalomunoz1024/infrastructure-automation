import { describe, expect, it } from 'vitest'
import type { Favourite, RecentVisit } from '../lib/personal'
import { buildMockData } from './data'
import { restoreDirectory, saveDirectory } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Recent visits and favourites in the mock BFF: kept per person and account, the newest first,
 * a second visit moving its key to the top and 20 kept; favourites idempotent, 100 at most, left
 * out of the list once their type may not be listed; and kept with the directory over a reload.
 */

const T0 = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = '123456789012'
const vm = (id: string) => `wfrn:wf:compute:global:${COMPUTE}:vm/${id}`

function bff(person = 'dana') {
  let now = T0
  let who = person
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const signIn = (p: string) => {
    who = p
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${p}`, headers: {} })
  }
  signIn(person)
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who) }, body })
  return {
    data,
    call,
    signIn,
    tick: () => (now += 1000),
    recent: () => (call('GET', '/console/v1/recent').body as { items: RecentVisit[] }).items,
    favourites: () => (call('GET', '/console/v1/favourites').body as { items: Favourite[] }).items,
  }
}

describe('recent visits', () => {
  it('keeps the 20 latest, newest first, a second visit moving to the top', () => {
    const b = bff()
    expect(b.recent()).toEqual([])
    expect(b.call('POST', '/console/v1/recent', { kind: 'type', key: 'compute/vm', title: 'Virtual machines' }).status).toBe(204)
    b.tick()
    b.call('POST', '/console/v1/recent', { kind: 'resource', key: vm('vm-demo01'), title: 'web-01' })
    b.tick()
    b.call('POST', '/console/v1/recent', { kind: 'type', key: 'compute/vm', title: 'Virtual machines' })
    expect(b.recent().map((v) => v.key)).toEqual(['compute/vm', vm('vm-demo01')])
    expect(b.recent()[0].visitedAt).toBe(new Date(T0 + 2000).toISOString())
    for (let i = 0; i < 25; i++) b.call('POST', '/console/v1/recent', { kind: 'resource', key: vm(`vm-gen-${String(i).padStart(3, '0')}`), title: `gen ${i}` })
    expect(b.recent()).toHaveLength(20)
    expect(b.recent()[0].key).toBe(vm('vm-gen-024'))
  })

  it('checks the body as the contract does', () => {
    const b = bff()
    expect(b.call('POST', '/console/v1/recent', { kind: 'page', key: 'x', title: 'x' })).toMatchObject({ status: 400, body: { error: 'kind is type or resource' } })
    expect(b.call('POST', '/console/v1/recent', { kind: 'type', key: 'vm', title: 'x' }).status).toBe(400)
    expect(b.call('POST', '/console/v1/recent', { kind: 'resource', key: 'vm-demo01', title: 'x' }).status).toBe(400)
    expect(b.call('POST', '/console/v1/recent', { kind: 'type', key: 'compute/vm', title: 'x'.repeat(257) }).status).toBe(400)
    expect(b.call('POST', '/console/v1/recent', { kind: 'type', key: 'compute/vm', title: 'ok' }, ).status).toBe(204)
  })

  it('is each person’s own, in each account', () => {
    const b = bff()
    b.call('POST', '/console/v1/recent', { kind: 'type', key: 'compute/vm', title: 'Virtual machines' })
    b.signIn('mateo')
    expect(b.recent()).toEqual([])
    b.signIn('gonzalo')
    b.call('PUT', '/console/v1/session', { accountId: '210987654321' })
    b.call('POST', '/console/v1/recent', { kind: 'type', key: 'data/database', title: 'Databases' })
    b.call('PUT', '/console/v1/session', { accountId: COMPUTE })
    expect(b.recent()).toEqual([])
  })
})

describe('favourites', () => {
  it('stars a resource once, newest first, and unstars it', () => {
    const b = bff()
    const first = b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo01'))}`, { type: 'vm', name: 'web-01' })
    expect(first).toMatchObject({ status: 200, body: { wfrn: vm('vm-demo01'), type: 'vm', name: 'web-01', addedAt: new Date(T0).toISOString() } })
    b.tick()
    b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo03'))}`, { type: 'vm', name: 'web-02' })
    // the same star again keeps the first
    expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo01'))}`, { type: 'vm', name: 'web-01' }).body).toMatchObject({ addedAt: new Date(T0).toISOString() })
    expect(b.favourites().map((f) => f.name)).toEqual(['web-02', 'web-01'])
    expect(b.call('DELETE', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo03'))}`).status).toBe(204)
    expect(b.call('DELETE', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo03'))}`).status).toBe(204)
    expect(b.favourites().map((f) => f.name)).toEqual(['web-01'])
  })

  it('refuses a body the contract does not take, another account’s wfrn, and the 101st', () => {
    const b = bff()
    expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo01'))}`, { type: 'nope', name: 'web-01' }).status).toBe(400)
    expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo01'))}`, { type: 'vm', name: '' }).status).toBe(400)
    expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent('wfrn:wf:compute:global:210987654321:vm/x')}`, { type: 'vm', name: 'x' }).status).toBe(400)
    for (let i = 0; i < 100; i++) expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm(`vm-gen-${i}`))}`, { type: 'vm', name: `gen ${i}` }).status).toBe(200)
    expect(b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-one-more'))}`, { type: 'vm', name: 'one more' })).toMatchObject({ status: 409, body: { error: 'favourites.full' } })
  })

  it('leaves out a favourite whose type the person may no longer list, and survives a reload with the directory', () => {
    const b = bff()
    b.call('PUT', `/console/v1/favourites/${encodeURIComponent(vm('vm-demo01'))}`, { type: 'vm', name: 'web-01' })
    const deny = { sid: 'no-compute-reads', effect: 'deny' as const, actions: ['compute:list'], resources: ['*'] }
    const policy = b.data.directory.policies[COMPUTE].find((p) => p.policyId === 'managed-operator')!
    policy.statements = [deny, ...policy.statements]
    expect(b.favourites()).toEqual([])
    policy.statements = policy.statements.slice(1)
    expect(b.favourites()).toHaveLength(1)

    const saved = new Map<string, string>()
    saveDirectory(b.data.directory, { setItem: (k, v) => saved.set(k, v) })
    const fresh = buildMockData()
    restoreDirectory(fresh.directory, { getItem: (k) => saved.get(k) ?? null })
    expect(fresh.directory.personal?.[`dana/${COMPUTE}`]?.favourites.map((f) => f.name)).toEqual(['web-01'])
  })
})
