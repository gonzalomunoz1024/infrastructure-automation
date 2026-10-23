import { describe, expect, it } from 'vitest'
import { decisionKeys, driftInWords, fieldLabel, fieldNameOf, findingDecision, ignoreUntilOf, minutesText, moneyText, observedBrief, resolutionLine, revertStateOf } from './drift'
import type { Finding } from './types'

/* The words the drift pages use (./drift): findings, decisions, durations, costs and what a provider reports. */

const type = { schema: { type: 'object', properties: { memoryGb: { type: 'integer', title: 'Memory', 'x-console': { format: 'gibibytes' } }, cpu: { type: 'integer' } } } } as const
const open: Finding = { findingId: 'drift_1', wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo02', status: 'open', severity: 'warning', fields: [{ path: 'memoryGb', expected: 8, observed: 16 }], openedAt: '2026-10-22T10:00:00Z', can: ['revert', 'accept', 'ignore'] }

describe('findings in words', () => {
  it('names a field by its schema title, from a pointer or a dotted path', () => {
    expect(fieldNameOf('/memoryGb')).toBe('memoryGb')
    expect(fieldNameOf('attributes.memoryGb')).toBe('memoryGb')
    expect(fieldLabel('/memoryGb', type)).toBe('Memory')
    expect(fieldLabel('cpu', type)).toBe('CPU')
  })

  it('says what drifted, present while open and past once closed, leaving ignored fields out', () => {
    expect(driftInWords(open, type)).toBe('Memory is 16 GiB, asked for 8 GiB')
    expect(driftInWords({ ...open, status: 'closed' }, type)).toBe('Memory was 16 GiB, asked for 8 GiB')
    const two = { ...open, fields: [...open.fields, { path: 'cpu', expected: 2, observed: 4, ignored: true }] }
    expect(driftInWords(two, type)).toBe('Memory is 16 GiB, asked for 8 GiB')
  })

  it('says how a closed finding ended', () => {
    expect(resolutionLine({ resolution: 'reverted', decidedBy: 'dana' })).toBe('Put back to what was asked for by dana')
    expect(resolutionLine({ resolution: 'ignored', decidedBy: 'mateo' })).toBe('Ignored by mateo until what was asked for changes')
    expect(resolutionLine({ resolution: 'ignored', ignoredUntil: '2026-10-30T10:00:00Z' })).toMatch(/^Ignored until /)
    expect(resolutionLine({ resolution: 'vanished' })).toBe('The provider stopped reporting the difference')
  })

  it('reads a revert’s state: running, failed while open, or done', () => {
    expect(revertStateOf(open)).toBe('none')
    expect(revertStateOf({ ...open, revertStatus: 'running' })).toBe('running')
    expect(revertStateOf({ ...open, revertStatus: 'failed' })).toBe('failed')
    expect(revertStateOf({ ...open, status: 'closed', revertStatus: 'succeeded' })).toBe('done')
  })
})

describe('deciding on a finding', () => {
  const allowed = { allowed: true }
  const denied = { allowed: false, denied: { layer: 'console-iam' as const, action: 'compute:update', resource: open.wfrn, reason: 'no policy allows it', requestable: true } }

  it('keeps the role’s refusal, with its denial, before anything the finding says', () => {
    expect(findingDecision(denied, open, 'revert')).toBe(denied)
  })

  it('says why a decision the role allows is not offered on this finding', () => {
    expect(findingDecision(allowed, open, 'revert')).toBe(allowed)
    expect(findingDecision(allowed, { ...open, status: 'closed' }, 'accept').why).toBe('This finding is closed.')
    const running = { ...open, revertStatus: 'running' as const, can: ['ignore' as const] }
    expect(findingDecision(allowed, running, 'revert').why).toMatch(/^A revert is running/)
    expect(findingDecision(allowed, running, 'accept').why).toMatch(/^A revert is running/)
    expect(findingDecision(allowed, running, 'ignore').allowed).toBe(true)
    expect(findingDecision(allowed, { ...open, can: ['accept', 'ignore'] }, 'revert').why).toMatch(/does not set these fields/)
  })

  it('names what a decision makes the pages read again', () => {
    expect(decisionKeys({ name: 'vm', plural: 'vms' })).toEqual(expect.arrayContaining(['console-drift/vm', 'console-findings', 'console-resource/vm', 'resource/vms']))
  })

  it('turns an ignore’s choice into its end, or none for until the desired state changes', () => {
    const now = Date.parse('2026-10-23T10:00:00Z')
    expect(ignoreUntilOf('desired', now)).toBeUndefined()
    expect(ignoreUntilOf('7', now)).toBe('2026-10-30T10:00:00.000Z')
  })
})

describe('durations, costs and what a provider reports', () => {
  it('reads the mean time to close as a short duration', () => {
    expect(minutesText(null)).toBe('Nothing closed yet')
    expect(minutesText(45)).toBe('45 min')
    expect(minutesText(200)).toBe('3 h 20 min')
    expect(minutesText(120)).toBe('2 h')
    expect(minutesText(52 * 60)).toBe('2 days 4 h')
    expect(minutesText(24 * 60)).toBe('1 day')
  })

  it('reads a monthly cost, or that there is none', () => {
    expect(moneyText(undefined)).toBe('No estimate')
    expect(moneyText({ amount: 77, currency: 'USD' })).toMatch(/77\.00 a month$/)
  })

  it('briefs what a provider reports: up to four telling attributes, never an object', () => {
    expect(observedBrief({ name: 'x', cpu: 4, memoryGb: 16, environment: 'dev', powerState: 'poweredOn', tags: { team: 'a' } }, type)).toBe('4 CPU · Memory 16 GiB · Environment dev · Power state poweredOn')
    expect(observedBrief({})).toBe('Nothing reported')
  })
})
