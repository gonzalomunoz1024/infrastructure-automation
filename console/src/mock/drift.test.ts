import { describe, expect, it } from 'vitest'
import type { DiscoveryRun, FindingStats, FindingsSummary, UnmanagedObject } from '../lib/drift'
import type { BffJobDetail, ConsoleResourceDetail, Denial, Finding, Page } from '../lib/types'
import { RUN_MS } from './commands'
import { buildMockData } from './data'
import { DISCOVERY_MS, ownerGuessOf } from './drift'
import { restoreConsole, saveConsole } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * The BFF's drift routes in the mock (./drift): a finding's revert follows the update job it
 * started and closes when that job succeeded, a failed one stays open and is offered again;
 * accept writes the provider's values, ignore silences the fields until the desired state changes
 * or the time is up; the 409s the decisions answer; the account's summary and stats; discovery
 * runs, the unmanaged objects they find with their owner guesses, import and Assign to team; and
 * the journal replaying all of it after a reload.
 */

const COMPUTE = '123456789012'
const DATA = '210987654321'
const VM2 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-demo02`
/** api-047, a test machine whose provider refuses the first revert */
const API047 = `wfrn:wf:compute:global:${COMPUTE}:vm/vm-gen-037`
const START = Date.parse('2026-10-23T10:00:00Z')
const enc = encodeURIComponent

function world(person = 'dana', start = START) {
  let now = start
  let who = person
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who), ...headers }, body })
  const as = (next: string, accountId?: string) => {
    who = next
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${next}`, headers: {} })
    if (accountId) expect(call('PUT', '/console/v1/session', { accountId }).status).toBe(200)
  }
  as(person)
  return {
    data,
    server,
    call,
    as,
    get: (url: string) => call('GET', url),
    /** moves the clock; past a sign-in's lifetime the same person signs in again */
    tick: (ms: number) => {
      now += ms
      if (ms > 3_600_000) as(who)
    },
    now: () => now,
  }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const errorOf = (res: { body?: unknown }) => (res.body as { error?: string }).error

function openFindingOf(w: ReturnType<typeof world>, wfrn: string): Finding {
  const page = body<Page<Finding>>(w.get(`/console/v1/findings?wfrn=${enc(wfrn)}`))
  expect(page.items).toHaveLength(1)
  return page.items[0]
}

