import type { AccessKey, AccessKeyCreated, AccessRequest, AccessRequestInput, AccountSummary, DemoPerson, Denial, DenialLayer, IamGroup, IamGroupInput, IamPolicy, IamRole, IamStatement, IamUser, Preferences, Quota, SavedView } from '../lib/types'

/*
 * The mock's identity directory: the two demo accounts, the six people the demo identity
 * provider knows (the same ones the BFF's demo profile and the identity service seed), the
 * groups of each account with the managed roles they carry, the four managed roles with their
 * policies, access keys, quotas and access requests. It also decides: `authorize` evaluates the
 * statements the way the identity service does (an explicit deny wins, then any allow, else
 * denied), and then the platform's own role for the account, so a refusal can name the layer
 * and the statement that decided.
 */

export const DIRECTORY_REGION = 'global'
export const DIRECTORY_PARTITION = 'wf'

export interface DirectoryAccount {
  accountId: string
  name: string
  owningTeam: string
  status: 'active' | 'suspended' | 'closed'
  defaultRegion: string
  /** the platform service account the account is bound to (P-03) */
  platformServiceAccountId: string
  /** the scopes that service account holds at the platform: `<service>:read|write|admin|*` */
  platformScopes: string[]
  createdAt: string
}

export interface DirectoryPerson {
  id: string
  name: string
  email: string
}

export const ACCOUNTS: DirectoryAccount[] = [
  { accountId: '123456789012', name: 'Compute', owningTeam: 'team-compute', status: 'active', defaultRegion: DIRECTORY_REGION, platformServiceAccountId: 'svc-compute', platformScopes: ['compute:*', 'platform:*', 'storage:*', 'data:read', 'iam:*', 'audit:*'], createdAt: '2026-09-01T09:00:00Z' },
  { accountId: '210987654321', name: 'Data', owningTeam: 'team-data', status: 'active', defaultRegion: DIRECTORY_REGION, platformServiceAccountId: 'svc-data', platformScopes: ['data:*', 'compute:read', 'platform:read', 'storage:read', 'iam:*', 'audit:*'], createdAt: '2026-09-03T09:00:00Z' },
]

export const PEOPLE: DirectoryPerson[] = [
  { id: 'gonzalo', name: 'Gonzalo Munoz', email: 'gonzalo@example.test' },
  { id: 'dana', name: 'Dana Whitfield', email: 'dana@example.test' },
  { id: 'mateo', name: 'Mateo Rivas', email: 'mateo@example.test' },
  { id: 'raj', name: 'Raj Patel', email: 'raj@example.test' },
  { id: 'ana', name: 'Ana Costa', email: 'ana@example.test' },
  { id: 'mia', name: 'Mia Chen', email: 'mia@example.test' },
]

interface GroupSeed {
  name: string
  description: string
  roles: string[]
  members: string[]
  idpGroups?: string[]
  builtIn?: boolean
}

/** The groups of each account: who is in them and the managed roles they carry. */
const GROUP_SEEDS: Record<string, GroupSeed[]> = {
  '123456789012': [
    { name: 'admin', description: 'Runs the account: people, groups, keys and approvals.', roles: ['admin'], members: ['gonzalo'], builtIn: true },
    { name: 'developer', description: 'Builds and changes the compute estate.', roles: ['operator'], members: ['dana'], idpGroups: ['corp/payments-developers'] },
    { name: 'ops', description: 'Keeps the estate running day to day.', roles: ['operator'], members: ['mateo'], idpGroups: ['corp/payments-ops'] },
    { name: 'team-compute', description: 'Everyone on the compute team sees the account.', roles: ['viewer'], members: ['gonzalo', 'dana', 'mateo'], idpGroups: ['corp/team-compute'] },
    { name: 'team-platform', description: 'The platform team sees the account.', roles: ['viewer'], members: ['gonzalo', 'mia'], idpGroups: ['corp/team-platform'] },
    { name: 'compute-approvers', description: 'Approves the compute workflows that wait for one.', roles: ['viewer'], members: ['mia'] },
    { name: 'platform-approvers', description: 'Approves the platform workflows that wait for one.', roles: ['viewer'], members: ['mia'] },
    { name: 'auditors', description: 'Reads everything, including the trail; changes nothing.', roles: ['auditor'], members: [] },
  ],
  '210987654321': [
    { name: 'admin', description: 'Runs the account: people, groups, keys and approvals.', roles: ['admin'], members: ['gonzalo'], builtIn: true },
    { name: 'developer', description: 'Builds and changes the data estate.', roles: ['operator'], members: ['ana'], idpGroups: ['corp/data-developers'] },
    { name: 'dba', description: 'Runs the databases.', roles: ['operator'], members: ['ana'] },
    { name: 'data-approvers', description: 'Approves the data workflows that wait for one.', roles: ['operator'], members: ['raj'] },
    { name: 'team-data', description: 'Everyone on the data team sees the account.', roles: ['viewer'], members: ['raj', 'ana'], idpGroups: ['corp/team-data'] },
    { name: 'auditors', description: 'Reads everything, including the trail; changes nothing.', roles: ['auditor'], members: [] },
  ],
}

