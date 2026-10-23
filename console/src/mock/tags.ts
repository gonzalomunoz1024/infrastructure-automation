import type { Problem } from '@iaf/console-kit'
import { serviceOf } from '../lib/descriptors'
import { applyProblems, changeProblems, fullMatch, groupChangeProblems, newGroupProblems, policyProblems, tagRulesOf, tagSentence, TAGS_INVALID, TAGS_REQUIRED, type CloneDraft, type CloneNote, type RequiredTag, type ResourceGroup, type ResourceGroupQuery, type TagApplyFailure, type TagApplyInput, type TagPolicy, type TaggableResource } from '../lib/tags'
import type { ConsoleResource, Resource, ResourceType, Schema } from '../lib/types'
import { createEntryOf, emptyConsoleState, idFrom, journaled, liveRowOf, resourceByWfrn, toConsoleResource, tombstoneOf, typeOf, wfrnOfResource, wfrnPatternOf, type ConsoleCommand, type RouteAnswer, type RouteContext } from './commands'
import type { MockData } from './data'
import { accountOf, authorize, isMember, type Decision } from './directory'
import { accountOfResource, consoleResourcesOf, filterConsoleResources } from './resources'

/*
 * The BFF's tag and resource-group endpoints (console-bff contracts/additions.md, phase 4): the
 * account's required tags (`/console/v1/tag-policy`), the Tag Editor (`/console/v1/tags/...`),
 * resource groups (`/console/v1/resource-groups`) and the draft of a clone
 * (`/r/{type}/{wfrn}/clone`). The rules are lib/tags' own, which word them as the BFF does. Writes
 * are journaled through `journaled` in ./commands: a group under the `group.` kinds, and each
 * resource a Tag Editor change touches as its own `tags` command, so a reload replays them.
 */

/** What these endpoints keep beside the dataset, made on first use: the groups, and the Idempotency-Keys their creates came with. */
export interface TagsState {
  groups: Record<string, ResourceGroup>
  createKeys: Record<string, { userId: string; fingerprint: string; groupId: string }>
}

export function emptyTagsState(): TagsState {
  return { groups: {}, createKeys: {} }
}

