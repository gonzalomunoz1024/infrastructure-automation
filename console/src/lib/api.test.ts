import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, CACHE_PREFIX, api, cachedGet, clearDescriptorCache, isApiError, page, qs, request, requestHeaders, resolveUrl, retryAfterSeconds, traceparent } from './api'
import { setCurrentUser } from './identity'

/* A localStorage for node, and a fetch the tests script. */

const storage = new Map<string, string>()
beforeAll(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (k: string) => storage.get(k) ?? null,
      setItem: (k: string, v: string) => void storage.set(k, String(v)),
      removeItem: (k: string) => void storage.delete(k),
      clear: () => storage.clear(),
      key: (i: number) => [...storage.keys()][i] ?? null,
      get length() {
        return storage.size
      },
    },
  })
})

type Call = { url: string; init: RequestInit | undefined }
const calls: Call[] = []
let answer: (call: Call) => Response | Promise<Response>

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

beforeEach(() => {
  calls.length = 0
  storage.clear()
  answer = () => json({})
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init }
    calls.push(call)
    return Promise.resolve(answer(call))
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  setCurrentUser(null)
})

const headerOf = (call: Call, name: string) => (call.init?.headers as Record<string, string>)[name]

describe('traceparent', () => {
  it('is a W3C trace context header, fresh every time', () => {
    const a = traceparent()
    const b = traceparent()
    expect(a).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/)
    expect(b).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/)
    expect(a).not.toBe(b)
  })
})

describe('qs and resolveUrl', () => {
  it('builds a query from an object, skipping empty values', () => {
    expect(qs({ a: 1, b: 'x y', c: undefined, d: '', e: null, f: false })).toBe('?a=1&b=x+y&f=false')
    expect(qs({})).toBe('')
    expect(qs()).toBe('')
  })

  it('prefixes the API base unless the path already names it', () => {
    expect(resolveUrl('/me')).toBe('/api/v1/me')
    expect(resolveUrl('/api/v1/me')).toBe('/api/v1/me')
    expect(resolveUrl('/actuator/health')).toBe('/actuator/health')
    expect(resolveUrl('https://x.test/y')).toBe('https://x.test/y')
  })
})

describe('retryAfterSeconds', () => {
  it('reads a delta or a date', () => {
    expect(retryAfterSeconds('120')).toBe(120)
    expect(retryAfterSeconds(new Date(Date.UTC(2026, 0, 1, 0, 1, 0)).toUTCString(), Date.UTC(2026, 0, 1, 0, 0, 0))).toBe(60)
    expect(retryAfterSeconds('soon')).toBeUndefined()
    expect(retryAfterSeconds(null)).toBeUndefined()
  })
})

describe('requestHeaders', () => {
  it('sends Accept, a traceparent and the dev user when one is chosen', () => {
    const anonymous = requestHeaders()
    expect(anonymous.Accept).toBe('application/json')
    expect(anonymous.traceparent).toMatch(/^00-/)
    expect(anonymous['X-User']).toBeUndefined()
    setCurrentUser('gonzalo')
    expect(requestHeaders({ 'If-None-Match': 'x' })).toMatchObject({ 'X-User': 'gonzalo', 'If-None-Match': 'x' })
  })
})

describe('request', () => {
  it('returns the JSON body and carries the headers', async () => {
    answer = () => json({ id: 'gonzalo' })
    setCurrentUser('gonzalo')
    await expect(request<{ id: string }>('GET', '/me')).resolves.toEqual({ id: 'gonzalo' })
    expect(calls[0].url).toBe('/api/v1/me')
    expect(headerOf(calls[0], 'X-User')).toBe('gonzalo')
    expect(headerOf(calls[0], 'traceparent')).toMatch(/^00-/)
  })

  it('turns an error body into an ApiError with the problems and the trace', async () => {
    answer = () => json({ error: 'Invalid input', problems: [{ level: 'error', code: 'required', field: 'name', message: 'name is required' }], correlationId: 'abc' }, 400)
    const error = await request('POST', '/vms', { name: '' }).catch((e: unknown) => e)
    expect(isApiError(error)).toBe(true)
    const e = error as ApiError
    expect(e).toMatchObject({ status: 400, error: 'Invalid input', correlationId: 'abc', notFound: false, unauthorized: false })
    expect(e.problems).toHaveLength(1)
    expect(e.traceparent).toBe(headerOf(calls[0], 'traceparent'))
    expect(headerOf(calls[0], 'Content-Type')).toBe('application/json')
  })

  it('reads Retry-After and a 404 and a 401', async () => {
    answer = () => json({ error: 'Slow down' }, 429, { 'Retry-After': '30' })
    await expect(request('GET', '/jobs')).rejects.toMatchObject({ status: 429, retryAfter: 30 })
    answer = () => json({ error: 'Resource not found' }, 404)
    await expect(request('GET', '/resources/x')).rejects.toMatchObject({ status: 404, notFound: true })
    answer = () => new Response('', { status: 401, statusText: 'Unauthorized' })
    await expect(request('GET', '/me')).rejects.toMatchObject({ status: 401, unauthorized: true, error: 'Unauthorized', body: null })
  })

  it('reports a platform that does not answer as status 0', async () => {
    answer = () => Promise.reject(new TypeError('Failed to fetch'))
    const error = (await request('GET', '/me').catch((e: unknown) => e)) as ApiError
    expect(error).toMatchObject({ status: 0, unreachable: true, error: 'Platform unreachable' })
  })
})

