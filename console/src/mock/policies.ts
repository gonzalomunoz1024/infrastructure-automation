import { composeTypes } from '../lib/descriptors'
import { actionCatalogOf, guardrailProblems, isAction, patternProblem, policyProblems, statementProblems, type ActionCatalog, type Guardrail, type GuardrailInput, type PolicySimulationRequest, type SimulationResult } from '../lib/policies'
import type { IamPolicy, IamStatement } from '../lib/types'
import type { MockData } from './data'
import { ADMIN_ROLE, DirectoryError, accountOf, authorize, decide, groupsOf, guardrailsFor, guardrailsOf, iamResource, personOf, policyIn, roleIn, rolesIn, userOf, type Decision, type Directory, type Overlay, type StoredRole, type Subject } from './directory'
import { isRecord, liveRowOf, pageOf, refused, type RouteAnswer, type RouteContext } from './commands'
import { ulidOf } from './tags'

/*
 * Custom policies, roles with their permission boundaries, the organization's guardrails and the
 * policy simulator, for mock mode, as the identity service answers them through the BFF:
 * `/console/v1/iam/policies` and `/roles` (create, replace against the version read, delete
 * refused while a role holds or is bounded by the policy, naming the roles),
 * `/iam/policy-simulations` (each check answered by the directory's own `decide`, the code
 * `authorize` runs, with the drafts laid over the account and nothing written),
 * `/iam/guardrails` (the account's) and `/org/guardrails` (the organization's, for its admins).
 * Every write is gated by `iam:<verb>` on the thing's IAM wfrn, as the identity service guards it,
 * so a viewer is refused with the statement that decided.
 */

/** The organization's admins in the demo: the BFF writes guardrails as the internal principal for them alone. */
export const ORG_ADMINS = ['gonzalo']

const MANAGED_ROLE_NAMES = new Set(['viewer', 'operator', ADMIN_ROLE, 'auditor'])

/** The actions that exist in the mock's platform, as the identity service's catalog builds them. */
export function mockActionCatalog(data: MockData): ActionCatalog {
  return actionCatalogOf(composeTypes(data.resourceTypes, data.catalog))
}

/** What a check on one resource knows about it: its tags, so `tag.<name>` conditions decide. The BFF sends them as the check's context. */
export function resourceContextOf(data: MockData, s: RouteContext['session'], wfrn: string): Record<string, unknown> | undefined {
  if (wfrn.includes('*') || wfrn.includes('?')) return undefined
  const r = data.resources.find((x) => x.wfrn === wfrn || x.id === wfrn.slice(wfrn.indexOf('/') + 1))
  if (!r) return undefined
  const row = liveRowOf(data, s, r, Date.now())
  return row.wfrn === wfrn ? { tags: row.tags } : undefined
}

const bump = (dir: Directory) => {
  dir.authzVersion = (dir.authzVersion ?? 1) + 1
}

function invalid(what: string, problems: ReturnType<typeof policyProblems>): RouteAnswer {
  return { status: 400, body: { error: `${what} is invalid`, problems } }
}

/** The holders a policy delete runs into: the roles that hold it or are bounded by it, by name. */
export function holdersOfPolicy(dir: Directory, accountId: string, policyId: string): string[] {
  return rolesIn(dir, accountId)
    .filter((r) => r.policies?.includes(policyId) || r.boundary === policyId)
    .map((r) => r.name)
}

/** A role as the identity service serves it: the account it belongs to stays hidden. */
const served = (r: StoredRole): StoredRole => {
  const role = { ...r }
  delete role.accountId
  return role
}

function statementsOf(v: unknown): IamStatement[] {
  return Array.isArray(v) ? (v as IamStatement[]) : []
}

