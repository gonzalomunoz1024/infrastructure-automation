import { describe, expect, it } from 'vitest'
import type { Guardrail, OrgGuardrails, PolicySimulation } from '../lib/policies'
import type { IamGroup, IamPolicy, IamRole, IamStatement, Page } from '../lib/types'
import { buildMockData } from './data'
import { createMockServer, csrfTokenOf, memorySessionStore } from './server'

/*
 * Custom policies, roles with boundaries, guardrails and the simulator in the mock (phase 7):
 * the writes as the identity service guards and checks them, and the decisions they change,
 * which authorize (a verb on a resource) and the simulator answer from the same code.
 */

const ACCOUNT = '123456789012'
const vm = (id: string) => `wfrn:wf:compute:global:${ACCOUNT}:vm/${id}`
const VMS = `wfrn:wf:compute:*:${ACCOUNT}:vm/*`
const PROD = vm('vm-demo01')
const DEV = vm('vm-demo02')

const RESTARTS: IamStatement[] = [
  { sid: 'restart-prod', effect: 'allow', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': 'prod' } },
  { sid: 'restart-only-prod', effect: 'deny', actions: ['compute:restart'], resources: [VMS], conditions: { 'tag.environment': ['dev', 'test'] } },
  { sid: 'no-vm-deletes', effect: 'deny', actions: ['compute:delete'], resources: [VMS] },
]

/** One mock BFF, signed in as one person at a time, so a change by one is seen by the next. */
function world() {
  const data = buildMockData()
  const server = createMockServer(data, memorySessionStore(), () => Date.parse('2026-10-23T10:00:00Z'))
  let who = ''
  const as = (person: string) => {
    who = person
    server.handle({ method: 'GET', url: `/auth/login?login_hint=${person}`, headers: {} })
  }
  const call = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => server.handle({ method, url, headers: { 'x-csrf-token': csrfTokenOf(who), ...headers }, body })
  return { data, as, call }
}

const body = <T>(res: { body?: unknown }) => res.body as T

/** Gonzalo writes the prod-restarts policy and gives it to the operators' group through a new role. */
function withRestartPolicy() {
  const w = world()
  w.as('gonzalo')
  const created = w.call('POST', '/console/v1/iam/policies', { name: 'prod-restarts', description: 'Operators restart only prod VMs', statements: RESTARTS })
  expect(created.status).toBe(201)
  const policy = body<IamPolicy>(created)
  const role = body<IamRole>(w.call('POST', '/console/v1/iam/roles', { name: 'prod-restarter', policies: ['prod-restarts'] }))
  const members = body<Page<IamGroup>>(w.call('GET', '/console/v1/iam/groups')).items.find((g) => g.name === 'members')!
  expect(w.call('PATCH', `/console/v1/iam/groups/${members.groupId}`, { roles: [...(members.roles ?? []), role.roleId] }, { 'if-match': String(members.version) }).status).toBe(200)
  return { ...w, policy, role }
}

describe('custom policies', () => {
  it('creates a policy the identity service would take, and refuses one it would not, field by field', () => {
    const w = world()
    w.as('gonzalo')
    const bad = w.call('POST', '/console/v1/iam/policies', { name: 'Bad Name', statements: [{ effect: 'allow', actions: ['compute:restar', 'nope'], resources: ['vm/*'], notActions: [] }, { effect: 'deny', actions: ['compute:get'], resources: [VMS], notActions: ['compute:list'], conditions: { colour: 'red' } }] })
    expect(bad.status).toBe(400)
    expect(body<{ problems: { field: string; code: string }[] }>(bad).problems.map((p) => [p.field, p.code])).toEqual([
      ['name', 'invalid'],
      ['statements[0].actions[0]', 'unknown-action'],
      ['statements[0].actions[1]', 'invalid'],
      ['statements[0].resources[0]', 'invalid'],
      ['statements[1].notActions', 'invalid'],
      ['statements[1].conditions.colour', 'invalid'],
    ])
    const ok = w.call('POST', '/console/v1/iam/policies', { name: 'prod-restarts', statements: RESTARTS })
    expect(ok.status).toBe(201)
    expect(body<IamPolicy>(ok)).toMatchObject({ name: 'prod-restarts', version: 1, builtIn: false })
    expect(w.call('POST', '/console/v1/iam/policies', { name: 'prod-restarts', statements: RESTARTS }).status, 'a name in use').toBe(409)
  })

  it('replaces a document against the version read, and keeps the managed ones as they are', () => {
    const w = world()
    w.as('gonzalo')
    const p = body<IamPolicy>(w.call('POST', '/console/v1/iam/policies', { name: 'prod-restarts', statements: RESTARTS }))
    const changed = w.call('PUT', `/console/v1/iam/policies/${p.policyId}`, { name: 'prod-restarts', statements: RESTARTS.slice(0, 1) }, { 'if-match': '1' })
    expect(body<IamPolicy>(changed)).toMatchObject({ version: 2, statements: [{ sid: 'restart-prod' }] })
    expect(w.call('PUT', `/console/v1/iam/policies/${p.policyId}`, { name: 'prod-restarts', statements: RESTARTS }, { 'if-match': '1' }).status, 'it moved on').toBe(409)
    expect(w.call('PUT', '/console/v1/iam/policies/managed-operator', { name: 'managed-operator', statements: RESTARTS }).status).toBe(409)
  })

  it('refuses to delete a policy while a role holds it or is bounded by it, naming the roles', () => {
    const w = withRestartPolicy()
    const held = w.call('DELETE', `/console/v1/iam/policies/${w.policy.policyId}`)
    expect(held).toMatchObject({ status: 409, body: { error: 'the policy is held by prod-restarter', detail: ['prod-restarter'] } })
    expect(w.call('DELETE', '/console/v1/iam/policies/managed-viewer'), 'a managed policy stays').toMatchObject({ status: 409, body: { error: 'a managed policy cannot be deleted' } })
    const role = body<IamRole>(w.call('GET', `/console/v1/iam/roles/${w.role.roleId}`))
    w.call('PATCH', `/console/v1/iam/roles/${w.role.roleId}`, { policies: [] }, { 'if-match': String(role.version) })
    expect(w.call('DELETE', `/console/v1/iam/policies/${w.policy.policyId}`).status).toBe(204)
  })

  it('refuses Mia, a viewer, every write, with the statement that decided', () => {
    const w = world()
    w.as('mia')
    const refused = w.call('POST', '/console/v1/iam/policies', { name: 'mine', statements: RESTARTS })
    expect(refused).toMatchObject({ status: 403, body: { denied: { layer: 'console-iam', action: 'iam:create', role: 'viewer' } } })
    expect(w.call('PATCH', '/console/v1/iam/roles/operator', { boundary: 'managed-viewer' }).status).toBe(403)
    expect(w.call('GET', '/console/v1/iam/policies').status, 'reading is a viewer’s').toBe(200)
  })
})

describe('the decisions a custom policy changes', () => {
  it('lets Dana restart a prod VM, refuses her a dev one and a delete, each with the statement', () => {
    const w = withRestartPolicy()
    w.as('dana')
    const reason = { input: { reason: 'stuck' } }
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(PROD)}:restart`, reason).status).toBe(202)
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(DEV)}:restart`, reason)).toMatchObject({ status: 403, body: { denied: { statement: 'restart-only-prod', role: 'prod-restarter', refusedBy: 'policy' } } })
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(DEV)}:delete`, { confirm: true })).toMatchObject({ status: 403, body: { denied: { statement: 'no-vm-deletes', policyId: w.policy.policyId } } })
    // the type's delete is refused by the same statement before any call; its restart stays allowed (no tags on a pattern)
    const vmType = body<{ actions: { verb: string; allowed: boolean; denied?: { statement?: string } }[] }[]>(w.call('GET', '/console/v1/types')).find((t) => (t as { type?: string }).type === 'vm')!
    expect(vmType.actions.find((a) => a.verb === 'delete')).toMatchObject({ allowed: false, denied: { statement: 'no-vm-deletes' } })
    expect(vmType.actions.find((a) => a.verb === 'restart')).toMatchObject({ allowed: true })
  })
})

describe('the simulator', () => {
  it('answers from the same code as authorize: per check the decision, the statement and the part that decided', () => {
    const w = withRestartPolicy()
    const sim = body<PolicySimulation>(
      w.call('POST', '/console/v1/iam/policy-simulations', {
        principal: { userId: 'dana' },
        checks: [
          { action: 'compute:restart', resource: PROD, context: { tags: { environment: 'prod' } } },
          { action: 'compute:restart', resource: DEV, context: { tags: { environment: 'dev' } } },
          { action: 'compute:delete', resource: DEV },
          { action: 'compute:create', resource: `wfrn:wf:compute:us-east-1:${ACCOUNT}:vm/*` },
          { action: 'iam:create', resource: `wfrn:wf:iam::${ACCOUNT}:policy/*` },
        ],
      }),
    )
    expect(sim.results.map((r) => [r.allowed, r.scope ?? null, r.statement ?? null])).toEqual([
      [true, 'policy', 'changes'],
      [false, 'policy', 'restart-only-prod'],
      [false, 'policy', 'no-vm-deletes'],
      [false, 'guardrail', 'no-creates-elsewhere'],
      [false, null, null],
    ])
    expect(sim.results[3]).toMatchObject({ guardrailId: 'gr_01jb0a1b2c3d4e5f6g7h8j9k0m', requestable: false })
    expect(sim.results[4]).toMatchObject({ requestable: true, suggestedRole: 'account-admin' })
  })

  it('lays an unsaved policy over a person and a draft boundary over a role, and writes nothing', () => {
    const w = world()
    w.as('gonzalo')
    // the guardrails are seeded on first use; after that a simulation changes nothing
    w.call('GET', '/console/v1/iam/guardrails')
    const before = JSON.stringify(w.data.directory)
    const sim = body<PolicySimulation>(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { userId: 'dana' }, policies: [{ name: 'draft', statements: RESTARTS }], checks: [{ action: 'compute:delete', resource: PROD }] }))
    expect(sim.results[0]).toMatchObject({ allowed: false, policyId: 'simulated:policy-0', statement: 'no-vm-deletes', role: 'simulated:draft' })
    const bounded = body<PolicySimulation>(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { role: 'operator' }, boundary: { name: 'reads', statements: [{ effect: 'allow', actions: ['*:get', '*:list'], resources: ['wfrn:wf:*'] }] }, checks: [{ action: 'compute:restart', resource: PROD }, { action: 'compute:get', resource: PROD }] }))
    expect(bounded.results[0]).toMatchObject({ allowed: false, scope: 'boundary', policyId: 'simulated:boundary', role: 'operator', requestable: false, reason: 'outside the permission boundary reads of role operator' })
    expect(bounded.results[1]).toMatchObject({ allowed: true })
    expect(JSON.stringify(w.data.directory)).toBe(before)
    expect(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { role: 'account-admin' }, boundary: { statements: RESTARTS }, checks: [{ action: 'compute:get', resource: PROD }] }).status).toBe(409)
    expect(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { userId: 'dana', role: 'operator' }, checks: [] })).toMatchObject({ status: 400, body: { problems: [{ field: 'principal' }, { field: 'checks' }] } })
  })

  it('shows a saved boundary deciding, and that it bounds only that role', () => {
    const w = withRestartPolicy()
    const role = body<IamRole>(w.call('GET', `/console/v1/iam/roles/${w.role.roleId}`))
    expect(body<IamRole>(w.call('PATCH', `/console/v1/iam/roles/${w.role.roleId}`, { boundary: 'managed-viewer' }, { 'if-match': String(role.version) }))).toMatchObject({ boundary: 'managed-viewer', version: role.version + 1 })
    const sim = body<PolicySimulation>(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { role: 'prod-restarter' }, checks: [{ action: 'compute:restart', resource: PROD, context: { tags: { environment: 'prod' } } }] }))
    expect(sim.results[0]).toMatchObject({ allowed: false, scope: 'boundary', policyId: 'managed-viewer' })
    // Dana still restarts it: her operator role is not bounded
    w.as('dana')
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(PROD)}:restart`, { input: { reason: 'stuck' } }).status).toBe(202)
    w.as('gonzalo')
    expect(w.call('PATCH', '/console/v1/iam/roles/account-admin', { boundary: 'managed-viewer' }).status).toBe(409)
  })
})

describe('guardrails', () => {
  it('lists the account’s to anyone who reads IAM, and the organization’s to its admins alone', () => {
    const w = world()
    w.as('mia')
    expect(body<{ items: Guardrail[] }>(w.call('GET', '/console/v1/iam/guardrails')).items.map((g) => g.name)).toEqual(['approved-regions', 'respect-legal-holds'])
    expect(w.call('GET', '/console/v1/org/guardrails')).toMatchObject({ status: 403, body: { denied: { requestable: false } } })
    w.as('gonzalo')
    expect(body<OrgGuardrails>(w.call('GET', '/console/v1/org/guardrails')).can).toEqual(['create', 'update', 'delete'])
  })

  it('an organization admin’s guardrail refuses what a role allows, for the accounts it names', () => {
    const w = world()
    w.as('gonzalo')
    const bad = w.call('POST', '/console/v1/org/guardrails', { name: 'no-restarts', statements: [{ effect: 'deny', actions: ['compute:restart'], resources: ['wfrn:wf:*'] }], accounts: ['999999999999'] })
    expect(bad).toMatchObject({ status: 400, body: { problems: [{ field: 'accounts[0]' }] } })
    const g = body<Guardrail>(w.call('POST', '/console/v1/org/guardrails', { name: 'no-restarts', statements: [{ sid: 'freeze', effect: 'deny', actions: ['compute:restart'], resources: ['wfrn:wf:*'] }], accounts: [ACCOUNT] }))
    w.as('dana')
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(PROD)}:restart`, { input: { reason: 'stuck' } })).toMatchObject({ status: 403, body: { denied: { refusedBy: 'guardrail', guardrailId: g.guardrailId, requestable: false, statement: 'freeze' } } })
    w.as('gonzalo')
    expect(w.call('PATCH', `/console/v1/org/guardrails/${g.guardrailId}`, { accounts: ['210987654321'] }, { 'if-match': '1' }).status).toBe(200)
    w.as('dana')
    expect(w.call('POST', `/console/v1/r/vm/${encodeURIComponent(PROD)}:restart`, { input: { reason: 'stuck' } }).status).toBe(202)
  })

  it('holds an account to the allow-list once a guardrail has allow statements', () => {
    const w = world()
    w.as('gonzalo')
    w.call('POST', '/console/v1/org/guardrails', { name: 'reads-only', statements: [{ effect: 'allow', actions: ['*:get', '*:list'], resources: ['wfrn:wf:*'] }], accounts: ['*'] })
    const sim = body<PolicySimulation>(w.call('POST', '/console/v1/iam/policy-simulations', { principal: { userId: 'gonzalo' }, checks: [{ action: 'compute:restart', resource: PROD }, { action: 'compute:get', resource: PROD }] }))
    expect(sim.results.map((r) => [r.allowed, r.reason])).toEqual([
      [false, 'outside the organization’s guardrails'],
      [true, 'allowed by role account-admin'],
    ])
  })
})
