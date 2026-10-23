import { sensitiveFields, summarize, titleCase, type FilterToken } from '@iaf/console-kit'
import type { AssistantContext, AssistantProposal, SearchFilter } from '../../lib/assistant'
import { findType, type ConsoleType } from '../../lib/descriptors'
import { decodeSort, decodeTokens, queryFromListState } from '../../lib/listState'
import { encodePrefill } from '../../lib/prefill'
import { normaliseTokens, tagOfProperty, tagProperty } from '../../lib/resourceList'
import type { ResourceState, Schema } from '../../lib/types'
import { parseHash, pathFor, resolve, splitAccount, type Route } from '../router'

/*
 * What a proposal of the copilot opens, as a console link, and how its card says it. A filter
 * becomes the list's own `?f=` tokens and `sort=`, so the list it opens is the list a person
 * could have filtered by hand; a create opens the wizard with `?prefill=`; an action opens the
 * resource's drawer with `?action=<verb>&prefill=`; an open goes to the route it names, when the
 * console has that route. Nothing here runs anything: the person opens the link and checks it.
 */

/** The terms of a free-text query, a quoted phrase as one: 'web "two words" created:7d' → three terms. */
export function termsOf(q: string | undefined): string[] {
  if (!q) return []
  return (q.match(/"[^"]*"|“[^”]*”|\S+/g) ?? []).filter((t) => t.replace(/["“”]/g, '').trim() !== '')
}

/**
 * A reading's filter as the list's tokens: Status, Drift, each tag with a value, then the free
 * text term by term (`created:` and `updated:` terms travel in it, as the list's `q`). What the
 * list cannot hold is named in `leftOut`: a tag with any value, a tag name the list refuses, and
 * another region than the one the console is in (a list shows its own region only).
 */
export function listableOf(filter: SearchFilter | undefined, region?: string): { tokens: FilterToken[]; leftOut: string[] } {
  const tokens: FilterToken[] = []
  const leftOut: string[] = []
  if (!filter) return { tokens, leftOut }
  if (filter.state) tokens.push({ property: 'status', operator: '=', value: filter.state })
  if (filter.drift !== undefined) tokens.push({ property: 'drift', operator: '=', value: filter.drift ? 'yes' : 'no' })
  for (const [key, value] of Object.entries(filter.tags ?? {})) {
    if (!value.trim()) leftOut.push(`the tag ${key} with any value`)
    else if (!tagOfProperty(tagProperty(key))) leftOut.push(`the tag ${key}=${value}`)
    else tokens.push({ property: tagProperty(key), operator: '=', value })
  }
  if (filter.region && region && filter.region !== region) leftOut.push(`the region ${filter.region}`)
  for (const term of termsOf(filter.q)) tokens.push({ property: null, operator: ':', value: term })
  return { tokens: normaliseTokens(tokens), leftOut }
}

/** The list's tokens as a filter, for the context of a question asked on a list ("which of these"). */
export function filterOfTokens(tokens: readonly FilterToken[]): SearchFilter | undefined {
  const out: SearchFilter = {}
  const words: string[] = []
  for (const t of normaliseTokens(tokens)) {
    if (t.property === null) words.push(t.value)
    else if (t.property === 'status') out.state = t.value as ResourceState
    else if (t.property === 'drift') out.drift = t.value === 'yes'
    else {
      const key = tagOfProperty(t.property)
      if (key) out.tags = { ...out.tags, [key]: t.value }
    }
  }
  if (words.length) out.q = words.join(' ')
  return Object.keys(out).length ? out : undefined
}

/** Where the person is, so that "these" and "this one" mean something to the copilot. */
export function contextOf(route: Pick<Route, 'name' | 'path' | 'params' | 'query'>): AssistantContext {
  const context: AssistantContext = { path: route.path }
  if (route.params.service) context.service = route.params.service
  if (route.params.type) context.type = route.params.type
  if (route.name === 'resource' && route.params.wfrn?.startsWith('wfrn:')) context.wfrn = route.params.wfrn
  if (route.name === 'type') {
    const filter = filterOfTokens(decodeTokens(route.query.f))
    if (filter) context.filter = filter
  }
  return context
}

const prefillOf = (input: Record<string, unknown> | undefined) => (input && Object.keys(input).length ? encodePrefill(input) : undefined)

/** A console route the copilot named, or null when the console has no such page (or it is not one of the console's paths at all). */
export function openPath(path: string | undefined): string | null {
  if (!path) return null
  const bare = path.startsWith('#') ? path.slice(1) : path
  if (!bare.startsWith('/') || bare.startsWith('//')) return null
  const { def } = resolve(splitAccount(parseHash(bare).path).path)
  return def && !def.bare ? bare : null
}

/** The link a proposal opens, or null when the console cannot open it (a type it does not know, an action without its resource). */
export function proposalPath(p: AssistantProposal, types: ConsoleType[], region?: string): string | null {
  if (p.kind === 'open') return openPath(p.path)
  const t = p.type ? findType(types, p.service ?? '', p.type) : undefined
  if (!t) return null
  const at = { service: t.service, type: t.name }
  if (p.kind === 'filter') return pathFor('type', at, queryFromListState({ tokens: listableOf(p.filter, region).tokens, sort: decodeSort(p.sort) }))
  if (p.kind === 'create') return pathFor('create', at, { prefill: prefillOf(p.input) })
  if (p.kind === 'action' && p.wfrn && p.verb) return pathFor('resource', { ...at, wfrn: p.wfrn }, { action: p.verb, prefill: prefillOf(p.input) })
  return null
}

/* ------------------------------------------------------------------ chips */

