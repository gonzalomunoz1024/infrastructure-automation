import type { Problem } from '@iaf/console-kit'
import { get, page, qs, request } from './api'
import { BFF_BASE, idempotencyKey } from './bff'
import type { ConsoleResource, Denial, Page } from './types'

/*
 * Tags and resource groups (console-bff contracts/additions.md, phase 4): the account's required
 * tags (`/console/v1/tag-policy`), the Tag Editor (`/console/v1/tags/...`), resource groups
 * (`/console/v1/resource-groups`) and the draft of a clone (`/r/{type}/{wfrn}/clone`). The rules
 * the BFF applies to a body are here too, word for word, so a form can say what is wrong before
 * it sends anything and the mock answers as the BFF does.
 */

/** A tag every create of the account names (WP 4.4). */
export interface RequiredTag {
  key: string
  title: string
  description?: string
  /** the values it takes, when it takes one of a list */
  values?: string[] | null
  /** the pattern it matches, when it takes a pattern */
  pattern?: string | null
  /** what the create form pre-fills */
  default?: string | null
  /** account | organization | input.<field> (copy the create's own field) */
  defaultFrom?: string | null
}

export interface TagPolicy {
  accountId: string
  required: RequiredTag[]
}

/** A row of the Tag Editor: a resource, and whether the person may tag it (`<service>:tag`). */
export type TaggableResource = ConsoleResource & { canTag: boolean }

export interface TagApplyInput {
  resources: string[]
  set: Record<string, string>
  remove: string[]
}

export interface TagApplyFailure {
  wfrn: string
  /** denied | not-found | gone | conflict | invalid | error */
  error: string
  message: string
  denied?: Denial
}

export interface TagApplyResult {
  applied: { wfrn: string; tags: Record<string, string> }[]
  failed: TagApplyFailure[]
}

export interface ResourceGroupQuery {
  /** type names of the region's catalogue; [] is every type */
  types: string[]
  tags: Record<string, string>
}

