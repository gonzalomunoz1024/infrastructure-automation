import { describe, expect, it } from 'vitest'
import { ALLOWED, actionOf, adminOnly, decide, decidedBy, denialOf, denialTitle, layerLabel, permissionsOf, splitAction, typeKey, verbTitle } from './permissions'
import type { BffConsoleType, Denial } from './types'

const denied: Denial = { layer: 'console-iam', action: 'compute:delete', reason: 'Statement DenyDestructive of policy managed-operator denies compute:delete', role: 'operator', policyId: 'managed-operator', statement: 'DenyDestructive', requestable: true, suggestedRole: 'admin' }

const types: BffConsoleType[] = [
  {
    key: 'compute/vm',
    type: 'vm',
    service: 'compute',
    actions: [
      { verb: 'list', title: 'List', allowed: true },
      { verb: 'create', title: 'Create', allowed: true },
      { verb: 'delete', title: 'Delete', allowed: false, denied },
      { verb: 'restart', title: 'Restart', allowed: false, deniedReason: 'Operators do not restart' },
      { verb: 'describe', title: 'Describe' },
    ],
  },
  { key: '', type: 'database', service: 'data', actions: [{ verb: 'create', title: 'Create', allowed: false, denied: { layer: 'platform', action: 'data:create', reason: 'no scope', scope: 'data:write', requestable: true } }] },
]

describe('permissionsOf and decide', () => {
  it('reads every verb of every type, a deniedReason alone becoming a console-iam denial', () => {
    const perms = permissionsOf(types)
    expect(Object.keys(perms)).toEqual(['compute/vm', 'data/database'])
    expect(perms['compute/vm'].list).toBe(ALLOWED)
    expect(perms['compute/vm'].describe).toBe(ALLOWED)
    expect(perms['compute/vm'].delete).toEqual({ allowed: false, denied })
    expect(perms['compute/vm'].restart.denied).toMatchObject({ layer: 'console-iam', action: 'compute:restart', reason: 'Operators do not restart', requestable: true })
    expect(perms['data/database'].create.denied?.layer).toBe('platform')
  })

  it('allows what the BFF has not decided, so an unknown call is refused by the call itself', () => {
    const perms = permissionsOf(types)
    expect(decide(perms, 'compute', 'vm', 'delete').allowed).toBe(false)
    expect(decide(perms, 'compute', 'vm', 'upgrade')).toBe(ALLOWED)
    expect(decide(perms, 'storage', 'bucket', 'create')).toBe(ALLOWED)
    expect(decide(undefined, 'compute', 'vm', 'delete')).toBe(ALLOWED)
    expect(permissionsOf(undefined)).toEqual({})
  })

  it('names keys and actions', () => {
    expect(typeKey('compute', 'vm')).toBe('compute/vm')
    expect(actionOf('compute', 'create')).toBe('compute:create')
    expect(splitAction('compute:add-disk')).toEqual({ service: 'compute', verb: 'add-disk' })
    expect(splitAction('restart')).toEqual({ service: '', verb: 'restart' })
  })
})

describe('the words of a refusal', () => {
  it('titles verbs and layers', () => {
    expect(verbTitle('create')).toBe('Create')
    expect(verbTitle('get')).toBe('View')
    expect(verbTitle('add-disk')).toBe('Add disk')
    expect(layerLabel('console-iam')).toBe('Console IAM')
    expect(layerLabel('platform')).toBe('The platform')
  })

  it('says who decided, naming the statement when there is one', () => {
    expect(denialTitle(denied)).toBe('Your role does not allow compute:delete')
    expect(decidedBy(denied)).toBe('Statement DenyDestructive of policy managed-operator denies it for the role operator.')
    expect(decidedBy({ ...denied, statement: undefined })).toBe('Policy managed-operator denies it for the role operator.')
    expect(decidedBy({ layer: 'console-iam', action: 'compute:create', reason: 'r', role: 'viewer', requestable: true })).toBe('No statement of the role viewer allows it, and what no statement allows is denied.')
    expect(decidedBy({ layer: 'console-iam', action: 'compute:create', reason: 'r', requestable: false })).toBe('No statement of your roles allows it, and what no statement allows is denied.')
    const platform: Denial = { layer: 'platform', action: 'compute:create', reason: 'r', scope: 'compute:write', requestable: true }
    expect(denialTitle(platform)).toBe('The platform does not allow compute:create in this account')
    expect(decidedBy(platform)).toBe("The platform's role for this account has no compute:write scope.")
    expect(decidedBy({ ...platform, scope: undefined })).toBe('The platform refused the account.')
  })

  it('finds the Denial a failed call carried', () => {
    expect(denialOf({ status: 403, denied })).toEqual(denied)
    expect(denialOf({ status: 403, denied: { reason: 'no layer' } })).toBeNull()
    expect(denialOf(new Error('x'))).toBeNull()
    expect(denialOf(null)).toBeNull()
    expect(denialOf('nope')).toBeNull()
  })
})

describe('adminOnly', () => {
  it('lets admins through and greys everyone else with a requestable console-iam denial', () => {
    expect(adminOnly(['viewer', 'admin'], 'iam:create', 'Add a user')).toBe(ALLOWED)
    const d = adminOnly(['viewer', 'operator'], 'iam:create', 'Add a user')
    expect(d.allowed).toBe(false)
    expect(d.denied).toMatchObject({ layer: 'console-iam', action: 'iam:create', role: 'operator, viewer', requestable: true, suggestedRole: 'admin' })
    expect(d.denied?.reason).toBe('Only the admin role of this account can add a user; you hold operator, viewer')
    expect(adminOnly([], 'iam:delete', 'Remove a user').denied?.reason).toBe('Only the admin role of this account can remove a user; you hold no role here')
  })
})