/** One part of a filter, drawn as the list draws its tokens: "Status = deleted", "search web". */
export interface Chip {
  label: string
  op?: string
  value: string
}

export function chipText(c: Chip): string {
  return [c.label, c.op, c.value].filter(Boolean).join(' ')
}

const UNIT: Record<string, string> = { m: 'minute', h: 'hour', d: 'day', w: 'week' }

type Point = { kind: 'age'; n: number; unit: string } | { kind: 'day' | 'time'; text: string }

function pointOf(text: string): Point | null {
  const age = /^(\d+)([mhdw])$/.exec(text)
  if (age) return { kind: 'age', n: Number(age[1]), unit: UNIT[age[2]] }
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return { kind: 'day', text }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/.test(text)) return { kind: 'time', text }
  return null
}

const span = (p: { n: number; unit: string }) => `${p.n} ${p.unit}${p.n === 1 ? '' : 's'}`

/**
 * A `created:` or `updated:` term in words, the syntax of the search indexer's queries: an age
 * (`7d`) counts back from now, a day is that whole day, and `a..b` keeps both ends.
 * 'created:14d' → { field: 'Created', words: 'in the last 14 days' }; null when it is no such term.
 */
export function describeTerm(term: string): { field: 'Created' | 'Updated'; words: string } | null {
  const m = /^(created|updated):(.+)$/i.exec(term)
  if (!m) return null
  const field = m[1].toLowerCase() === 'created' ? 'Created' : 'Updated'
  const range = /^([^.]+)\.\.([^.]+)$/.exec(m[2])
  if (range) {
    const a = pointOf(range[1])
    const b = pointOf(range[2])
    if (!a || !b) return null
    if (a.kind === 'age' && b.kind === 'age') return { field, words: `between ${span(a)} and ${span(b)} ago` }
    if (a.kind === 'age' || b.kind === 'age') return null
    return { field, words: `between ${a.text} and ${b.text}` }
  }
  const cmp = /^(>=|<=|>|<)?(.+)$/.exec(m[2])
  const op = cmp?.[1] ?? ''
  const p = cmp ? pointOf(cmp[2]) : null
  if (!p) return null
  if (p.kind === 'age') return { field, words: op === '<' || op === '<=' ? `more than ${span(p)} ago` : `in the last ${p.n === 1 ? p.unit : span(p)}` }
  if (p.kind === 'time' && !op) return null
  const words = op === '>=' ? `since ${p.text}` : op === '>' ? `after ${p.text}` : op === '<' ? `before ${p.text}` : op === '<=' ? `on or before ${p.text}` : `on ${p.text}`
  return { field, words }
}

const SORT_WORDS: Record<string, string> = {
  name: 'by name, A to Z',
  '-name': 'by name, Z to A',
  created: 'oldest first',
  '-created': 'newest first',
  updated: 'least recently changed first',
  '-updated': 'most recently changed first',
}

/** The order a sort asks for, in words; undefined for none (or the search's own relevance). */
export function sortWords(sort: string | undefined): string | undefined {
  return sort ? SORT_WORDS[sort] : undefined
}

/** What a filter proposal applies, chip by chip: the list's tokens, times in words, then the order. */
export function chipsOf(tokens: readonly FilterToken[], sort?: string): Chip[] {
  const out: Chip[] = []
  for (const t of tokens) {
    if (t.property === 'status') out.push({ label: 'Status', op: '=', value: t.value })
    else if (t.property === 'drift') out.push({ label: 'Drift', op: '=', value: t.value })
    else if (t.property === null) {
      const time = describeTerm(t.value)
      out.push(time ? { label: time.field, value: time.words } : { label: 'search', value: t.value })
    } else {
      const key = tagOfProperty(t.property)
      if (key) out.push({ label: `Tag: ${key}`, op: '=', value: t.value })
    }
  }
  const order = sortWords(sort)
  if (order) out.push({ label: 'Sort', value: order })
  return out
}

/* ------------------------------------------------------------ form inputs */

/** A field of a form by its title: 'name' → 'VM name'. */
export function fieldTitle(schema: Schema | undefined, key: string): string {
  const title = schema?.properties?.[key]?.title
  return typeof title === 'string' && title ? title : titleCase(key)
}

/** The inputs a proposal fills that the form takes (its own fields, never a sensitive one), by title. */
export function filledOf(schema: Schema | undefined, input: Record<string, unknown> | undefined): Chip[] {
  if (!input) return []
  const known = schema?.properties
  const secret = new Set(schema ? sensitiveFields(schema) : [])
  return Object.entries(input)
    .filter(([key, value]) => value !== undefined && value !== null && value !== '' && (!known || key in known) && !secret.has(key))
    .map(([key, value]) => ({ label: fieldTitle(schema, key), op: '=', value: typeof value === 'string' ? value : summarize(value, 40) }))
}

/* ------------------------------------------------------------ suggestions */

/** A first question the empty panel offers, fitting the page. */
export interface Suggestion {
  text: string
  intent: 'ask' | 'explain'
  context?: AssistantContext
}

/** On a resource, its explanation; on a list, its drift; anywhere else, a filter and a form. */
export function suggestionsFor(route: Pick<Route, 'name' | 'path' | 'params' | 'query'>): Suggestion[] {
  if (route.name === 'resource' && route.params.wfrn?.startsWith('wfrn:')) return [{ text: 'Explain this resource', intent: 'explain', context: contextOf(route) }]
  if (route.name === 'type') return [{ text: 'Which of these drifted?', intent: 'ask' }]
  return [
    { text: 'Find stopped virtual machines in prod', intent: 'ask' },
    { text: 'Create a small VM in dev', intent: 'ask' },
  ]
}
