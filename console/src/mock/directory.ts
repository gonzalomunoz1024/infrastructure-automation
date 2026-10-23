import type { AccessKey, AccessKeyCreated, AccessRequest, AccessRequestInput, AccountSummary, DemoPerson, Denial, DenialLayer, IamGroup, IamGroupInput, IamPolicy, IamRole, IamStatement, IamUser, Quota, QuotaRequest, QuotaRequestInput } from '../lib/types'

/*
 * The mock's identity directory, seeded as the identity service's demo seeds it (and as the BFF's
 * demo stand-in and the other mocks know it): two accounts, the seven people of the demo identity
 * provider (noah has no account), the four managed roles with the identity service's managed
 * policies, the four managed groups every account is born with (owners, members, viewers,
 * auditors) and a custom approver role held by a group of approvers, access keys, quotas, quota
 * requests and access requests. It also decides the way the identity service does: the
 * statements of the person's roles (roles by name, policies in role order, statements in order),
 * the first matching deny wins, else the first matching allow, else a default deny that names the
 * weakest managed role that would allow it; then the platform's own role for the account, so a
 * refusal names the layer and the statement that decided.
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
  { accountId: '123456789012', name: 'Compute', owningTeam: 'team-compute', status: 'active', defaultRegion: DIRECTORY_REGION, platformServiceAccountId: 'svc-compute', platformScopes: ['compute:*', 'platform:*', 'storage:*', 'data:read', 'audit:*'], createdAt: '2026-09-01T09:00:00Z' },
  { accountId: '210987654321', name: 'Data', owningTeam: 'team-data', status: 'active', defaultRegion: DIRECTORY_REGION, platformServiceAccountId: 'svc-data', platformScopes: ['data:*', 'compute:read', 'platform:read', 'storage:read', 'audit:*'], createdAt: '2026-09-03T09:00:00Z' },
]

/** The people of the demo identity provider. The console's user ids are their short names here; the BFF's and the identity service's are `user_<name>`, which the pages never parse. */
export const PEOPLE: DirectoryPerson[] = [
  { id: 'gonzalo', name: 'Gonzalo Munoz', email: 'gonzalo@example.com' },
  { id: 'dana', name: 'Dana Reyes', email: 'dana@example.com' },
  { id: 'mateo', name: 'Mateo Silva', email: 'mateo@example.com' },
  { id: 'raj', name: 'Raj Patel', email: 'raj@example.com' },
  { id: 'ana', name: 'Ana Costa', email: 'ana@example.com' },
  { id: 'mia', name: 'Mia Chen', email: 'mia@example.com' },
  { id: 'noah', name: 'Noah Berg', email: 'noah@example.com' },
]

/* ----------------------------------------------------------------------------
   The managed roles and policies (the identity service's ManagedIam), and the approver role
   ------------------------------------------------------------------------- */

export const ADMIN_ROLE = 'account-admin'
export const APPROVER_ROLE = 'approver'

/** The managed roles from the least to the most permissive: the order a suggested role is searched in. */
export const MANAGED_ORDER = ['viewer', 'auditor', 'operator', ADMIN_ROLE] as const

/** The managed group that carries each managed role, which an approved request joins. */
export const MANAGED_GROUP: Record<string, string> = { [ADMIN_ROLE]: 'owners', operator: 'members', viewer: 'viewers', auditor: 'auditors' }

/** Every resource of every service in the account: the managed policies' resource. */
export function everythingIn(accountId: string): string {
  return `wfrn:${DIRECTORY_PARTITION}:*:*:${accountId}:*`
}

/** The wfrn of an IAM thing: `wfrn:<partition>:iam::<account>:<kind>/<id>` (no region: identity is region-less). */
export function iamResource(accountId: string, kind: string, id = '*'): string {
  return `wfrn:${DIRECTORY_PARTITION}:iam::${accountId}:${kind}/${id}`
}

const allow = (sid: string, actions: string[], resources: string[], notActions?: string[]): IamStatement => ({ sid, effect: 'allow', actions, ...(notActions ? { notActions } : {}), resources })

/** The policies every account is born with, and its approver policy, as the identity service writes them. */
export function policiesFor(accountId: string): IamPolicy[] {
  const all = [everythingIn(accountId)]
  const born = ACCOUNTS.find((a) => a.accountId === accountId)?.createdAt ?? '2026-09-01T09:00:00Z'
  const managed = (policyId: string, description: string, statements: IamStatement[]): IamPolicy => ({ policyId, name: policyId, description, builtIn: true, version: 1, updatedAt: born, statements })
  return [
    // a person always sees their own access requests; reading the account's is for those who decide them, auditors and admins
    managed('managed-viewer', 'The viewer role’s policy: every read, no change', [allow('reads', ['*:get', '*:list', '*:observe', '*:history', 'search:query', 'cost:get', 'cost:list', 'access-request:create', 'quota:request-increase'], all, ['access-request:get', 'access-request:list'])]),
    managed('managed-operator', 'The operator role’s policy: everything but deletes and IAM', [
      allow('changes', ['*:*'], all, ['*:delete', 'iam:*', 'quota:set', 'account:update', 'access-request:get', 'access-request:list', 'access-request:decide']),
      allow('iam-reads', ['iam:get', 'iam:list'], all),
    ]),
    managed('managed-account-admin', 'The account-admin role’s policy: everything', [allow('everything', ['*:*'], all)]),
    managed('managed-auditor', 'The auditor role’s policy: every read and the audit trail, no change', [allow('reads', ['*:get', '*:list', '*:observe', '*:history', 'search:query', 'cost:get', 'cost:list'], all), allow('audit', ['audit:*'], all)]),
    { policyId: APPROVER_ROLE, name: APPROVER_ROLE, description: 'Reads and decides the account’s access requests', builtIn: false, version: 1, updatedAt: born, statements: [allow('approve', ['access-request:get', 'access-request:list', 'access-request:decide'], [iamResource(accountId, 'access-request')])] },
  ]
}

