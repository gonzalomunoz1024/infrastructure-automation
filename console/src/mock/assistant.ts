import { lifecycleOf, sensitiveFields, type Problem } from '@iaf/console-kit'
import type { AssistantContext, AssistantProposal, AssistantTurn, Confidence, ResourceExplanation, SearchFilter, SearchInterpretation } from '../lib/assistant'
import { capitalise, serviceOf, singularOf, titleOf } from '../lib/descriptors'
import { RESOURCE_STATES, type CatalogEntry, type ConsoleResource, type ResourceState, type ResourceType } from '../lib/types'
import { createEntryOf, idFrom, liveRowOf, resourceByWfrn, subjectFieldOf, toConsoleResourceDetail, typeOf, verbEntryOf, wfrnPatternOf, type RouteAnswer, type RouteContext } from './commands'
import { CURRENCY, chargesOf, dayOf } from './cost'
import type { MockData } from './data'
import { accountOf, authorize, type Decision } from './directory'
import { accountOfResource, consoleResourcesOf, countsOf, filterConsoleResources, sortConsoleResources, type ResourceContext } from './resources'

/*
 * Phase 9 in the mock BFF: the copilot (POST /console/v1/assistant/turns, GET …/conversations/{id})
 * and plain-English search (GET /console/v1/search:interpret). Answers null for any path it does
 * not serve, so the server tries the next routes.
 *
 * It is deterministic and honest. The reading of plain English follows the search indexer's rules
 * (console-search-indexer.yaml 1.2.0): types, states and their everyday names, drift, times, sorts,
 * the account's own tag names and values, regions and the query syntax; a word it cannot place is
 * free text while the filter still lists something and left out otherwise; its total is counted
 * with the list's own filtering. The copilot thinks for THINKING_MS on the server's clock, reads
 * only what the person may read, and never acts: it answers in words and in proposals (a filtered
 * list, a filled create form or action) that the console opens for the person to review.
 *
 * The list learns the indexer's `created:` and `updated:` terms here: a list whose `q` holds one is
 * answered by this module, the same rows and the same filtering with the time kept, so a reading's
 * total and the list it opens always agree.
 *
 * A message holding "fail the copilot" fails its first turn in a conversation (assistant.unavailable),
 * so the console's failed state and its Try again can be seen; asked again, the copilot answers the
 * rest of the message.
 */

/* ----------------------------------------------------------------------------
   Limits
   ------------------------------------------------------------------------- */

/** How long the copilot thinks before its answer shows, on the server's clock. */
export const THINKING_MS = 1_200
/** A person's turns a minute; past it, 429 with Retry-After. */
export const TURNS_PER_MINUTE = 10
/** How long a conversation is kept. */
export const KEPT_MS = 24 * 3_600_000
export const MAX_MESSAGE = 2_000
export const MAX_INTERPRET = 500
/** A message holding it fails once per conversation, for the console's failed state. */
export const FAIL_PHRASE = 'fail the copilot'

const MINUTE = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

type Session = RouteContext['session']

interface Scope {
  data: MockData
  s: Session
  now: number
}

function can(scope: Scope, action: string, resource: string): Decision {
  return authorize(scope.data.directory, { userId: scope.s.userId, accountId: scope.s.accountId }, action, resource)
}

function listDecision(scope: Scope, rt: ResourceType): Decision {
  return can(scope, `${serviceOf(rt)}:list`, wfrnPatternOf(scope.data, scope.s, rt))
}

/** The account's rows of a type, as the list serves them. */
function rowsOf(scope: Scope, rt: ResourceType): ConsoleResource[] {
  const rctx: ResourceContext = { partition: scope.data.partition, region: scope.s.region, accountId: scope.s.accountId }
  const accounts = scope.data.directory.accounts.map((a) => a.accountId)
  return consoleResourcesOf(rt.name, scope.data, rctx, accounts, (r) => liveRowOf(scope.data, scope.s, r, scope.now))
}

function accountName(scope: Scope): string {
  return accountOf(scope.data.directory, scope.s.accountId)?.name ?? scope.s.accountId
}

/** Why a refusal refused, in a few plain words (its reason names the whole wfrn, too long to read). */
function whyRefused(d: Decision): string {
  const denied = d.denied
  if (!denied) return 'your access does not allow it'
  if (denied.layer === 'platform') return `the platform's role for this account has no ${denied.scope ?? 'such'} scope`
  return `none of your roles allows ${denied.action}`
}

function refusedAnswer(d: Decision): RouteAnswer {
  return { status: 403, body: { error: d.denied?.reason ?? 'Forbidden', denied: d.denied, correlationId: `mock-${idFrom(JSON.stringify(d.denied ?? {}), 8)}` } }
}

function problem(field: string, message: string): RouteAnswer {
  const problems: Problem[] = [{ level: 'error', code: `assistant.${field.replace(/\W+/g, '-')}`, field, message }]
  return { status: 400, body: { error: 'validation.failed', detail: message, problems } }
}

function pageOf<T>(items: T[], params: URLSearchParams): RouteAnswer {
  const limit = Math.min(200, Math.max(1, Number(params.get('limit') ?? 50) || 50))
  const m = /^c(\d+)$/.exec(params.get('after') ?? '')
  const start = m ? Number(m[1]) : 0
  return { status: 200, body: { items: items.slice(start, start + limit), next: start + limit < items.length ? `c${start + limit}` : null }, headers: { 'X-Total-Count': String(items.length) } }
}

const money = (n: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: CURRENCY, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n)