function stateOf(data: MockData): TagsState {
  const st = (data.console ??= emptyConsoleState())
  const tools = (st.tools ??= emptyTagsState())
  tools.createKeys ??= {}
  return tools
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

/** A body's tags as strings; anything else is left out, so a blank or missing one reads as missing. */
function tagsOf(v: unknown): Record<string, string> {
  return isRecord(v) ? Object.fromEntries(Object.entries(v).filter((e): e is [string, string] => typeof e[1] === 'string')) : {}
}

function iso(ms: number): string {
  return new Date(ms).toISOString()
}

/* ----------------------------------------------------------------------------
   The required tags (WP 4.4)
   ------------------------------------------------------------------------- */

/** Each account's own values, as `console.tags.accounts.<accountId>.<key>` configures them. */
const ACCOUNT_TAGS: Record<string, Record<string, string>> = {
  '123456789012': { 'cost-centre': 'cc-1042' },
  '210987654321': { 'cost-centre': 'cc-2077' },
}

/** The organization's guardrails, as `console.tags.required` configures them. */
const REQUIRED: RequiredTag[] = [
  { key: 'owner', title: 'Owner', description: 'The team that owns the resource and answers for it', defaultFrom: 'account.owning-team' },
  { key: 'cost-centre', title: 'Cost centre', description: 'Where the resource’s cost is charged, like cc-1042', pattern: '^cc-[0-9]{4}$', defaultFrom: 'account' },
  { key: 'data-class', title: 'Data class', description: 'How sensitive the data on it is', values: ['public', 'internal', 'confidential', 'restricted'], default: 'internal' },
  { key: 'environment', title: 'Environment', description: 'The environment it serves', values: ['dev', 'test', 'prod'], defaultFrom: 'input.environment' },
]

/** The account's required tags with their defaults resolved: its own value, its owning team, the create's field, or the organization's. */
export function tagPolicyOf(data: MockData, accountId: string): TagPolicy {
  const own = ACCOUNT_TAGS[accountId] ?? {}
  const team = accountOf(data.directory, accountId)?.owningTeam
  const required = REQUIRED.map((t): RequiredTag => {
    const { default: preset, defaultFrom, ...rest } = t
    if (own[t.key]) return { ...rest, default: own[t.key], defaultFrom: 'account' }
    if (defaultFrom === 'account.owning-team') return team ? { ...rest, default: team, defaultFrom: 'account' } : rest
    if (defaultFrom?.startsWith('input.')) return { ...rest, defaultFrom }
    if (defaultFrom === 'account') return rest
    return preset ? { ...rest, default: preset, defaultFrom: 'organization' } : rest
  })
  return { accountId, required }
}

/** The create review's tag rules (`tags.required`, `tags.invalid`): what the account's tag policy says of the create's tags. */
export function createTagRules(data: MockData, session: RouteContext['session'], _rt: ResourceType, _input: Record<string, unknown>, tags: unknown): { id: string; result: string; message: string }[] {
  return tagRulesOf(policyProblems(tagPolicyOf(data, session.accountId).required, tagsOf(tags)))
}

/** A create's tags against the account's tag policy: the 400's code and its problems at `/tags/<key>`, or null when they pass. */
export function createTagProblems(data: MockData, session: RouteContext['session'], _rt: ResourceType, _input: Record<string, unknown>, tags: unknown): { error: 'tags.required' | 'tags.invalid'; problems: Problem[] } | null {
  const problems = policyProblems(tagPolicyOf(data, session.accountId).required, tagsOf(tags))
  return problems.length ? { error: tagSentence(problems).error, problems } : null
}

/** A PUT of a resource's tags against the policy: it may not drop a required tag the resource carries, nor give one a value it may not take. */
export function tagChangeProblems(data: MockData, session: RouteContext['session'], current: Record<string, string>, next: Record<string, string>): { error: 'tags.required' | 'tags.invalid'; problems: Problem[] } | null {
  const wanted = tagsOf(next)
  const removed = Object.keys(current).filter((k) => !(k in wanted))
  const problems = changeProblems(tagPolicyOf(data, session.accountId).required, current, wanted, removed)
  if (!problems.length) return null
  return { error: problems.some((p) => p.code === TAGS_REQUIRED) ? TAGS_REQUIRED : TAGS_INVALID, problems }
}

/* ----------------------------------------------------------------------------
   The account's resources, as the Tag Editor and the groups read them
   ------------------------------------------------------------------------- */

interface Located {
  rt: ResourceType
  r: Resource
  wfrn: string
}

function accountIds(data: MockData): string[] {
  return data.directory.accounts.map((a) => a.accountId)
}

/** The rows of the types named, of the session's account, that carry every tag (tombstones left out, as a list without `state`). */
function rowsWith(data: MockData, s: RouteContext['session'], types: readonly ResourceType[], tags: Record<string, string>, now: number): ConsoleResource[] {
  const ctx = { partition: data.partition, region: s.region, accountId: s.accountId }
  const filter = new URLSearchParams(Object.entries(tags).map(([k, v]) => [`tag.${k}`, v]))
  return types.flatMap((rt) => filterConsoleResources(consoleResourcesOf(rt.name, data, ctx, accountIds(data), (r) => liveRowOf(data, s, r, now)), filter))
}

/** The resource a wfrn names in the session's account, of any type. */
function locate(data: MockData, s: RouteContext['session'], wfrn: string): Located | null {
  for (const rt of data.resourceTypes) {
    if (!wfrn.includes(`:${rt.name}/`)) continue
    const r = resourceByWfrn(data, rt, wfrn)
    if (r && accountOfResource(r, accountIds(data)) === s.accountId && wfrnOfResource(data, s, rt, r) === wfrn) return { rt, r, wfrn }
  }
  return null
}

function refused(decision: Decision, ctx: RouteContext, path: string): RouteAnswer {
  return { status: 403, body: { error: decision.denied?.reason ?? 'Forbidden', denied: decision.denied, correlationId: `mock-${idFrom(`${ctx.now}:${path}`, 8)}` } }
}

function invalid(problems: Problem[]): RouteAnswer {
  return { status: 400, body: { error: problems.length === 1 ? problems[0].message : `the change has ${problems.length} problems`, problems } }
}

function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { status: 200, body: { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null } }
}