describe('deciding on a finding', () => {
  it('reverts through the update workflow, stays open while the job runs and closes when it succeeded', () => {
    const w = world()
    const f = openFindingOf(w, VM2)
    expect(f).toMatchObject({ status: 'open', severity: 'warning', type: 'vm', accountId: COMPUTE, region: 'global', version: 1, can: ['revert', 'accept', 'ignore'] })
    expect(f.fields).toEqual([{ path: 'memoryGb', expected: 8, observed: 16 }])

    const res = w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {}, { 'idempotency-key': 'revert-1' })
    expect(res.status).toBe(202)
    const { findingId, jobId } = body<{ findingId: string; jobId: string }>(res)
    expect(findingId).toBe(f.findingId)
    expect(jobId).toMatch(/^job_[a-z0-9]{10}$/)
    // a retry with the same key is answered from memory, and starts nothing new
    const again = w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {}, { 'idempotency-key': 'revert-1' })
    expect(again.headers?.['Idempotent-Replayed']).toBe('true')
    expect(body<{ jobId: string }>(again).jobId).toBe(jobId)

    const running = body<Finding>(w.get(`/console/v1/findings/${f.findingId}`))
    expect(running).toMatchObject({ status: 'open', revertStatus: 'running', revertJobId: jobId, decidedBy: 'dana', version: 2 })
    expect(running.can).toEqual(['ignore'])
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {}))).toBe('finding.reverting')
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:accept`, {}))).toBe('finding.reverting')

    const job = body<BffJobDetail>(w.get(`/console/v1/jobs/${jobId}`))
    expect(job).toMatchObject({ status: 'running', workflow: 'update-vm', title: 'Revert drift on api-01', requester: 'dana', wfrn: VM2 })
    expect(job.input).toEqual({ memoryGb: 8 })

    w.tick(RUN_MS + 1)
    const closed = body<Finding>(w.get(`/console/v1/findings/${f.findingId}`))
    expect(closed).toMatchObject({ status: 'closed', resolution: 'reverted', revertStatus: 'succeeded', decidedBy: 'dana', closedAt: new Date(START + RUN_MS).toISOString() })
    expect(closed.can).toEqual([])
    expect(body<Page<Finding>>(w.get(`/console/v1/findings?wfrn=${enc(VM2)}`)).items).toEqual([])
    const detail = body<ConsoleResourceDetail>(w.get(`/console/v1/r/vm/${enc(VM2)}`))
    expect(detail.drift).toBe(false)
    // the tab keeps it in the history, newest first, above the seeded one
    const tab = body<{ items: Finding[] }>(w.get(`/console/v1/r/vm/${enc(VM2)}/drift`)).items
    expect(tab.map((x) => [x.status, x.resolution])).toEqual([
      ['closed', 'reverted'],
      ['closed', 'ignored'],
    ])
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:ignore`, {}))).toBe('finding.closed')
  })

  it('keeps a revert whose step failed running until its request ends, then offers it again, and closes on the second', () => {
    const w = world()
    const f = openFindingOf(w, API047)
    expect(f.fields).toEqual([{ path: 'cpu', expected: 4, observed: 6 }])
    const first = body<{ jobId: string }>(w.call('POST', `/console/v1/findings/${f.findingId}:revert`, { reason: 'back to the size the request asked for' }))
    w.tick(RUN_MS + 1)
    // the provider refused it: the request pauses on its failed step (hub decision 24), which has not ended, so the revert still runs
    expect(body<BffJobDetail>(w.get(`/console/v1/jobs/${first.jobId}`)).status).toBe('needs-attention')
    expect(body<Finding>(w.get(`/console/v1/findings/${f.findingId}`))).toMatchObject({ status: 'open', revertStatus: 'running', revertJobId: first.jobId })
    // aborted, the request ends: the revert failed, and the finding stays open, offered again
    expect(w.call('POST', `/console/v1/jobs/${first.jobId}:abort`, {}).status).toBe(202)
    w.tick(1)
    const failed = body<Finding>(w.get(`/console/v1/findings/${f.findingId}`))
    expect(failed).toMatchObject({ status: 'open', revertStatus: 'failed', revertJobId: first.jobId, reason: 'back to the size the request asked for' })
    expect(failed.can).toEqual(['revert', 'accept', 'ignore'])
    expect(body<BffJobDetail>(w.get(`/console/v1/jobs/${first.jobId}`)).status).toBe('rolled-back')

    const second = body<{ jobId: string }>(w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {}))
    expect(second.jobId).not.toBe(first.jobId)
    expect(body<Finding>(w.get(`/console/v1/findings/${f.findingId}`)).revertStatus).toBe('running')
    w.tick(RUN_MS + 1)
    expect(body<Finding>(w.get(`/console/v1/findings/${f.findingId}`))).toMatchObject({ status: 'closed', resolution: 'reverted', revertJobId: second.jobId })
  })

  it('accepts the provider’s values into the desired state', () => {
    const w = world()
    const f = openFindingOf(w, VM2)
    const res = w.call('POST', `/console/v1/findings/${f.findingId}:accept`, { reason: 'the load test needs it' })
    expect(res.status).toBe(200)
    expect(body<Finding>(res)).toMatchObject({ findingId: f.findingId, status: 'closed', resolution: 'accepted', decidedBy: 'dana', reason: 'the load test needs it', closedAt: new Date(START).toISOString() })
    const detail = body<ConsoleResourceDetail>(w.get(`/console/v1/r/vm/${enc(VM2)}`))
    expect(detail.attributes).toMatchObject({ memoryGb: 16 })
    expect(detail.drift).toBe(false)
    expect(body<Page<Finding>>(w.get(`/console/v1/findings?wfrn=${enc(VM2)}`)).items).toEqual([])
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:accept`, {}))).toBe('finding.closed')
  })

  it('ignores the fields for a time, then the next scan opens a new finding', () => {
    const w = world()
    const f = openFindingOf(w, VM2)
    const until = new Date(START + 86_400_000).toISOString()
    const res = w.call('POST', `/console/v1/findings/${f.findingId}:ignore`, { until, reason: 'load test today' })
    expect(res.status).toBe(200)
    const ignored = body<Finding>(res)
    expect(ignored).toMatchObject({ status: 'closed', resolution: 'ignored', ignoredUntil: until, reason: 'load test today' })
    expect(ignored.fields.every((x) => x.ignored)).toBe(true)
    expect(body<ConsoleResourceDetail>(w.get(`/console/v1/r/vm/${enc(VM2)}`)).drift).toBe(false)

    w.tick(86_400_000 + 1)
    const next = openFindingOf(w, VM2)
    expect(next.findingId).not.toBe(f.findingId)
    expect(next).toMatchObject({ status: 'open', openedAt: until, version: 1 })
    expect(next.fields[0].ignored).toBeUndefined()
  })

  it('ignores until the desired state changes, when no time is given', () => {
    const w = world()
    const f = openFindingOf(w, VM2)
    expect(w.call('POST', `/console/v1/findings/${f.findingId}:ignore`, {}).status).toBe(200)
    w.tick(30 * 86_400_000)
    expect(body<Page<Finding>>(w.get(`/console/v1/findings?wfrn=${enc(VM2)}`)).items).toEqual([])
    // an edit of what was asked for ends the ignore: the next scan compares again
    expect(w.call('PATCH', `/console/v1/r/vm/${enc(VM2)}`, { input: { cpu: 4 } }).status).toBe(202)
    w.tick(RUN_MS + 1)
    const next = openFindingOf(w, VM2)
    expect(next.findingId).not.toBe(f.findingId)
    expect(next.fields.map((x) => x.path)).toEqual(['memoryGb'])
  })

  it('answers the 409s, 404s and 400s a decision can meet', () => {
    const w = world()
    expect(w.call('POST', '/console/v1/findings/drift_nothing:revert', {}).status).toBe(404)
    expect(errorOf(w.call('POST', '/console/v1/findings/drift_nothing:revert', {}))).toBe('finding.notFound')
    const f = openFindingOf(w, VM2)
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:ignore`, { until: new Date(START - 1000).toISOString() }))).toBe('validation.failed')
    expect(w.call('POST', `/console/v1/findings/${f.findingId}:ignore`, { until: 'soon' }).status).toBe(400)
    expect(w.call('POST', `/console/v1/findings/${f.findingId}:accept`, { reason: 'x'.repeat(1001) }).status).toBe(400)
    expect(w.call('GET', `/console/v1/findings/${f.findingId}:accept`).status).toBe(405)

    // another change in flight on the same machine holds the revert back
    expect(w.call('PATCH', `/console/v1/r/vm/${enc(VM2)}`, { input: { cpu: 4 } }).status).toBe(202)
    expect(errorOf(w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {}))).toBe('resource.busy')
    w.tick(RUN_MS + 1)

    // a field the update workflow does not set cannot be put back
    const vm = w.data.resourceTypes.find((t) => t.name === 'vm')!
    const cpu = vm.schema.properties!.cpu
    cpu['x-console'] = { ...cpu['x-console'], mutable: false }
    const fixed = openFindingOf(w, API047)
    expect(fixed.can).toEqual(['accept', 'ignore'])
    const refused = w.call('POST', `/console/v1/findings/${fixed.findingId}:revert`, {})
    expect(errorOf(refused)).toBe('finding.notRevertable')
    expect(body<{ detail: { why: string; fields: string[] } }>(refused).detail).toEqual({ findingId: fixed.findingId, why: 'The update workflow of vms does not set cpu', fields: ['cpu'] })
  })

  it('refuses a viewer, with the deciding layer, and shows them the findings with nothing to do', () => {
    const w = world('mia')
    const f = openFindingOf(w, VM2)
    expect(f.can).toEqual([])
    const refused = w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {})
    expect(refused.status).toBe(403)
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ layer: 'console-iam', action: 'compute:update' })
    expect(body<FindingsSummary>(w.get('/console/v1/findings/summary')).open).toBeGreaterThan(0)
  })

  it('replays the decisions after a reload, ending where the clock is', () => {
    const w = world()
    const f = openFindingOf(w, VM2)
    w.call('POST', `/console/v1/findings/${f.findingId}:revert`, {})
    const g = openFindingOf(w, API047)
    w.call('POST', `/console/v1/findings/${g.findingId}:ignore`, { reason: 'later' })
    const saved: Record<string, string> = {}
    saveConsole(w.data, { setItem: (k, v) => (saved[k] = v) })
    const fresh = buildMockData()
    expect(restoreConsole(fresh, { getItem: (k) => saved[k] ?? null }, START + RUN_MS + 1)).toBe(true)
    const again = createMockServer(fresh, memorySessionStore(), () => START + RUN_MS + 1)
    again.handle({ method: 'GET', url: '/auth/login?login_hint=dana', headers: {} })
    const read = (id: string) => again.handle({ method: 'GET', url: `/console/v1/findings/${id}`, headers: {} }).body as Finding
    expect(read(f.findingId)).toMatchObject({ status: 'closed', resolution: 'reverted' })
    expect(read(g.findingId)).toMatchObject({ status: 'closed', resolution: 'ignored', reason: 'later' })
  })
})

