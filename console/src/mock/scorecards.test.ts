import { describe, expect, it } from 'vitest'
import type { Evaluation, Remediation, ResourceCompliance, RulePackSummary, Scorecard, ScorecardHeatmap } from '../lib/scorecards'
import { scoreOf } from '../lib/scorecards'
import type { Delivery, Subscription } from '../lib/subscriptions'
import type { Page } from '../lib/types'
import { APPROVAL_MS, RUN_MS } from './commands'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Compliance scorecards (./scorecards) and event subscriptions (./subscriptions) in the mock: the
 * account's score by pack and rule, the heat map across the person's accounts, the resources
 * failing a rule, a workflow fix that passes the rule once its request succeeded, a tags fix, a
 * resource's own evaluations, the refusals; and a subscription created, tested and deleted.
 */

const START = Date.parse('2026-10-23T10:00:00Z')
const COMPUTE = '123456789012'
const enc = encodeURIComponent
const OFF = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-gen-011`

function world(person = 'gonzalo') {
  let now = START
  let who = person
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const call = (method: string, url: string, body?: unknown) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who) }, body })
  const as = (next: string) => {
    who = next
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${next}`, headers: {} })
  }
  as(person)
  return { data, call, as, get: (url: string) => call('GET', url), tick: (ms: number) => (now += ms) }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const failing = (w: ReturnType<typeof world>) => body<Page<Evaluation>>(w.get('/console/v1/scorecards/packs/compute-baseline/rules/prod-powered-on/failing')).items

describe('the scorecard', () => {
  it('weighs critical 10, warning 3 and info 1, and has no score when nothing is judged', () => {
    expect(scoreOf([{ severity: 'critical', status: 'pass' }, { severity: 'warning', status: 'fail' }, { severity: 'info', status: 'error' }])).toBe(77)
    expect(scoreOf([])).toBeNull()
  })

  it('scores the account by pack and rule, with a trend ending on today’s score', () => {
    const w = world()
    const card = body<Scorecard>(w.get('/console/v1/scorecards'))
    expect(card.accountId).toBe(COMPUTE)
    expect(card.packs.map((p) => p.name)).toEqual(['required-tags', 'compute-baseline', 'data-baseline'])
    const power = card.packs[1].rules.find((r) => r.id === 'prod-powered-on')!
    expect(power).toMatchObject({ failed: 3, severity: 'critical', remediable: true })
    expect(card.trend).toHaveLength(30)
    expect(card.trend.at(-1)?.score).toBe(card.score)
    expect(card.bySeverity.critical.failed).toBeGreaterThanOrEqual(3)
    expect(body<Page<RulePackSummary>>(w.get('/console/v1/scorecards/packs')).items[1].rules[2].remediation).toMatchObject({ kind: 'workflow', verb: 'restart' })
  })

  it('draws the heat map over the accounts the person may read', () => {
    const w = world()
    const map = body<ScorecardHeatmap>(w.get('/console/v1/scorecards/heatmap'))
    expect(map.rows.map((r) => r.accountName).sort()).toEqual(['Compute', 'Data'])
    expect(Object.keys(map.rows[0].packs)).toEqual(['required-tags', 'compute-baseline', 'data-baseline'])
    w.as('dana')
    expect(body<ScorecardHeatmap>(w.get('/console/v1/scorecards/heatmap')).rows.map((r) => r.accountName)).toEqual(['Compute'])
  })

  it('lists the failing resources, longest failing first, and fixes one with the rule’s workflow', () => {
    const w = world()
    const rows = failing(w)
    expect(rows.map((r) => r.wfrn)).toContain(OFF)
    expect([...rows].sort((a, b) => a.since.localeCompare(b.since))).toEqual(rows)
    const fixed = w.call('POST', '/console/v1/scorecards/packs/compute-baseline/rules/prod-powered-on:remediate', { wfrn: OFF })
    expect(fixed.status).toBe(202)
    const r = body<Remediation>(fixed)
    expect(r).toMatchObject({ kind: 'workflow', workflow: 'vm-restart', jobId: expect.stringMatching(/^job_/) })
    expect(w.data.console?.jobs[r.jobId!]?.labels).toEqual({ 'scorecard-rule': 'compute-baseline/prod-powered-on' })
    // a second fix waits for the first
    expect(body<{ error: string }>(w.call('POST', '/console/v1/scorecards/packs/compute-baseline/rules/prod-powered-on:remediate', { wfrn: OFF })).error).toBe('resource.inFlight')
    w.tick(APPROVAL_MS + RUN_MS + 1)
    expect(failing(w).map((x) => x.wfrn)).not.toContain(OFF)
    expect(body<{ error: string }>(w.call('POST', '/console/v1/scorecards/packs/compute-baseline/rules/prod-powered-on:remediate', { wfrn: OFF })).error).toBe('evaluation.passing')
  })

  it('sets a tag for a tags fix, and says when a rule has no fix', () => {
    const w = world()
    const row = body<Page<Evaluation>>(w.get('/console/v1/scorecards/packs/required-tags/rules/data-class/failing')).items[0]
    const res = w.call('POST', '/console/v1/scorecards/packs/required-tags/rules/data-class:remediate', { wfrn: row.wfrn, input: { dataClass: 'confidential' } })
    expect(res.status).toBe(200)
    expect(body<Remediation>(res).tags).toEqual({ 'data-class': 'confidential' })
    expect(body<Page<Evaluation>>(w.get('/console/v1/scorecards/packs/required-tags/rules/data-class/failing')).items.map((x) => x.wfrn)).not.toContain(row.wfrn)
    expect(w.call('POST', '/console/v1/scorecards/packs/required-tags/rules/cost-centre:remediate', { wfrn: row.wfrn, input: { costCentre: '4411' } }).status).toBe(400)
  })

  it('reads a resource’s evaluations, failing first', () => {
    const w = world()
    const c = body<ResourceCompliance>(w.get(`/console/v1/r/vm/${enc(OFF)}/compliance`))
    expect(c.results[0]).toMatchObject({ ruleId: 'prod-powered-on', status: 'fail', remediable: true })
    expect(c.failed).toBeGreaterThan(0)
    expect(c.passed).toBeGreaterThan(0)
  })

  it('lets a viewer read and not fix', () => {
    const w = world('mia')
    expect(w.get('/console/v1/scorecards').status).toBe(200)
    expect(w.call('POST', '/console/v1/scorecards/packs/compute-baseline/rules/prod-powered-on:remediate', { wfrn: OFF }).status).toBe(403)
  })
})

