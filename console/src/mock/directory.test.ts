import { describe, expect, it } from 'vitest'
import {
  ADMIN_ROLE,
  DirectoryError,
  PendingRequestError,
  accessKeysOf,
  accessRequestOf,
  accessRequestsOf,
  addUser,
  adminsOf,
  approversOf,
  authorize,
  buildDirectory,
  canonicalRole,
  createAccessKey,
  createAccessRequest,
  createGroup,
  decideAccessRequest,
  decideQuotaRequest,
  demoPeople,
  evaluateRoles,
  iamResource,
  matchesAction,
  matchesGlob,
  membershipsOf,
  policiesFor,
  quotaOf,
  quotasOf,
  removeGroup,
  removeUser,
  requestQuotaIncrease,
  revokeAccessKey,
  rolesOf,
  rotateAccessKey,
  seedQuotaOverrides,
  statementMatches,
  suggestedRoleFor,
  updateGroup,
  updateUser,
  userOf,
  usersOf,
  withdrawQuotaRequest,
} from './directory'

const COMPUTE = '123456789012'
const DATA = '210987654321'
const vm = (account = COMPUTE) => `wfrn:wf:compute:global:${account}:vm/*`
const NOW = Date.parse('2026-10-23T09:00:00Z')

describe('the people and their accounts, as the identity service seeds them', () => {
  it('seeds seven people (noah in no account), the four managed groups and an approvers group per account', () => {
    const dir = buildDirectory()
    expect(dir.people.map((p) => p.id)).toEqual(['gonzalo', 'dana', 'mateo', 'raj', 'ana', 'mia', 'noah'])
    expect(dir.people.find((p) => p.id === 'dana')).toMatchObject({ name: 'Dana Reyes', email: 'dana@example.com' })
    expect(usersOf(dir, COMPUTE).map((u) => u.userId).sort()).toEqual(['dana', 'gonzalo', 'mateo', 'mia'])
    expect(usersOf(dir, DATA).map((u) => u.userId).sort()).toEqual(['ana', 'gonzalo', 'raj'])
    expect(dir.groups[COMPUTE].map((g) => g.name)).toEqual(['owners', 'members', 'viewers', 'auditors', 'compute-approvers'])
    expect(dir.groups[DATA].map((g) => g.name)).toEqual(['owners', 'members', 'viewers', 'auditors', 'data-approvers'])
    expect(dir.groups[COMPUTE].filter((g) => g.builtIn).map((g) => g.name)).toEqual(['owners', 'members', 'viewers', 'auditors'])
    expect(dir.groups[COMPUTE].find((g) => g.name === 'members')?.idpGroups).toEqual(['team-compute'])
    expect(membershipsOf(dir, 'noah')).toEqual([])
    expect(demoPeople(dir).find((p) => p.id === 'noah')?.memberships).toEqual([])
  })

  it('gives each person the roles of their groups, the strongest first and a custom role last', () => {
    const dir = buildDirectory()
    expect(rolesOf(dir, COMPUTE, 'gonzalo')).toEqual([ADMIN_ROLE])
    expect(rolesOf(dir, COMPUTE, 'dana')).toEqual(['operator', 'approver'])
    expect(rolesOf(dir, COMPUTE, 'mia')).toEqual(['viewer', 'approver'])
    expect(rolesOf(dir, COMPUTE, 'mateo')).toEqual(['operator'])
    expect(rolesOf(dir, DATA, 'ana')).toEqual(['operator', 'viewer'])
    expect(rolesOf(dir, DATA, 'raj')).toEqual(['operator', 'approver'])
    expect(membershipsOf(dir, 'gonzalo').map((m) => m.accountId)).toEqual([COMPUTE, DATA])
    expect(adminsOf(dir, COMPUTE)).toEqual(['gonzalo'])
    expect(userOf(dir, COMPUTE, 'raj')).toBeUndefined()
  })

  it('names the approvers: the members of the groups whose policies allow deciding, sorted', () => {
    const dir = buildDirectory()
    expect(approversOf(dir, COMPUTE)).toEqual(['dana', 'gonzalo', 'mia'])
    expect(approversOf(dir, DATA)).toEqual(['gonzalo', 'raj'])
    // never the requester of the request at hand
    expect(approversOf(dir, COMPUTE, 'mia')).toEqual(['dana', 'gonzalo'])
  })
})

