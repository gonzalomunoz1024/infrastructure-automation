import { describe, expect, it } from 'vitest'
import type { ChatLink, Digest, DigestSummary, NotificationPage, NotificationSettings } from '../lib/notifications'
import type { CommandAccepted } from '../lib/types'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { APPROVAL_MS, RUN_MS } from './commands'
import { digestWeeksOf, mockChatLinkToken, notificationEventsAfter } from './notifications'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The BFF's notifications in the mock (WP 7.2, 7.5, 9.13): seeded feeds, what the console's jobs
 * and access requests notify and to whom, once each, reads and settings against their version,
 * chat links by token, and the weekly digests, all replayed from the journal after a reload.
 */

const START = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = 'wfrn:wf:compute:global:123456789012'
const enc = encodeURIComponent
const body = <T>(res: { body?: unknown }) => res.body as T

function world() {
  let now = START
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const as = (person: string) => {
    const call = (method: string, url: string, payload?: unknown, headers: Record<string, string> = {}) => {
      server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
      return server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body: payload })
    }
    return { call, feed: (q = '') => body<NotificationPage>(call('GET', `/console/v1/notifications${q}`)), settings: () => body<NotificationSettings>(call('GET', '/console/v1/notification-settings')) }
  }
  return { data, server, as, tick: (ms: number) => void (now += ms), now: () => now }
}

describe('the feed', () => {
  it('starts with a person’s seeded notifications, newest first, and counts the unread', () => {
    const w = world()
    const page = w.as('dana').feed()
    expect(page.items[0]).toMatchObject({ id: 'ntf_dana_drift', kind: 'drift.found', link: `/r/vm/${enc(`${COMPUTE}:vm/vm-demo02`)}?tab=drift` })
    expect(page.items.map((n) => n.kind)).toEqual(expect.arrayContaining(['digest.ready', 'access-request.decided']))
    // Dana's approved access request of 8 October is old news: it arrives read
    expect(page.items.find((n) => n.kind === 'access-request.decided')?.readAt).toBeTruthy()
    expect(page.unread).toBe(page.items.filter((n) => !n.readAt).length)
    expect(w.as('dana').feed('?unread=true').items.every((n) => !n.readAt)).toBe(true)
    expect(w.as('dana').feed('?kind=drift.found').items.every((n) => n.kind === 'drift.found')).toBe(true)
    expect(w.as('dana').call('GET', '/console/v1/notifications?kind=nope').status).toBe(400)
  })

  it('marks some or all read, and keeps it after a reload', () => {
    const w = world()
    const dana = w.as('dana')
    const before = dana.feed().unread
    expect(body<{ unread: number }>(dana.call('POST', '/console/v1/notifications:read', { ids: ['ntf_dana_drift'] })).unread).toBe(before - 1)
    expect(dana.call('POST', '/console/v1/notifications:read', {}).status).toBe(400)
    const store: Record<string, string> = {}
    saveConsole(w.data, { setItem: (k, v) => void (store[k] = v) })
    const fresh = buildMockData()
    restoreConsole(fresh, { getItem: (k) => store[k] ?? null }, w.now())
    const again = createMockServer(fresh, memorySessionStore(), () => w.now())
    again.handle({ method: 'GET', url: '/auth/login?login_hint=dana', headers: {} })
    const page = body<NotificationPage>(again.handle({ method: 'GET', url: '/console/v1/notifications', headers: {} }))
    expect(page.items.find((n) => n.id === 'ntf_dana_drift')?.readAt).toBeTruthy()
    expect(body<{ unread: number }>(dana.call('POST', '/console/v1/notifications:read', { all: true })).unread).toBe(0)
  })
})

