import { renderToString } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../lib/api'
import type { TryResult } from '../lib/types'
import { realResponseOverride, TryOutcome } from '../pages/ProviderPage'

/*
 * "Use the real response" on the provider page: a tried call whose answer differs from the
 * declared output offers to make that answer the action's override, through a change request
 * (POST /providers/{name}/overrides); an answer that matches, or is not a JSON object, does not.
 */

const ANSWER = { id: 'vm-1042', name: 'web-1042', power_state: 'POWERED_ON', cpu_count: 4 }

const DIFFERS: TryResult = {
  ok: true,
  status: 200,
  durationMs: 412,
  response: ANSWER,
  mismatches: [
    { field: 'powerState', expected: 'string', actual: undefined },
    { field: 'power_state', expected: undefined, actual: 'POWERED_ON' },
  ],
  verified: true,
}

const MATCHES: TryResult = { ok: true, status: 200, durationMs: 182, response: { items: [], total: 0 }, mismatches: [] }

const noop = () => {}
const text = (html: string) => html.replace(/<!--.*?-->/g, '')

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the real response of a tried action', () => {
  it('becomes the override of the action when it differs from the declared output', () => {
    expect(realResponseOverride(DIFFERS, 'vm.get')).toEqual({ action: 'vm.get', response: ANSWER })
  })

  it('is no override when it matches, is not an object, or there is no action', () => {
    expect(realResponseOverride(MATCHES, 'vm.get')).toBeNull()
    expect(realResponseOverride({ ...DIFFERS, response: [ANSWER] }, 'vm.get')).toBeNull()
    expect(realResponseOverride({ ...DIFFERS, response: 'POWERED_ON' }, 'vm.get')).toBeNull()
    expect(realResponseOverride({ ...DIFFERS, response: null }, 'vm.get')).toBeNull()
    expect(realResponseOverride(DIFFERS, '')).toBeNull()
    expect(realResponseOverride(null, 'vm.get')).toBeNull()
  })

  it('is offered under the fields that differ', () => {
    const html = text(renderToString(<TryOutcome result={DIFFERS} action="vm.get" fixing={false} onUseRealResponse={noop} />))
    expect(html).toContain('HTTP 200')
    expect(html).toContain('2 fields differ')
    expect(html).toContain('verified afterwards')
    expect(html).toContain('Mismatches')
    expect(html).toContain('power_state')
    expect(html).toContain('Use the real response')
    expect(html).toContain('Opens a change request with the schema of this answer')
  })

  it('is not offered for an answer that matches, and says why for one that is not an object', () => {
    const matches = text(renderToString(<TryOutcome result={MATCHES} action="vm.get" fixing={false} onUseRealResponse={noop} />))
    expect(matches).toContain('matches the declared output')
    expect(matches).not.toContain('Use the real response')
    expect(matches).not.toContain('Mismatches')
    const scalar = text(renderToString(<TryOutcome result={{ ...DIFFERS, ok: false, status: 502, response: 'Bad gateway' }} action="vm.get" fixing={false} onUseRealResponse={noop} />))
    expect(scalar).toContain('Failed')
    expect(scalar).not.toContain('Use the real response')
    expect(scalar).toContain('not a JSON object')
  })

  it('goes to the backend as the action and the answer', async () => {
    const calls: { url: string; method?: string; body: unknown }[] = []
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method, body: JSON.parse(String(init?.body)) })
      return new Response(JSON.stringify({ id: 'cr-91', status: 'open' }), { status: 202, headers: { 'content-type': 'application/json' } })
    })
    const override = realResponseOverride(DIFFERS, 'vm.get')
    expect(override).not.toBeNull()
    expect(await api.overrideAction('vsphere', override!)).toEqual({ id: 'cr-91', status: 'open' })
    expect(calls).toEqual([{ url: '/api/v1/providers/vsphere/overrides', method: 'POST', body: { action: 'vm.get', response: ANSWER } }])
  })
})
