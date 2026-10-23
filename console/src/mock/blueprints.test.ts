import { describe, expect, it } from 'vitest'
import type { Blueprint, Stack, StackEvent, StackPlan } from '../lib/blueprints'
import type { Page } from '../lib/types'
import { APPROVAL_MS, RUN_MS } from './commands'
import { buildMockData } from './data'
import { restoreConsole, saveConsole } from './install'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Blueprints and stacks in the mock (./blueprints): the catalog and a blueprint's waves, a plan
 * with its authorizations decided, a launch whose steps start as their dependencies finish, a
 * failed child that stops the stack and Resume that retries it, a teardown in reverse order that
 * needs each child's delete, and the journal replaying it all after a reload.
 */

const START = Date.parse('2026-10-23T10:00:00Z')
const enc = encodeURIComponent
const TAGS = { owner: 'team-compute', 'cost-centre': 'cc-1042', 'data-class': 'internal', environment: 'dev' }

function world(person = 'dana') {
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
  return { data, call, as, get: (url: string) => call('GET', url), tick: (ms: number) => (now += ms), now: () => now }
}

const body = <T>(res: { body?: unknown }) => res.body as T
const launch = (w: ReturnType<typeof world>, name: string, appName: string, extra: Record<string, unknown> = {}) => w.call('POST', '/console/v1/stacks', { blueprint: 'web-tier', version: '1.2.0', name, parameters: { appName, ...extra }, tags: TAGS })
const stackOf = (w: ReturnType<typeof world>, wfrn: string) => body<Stack>(w.get(`/console/v1/stacks/${enc(wfrn)}`))
const states = (s: Stack) => Object.fromEntries(s.steps.map((x) => [x.step, x.state]))

describe('the blueprint catalog', () => {
  it('lists the blueprints with how many of each type they launch, and draws one with its waves', () => {
    const w = world()
    const list = body<Page<{ name: string; resources: Record<string, number> }>>(w.get('/console/v1/blueprints'))
    expect(list.items.map((b) => b.name)).toEqual(['web-tier', 'web-app', 'dev-sandbox'])
    expect(list.items[0].resources).toEqual({ image: 1, vm: 1, volume: 1 })
    const bp = body<Blueprint>(w.get('/console/v1/blueprints/web-tier'))
    expect(bp.waves).toEqual([['image'], ['web'], ['data']])
    expect(bp.resources.web.dependsOn).toEqual(['image'])
    expect(bp.parameters.appName.required).toBe(true)
    expect(bp.parameters.size.required).toBe(false)
    expect(body<Blueprint>(w.get('/console/v1/blueprints/web-app')).waves).toEqual([['image', 'database'], ['web'], ['data']])
  })

  it('plans a launch with the parameters checked and every authorization decided', () => {
    const w = world()
    const bad = w.call('POST', '/console/v1/blueprints/web-tier:plan', { name: 'X', parameters: { appName: 'Shop', dataGb: 5 } })
    expect(bad.status).toBe(400)
    expect(body<{ problems: { field: string }[] }>(bad).problems.map((p) => p.field)).toEqual(['/name', '/parameters/appName', '/parameters/dataGb'])
    const plan = body<StackPlan>(w.call('POST', '/console/v1/blueprints/web-tier:plan', { name: 'shop', parameters: { appName: 'shop' } }))
    expect(plan.parameters).toMatchObject({ appName: 'shop', size: 'small', dataGb: 100 })
    expect(plan.steps.find((s) => s.step === 'web')).toMatchObject({ wave: 1, input: { name: 'shop-web', image: '${resources.image.registryId}', size: 'small' } })
    expect(plan.authorize.every((a) => a.allowed)).toBe(true)
    // the Compute account's platform role may not create databases: web-app's plan says so, and its launch is refused
    const app = body<StackPlan>(w.call('POST', '/console/v1/blueprints/web-app:plan', { name: 'shop', parameters: { appName: 'shop' } }))
    expect(app.authorize.find((a) => a.action === 'data:create')).toMatchObject({ allowed: false, denied: { layer: 'platform' } })
    expect(w.call('POST', '/console/v1/stacks', { blueprint: 'web-app', name: 'shop', parameters: { appName: 'shop' }, tags: TAGS }).status).toBe(403)
  })

  it('refuses a viewer’s launch, a missing tag, a taken name and an old version', () => {
    const w = world('mia')
    expect(launch(w, 'shop', 'shop').status).toBe(403)
    w.as('dana')
    expect(w.call('POST', '/console/v1/stacks', { blueprint: 'web-tier', name: 'shop', parameters: { appName: 'shop' } }).status).toBe(400)
    expect(w.call('POST', '/console/v1/stacks', { blueprint: 'web-tier', version: '1.0.0', name: 'shop', parameters: { appName: 'shop' }, tags: TAGS }).status).toBe(409)
    expect(launch(w, 'shop', 'shop').status).toBe(201)
    expect(body<{ error: string }>(launch(w, 'shop', 'shop2')).error).toBe('stack.nameTaken')
  })
})

