import { describe, expect, it } from 'vitest'
import type { ConsoleQuotaRequest, QuotaRules } from '../lib/quotas'
import type { Page, Quota, QuotaRequest } from '../lib/types'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Service Quotas in the mock: the organization's rules; an ask a rule covers is approved at once
 * and raises the limit, then the key cools down; any other ask waits for the platform team,
 * who decide it (never their own); the account's requests carry what each person may do with
 * them; a viewer reads everything and decides nothing.
 */

const T0 = Date.parse('2026-10-23T10:00:00Z')
const DAY = 86_400_000

function bff(start = T0) {
  let now = start
  let person = 'dana'
  const server = createMockServer(buildMockData(), memorySessionStore(), () => now)
  const signIn = (who: string) => {
    person = who
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${who}`, headers: {} })
  }
  signIn('dana')
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person) }, body })
  return {
    call,
    signIn,
    advance: (ms: number) => {
      now += ms
      signIn(person)
    },
    quota: (key: string) => call('GET', `/console/v1/iam/quotas/${key}`).body as Quota,
    requests: () => (call('GET', '/console/v1/iam/quota-requests').body as Page<ConsoleQuotaRequest>).items,
  }
}

describe('quota rules', () => {
  it('serves the rules in the order they are read, and who decides the rest', () => {
    const b = bff()
    const rules = b.call('GET', '/console/v1/iam/quota-rules').body as QuotaRules
    expect(rules.approver).toBe('platform-team')
    expect(rules.items.map((r) => r.name)).toEqual(['resources-double', 'access-keys-double', 'jobs-half-again'])
    expect(rules.items[0]).toMatchObject({ keys: ['resources.*'], maxFactor: 2, maxLimit: 1000, cooldown: 'PT720H' })
  })
})

describe('asking for more', () => {
  it('approves at once what a rule covers, raises the limit, and cools the key down', () => {
    const b = bff()
    const before = b.quota('resources.vm')
    expect(before.limit).toBe(400)
    expect(b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 400, reason: 'the same as now is no increase' })).toMatchObject({ status: 400, body: { error: 'not-an-increase' } })
    const asked = b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 600, reason: 'The batch farm moves here next week' })
    expect(asked.status).toBe(202)
    expect(asked.body).toMatchObject({ status: 'approved', grantedLimit: 600, decidedBy: 'rule:resources-double', currentLimit: 400, requestedLimit: 600 })
    expect(b.quota('resources.vm')).toMatchObject({ limit: 600, source: 'override' })
    expect(b.quota('resources.vm').pendingRequestId).toBeUndefined()

    // within 30 days no rule raises it again, even within twice the limit
    const again = b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 700, reason: 'A second batch farm arrives too' })
    expect(again.body).toMatchObject({ status: 'open', routedTo: 'platform-team' })
    expect(b.quota('resources.vm').pendingRequestId).toBe((again.body as QuotaRequest).requestId)
    expect(b.call('DELETE', `/console/v1/iam/quota-requests/${(again.body as QuotaRequest).requestId}`).status).toBe(204)

    b.advance(31 * DAY)
    expect(b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 1000, reason: 'A month on, the farm grew' }).body).toMatchObject({ status: 'approved', decidedBy: 'rule:resources-double' })
    // past the rule's ceiling of 1,000 it waits
    expect(b.call('POST', '/console/v1/iam/quotas/access-keys:request-increase', { limit: 150, reason: 'A key for every build runner' }).body).toMatchObject({ status: 'open', routedTo: 'platform-team' })
  })

  it('lets the platform team decide what no rule approved, never their own, and says who may on every request', () => {
    const b = bff()
    const asked = b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 3000, reason: 'The whole render farm moves here' }).body as QuotaRequest
    expect(asked).toMatchObject({ status: 'open', routedTo: 'platform-team' })

    // the requester withdraws, never decides
    const own = b.requests()[0]
    expect(own.can).toEqual(['withdraw'])
    expect(own.refusals?.decide).toMatchObject({ action: 'quota:decide', reason: 'Nobody decides their own quota request', requestable: false })
    expect(b.call('POST', `/console/v1/iam/quota-requests/${asked.requestId}:decide`, { decision: 'approve' })).toMatchObject({ status: 403, body: { denied: { action: 'quota:decide' } } })

    // a viewer reads the requests and does nothing with someone else's
    b.signIn('mia')
    const seen = b.requests()[0]
    expect(seen.can).toEqual([])
    expect(seen.refusals?.decide?.reason).toMatch(/^The platform team decides/)
    expect(seen.refusals?.withdraw).toMatchObject({ action: 'quota:set', role: 'viewer' })
    expect(b.call('POST', `/console/v1/iam/quota-requests/${asked.requestId}:decide`, { decision: 'approve' }).status).toBe(403)

    // the platform team decides; the limit granted may differ from the ask
    b.signIn('gonzalo')
    expect(b.requests()[0].can).toEqual(['decide', 'withdraw'])
    expect(b.call('POST', `/console/v1/iam/quota-requests/${asked.requestId}:decide`, { decision: 'maybe' }).status).toBe(400)
    const decided = b.call('POST', `/console/v1/iam/quota-requests/${asked.requestId}:decide`, { decision: 'approve', limit: 2500, comment: 'Room for the farm, not the spares' })
    expect(decided).toMatchObject({ status: 202, body: { status: 'approved', grantedLimit: 2500, decidedBy: 'gonzalo', comment: 'Room for the farm, not the spares', can: [] } })
    expect(b.quota('resources.vm').limit).toBe(2500)
    expect(b.call('POST', `/console/v1/iam/quota-requests/${asked.requestId}:decide`, { decision: 'reject' })).toMatchObject({ status: 409, body: { error: 'the request is approved' } })

    // a rejection leaves the limit
    b.signIn('dana')
    const next = b.call('POST', '/console/v1/iam/quotas/jobs-per-minute:request-increase', { limit: 5000, reason: 'Every runner at once, please' }).body as QuotaRequest
    b.signIn('gonzalo')
    expect(b.call('POST', `/console/v1/iam/quota-requests/${next.requestId}:decide`, { decision: 'reject', comment: 'Ask for 600 and stagger them' }).body).toMatchObject({ status: 'rejected', decidedBy: 'gonzalo' })
    expect(b.quota('jobs-per-minute').limit).toBe(60)
  })

  it('lists the account’s requests newest first, filtered by status and quota', () => {
    const b = bff()
    b.call('POST', '/console/v1/iam/quotas/resources.vm:request-increase', { limit: 600, reason: 'The batch farm moves here next week' })
    b.advance(60_000)
    b.call('POST', '/console/v1/iam/quotas/access-keys:request-increase', { limit: 150, reason: 'A key for every build runner' })
    expect(b.requests().map((r) => r.quotaKey)).toEqual(['access-keys', 'resources.vm'])
    expect((b.call('GET', '/console/v1/iam/quota-requests?status=open').body as Page<QuotaRequest>).items.map((r) => r.quotaKey)).toEqual(['access-keys'])
    expect((b.call('GET', '/console/v1/iam/quota-requests?quotaKey=resources.vm').body as Page<QuotaRequest>).items).toHaveLength(1)
    expect(b.call('GET', '/console/v1/iam/quota-requests?status=nope').status).toBe(400)
    expect(b.call('GET', '/console/v1/iam/quota-requests/qreq_999').status).toBe(404)
    // the other account's requests are its own
    b.signIn('raj')
    expect(b.requests()).toEqual([])
  })
})
