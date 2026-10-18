import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, errorMessage, newIdempotencyKey, request, retryAfterSeconds, retryIn, runHeaders } from '../lib/api'
import type { WorkflowDefinition } from '../lib/types'

/*
 * The client's side of docs/api.md "Calls that start jobs": the headers that pin a version and
 * key a submission, and the 429 that says when to try again.
 */

type Fetch = typeof fetch

function answer(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } })
}

function mockFetch(impl: (url: string, init: RequestInit | undefined) => Response): { calls: { url: string; init: RequestInit | undefined }[] } {
  const calls: { url: string; init: RequestInit | undefined }[] = []
  const f: Fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    calls.push({ url, init })
    return impl(url, init)
  }
  vi.stubGlobal('fetch', f)
  return { calls }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Retry-After', () => {
  it('reads a delta in seconds and an HTTP date', () => {
    expect(retryAfterSeconds('42')).toBe(42)
    expect(retryAfterSeconds(' 7 ')).toBe(7)
    const now = Date.parse('2026-10-18T10:00:00Z')
    expect(retryAfterSeconds('Sun, 18 Oct 2026 10:00:30 GMT', now)).toBe(30)
    expect(retryAfterSeconds('Sun, 18 Oct 2026 09:59:30 GMT', now)).toBe(0)
    expect(retryAfterSeconds(null)).toBeUndefined()
    expect(retryAfterSeconds('soon')).toBeUndefined()
  })

  it('says when to try again in seconds, or minutes for a long wait', () => {
    expect(retryIn(12)).toBe('try again in 12 s')
    expect(retryIn(0.2)).toBe('try again in 1 s')
    expect(retryIn(600)).toBe('try again in 10 min')
    expect(retryIn(undefined)).toBeNull()
  })

  it('a 429 carries the header, else the body, as retryAfter', async () => {
    mockFetch(() => answer(429, { error: 'team-data is over its quota of 60 jobs a minute', retryAfter: 9 }, { 'Retry-After': '11' }))
    const e = await request('POST', '/jobs', {}).catch((x: unknown) => x)
    expect(e).toBeInstanceOf(ApiError)
    const err = e as ApiError
    expect(err.status).toBe(429)
    expect(err.tooManyRequests).toBe(true)
    expect(err.error).toBe('team-data is over its quota of 60 jobs a minute')
    expect(err.retryAfter).toBe(11)

    mockFetch(() => answer(429, { error: 'over the quota', retryAfter: 9 }))
    const fromBody = (await request('POST', '/jobs', {}).catch((x: unknown) => x)) as ApiError
    expect(fromBody.retryAfter).toBe(9)

    const plain = new ApiError(500, { error: 'boom' })
    expect(plain.retryAfter).toBeUndefined()
    expect(plain.tooManyRequests).toBe(false)
  })
})

describe('run headers', () => {
  it('pins the version and keys the submission only when given', () => {
    expect(runHeaders()).toEqual({})
    expect(runHeaders({ version: '1.2.0' })).toEqual({ 'X-Workflow-Version': '1.2.0' })
    expect(runHeaders({ idempotencyKey: 'k-1' })).toEqual({ 'Idempotency-Key': 'k-1' })
    expect(runHeaders({ version: '1.2.0', idempotencyKey: 'k-1' })).toEqual({ 'X-Workflow-Version': '1.2.0', 'Idempotency-Key': 'k-1' })
  })

  it('mints a different key per submission', () => {
    const a = newIdempotencyKey()
    const b = newIdempotencyKey()
    expect(a).not.toBe(b)
    expect(a.length).toBeGreaterThan(16)
    expect(a.length).toBeLessThanOrEqual(255)
  })

  it('api.run sends them on the generated route, for a POST and for a GET', async () => {
    const { calls } = mockFetch(() => answer(202, { jobId: 'job_1', status: 'queued', links: { job: '/api/v1/jobs/job_1' } }))
    await api.run({ method: 'POST', path: '/api/v1/vms/{vmId}:restart' }, { vmId: 'vm-1', reason: 'patching' }, { version: '1.1.0', idempotencyKey: 'k-7' })
    expect(calls[0].url).toBe('/api/v1/vms/vm-1:restart')
    const headers = calls[0].init?.headers as Record<string, string>
    expect(headers['X-Workflow-Version']).toBe('1.1.0')
    expect(headers['Idempotency-Key']).toBe('k-7')
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ vmId: 'vm-1', reason: 'patching' })

    await api.run({ method: 'GET', path: '/api/v1/vms/{vmId}:check' }, { vmId: 'vm-2' }, { idempotencyKey: 'k-8' })
    expect(calls[1].url).toBe('/api/v1/vms/vm-2:check')
    expect((calls[1].init?.headers as Record<string, string>)['Idempotency-Key']).toBe('k-8')
    expect(calls[1].init?.body).toBeUndefined()

    await api.run({ method: 'POST', path: '/api/v1/vms' }, { name: 'x' })
    const bare = calls[2].init?.headers as Record<string, string>
    expect(bare['X-Workflow-Version']).toBeUndefined()
    expect(bare['Idempotency-Key']).toBeUndefined()
  })

  it('api.runBatch and api.retryJob key the submission too', async () => {
    const { calls } = mockFetch(() => answer(202, { jobId: 'job_2', rows: [] }))
    await api.runBatch({ batchPath: '/api/v1/vms:restart:batch' }, { items: [{ vmId: 'vm-1' }] }, { idempotencyKey: 'k-9', version: '1.0.0' })
    expect(calls[0].url).toBe('/api/v1/vms:restart:batch')
    expect((calls[0].init?.headers as Record<string, string>)['Idempotency-Key']).toBe('k-9')
    expect((calls[0].init?.headers as Record<string, string>)['X-Workflow-Version']).toBe('1.0.0')
    await api.retryJob('job_2', { step: 'restart' }, { idempotencyKey: 'k-10' })
    expect(calls[1].url).toBe('/api/v1/jobs/job_2:retry')
    expect((calls[1].init?.headers as Record<string, string>)['Idempotency-Key']).toBe('k-10')
  })
})