/** The types a query names (repeated or comma separated), or the problems of the unknown ones at `/types/<i>`. */
function typesNamed(data: MockData, params: URLSearchParams): { types: ResourceType[]; problems: Problem[] } {
  const names = params
    .getAll('type')
    .flatMap((v) => v.split(','))
    .map((v) => v.trim())
    .filter(Boolean)
  const problems: Problem[] = []
  names.forEach((n, i) => {
    if (!typeOf(data, n)) problems.push({ level: 'error', code: 'type.unknown', field: `/types/${i}`, message: `no type '${n}' in this region` })
  })
  return { types: names.length ? data.resourceTypes.filter((t) => names.includes(t.name)) : data.resourceTypes, problems }
}

/* ----------------------------------------------------------------------------
   The routes
   ------------------------------------------------------------------------- */

/** Answers the tag and resource-group paths, or null for any other path. */
export function tagRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const s = ctx.session
  const can = (action: string, resource: string) => authorize(data.directory, { accountId: s.accountId, userId: s.userId }, action, resource)
  const listable = (rt: ResourceType) => can(`${serviceOf(rt)}:list`, wfrnPatternOf(data, s, rt)).allowed

  if (path === '/console/v1/tag-policy') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const account = params.get('account') || s.accountId
    if (!isMember(data.directory, account, s.userId)) return { status: 404, body: { error: `no account ${account} of yours` } }
    return { status: 200, body: tagPolicyOf(data, account) }
  }

  if (path === '/console/v1/tags/resources') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const { types, problems } = typesNamed(data, params)
    if (problems.length) return { status: 400, body: { error: problems[0].message, problems } }
    const tags: Record<string, string> = {}
    params.forEach((v, k) => {
      if (k.startsWith('tag.') && k.length > 4) tags[k.slice(4)] = v
    })
    const rows: TaggableResource[] = rowsWith(data, s, types.filter(listable), tags, ctx.now).map((row) => ({ ...row, canTag: can(`${row.service}:tag`, row.wfrn).allowed }))
    return pageOf(rows, params)
  }

  if (path === '/console/v1/tags:apply') {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    return applyTags(data, ctx, can)
  }

  if (path === '/console/v1/resource-groups' || path.startsWith('/console/v1/resource-groups/')) return groupRoutes(data, ctx, path, params, method, can, listable)

  const m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})\/([^/:]+)\/clone$/.exec(path)
  if (m) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    return cloneDraft(data, ctx, m[1], decodeURIComponent(m[2]), params.get('account') || s.accountId, can)
  }
  return null
}

/* ----------------------------------------------------------------------------
   The Tag Editor's change (WP 4.3)
   ------------------------------------------------------------------------- */

