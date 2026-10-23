import { describe, expect, it } from 'vitest'
import { channelState, defaultChannels, effectiveRules, factLabel, readChatLinkToken, routeOfLink, rulesToSave, weekWords, type NotificationSettings } from './notifications'
import { bellLabel } from '../app/NotificationsBell'

const TYPES = [{ service: 'compute', name: 'vm' }]
const COMPUTE = '123456789012'
const wfrn = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo02`

const settings = (over: Partial<NotificationSettings['channels']> = {}, rules: NotificationSettings['rules'] = {}): NotificationSettings => ({
  version: 1,
  channels: { email: { address: 'dana@example.com', enabled: true }, slack: { linked: true, enabled: true }, teams: { linked: false, enabled: false }, ...over },
  rules,
})

describe('where a notification leads', () => {
  it('reads a resource link, keeping its tab, whether the wfrn is encoded or not', () => {
    const to = `/console/compute/vm/${encodeURIComponent(wfrn)}?tab=drift`
    expect(routeOfLink({ link: `/r/vm/${encodeURIComponent(wfrn)}?tab=drift`, accountId: COMPUTE }, TYPES, COMPUTE)).toBe(to)
    expect(routeOfLink({ link: `/r/vm/${wfrn}?tab=drift`, accountId: COMPUTE }, TYPES, COMPUTE)).toBe(to)
  })

  it('reads requests, access requests and digests, and an absolute link from an email', () => {
    expect(routeOfLink({ link: '/jobs/job_1', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/requests/job_1')
    expect(routeOfLink({ link: 'https://console.example.com/console/jobs/job_1', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/requests/job_1')
    expect(routeOfLink({ link: '/access-requests/areq_002', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/iam/access-requests/areq_002')
    expect(routeOfLink({ link: '/digests/2026-10-12', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/digests/2026-10-12')
  })

  it('scopes a link of another account to it, so following it switches the session', () => {
    expect(routeOfLink({ link: '/jobs/job_demo_approval', accountId: '210987654321' }, TYPES, COMPUTE)).toBe('/account/210987654321/requests/job_demo_approval')
  })

  it('falls back to the wfrn or the job, else nothing', () => {
    expect(routeOfLink({ link: '/budgets/b1', wfrn, accountId: COMPUTE }, TYPES, COMPUTE)).toBe(`/console/compute/vm/${encodeURIComponent(wfrn)}`)
    expect(routeOfLink({ link: '/budgets/b1', jobId: 'job_9', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/requests/job_9')
    expect(routeOfLink({ link: '/budgets/b1', accountId: COMPUTE }, TYPES, COMPUTE)).toBeNull()
  })
})

describe('the rules and their defaults', () => {
  it('sends the loud kinds by email and the linked chats, the rest to the console only', () => {
    expect(defaultChannels('job.failed', { slack: true, teams: false })).toEqual(['console', 'email', 'slack'])
    expect(defaultChannels('job.succeeded', { slack: true, teams: true })).toEqual(['console'])
  })

  it('fills in the defaults, and saves only what differs from them', () => {
    const rules = effectiveRules(settings({}, { 'job.succeeded': ['console', 'email'] }))
    expect(rules['job.succeeded']).toEqual(['console', 'email'])
    expect(rules['digest.ready']).toEqual(['console', 'email', 'slack'])
    expect(rulesToSave(rules, { slack: true, teams: false })).toEqual({ 'job.succeeded': ['console', 'email'] })
    expect(rulesToSave({ ...rules, 'drift.found': [] }, { slack: true, teams: false })).toEqual({ 'job.succeeded': ['console', 'email'], 'drift.found': [] })
  })

  it('says why a channel carries nothing', () => {
    expect(channelState(settings(), 'teams')).toEqual({ on: false, reason: 'Teams is not linked' })
    expect(channelState(settings({ email: { enabled: false } }), 'email')).toEqual({ on: false, reason: 'Email is turned off' })
    expect(channelState(settings(), 'slack')).toEqual({ on: true })
  })
})

describe('words', () => {
  it('names a week within a month, across months and across years', () => {
    expect(weekWords('2026-10-12')).toBe('12 to 18 October 2026')
    expect(weekWords('2026-09-28', '2026-10-04')).toBe('28 September to 4 October 2026')
    expect(weekWords('2026-12-28')).toBe('28 December 2026 to 3 January 2027')
  })

  it('turns a fact key into words', () => {
    expect(factLabel('averageApprovalMinutes')).toBe('Average approval minutes')
    expect(factLabel('new_findings')).toBe('New findings')
  })

  it('names the bell with what waits', () => {
    expect(bellLabel(0, 0)).toBe('Notifications')
    expect(bellLabel(1, 0)).toBe('Notifications, 1 to decide')
    expect(bellLabel(1, 3)).toBe('Notifications, 1 to decide, 3 unread')
  })

  it('reads what a chat link token names, and nothing from a token it cannot read', () => {
    const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '')
    const token = `${b64({ alg: 'HS256' })}.${b64({ platform: 'teams', workspaceId: 'firm', chatUserId: '29:dana', chatUserName: 'Dana Reyes', exp: 1_800_000_000 })}.sig`
    expect(readChatLinkToken(token)).toEqual({ platform: 'teams', workspaceId: 'firm', chatUserId: '29:dana', chatUserName: 'Dana Reyes', expiresAt: new Date(1_800_000_000_000).toISOString() })
    expect(readChatLinkToken('not-a-token')).toBeNull()
  })
})

describe('links from the data stay inside the console', () => {
  it('drops a host and any query but a tab name', () => {
    expect(routeOfLink({ link: 'javascript:alert(1)', accountId: COMPUTE }, TYPES, COMPUTE)).toBeNull()
    expect(routeOfLink({ link: 'https://evil.example/jobs/job_1?x=%22%3E', accountId: COMPUTE }, TYPES, COMPUTE)).toBe('/requests/job_1')
    expect(routeOfLink({ link: `/r/vm/${encodeURIComponent(wfrn)}?tab=drift&next=https://evil.example`, accountId: COMPUTE }, TYPES, COMPUTE)).toBe(`/console/compute/vm/${encodeURIComponent(wfrn)}?tab=drift`)
  })
})