describe('cachedGet', () => {
  const body = [{ name: 'vm', plural: 'vms' }]

  it('stores the body with its ETag, revalidates with If-None-Match and answers a 304 from the cache', async () => {
    answer = () => json(body, 200, { ETag: 'W/"v1"' })
    await expect(cachedGet('/resource-types')).resolves.toEqual(body)
    expect(headerOf(calls[0], 'If-None-Match')).toBeUndefined()
    const stored = JSON.parse(storage.get(`${CACHE_PREFIX}/resource-types`) ?? 'null') as { etag: string; body: unknown }
    expect(stored).toMatchObject({ etag: 'W/"v1"', body })

    answer = () => new Response(null, { status: 304, headers: { ETag: 'W/"v1"' } })
    await expect(cachedGet('/resource-types')).resolves.toEqual(body)
    expect(headerOf(calls[1], 'If-None-Match')).toBe('W/"v1"')
  })

  it('answers from the cache when the platform fails or does not answer, and throws without one', async () => {
    answer = () => json(body, 200, { ETag: 'W/"v1"' })
    await cachedGet('/resource-types')
    answer = () => json({ error: 'boom' }, 503)
    await expect(cachedGet('/resource-types')).resolves.toEqual(body)
    answer = () => Promise.reject(new TypeError('Failed to fetch'))
    await expect(cachedGet('/resource-types')).resolves.toEqual(body)

    clearDescriptorCache()
    expect(storage.size).toBe(0)
    await expect(cachedGet('/resource-types')).rejects.toMatchObject({ status: 0 })
    answer = () => json({ error: 'nope' }, 404)
    await expect(cachedGet('/resource-types')).rejects.toMatchObject({ status: 404 })
  })

  it('replaces the cache when the platform answers a new body', async () => {
    answer = () => json(body, 200, { ETag: 'W/"v1"' })
    await cachedGet('/catalog')
    answer = () => json([], 200, { ETag: 'W/"v2"' })
    await expect(cachedGet('/catalog')).resolves.toEqual([])
    expect(JSON.parse(storage.get(`${CACHE_PREFIX}/catalog`) ?? 'null')).toMatchObject({ etag: 'W/"v2"', body: [] })
  })
})

describe('page', () => {
  it('reads an array body and the next cursor from the Link header', async () => {
    answer = () => json([{ id: 1 }, { id: 2 }], 200, { Link: '</api/v1/vms?limit=2&after=c2>; rel="next"' })
    await expect(page('/vms', { limit: 2 })).resolves.toEqual({ items: [{ id: 1 }, { id: 2 }], next: 'c2' })
    expect(calls[0].url).toBe('/api/v1/vms?limit=2')
  })

  it('reads an items envelope, with or without a cursor, and an empty body', async () => {
    answer = () => json({ items: [{ id: 1 }], next: '9' })
    await expect(page('/events', { after: 3 })).resolves.toEqual({ items: [{ id: 1 }], next: '9' })
    answer = () => json({ items: [] })
    await expect(page('/events')).resolves.toEqual({ items: [], next: null })
    answer = () => new Response('', { status: 200 })
    await expect(page('/events')).resolves.toEqual({ items: [], next: null })
  })

  it('throws the ApiError of a failed page', async () => {
    answer = () => json({ error: 'Collection not found' }, 404)
    await expect(page('/nothing')).rejects.toMatchObject({ status: 404 })
  })
})

describe('api', () => {
  it('names the endpoints', async () => {
    answer = () => json({ id: 'x' })
    await api.resourceOf('virtual machines', 'vm demo')
    expect(calls[0].url).toBe('/api/v1/virtual%20machines/vm%20demo')
    await api.lineage('a/b')
    expect(calls[1].url).toBe('/api/v1/resources/a%2Fb/lineage')
    expect(api.eventsStreamUrl()).toBe('/api/v1/events/stream')
    expect(api.eventsStreamUrl('12')).toBe('/api/v1/events/stream?after=12')
  })

  it('reads the regions through the tolerant parser', async () => {
    answer = () => json([{ metadata: { name: 'global', title: 'Global' }, spec: { partition: 'wf' } }], 200, { ETag: 'W/"r"' })
    await expect(api.regions()).resolves.toEqual([{ name: 'global', title: 'Global', partition: 'wf', status: undefined, environments: undefined }])
  })

  it('reads a 503 health answer as the health', async () => {
    answer = () => json({ status: 'DOWN', components: { db: { status: 'DOWN' } } }, 503)
    await expect(api.health()).resolves.toMatchObject({ status: 'DOWN' })
    answer = () => json({ status: 'UP' })
    await expect(api.health()).resolves.toEqual({ status: 'UP' })
    expect(calls[1].url).toBe('/actuator/health')
  })
})