describe('event subscriptions', () => {
  const hook = { name: 'Compute changes', events: ['ProvisioningSucceeded', 'Deleted'], target: { kind: 'webhook', url: 'https://hooks.example.com/console', secret: 'a-long-signing-secret' } }

  it('creates, tests and deletes a webhook subscription, never reading the secret back', () => {
    const w = world()
    const made = w.call('POST', '/console/v1/event-subscriptions', hook)
    expect(made.status).toBe(201)
    const sub = body<Subscription>(made)
    expect(sub.target).toEqual({ kind: 'webhook', url: 'https://hooks.example.com/console', secretSet: true })
    expect(JSON.stringify(w.get('/console/v1/event-subscriptions').body)).not.toContain('a-long-signing-secret')
    const test = body<Delivery>(w.call('POST', `/console/v1/event-subscriptions/${sub.subscriptionId}:test`, {}))
    expect(test).toMatchObject({ status: 'delivered', httpStatus: 200, test: true, eventType: 'ProvisioningSucceeded' })
    expect(body<Subscription>(w.get(`/console/v1/event-subscriptions/${sub.subscriptionId}`)).lastDelivery?.deliveryId).toBe(test.deliveryId)
    expect(w.call('DELETE', `/console/v1/event-subscriptions/${sub.subscriptionId}`).status).toBe(204)
    expect(body<Page<Subscription>>(w.get('/console/v1/event-subscriptions')).items).toEqual([])
  })

  it('says a failing endpoint fails, and checks what it is sent', () => {
    const w = world()
    const sub = body<Subscription>(w.call('POST', '/console/v1/event-subscriptions', { ...hook, name: 'Failing hook', target: { kind: 'webhook', url: 'https://hooks.example.com/fail' } }))
    expect(body<Delivery>(w.call('POST', `/console/v1/event-subscriptions/${sub.subscriptionId}:test`, {}))).toMatchObject({ status: 'failed', httpStatus: 500 })
    expect(body<Subscription>(w.get(`/console/v1/event-subscriptions/${sub.subscriptionId}`)).status).toBe('failing')
    const bad = w.call('POST', '/console/v1/event-subscriptions', { name: '', events: [], target: { kind: 'webhook', url: 'http://plain' } })
    expect(bad.status).toBe(400)
    expect(body<{ problems: { field: string }[] }>(bad).problems.map((p) => p.field)).toEqual(['/name', '/events', '/target/url'])
    expect(w.call('POST', '/console/v1/event-subscriptions', { ...hook, name: 'Compute changes', target: { kind: 'slack', channel: '#compute' } }).status).toBe(201)
    expect(w.call('POST', '/console/v1/event-subscriptions', { ...hook, name: 'compute changes', target: { kind: 'kafka', topic: 'team-compute.events' } }).status).toBe(409)
  })

  it('lets a viewer read, an operator create and test, and only an admin delete', () => {
    const w = world()
    const sub = body<Subscription>(w.call('POST', '/console/v1/event-subscriptions', hook))
    w.as('mia')
    expect(w.get('/console/v1/event-subscriptions').status).toBe(200)
    expect(w.call('POST', `/console/v1/event-subscriptions/${sub.subscriptionId}:test`, {}).status).toBe(403)
    w.as('dana')
    expect(w.call('POST', `/console/v1/event-subscriptions/${sub.subscriptionId}:test`, {}).status).toBe(200)
    expect(w.call('DELETE', `/console/v1/event-subscriptions/${sub.subscriptionId}`).status).toBe(403)
  })
})
