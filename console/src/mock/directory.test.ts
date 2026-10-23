import { describe, expect, it } from 'vitest'
import { DirectoryError, addUser, adminsOf, authorize, buildDirectory, createAccessKey, createAccessRequest, createGroup, decideAccessRequest, evaluateRoles, matchesAction, matchesGlob, membershipsOf, removeUser, revokeAccessKey, rolesOf, rotateAccessKey, suggestedRoleFor, updateGroup, updateUser, userOf, usersOf } from './directory'

const COMPUTE = '123456789012'
const DATA = '210987654321'
const vm = (account = COMPUTE) => `wfrn:wf:compute:global:${account}:vm/*`

describe('the people and their accounts', () => {
  it('mirrors the BFF demo profile: six people, two accounts, roles from groups', () => {
    const dir = buildDirectory()
    expect(usersOf(dir, COMPUTE).map((u) => u.userId).sort()).toEqual(['dana', 'gonzalo', 'mateo', 'mia'])
    expect(usersOf(dir, DATA).map((u) => u.userId).sort()).toEqual(['ana', 'gonzalo', 'raj'])
    expect(rolesOf(dir, COMPUTE, 'gonzalo')).toContain('admin')
    expect(rolesOf(dir, COMPUTE, 'mia')).toEqual(['viewer'])
    expect(rolesOf(dir, COMPUTE, 'dana')).toEqual(['operator', 'viewer'])
    expect(rolesOf(dir, DATA, 'raj')).toContain('operator')
    expect(membershipsOf(dir, 'gonzalo').map((m) => m.accountId)).toEqual([COMPUTE, DATA])
    expect(membershipsOf(dir, 'nobody')).toEqual([])
    expect(adminsOf(dir, COMPUTE)).toEqual(['gonzalo'])
    expect(userOf(dir, COMPUTE, 'raj')).toBeUndefined()
  })
})

describe('matching', () => {
  it('matches actions and resources with globs', () => {
    expect(matchesGlob('*', 'anything')).toBe(true)
    expect(matchesGlob('wfrn:wf:compute:*', 'wfrn:wf:compute:global:1:vm/x')).toBe(true)
    expect(matchesGlob('wfrn:wf:data:*', 'wfrn:wf:compute:global:1:vm/x')).toBe(false)
    expect(matchesAction('*:list', 'compute:list')).toBe(true)
    expect(matchesAction('*:*', 'iam:create')).toBe(true)
    expect(matchesAction('iam:*', 'iam:decide')).toBe(true)
    expect(matchesAction('compute:delete', 'compute:destroy')).toBe(false)
  })
})

describe('the decision', () => {
  it('lets an explicit deny win, then any allow, else denies', () => {
    const dir = buildDirectory()
    expect(evaluateRoles(dir, ['operator'], 'compute:delete', vm())).toEqual({ effect: 'deny', policyId: 'managed-operator', statement: 'DenyDestructive' })
    expect(evaluateRoles(dir, ['operator', 'admin'], 'compute:delete', vm())).toMatchObject({ effect: 'deny', statement: 'DenyDestructive' })
    expect(evaluateRoles(dir, ['admin'], 'compute:delete', vm())).toEqual({ effect: 'allow', policyId: 'managed-admin', statement: 'AllowAll' })
    expect(evaluateRoles(dir, ['viewer'], 'compute:list', vm())).toMatchObject({ effect: 'allow', policyId: 'managed-viewer' })
    expect(evaluateRoles(dir, ['viewer'], 'compute:create', vm())).toEqual({ effect: null })
    expect(evaluateRoles(dir, [], 'compute:list', vm())).toEqual({ effect: null })
  })

  it('suggests the weakest managed role that would allow the action', () => {
    const dir = buildDirectory()
    expect(suggestedRoleFor(dir, 'compute:list', vm())).toBe('viewer')
    expect(suggestedRoleFor(dir, 'compute:create', vm())).toBe('operator')
    expect(suggestedRoleFor(dir, 'compute:delete', vm())).toBe('admin')
    expect(suggestedRoleFor(dir, 'iam:create', `wfrn:wf:iam:global:${COMPUTE}:user/*`)).toBe('admin')
  })

  it('names the layer and the statement in the Denial, console IAM first and the platform second', () => {
    const dir = buildDirectory()
    expect(authorize(dir, { accountId: COMPUTE, userId: 'gonzalo' }, 'compute:delete', vm())).toMatchObject({ allowed: true, via: { policyId: 'managed-admin' } })
    const viewer = authorize(dir, { accountId: COMPUTE, userId: 'mia' }, 'compute:create', vm())
    expect(viewer.allowed).toBe(false)
    expect(viewer.denied).toMatchObject({ layer: 'console-iam', action: 'compute:create', role: 'viewer', requestable: true, suggestedRole: 'operator' })
    expect(viewer.denied?.statement).toBeUndefined()
    const operator = authorize(dir, { accountId: COMPUTE, userId: 'dana' }, 'compute:delete', vm())
    expect(operator.denied).toMatchObject({ layer: 'console-iam', policyId: 'managed-operator', statement: 'DenyDestructive', requestable: true, suggestedRole: 'admin' })
    const platform = authorize(dir, { accountId: DATA, userId: 'raj' }, 'compute:create', vm(DATA))
    expect(platform.denied).toMatchObject({ layer: 'platform', scope: 'compute:write', requestable: true })
    expect(platform.denied?.serviceAccount).toMatch(/^svc-/)
    expect(authorize(dir, { accountId: DATA, userId: 'raj' }, 'compute:list', vm(DATA)).allowed).toBe(true)
    expect(authorize(dir, { accountId: 'nope', userId: 'raj' }, 'compute:list', vm()).denied?.requestable).toBe(false)
    expect(authorize(dir, { accountId: COMPUTE, userId: 'raj' }, 'compute:list', vm()).denied?.reason).toMatch(/no role/)
  })
})

