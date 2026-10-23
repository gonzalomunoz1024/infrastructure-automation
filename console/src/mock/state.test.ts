import { describe, expect, it } from 'vitest'
import { checkChain, checkPack, checkPackHashes, eventHash, type ChainCheck, type EvidencePack, type TrailDigest, type TrailEntry, type TrailKey } from '../lib/trail'
import { replayAt } from '../lib/timeTravel'
import type { ResourceGraph, RevertPlan, StateLineage } from '../lib/state'
import type { ActivityEntry, CommandAccepted, ConsoleResource, ConsoleResourceDetail, Page } from '../lib/types'
import { buildMockData } from './data'
import { RUN_MS } from './commands'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'
import { REPLAY_SAMPLE, TAMPERED_SAMPLE, logOf } from './state'

/*
 * The state service's trail, time travel, lineage and map in the mock: every resource's log is a
 * real SHA-256 chain, the days are sealed and signed, one seeded event was changed after it was
 * written and the checks find it there and nowhere else, `?at=` and the revert plan replay the
 * log, and the lineage and map walk the dataset's builtFrom links within what the person may see.
 */

const NOW = Date.parse('2026-10-23T10:00:00Z')
const DAY = 24 * 3600_000
const enc = encodeURIComponent
const BATCH = `wfrn:wf:compute:global:123456789012:vm/${REPLAY_SAMPLE}`
const TAMPERED = `wfrn:wf:compute:global:123456789012:vm/${TAMPERED_SAMPLE}`
const VM = 'wfrn:wf:compute:global:123456789012:vm/vm-demo01'

function bff(person: string, start = NOW) {
  let now = start
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => now)
  server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(person), ...headers }, body })
  return { data, call, get: (url: string) => call('GET', url), tick: (ms: number) => (now += ms), now: () => now }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const iso = (ms: number) => new Date(ms).toISOString()

describe('the activity trail', () => {
  it('lists the account’s events newest first, each linked to the one before in its resource’s chain', () => {
    const gonzalo = bff('gonzalo')
    const page = body<Page<TrailEntry>>(gonzalo.get('/console/v1/activity?limit=200'))
    expect(page.items.length).toBe(200)
    expect(page.next).toBeTruthy()
    const times = page.items.map((e) => e.at)
    expect([...times].sort().reverse()).toEqual(times)
    for (const e of page.items) {
      expect(e.context.accountId).toBe('123456789012')
      expect(e.chain.hash).toMatch(/^sha256:[0-9a-f]{64}$/)
      expect(e.key).toBe(`${e.type}:${e.aggregateId}:${e.data.version}`)
    }
    // the hash is the SHA-256 of the stored form: the envelope with `previous`
    const log = logOf(gonzalo.data, VM, NOW)
    expect(log[0].data.version).toBe(1)
    expect(log[0].previous).toBeUndefined()
    expect(log[1].previous).toBe(log[0].hash)
    expect(eventHash(log[1])).toBe(log[1].hash)
  })

  it('filters by person, event type, outcome, resource and time, as the state service does', () => {
    const gonzalo = bff('gonzalo')
    const all = (q: string) => body<Page<TrailEntry>>(gonzalo.get(`/console/v1/activity?limit=200&${q}`)).items
    const mateo = all('actor=mateo')
    expect(mateo.length).toBeGreaterThan(0)
    expect(mateo.every((e) => e.actor === 'mateo' && e.onBehalfOf?.name === 'Mateo Silva')).toBe(true)
    expect(all('type=UpdateRequested').every((e) => e.type === 'UpdateRequested')).toBe(true)
    expect(all('outcome=succeeded').every((e) => ['ProvisioningSucceeded', 'Deleted', 'Imported', 'Transferred', 'DriftResolved'].includes(e.type))).toBe(true)
    const batch = all(`wfrn=${enc(BATCH)}`)
    expect(batch.map((e) => e.type)).toContain('UpdateRequested')
    expect(batch.every((e) => e.aggregateId === BATCH)).toBe(true)
    expect(all(`wfrn=${enc('wfrn:*:*:*:*:image/*')}`).every((e) => e.data.resourceType === 'image')).toBe(true)
    const yesterday = all(`from=${iso(NOW - DAY)}&to=${iso(NOW)}`)
    expect(yesterday.every((e) => Date.parse(e.at) >= NOW - DAY && Date.parse(e.at) < NOW)).toBe(true)
    expect(gonzalo.get('/console/v1/activity?type=Nothing').status).toBe(400)
    expect(gonzalo.get('/console/v1/activity?from=yesterday').status).toBe(400)
  })

  it('keeps another account’s events out, and a viewer reads the trail but not the evidence pack', () => {
    const mia = bff('mia')
    const items = body<Page<TrailEntry>>(mia.get('/console/v1/activity?limit=200')).items
    expect(items.length).toBeGreaterThan(0)
    expect(items.every((e) => e.context.accountId === '123456789012')).toBe(true)
    const refused = mia.get(`/console/v1/activity/evidence?from=${iso(NOW - 7 * DAY)}&to=${iso(NOW)}`)
    expect(refused.status).toBe(403)
    expect(body<{ denied: { action: string; suggestedRole: string } }>(refused).denied).toMatchObject({ action: 'activity:evidence', suggestedRole: 'auditor' })
    const dana = bff('dana')
    expect(dana.get(`/console/v1/activity/evidence?from=${iso(NOW - 7 * DAY)}&to=${iso(NOW)}`).status).toBe(403)
  })
})

