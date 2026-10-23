import { describe, expect, it } from 'vitest'
import type { ApiSession, DeviceGrant, Page } from '../lib/types'
import { codeLettersValid, machineOf, normalizeUserCode, timeLeft } from '../lib/cli'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { STEP_UP_MAX_AGE_MS, codeKey } from './cli'

/*
 * Device sign-in and API sessions in the mock (WP 7.4): a grant read by its code in any spelling,
 * approving it only after a fresh sign-in (the step-up 403 past ten minutes), deciding it once,
 * never after it expired and never with a bearer token; the person's own sessions, the new one
 * an approval opens, revoking one and not someone else's; and the journal's replay.
 */

const T0 = Date.parse('2026-10-23T10:00:00Z')
const MINUTE = 60_000

function bff(person: string, start = T0) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const login = (who = person) => server.handle({ method: 'GET', url: `/auth/login?login_hint=${who}`, headers: {} })
  login()
  const call = (method: string, url: string, headers: Record<string, string> = {}, who = person) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who), ...headers } })
  return { data, server, call, login, tick: (ms: number) => (now += ms), now: () => now }
}

const body = <T>(res: { body?: unknown }) => res.body as T

describe('the code the CLI shows', () => {
  it('is read in any spelling, and only eight letters make one', () => {
    expect(normalizeUserCode('wdjb mjht')).toBe('WDJB-MJHT')
    expect(normalizeUserCode('WDJBMJHT')).toBe('WDJB-MJHT')
    expect(normalizeUserCode('WDJB-MJH')).toBeNull()
    expect(normalizeUserCode('WDJB-MJH7')).toBeNull()
    expect(codeLettersValid('WDJB-MJHT')).toBe(true)
    expect(codeLettersValid('WAJB-MJHT')).toBe(false)
    expect(codeKey('wdjbmjht')).toBe('WDJB-MJHT')
  })

  it('says the machine in words', () => {
    expect(machineOf('iaf/0.7.0 (darwin; arm64) node/22.11.0')).toBe('macOS on arm64, iaf 0.7.0')
    expect(machineOf('iaf-sdk-ts/0.3.1 node/22.11.0 (linux; x64)')).toBe('Linux on x64, iaf-sdk-ts 0.3.1')
    expect(machineOf(undefined)).toBe('An unknown machine')
    expect(timeLeft(new Date(T0 + 90_000).toISOString(), T0)).toBe('1 min 30 s')
    expect(timeLeft(new Date(T0 - 1).toISOString(), T0)).toBeNull()
  })
})

describe('device grants', () => {
  it('shows a waiting grant by its code, in any case and with or without the hyphen', () => {
    const b = bff('dana')
    const g = body<DeviceGrant>(b.call('GET', '/console/v1/device-grants/wdjbmjht'))
    expect(g).toMatchObject({ userCode: 'WDJB-MJHT', clientId: 'console-cli', status: 'pending', requestedFrom: { ip: '10.20.4.17' } })
    expect(b.call('GET', '/console/v1/device-grants/BCDF-GHJK').status).toBe(404)
    expect(b.call('GET', '/console/v1/device-grants/nope').status).toBe(400)
    expect(body<DeviceGrant>(b.call('GET', '/console/v1/device-grants/HZKD-FWBM')).status).toBe('expired')
  })

  it('approves after a fresh sign-in only, once, and opens an API session', () => {
    const b = bff('dana')
    b.tick(STEP_UP_MAX_AGE_MS + MINUTE)
    const stale = b.call('POST', '/console/v1/device-grants/WDJB-MJHT:approve')
    expect(stale.status).toBe(403)
    expect(stale.body).toMatchObject({ error: 'step-up.required', detail: { stepUp: true, login: '/auth/login?stepUp=true' } })
    b.login()
    const ok = b.call('POST', '/console/v1/device-grants/WDJB-MJHT:approve')
    expect(ok.status).toBe(200)
    expect(body<DeviceGrant>(ok)).toMatchObject({ status: 'approved' })
    expect(b.call('POST', '/console/v1/device-grants/WDJB-MJHT:deny').body).toMatchObject({ error: 'device-grant.decided' })
    const sessions = body<Page<ApiSession>>(b.call('GET', '/console/v1/api-sessions')).items
    expect(sessions).toHaveLength(3)
    expect(sessions[0]).toMatchObject({ clientId: 'console-cli', ip: '10.20.4.17', accountId: '123456789012' })
    expect(sessions[0].id).toMatch(/^aps_[0-9a-z]{26}$/)
  })

  it('denies without a fresh sign-in, refuses an expired code and a bearer token', () => {
    const b = bff('dana')
    b.tick(STEP_UP_MAX_AGE_MS + MINUTE)
    expect(body<DeviceGrant>(b.call('POST', '/console/v1/device-grants/QXRT-VNPL:deny'))).toMatchObject({ status: 'denied' })
    b.login()
    expect(b.call('POST', '/console/v1/device-grants/HZKD-FWBM:approve')).toMatchObject({ status: 410, body: { error: 'device-grant.expired' } })
    expect(b.call('POST', '/console/v1/device-grants/WDJB-MJHT:approve', { authorization: 'Bearer abc' })).toMatchObject({ status: 403, body: { error: 'browser.only' } })
    expect(b.call('POST', '/console/v1/device-grants/WDJB-MJHT:approve', { 'x-csrf-token': 'wrong' }).status).toBe(403)
  })
})