export const ROLES: IamRole[] = [
  { roleId: 'viewer', name: 'viewer', description: 'Reads everything in the account', policies: ['managed-viewer'], builtIn: true, version: 1 },
  { roleId: 'operator', name: 'operator', description: 'Reads and every change that is not a delete, no IAM', policies: ['managed-operator'], builtIn: true, version: 1 },
  { roleId: ADMIN_ROLE, name: ADMIN_ROLE, description: 'Everything in the account, IAM and deletes included', policies: ['managed-account-admin'], builtIn: true, version: 1 },
  { roleId: 'auditor', name: 'auditor', description: 'Reads everything and the audit trail', policies: ['managed-auditor'], builtIn: true, version: 1 },
  { roleId: APPROVER_ROLE, name: APPROVER_ROLE, description: 'Decides access requests', policies: [APPROVER_ROLE], builtIn: false, version: 1 },
]

/** the strongest first, for a person's roles; a custom role after the managed ones */
const ROLE_RANK: Record<string, number> = { viewer: 1, auditor: 2, operator: 3, [ADMIN_ROLE]: 4 }

interface GroupSeed {
  name: string
  description: string
  roles: string[]
  members: string[]
  idpGroups?: string[]
  /** the managed groups: they keep their names, and owners can be neither deleted nor left without an admin */
  builtIn?: boolean
}

/** The managed groups every account is born with (the owning team joins members at sign-in), and the account's approvers. */
function groupSeeds(owningTeam: string, members: { owners: string[]; members: string[]; viewers: string[]; auditors: string[] }, approvers: { name: string; members: string[] }): GroupSeed[] {
  return [
    { name: 'owners', description: 'The account’s administrators', roles: [ADMIN_ROLE], members: members.owners, builtIn: true },
    { name: 'members', description: 'The owning team: operators', roles: ['operator'], members: members.members, idpGroups: [owningTeam], builtIn: true },
    { name: 'viewers', description: 'Read-only users', roles: ['viewer'], members: members.viewers, builtIn: true },
    { name: 'auditors', description: 'Auditors', roles: ['auditor'], members: members.auditors, builtIn: true },
    { name: approvers.name, description: 'Approves access requests', roles: [APPROVER_ROLE], members: approvers.members },
  ]
}

const GROUP_SEEDS: Record<string, GroupSeed[]> = {
  '123456789012': groupSeeds('team-compute', { owners: ['gonzalo'], members: ['dana', 'mateo'], viewers: ['mia'], auditors: [] }, { name: 'compute-approvers', members: ['dana', 'mia'] }),
  '210987654321': groupSeeds('team-data', { owners: ['gonzalo'], members: ['raj', 'ana'], viewers: ['ana'], auditors: [] }, { name: 'data-approvers', members: ['raj'] }),
}

/** the quota of every `resources.<type>` and of the account's access keys, as the identity service defaults them */
const DEFAULT_RESOURCES_LIMIT = 100
const DEFAULT_ACCESS_KEYS_LIMIT = 50
/** a pending access request expires after this long */
const EXPIRY_MS = 30 * 86_400_000

export interface Directory {
  accounts: DirectoryAccount[]
  people: DirectoryPerson[]
  /** by account */
  groups: Record<string, IamGroup[]>
  roles: IamRole[]
  /** by account: each account holds its own policies */
  policies: Record<string, IamPolicy[]>
  /** by account */
  accessKeys: Record<string, AccessKey[]>
  /** by account, as stored: a pending request older than 30 days is served `expired` */
  accessRequests: Record<string, AccessRequest[]>
  /** by account: limits the platform team raised above the default, by quota key */
  quotaOverrides: Record<string, Record<string, number>>
  /** by account */
  quotaRequests: Record<string, QuotaRequest[]>
  /** `<account>/<user>` → version, bumped by every change to the user's groups */
  userVersions: Record<string, number>
  counters: { groups: number; keys: number; requests: number; quotaRequests: number }
}