const READ_ACTIONS = ['*:list', '*:get', '*:read']

export const POLICIES: IamPolicy[] = [
  {
    policyId: 'managed-viewer',
    name: 'managed-viewer',
    description: 'Sees every resource and request of the account.',
    builtIn: true,
    version: 1,
    updatedAt: '2026-09-01T09:00:00Z',
    statements: [
      { sid: 'AllowRead', effect: 'allow', actions: READ_ACTIONS, resources: ['*'] },
      { sid: 'AllowOwnKeys', effect: 'allow', actions: ['iam:manage-own-keys'], resources: ['*'] },
    ],
  },
  {
    policyId: 'managed-operator',
    name: 'managed-operator',
    description: 'Everything a viewer sees, plus every change that is not destructive.',
    builtIn: true,
    version: 1,
    updatedAt: '2026-09-01T09:00:00Z',
    statements: [
      { sid: 'AllowChanges', effect: 'allow', actions: ['*:*'], resources: ['*'] },
      { sid: 'DenyDestructive', effect: 'deny', actions: ['*:delete', '*:destroy', '*:purge'], resources: ['*'] },
      { sid: 'DenyIamChanges', effect: 'deny', actions: ['iam:create', 'iam:update', 'iam:delete', 'iam:decide'], resources: ['*'] },
    ],
  },
  {
    policyId: 'managed-admin',
    name: 'managed-admin',
    description: 'Runs the account: people, groups, access keys, deletions and approvals.',
    builtIn: true,
    version: 1,
    updatedAt: '2026-09-01T09:00:00Z',
    statements: [{ sid: 'AllowAll', effect: 'allow', actions: ['*:*'], resources: ['*'] }],
  },
  {
    policyId: 'managed-auditor',
    name: 'managed-auditor',
    description: 'Read-only, including the audit trail and the policy decisions.',
    builtIn: true,
    version: 1,
    updatedAt: '2026-09-01T09:00:00Z',
    statements: [
      { sid: 'AllowRead', effect: 'allow', actions: READ_ACTIONS, resources: ['*'] },
      { sid: 'AllowTrail', effect: 'allow', actions: ['audit:*'], resources: ['*'] },
      { sid: 'AllowOwnKeys', effect: 'allow', actions: ['iam:manage-own-keys'], resources: ['*'] },
    ],
  },
]

export const ROLES: IamRole[] = [
  { roleId: 'viewer', name: 'viewer', description: 'Sees every resource and request of the account.', policies: ['managed-viewer'], builtIn: true, version: 1 },
  { roleId: 'operator', name: 'operator', description: 'Everything a viewer sees, plus every change that is not destructive.', policies: ['managed-operator'], builtIn: true, version: 1 },
  { roleId: 'admin', name: 'admin', description: 'Runs the account: people, groups, access keys, deletions and approvals.', policies: ['managed-admin'], builtIn: true, version: 1 },
  { roleId: 'auditor', name: 'auditor', description: 'Read-only, including the audit trail and the policy decisions.', policies: ['managed-auditor'], builtIn: true, version: 1 },
]

const ROLE_RANK: Record<string, number> = { viewer: 1, auditor: 2, operator: 3, admin: 4 }

export interface Directory {
  accounts: DirectoryAccount[]
  people: DirectoryPerson[]
  /** by account */
  groups: Record<string, IamGroup[]>
  roles: IamRole[]
  policies: IamPolicy[]
  /** by account */
  accessKeys: Record<string, AccessKey[]>
  /** by account */
  accessRequests: Record<string, AccessRequest[]>
  /** `<account>/<user>` → version, bumped by every change to the user's groups */
  userVersions: Record<string, number>
  counters: { groups: number; keys: number; requests: number }
  /** by person: what `/console/v1/preferences` keeps (phase 3) */
  preferences?: Record<string, Preferences>
}