describe('the account’s drift', () => {
  it('lists the open findings across types, worst first, with filters and pages', () => {
    const w = world()
    const page = body<Page<Finding>>(w.get('/console/v1/findings?limit=200'))
    expect(page.items.length).toBeGreaterThan(5)
    expect(page.items.every((f) => f.status === 'open' && f.accountId === COMPUTE)).toBe(true)
    const ranks = page.items.map((f) => ['critical', 'warning', 'info'].indexOf(f.severity))
    expect(ranks).toEqual([...ranks].sort((a, b) => a - b))
    // a retired machine's drift is not listed
    expect(page.items.some((f) => f.wfrn.endsWith('/vm-gen-007'))).toBe(false)
    const critical = body<Page<Finding>>(w.get('/console/v1/findings?severity=critical&limit=200')).items
    expect(critical.length).toBeGreaterThan(0)
    expect(critical.every((f) => f.severity === 'critical')).toBe(true)
    expect(body<Page<Finding>>(w.get('/console/v1/findings?type=database')).items).toEqual([])
    const first = w.get('/console/v1/findings?limit=2')
    expect(body<Page<Finding>>(first).items).toHaveLength(2)
    expect(first.headers?.['X-Total-Count']).toBe(String(page.items.length))
    const closed = body<Page<Finding>>(w.get('/console/v1/findings?status=closed')).items
    expect(closed.map((f) => f.resolution)).toEqual(expect.arrayContaining(['reverted', 'accepted', 'ignored', 'vanished']))
    expect(w.get('/console/v1/findings?status=maybe').status).toBe(400)
  })

  it('sums the open findings, and a decision moves the sums', () => {
    const w = world()
    const before = body<FindingsSummary>(w.get('/console/v1/findings/summary'))
    expect(before.open).toBe(body<Page<Finding>>(w.get('/console/v1/findings?limit=200')).items.length)
    expect(before.byType).toEqual({ vm: before.open })
    expect(Object.values(before.bySeverity).reduce((a, b) => a + (b ?? 0), 0)).toBe(before.open)
    expect(before.lastScanAt).toBe(new Date(START - 9 * 60_000).toISOString())
    const f = openFindingOf(w, VM2)
    w.call('POST', `/console/v1/findings/${f.findingId}:accept`, {})
    const after = body<FindingsSummary>(w.get('/console/v1/findings/summary'))
    expect(after.open).toBe(before.open - 1)
    expect(after.bySeverity.warning).toBe((before.bySeverity.warning ?? 0) - 1)
  })

  it('counts the last 30 days: opened, closed by resolution and the mean time to close', () => {
    const w = world()
    const stats = body<FindingStats>(w.get('/console/v1/findings/stats'))
    expect(stats).toMatchObject({ accountId: COMPUTE, until: new Date(START).toISOString(), since: new Date(START - 30 * 86_400_000).toISOString() })
    // the seeded history of the compute account: two reverted, one accepted, one ignored, one settled by itself
    expect(stats.closed).toEqual({ total: 5, reverted: 2, accepted: 1, ignored: 1, vanished: 1 })
    expect(stats.open).toBe(body<FindingsSummary>(w.get('/console/v1/findings/summary')).open)
    expect(stats.opened).toBeGreaterThanOrEqual(stats.closed.total)
    expect(stats.meanMinutesToClose).toBe(109)
    const f = openFindingOf(w, VM2)
    w.call('POST', `/console/v1/findings/${f.findingId}:accept`, {})
    const after = body<FindingStats>(w.get('/console/v1/findings/stats'))
    expect(after.closed).toMatchObject({ total: 6, accepted: 2 })
    const week = body<FindingStats>(w.get(`/console/v1/findings/stats?since=${enc(new Date(START - 7 * 86_400_000).toISOString())}`))
    expect(week.closed.total).toBe(4)
    expect(w.get('/console/v1/findings/stats?since=yesterday').status).toBe(400)
    expect(w.get(`/console/v1/findings/stats?since=${enc(new Date(START + 1000).toISOString())}`).status).toBe(400)
    w.as('raj')
    expect(body<FindingStats>(w.get('/console/v1/findings/stats')).closed).toEqual({ total: 2, reverted: 0, accepted: 1, ignored: 0, vanished: 1 })
  })
})