describe('what the console’s jobs notify', () => {
  it('tells the approvers a request waits, the requester it was approved and that it succeeded, once each', () => {
    const w = world()
    const gonzalo = w.as('gonzalo')
    // web-01 is production: its restart waits for compute-approvers (Dana and Mia)
    const accepted = body<CommandAccepted>(gonzalo.call('POST', `/console/v1/r/vm/${enc(`${COMPUTE}:vm/vm-demo01`)}:restart`, { input: { reason: 'kernel update' } }))
    const waiting = w.as('dana').feed('?kind=approval.requested').items.filter((n) => n.jobId === accepted.jobId)
    expect(waiting).toHaveLength(1)
    expect(waiting[0]).toMatchObject({ severity: 'warning', link: `/jobs/${accepted.jobId}` })
    expect(waiting[0].title).toContain('Gonzalo Munoz asks for an approval')
    expect(w.as('mia').feed('?kind=approval.requested').items.some((n) => n.jobId === accepted.jobId)).toBe(true)
    expect(gonzalo.feed('?kind=approval.requested').items.some((n) => n.jobId === accepted.jobId)).toBe(false)
    w.tick(APPROVAL_MS + RUN_MS)
    const mine = gonzalo.feed().items.filter((n) => n.jobId === accepted.jobId)
    expect(mine.map((n) => n.kind).sort()).toEqual(['approval.decided', 'job.succeeded'])
    w.tick(1000)
    expect(gonzalo.feed().items.filter((n) => n.jobId === accepted.jobId)).toHaveLength(2)
  })

  it('tells the requester a step failed and paused the request, and leaves out what their rules keep off the console', () => {
    const w = world()
    const dana = w.as('dana')
    const s = dana.settings()
    expect(dana.call('PUT', '/console/v1/notification-settings', { ...s, rules: { 'job.succeeded': ['email'] } }, { 'if-match': String(s.version) }).status).toBe(200)
    const failing = body<CommandAccepted>(dana.call('POST', `/console/v1/r/vm/${enc(`${COMPUTE}:vm/vm-demo04`)}:restart`, { input: { reason: 'fail on purpose' } }))
    const fine = body<CommandAccepted>(dana.call('POST', `/console/v1/r/vm/${enc(`${COMPUTE}:vm/vm-demo06`)}:restart`, { input: { reason: 'routine' } }))
    w.tick(RUN_MS)
    const feed = dana.feed().items
    expect(feed.find((n) => n.jobId === failing.jobId)).toMatchObject({ kind: 'job.needs-attention', title: expect.stringContaining('paused on a failed step') })
    expect(feed.some((n) => n.jobId === fine.jobId && n.kind === 'job.succeeded')).toBe(false)
  })

  it('tells the approvers someone asks for access, and the stream carries what is new after a cursor', () => {
    const w = world()
    w.as('gonzalo').feed()
    const last = notificationEventsAfter(w.data, 'gonzalo', 0, w.now()).last
    expect(w.as('mia').call('POST', '/console/v1/access-requests', { action: 'compute:create', resource: `${COMPUTE}:vm/*`, role: 'operator', reason: 'on call' }).status).toBe(202)
    w.as('gonzalo').feed()
    const next = notificationEventsAfter(w.data, 'gonzalo', last, w.now())
    expect(next.events.map((e) => e.event.data?.kind)).toEqual(['access-request.created'])
    expect(next.events[0].event).toMatchObject({ type: 'notification', data: { title: 'Mia Chen asks for Operator in Compute' } })
    expect(notificationEventsAfter(w.data, 'gonzalo', next.last, w.now()).events).toEqual([])
  })
})

describe('settings', () => {
  it('needs the version read, refuses a channel that is not linked, and moves the version on', () => {
    const w = world()
    const dana = w.as('dana')
    const s = dana.settings()
    expect(s).toMatchObject({ version: 1, channels: { email: { address: 'dana@example.com', enabled: true }, slack: { linked: true, enabled: true }, teams: { linked: false, enabled: false } }, rules: {} })
    const put = (doc: unknown, version?: number) => dana.call('PUT', '/console/v1/notification-settings', doc, version === undefined ? {} : { 'if-match': String(version) })
    expect(put(s).status).toBe(400)
    expect(body<{ error: string }>(put({ ...s, channels: { ...s.channels, teams: { linked: false, enabled: true } } }, 1)).error).toBe('channel.not-linked')
    expect(body<{ error: string }>(put({ ...s, rules: { 'job.done': ['email'] } }, 1)).error).toBe('rules.kind')
    const saved = put({ ...s, channels: { ...s.channels, email: { enabled: false } } }, 1)
    expect(saved).toMatchObject({ status: 200, headers: { ETag: '2' } })
    expect(body<{ error: string }>(put(s, 1))).toMatchObject({ error: 'version.moved' })
    expect(body<{ error: string }>(dana.call('POST', '/console/v1/notification-settings:test', { channel: 'email' })).error).toBe('channel.disabled')
    expect(dana.call('POST', '/console/v1/notification-settings:test', { channel: 'slack' }).status).toBe(202)
    expect(body<{ error: string }>(dana.call('POST', '/console/v1/notification-settings:test', { channel: 'teams' })).error).toBe('channel.not-linked')
  })
})