describe('matching', () => {
  it('matches actions and resources with globs, a star matching nothing too', () => {
    expect(matchesGlob('*', 'anything')).toBe(true)
    expect(matchesGlob('wfrn:wf:compute:*', 'wfrn:wf:compute:global:1:vm/x')).toBe(true)
    expect(matchesGlob('wfrn:wf:data:*', 'wfrn:wf:compute:global:1:vm/x')).toBe(false)
    expect(matchesGlob(`wfrn:wf:*:*:${COMPUTE}:*`, iamResource(COMPUTE, 'user')), 'the region-less IAM wfrn').toBe(true)
    expect(matchesAction('*:list', 'compute:list')).toBe(true)
    expect(matchesAction('*:*', 'iam:create')).toBe(true)
    expect(matchesAction('iam:*', 'iam:decide')).toBe(true)
    expect(matchesAction('compute:delete', 'compute:destroy')).toBe(false)
  })

  it('carves an allow’s notActions out of it', () => {
    const changes = policiesFor(COMPUTE).find((p) => p.policyId === 'managed-operator')!.statements[0]
    expect(statementMatches(changes, 'compute:create', vm())).toBe(true)
    expect(statementMatches(changes, 'compute:delete', vm())).toBe(false)
    expect(statementMatches(changes, 'iam:create', iamResource(COMPUTE, 'user'))).toBe(false)
    expect(statementMatches(changes, 'access-request:decide', iamResource(COMPUTE, 'access-request'))).toBe(false)
  })
})

