import { describe, expect, it } from 'vitest'
import { approvingRule, ceilingOf, cooldownUntil, decidedByWords, durationMs, durationWords, keyMatches, quotaRefusalOf, quotaTitle, requestStatusOf, ruleCovering, ruleSentence, suggestedLimit, type QuotaRules } from './quotas'
import type { QuotaRequest } from './types'

/* The quota rules read as the identity service reads them, and the words the Service Quotas page says them in. */

const RULES: QuotaRules = {
  items: [
    { name: 'resources-double', keys: ['resources.*'], maxFactor: 2, maxLimit: 1000, cooldown: 'PT720H' },
    { name: 'access-keys-double', keys: ['access-keys'], maxFactor: 2, maxLimit: 100, cooldown: 'PT720H' },
    { name: 'jobs-half-again', keys: ['jobs-per-minute'], maxFactor: 1.5, maxLimit: 600, cooldown: 'PT168H' },
  ],
  approver: 'platform-team',
}
const NOW = Date.parse('2026-10-23T12:00:00Z')
const DAY = 86_400_000

const raised = (key: string, at: number, by = 'rule:resources-double'): QuotaRequest => ({ requestId: `qreq_${at}`, accountId: '123456789012', quotaKey: key, currentLimit: 400, requestedLimit: 600, reason: 'more', status: 'approved', requestedBy: 'dana', requestedAt: new Date(at).toISOString(), decidedAt: new Date(at).toISOString(), decidedBy: by })

describe('reading the rules', () => {
  it('matches key patterns, `*` being any run of characters', () => {
    expect(keyMatches('resources.*', 'resources.vm')).toBe(true)
    expect(keyMatches('resources.*', 'resources.')).toBe(true)
    expect(keyMatches('resources.*', 'access-keys')).toBe(false)
    expect(keyMatches('access-keys', 'access-keys')).toBe(true)
    expect(keyMatches('access-keys', 'access-keys-2')).toBe(false)
    expect(keyMatches('*.vm', 'resources.vm')).toBe(true)
    expect(keyMatches('a*b*c', 'axxbyyc')).toBe(true)
    expect(keyMatches('a*b*c', 'axxcyyb')).toBe(false)
  })

  it('reads ISO 8601 durations', () => {
    expect(durationMs('PT720H')).toBe(30 * DAY)
    expect(durationMs('P7D')).toBe(7 * DAY)
    expect(durationMs('P1DT12H30M')).toBe(DAY + 12.5 * 3_600_000)
    expect(durationMs(undefined)).toBe(0)
    expect(durationMs('30d')).toBe(0)
    expect(durationWords('PT720H')).toBe('30 days')
    expect(durationWords('PT24H')).toBe('1 day')
    expect(durationWords('PT12H')).toBe('12 hours')
  })

  it('approves at once within the factor and the ceiling, outside a cooldown', () => {
    expect(ruleCovering(RULES, 'resources.vm')?.name).toBe('resources-double')
    expect(ruleCovering(RULES, 'other')).toBeUndefined()
    expect(ceilingOf(RULES.items[0], 400)).toBe(800)
    expect(ceilingOf(RULES.items[0], 700)).toBe(1000)
    expect(ceilingOf(RULES.items[2], 60)).toBe(90)
    expect(approvingRule(RULES, [], 'resources.vm', 400, 800, NOW)?.name).toBe('resources-double')
    expect(approvingRule(RULES, [], 'resources.vm', 400, 801, NOW)).toBeUndefined()
    expect(approvingRule(RULES, [], 'resources.vm', 700, 1001, NOW)).toBeUndefined()
    expect(approvingRule(RULES, [], 'other', 1, 2, NOW)).toBeUndefined()
    const recent = [raised('resources.vm', NOW - 10 * DAY)]
    expect(approvingRule(RULES, recent, 'resources.vm', 600, 700, NOW)).toBeUndefined()
    expect(cooldownUntil(RULES.items[0], recent, 'resources.vm', NOW)).toBe(NOW + 20 * DAY)
    // another key's raise, a person's approval and an old raise start no cooldown
    expect(approvingRule(RULES, recent, 'resources.volume', 100, 200, NOW)?.name).toBe('resources-double')
    expect(approvingRule(RULES, [raised('resources.vm', NOW - DAY, 'gonzalo')], 'resources.vm', 600, 700, NOW)?.name).toBe('resources-double')
    expect(approvingRule(RULES, [raised('resources.vm', NOW - 31 * DAY)], 'resources.vm', 600, 700, NOW)?.name).toBe('resources-double')
  })
})

describe('the words', () => {
  it('names quotas by their type, and says what a rule gives', () => {
    expect(quotaTitle('resources.vm', [{ name: 'vm', title: 'Virtual machines' }])).toBe('Virtual machines')
    expect(quotaTitle('resources.queue')).toBe('Resources of type queue')
    expect(quotaTitle('jobs-per-minute')).toBe('Jobs per minute')
    expect(ruleSentence(RULES.items[0])).toBe('Raised at once to up to twice the limit, 1,000 at most, once every 30 days.')
    expect(ruleSentence(RULES.items[2])).toBe('Raised at once to up to half as much again as the limit, 600 at most, once every 7 days.')
    expect(ruleSentence({ name: 'x', keys: ['x'], maxFactor: 3, maxLimit: 10 })).toBe('Raised at once to up to 3 times the limit, 10 at most.')
  })

  it('says a request’s status and who decided it', () => {
    expect(requestStatusOf({ status: 'open', routedTo: 'platform-team' })).toEqual({ status: 'waiting', label: 'Waiting for the platform team' })
    expect(requestStatusOf({ status: 'withdrawn' }).label).toBe('Withdrawn')
    const names = (id: string) => (id === 'gonzalo' ? 'Gonzalo Munoz' : id)
    expect(decidedByWords({ status: 'approved', decidedBy: 'rule:resources-double' }, names)).toBe('the rule resources-double, at once')
    expect(decidedByWords({ status: 'approved', decidedBy: 'gonzalo' }, names)).toBe('Gonzalo Munoz')
    expect(decidedByWords({ status: 'approved', decidedBy: 'svc:platform-team' }, names)).toBe('the platform team service')
    expect(decidedByWords({ status: 'open' }, names)).toBeUndefined()
  })

  it('offers what a rule gives at once first, else half as much again', () => {
    expect(suggestedLimit(400, RULES.items[0])).toBe(800)
    expect(suggestedLimit(400, RULES.items[0], true)).toBe(600)
    expect(suggestedLimit(1000, RULES.items[0])).toBe(1500)
    expect(suggestedLimit(3)).toBe(5)
  })

  it('finds the quota a create preview refused', () => {
    expect(quotaRefusalOf({ policy: { decision: 'deny', rules: [{ id: 'quota.resources', result: 'deny', message: 'used up' }] } }, 'vm')).toEqual({ key: 'resources.vm', message: 'used up' })
    expect(quotaRefusalOf({ policy: { decision: 'allow', rules: [{ id: 'quota.resources', result: 'pass', message: '3 of 100' }] } }, 'vm')).toBeNull()
    expect(quotaRefusalOf(undefined, 'vm')).toBeNull()
  })
})