describe('people and groups', () => {
  it('adds a person by email into groups, moves them between groups and removes them', () => {
    const dir = buildDirectory()
    const added = addUser(dir, COMPUTE, { email: 'Lee.Park@example.com', name: 'Lee Park' })
    expect(added).toMatchObject({ userId: 'lee.park', email: 'lee.park@example.com', status: 'active' })
    expect(rolesOf(dir, COMPUTE, 'lee.park')).toEqual(['viewer'])
    expect(() => addUser(dir, COMPUTE, { name: 'no email' })).toThrow(DirectoryError)
    expect(() => addUser(dir, COMPUTE, { email: 'x@example.com', groups: ['nope'] })).toThrow(/No group nope/)

    const developer = dir.groups[COMPUTE].find((g) => g.name === 'developer')!
    const changed = updateUser(dir, COMPUTE, 'lee.park', { groups: [developer.groupId] }, added.version)
    expect(changed.groups).toEqual([developer.name])
    expect(rolesOf(dir, COMPUTE, 'lee.park')).toEqual(['operator'])
    expect(() => updateUser(dir, COMPUTE, 'lee.park', { groups: [] }, 1)).toThrow(/version/)
    expect(() => updateUser(dir, COMPUTE, 'lee.park', { groups: [] })).toThrow(/no group/)

    removeUser(dir, COMPUTE, 'lee.park')
    expect(userOf(dir, COMPUTE, 'lee.park')).toBeUndefined()
    expect(() => removeUser(dir, COMPUTE, 'lee.park')).toThrow(DirectoryError)
  })

  it('creates and changes a group, validating the name and the roles', () => {
    const dir = buildDirectory()
    const g = createGroup(dir, COMPUTE, { name: 'team-storage', roles: ['operator'], idpGroups: ['corp-storage'] })
    expect(g).toMatchObject({ name: 'team-storage', roles: ['operator'], idpGroups: ['corp-storage'], version: 1 })
    expect(() => createGroup(dir, COMPUTE, { name: 'Bad Name', roles: ['viewer'] })).toThrow(DirectoryError)
    expect(() => createGroup(dir, COMPUTE, { name: 'team-storage', roles: ['viewer'] })).toThrow(/in use|exists/i)
    const next = updateGroup(dir, COMPUTE, g.groupId, { members: ['mia'], roles: ['operator', 'viewer'] }, g.version)
    expect(next.members).toEqual(['mia'])
    expect(rolesOf(dir, COMPUTE, 'mia')).toEqual(['operator', 'viewer'])
    expect(() => updateGroup(dir, COMPUTE, g.groupId, { roles: ['king'] })).toThrow(/No role king/)
    expect(() => updateGroup(dir, COMPUTE, g.groupId, { members: ['nobody'] })).toThrow(/Nobody is called nobody/)
  })
})