describe('an unexpected error', () => {
  it("says the request's correlation id, which finds the cause in the API's log", async () => {
    mockFetch(() => answer(500, { error: 'unexpected error', correlationId: '4bf92f3577b34da6a3ce929d0e0e4736' }))
    const e = await request('GET', '/workflows').catch((x: unknown) => x)
    expect(e).toBeInstanceOf(ApiError)
    expect((e as ApiError).correlationId).toBe('4bf92f3577b34da6a3ce929d0e0e4736')
    expect(errorMessage(e)).toBe('unexpected error (correlation id 4bf92f3577b34da6a3ce929d0e0e4736)')
    // any other error reads as the backend wrote it
    expect(errorMessage(new ApiError(409, { error: 'checks failed' }))).toBe('checks failed')
  })
})

describe('the calls of this wave', () => {
  it('asks for the inbox, the platform stream, releases by kind and the imports at their paths', async () => {
    const { calls } = mockFetch((url) => (url.includes('/notifications/') ? answer(200, { id: 'n1', read: true }) : answer(200, { items: [], unread: 0, entries: [], total: 0 })))
    await api.notifications(true)
    await api.markNotificationRead('n 1')
    await api.auditPlatform({ aggregate: 'provider', actor: 'ana', from: '2026-10-01' })
    await api.releases({ kind: 'provider' })
    await api.releases({ provider: 'vsphere' })
    await api.releases()
    await api.importAnsible({ git: { repo: 'https://github.com/acme/ops-playbooks', ref: 'main' } })
    await api.importTerraform({ registry: { module: 'acme/vpc/aws', version: '2.1.0' } })
    await api.refreshSpec('vsphere', { ref: 'v2' })
    await api.refreshSpec('vsphere')
    expect(calls.map((c) => c.url)).toEqual([
      '/api/v1/notifications?unread=true',
      '/api/v1/notifications/n%201:read',
      '/api/v1/audit/platform?aggregate=provider&actor=ana&from=2026-10-01',
      '/api/v1/releases?kind=provider',
      '/api/v1/releases?provider=vsphere',
      '/api/v1/releases',
      '/api/v1/providers:import-ansible',
      '/api/v1/providers:import-terraform',
      '/api/v1/providers/vsphere:refresh-spec',
      '/api/v1/providers/vsphere:refresh-spec',
    ])
    expect(JSON.parse(String(calls[9].init?.body))).toEqual({})
  })
})

/*
 * The designer's provider drafts: a workflow's dry run and checks carry the YAML of the drafts it
 * uses, and its publish names the provider change requests that must merge first. A call without
 * any sends exactly what it always sent.
 */
describe('provider drafts and the change requests a publish requires', () => {
  const definition = { apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'vm-restart' } } as WorkflowDefinition
  const DRAFT = 'apiVersion: iaf.platform/v1\nkind: Provider\nmetadata:\n  name: backup\n'

  it('go with validate and the dry run only when there are drafts', async () => {
    const { calls } = mockFetch(() => answer(200, { valid: true, problems: [] }))
    await api.validateWorkflow({ definition })
    await api.validateWorkflow({ definition }, [])
    await api.validateWorkflow({ definition }, [DRAFT])
    await api.dryRunWorkflow({ definition }, { vm: 'web-1' })
    await api.dryRunWorkflow({ definition }, { vm: 'web-1' }, [])
    await api.dryRunWorkflow({ definition }, { vm: 'web-1' }, [DRAFT])
    expect(calls.map((c) => c.url)).toEqual([...Array(3).fill('/api/v1/workflows:validate'), ...Array(3).fill('/api/v1/workflows:dry-run')])
    expect(calls.map((c) => c.init?.body)).toEqual([
      JSON.stringify({ definition }),
      JSON.stringify({ definition }),
      JSON.stringify({ definition, providers: [DRAFT] }),
      JSON.stringify({ definition, input: { vm: 'web-1' } }),
      JSON.stringify({ definition, input: { vm: 'web-1' } }),
      JSON.stringify({ definition, input: { vm: 'web-1' }, providers: [DRAFT] }),
    ])
  })

  it('names the change requests a publish requires only when there are some', async () => {
    const { calls } = mockFetch(() => answer(202, { id: 'cr-9', status: 'open' }))
    await api.publishWorkflow('vm-restart')
    await api.publishWorkflow('vm-restart', { strategy: 'canary', notes: undefined })
    await api.publishWorkflow('vm-restart', { strategy: 'canary', requires: [] })
    await api.publishWorkflow('vm-restart', { strategy: 'all', notes: 'With the backup provider', requires: ['cr-1', 'cr-2'] })
    expect(calls.every((c) => c.url === '/api/v1/workflows/vm-restart:publish')).toBe(true)
    expect(calls.map((c) => c.init?.body)).toEqual([
      JSON.stringify({}),
      JSON.stringify({ strategy: 'canary' }),
      JSON.stringify({ strategy: 'canary' }),
      JSON.stringify({ strategy: 'all', notes: 'With the backup provider', requires: ['cr-1', 'cr-2'] }),
    ])
  })
})