export function buildDirectory(): Directory {
  const groups: Record<string, IamGroup[]> = {}
  for (const [accountId, seeds] of Object.entries(GROUP_SEEDS)) groups[accountId] = seeds.map((g, i) => ({ groupId: `grp_${accountId.slice(-4)}_${String(i + 1).padStart(2, '0')}`, name: g.name, description: g.description, roles: [...g.roles], members: [...g.members], idpGroups: g.idpGroups ? [...g.idpGroups] : undefined, builtIn: g.builtIn, version: 1 }))
  return {
    accounts: ACCOUNTS.map((a) => ({ ...a, platformScopes: [...a.platformScopes] })),
    people: PEOPLE.map((p) => ({ ...p })),
    groups,
    roles: ROLES.map((r) => ({ ...r, policies: [...(r.policies ?? [])] })),
    policies: POLICIES.map((p) => ({ ...p, statements: p.statements.map((s) => ({ ...s, actions: [...s.actions], resources: [...s.resources] })) })),
    accessKeys: {
      '123456789012': [
        { keyId: 'ck_01JB0Q4W2M7N8R9S0T1V2W3X4Y', userId: 'gonzalo', description: 'Terraform runner', status: 'active', createdAt: '2026-10-02T10:15:00Z', lastUsedAt: '2026-10-22T18:40:00Z' },
        { keyId: 'ck_01JB1R5X3N8P9S0T1V2W3X4Y5Z', userId: 'dana', description: 'Laptop CLI', status: 'active', createdAt: '2026-10-09T08:05:00Z', expiresAt: '2027-01-07T08:05:00Z', lastUsedAt: '2026-10-23T06:12:00Z' },
        { keyId: 'ck_01JAZ9P3K2M6N7Q8R9S0T1V2W3', userId: 'mateo', description: 'Old laptop', status: 'revoked', createdAt: '2026-09-12T12:00:00Z', lastUsedAt: '2026-09-30T09:00:00Z' },
      ],
      '210987654321': [{ keyId: 'ck_01JB2S6Y4P9Q0T1V2W3X4Y5Z6A', userId: 'ana', description: 'Backup verifier', status: 'active', createdAt: '2026-10-11T07:30:00Z', lastUsedAt: '2026-10-23T03:00:00Z' }],
    },
    accessRequests: {
      '123456789012': [
        {
          requestId: 'areq_001',
          accountId: '123456789012',
          requester: { userId: 'dana', email: 'dana@example.test', name: 'Dana Whitfield' },
          action: 'compute:create',
          resource: 'wfrn:wf:compute:global:123456789012:vm/*',
          role: 'operator',
          reason: 'Joining the payments on-call rotation; I need to build replacement machines during incidents.',
          status: 'approved',
          requestedAt: '2026-10-08T14:20:00Z',
          decidedAt: '2026-10-08T16:02:00Z',
          decidedBy: 'gonzalo',
          comment: 'Welcome to the rotation.',
          approvers: ['gonzalo'],
          layer: 'console-iam',
        },
      ],
      '210987654321': [],
    },
    userVersions: {},
    counters: { groups: 20, keys: 10, requests: 1 },
  }
}

/* ----------------------------------------------------------------------------
   Lookups
   ------------------------------------------------------------------------- */

export function accountOf(dir: Directory, accountId: string): DirectoryAccount | undefined {
  return dir.accounts.find((a) => a.accountId === accountId)
}

export function personOf(dir: Directory, userId: string): DirectoryPerson | undefined {
  return dir.people.find((p) => p.id === userId)
}

export function groupsOf(dir: Directory, accountId: string, userId: string): IamGroup[] {
  return (dir.groups[accountId] ?? []).filter((g) => g.members?.includes(userId))
}

/** The managed roles a person holds in an account: the union of the roles of their groups, strongest first. */
export function rolesOf(dir: Directory, accountId: string, userId: string): string[] {
  const roles = new Set<string>()
  for (const g of groupsOf(dir, accountId, userId)) for (const r of g.roles ?? []) roles.add(r)
  return [...roles].sort((a, b) => (ROLE_RANK[b] ?? 0) - (ROLE_RANK[a] ?? 0) || a.localeCompare(b))
}

export function isMember(dir: Directory, accountId: string, userId: string): boolean {
  return groupsOf(dir, accountId, userId).length > 0
}

/** The accounts a person belongs to, in the directory's order. */
export function membershipsOf(dir: Directory, userId: string): AccountSummary[] {
  return dir.accounts.filter((a) => isMember(dir, a.accountId, userId)).map((a) => accountSummaryFor(dir, a.accountId, userId))
}

export function accountSummaryFor(dir: Directory, accountId: string, userId: string): AccountSummary {
  const a = accountOf(dir, accountId)
  if (!a) throw new Error(`Unknown account ${accountId}`)
  return { accountId: a.accountId, name: a.name, status: a.status, defaultRegion: a.defaultRegion, owningTeam: a.owningTeam, roles: rolesOf(dir, accountId, userId), groups: groupsOf(dir, accountId, userId).map((g) => g.name) }
}

export function adminsOf(dir: Directory, accountId: string): string[] {
  const out = new Set<string>()
  for (const g of dir.groups[accountId] ?? []) if (g.roles?.includes('admin')) for (const m of g.members ?? []) out.add(m)
  return [...out]
}

function userVersion(dir: Directory, accountId: string, userId: string): number {
  return dir.userVersions[`${accountId}/${userId}`] ?? 1
}

function bumpUser(dir: Directory, accountId: string, userId: string) {
  dir.userVersions[`${accountId}/${userId}`] = userVersion(dir, accountId, userId) + 1
}