export function buildDirectory(): Directory {
  const groups: Record<string, IamGroup[]> = {}
  for (const [accountId, seeds] of Object.entries(GROUP_SEEDS)) groups[accountId] = seeds.map((g, i) => ({ groupId: `grp_${accountId.slice(-4)}_${String(i + 1).padStart(2, '0')}`, name: g.name, description: g.description, roles: [...g.roles], members: [...g.members], idpGroups: g.idpGroups ? [...g.idpGroups] : undefined, builtIn: g.builtIn, version: 1 }))
  return {
    accounts: ACCOUNTS.map((a) => ({ ...a, platformScopes: [...a.platformScopes] })),
    people: PEOPLE.map((p) => ({ ...p })),
    groups,
    roles: ROLES.map((r) => ({ ...r, policies: [...(r.policies ?? [])] })),
    policies: Object.fromEntries(ACCOUNTS.map((a) => [a.accountId, policiesFor(a.accountId)])),
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
          requester: { userId: 'dana', email: 'dana@example.com', name: 'Dana Reyes' },
          action: 'compute:create',
          resource: 'wfrn:wf:compute:global:123456789012:vm/*',
          role: 'operator',
          reason: 'Joining the payments on-call rotation; I need to build replacement machines during incidents.',
          status: 'approved',
          requestedAt: '2026-10-08T14:20:00Z',
          decidedAt: '2026-10-08T16:02:00Z',
          decidedBy: 'gonzalo',
          comment: 'Welcome to the rotation.',
          approvers: ['gonzalo', 'mia'],
          layer: 'console-iam',
        },
        {
          // nobody decided it within 30 days, so it is served expired
          requestId: 'areq_002',
          accountId: '123456789012',
          requester: { userId: 'mateo', email: 'mateo@example.com', name: 'Mateo Silva' },
          action: 'compute:delete',
          resource: 'wfrn:wf:compute:global:123456789012:vm/*',
          role: ADMIN_ROLE,
          reason: 'Clearing out the load-test machines after the September run.',
          status: 'pending',
          requestedAt: '2026-09-15T10:00:00Z',
          approvers: ['dana', 'gonzalo', 'mia'],
          layer: 'console-iam',
        },
      ],
      '210987654321': [],
    },
    quotaOverrides: { '123456789012': {}, '210987654321': {} },
    quotaRequests: { '123456789012': [], '210987654321': [] },
    userVersions: {},
    counters: { groups: 20, keys: 10, requests: 2, quotaRequests: 0 },
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

function sortRoles(roles: Iterable<string>): string[] {
  return [...new Set(roles)].sort((a, b) => (ROLE_RANK[b] ?? 0) - (ROLE_RANK[a] ?? 0) || a.localeCompare(b))
}

