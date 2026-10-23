import { describe, expect, it } from 'vitest'
import { decidableBy, isOpen, progressOf, type AccessReviewItem } from './accessReviews'
import { earliestEnd, elevatableRole, groupRolesOf, isoDurationMs, minutesWords, ruleFor, servedStatus, timeLeftWords, type ElevationRules } from './elevations'
import { resourcesRemainOf } from './organization'
import { permissionsOf } from './permissions'
import type { BffConsoleType, Denial } from './types'

/*
 * The rules the access reviews, elevations and organization pages read in words: what is left to
 * review and by whom, which rule approves an ask at once, when a refusal may offer to hold a role
 * for a while, the time left, and a refused close's list of what remains.
 */

const RULES: ElevationRules = {
  defaultMinutes: 60,
  maxMinutes: 480,
  pendingFor: 'PT24H',
  breakGlassMaxMinutes: 240,
  items: [{ name: 'operators-admin-two-hours', roles: ['account-admin'], requesterRoles: ['operator'], maxMinutes: 120 }],
}
const NOW = Date.parse('2026-10-23T10:00:00Z')

const item = (userId: string, decision: AccessReviewItem['decision']): AccessReviewItem => ({
  itemId: `i-${userId}`,
  reviewId: 'arev_1',
  userId,
  groupId: 'g',
  groupName: 'members',
  roles: ['operator'],
  decision,
})
const denial = (over: Partial<Denial> = {}): Denial => ({ layer: 'console-iam', action: 'compute:delete', reason: 'no policy allows it', requestable: true, suggestedRole: 'account-admin', ...over })

describe('access reviews', () => {
  it('leaves a person’s own items and decided ones out of what they may decide', () => {
    expect(decidableBy([item('raj', 'pending'), item('ana', 'pending'), item('mia', 'keep')], 'raj').map((i) => i.userId)).toEqual(['ana'])
  })
  it('counts progress and stops taking decisions at the due date', () => {
    expect(progressOf({ total: 5, kept: 1, revoked: 1, expired: 0, pending: 3 })).toBe(40)
    expect(progressOf({ total: 0, kept: 0, revoked: 0, expired: 0, pending: 0 })).toBe(100)
    expect(isOpen({ status: 'open', dueAt: '2026-10-24T00:00:00Z' }, NOW)).toBe(true)
    expect(isOpen({ status: 'open', dueAt: '2026-10-22T00:00:00Z' }, NOW)).toBe(false)
  })
})

describe('elevations', () => {
  it('finds the rule that approves at once only for the roles held through groups and a duration it covers', () => {
    expect(ruleFor(RULES, 'admin', ['operator'], 60)?.name).toBe('operators-admin-two-hours')
    expect(ruleFor(RULES, 'account-admin', ['operator'], 180)).toBeUndefined()
    expect(ruleFor(RULES, 'account-admin', ['viewer'], 60)).toBeUndefined()
    expect(ruleFor(RULES, 'operator', ['viewer'], 60)).toBeUndefined()
  })
  it('takes the held roles out of the session’s to find the group roles', () => {
    expect(groupRolesOf(['account-admin', 'operator'], [{ elevationId: 'e', role: 'account-admin', kind: 'elevation', until: '2026-10-23T11:00:00Z' }])).toEqual(['operator'])
  })
  it('offers to hold a role only for a requestable policy refusal naming a role not held', () => {
    expect(elevatableRole(denial(), ['operator'])).toBe('account-admin')
    expect(elevatableRole(denial({ layer: 'platform' }), ['operator'])).toBeNull()
    expect(elevatableRole(denial({ requestable: false }), ['operator'])).toBeNull()
    expect(elevatableRole({ ...denial(), refusedBy: 'guardrail' } as Denial, ['operator'])).toBeNull()
    expect(elevatableRole(denial(), ['account-admin'])).toBeNull()
  })
  it('reads durations and says the time left in words', () => {
    expect(isoDurationMs('PT24H')).toBe(86_400_000)
    expect(isoDurationMs('P1DT30M')).toBe(86_400_000 + 1_800_000)
    expect(isoDurationMs('soon')).toBeNull()
    expect(minutesWords(150)).toBe('2 hours 30 minutes')
    expect(minutesWords(1)).toBe('1 minute')
    expect(timeLeftWords('2026-10-23T10:52:30Z', NOW)).toBe('52 minutes left')
    expect(timeLeftWords('2026-10-23T10:00:20Z', NOW)).toBe('less than a minute left')
    expect(timeLeftWords('2026-10-23T09:00:00Z', NOW)).toBe('ended')
    expect(servedStatus({ status: 'active', until: '2026-10-23T09:59:00Z' }, NOW)).toBe('expired')
    expect(
      earliestEnd([
        { elevationId: 'a', role: 'operator', kind: 'elevation', until: '2026-10-23T12:00:00Z' },
        { elevationId: 'b', role: 'auditor', kind: 'break-glass', until: '2026-10-23T11:00:00Z' },
      ]),
    ).toBe(Date.parse('2026-10-23T11:00:00Z'))
    expect(earliestEnd(undefined)).toBeNull()
  })
  it('keeps the elevated mark of an action the BFF allowed by a held role', () => {
    const types: BffConsoleType[] = [
      {
        key: 'compute/vm',
        type: 'vm',
        service: 'compute',
        actions: [
          { verb: 'delete', title: 'Delete', allowed: true, elevated: true },
          { verb: 'get', title: 'Get', allowed: true },
        ],
      },
    ]
    expect(permissionsOf(types)['compute/vm']).toEqual({ delete: { allowed: true, elevated: true }, get: { allowed: true } })
  })
})

describe('closing an account', () => {
  it('reads what a refused close names', () => {
    expect(resourcesRemainOf({ status: 409, error: 'account.hasResources', detail: { total: 3, byType: { vm: 3 } } })).toEqual({ total: 3, byType: { vm: 3 } })
    expect(resourcesRemainOf({ status: 409, error: 'the account is closed already' })).toBeNull()
  })
})