const SIGN_INS: Record<string, string> = { gonzalo: '2026-10-23T06:58:00Z', dana: '2026-10-23T06:12:00Z', mateo: '2026-10-22T17:45:00Z', raj: '2026-10-22T09:30:00Z', ana: '2026-10-23T03:05:00Z', mia: '2026-10-21T11:20:00Z' }

export function userOf(dir: Directory, accountId: string, userId: string): IamUser | undefined {
  const p = personOf(dir, userId)
  if (!p || !isMember(dir, accountId, userId)) return undefined
  return { userId: p.id, email: p.email, name: p.name, status: 'active', issuer: 'corp-sso', subject: `corp|${p.id}`, groups: groupsOf(dir, accountId, userId).map((g) => g.name), version: userVersion(dir, accountId, userId), createdAt: accountOf(dir, accountId)?.createdAt, lastSignInAt: SIGN_INS[p.id] }
}

/** Everyone with a group in the account. */
export function usersOf(dir: Directory, accountId: string): IamUser[] {
  return dir.people.map((p) => userOf(dir, accountId, p.id)).filter((u): u is IamUser => !!u)
}

/** The people the demo identity provider lists, with where they belong. */
export function demoPeople(dir: Directory): DemoPerson[] {
  return dir.people.map((p) => ({ id: p.id, name: p.name, email: p.email, memberships: membershipsOf(dir, p.id).map((m) => ({ accountId: m.accountId, accountName: m.name, roles: m.roles ?? [] })) }))
}

/* ----------------------------------------------------------------------------
   Authorization: the statements, then the platform's role for the account
   ------------------------------------------------------------------------- */

export interface Decision {
  allowed: boolean
  denied?: Denial
  /** the statement that allowed it */
  via?: { policyId: string; statement?: string }
}

/** `*` matches any run of characters; the rest is literal. */
export function matchesGlob(pattern: string, value: string): boolean {
  if (pattern === '*') return true
  const re = new RegExp(`^${pattern.split('*').map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*')}$`)
  return re.test(value)
}

/** 'compute:*' matches 'compute:create'; '*:delete' matches 'data:delete'; '*:*' matches everything. */
export function matchesAction(pattern: string, action: string): boolean {
  const [ps, pv] = pattern.split(':')
  const [as, av] = action.split(':')
  return matchesGlob(ps ?? '', as ?? '') && matchesGlob(pv ?? '', av ?? '')
}

interface StatementMatch {
  effect: 'allow' | 'deny'
  policyId: string
  statement?: string
}

/** Every statement of the policies that speaks about the action and the resource, denies first. */
export function matchingStatements(policies: IamPolicy[], action: string, resource: string): StatementMatch[] {
  const out: StatementMatch[] = []
  for (const p of policies) {
    for (const s of p.statements) {
      if (!s.actions.some((a) => matchesAction(a, action))) continue
      if (!s.resources.some((r) => matchesGlob(r, resource))) continue
      out.push({ effect: s.effect, policyId: p.policyId, statement: s.sid })
    }
  }
  return out.sort((a, b) => (a.effect === b.effect ? 0 : a.effect === 'deny' ? -1 : 1))
}

export function policiesOfRoles(dir: Directory, roles: readonly string[]): IamPolicy[] {
  const ids = new Set<string>()
  for (const r of roles) for (const id of dir.roles.find((x) => x.roleId === r || x.name === r)?.policies ?? []) ids.add(id)
  return dir.policies.filter((p) => ids.has(p.policyId))
}

/** The console-iam layer alone: what the roles' statements say about the action. */
export function evaluateRoles(dir: Directory, roles: readonly string[], action: string, resource: string): { effect: 'allow' | 'deny' | null; policyId?: string; statement?: string } {
  const matches = matchingStatements(policiesOfRoles(dir, roles), action, resource)
  const deny = matches.find((m) => m.effect === 'deny')
  if (deny) return { effect: 'deny', policyId: deny.policyId, statement: deny.statement }
  const allow = matches.find((m) => m.effect === 'allow')
  if (allow) return { effect: 'allow', policyId: allow.policyId, statement: allow.statement }
  return { effect: null }
}

/** The weakest managed role whose statements allow the action, for Request access. */
export function suggestedRoleFor(dir: Directory, action: string, resource: string): string | undefined {
  const ordered = [...dir.roles].sort((a, b) => (ROLE_RANK[a.roleId] ?? 9) - (ROLE_RANK[b.roleId] ?? 9))
  return ordered.find((r) => evaluateRoles(dir, [r.roleId], action, resource).effect === 'allow')?.roleId
}

/** The platform scope an action needs: reads need `read`, deletions `admin`, everything else `write`. */
export function platformScopeOf(action: string): string {
  const [service, verb = ''] = action.split(':')
  const kind = ['list', 'get', 'read', 'describe'].includes(verb) ? 'read' : ['delete', 'destroy', 'purge'].includes(verb) ? 'admin' : 'write'
  return `${service}:${kind}`
}