/** The roles a person holds in an account: the union of the roles of their groups, the strongest managed role first. */
export function rolesOf(dir: Directory, accountId: string, userId: string): string[] {
  return sortRoles(groupsOf(dir, accountId, userId).flatMap((g) => g.roles ?? []))
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

/** The people holding account-admin in the account. */
export function adminsOf(dir: Directory, accountId: string): string[] {
  const out = new Set<string>()
  for (const g of dir.groups[accountId] ?? []) if (g.roles?.includes(ADMIN_ROLE)) for (const m of g.members ?? []) out.add(m)
  return [...out].sort()
}

/** Whom an access request goes to: the members of the groups whose roles allow deciding one, sorted, except the requester, who never decides their own. */
export function approversOf(dir: Directory, accountId: string, requesterId?: string): string[] {
  const resource = iamResource(accountId, 'access-request')
  const out = new Set<string>()
  for (const g of dir.groups[accountId] ?? []) {
    if (evaluateRoles(dir, accountId, g.roles ?? [], 'access-request:decide', resource).effect !== 'allow') continue
    for (const m of g.members ?? []) if (m !== requesterId) out.add(m)
  }
  return [...out].sort()
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

/** The people the demo identity provider lists, with where they belong (none, for noah). */
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
  via?: { policyId: string; statement?: string; role?: string }
}

/** `*` matches any run of characters, none included; the rest is literal. */
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

/** Whether a statement speaks about the action (its notActions carved out) and the resource. */
export function statementMatches(s: IamStatement, action: string, resource: string): boolean {
  if (!s.actions.some((a) => matchesAction(a, action))) return false
  if (s.notActions?.some((a) => matchesAction(a, action))) return false
  return s.resources.some((r) => matchesGlob(r, resource))
}

interface Bound {
  role: string
  policyId: string
  statement: IamStatement
}

/** The statements of the roles, as the identity service orders them: roles by name, policies in role order, statements in order. */
function statementsOf(dir: Directory, accountId: string, roles: readonly string[]): Bound[] {
  const policies = dir.policies[accountId] ?? []
  const out: Bound[] = []
  for (const role of [...new Set(roles)].sort()) {
    const r = dir.roles.find((x) => x.roleId === role || x.name === role)
    for (const id of r?.policies ?? []) {
      const p = policies.find((x) => x.policyId === id || x.name === id)
      for (const s of p?.statements ?? []) out.push({ role, policyId: p!.policyId, statement: s })
    }
  }
  return out
}

/** The policies the roles carry in the account. */
export function policiesOfRoles(dir: Directory, accountId: string, roles: readonly string[]): IamPolicy[] {
  const ids = new Set(statementsOf(dir, accountId, roles).map((b) => b.policyId))
  return (dir.policies[accountId] ?? []).filter((p) => ids.has(p.policyId))
}

/** The console-iam layer alone: the first matching deny, else the first matching allow, else nothing (a default deny). */
export function evaluateRoles(dir: Directory, accountId: string, roles: readonly string[], action: string, resource: string): { effect: 'allow' | 'deny' | null; role?: string; policyId?: string; statement?: string } {
  const bound = statementsOf(dir, accountId, roles)
  const deny = bound.find((b) => b.statement.effect === 'deny' && statementMatches(b.statement, action, resource))
  if (deny) return { effect: 'deny', role: deny.role, policyId: deny.policyId, statement: deny.statement.sid }
  const allowed = bound.find((b) => b.statement.effect === 'allow' && statementMatches(b.statement, action, resource))
  if (allowed) return { effect: 'allow', role: allowed.role, policyId: allowed.policyId, statement: allowed.statement.sid }
  return { effect: null }
}

/** The weakest managed role whose statements allow the action, for Request access. */
export function suggestedRoleFor(dir: Directory, accountId: string, action: string, resource: string): string | undefined {
  return MANAGED_ORDER.find((r) => evaluateRoles(dir, accountId, [r], action, resource).effect === 'allow')
}

/** The services the console's identity service answers for itself: the platform's role for the account never decides them. */
const CONSOLE_SERVICES = new Set(['iam', 'access-request', 'quota', 'account'])

/** The platform scope an action needs: reads need `read`, deletions `admin`, everything else `write`. */
export function platformScopeOf(action: string): string {
  const [service, verb = ''] = action.split(':')
  const kind = ['list', 'get', 'read', 'describe', 'observe', 'history', 'query'].includes(verb) ? 'read' : ['delete', 'destroy', 'purge'].includes(verb) ? 'admin' : 'write'
  return `${service}:${kind}`
}

export function platformAllows(account: DirectoryAccount, action: string): boolean {
  if (CONSOLE_SERVICES.has(action.split(':')[0])) return true
  const need = platformScopeOf(action)
  return account.platformScopes.some((s) => matchesAction(s, need))
}

export interface AuthorizeContext {
  accountId: string
  userId: string
}

/**
 * The decision for one action on one resource (a wfrn or a pattern): the account's statements
 * first (the first matching deny wins, then the first allow, else denied by default), then the
 * platform's role for the account. A refusal names the layer, the statement when one decided, the
 * strongest role the person holds, and the weakest managed role that would allow it.
 */
export function authorize(dir: Directory, ctx: AuthorizeContext, action: string, resource: string): Decision {
  const account = accountOf(dir, ctx.accountId)
  if (!account) return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `account ${ctx.accountId} unknown`, requestable: false } }
  const roles = rolesOf(dir, ctx.accountId, ctx.userId)
  if (!roles.length) return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `not a member of ${account.name}`, requestable: false } }
  const verdict = evaluateRoles(dir, ctx.accountId, roles, action, resource)
  const suggested = suggestedRoleFor(dir, ctx.accountId, action, resource)
  const requestable = !!suggested && !roles.includes(suggested)
  if (verdict.effect === 'deny') {
    const policy = (dir.policies[ctx.accountId] ?? []).find((p) => p.policyId === verdict.policyId)
    return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `denied by policy ${policy?.name ?? verdict.policyId}, statement ${verdict.statement ?? '(unnamed)'}`, role: verdict.role, policyId: verdict.policyId, statement: verdict.statement, requestable, suggestedRole: suggested } }
  }
  if (verdict.effect === null) {
    return { allowed: false, denied: { layer: 'console-iam', action, resource, reason: `no policy allows ${action} on ${resource}`, role: roles[0], requestable, suggestedRole: suggested } }
  }
  if (!platformAllows(account, action)) {
    const scope = platformScopeOf(action)
    return { allowed: false, denied: { layer: 'platform', action, resource, reason: `The platform's role for ${account.name} (${account.platformServiceAccountId}) has no ${scope} scope`, role: roles[0], scope, serviceAccount: account.platformServiceAccountId, requestable: true } }
  }
  return { allowed: true, via: { policyId: verdict.policyId!, statement: verdict.statement, role: verdict.role } }
}

/* ----------------------------------------------------------------------------
   Changes: users, groups, access keys, quotas, access requests
   ------------------------------------------------------------------------- */

/** A refusal of the identity service: the status and its words, and for some 409s what it is about (the body's `detail`). */
export class DirectoryError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly detail?: unknown,
  ) {
    super(message)
    this.name = 'DirectoryError'
  }
}

/** The same access request is pending: the identity service answers 409 with that request as the body. */
export class PendingRequestError extends Error {
  constructor(readonly request: AccessRequest) {
    super(`the same request is pending: ${request.requestId}`)
    this.name = 'PendingRequestError'
  }
}

export const OWNERS_GROUP = 'owners'

function groupByIdOrName(dir: Directory, accountId: string, idOrName: string): IamGroup | undefined {
  return (dir.groups[accountId] ?? []).find((g) => g.groupId === idOrName || g.name === idOrName)
}

function roleIdOf(dir: Directory, idOrName: string): string | undefined {
  return dir.roles.find((r) => r.roleId === idOrName || r.name === idOrName)?.roleId
}

/** The guardrail: a change that would leave the owners group without a member is a 409. */
function guardOwners(dir: Directory, accountId: string, leaving: readonly string[]) {
  const owners = groupByIdOrName(dir, accountId, OWNERS_GROUP)
  const members = owners?.members ?? []
  if (members.length && members.every((m) => leaving.includes(m))) throw new DirectoryError(409, 'the owners group keeps at least one active member')
}