describe('the decision', () => {
  it('takes the first matching deny, else the first allow, else denies by default', () => {
    const dir = buildDirectory()
    expect(evaluateRoles(dir, COMPUTE, ['operator'], 'compute:delete', vm())).toEqual({ effect: null })
    expect(evaluateRoles(dir, COMPUTE, ['operator'], 'compute:create', vm())).toEqual({ effect: 'allow', role: 'operator', policyId: 'managed-operator', statement: 'changes' })
    expect(evaluateRoles(dir, COMPUTE, ['operator'], 'iam:list', iamResource(COMPUTE, 'user'))).toMatchObject({ effect: 'allow', statement: 'iam-reads' })
    expect(evaluateRoles(dir, COMPUTE, [ADMIN_ROLE], 'compute:delete', vm())).toEqual({ effect: 'allow', role: ADMIN_ROLE, policyId: 'managed-account-admin', statement: 'everything' })
    expect(evaluateRoles(dir, COMPUTE, ['viewer'], 'compute:list', vm())).toMatchObject({ effect: 'allow', policyId: 'managed-viewer', statement: 'reads' })
    expect(evaluateRoles(dir, COMPUTE, ['viewer'], 'compute:create', vm())).toEqual({ effect: null })
    expect(evaluateRoles(dir, COMPUTE, ['viewer'], 'access-request:list', iamResource(COMPUTE, 'access-request'))).toEqual({ effect: null })
    expect(evaluateRoles(dir, COMPUTE, ['viewer', 'approver'], 'access-request:list', iamResource(COMPUTE, 'access-request'))).toMatchObject({ effect: 'allow', role: 'approver', statement: 'approve' })
    expect(evaluateRoles(dir, COMPUTE, ['auditor'], 'audit:query', vm())).toMatchObject({ effect: 'allow', statement: 'audit' })
    expect(evaluateRoles(dir, COMPUTE, [], 'compute:list', vm())).toEqual({ effect: null })
  })

  it('lets an explicit deny win over any allow, naming the statement', () => {
    const dir = buildDirectory()
    dir.policies[COMPUTE].push({ policyId: 'no-prod-deletes', name: 'no-prod-deletes', builtIn: false, version: 1, statements: [{ sid: 'keep-prod', effect: 'deny', actions: ['compute:delete'], resources: [`wfrn:wf:compute:global:${COMPUTE}:vm/prod-*`] }] })
    dir.roles.find((r) => r.roleId === ADMIN_ROLE)!.policies = ['managed-account-admin', 'no-prod-deletes']
    const d = authorize(dir, { accountId: COMPUTE, userId: 'gonzalo' }, 'compute:delete', `wfrn:wf:compute:global:${COMPUTE}:vm/prod-01`)
    expect(d.denied).toMatchObject({ layer: 'console-iam', reason: 'denied by policy no-prod-deletes, statement keep-prod', policyId: 'no-prod-deletes', statement: 'keep-prod', role: ADMIN_ROLE, requestable: false })
    expect(authorize(dir, { accountId: COMPUTE, userId: 'gonzalo' }, 'compute:delete', `wfrn:wf:compute:global:${COMPUTE}:vm/dev-01`).allowed).toBe(true)
  })

  it('suggests the weakest managed role that would allow the action', () => {
    const dir = buildDirectory()
    expect(suggestedRoleFor(dir, COMPUTE, 'compute:list', vm())).toBe('viewer')
    expect(suggestedRoleFor(dir, COMPUTE, 'audit:query', vm())).toBe('auditor')
    expect(suggestedRoleFor(dir, COMPUTE, 'compute:create', vm())).toBe('operator')
    expect(suggestedRoleFor(dir, COMPUTE, 'compute:delete', vm())).toBe(ADMIN_ROLE)
    expect(suggestedRoleFor(dir, COMPUTE, 'iam:create', iamResource(COMPUTE, 'user'))).toBe(ADMIN_ROLE)
    // the auditor's reads carve nothing out, so an auditor reads the account's requests
    expect(suggestedRoleFor(dir, COMPUTE, 'access-request:list', iamResource(COMPUTE, 'access-request'))).toBe('auditor')
  })

  it('names the layer, the role and the suggestion in the Denial, console IAM first and the platform second', () => {
    const dir = buildDirectory()
    expect(authorize(dir, { accountId: COMPUTE, userId: 'gonzalo' }, 'compute:delete', vm())).toMatchObject({ allowed: true, via: { policyId: 'managed-account-admin', statement: 'everything', role: ADMIN_ROLE } })
    const viewer = authorize(dir, { accountId: COMPUTE, userId: 'mia' }, 'compute:create', vm())
    expect(viewer.denied).toMatchObject({ layer: 'console-iam', action: 'compute:create', reason: `no policy allows compute:create on ${vm()}`, role: 'viewer', requestable: true, suggestedRole: 'operator' })
    expect(viewer.denied?.statement).toBeUndefined()
    const operator = authorize(dir, { accountId: COMPUTE, userId: 'dana' }, 'compute:delete', vm())
    expect(operator.denied).toMatchObject({ layer: 'console-iam', role: 'operator', requestable: true, suggestedRole: ADMIN_ROLE })
    expect(operator.denied?.policyId).toBeUndefined()
    const platform = authorize(dir, { accountId: DATA, userId: 'raj' }, 'compute:create', vm(DATA))
    expect(platform.denied).toMatchObject({ layer: 'platform', scope: 'compute:write', serviceAccount: 'svc-data', requestable: true })
    expect(authorize(dir, { accountId: DATA, userId: 'raj' }, 'compute:list', vm(DATA)).allowed).toBe(true)
    // the console's own services are the identity service's alone: the platform's scopes never decide them
    expect(authorize(dir, { accountId: COMPUTE, userId: 'gonzalo' }, 'iam:create', iamResource(COMPUTE, 'user')).allowed).toBe(true)
    expect(authorize(dir, { accountId: COMPUTE, userId: 'mia' }, 'access-request:create', iamResource(COMPUTE, 'access-request')).allowed).toBe(true)
    expect(authorize(dir, { accountId: 'nope', userId: 'raj' }, 'compute:list', vm()).denied?.requestable).toBe(false)
    expect(authorize(dir, { accountId: COMPUTE, userId: 'raj' }, 'compute:list', vm()).denied?.reason).toMatch(/not a member/)
  })
})