export function platformAllows(account: DirectoryAccount, action: string): boolean {
  const need = platformScopeOf(action)
  return account.platformScopes.some((s) => matchesAction(s, need))
}

export interface AuthorizeContext {
  accountId: string
  userId: string
}

/**
 * The decision for one action on one resource (a wfrn or a pattern): the account's statements
 * first (an explicit deny wins, then any allow, else denied by default), then the platform's role
 * for the account. A refusal names the layer, the statement when one decided, and the managed
 * role that would allow it.
 */
export function authorize(dir: Directory, ctx: AuthorizeContext, action: string, resource: string): Decision {
  const account = accountOf(dir, ctx.accountId)
  if (!account) return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `Unknown account ${ctx.accountId}`, requestable: false } }
  const roles = rolesOf(dir, ctx.accountId, ctx.userId)
  const role = roles[0]
  const verdict = evaluateRoles(dir, roles, action, resource)
  const suggestedRole = suggestedRoleFor(dir, action, resource)
  if (verdict.effect === 'deny') {
    return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `Statement ${verdict.statement ?? '(unnamed)'} of policy ${verdict.policyId} denies ${action}`, role, policyId: verdict.policyId, statement: verdict.statement, requestable: !!suggestedRole && suggestedRole !== role, suggestedRole } }
  }
  if (verdict.effect === null) {
    return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: roles.length ? `No statement of ${roles.length === 1 ? 'the role' : 'the roles'} ${roles.join(', ')} allows ${action}` : `You hold no role in ${account.name}`, role, requestable: !!suggestedRole, suggestedRole } }
  }
  if (!platformAllows(account, action)) {
    const scope = platformScopeOf(action)
    return { allowed: false, denied: { layer: 'platform', action, resource, reason: `The platform's role for ${account.name} (${account.platformServiceAccountId}) has no ${scope} scope`, role, scope, serviceAccount: account.platformServiceAccountId, requestable: true } }
  }
  return { allowed: true, via: { policyId: verdict.policyId!, statement: verdict.statement } }
}

/* ----------------------------------------------------------------------------
   Changes: users, groups, access keys, access requests
   ------------------------------------------------------------------------- */

export class DirectoryError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'DirectoryError'
  }
}

function groupByIdOrName(dir: Directory, accountId: string, idOrName: string): IamGroup | undefined {
  return (dir.groups[accountId] ?? []).find((g) => g.groupId === idOrName || g.name === idOrName)
}

/** Adds a person to the account by putting them in groups; a person nobody knows is created from the email. */
export function addUser(dir: Directory, accountId: string, input: { userId?: string; email?: string; name?: string; groups?: string[] }): IamUser {
  const email = input.email?.trim().toLowerCase()
  let person = input.userId ? personOf(dir, input.userId) : email ? dir.people.find((p) => p.email.toLowerCase() === email) : undefined
  if (!person) {
    if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new DirectoryError(400, 'An email address is needed to add someone')
    const id = (input.userId?.trim() || email.split('@')[0]).toLowerCase().replace(/[^a-z0-9.-]+/g, '-')
    if (personOf(dir, id)) throw new DirectoryError(409, `The id ${id} is in use`)
    person = { id, email, name: input.name?.trim() || email.split('@')[0] }
    dir.people.push(person)
  }
  const wanted = input.groups?.length ? input.groups : [(dir.groups[accountId] ?? []).find((g) => g.roles?.includes('viewer'))?.name ?? 'team']
  for (const name of wanted) {
    const g = groupByIdOrName(dir, accountId, name)
    if (!g) throw new DirectoryError(400, `No group ${name} in this account`)
    if (!g.members?.includes(person.id)) {
      g.members = [...(g.members ?? []), person.id]
      g.version++
    }
  }
  bumpUser(dir, accountId, person.id)
  return userOf(dir, accountId, person.id)!
}

/** Sets the person's groups in the account to exactly the ones given. */
export function updateUser(dir: Directory, accountId: string, userId: string, input: { groups?: string[] }, ifMatch?: number): IamUser {
  const current = userOf(dir, accountId, userId)
  if (!current) throw new DirectoryError(404, `${userId} is not a member of this account`)
  if (ifMatch !== undefined && ifMatch !== current.version) throw new DirectoryError(409, `${userId} changed since you read it (version ${current.version})`)
  if (input.groups) {
    for (const name of input.groups) if (!groupByIdOrName(dir, accountId, name)) throw new DirectoryError(400, `No group ${name} in this account`)
    if (input.groups.length === 0) throw new DirectoryError(409, `${userId} would be left in no group; remove them instead`)
    for (const g of dir.groups[accountId] ?? []) {
      const wanted = input.groups.some((n) => n === g.groupId || n === g.name)
      const has = g.members?.includes(userId) ?? false
      if (wanted && !has) {
        g.members = [...(g.members ?? []), userId]
        g.version++
      } else if (!wanted && has) {
        g.members = (g.members ?? []).filter((m) => m !== userId)
        g.version++
      }
    }
  }
  bumpUser(dir, accountId, userId)
  const after = userOf(dir, accountId, userId)
  if (!after) throw new DirectoryError(409, `${userId} would be left in no group; remove them instead`)
  return after
}