describe('chat links', () => {
  const token = (over: Partial<Parameters<typeof mockChatLinkToken>[0]> = {}) => mockChatLinkToken({ platform: 'teams', workspaceId: 'firm.onmicrosoft.com', chatUserId: '29:dana-reyes', chatUserName: 'Dana Reyes', exp: Math.floor(START / 1000) + 600, ...over })

  it('links by token, turns the channel on, lists and unlinks', () => {
    const w = world()
    const dana = w.as('dana')
    const linked = dana.call('POST', '/console/v1/chat-links', { token: token() })
    expect(linked).toMatchObject({ status: 201, body: { platform: 'teams', chatUserName: 'Dana Reyes' } })
    expect(dana.settings().channels.teams).toEqual({ linked: true, enabled: true })
    const links = body<{ items: ChatLink[] }>(dana.call('GET', '/console/v1/chat-links')).items
    expect(links.map((l) => l.platform)).toEqual(['slack', 'teams'])
    expect(dana.call('DELETE', '/console/v1/chat-links/clk_dana_slack').status).toBe(204)
    expect(dana.settings().channels.slack).toEqual({ linked: false, enabled: false })
    expect(dana.call('DELETE', '/console/v1/chat-links/clk_mateo_slack').status).toBe(404)
  })

  it('refuses an expired or foreign token, and a chat user someone else holds', () => {
    const w = world()
    const dana = w.as('dana')
    expect(body<{ error: string }>(dana.call('POST', '/console/v1/chat-links', { token: token({ exp: Math.floor(START / 1000) - 1 }) })).error).toBe('chat-link.expired')
    expect(body<{ error: string }>(dana.call('POST', '/console/v1/chat-links', { token: 'a.b.c' })).error).toBe('chat-link.invalid')
    expect(dana.call('POST', '/console/v1/chat-links', { token: token({ platform: 'slack', workspaceId: 'T0FIRM01', chatUserId: 'U0MATEO7' }) })).toMatchObject({ status: 409, body: { error: 'chat-link.taken' } })
  })
})

describe('weekly digests', () => {
  it('lists two weeks of the session account, newest first, and reads one', () => {
    const w = world()
    const weeks = digestWeeksOf(START)
    expect(weeks.map((x) => x.weekStart)).toEqual(['2026-10-12', '2026-10-05'])
    const list = body<{ items: DigestSummary[] }>(w.as('mia').call('GET', '/console/v1/digests')).items
    expect(list.map((d) => d.weekStart)).toEqual(['2026-10-12', '2026-10-05'])
    const one = body<Digest>(w.as('mia').call('GET', '/console/v1/digests/2026-10-12'))
    expect(one).toMatchObject({ accountId: '123456789012', weekEnd: '2026-10-18', source: 'assistant' })
    expect(one.sections.map((s) => s.kind)).toEqual(['changes', 'drift', 'cost', 'access'])
    expect(w.as('mia').call('GET', '/console/v1/digests/2026-01-05').status).toBe(404)
    expect(body<Digest>(w.as('raj').call('GET', '/console/v1/digests/2026-10-12')).accountId).toBe('210987654321')
  })

  it('is written on Monday at 06:00 UTC: before then the last digest is the week before', () => {
    expect(digestWeeksOf(Date.parse('2026-10-19T05:00:00Z'))[0].weekStart).toBe('2026-10-05')
    expect(digestWeeksOf(Date.parse('2026-10-19T07:00:00Z'))[0].weekStart).toBe('2026-10-12')
  })
})