describe('people and groups', () => {
  it('adds a person by email into viewers, moves them between groups and removes them', () => {
    const dir = buildDirectory()
    const added = addUser(dir, COMPUTE, { email: 'Lee.Park@example.com', name: 'Lee Park' })
    expect(added).toMatchObject({ userId: 'lee.park', email: 'lee.park@example.com', status: 'active', groups: ['viewers'] })
    expect(rolesOf(dir, COMPUTE, 'lee.park')).toEqual(['viewer'])
    expect(() => addUser(dir, COMPUTE, { name: 'no email' })).toThrow(DirectoryError)
    expect(() => addUser(dir, COMPUTE, { email: 'x@example.com', groups: ['nope'] })).toThrow(/'nope' is no group/)

    const members = dir.groups[COMPUTE].find((g) => g.name === 'members')!
    const changed = updateUser(dir, COMPUTE, 'lee.park', { groups: [members.groupId] }, added.version)
    expect(changed.groups).toEqual(['members'])
    expect(rolesOf(dir, COMPUTE, 'lee.park')).toEqual(['operator'])
    expect(() => updateUser(dir, COMPUTE, 'lee.park', { groups: [] }, 1)).toThrow(/read it again/)
    expect(() => updateUser(dir, COMPUTE, 'lee.park', { groups: [] })).toThrow(/no group/)

    removeUser(dir, COMPUTE, 'lee.park')
    expect(userOf(dir, COMPUTE, 'lee.park')).toBeUndefined()
    expect(() => removeUser(dir, COMPUTE, 'lee.park')).toThrow(DirectoryError)
  })

  it('keeps the owners group: a member, its name, the account-admin role, and itself', () => {
    const dir = buildDirectory()
    const owners = dir.groups[COMPUTE].find((g) => g.name === 'owners')!
    expect(() => removeUser(dir, COMPUTE, 'gonzalo')).toThrow('the owners group keeps at least one active member')
    expect(() => updateUser(dir, COMPUTE, 'gonzalo', { groups: ['viewers'] })).toThrow('the owners group keeps at least one active member')
    expect(() => updateGroup(dir, COMPUTE, owners.groupId, { members: [] })).toThrow('the owners group keeps at least one active member')
    expect(() => updateGroup(dir, COMPUTE, owners.groupId, { name: 'admins' })).toThrow('a built-in group keeps its name')
    expect(() => updateGroup(dir, COMPUTE, owners.groupId, { roles: ['operator'] })).toThrow('the owners group keeps the account-admin role')
    expect(() => removeGroup(dir, COMPUTE, owners.groupId)).toThrow('the owners group cannot be deleted')
    // with a second owner, the first may leave
    updateGroup(dir, COMPUTE, owners.groupId, { members: ['gonzalo', 'dana'] })
    expect(updateUser(dir, COMPUTE, 'gonzalo', { groups: ['viewers'] }).groups).toEqual(['viewers'])
    // another managed group keeps its name but may go
    const auditors = dir.groups[COMPUTE].find((g) => g.name === 'auditors')!
    expect(() => updateGroup(dir, COMPUTE, auditors.groupId, { name: 'readers' })).toThrow('a built-in group keeps its name')
    removeGroup(dir, COMPUTE, auditors.groupId)
    expect(dir.groups[COMPUTE].some((g) => g.name === 'auditors')).toBe(false)
  })

  it('creates and changes a group, validating the name, the roles and the members', () => {
    const dir = buildDirectory()
    const g = createGroup(dir, COMPUTE, { name: 'team-storage', roles: ['operator'], idpGroups: ['corp-storage'] })
    expect(g).toMatchObject({ name: 'team-storage', roles: ['operator'], idpGroups: ['corp-storage'], builtIn: false, version: 1 })
    expect(() => createGroup(dir, COMPUTE, { name: 'Bad Name', roles: ['viewer'] })).toThrow(DirectoryError)
    expect(() => createGroup(dir, COMPUTE, { name: 'team-storage', roles: ['viewer'] })).toThrow(/exists/)
    expect(() => createGroup(dir, COMPUTE, { name: 'outsiders', members: ['raj'] })).toThrow(/'raj' is no user of this account/)
    const next = updateGroup(dir, COMPUTE, g.groupId, { members: ['mia'], roles: ['operator', 'viewer'] }, g.version)
    expect(next.members).toEqual(['mia'])
    expect(rolesOf(dir, COMPUTE, 'mia')).toEqual(['operator', 'viewer', 'approver'])
    expect(() => updateGroup(dir, COMPUTE, g.groupId, { roles: ['king'] })).toThrow(/'king' is no role/)
    expect(() => updateGroup(dir, COMPUTE, g.groupId, { name: 'members' })).toThrow(/exists/)
  })
})