export function removeUser(dir: Directory, accountId: string, userId: string) {
  if (!userOf(dir, accountId, userId)) throw new DirectoryError(404, `${userId} is not a member of this account`)
  if (adminsOf(dir, accountId).length === 1 && adminsOf(dir, accountId)[0] === userId) throw new DirectoryError(409, `${userId} is the account's only admin`)
  for (const g of dir.groups[accountId] ?? []) {
    if (g.members?.includes(userId)) {
      g.members = g.members.filter((m) => m !== userId)
      g.version++
    }
  }
  bumpUser(dir, accountId, userId)
}

export function createGroup(dir: Directory, accountId: string, input: IamGroupInput): IamGroup {
  const name = input.name?.trim().toLowerCase() ?? ''
  if (!/^[a-z0-9-]{2,64}$/.test(name)) throw new DirectoryError(400, 'A group name is 2 to 64 lower-case letters, digits and dashes')
  if (groupByIdOrName(dir, accountId, name)) throw new DirectoryError(409, `A group named ${name} exists`)
  for (const r of input.roles ?? []) if (!dir.roles.some((x) => x.roleId === r)) throw new DirectoryError(400, `No role ${r}`)
  for (const m of input.members ?? []) if (!personOf(dir, m)) throw new DirectoryError(400, `Nobody is called ${m}`)
  const group: IamGroup = { groupId: `grp_${accountId.slice(-4)}_${String(++dir.counters.groups).padStart(2, '0')}`, name, description: input.description?.trim(), roles: [...(input.roles ?? [])], members: [...(input.members ?? [])], idpGroups: input.idpGroups?.length ? [...input.idpGroups] : undefined, version: 1 }
  dir.groups[accountId] = [...(dir.groups[accountId] ?? []), group]
  for (const m of group.members ?? []) bumpUser(dir, accountId, m)
  return group
}

export function updateGroup(dir: Directory, accountId: string, groupId: string, input: IamGroupInput, ifMatch?: number): IamGroup {
  const g = groupByIdOrName(dir, accountId, groupId)
  if (!g) throw new DirectoryError(404, `No group ${groupId}`)
  if (ifMatch !== undefined && ifMatch !== g.version) throw new DirectoryError(409, `${g.name} changed since you read it (version ${g.version})`)
  if (input.name !== undefined && input.name !== g.name) {
    if (g.builtIn) throw new DirectoryError(409, `${g.name} is built in and keeps its name`)
    if (!/^[a-z0-9-]{2,64}$/.test(input.name)) throw new DirectoryError(400, 'A group name is 2 to 64 lower-case letters, digits and dashes')
    g.name = input.name
  }
  if (input.description !== undefined) g.description = input.description
  if (input.roles) {
    for (const r of input.roles) if (!dir.roles.some((x) => x.roleId === r)) throw new DirectoryError(400, `No role ${r}`)
    g.roles = [...input.roles]
  }
  if (input.idpGroups) g.idpGroups = [...input.idpGroups]
  if (input.members) {
    for (const m of input.members) if (!personOf(dir, m)) throw new DirectoryError(400, `Nobody is called ${m}`)
    const touched = new Set([...(g.members ?? []), ...input.members])
    g.members = [...input.members]
    for (const m of touched) bumpUser(dir, accountId, m)
  }
  g.version++
  return g
}

export function removeGroup(dir: Directory, accountId: string, groupId: string) {
  const g = groupByIdOrName(dir, accountId, groupId)
  if (!g) throw new DirectoryError(404, `No group ${groupId}`)
  if (g.builtIn) throw new DirectoryError(409, `${g.name} is built in and cannot be deleted`)
  dir.groups[accountId] = (dir.groups[accountId] ?? []).filter((x) => x !== g)
  for (const m of g.members ?? []) bumpUser(dir, accountId, m)
}

const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

function ulidLike(n: number, now: number): string {
  let out = ''
  let t = now
  for (let i = 0; i < 10; i++) {
    out = ULID_ALPHABET[t % 32] + out
    t = Math.floor(t / 32)
  }
  let r = n * 2654435761 + now
  for (let i = 0; i < 16; i++) {
    r = (r * 1103515245 + 12345) % 2147483648
    out += ULID_ALPHABET[r % 32]
  }
  return out
}