describe('access keys', () => {
  it('issues a secret once, keeps five active per person, rotates and revokes', () => {
    const dir = buildDirectory()
    const now = Date.parse('2026-10-23T08:00:00Z')
    const created = createAccessKey(dir, COMPUTE, 'mia', { description: 'ci' }, now)
    expect(created.keyId).toMatch(/^ck_[0-9A-Z]{26}$/)
    expect(created.secretPart).toMatch(/^cs_/)
    expect(created).toMatchObject({ userId: 'mia', status: 'active', description: 'ci', createdAt: '2026-10-23T08:00:00.000Z' })
    expect(() => createAccessKey(dir, COMPUTE, 'mia', { expiresAt: 'tomorrow' }, now)).toThrow(/not a date/)
    expect(() => createAccessKey(dir, COMPUTE, 'raj', {}, now)).toThrow(/not a member/)
    for (let i = 0; i < 4; i++) createAccessKey(dir, COMPUTE, 'mia', {}, now)
    expect(() => createAccessKey(dir, COMPUTE, 'mia', {}, now)).toThrow(/5 active/)

    const rotated = rotateAccessKey(dir, COMPUTE, created.keyId, now + 60_000)
    expect(rotated.keyId).toBe(created.keyId)
    expect(rotated.secretPart).not.toBe(created.secretPart)
    expect(rotated.graceUntil).toBeDefined()
    revokeAccessKey(dir, COMPUTE, created.keyId)
    expect(dir.accessKeys[COMPUTE].find((k) => k.keyId === created.keyId)?.status).toBe('revoked')
    expect(() => revokeAccessKey(dir, COMPUTE, 'ck_nope')).toThrow(DirectoryError)
  })
})

describe('access requests', () => {
  it('needs a reason, suggests the role, refuses a duplicate and, approved, grants the role', () => {
    const dir = buildDirectory()
    const mia = userOf(dir, COMPUTE, 'mia')!
    const now = Date.parse('2026-10-23T09:00:00Z')
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: 'short' }, now)).toThrow(/ten characters/)
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'create a vm', reason: 'a long enough reason' }, now)).toThrow(/<service>:<verb>/)
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', role: 'king', reason: 'a long enough reason' }, now)).toThrow(/No role king/)
    const request = createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: 'I build the team machines' }, now)
    expect(request).toMatchObject({ accountId: COMPUTE, requester: { userId: 'mia' }, action: 'compute:create', role: 'operator', status: 'pending', approvers: ['gonzalo'], layer: 'console-iam', requestedAt: '2026-10-23T09:00:00.000Z' })
    expect(request.resource).toBe(`wfrn:wf:compute:global:${COMPUTE}:*`)
    expect(() => createAccessRequest(dir, COMPUTE, mia, { action: 'compute:create', reason: 'I build the team machines' }, now)).toThrow(/still pending/)

    expect(() => decideAccessRequest(dir, COMPUTE, 'areq_nope', 'approve', 'gonzalo')).toThrow(DirectoryError)
    const decided = decideAccessRequest(dir, COMPUTE, request.requestId, 'approve', 'gonzalo', 'Welcome aboard', now + 1000)
    expect(decided).toMatchObject({ status: 'approved', decidedBy: 'gonzalo', comment: 'Welcome aboard', decidedAt: '2026-10-23T09:00:01.000Z' })
    expect(rolesOf(dir, COMPUTE, 'mia')).toContain('operator')
    expect(authorize(dir, { accountId: COMPUTE, userId: 'mia' }, 'compute:create', vm()).allowed).toBe(true)
    expect(() => decideAccessRequest(dir, COMPUTE, request.requestId, 'reject', 'gonzalo')).toThrow(/already approved/)

    const second = createAccessRequest(dir, COMPUTE, mia, { action: 'compute:delete', reason: 'I need to clean up old machines' }, now)
    expect(second.role).toBe('admin')
    expect(decideAccessRequest(dir, COMPUTE, second.requestId, 'reject', 'gonzalo').status).toBe('rejected')
    expect(rolesOf(dir, COMPUTE, 'mia')).not.toContain('admin')
  })
})