function applyTags(data: MockData, ctx: RouteContext, can: (action: string, resource: string) => Decision): RouteAnswer {
  const s = ctx.session
  const body = ctx.body
  const change: TagApplyInput = {
    resources: Array.isArray(body.resources) ? body.resources.filter((x): x is string => typeof x === 'string') : [],
    set: isRecord(body.set) ? (body.set as Record<string, string>) : {},
    remove: Array.isArray(body.remove) ? body.remove.filter((x): x is string => typeof x === 'string') : [],
  }
  const problems = applyProblems(tagPolicyOf(data, s.accountId).required, change)
  if (problems.length) return invalid(problems)
  const wfrns = [...new Set(change.resources)]
  const applied: { wfrn: string; tags: Record<string, string> }[] = []
  const failed: TagApplyFailure[] = []
  const key = ctx.headers['idempotency-key']
  for (const wfrn of wfrns) {
    const found = locate(data, s, wfrn)
    if (!found) {
      failed.push({ wfrn, error: 'not-found', message: `no resource ${wfrn} in this account and region` })
      continue
    }
    const d = can(`${serviceOf(found.rt)}:tag`, wfrn)
    if (!d.allowed) {
      failed.push({ wfrn, error: 'denied', message: d.denied?.reason ?? 'denied', denied: d.denied })
      continue
    }
    const row = toConsoleResource(data, s, found.rt, found.r, ctx.now)
    if (row.state === 'deleted') {
      failed.push({ wfrn, error: 'gone', message: 'the resource was deleted' })
      continue
    }
    const next = { ...row.tags, ...change.set }
    for (const k of change.remove) delete next[k]
    // each resource is its own change, with the request's key per resource
    const answer = journaled(data, { body: next, headers: key ? { 'idempotency-key': `${key}:${wfrn}` } : {} }, { at: ctx.now, kind: 'tags', userId: s.userId, accountId: s.accountId, region: s.region, type: found.rt.name, wfrn, body: next, ids: {} })
    const said = (answer.body ?? {}) as { detail?: unknown; error?: unknown }
    if (answer.status < 400) applied.push({ wfrn, tags: answer.body as Record<string, string> })
    else failed.push({ wfrn, error: answer.status === 409 ? 'conflict' : 'error', message: String(said.detail ?? said.error ?? 'the change failed') })
  }
  failed.sort((a, b) => wfrns.indexOf(a.wfrn) - wfrns.indexOf(b.wfrn))
  return { status: 200, body: { applied, failed } }
}

/* ----------------------------------------------------------------------------
   Resource groups (WP 4.3)
   ------------------------------------------------------------------------- */

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz'

/** A lowercase ULID drawn from the time and a seed, the same every time (so a replay mints the same id). */
export function ulidOf(at: number, seed: string): string {
  let time = ''
  let t = Math.max(0, Math.floor(at))
  for (let i = 0; i < 10; i++) {
    time = CROCKFORD[t % 32] + time
    t = Math.floor(t / 32)
  }
  const random = idFrom(seed, 16)
    .split('')
    .map((c) => CROCKFORD[parseInt(c, 36) % 32])
    .join('')
  return time + random
}

function groupWfrn(data: MockData, s: { region: string; accountId: string }, groupId: string): string {
  return `wfrn:${data.partition}:resource-groups:${s.region}:${s.accountId}:group/${groupId}`
}