export interface ResourceGroup {
  groupId: string
  wfrn: string
  name: string
  description: string
  query: ResourceGroupQuery
  accountId: string
  region: string
  createdBy: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface ResourceGroupInput {
  name: string
  description?: string
  query: ResourceGroupQuery
}

export interface ResourceGroupChange {
  description?: string
  query?: ResourceGroupQuery
}

/** What the draft of a clone says of a field: a secret not copied, a name to choose, a reference to check. */
export interface CloneNote {
  field: string
  code: string
  message: string
}

/** An account of the person's, and whether they may create the type there. */
export interface CloneTarget {
  accountId: string
  name: string
  current: boolean
  allowed: boolean
  denied?: Denial
}

/** The type's create form filled from a resource (WP 4.6). Nothing is created until the wizard creates. */
export interface CloneDraft {
  type: string
  workflow: string
  source: { wfrn: string; name: string; accountId: string; region: string }
  accountId: string
  input: Record<string, unknown>
  tags: Record<string, string>
  notes: CloneNote[]
  targets: CloneTarget[]
  tagPolicy: TagPolicy
}

/** The Tag Editor's query: the types (every type when none) and the tags a resource carries. */
export interface TagSearch {
  types?: string[]
  tags?: Record<string, string>
  limit?: number
  after?: string | null
}

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent
const keyed = (key?: string) => ({ headers: { 'Idempotency-Key': key ?? idempotencyKey() } })

export const tagsApi = {
  /** the account's required tags; another account of the person's with `accountId` */
  policy: (accountId?: string) => get<TagPolicy>(b(`/tag-policy${qs({ account: accountId })}`)),
  /** the Tag Editor's rows: resources of the types that carry every named tag */
  resources: ({ types = [], tags = {}, limit, after }: TagSearch = {}): Promise<Page<TaggableResource>> =>
    page<TaggableResource>(b('/tags/resources'), { type: types.join(','), ...Object.fromEntries(Object.entries(tags).map(([k, v]) => [`tag.${k}`, v])), limit, after }),
  /** sets and removes tags on up to 100 resources, each its own change */
  apply: (body: TagApplyInput, key?: string) => request<TagApplyResult>('POST', b('/tags:apply'), body, keyed(key)),
  /** the draft of a create like this resource, in this account or another of the person's */
  clone: (type: string, wfrn: string, accountId?: string) => get<CloneDraft>(b(`/r/${enc(type)}/${enc(wfrn)}/clone${qs({ account: accountId })}`)),
  groups: {
    list: () => page<ResourceGroup>(b('/resource-groups')),
    get: (groupId: string) => get<ResourceGroup>(b(`/resource-groups/${enc(groupId)}`)),
    create: (body: ResourceGroupInput, key?: string) => request<ResourceGroup>('POST', b('/resource-groups'), body, keyed(key)),
    update: (groupId: string, body: ResourceGroupChange, version?: number) => request<ResourceGroup>('PATCH', b(`/resource-groups/${enc(groupId)}`), body, version === undefined ? {} : { headers: { 'If-Match': `"${version}"` } }),
    remove: (groupId: string) => request<void>('DELETE', b(`/resource-groups/${enc(groupId)}`)),
    resources: (groupId: string, params: { limit?: number; after?: string | null } = {}) => page<ConsoleResource>(b(`/resource-groups/${enc(groupId)}/resources`), params),
  },
}

/* ----------------------------------------------------------------------------
   The rules, as the BFF words them
   ------------------------------------------------------------------------- */

/** A tag missing or blank, or about to be removed; a tag with a value it may not take. */
export const TAGS_REQUIRED = 'tags.required'
export const TAGS_INVALID = 'tags.invalid'
/** A tag's name as the state service takes it. */
export const TAG_NAME = /^[a-z0-9-]{1,32}$/
export const TAG_VALUE_MAX = 256
/** How many resources one apply changes, and how many tags a group's query names. */
export const APPLY_MAX = 100
export const GROUP_TAGS_MAX = 20
/** A group's name: 1 to 128 of [A-Za-z0-9._-], unique per account and region. */
export const GROUP_NAME = /^[A-Za-z0-9._-]{1,128}$/
export const GROUP_DESCRIPTION_MAX = 512

const error = (code: string, field: string, message: string): Problem => ({ level: 'error', code, field, message })

/** Whether a value is one the tag may take: not blank, at most 256 characters, in its list or matching its pattern. */
export function tagAccepts(tag: RequiredTag, value: string | undefined | null): boolean {
  if (value === undefined || value === null || !value.trim() || value.length > TAG_VALUE_MAX) return false
  if (tag.values?.length) return tag.values.includes(value)
  if (!tag.pattern) return true
  return fullMatch(tag.pattern, value)
}

/** Whether the whole value matches the pattern, as Java's Pattern.matches reads it; a pattern that does not compile takes anything. */
export function fullMatch(pattern: string, value: string): boolean {
  try {
    return new RegExp(`^(?:${pattern})$`).test(value)
  } catch {
    return true
  }
}

/** The sentence for a value the tag may not take. */
export function allowedSentence(tag: RequiredTag): string {
  if (tag.values?.length) return `${tag.title} is one of ${tag.values.join(', ')}`
  if (tag.pattern) return `${tag.title} must match ${tag.pattern}`
  return `${tag.title} is at most ${TAG_VALUE_MAX} characters`
}

/** The create's field a tag copies (`input.environment` → environment), or null. */
export function inputFieldOf(tag: RequiredTag): string | null {
  return tag.defaultFrom?.startsWith('input.') ? tag.defaultFrom.slice('input.'.length) || null : null
}

/** What a create form pre-fills: the create's own field for `input.<field>` (while it holds text), else the tag's default. */
export function tagDefault(tag: RequiredTag, input: Record<string, unknown> = {}): string {
  const field = inputFieldOf(tag)
  if (field) {
    const v = input[field]
    if (typeof v === 'string' && v) return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  }
  return tag.default ?? ''
}

/** Where a tag's default comes from, in words. */
export function defaultSource(tag: RequiredTag): string {
  const field = inputFieldOf(tag)
  if (field) return `Copied from the create's own ${field} field`
  if (tag.defaultFrom === 'account') return 'Set for this account'
  if (tag.defaultFrom === 'organization' || (tag.default && !tag.defaultFrom)) return 'Set for the organization'
  return 'No default'
}

/** What the tags lack or get wrong, as problems at `<prefix>/<key>` (the policy's check of a create). */
export function policyProblems(required: readonly RequiredTag[], tags: Record<string, string> | null | undefined, prefix = '/tags'): Problem[] {
  const given = tags ?? {}
  const problems: Problem[] = []
  for (const tag of required) {
    const value = given[tag.key]
    if (value === undefined || value === null || !value.trim()) problems.push(error(TAGS_REQUIRED, `${prefix}/${tag.key}`, `${tag.title} is required${tag.default ? ` (this account uses ${tag.default})` : ''}`))
    else if (!tagAccepts(tag, value)) problems.push(error(TAGS_INVALID, `${prefix}/${tag.key}`, allowedSentence(tag)))
  }
  return problems
}

/**
 * What a change to a resource's tags may not do: remove a required tag it carries, or give one a
 * value it may not take. A resource that never carried one is not made to take it.
 */
export function changeProblems(required: readonly RequiredTag[], before: Record<string, string>, set: Record<string, string>, removed: readonly string[], prefix = '/tags'): Problem[] {
  const problems: Problem[] = []
  for (const tag of required) {
    const value = set[tag.key]
    if (value !== undefined && !tagAccepts(tag, value)) problems.push(error(TAGS_INVALID, `${prefix}/${tag.key}`, allowedSentence(tag)))
    else if (value === undefined && removed.includes(tag.key) && tag.key in before) problems.push(error(TAGS_REQUIRED, `${prefix}/${tag.key}`, `${tag.title} is required and stays on the resource`))
  }
  return problems
}

/** The keys problems name, in their order. */
export function keysOf(problems: readonly Problem[]): string[] {
  const keys: string[] = []
  for (const p of problems) {
    const key = (p.field ?? '').slice((p.field ?? '').lastIndexOf('/') + 1)
    if (key && !keys.includes(key)) keys.push(key)
  }
  return keys
}

/** The preview's deny rules for a create's tags: `tags.required` names the missing keys, `tags.invalid` the wrong ones. */
export function tagRulesOf(problems: readonly Problem[]): { id: string; result: 'deny'; message: string }[] {
  const out: { id: string; result: 'deny'; message: string }[] = []
  const missing = problems.filter((p) => p.code === TAGS_REQUIRED)
  const wrong = problems.filter((p) => p.code === TAGS_INVALID)
  if (missing.length) out.push({ id: TAGS_REQUIRED, result: 'deny', message: `required tags are missing: ${keysOf(missing).join(', ')}` })
  if (wrong.length) out.push({ id: TAGS_INVALID, result: 'deny', message: `tags with values they may not take: ${keysOf(wrong).join(', ')}` })
  return out
}

/** A create's 400 for its tags: the code and the sentence. */
export function tagSentence(problems: readonly Problem[]): { error: typeof TAGS_REQUIRED | typeof TAGS_INVALID; message: string } {
  return problems.some((p) => p.code === TAGS_REQUIRED) ? { error: TAGS_REQUIRED, message: `a create names every required tag: ${keysOf(problems).join(', ')}` } : { error: TAGS_INVALID, message: 'some tags have values they may not take' }
}

/** What a `tags:apply` body gets wrong, whatever the resources: their number, the tags' names and values, the required tags' rules. */
export function applyProblems(required: readonly RequiredTag[], change: TagApplyInput): Problem[] {
  const problems: Problem[] = []
  if (change.resources.length === 0) problems.push(error('required', '/resources', 'at least one resource'))
  else if (change.resources.length > APPLY_MAX) problems.push(error('tags.too-many-resources', '/resources', `at most ${APPLY_MAX} resources at once, not ${change.resources.length}`))
  if (Object.keys(change.set).length === 0 && change.remove.length === 0) problems.push(error('required', '/set', 'a tag to set or one to remove'))
  for (const [name, value] of Object.entries(change.set)) {
    if (!TAG_NAME.test(name)) problems.push(error('tag.name', `/set/${name}`, 'a tag name is [a-z0-9-]{1,32}'))
    else if (typeof value !== 'string' || value.length > TAG_VALUE_MAX) problems.push(error('tag.value', `/set/${name}`, `a tag value has at most ${TAG_VALUE_MAX} characters`))
    else if (change.remove.includes(name)) problems.push(error('tags.conflict', `/set/${name}`, `${name} is both set and removed`))
  }
  for (const tag of required) {
    const value = change.set[tag.key]
    if (value !== undefined && !tagAccepts(tag, value)) problems.push(error(TAGS_INVALID, `/set/${tag.key}`, allowedSentence(tag)))
    const at = change.remove.indexOf(tag.key)
    if (at >= 0) problems.push(error(TAGS_REQUIRED, `/remove/${at}`, `${tag.title} is required and stays on the resources`))
  }
  return problems
}

/** One reference token of a JSON pointer, escaped. */
export function pointerToken(token: string): string {
  return token.replace(/~/g, '~0').replace(/\//g, '~1')
}

/** A group's name problems at `/name`. */
export function groupNameProblems(name: unknown): Problem[] {
  if (typeof name !== 'string' || !name) return [error('required', '/name', 'name is required')]
  if (!GROUP_NAME.test(name)) return [error('group.name', '/name', "a group's name is 1 to 128 letters, digits, '.', '_' or '-'")]
  return []
}

/** A group's description problems at `/description`. */
export function groupDescriptionProblems(description: unknown): Problem[] {
  return typeof description === 'string' && description.length > GROUP_DESCRIPTION_MAX ? [error('length', '/description', `description is at most ${GROUP_DESCRIPTION_MAX} characters`)] : []
}

/** A group query's problems, each at its pointer; `known` are the type names of the region's catalogue. */
export function groupQueryProblems(query: Partial<ResourceGroupQuery> | null | undefined, known: ReadonlySet<string>): Problem[] {
  const problems: Problem[] = []
  const types = query?.types ?? []
  types.forEach((t, i) => {
    if (typeof t !== 'string' || !known.has(t)) problems.push(error('type.unknown', `/query/types/${i}`, `no type '${String(t)}' in this region`))
  })
  const tags = Object.entries(query?.tags ?? {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  if (tags.length === 0) problems.push(error('group.query.tags-required', '/query/tags', "a group's query names at least one tag"))
  else if (tags.length > GROUP_TAGS_MAX) problems.push(error('length', '/query/tags', `a group's query names at most ${GROUP_TAGS_MAX} tags`))
  for (const [name, value] of tags) {
    const field = `/query/tags/${pointerToken(name)}`
    if (!TAG_NAME.test(name)) problems.push(error('tag.name', field, "a tag name is 1 to 32 lowercase letters, digits or '-'"))
    else if (typeof value !== 'string' || value.length > TAG_VALUE_MAX) problems.push(error('tag.value', field, `a tag value is a string of at most ${TAG_VALUE_MAX} characters`))
  }
  return problems
}

/** A new group's problems: its name, its description and its query. */
export function newGroupProblems(body: Partial<ResourceGroupInput>, known: ReadonlySet<string>): Problem[] {
  return [...groupNameProblems(body.name), ...groupDescriptionProblems(body.description), ...groupQueryProblems(body.query ?? { types: [], tags: {} }, known)]
}

/** A change's problems against the group it changes: the name never changes, the query is checked as on create. */
export function groupChangeProblems(body: { name?: unknown } & ResourceGroupChange, current: Pick<ResourceGroup, 'name'>, known: ReadonlySet<string>): Problem[] {
  const problems: Problem[] = []
  if (body.name !== undefined && body.name !== null && body.name !== current.name) problems.push(error('group.name-immutable', '/name', "a group's name never changes; create a group with the new name instead"))
  problems.push(...groupDescriptionProblems(body.description))
  if (body.query) problems.push(...groupQueryProblems(body.query, known))
  return problems
}

/** A tag query as text, `owner=team-compute, environment=prod`. */
export function tagsText(tags: Record<string, string>): string {
  return Object.entries(tags)
    .map(([k, v]) => `${k}=${v}`)
    .join(', ')
}

/* ----------------------------------------------------------------------------
   A form's tags: the required ones, then the person's own rows
   ------------------------------------------------------------------------- */

/** One row of free tags: a name and a value. */
export interface TagRow {
  id: string
  key: string
  value: string
}

/** What a create form holds of its tags: the required tags the person set (the others follow their default), and their own rows. */
export interface TagsDraft {
  values: Record<string, string>
  /** the required tags the person changed; the others follow their default (the create's own field for `input.<field>`) */
  edited: string[]
  extra: TagRow[]
}

export function emptyTagsDraft(): TagsDraft {
  return { values: {}, edited: [], extra: [] }
}

let rowSeq = 0
/** A new empty row, with an id of its own. */
export function newTagRow(key = '', value = ''): TagRow {
  rowSeq += 1
  return { id: `tag-row-${rowSeq}`, key, value }
}

/** A draft holding tags someone gave (a clone's): the required ones as set, the rest as rows. */
export function draftOf(tags: Record<string, string>, required: readonly RequiredTag[]): TagsDraft {
  const keys = new Set(required.map((t) => t.key))
  const values = Object.fromEntries(Object.entries(tags).filter(([k]) => keys.has(k)))
  return { values, edited: Object.keys(values), extra: Object.entries(tags).filter(([k]) => !keys.has(k)).map(([k, v]) => newTagRow(k, v)) }
}

/** A required tag's value in the form: what the person set, else its default. */
export function requiredValue(tag: RequiredTag, draft: TagsDraft, input: Record<string, unknown>): string {
  return draft.edited.includes(tag.key) ? (draft.values[tag.key] ?? '') : tagDefault(tag, input)
}

/** The tags the form sends: every required tag that has a value, then the rows that name one. */
export function draftTags(required: readonly RequiredTag[], draft: TagsDraft, input: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const tag of required) {
    const v = requiredValue(tag, draft, input).trim()
    if (v) out[tag.key] = v
  }
  for (const row of draft.extra) if (row.key.trim() && !(row.key.trim() in out)) out[row.key.trim()] = row.value
  return out
}

/** The required tags the form leaves blank. */
export function missingTags(required: readonly RequiredTag[], draft: TagsDraft, input: Record<string, unknown>): RequiredTag[] {
  return required.filter((t) => !requiredValue(t, draft, input).trim())
}

/** What is wrong with the person's own rows, by row id: a value without a name, a name the state service does not take, a name given twice or one the required tags hold. */
export function rowErrors(rows: readonly TagRow[], required: readonly RequiredTag[]): Record<string, { key?: string; value?: string }> {
  const out: Record<string, { key?: string; value?: string }> = {}
  const reserved = new Map(required.map((t) => [t.key, t.title]))
  const seen = new Set<string>()
  for (const row of rows) {
    const key = row.key.trim()
    const e: { key?: string; value?: string } = {}
    if (!key && row.value) e.key = 'Name the tag, or clear its value'
    else if (key && !TAG_NAME.test(key)) e.key = 'A tag name is 1 to 32 lowercase letters, digits or hyphens'
    else if (reserved.has(key)) e.key = `${reserved.get(key)} is a required tag; set it above`
    else if (key && seen.has(key)) e.key = `Another row names ${key} already`
    if (row.value.length > TAG_VALUE_MAX) e.value = `A tag value is at most ${TAG_VALUE_MAX} characters`
    if (key) seen.add(key)
    if (e.key || e.value) out[row.id] = e
  }
  return out
}

/** Names in a sentence: "Owner", "Owner and Cost centre", "Owner, Cost centre and Environment". */
export function andList(words: readonly string[]): string {
  if (words.length <= 1) return words.join('')
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}