function ifMatchOf(headers: Record<string, string>): number | undefined {
  const v = headers['if-match']
  if (!v) return undefined
  const n = Number(v.replace(/^W\//, '').replace(/"/g, ''))
  return Number.isFinite(n) ? n : undefined
}

/** A role's policies by id, from ids or names; an unknown one is a 400 naming it. */
function resolvePolicies(dir: Directory, accountId: string, names: unknown): string[] {
  if (!Array.isArray(names)) throw new DirectoryError(400, 'policies is a list of policy ids or names')
  return names.map((n) => {
    const p = typeof n === 'string' ? policyIn(dir, accountId, n) : undefined
    if (!p) throw new DirectoryError(400, `'${String(n)}' is no policy of this account`)
    return p.policyId
  })
}

export function policyRoutes(data: MockData, ctx: RouteContext, path: string, _params: URLSearchParams, method: string): RouteAnswer | null {
  const dir = data.directory
  const s = ctx.session
  const accountId = s.accountId
  const can = (action: string, resource: string): Decision => authorize(dir, { accountId, userId: s.userId }, action, resource)
  const correlation = () => `mock-${ulidOf(ctx.now, path).slice(-8).toLowerCase()}`
  const deny = (d: Decision) => refused(d, correlation())
  const body = ctx.body
  const ifMatch = ifMatchOf(ctx.headers)
  const now = new Date(ctx.now).toISOString()

  /* policies */
  if (path === '/console/v1/iam/policies') {
    if (method === 'GET') {
      const d = can('iam:list', iamResource(accountId, 'policy'))
      return d.allowed ? pageOf(dir.policies[accountId] ?? [], _params) : deny(d)
    }
    if (method === 'POST') {
      const d = can('iam:create', iamResource(accountId, 'policy'))
      if (!d.allowed) return deny(d)
      const problems = policyProblems(body, mockActionCatalog(data))
      if (problems.length) return invalid('the policy', problems)
      const name = String(body.name)
      if (policyIn(dir, accountId, name)) throw new DirectoryError(409, `a policy named ${name} exists`)
      const counter = (dir.counters.policies = (dir.counters.policies ?? 0) + 1)
      const policy: IamPolicy = { policyId: `pol_${ulidOf(ctx.now, `policy-${counter}`).toLowerCase()}`, name, ...(typeof body.description === 'string' && body.description ? { description: body.description } : {}), statements: statementsOf(body.statements), version: 1, builtIn: false, updatedAt: now }
      dir.policies[accountId] = [...(dir.policies[accountId] ?? []), policy]
      bump(dir)
      return { status: 201, body: policy, headers: { ETag: String(policy.version) } }
    }
    return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  }
  let m = /^\/console\/v1\/iam\/policies\/([^/:]+)$/.exec(path)
  if (m) {
    const id = decodeURIComponent(m[1])
    const p = policyIn(dir, accountId, id)
    const resource = iamResource(accountId, 'policy', p?.policyId ?? id)
    if (method === 'GET') {
      const d = can('iam:get', resource)
      if (!d.allowed) return deny(d)
      return p ? { status: 200, body: p, headers: { ETag: String(p.version) } } : { status: 404, body: { error: `no policy ${id}` } }
    }
    if (method === 'PUT') {
      const d = can('iam:update', resource)
      if (!d.allowed) return deny(d)
      if (!p) return { status: 404, body: { error: `no policy ${id}` } }
      if (ifMatch !== undefined && ifMatch !== p.version) throw new DirectoryError(409, 'the policy moved on; read it again', { version: p.version })
      if (p.builtIn) throw new DirectoryError(409, 'a managed policy cannot be changed')
      const problems = policyProblems(body, mockActionCatalog(data))
      if (problems.length) return invalid('the policy', problems)
      const name = String(body.name)
      const other = policyIn(dir, accountId, name)
      if (other && other.policyId !== p.policyId) throw new DirectoryError(409, `a policy named ${name} exists`)
      p.name = name
      p.description = typeof body.description === 'string' && body.description ? body.description : undefined
      p.statements = statementsOf(body.statements)
      p.version++
      p.updatedAt = now
      bump(dir)
      return { status: 200, body: p, headers: { ETag: String(p.version) } }
    }
    if (method === 'DELETE') {
      const d = can('iam:delete', resource)
      if (!d.allowed) return deny(d)
      if (!p) return { status: 404, body: { error: `no policy ${id}` } }
      if (p.builtIn) throw new DirectoryError(409, 'a managed policy cannot be deleted')
      const holders = holdersOfPolicy(dir, accountId, p.policyId)
      if (holders.length) throw new DirectoryError(409, `the policy is held by ${holders.join(', ')}`, holders)
      dir.policies[accountId] = (dir.policies[accountId] ?? []).filter((x) => x !== p)
      bump(dir)
      return { status: 204 }
    }
    return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  }

  /* roles, their policies and their permission boundaries */
  if (path === '/console/v1/iam/roles') {
    if (method === 'GET') {
      const d = can('iam:list', iamResource(accountId, 'role'))
      return d.allowed ? pageOf(rolesIn(dir, accountId).map(served), _params) : deny(d)
    }
    if (method === 'POST') {
      const d = can('iam:create', iamResource(accountId, 'role'))
      if (!d.allowed) return deny(d)
      const name = typeof body.name === 'string' ? body.name : ''
      if (!/^[a-z0-9-]{2,64}$/.test(name)) return invalid('the role', [{ level: 'error', code: 'invalid', field: 'name', message: 'name matches ^[a-z0-9-]{2,64}$' }])
      if (roleIn(dir, accountId, name)) throw new DirectoryError(409, `a role named ${name} exists`)
      const policies = resolvePolicies(dir, accountId, body.policies ?? [])
      const boundary = typeof body.boundary === 'string' && body.boundary ? policyIn(dir, accountId, body.boundary) : undefined
      if (typeof body.boundary === 'string' && body.boundary && !boundary) throw new DirectoryError(400, `'${body.boundary}' is no policy of this account`)
      const counter = (dir.counters.roles = (dir.counters.roles ?? 0) + 1)
      const role: StoredRole = { roleId: `role_${ulidOf(ctx.now, `role-${counter}`).toLowerCase()}`, name, ...(typeof body.description === 'string' && body.description ? { description: body.description } : {}), policies, ...(boundary ? { boundary: boundary.policyId } : {}), builtIn: false, version: 1, accountId }
      dir.roles = [...dir.roles, role]
      bump(dir)
      return { status: 201, body: served(role) }
    }
    return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  }
  m = /^\/console\/v1\/iam\/roles\/([^/:]+)$/.exec(path)
  if (m) {
    const id = decodeURIComponent(m[1])
    const r = roleIn(dir, accountId, id)
    const resource = iamResource(accountId, 'role', r?.roleId ?? id)
    if (method === 'GET') {
      const d = can('iam:get', resource)
      if (!d.allowed) return deny(d)
      return r ? { status: 200, body: served(r) } : { status: 404, body: { error: `no role ${id}` } }
    }
    if (method === 'PATCH') {
      const d = can('iam:update', resource)
      if (!d.allowed) return deny(d)
      if (!r) return { status: 404, body: { error: `no role ${id}` } }
      if (ifMatch !== undefined && ifMatch !== r.version) throw new DirectoryError(409, 'the role moved on; read it again', { version: r.version })
      const managed = MANAGED_ROLE_NAMES.has(r.name)
      if (body.name !== undefined && body.name !== r.name) {
        if (r.builtIn !== false) throw new DirectoryError(409, 'a managed role keeps its name')
        if (typeof body.name !== 'string' || !/^[a-z0-9-]{2,64}$/.test(body.name)) return invalid('the role', [{ level: 'error', code: 'invalid', field: 'name', message: 'name matches ^[a-z0-9-]{2,64}$' }])
        if (roleIn(dir, accountId, body.name)) throw new DirectoryError(409, `a role named ${body.name} exists`)
      }
      const policies = body.policies !== undefined ? resolvePolicies(dir, accountId, body.policies) : undefined
      if (policies && managed && policies.join() !== (r.policies ?? []).join()) throw new DirectoryError(409, 'a managed role keeps its policies')
      let boundary: string | undefined | null = null
      if (typeof body.boundary === 'string') {
        if (body.boundary === '') boundary = undefined
        else {
          if (r.name === ADMIN_ROLE) throw new DirectoryError(409, 'the account-admin role takes no permission boundary')
          const p = policyIn(dir, accountId, body.boundary)
          if (!p) throw new DirectoryError(400, `'${body.boundary}' is no policy of this account`)
          boundary = p.policyId
        }
      }
      if (typeof body.name === 'string') r.name = body.name
      if (typeof body.description === 'string') r.description = body.description || undefined
      if (policies) r.policies = policies
      if (boundary !== null) {
        if (boundary === undefined) delete r.boundary
        else r.boundary = boundary
      }
      r.version++
      bump(dir)
      return { status: 200, body: served(r) }
    }
    if (method === 'DELETE') {
      const d = can('iam:delete', resource)
      if (!d.allowed) return deny(d)
      if (!r) return { status: 404, body: { error: `no role ${id}` } }
      if (r.builtIn !== false || r.name === ADMIN_ROLE) throw new DirectoryError(409, 'a managed role cannot be deleted')
      const holders = (dir.groups[accountId] ?? []).filter((g) => g.roles?.includes(r.roleId) || g.roles?.includes(r.name)).map((g) => g.name)
      if (holders.length) throw new DirectoryError(409, `the role is held by ${holders.join(', ')}`, holders)
      dir.roles = dir.roles.filter((x) => x !== r)
      bump(dir)
      return { status: 204 }
    }
    return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
  }

  /* the policy simulator: nothing is written */
  if (path === '/console/v1/iam/policy-simulations') {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    return simulate(data, ctx, body as Partial<PolicySimulationRequest>, can, deny)
  }

  /* the guardrails over this account, and the organization's for its admins */
  if (path === '/console/v1/iam/guardrails' && method === 'GET') {
    const d = can('iam:list', iamResource(accountId, 'guardrail'))
    return d.allowed ? { status: 200, body: { items: guardrailsFor(dir, accountId) } } : deny(d)
  }
  if (path === '/console/v1/org/guardrails' || path.startsWith('/console/v1/org/guardrails/')) return orgGuardrailRoutes(data, ctx, path, method, ifMatch, deny)
  return null
}

function orgDenial(action: string): Decision {
  return { allowed: false, denied: { layer: 'console-iam', action, resource: `wfrn:wf:iam:::guardrail/*`, reason: 'Only an organization admin sees and changes the organization’s guardrails', requestable: false } }
}

function orgGuardrailRoutes(data: MockData, ctx: RouteContext, path: string, method: string, ifMatch: number | undefined, deny: (d: Decision) => RouteAnswer): RouteAnswer {
  const dir = data.directory
  const admin = ORG_ADMINS.includes(ctx.session.userId)
  const body = ctx.body
  const known = dir.accounts.map((a) => a.accountId)
  const now = new Date(ctx.now).toISOString()
  const all = guardrailsOf(dir)
  if (path === '/console/v1/org/guardrails') {
    if (method === 'GET') return admin ? { status: 200, body: { items: [...all].sort((a, b) => a.guardrailId.localeCompare(b.guardrailId)), next: null, can: ['create', 'update', 'delete'] } } : deny(orgDenial('iam:list'))
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    if (!admin) return deny(orgDenial('iam:create'))
    const input = body as GuardrailInput
    const problems = guardrailProblems(input, known, mockActionCatalog(data))
    if (problems.length) return invalid('the guardrail', problems)
    if (all.some((g) => g.name === input.name)) throw new DirectoryError(409, `a guardrail named ${input.name} exists in the organization`)
    const counter = (dir.counters.guardrails = (dir.counters.guardrails ?? 0) + 1)
    const g: Guardrail = { guardrailId: `gr_${ulidOf(ctx.now, `guardrail-${counter}`).toLowerCase()}`, name: input.name!, ...(input.description ? { description: input.description } : {}), statements: input.statements!, accounts: input.accounts!, version: 1, createdAt: now, updatedAt: now }
    dir.guardrails = [...all, g]
    bump(dir)
    return { status: 201, body: g }
  }
  const id = decodeURIComponent(path.slice('/console/v1/org/guardrails/'.length))
  const g = all.find((x) => x.guardrailId === id)
  if (method === 'GET') {
    if (!admin) return deny(orgDenial('iam:get'))
    return g ? { status: 200, body: g } : { status: 404, body: { error: `no guardrail ${id}` } }
  }
  if (method === 'PATCH') {
    if (!admin) return deny(orgDenial('iam:update'))
    if (!g) return { status: 404, body: { error: `no guardrail ${id}` } }
    if (ifMatch !== undefined && ifMatch !== g.version) throw new DirectoryError(409, 'the guardrail moved on; read it again', { version: g.version })
    const input = body as GuardrailInput
    const problems = guardrailProblems(input, known, mockActionCatalog(data), true)
    if (problems.length) return invalid('the guardrail', problems)
    if (input.name !== undefined && input.name !== g.name && all.some((x) => x.name === input.name)) throw new DirectoryError(409, `a guardrail named ${input.name} exists in the organization`)
    if (input.name !== undefined) g.name = input.name
    if (input.description !== undefined) g.description = input.description || undefined
    if (input.statements !== undefined) g.statements = input.statements
    if (input.accounts !== undefined) g.accounts = input.accounts
    g.version++
    g.updatedAt = now
    bump(dir)
    return { status: 200, body: g }
  }
  if (method === 'DELETE') {
    if (!admin) return deny(orgDenial('iam:delete'))
    if (!g) return { status: 404, body: { error: `no guardrail ${id}` } }
    dir.guardrails = all.filter((x) => x !== g)
    bump(dir)
    return { status: 204 }
  }
  return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
}

/**
 * The simulator, as the identity service runs it: exactly one principal (a person, a role or a
 * set of groups), at most 10 unsaved policies the principal would also hold, a draft boundary
 * for a role only, 1 to 100 checks; each check answered by `decide`, the code authorize runs.
 */
function simulate(data: MockData, ctx: RouteContext, req: Partial<PolicySimulationRequest>, can: (action: string, resource: string) => Decision, deny: (d: Decision) => RouteAnswer): RouteAnswer {
  const dir = data.directory
  const accountId = ctx.session.accountId
  const principal = isRecord(req.principal) ? req.principal : {}
  const problems: ReturnType<typeof policyProblems> = []
  const given = ['userId', 'role', 'groups'].filter((k) => principal[k as keyof typeof principal] !== undefined).length
  if (given !== 1) problems.push({ level: 'error', code: 'invalid', field: 'principal', message: 'principal names exactly one of userId, role or groups' })
  if (req.boundary !== undefined && principal.role === undefined) problems.push({ level: 'error', code: 'invalid', field: 'boundary', message: 'a draft boundary is for a role' })
  const checks = Array.isArray(req.checks) ? req.checks : []
  if (!checks.length || checks.length > 100) problems.push({ level: 'error', code: 'invalid', field: 'checks', message: '1 to 100 checks' })
  checks.forEach((c, i) => {
    if (!isAction(c?.action)) problems.push({ level: 'error', code: 'invalid', field: `checks[${i}].action`, message: 'action is <service>:<verb>' })
    if (typeof c?.resource !== 'string' || patternProblem(c.resource) !== null) problems.push({ level: 'error', code: 'invalid', field: `checks[${i}].resource`, message: 'resource is a wfrn or a wfrn pattern' })
  })
  const drafts = Array.isArray(req.policies) ? req.policies : []
  if (drafts.length > 10) problems.push({ level: 'error', code: 'invalid', field: 'policies', message: 'at most 10 unsaved policies' })
  if (problems.length) return invalid('the simulation', problems)
  const catalog = mockActionCatalog(data)
  for (const [i, d] of drafts.entries()) {
    const found = statementProblems(d?.statements, catalog).map((p) => ({ ...p, field: `policies[${i}].${p.field}` }))
    if (found.length) return invalid(`policies[${i}]`, found)
  }
  if (req.boundary) {
    const found = statementProblems(req.boundary.statements, catalog).map((p) => ({ ...p, field: `boundary.${p.field}` }))
    if (found.length) return invalid('the boundary', found)
  }
  const userId = principal.userId
  const d = can(principal.groups ? 'iam:list' : 'iam:get', userId ? iamResource(accountId, 'user', userId) : iamResource(accountId, principal.role !== undefined ? 'role' : 'group'))
  if (!d.allowed) return deny(d)

  const overlay: Overlay = { policies: drafts.map((p) => ({ name: p.name, statements: p.statements })) }
  let subject: Subject
  if (userId) {
    if (!userOf(dir, accountId, userId)) return { status: 404, body: { error: `no user ${userId}` } }
    subject = { userId, email: personOf(dir, userId)?.email, groups: groupsOf(dir, accountId, userId).map((g) => g.name) }
  } else if (principal.role !== undefined) {
    const role = roleIn(dir, accountId, principal.role)
    if (!role) return { status: 404, body: { error: `no role ${principal.role}` } }
    if (req.boundary) {
      if (role.name === ADMIN_ROLE) throw new DirectoryError(409, 'the account-admin role takes no permission boundary')
      overlay.boundary = { role: role.name, name: req.boundary.name, statements: req.boundary.statements }
    }
    subject = { userId: 'simulated:principal', groups: [], roles: [role.name] }
  } else {
    const names: string[] = []
    for (const g of principal.groups ?? []) {
      const found = (dir.groups[accountId] ?? []).find((x) => x.groupId === g || x.name === g)
      if (!found) return { status: 404, body: { error: `no group ${g}` } }
      names.push(found.name)
    }
    subject = { userId: 'simulated:principal', groups: names }
  }
  if (!accountOf(dir, accountId)) return { status: 404, body: { error: `no account ${accountId}` } }
  const results: SimulationResult[] = checks.map((c) => {
    const { reason, ...rest } = decide(dir, accountId, subject, { action: c.action, resource: c.resource, context: isRecord(c.context) ? c.context : undefined }, overlay)
    return { action: c.action, resource: c.resource, reason, ...rest }
  })
  return { status: 200, body: { results, version: dir.authzVersion ?? 1 } }
}