describe('access keys', () => {
  it('issues a secret once, keeps two usable per person, rotates, revokes and serves an old one expired', () => {
    const dir = buildDirectory()
    const created = createAccessKey(dir, COMPUTE, 'mia', { description: 'ci' }, NOW)
    expect(created.keyId).toMatch(/^ck_[0-9A-Z]{26}$/)
    expect(created.secretPart).toMatch(/^cs_/)
    expect(created).toMatchObject({ userId: 'mia', status: 'active', description: 'ci', createdAt: '2026-10-23T09:00:00.000Z' })
    expect(() => createAccessKey(dir, COMPUTE, 'mia', { expiresAt: '2026-10-01T00:00:00Z' }, NOW)).toThrow('expiresAt is in the future')
    expect(() => createAccessKey(dir, COMPUTE, 'mia', { description: 'x'.repeat(129) }, NOW)).toThrow(/128/)
    expect(() => createAccessKey(dir, COMPUTE, 'raj', {}, NOW)).toThrow(/no user raj/)
    createAccessKey(dir, COMPUTE, 'mia', { expiresAt: '2026-10-24T09:00:00Z' }, NOW)
    expect(() => createAccessKey(dir, COMPUTE, 'mia', {}, NOW)).toThrow('a user has at most 2 active access keys')
    // the second one expires, which frees its place
    const later = Date.parse('2026-10-25T09:00:00Z')
    expect(accessKeysOf(dir, COMPUTE, later).filter((k) => k.userId === 'mia').map((k) => k.status)).toEqual(['expired', 'active'])
    expect(createAccessKey(dir, COMPUTE, 'mia', {}, later).status).toBe('active')

    const rotated = rotateAccessKey(dir, COMPUTE, created.keyId, NOW + 60_000)
    expect(rotated.keyId).toBe(created.keyId)
    expect(rotated.secretPart).not.toBe(created.secretPart)
    expect(rotated.graceUntil).toBeDefined()
    revokeAccessKey(dir, COMPUTE, created.keyId)
    revokeAccessKey(dir, COMPUTE, created.keyId)
    expect(dir.accessKeys[COMPUTE].find((k) => k.keyId === created.keyId)?.status).toBe('revoked')
    expect(() => rotateAccessKey(dir, COMPUTE, created.keyId, NOW)).toThrow('the key is revoked')
    expect(() => revokeAccessKey(dir, COMPUTE, 'ck_nope')).toThrow(DirectoryError)
  })
})