describe('a stack', () => {
  it('launches wave by wave, each child built from the one before, and is active with its outputs', () => {
    const w = world()
    const res = launch(w, 'shop', 'shop')
    expect(res.status).toBe(201)
    const stack = body<Stack>(res)
    expect(stack.wfrn).toMatch(/^wfrn:wf:blueprints:global:123456789012:stack\/stack-/)
    expect(states(stack)).toEqual({ image: 'running', web: 'waiting', data: 'waiting' })
    w.tick(RUN_MS + 1)
    let s = stackOf(w, stack.wfrn)
    expect(states(s)).toEqual({ image: 'done', web: 'running', data: 'waiting' })
    const web = s.steps.find((x) => x.step === 'web')!
    expect(web).toMatchObject({ name: 'shop-web', jobId: expect.stringMatching(/^job_/) })
    w.tick(RUN_MS)
    w.tick(RUN_MS)
    s = stackOf(w, stack.wfrn)
    expect(s.state).toBe('active')
    expect(s.outputs?.server.value).toBe(web.wfrn)
    expect(s.outputs?.image.value).toBe('shop-image')
    // the volume was built from the server, and the server from the image
    const vm = w.data.resources.find((r) => r.name === 'shop-web')!
    expect(vm.builtFrom).toEqual([w.data.resources.find((r) => r.name === 'shop-image')!.id])
    expect(w.data.console?.tags[web.wfrn!]).toMatchObject({ ...TAGS, app: 'shop', stack: 'shop' })
    const log = body<Page<StackEvent>>(w.get(`/console/v1/stacks/${enc(stack.wfrn)}/events`)).items.map((e) => e.type)
    expect(log[0]).toBe('StackRequested')
    expect(log.at(-1)).toBe('StackLaunched')
    expect(body<Page<Stack>>(w.get('/console/v1/stacks')).items.map((x) => x.name)).toEqual(['shop'])
  })

  it('stops on a failed child and goes on when Resume retries it', () => {
    const w = world()
    const stack = body<Stack>(launch(w, 'failing', 'fail-shop'))
    w.tick(RUN_MS + 1)
    let s = stackOf(w, stack.wfrn)
    expect(s.state).toBe('failed')
    expect(s.failure?.step).toBe('image')
    expect(states(s)).toEqual({ image: 'failed', web: 'waiting', data: 'waiting' })
    expect(s.can).toContain('resume')
    expect(w.call('POST', `/console/v1/stacks/${enc(stack.wfrn)}:resume`, {}).status).toBe(200)
    w.tick(RUN_MS + 1)
    s = stackOf(w, stack.wfrn)
    expect(states(s)).toMatchObject({ image: 'done', web: 'running' })
  })

  it('tears down in reverse order with each child’s delete, keeping the image', () => {
    const w = world()
    const stack = body<Stack>(launch(w, 'shop', 'shop'))
    for (let i = 0; i < 3; i++) w.tick(RUN_MS + 1)
    expect(stackOf(w, stack.wfrn).state).toBe('active')
    // Dana may not delete machines: the teardown is refused, and not offered
    expect(stackOf(w, stack.wfrn).can).not.toContain('teardown')
    expect(w.call('POST', `/console/v1/stacks/${enc(stack.wfrn)}:teardown`, { reason: 'the demo is over' }).status).toBe(403)
    w.as('gonzalo')
    expect(w.call('POST', `/console/v1/stacks/${enc(stack.wfrn)}:teardown`, { reason: 'short' }).status).toBe(400)
    const torn = w.call('POST', `/console/v1/stacks/${enc(stack.wfrn)}:teardown`, { reason: 'the demo is over' })
    expect(torn.status).toBe(200)
    expect(states(body<Stack>(torn))).toEqual({ image: 'retained', web: 'done', data: 'deleting' })
    w.tick(APPROVAL_MS + RUN_MS + 1)
    expect(states(stackOf(w, stack.wfrn))).toEqual({ image: 'retained', web: 'deleting', data: 'deleted' })
    w.tick(APPROVAL_MS + RUN_MS + 1)
    const s = stackOf(w, stack.wfrn)
    expect(s.state).toBe('deleted')
    expect(s.teardownReason).toBe('the demo is over')
    expect(w.data.resources.find((r) => r.name === 'shop-web')?.status).toBe('retired')
    expect(w.data.resources.find((r) => r.name === 'shop-image')?.status).not.toBe('retired')
    // the name is free again once the stack is torn down
    expect(launch(w, 'shop', 'shop').status).toBe(201)
  })

  it('replays the launch and the teardown after a reload, the same children at the same moments', () => {
    const w = world('gonzalo')
    const stack = body<Stack>(launch(w, 'shop', 'shop'))
    for (let i = 0; i < 3; i++) w.tick(RUN_MS + 1)
    w.call('POST', `/console/v1/stacks/${enc(stack.wfrn)}:teardown`, { reason: 'the demo is over' })
    w.tick(APPROVAL_MS + RUN_MS + 1)
    const before = stackOf(w, stack.wfrn)
    const saved: Record<string, string> = {}
    saveConsole(w.data, { setItem: (k, v) => (saved[k] = v) })
    const fresh = buildMockData()
    restoreConsole(fresh, { getItem: (k) => saved[k] ?? null }, w.now())
    const again = createMockServer(fresh, memorySessionStore(), () => w.now())
    again.handle({ method: 'GET', url: '/auth/login?login_hint=gonzalo', headers: {} })
    const after = again.handle({ method: 'GET', url: `/console/v1/stacks/${enc(stack.wfrn)}`, headers: {} }).body as Stack
    expect(after.steps).toEqual(before.steps)
    expect(after.state).toBe(before.state)
  })
})
