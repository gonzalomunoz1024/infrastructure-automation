import { describe, expect, it } from 'vitest'
import { ROUTES, href, matchRoute, parseHash, pathFor, resolve, routeFor } from '../app/router'

describe('parseHash', () => {
  it('splits the path and the query', () => {
    expect(parseHash('#/jobs/job_1?tab=steps&q=a%20b')).toEqual({ path: '/jobs/job_1', query: { tab: 'steps', q: 'a b' } })
  })
  it('normalises empty and unslashed hashes', () => {
    expect(parseHash('')).toEqual({ path: '/', query: {} })
    expect(parseHash('#')).toEqual({ path: '/', query: {} })
    expect(parseHash('#jobs')).toEqual({ path: '/jobs', query: {} })
    expect(parseHash('#/jobs/')).toEqual({ path: '/jobs', query: {} })
  })
})

describe('matchRoute', () => {
  it('extracts and decodes params', () => {
    expect(matchRoute('/workflows/:name/versions', '/workflows/vm%20restart/versions')).toEqual({ name: 'vm restart' })
  })
  it('rejects different lengths and static mismatches', () => {
    expect(matchRoute('/jobs/:id', '/jobs')).toBeNull()
    expect(matchRoute('/jobs/:id', '/releases/1')).toBeNull()
  })
})

describe('resolve', () => {
  it('prefers static segments over params', () => {
    expect(resolve('/providers/new').def?.name).toBe('provider-new')
    expect(resolve('/providers/vsphere')).toEqual({ def: ROUTES.find((r) => r.name === 'provider'), params: { name: 'vsphere' } })
  })
  it('matches every documented route', () => {
    const cases: [string, string][] = [
      ['/', 'home'],
      ['/workflows', 'workflows'],
      ['/workflows/vm-restart', 'workflow'],
      ['/workflows/vm-restart/versions', 'workflow-versions'],
      ['/catalog', 'catalog'],
      ['/catalog/vm-restart', 'catalog-run'],
      ['/jobs', 'jobs'],
      ['/jobs/job_1', 'job'],
      ['/approvals', 'approvals'],
      ['/providers', 'providers'],
      ['/resources', 'resources'],
      ['/resources/vm-1', 'resource'],
      ['/releases', 'releases'],
      ['/releases/rel_1', 'release'],
      ['/audit', 'audit'],
      ['/audit/job_1', 'audit-record'],
      ['/assistant', 'assistant'],
      ['/credentials', 'credentials'],
      ['/settings', 'settings'],
      ['/change-requests/cr_1', 'change-request'],
      ['/templates', 'templates'],
    ]
    for (const [path, name] of cases) expect(resolve(path).def?.name, path).toBe(name)
  })
  it('returns no route for unknown paths', () => {
    expect(resolve('/nope/at/all').def).toBeNull()
  })
})

describe('routeFor / href / pathFor', () => {
  it('builds the full route', () => {
    const r = routeFor('#/jobs/job_1?tab=steps')
    expect(r.name).toBe('job')
    expect(r.params).toEqual({ id: 'job_1' })
    expect(r.query).toEqual({ tab: 'steps' })
    expect(r.def?.fullBleed).toBeUndefined()
  })
  it('marks the designer as full bleed', () => {
    expect(routeFor('#/workflows/vm-restart').def?.fullBleed).toBe(true)
  })
  it('falls back to not-found', () => {
    expect(routeFor('#/nope').name).toBe('not-found')
  })
  it('href always yields a hash path', () => {
    expect(href('/jobs')).toBe('#/jobs')
    expect(href('jobs')).toBe('#/jobs')
    expect(href('#/jobs')).toBe('#/jobs')
  })
  it('pathFor fills params and queries', () => {
    expect(pathFor('job', { id: 'job 1' })).toBe('/jobs/job%201')
    expect(pathFor('jobs', {}, { status: 'running', limit: 20, empty: '' })).toBe('/jobs?status=running&limit=20')
  })
})