describe('quotas', () => {
  it('serves jobs, access keys and a resources quota per platform resource type, by name', () => {
    const dir = buildDirectory()
    const quotas = quotasOf(dir, COMPUTE, { vm: 40, image: 3 }, ['vm', 'image', 'database', 'vm'], NOW)
    expect(quotas.map((q) => q.key)).toEqual(['jobs-per-minute', 'access-keys', 'resources.database', 'resources.image', 'resources.vm'])
    expect(quotas[0]).toMatchObject({ limit: 60, burst: 120, source: 'platform' })
    expect(quotas[1]).toMatchObject({ limit: 50, used: 2, source: 'default' })
    expect(quotas[2]).toMatchObject({ limit: 100, used: 0, source: 'default' })
    expect(quotas[4]).toMatchObject({ key: 'resources.vm', limit: 100, used: 40, source: 'default' })
  })

  it('raises at the start what the seed already holds above the default', () => {
    const dir = buildDirectory()
    seedQuotaOverrides(dir, { [COMPUTE]: { vm: 1234, image: 20 } })
    expect(quotaOf(dir, COMPUTE, 'resources.vm', { vm: 1234 }, NOW)).toMatchObject({ limit: 1900, used: 1234, source: 'override' })
    expect(quotaOf(dir, COMPUTE, 'resources.image', { image: 20 }, NOW)).toMatchObject({ limit: 100, source: 'default' })
  })

  it('asks the platform team for more, once per key; the requester withdraws, the platform team decides', () => {
    const dir = buildDirectory()
    const current = quotaOf(dir, COMPUTE, 'access-keys', {}, NOW)
    expect(() => requestQuotaIncrease(dir, COMPUTE, 'nope', 'dana', { limit: 80, reason: 'the build farm grows' }, current, NOW)).toThrow('no quota nope')
    expect(() => requestQuotaIncrease(dir, COMPUTE, 'access-keys', 'dana', { limit: 0, reason: 'the build farm grows' }, current, NOW)).toThrow('limit is an integer of at least 1')
    expect(() => requestQuotaIncrease(dir, COMPUTE, 'access-keys', 'dana', { limit: 80, reason: 'more' }, current, NOW)).toThrow('reason is 10 to 1000 characters')
    const asked = requestQuotaIncrease(dir, COMPUTE, 'access-keys', 'dana', { limit: 80, reason: 'the build farm grows' }, current, NOW)
    expect(asked).toMatchObject({ requestId: 'qreq_001', quotaKey: 'access-keys', currentLimit: 50, requestedLimit: 80, status: 'open', requestedBy: 'dana' })
    expect(quotaOf(dir, COMPUTE, 'access-keys', {}, NOW).pendingRequestId).toBe('qreq_001')
    let again: unknown
    try {
      requestQuotaIncrease(dir, COMPUTE, 'access-keys', 'mia', { limit: 90, reason: 'the build farm grows' }, current, NOW)
    } catch (e) {
      again = e
    }
    expect(again).toBeInstanceOf(DirectoryError)
    expect(again).toMatchObject({ status: 409, message: 'an increase of access-keys is already requested', detail: { requestId: 'qreq_001' } })
    expect(withdrawQuotaRequest(dir, COMPUTE, 'qreq_001', 'dana', NOW).status).toBe('withdrawn')
    expect(() => withdrawQuotaRequest(dir, COMPUTE, 'qreq_001', 'dana', NOW)).toThrow('the request is withdrawn')

    const second = requestQuotaIncrease(dir, COMPUTE, 'access-keys', 'dana', { limit: 80, reason: 'the build farm grows' }, current, NOW)
    decideQuotaRequest(dir, COMPUTE, second.requestId, 'approve', 'platform-team', { limit: 70 }, NOW)
    expect(quotaOf(dir, COMPUTE, 'access-keys', {}, NOW)).toMatchObject({ limit: 70, source: 'override' })
    expect(quotaOf(dir, COMPUTE, 'access-keys', {}, NOW).pendingRequestId).toBeUndefined()
  })
})