describe('the seals', () => {
  it('seals each ended day in a digest chained to the day before, signed by keys that stay readable', async () => {
    const gonzalo = bff('gonzalo')
    const digests = body<Page<TrailDigest>>(gonzalo.get('/console/v1/activity/digests')).items
    expect(digests.length).toBeGreaterThan(20)
    for (let i = 1; i < digests.length; i++) expect(digests[i].previous).toBe(digests[i - 1].hash)
    // today has not ended, so it is not sealed yet
    expect(digests.every((d) => Date.parse(d.to) <= NOW)).toBe(true)
    const { keys } = body<{ keys: TrailKey[] }>(gonzalo.get('/console/v1/activity/keys'))
    expect(keys.map((k) => k.kid)).toEqual(['trail-2026-09', 'trail-2026-10'])
    expect(new Set(digests.map((d) => d.keyId))).toEqual(new Set(['trail-2026-09', 'trail-2026-10']))
  })

  it('packs a week’s evidence that holds when checked away from the console, signatures included', async () => {
    const gonzalo = bff('gonzalo')
    const res = gonzalo.get(`/console/v1/activity/evidence?from=${iso(NOW - 7 * DAY)}&to=${iso(NOW)}`)
    expect(res.status).toBe(200)
    const pack = body<EvidencePack>(res)
    expect(pack.from).toBe('2026-10-16T00:00:00.000Z')
    expect(pack.to).toBe('2026-10-24T00:00:00.000Z')
    expect(pack.verification).toMatchObject({ valid: true, problems: [] })
    expect(pack.verification.unsealed).toBeGreaterThan(0)
    expect(pack.jobs.length).toBeGreaterThan(0)
    expect(pack.jobs[0].evidence).toBe(`/api/v1/audit/${pack.jobs[0].jobId}`)
    const checked = await checkPack(pack)
    expect(checked.problems).toEqual([])
    expect(checked.signatures.checked).toBe(pack.digests.length)
    expect(checked.signatures.valid).toBe(pack.digests.length)
  })

  it('finds the seeded break in a month’s pack: the tampered sample’s resize, and nothing else', async () => {
    const gonzalo = bff('gonzalo')
    const pack = body<EvidencePack>(gonzalo.get(`/console/v1/activity/evidence?from=${iso(NOW - 30 * DAY)}&to=${iso(NOW)}`))
    expect(pack.verification.valid).toBe(false)
    const checked = await checkPack(pack)
    expect(checked.problems).toHaveLength(1)
    expect(checked.problems[0]).toMatchObject({ kind: 'event.altered', wfrn: TAMPERED, type: 'UpdateRequested' })
    expect(pack.verification.problems).toEqual([{ subject: checked.problems[0].subject, reason: expect.stringContaining('changed after it was recorded') }])
    // a changed seal is caught too, and so is a signature that does not match
    const resealed = { ...pack, digests: pack.digests.map((d, i) => (i === 2 ? { ...d, count: d.count + 1 } : d)) }
    expect(checkPackHashes(resealed).problems.map((p) => p.kind)).toEqual(expect.arrayContaining(['digest.count', 'digest.altered']))
    const forged = { ...pack, digests: pack.digests.map((d, i) => (i === 0 ? { ...d, signature: pack.digests[1].signature } : d)) }
    expect((await checkPack(forged)).problems.map((p) => p.kind)).toContain('digest.signature')
    expect(gonzalo.get(`/console/v1/activity/evidence?from=${iso(NOW - 40 * DAY)}&to=${iso(NOW)}`).status).toBe(400)
  })

  it('checks one resource’s chain: the tampered sample breaks at its resize, another holds', () => {
    const dana = bff('dana')
    const broken = body<ChainCheck>(dana.get(`/console/v1/r/vm/${enc(TAMPERED)}/activity:verify`))
    expect(broken.valid).toBe(false)
    const resize = logOf(dana.data, TAMPERED, NOW).find((e) => e.type === 'UpdateRequested')!
    expect(broken.brokenAt).toBe(resize.data.version)
    expect(broken.reason).toBe('its stored form does not match its hash')
    const holds = body<ChainCheck>(dana.get(`/console/v1/r/vm/${enc(BATCH)}/activity:verify`))
    expect(holds).toMatchObject({ valid: true, brokenAt: null })
    expect(holds.chained).toBe(holds.entries)
    expect(checkChain(logOf(dana.data, BATCH, NOW))).toEqual(holds)
  })
})