/** "a, b and c" */
function joinAnd(parts: readonly string[]): string {
  return parts.length < 2 ? (parts[0] ?? '') : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

const paragraphs = (...ps: (string | false | undefined | null)[]) => ps.filter((x): x is string => !!x).join('\n\n')
const article = (word: string) => (/^[aeiou]/i.test(word) ? 'an' : 'a')
const nounOf = (rt: ResourceType | undefined, n: number) => (rt ? (n === 1 ? singularOf(titleOf(rt)) : titleOf(rt)).toLowerCase() : n === 1 ? 'resource' : 'resources')

/* ----------------------------------------------------------------------------
   Times: the indexer's created: and updated: terms, which the list keeps here
   ------------------------------------------------------------------------- */

/** A time term read as a range of milliseconds, `from` kept and `to` left out. */
export interface TimeBound {
  field: 'created' | 'updated'
  from?: number
  to?: number
}

const TERM = /^(created|updated):(.+)$/i
const DAY_TEXT = /^\d{4}-\d{2}-\d{2}$/
const INSTANT_TEXT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/i
const AGE_TEXT = /^(\d{1,5})(m|h|d|w)$/i
const AGE_MS: Record<string, number> = { m: MINUTE, h: HOUR, d: DAY, w: 7 * DAY }
const AGE_UNIT: Record<string, string> = { m: 'minute', h: 'hour', d: 'day', w: 'week' }

interface Point {
  start: number
  end: number
  kind: 'day' | 'instant' | 'age'
}

function pointOf(text: string, now: number): Point | null {
  if (DAY_TEXT.test(text)) {
    const t = Date.parse(`${text}T00:00:00Z`)
    return Number.isNaN(t) ? null : { start: t, end: t + DAY, kind: 'day' }
  }
  if (INSTANT_TEXT.test(text)) {
    const t = Date.parse(text.toUpperCase())
    return Number.isNaN(t) ? null : { start: t, end: t, kind: 'instant' }
  }
  const age = AGE_TEXT.exec(text)
  if (age) {
    const t = now - Number(age[1]) * AGE_MS[age[2].toLowerCase()]
    return { start: t, end: t, kind: 'age' }
  }
  return null
}

/**
 * A `created:` or `updated:` term as the indexer reads it: a day is the whole day in UTC, an age
 * counts back from now (`7d` the last seven days), a comparison keeps one side (`<30d` older than
 * thirty days), `a..b` keeps both ends; a time alone needs a comparison. Null when it is not one.
 */
export function timeBoundOf(term: string, now: number): TimeBound | null {
  const m = TERM.exec(term)
  if (!m) return null
  const field = m[1].toLowerCase() as TimeBound['field']
  const value = m[2]
  const range = /^(.+?)\.\.(.+)$/.exec(value)
  if (range) {
    const a = pointOf(range[1], now)
    const b = pointOf(range[2], now)
    if (!a || !b) return null
    return { field, from: Math.min(a.start, b.start), to: Math.max(a.end, b.end) }
  }
  const op = /^(>=|<=|>|<)(.+)$/.exec(value)
  if (op) {
    const p = pointOf(op[2], now)
    if (!p) return null
    if (op[1] === '>=') return { field, from: p.start }
    if (op[1] === '>') return { field, from: p.end }
    if (op[1] === '<') return { field, to: p.start }
    return { field, to: p.end }
  }
  const p = pointOf(value, now)
  if (!p || p.kind === 'instant') return null
  return p.kind === 'day' ? { field, from: p.start, to: p.end } : { field, from: p.start }
}

/** A list's `q` split into its time terms and the rest, which the list reads as it always has. */
export function splitTimeTerms(q: string, now: number): { bounds: TimeBound[]; rest: string } {
  const bounds: TimeBound[] = []
  const rest: string[] = []
  for (const word of q.split(/\s+/).filter(Boolean)) {
    const b = timeBoundOf(word, now)
    if (b) bounds.push(b)
    else rest.push(word)
  }
  return { bounds, rest: rest.join(' ') }
}

function withinBounds(r: ConsoleResource, bounds: readonly TimeBound[]): boolean {
  return bounds.every((b) => {
    // a resource without the time is in no bounded range
    const t = Date.parse(b.field === 'created' ? r.createdAt : r.updatedAt)
    if (Number.isNaN(t)) return false
    return (b.from === undefined || t >= b.from) && (b.to === undefined || t < b.to)
  })
}

/** The rows a list keeps once its time terms are applied, and its params without them. */
function narrowed(rows: readonly ConsoleResource[], params: URLSearchParams, now: number): { rows: ConsoleResource[]; params: URLSearchParams } {
  const { bounds, rest } = splitTimeTerms(params.get('q') ?? '', now)
  const next = new URLSearchParams(params)
  if (rest) next.set('q', rest)
  else next.delete('q')
  return { rows: bounds.length ? rows.filter((r) => withinBounds(r, bounds)) : [...rows], params: next }
}

/** A filter as the list's query parameters: `q`, `state`, `drift` and `tag.<name>`. */
export function paramsOf(filter: SearchFilter): URLSearchParams {
  const p = new URLSearchParams()
  if (filter.q) p.set('q', filter.q)
  if (filter.state) p.set('state', filter.state)
  if (filter.drift !== undefined) p.set('drift', String(filter.drift))
  for (const [k, v] of Object.entries(filter.tags ?? {})) if (v) p.set(`tag.${k}`, v)
  return p
}

/** How many resources the list shows for a filter: the list's own count. */
function listedCount(rows: readonly ConsoleResource[], filter: SearchFilter, now: number): number {
  const n = narrowed(rows, paramsOf(filter), now)
  return countsOf(n.rows, n.params).total
}

const TIME_IN_Q = /(?:^|\s)(?:created|updated):\S/i

/** `GET /console/v1/r/{type}` and its `:counts` when `q` holds a time term; any other list goes on to the resource routes. */
function timedList(scope: Scope, path: string, params: URLSearchParams): RouteAnswer | null {
  const m = /^\/console\/v1\/r\/([a-z0-9-]{1,64})(:counts)?$/.exec(path)
  if (!m || !TIME_IN_Q.test(params.get('q') ?? '')) return null
  const rt = scope.data.resourceTypes.find((t) => t.name === m[1] || t.plural === m[1])
  if (!rt) return null
  const d = listDecision(scope, rt)
  if (!d.allowed) return refusedAnswer(d)
  const n = narrowed(rowsOf(scope, rt), params, scope.now)
  if (m[2]) return { status: 200, body: countsOf(n.rows, n.params) }
  return pageOf(sortConsoleResources(filterConsoleResources(n.rows, n.params), n.params.get('sort')), params)
}

/* ----------------------------------------------------------------------------
   Reading plain English
   ------------------------------------------------------------------------- */

interface Word {
  /** as typed */
  raw: string
  /** lower case, without its punctuation */
  w: string
  quoted?: boolean
}

function wordsOf(text: string): Word[] {
  const out: Word[] = []
  for (const m of text.matchAll(/"([^"]*)"|“([^”]*)”|(\S+)/g)) {
    const quoted = m[1] ?? m[2]
    if (quoted !== undefined) {
      if (quoted.trim()) out.push({ raw: quoted.trim(), w: quoted.trim().toLowerCase(), quoted: true })
      continue
    }
    const raw = m[3].replace(/^[([{'‘]+/, '').replace(/[)\]}'’.,;:!?]+$/, '')
    if (!raw) continue
    out.push({ raw, w: raw.toLowerCase().replace(/['’]s$/, '') })
  }
  return out
}

/** Words that carry no filter: the glue of a question. */
const FILLER = new Set(
  (
    'a an the all any every each some my our your their its me us i we you they them these those this that which what who where when whose how many much of in on at to for from with by and also plus as ' +
    'is are was were be been have has had do does did can could would should will may might please show find list get give display see view look looking search filter fetch tell want need like ' +
    'there here ones one resources resource items things currently now right just only ok okay hi hello hey thanks thank it state status region type kind sorted sort order ordered first ' +
    'team new tagged labelled labeled owned whats'
  ).split(' '),
)

/** A union or a negation: a filter cannot hold it, so it and what it governs are left out. */
const NEGATION = new Set(['not', 'except', 'excluding', 'without', "isn't", 'isnt', "aren't", 'arent', 'or', 'nor', 'neither'])

const EVERYDAY: [ResourceState, string[]][] = [
  ['active', ['active', 'running', 'ready', 'available', 'healthy', 'online']],
  ['pending', ['pending', 'building', 'creating', 'provisioning', 'starting', 'queued']],
  ['updating', ['updating', 'patching', 'restarting', 'resizing', 'upgrading']],
  ['deleting', ['deleting', 'retiring', 'terminating', 'destroying', 'stopping']],
  ['deleted', ['deleted', 'retired', 'terminated', 'destroyed', 'removed', 'stopped', 'decommissioned']],
  ['failed', ['failed', 'broken', 'error', 'errored', 'failing', 'unhealthy', 'degraded', 'down']],
]

/** "a|b|c" read as phrases with one value; the longest first, so "most recent first" wins over "most recent". */
function table<T>(...groups: [string, T][]): [string, T][] {
  return groups.flatMap(([text, value]) => text.split('|').map((t): [string, T] => [t, value])).sort((a, b) => b[0].split(' ').length - a[0].split(' ').length)
}

const STATE_PHRASES = table<ResourceState>(
  ['being deleted|being removed|being destroyed', 'deleting'],
  ['being created|being built|being provisioned', 'pending'],
  ['being updated|being changed|being patched|being restarted', 'updating'],
  ['needs attention|need attention|needing attention', 'failed'],
)

const DRIFT_PHRASES = table<boolean>(
  ['changed outside of the console|changed outside the console|edited outside the console|modified outside the console|changed by hand|modified by hand|changed manually', true],
  ['out of sync|have drifted|has drifted|with drift|drifted|drifting|drift', true],
  ['in sync|not drifted|never drifted|not drifting|no drift|without drift', false],
)

const SORT_PHRASES = table<string>(
  ['newest to oldest|most recent first|most recent|recently created|newest first|latest first|recent first|newest|latest|recent', '-created'],
  ['oldest to newest|oldest first|oldest', 'created'],
  ['most recently updated|last updated first|latest changes first|recently updated|recently changed', '-updated'],
  ['least recently updated|least recently changed', 'updated'],
  ['sorted by name|ordered by name|by name|a to z|alphabetically|alphabetical|a-z', 'name'],
  ['by name descending|reverse alphabetical|z to a|z-a', '-name'],
)

/** How a sort reads in the explanation. */
export const SORT_LABEL: Record<string, string> = {
  '-created': 'newest first',
  created: 'oldest first',
  name: 'by name',
  '-name': 'by name, Z to A',
  '-updated': 'most recently updated first',
  updated: 'least recently updated first',
}

const STATE_CLAUSE: Record<ResourceState, string> = {
  pending: 'that are being created',
  active: 'that are active',
  updating: 'that are being changed',
  deleting: 'that are being deleted',
  deleted: 'that are deleted',
  failed: 'that failed',
}

const CREATED_WORDS = new Set(['created', 'made', 'built', 'added', 'launched', 'provisioned'])
const UPDATED_WORDS = new Set(['updated', 'changed', 'modified', 'touched', 'edited'])
const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december']
const NUMBER_WORDS: Record<string, number> = {
  ...Object.fromEntries('one two three four five six seven eight nine ten eleven twelve'.split(' ').map((w, i) => [w, i + 1])),
  ...{ a: 1, an: 1, fourteen: 14, fifteen: 15, twenty: 20, thirty: 30, sixty: 60, ninety: 90 },
}

/** Damerau-Levenshtein over adjacent swaps (optimal string alignment). */
function editDistance(a: string, b: string): number {
  const d: number[][] = Array.from({ length: a.length + 1 }, (_, i) => Array.from({ length: b.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)))
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1)
    }
  }
  return d[a.length][b.length]
}

/** A typo away, as the indexer forgives one: none below 4 letters, one up to 6, two from 7, the first letter as typed. */
export function typoAway(typed: string, word: string): boolean {
  if (typed === word || typed.length < 4 || typed[0] !== word[0]) return false
  const max = typed.length <= 6 ? 1 : 2
  return Math.abs(typed.length - word.length) <= max && editDistance(typed, word) <= max
}

interface Vocabulary {
  types: ResourceType[]
  /** each type's names as word lists: name, plural, title, singular title */
  typeForms: { rt: ResourceType; words: string[] }[]
  states: Map<string, ResourceState>
  /** tag name to the values the account uses */
  tags: Map<string, Set<string>>
  /** a tag value to the names that hold it */
  values: Map<string, string[]>
  region: string
}

/** The account's readable rows by type, read once per question. */
class Rows {
  private cache = new Map<string, ConsoleResource[]>()
  readonly scope: Scope
  constructor(scope: Scope) {
    this.scope = scope
  }
  of(rt: ResourceType): ConsoleResource[] {
    let rows = this.cache.get(rt.name)
    if (!rows) {
      rows = listDecision(this.scope, rt).allowed ? rowsOf(this.scope, rt) : []
      this.cache.set(rt.name, rows)
    }
    return rows
  }
}

const tagRank = (key: string) => (key === 'environment' ? 0 : key === 'owner' ? 1 : 2)

function vocabularyOf(scope: Scope, rows: Rows): Vocabulary {
  const types = scope.data.resourceTypes
  const typeForms: Vocabulary['typeForms'] = []
  for (const rt of types) {
    const title = titleOf(rt).toLowerCase()
    for (const form of new Set([rt.name, rt.plural, title, singularOf(title)])) typeForms.push({ rt, words: form.split(/\s+/) })
  }
  const states = new Map<string, ResourceState>()
  for (const [state, words] of EVERYDAY) for (const w of words) states.set(w, state)
  for (const rt of types) {
    for (const w of rt.states ?? []) {
      const cls = lifecycleOf(w, rt.console?.lifecycle)
      if (cls && !states.has(w.toLowerCase())) states.set(w.toLowerCase(), cls)
    }
  }
  const tags = new Map<string, Set<string>>()
  for (const rt of types) {
    for (const r of rows.of(rt)) {
      for (const [k, v] of Object.entries(r.tags)) {
        if (!v) continue
        if (!tags.has(k)) tags.set(k, new Set())
        tags.get(k)!.add(v.toLowerCase())
      }
    }
  }
  // the common tags first, so a value two of them hold reads as theirs
  const values = new Map<string, string[]>()
  for (const k of [...tags.keys()].sort((a, b) => tagRank(a) - tagRank(b) || a.localeCompare(b))) for (const v of tags.get(k)!) values.set(v, [...(values.get(v) ?? []), k])
  return { types, typeForms, states, tags, values, region: scope.data.regionName }
}

/** What the reading holds as it goes. */
interface Reading {
  type?: ResourceType
  /** the text named the type (not only the list it was asked on) */
  typeNamed?: boolean
  state?: ResourceState
  /** the everyday word typed for the state, when it is not the state's own name */
  stateWord?: string
  drift?: boolean
  region?: string
  tags: Record<string, string>
  times: { term: string; label: string }[]
  free: string[]
  sort?: string
  unparsed: { at: number; text: string }[]
  typo: boolean
  otherType: boolean
  understood: number
}

const emptyReading = (): Reading => ({ tags: {}, times: [], free: [], unparsed: [], typo: false, otherType: false, understood: 0 })

type Apply = (r: Reading) => boolean
type Match = { kind: 'filler'; take: number } | { kind: 'drop'; take: number } | { kind: 'phrase'; take: number; apply: Apply } | { kind: 'free'; take: number; text: string }

interface Parser {
  words: Word[]
  vocab: Vocabulary
  listType?: ResourceType
  now: number
}

const phrase = (take: number, apply: Apply): Match => ({ kind: 'phrase', take, apply })
const refuse: Apply = () => false

function setType(p: Parser, rt: ResourceType, typo = false): Apply {
  return (r) => {
    if (p.listType && rt.name !== p.listType.name) {
      r.otherType = true
      return false
    }
    if (r.type && r.type.name !== rt.name) return false
    r.type = rt
    r.typeNamed = true
    if (typo) r.typo = true
    return true
  }
}

function setState(state: ResourceState, word?: string, typo = false): Apply {
  return (r) => {
    if (r.state && r.state !== state) return false
    r.state = state
    if (word && word !== state && !typo) r.stateWord = word
    if (typo) r.typo = true
    return true
  }
}

const setDrift =
  (drift: boolean): Apply =>
  (r) => {
    if (r.drift !== undefined && r.drift !== drift) return false
    r.drift = drift
    return true
  }

const setSort =
  (sort: string): Apply =>
  (r) => {
    if (r.sort && r.sort !== sort) return false
    r.sort = sort
    return true
  }

const setRegion =
  (region: string): Apply =>
  (r) => {
    if (r.region && r.region !== region) return false
    r.region = region
    return true
  }

const setTag =
  (key: string, value: string): Apply =>
  (r) => {
    if (r.tags[key] !== undefined && r.tags[key] !== value) return false
    r.tags[key] = value
    return true
  }

const addTime =
  (term: string, label: string): Apply =>
  (r) => {
    if (!r.times.some((t) => t.term === term)) r.times.push({ term, label })
    return true
  }

/** How many words at i match `text`, word for word; 0 when they do not. */
function phraseAt(words: Word[], i: number, text: string): number {
  const parts = text.split(' ')
  for (let k = 0; k < parts.length; k++) if (words[i + k]?.w !== parts[k] || words[i + k]?.quoted) return 0
  return parts.length
}

function numberOf(word: string | undefined): number | null {
  if (!word) return null
  if (/^\d{1,4}$/.test(word)) return Number(word)
  return NUMBER_WORDS[word] ?? null
}

function unitOf(word: string | undefined): { code: string; times: number; word: string } | null {
  if (!word) return null
  if (/^(mins?|minutes?)$/.test(word)) return { code: 'm', times: 1, word: 'minute' }
  if (/^(h|hrs?|hours?)$/.test(word)) return { code: 'h', times: 1, word: 'hour' }
  if (/^(d|days?)$/.test(word)) return { code: 'd', times: 1, word: 'day' }
  if (/^(w|wks?|weeks?)$/.test(word)) return { code: 'w', times: 1, word: 'week' }
  if (/^months?$/.test(word)) return { code: 'd', times: 30, word: 'month' }
  if (/^years?$/.test(word)) return { code: 'd', times: 365, word: 'year' }
  return null
}

/** "24 hours", "7 days", "a week", "24h": an age as the term spells it and as a person reads it. */
function ageAt(words: Word[], i: number): { take: number; code: string; text: string } | null {
  const glued = /^(\d{1,4})(h|d|w)$/.exec(words[i]?.w ?? '')
  if (glued) {
    const unit = unitOf(glued[2])!
    const n = Number(glued[1])
    return { take: 1, code: `${n}${unit.code}`, text: n === 1 ? unit.word : `${n} ${unit.word}s` }
  }
  const n = numberOf(words[i]?.w)
  const unit = unitOf(words[i + 1]?.w)
  if (n !== null && unit) return { take: 2, code: `${n * unit.times}${unit.code}`, text: n === 1 ? unit.word : `${n} ${unit.word}s` }
  // "in the last hour"
  const bare = unitOf(words[i]?.w)
  if (bare && !/^\d/.test(words[i].w)) return { take: 1, code: `${bare.times}${bare.code}`, text: bare.word }
  return null
}

/** "2026-10-01", "October 1", "Oct 1st 2026", "1 October", "October": a day, the latest one not after today when no year is given. */
function dateAt(words: Word[], i: number, now: number): { take: number; day: string } | null {
  const first = words[i]?.w
  if (!first) return null
  if (DAY_TEXT.test(first)) return Number.isNaN(Date.parse(`${first}T00:00:00Z`)) ? null : { take: 1, day: first }
  const month = (w: string | undefined) => (w && w.length >= 3 ? MONTHS.findIndex((m) => m.startsWith(w)) : -1)
  const dayNum = (w: string | undefined) => {
    const m = /^(\d{1,2})(st|nd|rd|th)?$/.exec(w ?? '')
    return m && Number(m[1]) >= 1 && Number(m[1]) <= 31 ? Number(m[1]) : null
  }
  let mo = month(first)
  let d: number | null
  let take: number
  if (mo >= 0) {
    d = dayNum(words[i + 1]?.w)
    take = d === null ? 1 : 2
  } else {
    d = dayNum(first)
    mo = d === null ? -1 : month(words[i + 1]?.w)
    if (mo < 0) return null
    take = 2
  }
  let year = /^\d{4}$/.test(words[i + take]?.w ?? '') ? Number(words[i + take].w) : null
  if (year !== null) take += 1
  else {
    year = new Date(now).getUTCFullYear()
    if (Date.UTC(year, mo, d ?? 1) > now) year -= 1
  }
  const t = Date.UTC(year, mo, d ?? 1)
  if (new Date(t).getUTCMonth() !== mo) return null
  return { take, day: dayOf(t) }
}

/** Times: "today", "this week", "in the last 24 hours", "since October 1", "older than 90 days", "not updated in 30 days". */
function timeAt(p: Parser, i: number): Match | null {
  const { words, now } = p
  const w = (k: number) => words[i + k]?.w
  // "not updated in 30 days", "not changed since October 1", "untouched for a week"
  const notUpdated = w(0) === 'not' && (UPDATED_WORDS.has(w(1) ?? '') || (w(1) === 'been' && UPDATED_WORDS.has(w(2) ?? '')))
  if (notUpdated || w(0) === 'untouched' || w(0) === 'unchanged' || w(0) === 'stale') {
    let k = notUpdated ? (w(1) === 'been' ? 3 : 2) : 1
    if (w(k) === 'since') {
      const date = dateAt(words, i + k + 1, now)
      return date ? phrase(k + 1 + date.take, addTime(`updated:<${date.day}`, `not updated since ${date.day}`)) : null
    }
    if (w(k) === 'in' || w(k) === 'for' || w(k) === 'within') k += 1
    if (w(k) === 'the') k += 1
    if (w(k) === 'last' || w(k) === 'past') k += 1
    const age = ageAt(words, i + k)
    return age ? phrase(k + age.take, addTime(`updated:<${age.code}`, `not updated in ${age.text}`)) : null
  }
  let k = 0
  let field: TimeBound['field'] = 'created'
  if (CREATED_WORDS.has(w(0) ?? '')) k = 1
  else if (UPDATED_WORDS.has(w(0) ?? '')) {
    field = 'updated'
    k = 1
  }
  const midnight = Math.floor(now / DAY) * DAY
  if (w(k) === 'today') return phrase(k + 1, addTime(`${field}:${dayOf(now)}`, `${field} today (${dayOf(now)})`))
  if (w(k) === 'yesterday') return phrase(k + 1, addTime(`${field}:${dayOf(now - DAY)}`, `${field} yesterday (${dayOf(now - DAY)})`))
  if (w(k) === 'this' && (w(k + 1) === 'week' || w(k + 1) === 'month' || w(k + 1) === 'year')) {
    const date = new Date(now)
    const since =
      w(k + 1) === 'week' ? midnight - ((date.getUTCDay() + 6) % 7) * DAY : w(k + 1) === 'month' ? Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1) : Date.UTC(date.getUTCFullYear(), 0, 1)
    return phrase(k + 2, addTime(`${field}:>=${dayOf(since)}`, `${field} this ${w(k + 1)} (since ${dayOf(since)})`))
  }
  // "in the last 24 hours", "past week", "within 7 days"
  let j = k
  if (w(j) === 'in' || w(j) === 'within' || w(j) === 'over') j += 1
  if (w(j) === 'the') j += 1
  const counted = w(j) === 'last' || w(j) === 'past'
  if (counted || (w(k) === 'within' && j > k)) {
    const age = ageAt(words, i + j + (counted ? 1 : 0))
    if (age) return phrase(j + (counted ? 1 : 0) + age.take, addTime(`${field}:${age.code}`, `${field} in the last ${age.text}`))
  }
  const when = w(k)
  if (when === 'since' || when === 'before' || when === 'after' || (when === 'on' && (k > 0 || DAY_TEXT.test(w(k + 1) ?? '')))) {
    const date = dateAt(words, i + k + 1, now)
    if (date) {
      const op = when === 'since' ? '>=' : when === 'before' ? '<' : when === 'after' ? '>' : ''
      return phrase(k + 1 + date.take, addTime(`${field}:${op}${date.day}`, `${field} ${when} ${date.day}`))
    }
  }
  // "older than 90 days", "newer than a week"
  if ((when === 'older' || when === 'newer' || when === 'younger') && w(k + 1) === 'than') {
    const age = ageAt(words, i + k + 2)
    if (age) {
      const older = when === 'older'
      return phrase(k + 2 + age.take, addTime(older ? `${field}:<${age.code}` : `${field}:${age.code}`, older ? `${field} more than ${age.text} ago` : `${field} in the last ${age.text}`))
    }
  }
  // "created 2026-10-01"
  if (k === 1 && DAY_TEXT.test(w(1) ?? '')) return phrase(2, addTime(`${field}:${w(1)}`, `${field} on ${w(1)}`))
  return null
}

/** A time term typed in the syntax, as the explanation says it. */
function describeTerm(term: string): string {
  const field = term.slice(0, term.indexOf(':')).toLowerCase()
  const value = term.slice(term.indexOf(':') + 1)
  const age = (v: string) => {
    const m = AGE_TEXT.exec(v)
    if (!m) return null
    const n = Number(m[1])
    return n === 1 ? AGE_UNIT[m[2].toLowerCase()] : `${n} ${AGE_UNIT[m[2].toLowerCase()]}s`
  }
  if (value.includes('..')) return `${field} between ${value.replace('..', ' and ')}`
  const op = /^(>=|<=|>|<)(.+)$/.exec(value)
  if (op) {
    const a = age(op[2])
    if (op[1] === '<' || op[1] === '<=') return a ? `${field} more than ${a} ago` : `${field} before ${op[2]}`
    return a ? `${field} in the last ${a}` : `${field} since ${op[2]}`
  }
  const a = age(value)
  return a ? `${field} in the last ${a}` : `${field} on ${value}`
}

/** The query syntax: type:, state:, region:, drift:, tag:, created:, updated:, a wfrn pattern, and a tag as name=value. */
function syntaxAt(p: Parser, i: number): Match | null {
  const word = p.words[i]
  if (word.quoted || word.w.startsWith('wfrn:')) return { kind: 'free', take: 1, text: word.raw }
  const w = word.w
  let m = /^type:(.+)$/.exec(w)
  if (m) {
    const name = m[1]
    const rt = p.vocab.types.find((t) => t.name === name || t.plural === name)
    return phrase(1, rt ? setType(p, rt) : refuse)
  }
  if ((m = /^state:(.+)$/.exec(w))) {
    const state = (RESOURCE_STATES as readonly string[]).includes(m[1]) ? (m[1] as ResourceState) : p.vocab.states.get(m[1])
    return phrase(1, state ? setState(state, m[1]) : refuse)
  }
  if ((m = /^region:(.+)$/.exec(w))) return phrase(1, m[1] === p.vocab.region ? setRegion(m[1]) : refuse)
  if ((m = /^drift:(.+)$/.exec(w))) return phrase(1, ['true', 'yes'].includes(m[1]) ? setDrift(true) : ['false', 'no'].includes(m[1]) ? setDrift(false) : refuse)
  if ((m = /^tag:([a-z0-9._:/-]+)=(.+)$/.exec(w))) return phrase(1, setTag(m[1], m[2]))
  // `tag:<name>` alone means "has the tag", which the console's list cannot hold: left out
  if (w.startsWith('tag:')) return phrase(1, refuse)
  if (/^(created|updated):/.test(w)) return phrase(1, timeBoundOf(w, p.now) ? addTime(w, describeTerm(w)) : refuse)
  if ((m = /^([a-z0-9-]{1,32})[=:](.+)$/.exec(w))) return phrase(1, p.vocab.tags.has(m[1]) ? setTag(m[1], m[2]) : refuse)
  return null
}

function tableAt<T>(p: Parser, i: number, table: [string, T][]): { take: number; value: T } | null {
  for (const [text, value] of table) {
    const take = phraseAt(p.words, i, text)
    if (take) return { take, value }
  }
  return null
}

/** A tag: "environment prod", "the prod environment", or a value the account uses ("prod", "team-compute"). */
function tagAt(p: Parser, i: number): Match | null {
  const { words, vocab } = p
  const w = words[i].w
  const next = words[i + 1]?.quoted ? undefined : words[i + 1]?.w
  if (vocab.tags.has(w) && next && vocab.tags.get(w)!.has(next)) return phrase(2, setTag(w, next))
  const keys = vocab.values.get(w)
  if (!keys || FILLER.has(w) || w.length < 3) return null
  const named = next?.replace(/s$/, '')
  if (named && keys.includes(named)) return phrase(2, setTag(named, w))
  return phrase(1, setTag(keys[0], w))
}

/** A type by its name, plural or title, exactly or a typo away. */
function typeAt(p: Parser, i: number, typos: boolean): Match | null {
  let best: { rt: ResourceType; take: number; typo: boolean } | null = null
  for (const { rt, words } of p.vocab.typeForms) {
    let typo = false
    const fits = words.every((part, k) => {
      const typed = p.words[i + k]
      if (!typed || typed.quoted) return false
      if (typed.w === part) return true
      if (typos && typoAway(typed.w, part)) return (typo = true)
      return false
    })
    if (fits && (!best || words.length > best.take || (words.length === best.take && best.typo && !typo))) best = { rt, take: words.length, typo }
  }
  return best ? phrase(best.take, setType(p, best.rt, best.typo)) : null
}

function matchAt(p: Parser, i: number): Match | null {
  const w = p.words[i].w
  const syntax = syntaxAt(p, i)
  if (syntax) return syntax
  const time = timeAt(p, i)
  if (time) return time
  const sort = tableAt(p, i, SORT_PHRASES)
  if (sort) return phrase(sort.take, setSort(sort.value))
  const drift = tableAt(p, i, DRIFT_PHRASES)
  if (drift) return phrase(drift.take, setDrift(drift.value))
  const statePhrase = tableAt(p, i, STATE_PHRASES)
  if (statePhrase) return phrase(statePhrase.take, setState(statePhrase.value))
  if (NEGATION.has(w)) return { kind: 'drop', take: 1 }
  const tag = tagAt(p, i)
  if (tag) return tag
  const type = typeAt(p, i, false)
  if (type) return type
  const state = p.vocab.states.get(w)
  if (state) return phrase(1, setState(state, w))
  if (w === p.vocab.region) return phrase(1, setRegion(w))
  if (FILLER.has(w)) return { kind: 'filler', take: 1 }
  const typoType = typeAt(p, i, true)
  if (typoType) return typoType
  for (const [word, st] of p.vocab.states) if (typoAway(w, word)) return phrase(1, setState(st, word, true))
  return null
}

/** The reading of a text, before its free text is weighed against what it lists. */
function parse(p: Parser): { reading: Reading; candidates: { at: number; text: string }[] } {
  const r = emptyReading()
  const candidates: { at: number; text: string }[] = []
  let drop: { at: number; text: string } | null = null
  let i = 0
  while (i < p.words.length) {
    const m = matchAt(p, i)
    const take = m?.take ?? 1
    const raw = p.words
      .slice(i, i + take)
      .map((x) => x.raw)
      .join(' ')
    if (m?.kind === 'filler') {
      i += take
      continue
    }
    if (m?.kind === 'drop') {
      drop = drop ? { at: drop.at, text: `${drop.text} ${raw}` } : { at: i, text: raw }
      i += take
      continue
    }
    if (drop) {
      r.unparsed.push({ at: drop.at, text: `${drop.text} ${raw}` })
      drop = null
    } else if (m?.kind === 'phrase') {
      if (m.apply(r)) r.understood += 1
      else r.unparsed.push({ at: i, text: raw })
    } else if (m?.kind === 'free') r.free.push(m.text)
    else candidates.push({ at: i, text: raw })
    i += take
  }
  if (drop) r.unparsed.push(drop)
  return { reading: r, candidates }
}

function filterOf(r: Reading, free = r.free): SearchFilter {
  const q = [...r.times.map((t) => t.term), ...free].join(' ')
  const filter: SearchFilter = {}
  if (q) filter.q = q
  if (r.state) filter.state = r.state
  if (r.drift !== undefined) filter.drift = r.drift
  if (r.region) filter.region = r.region
  if (Object.keys(r.tags).length) filter.tags = { ...r.tags }
  return filter
}

function totalOf(rows: Rows, types: readonly ResourceType[], filter: SearchFilter): number {
  return types.reduce((n, rt) => n + listedCount(rows.of(rt), filter, rows.scope.now), 0)
}

/** The sentence a reading is checked by: "Virtual machines that failed, tagged environment=prod, created this week (since 2026-10-19), newest first." */
function sentenceOf(r: Reading, type: ResourceType | undefined): string {
  const subject = type ? titleOf(type) : 'Resources'
  const parts: string[] = []
  if (r.state) parts.push(`${STATE_CLAUSE[r.state]}${r.stateWord ? ` (the console's state for "${r.stateWord}")` : ''}`)
  if (r.drift !== undefined) parts.push(r.drift ? 'that drifted' : 'in sync with what was asked for')
  const tags = Object.entries(r.tags)
  if (tags.length) parts.push(`tagged ${tags.map(([k, v]) => `${k}=${v}`).join(' and ')}`)
  if (r.region) parts.push(`in ${r.region}`)
  for (const t of r.times) parts.push(t.label)
  if (r.free.length) parts.push(`matching ${r.free.map((f) => `"${f}"`).join(' and ')}`)
  if (r.sort) parts.push(SORT_LABEL[r.sort])
  if (!parts.length) return `All ${subject.toLowerCase()}.`
  return `${subject} ${parts.join(', ')}.`
}

function confidenceOf(r: Reading): Confidence {
  const structured = !!r.typeNamed || !!r.state || r.drift !== undefined || !!r.region || Object.keys(r.tags).length > 0 || r.times.length > 0 || !!r.sort
  if (r.unparsed.length || r.otherType) return 'low'
  if (!structured && r.free.length) return 'low'
  if (r.free.length || r.typo || r.understood === 0) return 'medium'
  return 'high'
}

/** A reading, with what it lists and how it got there. */
interface Interpreted extends SearchInterpretation {
  reading: Reading
  /** the type the total counts: the text's, else the list's */
  rt?: ResourceType
}

/** `search:interpret`'s reading of a text in the person's account, with what the list shows for it. */
function interpretText(scope: Scope, text: string, listTypeName?: string, rows = new Rows(scope)): Interpreted {
  const listType = listTypeName ? scope.data.resourceTypes.find((t) => t.name === listTypeName || t.plural === listTypeName) : undefined
  const vocab = vocabularyOf(scope, rows)
  const { reading, candidates } = parse({ words: wordsOf(text), vocab, listType, now: scope.now })
  const rt = reading.type ?? listType
  const types = rt ? [rt] : scope.data.resourceTypes
  // a word it cannot place is free text while the filter still lists something
  for (const c of candidates) {
    if (totalOf(rows, types, filterOf(reading, [...reading.free, c.text])) > 0) reading.free.push(c.text)
    else reading.unparsed.push(c)
  }
  reading.unparsed.sort((a, b) => a.at - b.at)
  const filter = filterOf(reading)
  const syntax = [
    rt && reading.typeNamed ? `type:${rt.name}` : null,
    filter.state ? `state:${filter.state}` : null,
    filter.region ? `region:${filter.region}` : null,
    filter.drift !== undefined ? `drift:${filter.drift}` : null,
    ...Object.entries(filter.tags ?? {}).map(([k, v]) => `tag:${k}=${v}`),
    filter.q ?? null,
  ]
  const out: Interpreted = {
    filter,
    explanation: sentenceOf(reading, rt),
    confidence: confidenceOf(reading),
    q: syntax.filter((x): x is string => !!x).join(' '),
    total: totalOf(rows, types, filter),
    reading,
    rt,
  }
  if (rt) out.type = rt.name
  if (reading.sort) out.sort = reading.sort
  if (reading.unparsed.length) out.unparsed = reading.unparsed.map((u) => u.text)
  return out
}

/** `GET /console/v1/search:interpret` for the session: the reading as the indexer answers it. */
export function interpret(data: MockData, session: Session, now: number, text: string, type?: string): SearchInterpretation {
  const { reading: _reading, rt: _rt, ...out } = interpretText({ data, s: session, now }, text, type)
  return out
}

/* ----------------------------------------------------------------------------
   The copilot's answers
   ------------------------------------------------------------------------- */

interface Answer {
  status: 'answered' | 'failed'
  text?: string
  proposals?: AssistantProposal[]
  explanation?: ResourceExplanation
  error?: { code: string; message: string }
}

/** "deleted virtual machines tagged environment=prod": a filter as a list's name. */
function phraseOf(r: Reading, rt: ResourceType | undefined, n: number, filter: SearchFilter): string {
  const quals: string[] = []
  if (filter.drift !== undefined) quals.push(filter.drift ? 'that drifted' : 'in sync')
  const tags = Object.entries(filter.tags ?? {})
  if (tags.length) quals.push(`tagged ${tags.map(([k, v]) => `${k}=${v}`).join(' and ')}`)
  for (const t of r.times) quals.push(t.label)
  const free = (filter.q ?? '').split(/\s+/).filter((w) => w && !TERM.test(w))
  if (free.length) quals.push(`matching ${free.map((f) => `"${f}"`).join(' and ')}`)
  return `${filter.state ? `${filter.state} ` : ''}${nounOf(rt, n)}${quals.length ? ` ${quals.join(', ')}` : ''}`
}

function filterProposal(rt: ResourceType, label: string, filter: SearchFilter, sort?: string): AssistantProposal {
  const p: AssistantProposal = { kind: 'filter', label, service: serviceOf(rt), type: rt.name, filter }
  if (sort) p.sort = sort
  return p
}

/** The list's own filter with a reading laid over it, for "these" and "them" asked on a list. */
function mergedFilter(base: SearchFilter, over: SearchFilter): SearchFilter {
  const out: SearchFilter = { ...base, ...over }
  const tags = { ...(base.tags ?? {}), ...(over.tags ?? {}) }
  if (Object.keys(tags).length) out.tags = tags
  const q = [base.q, over.q].filter(Boolean).join(' ')
  if (q) out.q = q
  else delete out.q
  return out
}

const HELP = paragraphs(
  'I turn plain English into the console’s own filters, forms and actions, and I explain resources. I never change anything: you check what I open, and every change still goes through its review and your access.',
  'Try “Find stopped virtual machines in prod”, “Create a small VM in dev”, “Restart web-01”, or Explain on a resource’s page.',
)

function answerFilter(scope: Scope, text: string, context: AssistantContext | undefined): Answer {
  const rows = new Rows(scope)
  const listType = context?.type ? scope.data.resourceTypes.find((t) => t.name === context.type) : undefined
  const it = interpretText(scope, text, listType?.name, rows)
  const r = it.reading
  const leftOut = it.unparsed?.length ? `I left out: ${it.unparsed.join(', ')}.` : null
  const structured = r.typeNamed || r.state || r.drift !== undefined || r.region || Object.keys(r.tags).length || r.times.length || r.sort || r.free.length
  if (!structured) {
    return {
      status: 'answered',
      text: paragraphs(
        'I am not sure what you mean: I could not read it as a filter, a form or an action.',
        leftOut,
        'Try a type and a state, such as “failed virtual machines in prod”, or ask me to create something.',
      ),
    }
  }
  const low = it.confidence === 'low'
  const unsure = low ? 'I am not sure I understood. Here is what I could read: check it, and apply it only if it is what you meant.' : null
  // "which of these drifted?" on a list keeps the list's own filter
  const onThese = !!context?.filter && !!listType && it.rt?.name === listType.name && /\b(these|those|them|this list|the list|here)\b/i.test(text)
  const filter = onThese ? mergedFilter(context!.filter!, it.filter) : it.filter
  const types = it.rt ? [it.rt] : scope.data.resourceTypes
  const readable = types.filter((rt) => listDecision(scope, rt).allowed)
  if (it.rt && !readable.length)
    return { status: 'answered', text: `You cannot list ${titleOf(it.rt).toLowerCase()} in ${accountName(scope)}: ${whyRefused(listDecision(scope, it.rt))}. I read only what you may read.` }
  const counted = readable.map((rt) => ({ rt, n: listedCount(rows.of(rt), filter, scope.now) }))
  const reading = `I read it as: ${it.explanation}${onThese ? ' That is on top of the list’s own filter.' : ''}`
  if (it.rt) {
    const n = counted[0].n
    const name = phraseOf(r, it.rt, n, filter)
    const label = n ? `List the ${n} ${name}` : `List the ${name} (none now)`
    const count = n ? `${n} ${name} ${n === 1 ? 'matches' : 'match'} right now.` : 'Nothing matches that right now.'
    return { status: 'answered', text: low ? paragraphs(unsure, reading, leftOut, count) : paragraphs(count, reading), proposals: [filterProposal(it.rt, label, filter, it.sort)] }
  }
  // no type named: the types that list something, the most first
  const found = counted.filter((c) => c.n > 0).sort((a, b) => b.n - a.n || titleOf(a.rt).localeCompare(titleOf(b.rt)))
  if (!found.length) return { status: 'answered', text: paragraphs(unsure, reading, leftOut, 'Nothing of any type matches that right now.') }
  const proposals = found.slice(0, 3).map((c) => filterProposal(c.rt, `List the ${c.n} ${phraseOf(r, c.rt, c.n, filter)}`, filter, it.sort))
  const total = found.reduce((n, c) => n + c.n, 0)
  const count = `${total} ${total === 1 ? 'resource matches' : 'resources match'}: ${joinAnd(found.map((c) => `${c.n} ${nounOf(c.rt, c.n)}`))}.`
  return { status: 'answered', text: low ? paragraphs(unsure, reading, leftOut, count) : paragraphs(count, reading), proposals }
}

const CREATE_START =
  /^(?:please\s+|can you\s+|could you\s+|would you\s+|i (?:want|need|would like) to\s+|i'd like to\s+|let'?s\s+|help me\s+)?(create|launch|provision|spin up|deploy|make|add|build|set up|request)\b/i
const WANT_ONE = /^(?:i need|i want|give me|get me)\s+(?:a|an|another|one|\d+)\s/i
const VERB_START = /^(?:please\s+|can you\s+|could you\s+|would you\s+|i (?:want|need) to\s+)?(restart|reboot|patch|delete|remove|destroy|retire|decommission|upgrade)\b/i
const VERB_SYNONYM: Record<string, string> = { reboot: 'restart', remove: 'delete', destroy: 'delete', retire: 'delete', decommission: 'delete' }

/** The console's own pages, by the words a person would use for them: "open the budgets", "take me to cost". */
const PAGES: { words: string[]; path: string; title: string }[] = [
  { words: ['cost and usage', 'cost explorer', 'cost', 'costs', 'spend'], path: '/cost', title: 'Cost and usage' },
  { words: ['budgets', 'budget'], path: '/cost/budgets', title: 'Budgets' },
  { words: ['chargeback'], path: '/cost/chargeback', title: 'Chargeback' },
  { words: ['executive home', 'insights'], path: '/insights', title: 'Executive home' },
  { words: ['requests', 'my requests', 'jobs'], path: '/requests', title: 'Requests' },
  { words: ['access requests'], path: '/iam/access-requests', title: 'Access requests' },
  { words: ['users', 'people'], path: '/iam/users', title: 'Users' },
  { words: ['groups'], path: '/iam/groups', title: 'Groups' },
  { words: ['roles'], path: '/iam/roles', title: 'Roles' },
  { words: ['access keys'], path: '/iam/access-keys', title: 'Access keys' },
  { words: ['identity and access', 'iam'], path: '/iam', title: 'Identity and access' },
  { words: ['settings'], path: '/settings', title: 'Settings' },
  { words: ['home', 'console home'], path: '/', title: 'Console home' },
]
const OPEN_START = /^(?:please\s+)?(?:open|go to|take me to|show me|show)\s+(?:the\s+|my\s+)?(.+?)(?:\s+page)?[?.!]*$/i

/** A page named on its own: a proposal to open it. */
function pageAnswer(text: string): Answer | null {
  const target = OPEN_START.exec(text)?.[1]?.toLowerCase().trim()
  const page = target ? PAGES.find((p) => p.words.includes(target)) : undefined
  if (!page) return null
  return { status: 'answered', text: `${page.title} is a page of its own: open it from here.`, proposals: [{ kind: 'open', label: `Open ${page.title}`, path: page.path }] }
}

/** The type a text names, exactly or a typo away, else none. */
function typeNamedIn(scope: Scope, text: string): ResourceType | undefined {
  const p: Parser = { words: wordsOf(text), vocab: vocabularyOf(scope, new Rows(scope)), now: scope.now }
  for (let i = 0; i < p.words.length; i++) {
    const m = typeAt(p, i, false) ?? (FILLER.has(p.words[i].w) ? null : typeAt(p, i, true))
    if (m?.kind !== 'phrase') continue
    const r = emptyReading()
    if (m.apply(r) && r.type) return r.type
  }
  return undefined
}

const fieldTitle = (entry: CatalogEntry, key: string) => entry.input?.properties?.[key]?.title ?? capitalise(key)

/** "VM name web-77", "reason “disk full”", "CVE-2026-1234": a filled input as a sentence names it. */
function filledText(entry: CatalogEntry, input: Record<string, unknown>, shown: Record<string, string>): string {
  const lower = (t: string) =>
    t
      .split(' ')
      .map((w, i) => (/^[A-Z0-9]{2,}$/.test(w) ? w : i === 0 ? w.toLowerCase() : w))
      .join(' ')
  return joinAnd(
    Object.entries(input).map(([k, v]) => {
      const title = fieldTitle(entry, k)
      const value = shown[k] ?? String(v)
      if (value.toLowerCase().startsWith(title.toLowerCase())) return value
      return `${lower(title)} ${/\s/.test(value) ? `“${value}”` : value}`
    }),
  )
}

/** The inputs a text fills: enum values by name, a name after "called" or "named", an owner team, a CVE, a reason after "because", an image by its system. */
function inputsFrom(scope: Scope, text: string, entry: CatalogEntry, subject?: string): { input: Record<string, unknown>; shown: Record<string, string> } {
  const props = entry.input?.properties ?? {}
  const words = wordsOf(text).map((w) => w.w)
  const out: Record<string, unknown> = {}
  const shown: Record<string, string> = {}
  for (const [key, prop] of Object.entries(props)) {
    if (key === subject || !Array.isArray(prop?.enum)) continue
    const hit = prop.enum.find((v) => typeof v === 'string' && words.includes(v.toLowerCase()))
    if (hit !== undefined) out[key] = hit
  }
  const named = /\b(?:called|named)\s+["']?([a-z][a-z0-9-]{2,30})\b/i.exec(text)
  if (named && 'name' in props && subject !== 'name') out.name = named[1].toLowerCase()
  const team = /\b(team-[a-z0-9-]+)\b/i.exec(text)
  if (team && 'owner' in props) out.owner = team[1].toLowerCase()
  const cve = /\b(CVE-\d{4}-\d{4,})\b/i.exec(text)
  if (cve && 'cve' in props) out.cve = cve[1].toUpperCase()
  const reason = /\b(?:because|reason:?)\s+(.{3,200}?)\s*[.!?]*$/i.exec(text)
  if (reason && 'reason' in props) out.reason = reason[1]
  const keep = /\bkeep(?:ing)?\s+(?:the\s+)?snapshots?\s+(?:for\s+)?(\d{1,2})\s+days?\b/i.exec(text)
  if (keep && 'keepSnapshotDays' in props) out.keepSnapshotDays = Number(keep[1])
  // an image by its operating system: the newest active one whose system the text names
  const image = Object.entries(props).find(([k, prop]) => k !== subject && prop?.['x-resource']?.type === 'image')
  const imageType = typeOf(scope.data, 'image')
  const os = words.find((w) => ['ubuntu', 'rhel', 'windows'].includes(w))
  if (image && imageType && os && listDecision(scope, imageType).allowed) {
    const found = rowsOf(scope, imageType)
      .filter(
        (r) =>
          r.state === 'active' &&
          String(r.attributes.os ?? r.name ?? '')
            .toLowerCase()
            .startsWith(os),
      )
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
    const id = found?.wfrn.split('/').slice(1).join('/')
    if (found && id) {
      out[image[0]] = id
      shown[image[0]] = found.name ?? id
    }
  }
  return { input: out, shown }
}

/** Required inputs neither filled nor given a default by the form. */
function missingOf(entry: CatalogEntry, input: Record<string, unknown>, subject?: string): string[] {
  const props = entry.input?.properties ?? {}
  return (entry.input?.required ?? []).filter((k) => k !== subject && input[k] === undefined && props[k]?.default === undefined)
}

function answerCreate(scope: Scope, text: string, context: AssistantContext | undefined): Answer {
  const rt = typeNamedIn(scope, text) ?? (context?.type ? typeOf(scope.data, context.type) : undefined)
  if (!rt) {
    const names = scope.data.resourceTypes.map((t) => titleOf(t).toLowerCase())
    return { status: 'answered', text: paragraphs('What would you like to create?', `I can fill the create form for ${joinAnd(names)}. Name one, such as “Create a small VM in dev”.`) }
  }
  const entry = createEntryOf(scope.data, rt)
  const singular = nounOf(rt, 1)
  if (!entry) return { status: 'answered', text: `No workflow creates ${titleOf(rt).toLowerCase()} yet, so there is no form to fill.` }
  const d = can(scope, `${serviceOf(rt)}:create`, wfrnPatternOf(scope.data, scope.s, rt))
  if (!d.allowed)
    return {
      status: 'answered',
      text: paragraphs(`You cannot create ${titleOf(rt).toLowerCase()} in ${accountName(scope)}: ${whyRefused(d)}.`, 'The Create button shows the refusal and how to ask for access.'),
    }
  const { input, shown } = inputsFrom(scope, text, entry)
  const missing = missingOf(entry, input)
  const filled = filledText(entry, input, shown)
  const size = typeof input.size === 'string' ? `${input.size} ` : ''
  const where = typeof input.environment === 'string' ? ` in ${input.environment}` : ''
  const called = typeof input.name === 'string' ? ` called ${input.name}` : ''
  const proposal: AssistantProposal = { kind: 'create', label: `Create ${article(size || singular)} ${size}${singular}${where}${called}`, service: serviceOf(rt), type: rt.name, input }
  if (missing.length) proposal.missing = missing
  return {
    status: 'answered',
    text: paragraphs(
      filled ? `I filled the form to create ${article(singular)} ${singular}: ${filled}.` : `I opened the form to create ${article(singular)} ${singular}.`,
      missing.length ? `It still needs: ${missing.map((k) => fieldTitle(entry, k)).join(', ')}.` : 'Every required field is filled.',
      'Nothing is created until you check the form and submit it. Its review shows the cost and what policy says first.',
    ),
    proposals: [proposal],
  }
}

function answerVerb(scope: Scope, text: string, verbWord: string, context: AssistantContext | undefined): Answer {
  const verb = VERB_SYNONYM[verbWord] ?? verbWord
  const named = typeNamedIn(scope, text)
  const contextType = context?.type ? typeOf(scope.data, context.type) : undefined
  const candidates = named ? [named] : contextType ? [contextType] : scope.data.resourceTypes.filter((rt) => verbEntryOf(scope.data, rt, verb))
  // the resource the text names, else the page it was asked on
  const words = new Set(wordsOf(text).map((w) => w.w))
  let found: { rt: ResourceType; row: ConsoleResource } | undefined
  for (const rt of candidates) {
    if (!listDecision(scope, rt).allowed) continue
    const row = rowsOf(scope, rt).find((x) => !!x.name && words.has(x.name.toLowerCase()))
    if (row) {
      found = { rt, row }
      break
    }
  }
  if (!found && context?.wfrn) {
    const rt = contextType ?? scope.data.resourceTypes.find((t) => context.wfrn!.includes(`:${t.name}/`))
    const r = rt ? resourceByWfrn(scope.data, rt, context.wfrn) : undefined
    if (rt && r && can(scope, `${serviceOf(rt)}:get`, context.wfrn).allowed) found = { rt, row: liveRowOf(scope.data, scope.s, r, scope.now) }
  }
  if (!found) return { status: 'answered', text: paragraphs(`Which one should I ${verb}?`, `Name it, such as “${verb} web-01”, or ask from its page.`) }
  const { rt, row } = found
  const name = row.name ?? row.wfrn
  const entry = verbEntryOf(scope.data, rt, verb)
  if (!entry) return { status: 'answered', text: `${titleOf(rt)} have no ${verb} action, so there is nothing to fill for ${name}.` }
  if (row.state === 'deleted') return { status: 'answered', text: `${name} is deleted, so there is nothing to ${verb}.` }
  const d = can(scope, `${serviceOf(rt)}:${verb}`, row.wfrn)
  if (!d.allowed) return { status: 'answered', text: paragraphs(`You cannot ${verb} ${name}: ${whyRefused(d)}.`, 'Its Actions menu shows the refusal and how to ask for access.') }
  const subject = subjectFieldOf(entry, rt)
  const { input, shown } = inputsFrom(scope, text, entry, subject)
  const missing = missingOf(entry, input, subject)
  const destructive = entry.destructive ?? verb === 'delete'
  const filled = filledText(entry, input, shown)
  const proposal: AssistantProposal = {
    kind: 'action',
    label: `${capitalise(verb)} ${name}${typeof input.cve === 'string' ? ` for ${input.cve}` : ''}`,
    service: serviceOf(rt),
    type: rt.name,
    wfrn: row.wfrn,
    verb,
    input,
  }
  if (missing.length) proposal.missing = missing
  return {
    status: 'answered',
    text: paragraphs(
      filled ? `I filled the ${verb} action for ${name}: ${filled}.` : `I opened the ${verb} action for ${name}.`,
      missing.length ? `It still needs: ${missing.map((k) => fieldTitle(entry, k)).join(', ')}.` : null,
      row.state !== 'active' && row.state !== 'failed' ? `${name} is ${row.state} now; the action runs once it is active.` : null,
      `It runs only when you submit it from the drawer, after its preview${destructive ? ' and your confirmation' : ''}.`,
    ),
    proposals: [proposal],
  }
}

/** Every setting but the secret ones, in a few words: "image image-demo01, 4 CPUs, 16 GB of memory, environment prod". */
function configOf(rt: ResourceType, attributes: Record<string, unknown>): string[] {
  const hidden = new Set(sensitiveFields(rt.schema))
  const parts: string[] = []
  for (const [k, v] of Object.entries(attributes)) {
    if (k === 'name' || hidden.has(k) || v === undefined || v === null || v === '' || typeof v === 'object') continue
    if (k === 'cpu') parts.push(`${v} CPUs`)
    else if (k === 'memoryGb') parts.push(`${v} GB of memory`)
    else if (k === 'sizeGb') parts.push(`${v} GB`)
    else parts.push(`${k.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()} ${String(v)}`)
  }
  return parts
}

/** The few settings a summary names: size, engine, tier. */
function headlineOf(attributes: Record<string, unknown>): string[] {
  const out: string[] = []
  if (typeof attributes.cpu === 'number') out.push(`${attributes.cpu} CPUs`)
  if (typeof attributes.memoryGb === 'number') out.push(`${attributes.memoryGb} GB of memory`)
  if (typeof attributes.engine === 'string') out.push(`${attributes.engine}${typeof attributes.version === 'string' ? ` ${attributes.version}` : ''}`)
  if (typeof attributes.tier === 'string') out.push(`tier ${attributes.tier}`)
  if (typeof attributes.sizeGb === 'number') out.push(`${attributes.sizeGb} GB`)
  return out
}

const STATE_SENTENCE: Record<ResourceState, string> = {
  active: 'It is active',
  pending: 'It is being created',
  updating: 'A change to it is under way',
  deleting: 'It is being deleted',
  deleted: 'It is deleted',
  failed: 'It failed',
}

/** What a resource is, what it costs and how it is doing, from what the person may read. */
function explain(scope: Scope, wfrn: string): { answer: Answer; name?: string } {
  const m = /^wfrn:[^:]*:[^:]*:[^:]*:[^:]*:([^/]+)\/.+$/.exec(wfrn)
  const rt = m ? typeOf(scope.data, m[1]) : undefined
  const accounts = scope.data.directory.accounts.map((a) => a.accountId)
  const r = rt ? resourceByWfrn(scope.data, rt, wfrn) : undefined
  if (!rt || !r || accountOfResource(r, accounts) !== scope.s.accountId)
    return { answer: { status: 'answered', text: `I could not find ${wfrn} in ${accountName(scope)}. It may be in another account, or gone.` } }
  const read = can(scope, `${serviceOf(rt)}:get`, wfrn)
  if (!read.allowed) return { answer: { status: 'failed', error: { code: 'assistant.refused', message: `You cannot read this ${nounOf(rt, 1)}, so I cannot explain it: ${whyRefused(read)}.` } } }
  const detail = toConsoleResourceDetail(scope.data, scope.s, rt, r, scope.now, (a, res) => can(scope, a, res))
  const name = detail.name ?? r.id
  const noun = nounOf(rt, 1)
  const env = detail.tags.environment
  // money from the cost ledger, when the person may read cost
  const charge = can(scope, 'cost:get', detail.wfrn).allowed ? chargesOf(scope.data, scope.s.region, [scope.s.accountId], scope.now, { wfrn: detail.wfrn })[0] : undefined
  const costText = !charge
    ? null
    : !charge.priced
      ? 'No list price covers it, so it has no estimate.'
      : charge.live
        ? `About ${money(charge.monthly)} a month at list prices. This is an estimate from list prices, not an invoice.`
        : detail.state === 'deleted'
          ? `It is not charged now. While it ran it cost about ${money(charge.monthly)} a month at list prices.`
          : `It is not charged until it is built. At list prices it will cost about ${money(charge.monthly)} a month.`
  const readable = (refs: { wfrn: string; type: string; name?: string }[] | undefined) =>
    (refs ?? []).filter((x) => {
      const t = typeOf(scope.data, x.type)
      return !!t && can(scope, `${serviceOf(t)}:get`, x.wfrn).allowed
    })
  const dependents = readable(detail.dependents)
  const builtFrom = readable(detail.builtFrom)
  const refName = (x: { name?: string; wfrn: string; type: string }) => `${x.name ?? x.wfrn} (${nounOf(typeOf(scope.data, x.type), 1)})`
  const creator = r.createdBy?.jobId ? scope.data.jobs.find((j) => j.id === r.createdBy.jobId)?.requester : undefined
  const workflow = r.createdBy?.workflow ? `${r.createdBy.workflow} ${r.createdBy.version ?? ''}`.trim() : undefined
  const createdBy = creator ?? workflow
  const last = detail.lastJob
  const fields = detail.finding?.status === 'open' ? detail.finding.fields : []
  const config = configOf(rt, detail.attributes)
  const headline = headlineOf(detail.attributes)
  const sections: { title: string; text: string }[] = [
    { title: 'What it is', text: `${rt.description ?? `${capitalise(article(noun))} ${noun}.`} It belongs to ${accountName(scope)} and lives in ${detail.region ?? scope.s.region}.` },
  ]
  if (config.length) sections.push({ title: 'Configuration', text: `${capitalise(joinAnd(config))}.` })
  if (costText) sections.push({ title: 'Cost', text: costText })
  sections.push({
    title: 'History',
    text: paragraphs(
      `Created on ${dayOf(Date.parse(detail.createdAt))}${creator ? ` by ${creator}` : workflow ? ` by the ${workflow} workflow` : ''}.`,
      last
        ? `The latest request from the console, ${last.title ?? last.workflow} (${last.jobId}), ${last.status === 'succeeded' ? 'succeeded' : `is ${last.status}`}.`
        : 'No request from the console has changed it since.',
    ),
  })
  sections.push({
    title: 'Dependents',
    text: paragraphs(
      dependents.length
        ? `${dependents.length} ${dependents.length === 1 ? 'resource depends' : 'resources depend'} on it: ${joinAnd(dependents.map(refName))}.`
        : 'Nothing you can read depends on it.',
      builtFrom.length ? `It is built from ${joinAnd(builtFrom.map(refName))}.` : null,
    ),
  })
  sections.push({
    title: 'Drift',
    text: !detail.observedAt
      ? 'It has not been scanned yet. The drift scan reads every resource each hour.'
      : fields.length
        ? `At the last scan, ${fields.map((f) => `${f.path} was ${String(f.observed)} where ${String(f.expected)} was asked for`).join('; ')}. Its Drift tab can revert it or accept it.`
        : 'At the last scan it matched what was asked for.',
  })
  const summary = [
    `${name} is ${article(noun)} ${noun} in ${accountName(scope)}.`,
    `${STATE_SENTENCE[detail.state]}${headline.length ? `, with ${joinAnd(headline)}` : ''}${env ? `, in ${env}` : ''}.`,
    charge?.live && charge.priced ? `It costs about ${money(charge.monthly)} a month at list prices.` : null,
    fields.length ? `It has drifted: ${joinAnd(fields.map((f) => f.path))} changed outside the console.` : null,
  ]
    .filter(Boolean)
    .join(' ')
  const explanation: ResourceExplanation = {
    wfrn: detail.wfrn,
    summary,
    sections,
    facts: {
      ...(charge?.priced ? { monthlyCost: charge.live ? charge.monthly : 0, currency: CURRENCY } : {}),
      createdAt: detail.createdAt,
      ...(createdBy ? { createdBy } : {}),
      lastChangeAt: last?.endedAt ?? last?.startedAt ?? detail.updatedAt,
      ...(last?.requester ? { lastChangeBy: last.requester } : {}),
      dependents: dependents.length,
      drift: !!detail.drift,
    },
  }
  return { answer: { status: 'answered', text: `Here is ${name}: what it is, what it costs and how it is doing.`, explanation }, name }
}

/** The copilot's answer to one turn, and the name of what it explained. */
function answerOf(scope: Scope, intent: 'ask' | 'explain', message: string, context: AssistantContext | undefined): { answer: Answer; name?: string } {
  if (intent === 'explain') return explain(scope, context!.wfrn!)
  const text = message
    .replace(new RegExp(FAIL_PHRASE, 'ig'), ' ')
    .replace(/^[\s:,.-]+/, '')
    .trim()
  if (!text || /^(help|\?|what can you do|what do you do|how do you work|who are you|what are you|how does this work)\b/i.test(text)) return { answer: { status: 'answered', text: HELP } }
  const explainAsk = /^(?:please\s+)?(?:explain|describe|tell me about|what is)\s+(.+?)[?.!]*$/i.exec(text)
  if (explainAsk) {
    const target = explainAsk[1].toLowerCase().replace(/^the\s+/, '')
    if (context?.wfrn && /^(this|it|this one|this resource|this page)$/.test(target)) return explain(scope, context.wfrn)
    for (const rt of scope.data.resourceTypes) {
      if (!listDecision(scope, rt).allowed) continue
      const row = rowsOf(scope, rt).find((x) => x.name?.toLowerCase() === target)
      if (row) return explain(scope, row.wfrn)
    }
  }
  const page = pageAnswer(text)
  if (page) return { answer: page }
  const verb = VERB_START.exec(text)
  if (verb) return { answer: answerVerb(scope, text, verb[1].toLowerCase(), context) }
  if (CREATE_START.test(text) || WANT_ONE.test(text)) return { answer: answerCreate(scope, text, context) }
  return { answer: answerFilter(scope, text, context) }
}

/* ----------------------------------------------------------------------------
   Conversations
   ------------------------------------------------------------------------- */

interface Stored {
  turn: AssistantTurn
  /** an assistant turn shows its answer from then on */
  readyAt?: number
  answer?: Answer
}

interface Conversation {
  id: string
  userId: string
  startedAt: number
  turns: Stored[]
  /** the fail phrase has failed once here */
  failedOnce: boolean
}

interface AssistantState {
  conversations: Map<string, Conversation>
  /** each person's accepted turns, for the rate */
  asked: Map<string, number[]>
  seq: number
}

const STATES = new WeakMap<MockData, AssistantState>()

function stateOf(data: MockData): AssistantState {
  let st = STATES.get(data)
  if (!st) {
    st = { conversations: new Map(), asked: new Map(), seq: 0 }
    STATES.set(data, st)
  }
  return st
}

const NOT_FOUND: RouteAnswer = { status: 404, body: { error: 'Conversation not found: it is another person’s, or older than 24 hours' } }

/** The person's own conversation while it is kept, else none. */
function conversationOf(scope: Scope, id: string): Conversation | undefined {
  const st = stateOf(scope.data)
  const c = st.conversations.get(id)
  if (!c) return undefined
  if (scope.now - c.startedAt > KEPT_MS) {
    st.conversations.delete(id)
    return undefined
  }
  return c.userId === scope.s.userId ? c : undefined
}

function viewOf(c: Conversation, now: number) {
  const turns = c.turns.map((t): AssistantTurn => {
    if (t.turn.role !== 'assistant' || !t.answer) return t.turn
    if (t.readyAt !== undefined && now < t.readyAt) return { ...t.turn, status: 'thinking' }
    const { status, text, proposals, explanation, error } = t.answer
    return { ...t.turn, status, ...(text ? { text } : {}), ...(proposals?.length ? { proposals: proposals.slice(0, 5) } : {}), ...(explanation ? { explanation } : {}), ...(error ? { error } : {}) }
  })
  const last = [...turns].reverse().find((t) => t.role === 'assistant')
  const status: 'thinking' | 'ready' | 'failed' = last?.status === 'thinking' ? 'thinking' : last?.status === 'failed' ? 'failed' : 'ready'
  return { conversationId: c.id, status, startedAt: new Date(c.startedAt).toISOString(), turns }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

function contextOf(v: unknown): AssistantContext | undefined {
  if (!isRecord(v)) return undefined
  const str = (x: unknown) => (typeof x === 'string' && x ? x : undefined)
  const out: AssistantContext = { path: str(v.path), service: str(v.service), type: str(v.type), wfrn: str(v.wfrn) }
  if (isRecord(v.filter)) out.filter = v.filter as SearchFilter
  return out
}

function ask(scope: Scope, body: Record<string, unknown>): RouteAnswer {
  const intent = body.intent
  if (intent !== 'ask' && intent !== 'explain') return problem('intent', 'intent is ask or explain')
  const message = typeof body.message === 'string' ? body.message : undefined
  if (message !== undefined && message.length > MAX_MESSAGE) return problem('message', `A question is at most ${MAX_MESSAGE.toLocaleString('en-US')} characters`)
  if (intent === 'ask' && !message?.trim()) return problem('message', 'Ask a question')
  const context = contextOf(body.context)
  if (intent === 'explain' && !context?.wfrn?.startsWith('wfrn:')) return problem('context.wfrn', 'explain needs the wfrn of the resource')
  const st = stateOf(scope.data)
  let c: Conversation | undefined
  if (body.conversationId !== undefined) {
    c = typeof body.conversationId === 'string' ? conversationOf(scope, body.conversationId) : undefined
    if (!c) return NOT_FOUND
  }
  const recent = (st.asked.get(scope.s.userId) ?? []).filter((t) => scope.now - t < MINUTE)
  st.asked.set(scope.s.userId, recent)
  if (recent.length >= TURNS_PER_MINUTE) {
    const wait = Math.max(1, Math.ceil((recent[0] + MINUTE - scope.now) / 1000))
    return { status: 429, body: { error: `The copilot takes ${TURNS_PER_MINUTE} questions a minute`, retryAfter: wait }, headers: { 'Retry-After': String(wait) } }
  }
  recent.push(scope.now)
  if (!c) {
    st.seq += 1
    c = { id: `conv_${idFrom(`${scope.s.userId}:${st.seq}:${scope.now}`, 12)}`, userId: scope.s.userId, startedAt: scope.now, turns: [], failedOnce: false }
    st.conversations.set(c.id, c)
  }
  const conversation = c
  const turnId = (n: number) => `turn_${idFrom(`${conversation.id}:${n}`, 10)}`
  const said = message?.trim() ?? ''
  let answered: { answer: Answer; name?: string }
  if (intent === 'ask' && said.toLowerCase().includes(FAIL_PHRASE) && !conversation.failedOnce) {
    conversation.failedOnce = true
    answered = { answer: { status: 'failed', error: { code: 'assistant.unavailable', message: 'The copilot is not answering right now.' } } }
  } else answered = answerOf(scope, intent, said, context)
  const at = new Date(scope.now).toISOString()
  conversation.turns.push({ turn: { turnId: turnId(conversation.turns.length), role: 'person', at, text: said || `Explain ${answered.name ?? 'this resource'}` } })
  const assistantId = turnId(conversation.turns.length)
  conversation.turns.push({ turn: { turnId: assistantId, role: 'assistant', at }, readyAt: scope.now + THINKING_MS, answer: answered.answer })
  return { status: 202, body: { conversationId: conversation.id, turnId: assistantId } }
}

/* ----------------------------------------------------------------------------
   Routes
   ------------------------------------------------------------------------- */

export function assistantRoutes(data: MockData, ctx: RouteContext, path: string, params: URLSearchParams, method: string): RouteAnswer | null {
  const scope: Scope = { data, s: ctx.session, now: ctx.now }
  if (method === 'GET') {
    const timed = timedList(scope, path, params)
    if (timed) return timed
  }
  if (path === '/console/v1/search:interpret') {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on search:interpret` } }
    const q = params.get('q') ?? ''
    if (!q.trim()) return problem('q', 'q is required')
    if (q.length > MAX_INTERPRET) return problem('q', `q is at most ${MAX_INTERPRET} characters`)
    const type = params.get('type') ?? undefined
    if (type && !data.resourceTypes.some((t) => t.name === type || t.plural === type)) return problem('type', `No type ${type}`)
    return { status: 200, body: interpret(data, ctx.session, ctx.now, q, type) }
  }
  if (path === '/console/v1/assistant/turns') {
    if (method !== 'POST') return { status: 405, body: { error: `${method} is not allowed on assistant/turns` } }
    return ask(scope, ctx.body)
  }
  const m = /^\/console\/v1\/assistant\/conversations\/([^/]+)$/.exec(path)
  if (m) {
    if (method !== 'GET') return { status: 405, body: { error: `${method} is not allowed on a conversation` } }
    const c = conversationOf(scope, decodeURIComponent(m[1]))
    return c ? { status: 200, body: viewOf(c, scope.now) } : NOT_FOUND
  }
  return null
}