describe('access requests', () => {
  it('serves a request pending for more than 30 days as expired', () => {
    const dir = buildDirectory()
    expect(accessRequestOf(dir, COMPUTE, 'areq_002', NOW)?.status).toBe('expired')
    expect(accessRequestOf(dir, COMPUTE, 'areq_002', Date.parse('2026-09-20T00:00:00Z'))?.status).toBe('pending')
    expect(accessRequestsOf(dir, COMPUTE, NOW).map((r) => r.status)).toEqual(['approved', 'expired'])
    expect(() => decideAccessRequest(dir, COMPUTE, 'areq_002', 'approve', 'gonzalo', undefined, NOW)).toThrow('the request is expired')
  })

  it('needs a reason, suggests the role, answers a second ask with the pending one and, approved, joins the managed group', () => {
    const dir = buildDirectory()
    const mia = userOf(dir, COMPUTE, 'mia')!
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: '  ' }, NOW)).toThrow('reason is 1 to 2000 characters')
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: 'x'.repeat(2001) }, NOW)).toThrow('reason is 1 to 2000 characters')
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'create a vm', reason: 'machines' }, NOW)).toThrow('action is <service>:<verb>')
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', resource: 'vm/1', reason: 'machines' }, NOW)).toThrow(/wfrn/)
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', role: 'king', reason: 'machines' }, NOW)).toThrow("'king' is no role of this account")
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:list', reason: 'machines' }, NOW)).toThrow(/allowed already\); name the role/)
    const request = createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: 'I build the team machines' }, NOW)
    // mia is an approver too, but never of a request mia made: the identity service leaves the requester out
    expect(request).toMatchObject({ accountId: COMPUTE, requester: { userId: 'mia', name: 'Mia Chen' }, action: 'compute:create', role: 'operator', status: 'pending', approvers: ['dana', 'gonzalo'], layer: 'console-iam', requestedAt: '2026-10-23T09:00:00.000Z' })
    expect(request.resource).toBe(`wfrn:wf:*:*:${COMPUTE}:*`)
    let again: unknown
    try {
      createAccessRequest(dir, COMPUTE, mia, { action: 'compute:update', reason: 'the same role again' }, NOW)
    } catch (e) {
      again = e
    }
    expect(again).toBeInstanceOf(PendingRequestError)
    expect((again as PendingRequestError).request.requestId).toBe(request.requestId)

    expect(() => decideAccessRequest(dir, COMPUTE, 'areq_nope', 'approve', 'gonzalo')).toThrow(DirectoryError)
    expect(() => decideAccessRequest(dir, COMPUTE, request.requestId, 'approve', 'gonzalo', 'x'.repeat(2001))).toThrow('comment is at most 2000 characters')
    const decided = decideAccessRequest(dir, COMPUTE, request.requestId, 'approve', 'dana', 'Welcome aboard', NOW + 1000)
    expect(decided).toMatchObject({ status: 'approved', decidedBy: 'dana', comment: 'Welcome aboard', decidedAt: '2026-10-23T09:00:01.000Z' })
    expect(dir.groups[COMPUTE].find((g) => g.name === 'members')?.members).toContain('mia')
    expect(rolesOf(dir, COMPUTE, 'mia')).toContain('operator')
    expect(authorize(dir, { accountId: COMPUTE, userId: 'mia' }, 'compute:create', vm()).allowed).toBe(true)
    expect(() => decideAccessRequest(dir, COMPUTE, request.requestId, 'reject', 'gonzalo')).toThrow('the request is approved')

    const second = createAccessRequest(dir, COMPUTE, mia, { action: 'compute:delete', role: 'admin', reason: 'I need to clean up old machines' }, NOW)
    expect(second.role).toBe(ADMIN_ROLE)
    expect(decideAccessRequest(dir, COMPUTE, second.requestId, 'reject', 'gonzalo').status).toBe('rejected')
    expect(rolesOf(dir, COMPUTE, 'mia')).not.toContain(ADMIN_ROLE)
  })

  it('reads admin as the account-admin role, and lands a custom role in the first group holding it', () => {
    const dir = buildDirectory()
    expect(canonicalRole('admin')).toBe(ADMIN_ROLE)
    expect(canonicalRole('operator')).toBe('operator')
    const mateo = userOf(dir, COMPUTE, 'mateo')!
    const ask = createAccessRequest(dir, COMPUTE, mateo, { action: 'access-request:decide', role: 'approver', reason: 'I cover approvals this week' }, NOW)
    decideAccessRequest(dir, COMPUTE, ask.requestId, 'approve', 'gonzalo', undefined, NOW)
    expect(dir.groups[COMPUTE].find((g) => g.name === 'compute-approvers')?.members).toContain('mateo')
    expect(approversOf(dir, COMPUTE)).toEqual(['dana', 'gonzalo', 'mateo', 'mia'])
  })
})