describe('time travel', () => {
  it('replays the replay sample: bigger yesterday, back to its size today', () => {
    const dana = bff('dana')
    const yesterday = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(BATCH)}?at=${iso(NOW - DAY)}`))
    expect(yesterday.attributes).toMatchObject({ cpu: 8, memoryGb: 16 })
    expect(yesterday.can).toEqual([])
    const today = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(BATCH)}`))
    expect(today.attributes).toMatchObject({ cpu: 2, memoryGb: 8 })
    const born = replayAt(logOf(dana.data, BATCH, NOW), Date.parse('2026-10-01T00:00:00Z'))
    expect(born?.attributes).toMatchObject({ cpu: 4, memoryGb: 16 })
    expect(dana.get(`/console/v1/r/vm/${enc(BATCH)}?at=2026-01-01T00:00:00Z`).status).toBe(404)
  })

  it('plans the way back as a merge patch, and says why when there is none', () => {
    const dana = bff('dana')
    const plan = body<RevertPlan>(dana.get(`/console/v1/r/vm/${enc(BATCH)}/revert-plan?to=${iso(NOW - DAY)}`))
    expect(plan.possible).toBe(true)
    expect(plan.changes).toEqual({ cpu: 8, memoryGb: 16 })
    expect(plan.fields).toEqual([
      { path: '/cpu', now: 2, then: 8 },
      { path: '/memoryGb', now: 8, then: 16 },
    ])
    expect(body<RevertPlan>(dana.get(`/console/v1/r/vm/${enc(BATCH)}/revert-plan?to=${iso(NOW - 60_000)}`))).toMatchObject({ possible: false, reason: 'resource.unchanged' })
    expect(body<RevertPlan>(dana.get(`/console/v1/r/vm/${enc(BATCH)}/revert-plan?to=2026-01-01T00:00:00Z`))).toMatchObject({ possible: false, reason: 'resource.notYet' })
    expect(dana.get(`/console/v1/r/vm/${enc(BATCH)}/revert-plan`).status).toBe(400)
  })

  it('records the revert through the update workflow, and the trail and replay follow it', () => {
    const dana = bff('dana')
    const accepted = body<CommandAccepted>(dana.call('PATCH', `/console/v1/r/vm/${enc(BATCH)}`, { input: { cpu: 8, memoryGb: 16 }, reason: 'Back to yesterday' }, { 'idempotency-key': 'revert-1' }))
    expect(accepted.jobId).toBeTruthy()
    dana.tick(RUN_MS + 1000)
    const activity = body<Page<ActivityEntry>>(dana.get(`/console/v1/r/vm/${enc(BATCH)}/activity`)).items
    expect(activity[0]).toMatchObject({ kind: 'event', type: 'ProvisioningSucceeded', actor: 'dana' })
    expect(activity.find((e) => e.type === 'UpdateRequested')).toMatchObject({ actor: 'dana', data: { changes: { cpu: 8, memoryGb: 16 }, jobId: accepted.jobId } })
    const now = body<ConsoleResourceDetail>(dana.get(`/console/v1/r/vm/${enc(BATCH)}?at=${iso(dana.now())}`))
    expect(now.attributes).toMatchObject({ cpu: 8, memoryGb: 16 })
    expect(checkChain(logOf(dana.data, BATCH, dana.now())).valid).toBe(true)
  })

  it('lists a type as it was at an instant, in wfrn order, and refuses a sort with it', () => {
    const dana = bff('dana')
    const then = body<Page<ConsoleResource>>(dana.get(`/console/v1/r/vm?at=2026-09-29T12:00:00Z&limit=200`)).items
    expect(then.map((r) => r.name)).toEqual(expect.arrayContaining(['web-01', 'api-01']))
    expect(then.map((r) => r.name)).not.toContain('web-02')
    expect(then.map((r) => r.wfrn)).toEqual([...then.map((r) => r.wfrn)].sort())
    expect(dana.get('/console/v1/r/vm?at=2026-09-29T12:00:00Z&sort=name').status).toBe(400)
  })
})