function secretPart(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
  const buf = new Uint8Array(40)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') crypto.getRandomValues(buf)
  else for (let i = 0; i < buf.length; i++) buf[i] = Math.floor(Math.random() * 256)
  return `cs_${Array.from(buf, (b) => chars[b % chars.length]).join('')}`
}

export function createAccessKey(dir: Directory, accountId: string, userId: string, input: { description?: string; expiresAt?: string }, now = Date.now()): AccessKeyCreated {
  if (!userOf(dir, accountId, userId)) throw new DirectoryError(404, `${userId} is not a member of this account`)
  const active = (dir.accessKeys[accountId] ?? []).filter((k) => k.userId === userId && (k.status === 'active' || k.status === 'rotating'))
  if (active.length >= 5) throw new DirectoryError(429, `${userId} already holds 5 active access keys (the account's quota)`)
  if (input.expiresAt && Number.isNaN(Date.parse(input.expiresAt))) throw new DirectoryError(400, 'expiresAt is not a date')
  const key: AccessKey = { keyId: `ck_${ulidLike(++dir.counters.keys, now)}`, userId, description: input.description?.trim() || undefined, status: 'active', createdAt: new Date(now).toISOString(), expiresAt: input.expiresAt }
  dir.accessKeys[accountId] = [key, ...(dir.accessKeys[accountId] ?? [])]
  return { ...key, secretPart: secretPart() }
}

export function accessKeyOf(dir: Directory, accountId: string, keyId: string): AccessKey | undefined {
  return (dir.accessKeys[accountId] ?? []).find((k) => k.keyId === keyId)
}

export function revokeAccessKey(dir: Directory, accountId: string, keyId: string) {
  const key = accessKeyOf(dir, accountId, keyId)
  if (!key) throw new DirectoryError(404, `No access key ${keyId}`)
  key.status = 'revoked'
}

/** A new secret for the same key id; the previous one keeps working for a day. */
export function rotateAccessKey(dir: Directory, accountId: string, keyId: string, now = Date.now()): AccessKeyCreated {
  const key = accessKeyOf(dir, accountId, keyId)
  if (!key) throw new DirectoryError(404, `No access key ${keyId}`)
  if (key.status === 'revoked') throw new DirectoryError(409, `${keyId} is revoked`)
  key.status = 'rotating'
  return { ...key, secretPart: secretPart(), graceUntil: new Date(now + 86_400_000).toISOString() }
}

export function quotasOf(dir: Directory, accountId: string, counts: Record<string, number>): Quota[] {
  const keys = (dir.accessKeys[accountId] ?? []).filter((k) => k.status !== 'revoked').length
  const out: Quota[] = Object.entries(counts).map(([type, used]) => ({ key: `resources.${type}`, limit: type === 'vm' ? 500 : 100, used, source: 'default' as const }))
  out.push({ key: 'jobs-per-minute', limit: 60, burst: 120, used: 0, source: 'platform' }, { key: 'access-keys', limit: 25, used: keys, source: 'default' })
  return out
}

export function accessRequestsOf(dir: Directory, accountId: string): AccessRequest[] {
  return dir.accessRequests[accountId] ?? []
}

export function createAccessRequest(dir: Directory, accountId: string, requester: IamUser, input: AccessRequestInput, now = Date.now()): AccessRequest {
  const action = input.action?.trim()
  if (!action || !/^[a-z0-9*-]+:[a-z0-9*-]+$/.test(action)) throw new DirectoryError(400, 'An action looks like <service>:<verb>')
  const reason = input.reason?.trim() ?? ''
  if (reason.length < 10) throw new DirectoryError(400, 'Say why in at least ten characters; the admins read it')
  if (reason.length > 1000) throw new DirectoryError(400, 'A reason is at most 1000 characters')
  const resource = input.resource?.trim() || `wfrn:${DIRECTORY_PARTITION}:${action.split(':')[0]}:${DIRECTORY_REGION}:${accountId}:*`
  const role = input.role?.trim() || suggestedRoleFor(dir, action, resource) || 'operator'
  if (!dir.roles.some((r) => r.roleId === role)) throw new DirectoryError(400, `No role ${role}`)
  const open = accessRequestsOf(dir, accountId).find((r) => r.status === 'pending' && r.requester.userId === requester.userId && r.action === action && r.role === role)
  if (open) throw new DirectoryError(409, `You already asked for ${role} over ${action} (${open.requestId}); it is still pending`)
  const request: AccessRequest = {
    requestId: `areq_${String(++dir.counters.requests).padStart(3, '0')}`,
    accountId,
    requester: { userId: requester.userId, email: requester.email, name: requester.name },
    action,
    resource,
    role,
    reason,
    status: 'pending',
    requestedAt: new Date(now).toISOString(),
    approvers: adminsOf(dir, accountId),
    layer: input.layer ?? 'console-iam',
  }
  dir.accessRequests[accountId] = [request, ...accessRequestsOf(dir, accountId)]
  return request
}