describe('API sessions', () => {
  it('lists the person’s own, newest first, and revokes one but not someone else’s', () => {
    const b = bff('dana')
    const mine = body<Page<ApiSession>>(b.call('GET', '/console/v1/api-sessions')).items
    expect(mine.map((s) => s.clientId)).toEqual(['console-sdk', 'console-cli'])
    expect(b.call('DELETE', `/console/v1/api-sessions/${mine[0].id}`).status).toBe(204)
    expect(body<Page<ApiSession>>(b.call('GET', '/console/v1/api-sessions')).items.map((s) => s.id)).toEqual([mine[1].id])
    expect(b.call('DELETE', `/console/v1/api-sessions/${mine[0].id}`).status).toBe(404)

    b.login('mateo')
    const theirs = body<Page<ApiSession>>(b.call('GET', '/console/v1/api-sessions', {}, 'mateo')).items
    expect(theirs.map((s) => s.id)).not.toContain(mine[1].id)
    expect(b.call('DELETE', `/console/v1/api-sessions/${mine[1].id}`, {}, 'mateo').status).toBe(404)
  })

  it('keeps decisions and revocations across a reload', () => {
    const b = bff('dana')
    b.call('POST', '/console/v1/device-grants/WDJB-MJHT:approve')
    const first = body<Page<ApiSession>>(b.call('GET', '/console/v1/api-sessions')).items
    b.call('DELETE', `/console/v1/api-sessions/${first[2].id}`)
    const store = new Map<string, string>()
    saveConsole(b.data, { setItem: (k, v) => void store.set(k, v) })
    const again = buildMockData()
    restoreConsole(again, { getItem: (k) => store.get(k) ?? null }, b.now())
    const server = createMockServer(again, memorySessionStore(), b.now)
    server.handle({ method: 'GET', url: '/auth/login?login_hint=dana', headers: {} })
    const get = (url: string) => server.handle({ method: 'GET', url, headers: {} })
    expect(body<DeviceGrant>(get('/console/v1/device-grants/WDJB-MJHT')).status).toBe('approved')
    expect(body<Page<ApiSession>>(get('/console/v1/api-sessions')).items.map((s) => s.id)).toEqual(first.slice(0, 2).map((s) => s.id))
  })

  it('serves the console API’s outline for the developer portal', () => {
    const b = bff('mia')
    const doc = body<{ openapi: string; paths: Record<string, Record<string, { tags: string[] }>> }>(b.call('GET', '/console/v1/openapi.json'))
    expect(doc.openapi).toBe('3.1.0')
    expect(doc.paths['/console/v1/device-grants/{userCode}:approve'].post.tags).toEqual(['Device sign-in'])
  })
})