/** Adds a person to the account by putting them in groups (viewers, unless told); a person nobody knows is created from the email. */
export function addUser(dir: Directory, accountId: string, input: { userId?: string; email?: string; name?: string; groups?: string[] }): IamUser {
  const email = input.email?.trim().toLowerCase()
  let person = input.userId ? personOf(dir, input.userId) : email ? dir.people.find((p) => p.email.toLowerCase() === email) : undefined
  if (!person) {
    if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new DirectoryError(400, 'email is an email address')
    const id = (input.userId?.trim() || email.split('@')[0]).toLowerCase().replace(/[^a-z0-9.-]+/g, '-')
    if (personOf(dir, id)) throw new DirectoryError(409, `the id ${id} is in use`)
    person = { id, email, name: input.name?.trim() || email.split('@')[0] }
    dir.people.push(person)
  }
  const wanted = input.groups?.length ? input.groups : [groupByIdOrName(dir, accountId, MANAGED_GROUP.viewer) ? MANAGED_GROUP.viewer : ((dir.groups[accountId] ?? []).find((g) => g.roles?.includes('viewer'))?.name ?? MANAGED_GROUP.viewer)]
  for (const name of wanted) {
    const g = groupByIdOrName(dir, accountId, name)
    if (!g) throw new DirectoryError(400, `'${name}' is no group of this account`)
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
  if (!current) throw new DirectoryError(404, `no user ${userId}`)
  if (ifMatch !== undefined && ifMatch !== current.version) throw new DirectoryError(409, `${userId} moved on; read it again (version ${current.version})`)
  if (input.groups) {
    for (const name of input.groups) if (!groupByIdOrName(dir, accountId, name)) throw new DirectoryError(400, `'${name}' is no group of this account`)
    if (input.groups.length === 0) throw new DirectoryError(409, `${userId} would be left in no group; remove them instead`)
    const owners = groupByIdOrName(dir, accountId, OWNERS_GROUP)
    if (owners?.members?.includes(userId) && !input.groups.some((n) => n === owners.groupId || n === owners.name)) guardOwners(dir, accountId, [userId])
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
  return userOf(dir, accountId, userId)!
}

export function removeUser(dir: Directory, accountId: string, userId: string) {
  if (!userOf(dir, accountId, userId)) throw new DirectoryError(404, `no user ${userId}`)
  guardOwners(dir, accountId, [userId])
  for (const g of dir.groups[accountId] ?? []) {
    if (g.members?.includes(userId)) {
      g.members = g.members.filter((m) => m !== userId)
      g.version++
    }
  }
  // a removed user's keys are revoked
  for (const k of dir.accessKeys[accountId] ?? []) if (k.userId === userId) k.status = 'revoked'
  bumpUser(dir, accountId, userId)
}

const GROUP_NAME = /^[a-z0-9-]{2,64}$/

export function createGroup(dir: Directory, accountId: string, input: IamGroupInput): IamGroup {
  const name = input.name?.trim().toLowerCase() ?? ''
  if (!GROUP_NAME.test(name)) throw new DirectoryError(400, 'name is 2 to 64 lower-case letters, digits and dashes')
  if (groupByIdOrName(dir, accountId, name)) throw new DirectoryError(409, `a group named ${name} exists`)
  const roles = (input.roles ?? []).map((r) => roleIdOf(dir, r) ?? r)
  for (const r of roles) if (!dir.roles.some((x) => x.roleId === r)) throw new DirectoryError(400, `'${r}' is no role of this account`)
  for (const m of input.members ?? []) if (!isMember(dir, accountId, m)) throw new DirectoryError(400, `'${m}' is no user of this account`)
  const group: IamGroup = { groupId: `grp_${accountId.slice(-4)}_${String(++dir.counters.groups).padStart(2, '0')}`, name, description: input.description?.trim(), roles, members: [...(input.members ?? [])], idpGroups: input.idpGroups?.length ? [...input.idpGroups] : undefined, builtIn: false, version: 1 }
  dir.groups[accountId] = [...(dir.groups[accountId] ?? []), group]
  for (const m of group.members ?? []) bumpUser(dir, accountId, m)
  return group
}

export function updateGroup(dir: Directory, accountId: string, groupId: string, input: IamGroupInput, ifMatch?: number): IamGroup {
  const g = groupByIdOrName(dir, accountId, groupId)
  if (!g) throw new DirectoryError(404, `no group ${groupId}`)
  if (ifMatch !== undefined && ifMatch !== g.version) throw new DirectoryError(409, 'the group moved on; read it again')
  if (input.name !== undefined && input.name !== g.name) {
    if (g.builtIn) throw new DirectoryError(409, 'a built-in group keeps its name')
    if (!GROUP_NAME.test(input.name)) throw new DirectoryError(400, 'name is 2 to 64 lower-case letters, digits and dashes')
    if (groupByIdOrName(dir, accountId, input.name)) throw new DirectoryError(409, `a group named ${input.name} exists`)
  }
  const roles = input.roles?.map((r) => roleIdOf(dir, r) ?? r)
  if (roles) {
    for (const r of roles) if (!dir.roles.some((x) => x.roleId === r)) throw new DirectoryError(400, `'${r}' is no role of this account`)
    if (g.name === OWNERS_GROUP && !roles.includes(ADMIN_ROLE)) throw new DirectoryError(409, 'the owners group keeps the account-admin role')
  }
  if (input.members) {
    for (const m of input.members) if (!isMember(dir, accountId, m) && !personOf(dir, m)) throw new DirectoryError(400, `'${m}' is no user of this account`)
    if (g.name === OWNERS_GROUP && input.members.length === 0) throw new DirectoryError(409, 'the owners group keeps at least one active member')
  }
  if (input.name !== undefined) g.name = input.name
  if (input.description !== undefined) g.description = input.description
  if (roles) g.roles = roles
  if (input.idpGroups) g.idpGroups = [...input.idpGroups]
  if (input.members) {
    const touched = new Set([...(g.members ?? []), ...input.members])
    g.members = [...input.members]
    for (const m of touched) bumpUser(dir, accountId, m)
  }
  g.version++
  return g
}

export function removeGroup(dir: Directory, accountId: string, groupId: string) {
  const g = groupByIdOrName(dir, accountId, groupId)
  if (!g) throw new DirectoryError(404, `no group ${groupId}`)
  if (g.name === OWNERS_GROUP) throw new DirectoryError(409, 'the owners group cannot be deleted')
  dir.groups[accountId] = (dir.groups[accountId] ?? []).filter((x) => x !== g)
  for (const m of g.members ?? []) bumpUser(dir, accountId, m)
}

/* access keys: a person manages their own; at most two usable ones each */

export const MAX_USABLE_KEYS = 2

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

/** A key as served: one past its expiry is `expired`. */
export function servedKey(k: AccessKey, now = Date.now()): AccessKey {
  return k.status !== 'revoked' && k.expiresAt && Date.parse(k.expiresAt) <= now ? { ...k, status: 'expired' } : k
}

function usable(k: AccessKey, now: number): boolean {
  const s = servedKey(k, now).status
  return s === 'active' || s === 'rotating'
}

export function accessKeysOf(dir: Directory, accountId: string, now = Date.now()): AccessKey[] {
  return (dir.accessKeys[accountId] ?? []).map((k) => servedKey(k, now))
}

export function createAccessKey(dir: Directory, accountId: string, userId: string, input: { description?: string; expiresAt?: string }, now = Date.now()): AccessKeyCreated {
  if (!userOf(dir, accountId, userId)) throw new DirectoryError(404, `no user ${userId}`)
  const description = input.description?.trim() || undefined
  if (description && description.length > 128) throw new DirectoryError(400, 'description is at most 128 characters')
  if (input.expiresAt !== undefined && !(Date.parse(input.expiresAt) > now)) throw new DirectoryError(400, 'expiresAt is in the future')
  const held = (dir.accessKeys[accountId] ?? []).filter((k) => k.userId === userId && usable(k, now)).length
  if (held >= MAX_USABLE_KEYS) throw new DirectoryError(409, `a user has at most ${MAX_USABLE_KEYS} active access keys`)
  const key: AccessKey = { keyId: `ck_${ulidLike(++dir.counters.keys, now)}`, userId, description, status: 'active', createdAt: new Date(now).toISOString(), expiresAt: input.expiresAt }
  dir.accessKeys[accountId] = [key, ...(dir.accessKeys[accountId] ?? [])]
  return { ...key, secretPart: secretPart() }
}

export function accessKeyOf(dir: Directory, accountId: string, keyId: string, now = Date.now()): AccessKey | undefined {
  const k = (dir.accessKeys[accountId] ?? []).find((x) => x.keyId === keyId)
  return k && servedKey(k, now)
}

/** Revoking a revoked key changes nothing. */
export function revokeAccessKey(dir: Directory, accountId: string, keyId: string) {
  const key = (dir.accessKeys[accountId] ?? []).find((k) => k.keyId === keyId)
  if (!key) throw new DirectoryError(404, `no access key ${keyId}`)
  key.status = 'revoked'
}

/** A new secret for the same key id; the previous one keeps working for a day. */
export function rotateAccessKey(dir: Directory, accountId: string, keyId: string, now = Date.now()): AccessKeyCreated {
  const key = (dir.accessKeys[accountId] ?? []).find((k) => k.keyId === keyId)
  if (!key) throw new DirectoryError(404, `no access key ${keyId}`)
  if (!usable(key, now)) throw new DirectoryError(409, `the key is ${servedKey(key, now).status}`)
  key.status = 'rotating'
  return { ...key, secretPart: secretPart(), graceUntil: new Date(now + 86_400_000).toISOString() }
}

/* quotas: resources.<type> (one per platform resource type, by its name), jobs-per-minute (the platform's bucket) and access-keys; the platform team decides increases */

export const JOBS_PER_MINUTE = 'jobs-per-minute'
export const ACCESS_KEYS_QUOTA = 'access-keys'
const RESOURCES_PREFIX = 'resources.'
const QUOTA_KEY = /^[a-z0-9.-]{1,64}$/

export function isQuotaKey(key: string): boolean {
  return QUOTA_KEY.test(key) && (key === JOBS_PER_MINUTE || key === ACCESS_KEYS_QUOTA || (key.startsWith(RESOURCES_PREFIX) && key.length > RESOURCES_PREFIX.length))
}

/**
 * The platform team's raises at the mock's start, so no type of the seed holds more than its limit:
 * a type with more resources than the default gets about half as much again, in hundreds.
 */
export function seedQuotaOverrides(dir: Directory, usedByAccount: Record<string, Record<string, number>>) {
  for (const [accountId, used] of Object.entries(usedByAccount)) {
    const overrides = (dir.quotaOverrides[accountId] ??= {})
    for (const [type, n] of Object.entries(used)) {
      const key = `${RESOURCES_PREFIX}${type}`
      if (n > DEFAULT_RESOURCES_LIMIT && overrides[key] === undefined) overrides[key] = Math.ceil((n * 1.5) / 100) * 100
    }
  }
}

function openQuotaRequest(dir: Directory, accountId: string, key: string): QuotaRequest | undefined {
  return (dir.quotaRequests[accountId] ?? []).find((r) => r.quotaKey === key && r.status === 'open')
}

/** One quota as served: the override or the default, the platform's bucket for jobs, the open request. `used` counts the account's live resources of a type, as the state service reports them. */
export function quotaOf(dir: Directory, accountId: string, key: string, usedByType: Record<string, number>, now = Date.now()): Quota {
  const override = dir.quotaOverrides[accountId]?.[key]
  let q: Quota
  if (key === JOBS_PER_MINUTE) q = override !== undefined ? { key, limit: override, burst: override, used: 0, source: 'override' } : { key, limit: 60, burst: 120, used: 0, source: 'platform' }
  else if (key === ACCESS_KEYS_QUOTA) q = { key, limit: override ?? DEFAULT_ACCESS_KEYS_LIMIT, used: (dir.accessKeys[accountId] ?? []).filter((k) => usable(k, now)).length, source: override !== undefined ? 'override' : 'default' }
  else q = { key, limit: override ?? DEFAULT_RESOURCES_LIMIT, used: usedByType[key.slice(RESOURCES_PREFIX.length)] ?? 0, source: override !== undefined ? 'override' : 'default' }
  const open = openQuotaRequest(dir, accountId, key)
  return open ? { ...q, pendingRequestId: open.requestId } : q
}

/** The account's quotas, as the identity service lists them: jobs, access keys, a resources quota for every platform resource type by name, then any other override. */
export function quotasOf(dir: Directory, accountId: string, usedByType: Record<string, number>, types: readonly string[] = Object.keys(usedByType), now = Date.now()): Quota[] {
  const keys = [JOBS_PER_MINUTE, ACCESS_KEYS_QUOTA, ...[...new Set(types)].sort().map((t) => `${RESOURCES_PREFIX}${t}`)]
  for (const k of Object.keys(dir.quotaOverrides[accountId] ?? {}).sort()) if (!keys.includes(k)) keys.push(k)
  return keys.map((k) => quotaOf(dir, accountId, k, usedByType, now))
}

export function quotaRequestOf(dir: Directory, accountId: string, requestId: string): QuotaRequest | undefined {
  return (dir.quotaRequests[accountId] ?? []).find((r) => r.requestId === requestId)
}

/** Asks the platform team for a higher limit: one open request per key. */
export function requestQuotaIncrease(dir: Directory, accountId: string, key: string, requestedBy: string, input: Partial<QuotaRequestInput>, current: Quota, now = Date.now()): QuotaRequest {
  if (!isQuotaKey(key)) throw new DirectoryError(404, `no quota ${key}`)
  const limit = input.limit
  if (typeof limit !== 'number' || !Number.isInteger(limit) || limit < 1) throw new DirectoryError(400, 'limit is an integer of at least 1')
  const reason = typeof input.reason === 'string' ? input.reason : ''
  if (reason.length < 10 || reason.length > 1000) throw new DirectoryError(400, 'reason is 10 to 1000 characters')
  const open = openQuotaRequest(dir, accountId, key)
  if (open) throw new DirectoryError(409, `an increase of ${key} is already requested`, { requestId: open.requestId })
  const request: QuotaRequest = { requestId: `qreq_${String(++dir.counters.quotaRequests).padStart(3, '0')}`, accountId, quotaKey: key, currentLimit: current.limit, requestedLimit: limit, reason, status: 'open', requestedBy, requestedAt: new Date(now).toISOString() }
  dir.quotaRequests[accountId] = [request, ...(dir.quotaRequests[accountId] ?? [])]
  return request
}

/** The requester or an account admin withdraws an open request; a decided one is a 409. */
export function withdrawQuotaRequest(dir: Directory, accountId: string, requestId: string, by: string, now = Date.now()): QuotaRequest {
  const r = quotaRequestOf(dir, accountId, requestId)
  if (!r) throw new DirectoryError(404, `no quota request ${requestId}`)
  if (r.status !== 'open') throw new DirectoryError(409, `the request is ${r.status}`)
  r.status = 'withdrawn'
  r.decidedAt = new Date(now).toISOString()
  r.decidedBy = by
  return r
}

/** The platform team's decision (never the account's own admins): approving sets the limit to the one granted, the ask by default. */
export function decideQuotaRequest(dir: Directory, accountId: string, requestId: string, decision: 'approve' | 'reject', by: string, options: { limit?: number; comment?: string } = {}, now = Date.now()): QuotaRequest {
  const r = quotaRequestOf(dir, accountId, requestId)
  if (!r) throw new DirectoryError(404, `no quota request ${requestId}`)
  if (r.status !== 'open') throw new DirectoryError(409, `the request is ${r.status}`)
  if (decision === 'approve') {
    const granted = options.limit ?? r.requestedLimit
    ;(dir.quotaOverrides[accountId] ??= {})[r.quotaKey] = granted
    r.grantedLimit = granted
  }
  r.status = decision === 'approve' ? 'approved' : 'rejected'
  r.decidedAt = new Date(now).toISOString()
  r.decidedBy = by
  r.comment = options.comment?.trim() || undefined
  return r
}

/* access requests: a member asks for a role; the account's approvers decide */

/** A request as served: one pending for more than 30 days is `expired`. */
export function servedRequest(r: AccessRequest, now = Date.now()): AccessRequest {
  return r.status === 'pending' && now - Date.parse(r.requestedAt) > EXPIRY_MS ? { ...r, status: 'expired' } : r
}

export function accessRequestsOf(dir: Directory, accountId: string, now = Date.now()): AccessRequest[] {
  return (dir.accessRequests[accountId] ?? []).map((r) => servedRequest(r, now))
}

export function accessRequestOf(dir: Directory, accountId: string, requestId: string, now = Date.now()): AccessRequest | undefined {
  const r = (dir.accessRequests[accountId] ?? []).find((x) => x.requestId === requestId)
  return r && servedRequest(r, now)
}

/** `admin` is the account-admin role, as the identity service reads a role named in a request. */
export function canonicalRole(role: string): string {
  const r = role.trim()
  return r === 'admin' ? ADMIN_ROLE : r
}

const ACTION = /^[a-z0-9*][a-z0-9*-]*:[a-z0-9*][a-z0-9*-]*$/
const ROLE_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/

export function createAccessRequest(dir: Directory, accountId: string, requester: IamUser, input: Partial<AccessRequestInput>, now = Date.now()): AccessRequest {
  const action = typeof input.action === 'string' ? input.action.trim() : ''
  if (!ACTION.test(action)) throw new DirectoryError(400, 'action is <service>:<verb>')
  if (input.resource !== undefined && input.resource !== null && !(typeof input.resource === 'string' && input.resource.startsWith('wfrn:'))) throw new DirectoryError(400, 'resource is a wfrn or a wfrn pattern')
  const reason = typeof input.reason === 'string' ? input.reason : ''
  if (!reason.trim() || reason.length > 2000) throw new DirectoryError(400, 'reason is 1 to 2000 characters')
  const named = typeof input.role === 'string' && input.role.trim() ? canonicalRole(input.role) : undefined
  if (named !== undefined && !ROLE_NAME.test(named)) throw new DirectoryError(400, 'role is the name of a role of the account')
  const resource = input.resource || everythingIn(accountId)
  let role = named
  if (!role) {
    const d = authorize(dir, { accountId, userId: requester.userId }, action, resource)
    role = d.allowed ? undefined : d.denied?.suggestedRole
    if (!role) throw new DirectoryError(400, `no managed role would allow ${action} on ${resource} (or it is allowed already); name the role to ask for`)
  }
  if (!dir.roles.some((r) => r.name === role)) throw new DirectoryError(400, `'${role}' is no role of this account`)
  const pending = accessRequestsOf(dir, accountId, now).find((r) => r.status === 'pending' && r.requester.userId === requester.userId && r.role === role)
  if (pending) throw new PendingRequestError(pending)
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
    approvers: approversOf(dir, accountId, requester.userId),
    layer: input.layer ?? 'console-iam',
  }
  dir.accessRequests[accountId] = [request, ...(dir.accessRequests[accountId] ?? [])]
  return request
}

/** The group a granted role lands in: the managed group of a managed role, else the first group holding the role. */
export function groupForRole(dir: Directory, accountId: string, role: string): IamGroup {
  const managed = MANAGED_GROUP[role]
  const group = (managed && groupByIdOrName(dir, accountId, managed)) || (dir.groups[accountId] ?? []).find((g) => g.roles?.includes(roleIdOf(dir, role) ?? role))
  if (!group) throw new DirectoryError(409, `no group of the account holds the role ${role}`)
  return group
}

export function decideAccessRequest(dir: Directory, accountId: string, requestId: string, decision: 'approve' | 'reject', decidedBy: string, comment?: string, now = Date.now()): AccessRequest {
  if (comment !== undefined && comment.length > 2000) throw new DirectoryError(400, 'comment is at most 2000 characters')
  const request = (dir.accessRequests[accountId] ?? []).find((r) => r.requestId === requestId)
  if (!request) throw new DirectoryError(404, `no access request ${requestId}`)
  const status = servedRequest(request, now).status
  if (status !== 'pending') throw new DirectoryError(409, `the request is ${status}`)
  if (decision === 'approve') {
    if (!dir.roles.some((r) => r.name === request.role)) throw new DirectoryError(409, `the role ${request.role} no longer exists`)
    const group = groupForRole(dir, accountId, request.role)
    if (!isMember(dir, accountId, request.requester.userId)) throw new DirectoryError(409, 'the requester is no longer a user of the account')
    if (!group.members?.includes(request.requester.userId)) {
      group.members = [...(group.members ?? []), request.requester.userId]
      group.version++
      bumpUser(dir, accountId, request.requester.userId)
    }
  }
  request.status = decision === 'approve' ? 'approved' : 'rejected'
  request.decidedAt = new Date(now).toISOString()
  request.decidedBy = decidedBy
  request.comment = comment?.trim() || undefined
  return request
}

export type { DenialLayer, IamStatement }