/** The group a granted role lands in: one that carries exactly that role, else any that carries it, else a new one. */
export function groupForRole(dir: Directory, accountId: string, role: string): IamGroup {
  const groups = dir.groups[accountId] ?? []
  const exact = groups.filter((g) => g.roles?.length === 1 && g.roles[0] === role && !g.builtIn).sort((a, b) => a.name.localeCompare(b.name))
  if (exact.length) return exact[0]
  const any = groups.find((g) => g.roles?.includes(role))
  if (any) return any
  return createGroup(dir, accountId, { name: `${role}s`, description: `People granted the ${role} role through access requests.`, roles: [role], members: [] })
}

export function decideAccessRequest(dir: Directory, accountId: string, requestId: string, decision: 'approve' | 'reject', decidedBy: string, comment?: string, now = Date.now()): AccessRequest {
  const request = accessRequestsOf(dir, accountId).find((r) => r.requestId === requestId)
  if (!request) throw new DirectoryError(404, `No access request ${requestId}`)
  if (request.status !== 'pending') throw new DirectoryError(409, `${requestId} was already ${request.status}`)
  if (decision === 'approve') {
    const group = groupForRole(dir, accountId, request.role)
    if (!group.members?.includes(request.requester.userId)) {
      group.members = [...(group.members ?? []), request.requester.userId]
      group.version++
      bumpUser(dir, accountId, request.requester.userId)
    }
    request.status = 'approved'
  } else {
    request.status = 'rejected'
  }
  request.decidedAt = new Date(now).toISOString()
  request.decidedBy = decidedBy
  request.comment = comment?.trim() || undefined
  return request
}

export type { DenialLayer, IamStatement }

/* ----------------------------------------------------------------------------
   Preferences (phase 3): one object per person, replaced whole, with a version
   ------------------------------------------------------------------------- */

export function preferencesOf(dir: Directory, userId: string): Preferences {
  return dir.preferences?.[userId] ?? { version: 0 }
}

/** Replaces the person's preferences; a stale `version` is a 409, as another tab moved first. */
export function putPreferences(dir: Directory, userId: string, next: unknown): Preferences {
  if (!next || typeof next !== 'object' || Array.isArray(next)) throw new DirectoryError(400, 'Preferences are an object')
  const incoming = next as Preferences
  const current = preferencesOf(dir, userId)
  if (incoming.version !== undefined && incoming.version !== (current.version ?? 0)) throw new DirectoryError(409, `Preferences moved on to version ${current.version ?? 0}; read them again`)
  if (incoming.theme !== undefined && !['system', 'light', 'dark'].includes(incoming.theme)) throw new DirectoryError(400, 'theme is system, light or dark')
  if (incoming.columns !== undefined && (typeof incoming.columns !== 'object' || incoming.columns === null || Array.isArray(incoming.columns))) throw new DirectoryError(400, 'columns maps a type key to column names')
  if (incoming.savedViews !== undefined && (typeof incoming.savedViews !== 'object' || incoming.savedViews === null || Array.isArray(incoming.savedViews))) throw new DirectoryError(400, 'savedViews maps a type key to views')
  const problem = incoming.savedViews ? savedViewsProblem(incoming.savedViews) : null
  if (problem) throw new DirectoryError(400, problem)
  const saved: Preferences = { ...incoming, version: (current.version ?? 0) + 1 }
  dir.preferences = { ...(dir.preferences ?? {}), [userId]: saved }
  return saved
}

/** The BFF's rules for saved views: ids `v_…`, names of 1 to 80 characters unique in a type ignoring case, 50 a type, 50 filters a view of 1,024 characters at most, a creation time. */
function savedViewsProblem(byType: Record<string, unknown>): string | null {
  for (const [type, views] of Object.entries(byType)) {
    if (!Array.isArray(views)) return `savedViews.${type} is a list`
    if (views.length > 50) return `savedViews.${type} keeps 50 views at most`
    const names = new Set<string>()
    for (const v of views as Partial<SavedView>[]) {
      if (typeof v?.id !== 'string' || !/^v_[A-Za-z0-9_-]{1,64}$/.test(v.id)) return `A saved view's id is v_ and up to 64 letters, digits, _ or -`
      if (typeof v.name !== 'string' || v.name.trim().length === 0 || v.name.length > 80) return `A saved view's name is 1 to 80 characters`
      const key = v.name.trim().toLowerCase()
      if (names.has(key)) return `Two views of ${type} are called "${v.name}"`
      names.add(key)
      if (!Array.isArray(v.filters) || v.filters.length > 50) return `A saved view keeps 50 filters at most`
      if (v.filters.some((f) => typeof f?.value !== 'string' || f.value.length > 1024)) return `A saved filter's value is 1,024 characters at most`
      if (typeof v.createdAt !== 'string' || Number.isNaN(Date.parse(v.createdAt))) return `A saved view carries when it was created`
    }
  }
  return null
}