describe('lineage and the map', () => {
  it('walks what a machine was built from and what was built from it, counting another account’s', () => {
    const dana = bff('dana')
    const lineage = body<StateLineage>(dana.get(`/console/v1/r/vm/${enc(VM)}/lineage`))
    expect(lineage.resource).toMatchObject({ wfrn: VM, name: 'web-01', accountId: '123456789012' })
    expect(lineage.builtFrom.map((r) => r.name)).toEqual(['ubuntu-24.04-cis-l1-2026-10-01'])
    expect(lineage.dependents.map((r) => r.name)).toEqual(expect.arrayContaining(['volume-demo01', 'shop-prod']))
    expect(lineage.edges).toEqual(expect.arrayContaining([expect.objectContaining({ to: VM, via: 'create-vm' })]))
    expect(lineage.hidden).toBe(0)
    // the environment was built from the data account's databases too: counted, never named
    const env = body<StateLineage>(dana.get(`/console/v1/r/environment/${enc('wfrn:wf:platform:global:123456789012:environment/environment-demo01')}/lineage`))
    expect(env.hidden).toBe(2)
    expect(env.builtFrom.map((r) => r.name).sort()).toEqual(['api-01', 'web-01'])
    expect(env.nodes.every((n) => n.accountId === '123456789012')).toBe(true)
  })

  it('maps the account, colours what it can, and sums up the other account behind its links', () => {
    const dana = bff('dana')
    const map = body<ResourceGraph>(dana.get('/console/v1/map'))
    expect(map.nodes.length).toBeGreaterThan(200)
    expect(map.nodes.every((n) => n.accountId === '123456789012' && n.state !== 'deleted')).toBe(true)
    expect(map.external).toEqual(expect.arrayContaining([{ accountId: '210987654321', type: 'database', direction: 'builtFrom', count: 2 }]))
    const wfrns = new Set(map.nodes.map((n) => n.wfrn))
    expect(map.edges.every((e) => wfrns.has(e.from) && wfrns.has(e.to))).toBe(true)
    expect(map.nodes.find((n) => n.wfrn === VM)).toMatchObject({ cost: { monthly: 77, currency: 'USD' } })
    // only vms: the image they were built from drawn for context
    const vms = body<ResourceGraph>(dana.get('/console/v1/map?type=vm'))
    expect(vms.nodes.filter((n) => n.context).map((n) => n.type)).toEqual(['image'])
    // Gonzalo reads both accounts: the databases join the map, and nothing is external any more
    const both = body<ResourceGraph>(bff('gonzalo').get('/console/v1/map?accountIds=210987654321'))
    expect(new Set(both.nodes.map((n) => n.accountId))).toEqual(new Set(['123456789012', '210987654321']))
    expect(both.external).toEqual([])
  })
})