/** The query as the BFF keeps it: each type once in the order given, the tags by name. */
function canonicalQuery(q: unknown): ResourceGroupQuery {
  const raw = isRecord(q) ? q : {}
  const types = Array.isArray(raw.types) ? [...new Set(raw.types.filter((t): t is string => typeof t === 'string'))] : []
  const tags = Object.fromEntries(Object.entries(tagsOf(raw.tags)).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
  return { types, tags }
}

function etag(g: ResourceGroup): Record<string, string> {
  return { ETag: `"${g.version}"` }
}

/** The version an If-Match names (`"3"`, `W/"3"`, `3`), or null for `*` or none. */
function versionOf(ifMatch: string | undefined): number | null {
  if (!ifMatch || ifMatch.trim() === '*') return null
  const n = Number(ifMatch.trim().replace(/^W\//, '').replace(/"/g, ''))
  return Number.isFinite(n) ? n : null
}

function groupRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string, can: (action: string, resource: string) => Decision, listable: (rt: ResourceType) => boolean): RouteAnswer {
  const s = ctx.session
  const st = stateOf(data)
  const known = new Set(data.resourceTypes.map((t) => t.name))
  const all = groupWfrn(data, s, '*')
  const run = (cmd: Pick<ConsoleCommand, 'kind' | 'body' | 'ids'>) => journaled(data, { body: ctx.body, headers: {} }, { at: ctx.now, userId: s.userId, accountId: s.accountId, region: s.region, type: 'resource-group', ...cmd })

  if (path === '/console/v1/resource-groups') {
    if (method === 'GET') {
      const d = can('resource-groups:list', all)
      if (!d.allowed) return refused(d, ctx, path)
      const items = Object.values(st.groups)
        .filter((g) => g.accountId === s.accountId && g.region === s.region)
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
      return { status: 200, body: { items, next: null } }
    }
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = can('resource-groups:create', all)
    if (!d.allowed) return refused(d, ctx, path)
    const problems = newGroupProblems({ name: ctx.body.name as string, description: ctx.body.description as string, query: ctx.body.query as ResourceGroupQuery }, known)
    if (problems.length) return invalid(problems)
    const name = ctx.body.name as string
    const description = typeof ctx.body.description === 'string' ? ctx.body.description : ''
    const query = canonicalQuery(ctx.body.query)
    const key = ctx.headers['idempotency-key']
    const fingerprint = JSON.stringify([name, description, query])
    if (key) {
      if (key.length > 255) return invalid([{ level: 'error', code: 'length', field: '/Idempotency-Key', message: 'an Idempotency-Key has at most 255 characters' }])
      const seen = st.createKeys[`${s.userId}:${key}`]
      if (seen && seen.fingerprint !== fingerprint) return { status: 409, body: { error: 'idempotency.key-reused', message: 'this Idempotency-Key came with another body; send a new key for another group' } }
      const first = seen ? st.groups[seen.groupId] : undefined
      if (first) return { status: 201, body: first, headers: { ...etag(first), Location: `/console/v1/resource-groups/${first.groupId}`, 'Idempotent-Replay': 'true' } }
    }
    const taken = Object.values(st.groups).find((g) => g.accountId === s.accountId && g.region === s.region && g.name === name)
    if (taken) {
      const message = `a resource group named ${name} exists in this account and region already`
      return { status: 409, body: { error: 'group.name-taken', message, problems: [{ level: 'error', code: 'group.name-taken', field: '/name', message }], detail: { name } } }
    }
    const groupId = `group-${ulidOf(ctx.now, `${s.userId}:${s.accountId}:${name}:${ctx.now}`)}`
    const answer = run({ kind: 'group.create', body: { name, description, query, key }, ids: { resourceId: groupId } })
    const g = answer.body as ResourceGroup
    return answer.status < 400 ? { status: 201, body: g, headers: { ...etag(g), Location: `/console/v1/resource-groups/${g.groupId}` } } : answer
  }

  const m = /^\/console\/v1\/resource-groups\/([^/]+)(\/resources)?$/.exec(path)
  const groupId = m ? decodeURIComponent(m[1]) : ''
  const g = st.groups[groupId]
  if (!m || !g || g.accountId !== s.accountId || g.region !== s.region) return { status: 404, body: { error: `no resource group ${groupId} in this account and region` } }

  if (m[2]) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
    const d = can('resource-groups:get', g.wfrn)
    if (!d.allowed) return refused(d, ctx, path)
    const types = (g.query.types.length ? data.resourceTypes.filter((t) => g.query.types.includes(t.name)) : data.resourceTypes).filter(listable)
    return pageOf(rowsWith(data, s, types, g.query.tags, ctx.now), params)
  }
  if (method === 'GET') {
    const d = can('resource-groups:get', g.wfrn)
    if (!d.allowed) return refused(d, ctx, path)
    if (ctx.headers['if-none-match'] === `"${g.version}"`) return { status: 304, headers: etag(g) }
    return { status: 200, body: g, headers: etag(g) }
  }
  if (method === 'PATCH') {
    const d = can('resource-groups:update', g.wfrn)
    if (!d.allowed) return refused(d, ctx, path)
    const query = ctx.body.query === undefined || ctx.body.query === null ? undefined : (ctx.body.query as ResourceGroupQuery)
    const problems = groupChangeProblems({ name: ctx.body.name, description: ctx.body.description as string | undefined, query }, g, known)
    if (problems.length) return invalid(problems)
    const want = versionOf(ctx.headers['if-match'])
    if (want !== null && want !== g.version) return { status: 409, body: { error: `the group moved on to version ${g.version}`, detail: { version: g.version } } }
    const answer = run({ kind: 'group.update', body: { description: ctx.body.description, query: query ? canonicalQuery(query) : undefined }, ids: { resourceId: g.groupId } })
    return answer.status < 400 ? { status: 200, body: answer.body, headers: etag(answer.body as ResourceGroup) } : answer
  }
  if (method === 'DELETE') {
    const d = can('resource-groups:delete', g.wfrn)
    if (!d.allowed) return refused(d, ctx, path)
    const answer = run({ kind: 'group.delete', body: null, ids: { resourceId: g.groupId } })
    return answer.status < 400 ? { status: 204 } : answer
  }
  return { status: 405, body: { error: `${method} is not allowed on ${path}` } }
}

/** Applies a journaled `group.*` command, when it runs and when the journal replays it. */
export function applyTagCommand(data: MockData, cmd: ConsoleCommand): RouteAnswer {
  const st = stateOf(data)
  const groupId = cmd.ids.resourceId ?? ''
  const body = isRecord(cmd.body) ? cmd.body : {}
  if (cmd.kind === 'group.create') {
    const g: ResourceGroup = {
      groupId,
      wfrn: groupWfrn(data, cmd, groupId),
      name: String(body.name ?? ''),
      description: typeof body.description === 'string' ? body.description : '',
      query: canonicalQuery(body.query),
      accountId: cmd.accountId,
      region: cmd.region,
      createdBy: cmd.userId,
      createdAt: iso(cmd.at),
      updatedAt: iso(cmd.at),
      version: 1,
    }
    st.groups[groupId] = g
    if (typeof body.key === 'string' && body.key) st.createKeys[`${cmd.userId}:${body.key}`] = { userId: cmd.userId, fingerprint: JSON.stringify([g.name, g.description, g.query]), groupId }
    return { status: 201, body: g }
  }
  const g = st.groups[groupId]
  if (!g) return { status: 404, body: { error: `no resource group ${groupId}` } }
  if (cmd.kind === 'group.update') {
    const next: ResourceGroup = { ...g, description: typeof body.description === 'string' ? body.description : g.description, query: isRecord(body.query) ? canonicalQuery(body.query) : g.query }
    // a change that changes nothing keeps the version
    if (next.description === g.description && JSON.stringify(next.query) === JSON.stringify(g.query)) return { status: 200, body: g }
    st.groups[groupId] = { ...next, version: g.version + 1, updatedAt: iso(cmd.at) }
    return { status: 200, body: st.groups[groupId] }
  }
  if (cmd.kind === 'group.delete') {
    delete st.groups[groupId]
    return { status: 204 }
  }
  return { status: 404, body: { error: `No command ${cmd.kind}` } }
}

/* ----------------------------------------------------------------------------
   Launch more like this (WP 4.6)
   ------------------------------------------------------------------------- */

/** `<name>-copy`, the name shortened until it fits the field's pattern and length; null when nothing fits. */
export function copyName(name: string, schema: Schema | undefined): string | null {
  if (!name.trim()) return null
  const max = typeof schema?.maxLength === 'number' ? schema.maxLength : Infinity
  for (let keep = name.length; keep > 0; keep--) {
    const candidate = `${name.slice(0, keep).replace(/-+$/, '')}-copy`
    if (candidate.length <= max && (!schema?.pattern || fullMatch(schema.pattern, candidate))) return candidate
  }
  return null
}

/** The source's tags, each required tag that carried the source account's own default given the target account's. */
function cloneTags(source: Record<string, string>, here: TagPolicy, there: TagPolicy): Record<string, string> {
  const tags = { ...source }
  if (here.accountId === there.accountId) return tags
  const hereDefaults = Object.fromEntries(here.required.filter((t) => t.default).map((t) => [t.key, t.default as string]))
  for (const tag of there.required) {
    const value = tags[tag.key]
    if (tag.defaultFrom === 'account' && tag.default && (value === undefined || value === hereDefaults[tag.key])) tags[tag.key] = tag.default
  }
  return tags
}

function cloneDraft(data: MockData, ctx: RouteContext, typeName: string, wfrnParam: string, target: string, can: (action: string, resource: string) => Decision): RouteAnswer {
  const s = ctx.session
  if (!isMember(data.directory, target, s.userId)) return { status: 404, body: { error: `no account ${target} of yours` } }
  const rt = typeOf(data, typeName)
  if (!rt) return { status: 404, body: { error: `No resource type ${typeName}` } }
  const found = locate(data, s, wfrnParam)
  const noun = (rt.console?.title ?? rt.plural).replace(/s$/, '').toLowerCase()
  if (!found || found.rt.name !== rt.name) return { status: 404, body: { error: 'resource.not-found', detail: `No ${noun} lives at ${wfrnParam} in this account` } }
  const { r, wfrn } = found
  const service = serviceOf(rt)
  const d = can(`${service}:get`, wfrn)
  if (!d.allowed) return refused(d, ctx, wfrn)
  const row = toConsoleResource(data, s, rt, r, ctx.now)
  if (row.state === 'deleted') return { status: 410, body: { error: 'resource.deleted', detail: tombstoneOf(data, s, rt, r) } }
  const entry = createEntryOf(data, rt)
  if (!entry) return { status: 404, body: { error: 'create.unavailable', detail: `No workflow creates ${rt.plural}; the provider publishes one in its catalog` } }
  const elsewhere = target !== s.accountId
  const createdWith = data.console?.jobs[r.createdBy?.jobId ?? '']?.input ?? {}
  const notes: CloneNote[] = []
  const input: Record<string, unknown> = {}
  for (const [name, prop] of Object.entries(entry.input?.properties ?? {})) {
    const schema = (prop ?? {}) as Schema & { writeOnly?: boolean; readOnly?: boolean; 'x-secret'?: boolean }
    const attribute = r.attributes?.[name]
    const value = attribute !== undefined && attribute !== null ? attribute : name === 'name' ? (r.name ?? createdWith[name]) : createdWith[name]
    if (value === undefined || value === null) continue
    if (schema.format === 'password' || schema.writeOnly || schema['x-secret']) {
      notes.push({ field: name, code: 'secret', message: `${name} is a secret and is not copied; enter it again` })
      continue
    }
    if (schema.readOnly) continue
    if (name === 'name') {
      const suggested = copyName(String(value), schema)
      if (suggested === null) notes.push({ field: name, code: 'name', message: 'choose a name for the copy' })
      else input[name] = suggested
      continue
    }
    if (elsewhere && schema['x-resource']) notes.push({ field: name, code: 'reference', message: `${name} names a ${schema['x-resource'].type ?? 'resource'} of this account; check that the target account can use it` })
    input[name] = value
  }
  const targets = data.directory.accounts
    .filter((a) => isMember(data.directory, a.accountId, s.userId))
    .map((a) => {
      const decision = authorize(data.directory, { accountId: a.accountId, userId: s.userId }, `${service}:create`, wfrnPatternOf(data, { ...s, accountId: a.accountId }, rt))
      return { accountId: a.accountId, name: a.name, current: a.accountId === s.accountId, allowed: decision.allowed, ...(decision.allowed ? {} : { denied: decision.denied }) }
    })
  const here = tagPolicyOf(data, s.accountId)
  const there = tagPolicyOf(data, target)
  const draft: CloneDraft = { type: rt.name, workflow: entry.workflow, source: { wfrn, name: row.name ?? r.id, accountId: s.accountId, region: s.region }, accountId: target, input, tags: cloneTags(row.tags, here, there), notes, targets, tagPolicy: there }
  return { status: 200, body: draft }
}