describe('finding unmanaged resources', () => {
  it('runs a discovery on the clock, then lists what no account manages with owner guesses and costs', () => {
    const w = world()
    expect(body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged')).items).toEqual([])
    const res = w.call('POST', '/console/v1/discover', { type: 'vm' })
    expect(res.status).toBe(202)
    const run = body<DiscoveryRun>(res)
    expect(run).toMatchObject({ type: 'vm', region: 'global', status: 'running', startedAt: new Date(START).toISOString(), seen: 0, unmanaged: 0 })
    expect(errorOf(w.call('POST', '/console/v1/discover', { type: 'vm' }))).toBe('discover.running')
    w.tick(DISCOVERY_MS / 2)
    const half = body<DiscoveryRun>(w.get(`/console/v1/discover/${run.runId}`))
    expect(half.status).toBe('running')
    expect(half.unmanaged).toBe(4)
    expect(body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged')).items).toEqual([])
    w.tick(DISCOVERY_MS / 2)
    const done = body<DiscoveryRun>(w.get(`/console/v1/discover/${run.runId}`))
    expect(done).toMatchObject({ status: 'succeeded', unmanaged: 8, endedAt: new Date(START + DISCOVERY_MS).toISOString() })
    expect(done.seen).toBeGreaterThan(8)

    const items = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?limit=200')).items
    expect(items).toHaveLength(8)
    expect(items.every((o) => o.type === 'vm' && o.region === 'global' && o.can.join() === 'import,assign')).toBe(true)
    const costs = items.map((o) => o.monthlyCost?.amount ?? 0)
    expect(costs).toEqual([...costs].sort((a, b) => b - a))
    const byName = Object.fromEntries(items.map((o) => [o.name, o]))
    expect(byName['pay-gw-test'].ownerGuess).toMatchObject({ accountId: DATA, accountName: 'Data', team: 'team-data', confidence: 'high', reasons: ['Tagged team=team-data, the team that owns Data'] })
    expect(byName['compute-scratch-01'].ownerGuess).toMatchObject({ accountId: COMPUTE, confidence: 'medium' })
    expect(byName['tmp-build-agent'].ownerGuess).toMatchObject({ accountId: COMPUTE, confidence: 'low', reasons: ['In folder /dc1/compute, which only Compute’s resources use'] })
    expect(byName['old-jenkins'].ownerGuess).toBeUndefined()
    expect(byName['old-jenkins'].monthlyCost).toMatchObject({ currency: 'USD' })
    expect(byName['old-jenkins'].providerId).toBe('vsphere:vm-5133')

    const mine = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?owner=mine&limit=200')).items
    expect(mine.every((o) => o.ownerGuess?.accountId === COMPUTE)).toBe(true)
    expect(mine.length).toBe(4)
    const unknown = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?owner=unknown&limit=200')).items
    expect(unknown.map((o) => o.name).sort()).toEqual(['old-jenkins', 'win-desktop-qa'])
    expect(w.get('/console/v1/unmanaged?owner=theirs').status).toBe(400)
    expect(body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?type=database')).items).toEqual([])
  })

  it('refuses a type with no discovery read, an unknown run and someone else’s', () => {
    const w = world()
    expect(errorOf(w.call('POST', '/console/v1/discover', { type: 'volume' }))).toBe('discover.notSupported')
    expect(w.call('POST', '/console/v1/discover', { type: 'nothing' }).status).toBe(400)
    expect(errorOf(w.get('/console/v1/discover/run_nothing'))).toBe('discover.notFound')
    const run = body<DiscoveryRun>(w.call('POST', '/console/v1/discover', { type: 'vm' }))
    w.as('raj')
    expect(w.get(`/console/v1/discover/${run.runId}`).status).toBe(404)
  })

  it('imports an object as a managed resource with a new wfrn, gone from the list', () => {
    const w = world()
    w.call('POST', '/console/v1/discover', { type: 'vm' })
    w.tick(DISCOVERY_MS)
    const res = w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5102' })
    expect(res.status).toBe(201)
    const { wfrn } = body<{ wfrn: string }>(res)
    expect(wfrn).toBe(`wfrn:wf:compute:global:${COMPUTE}:vm/vm-5102`)
    const detail = body<ConsoleResourceDetail>(w.get(`/console/v1/r/vm/${enc(wfrn)}`))
    expect(detail).toMatchObject({ wfrn, type: 'vm', name: 'compute-scratch-01', state: 'active' })
    expect(detail.attributes).toMatchObject({ name: 'compute-scratch-01', cpu: 4, memoryGb: 16, environment: 'dev', owner: 'team-compute' })
    expect(w.data.resources.find((r) => r.id === 'vm-5102')?.providerId).toBe('vsphere:vm-5102')
    const items = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?limit=200')).items
    expect(items.map((o) => o.providerId)).not.toContain('vsphere:vm-5102')
    expect(items).toHaveLength(7)
    expect(errorOf(w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5102' }))).toBe('unmanaged.imported')
    expect(errorOf(w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-9999' }))).toBe('unmanaged.notFound')
    expect(w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5118', name: 'Pay GW' }).status).toBe(400)
    // named on the way in
    const named = body<{ wfrn: string }>(w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5133', name: 'jenkins-legacy' }))
    expect(body<ConsoleResourceDetail>(w.get(`/console/v1/r/vm/${enc(named.wfrn)}`)).name).toBe('jenkins-legacy')
  })

  it('assigns an object to another team’s account, shown in both lists until either imports it', () => {
    const w = world()
    w.call('POST', '/console/v1/discover', { type: 'vm' })
    w.tick(DISCOVERY_MS)
    expect(errorOf(w.call('POST', '/console/v1/unmanaged:assign', { type: 'vm', providerId: 'vsphere:vm-5118', accountId: '999999999999' }))).toBe('unmanaged.unknownAccount')
    expect(errorOf(w.call('POST', '/console/v1/unmanaged:assign', { type: 'vm', providerId: 'vsphere:vm-5118', accountId: COMPUTE }))).toBe('unmanaged.unknownAccount')
    const res = w.call('POST', '/console/v1/unmanaged:assign', { type: 'vm', providerId: 'vsphere:vm-5118', accountId: DATA, note: 'Tagged for your team; yours to import or delete' })
    expect(res.status).toBe(200)
    expect(body<UnmanagedObject>(res).assignment).toMatchObject({ accountId: DATA, accountName: 'Data', fromAccountId: COMPUTE, by: 'dana', note: 'Tagged for your team; yours to import or delete', at: new Date(START + DISCOVERY_MS).toISOString() })
    expect(body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?limit=200')).items.find((o) => o.providerId === 'vsphere:vm-5118')?.assignment?.accountId).toBe(DATA)

    w.as('raj')
    const theirs = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged?owner=mine')).items
    expect(theirs.map((o) => o.providerId)).toEqual(['vsphere:vm-5118'])
    // the data account's platform role may not create machines, so it cannot import or reassign it
    expect(theirs[0].can).toEqual([])
    const refused = w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5118' })
    expect(body<{ denied: Denial }>(refused).denied).toMatchObject({ layer: 'platform', action: 'compute:create' })

    w.as('dana')
    expect(w.call('POST', '/console/v1/unmanaged:import', { type: 'vm', providerId: 'vsphere:vm-5118' }).status).toBe(201)
    w.as('raj')
    expect(body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged')).items).toEqual([])
  })

  it('lets the data account import the clusters its own run found', () => {
    const w = world('raj')
    w.call('POST', '/console/v1/discover', { type: 'database' })
    w.tick(DISCOVERY_MS)
    const items = body<Page<UnmanagedObject>>(w.get('/console/v1/unmanaged')).items
    expect(items.map((o) => o.name)).toEqual(['ledger-archive', 'test-cluster-old'])
    expect(items[0].ownerGuess).toMatchObject({ accountId: DATA, confidence: 'low' })
    const { wfrn } = body<{ wfrn: string }>(w.call('POST', '/console/v1/unmanaged:import', { type: 'database', providerId: 'mongodb:65f1a2c4d3' }))
    expect(wfrn).toBe(`wfrn:wf:data:global:${DATA}:database/database-65f1a2c4d3`)
    expect(body<ConsoleResourceDetail>(w.get(`/console/v1/r/database/${enc(wfrn)}`))).toMatchObject({ name: 'ledger-archive', state: 'active' })
  })

  it('guesses owners by tag, by name and by placement, in that order', () => {
    const data = buildMockData()
    expect(ownerGuessOf(data, { name: 'x', tags: { account: COMPUTE }, observed: {} })).toMatchObject({ accountId: COMPUTE, confidence: 'high', reasons: [`Tagged account=${COMPUTE}, the id of Compute`] })
    expect(ownerGuessOf(data, { name: 'data-sink', tags: { owner: 'team-compute' }, observed: {} })).toMatchObject({ accountId: COMPUTE, confidence: 'high' })
    expect(ownerGuessOf(data, { name: 'data-sink', observed: { folder: '/dc1/compute' } })).toMatchObject({ accountId: DATA, confidence: 'medium' })
    expect(ownerGuessOf(data, { name: 'sink', observed: { project: 'data-prod' } })).toMatchObject({ accountId: DATA, confidence: 'low' })
    expect(ownerGuessOf(data, { name: 'sink', observed: {} })).toBeUndefined()
  })
})
