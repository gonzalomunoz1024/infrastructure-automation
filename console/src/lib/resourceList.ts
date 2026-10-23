import { formatNumber } from '@iaf/ui'
import { PAGE_SIZES, titleCase, type FilterProperty, type FilterToken, type VirtualSort } from '@iaf/console-kit'
import { idOfWfrn, type ColumnKind, type ConsoleType } from './descriptors'
import { encodeSort, queryFromListState, sameListState, type ListState } from './listState'
import { RESOURCE_STATES, type ConsoleResource, type ResourceFilters, type ResourceState, type Schema } from './types'

/*
 * The resource list over the BFF (`GET /console/v1/r/{type}`), built for a million rows:
 *
 *   filters  free text (`q`), Status (`state`), Drift (`drift`) and tags (`tag.<name>`), each but
 *            free text with `=` only: what the BFF and the state service answer. Nothing is
 *            filtered in the browser, so the rows on screen and the count are always the server's.
 *   sort     name, created or updated, the state service's three orders; `-` for descending
 *   columns  the type's descriptor columns by default; to choose from, also updated, region,
 *            drift, the tags seen and every other scalar field the schema does not mark sensitive
 */

export { PAGE_SIZES }
export const DEFAULT_PAGE_SIZE = 50

/** The orders the state service keeps (name, createdAt, updatedAt), by the list's column keys. */
export const SORTABLE: ReadonlySet<string> = new Set(['name', 'created', 'updated'])

/** The BFF's pattern for a tag in a query (`tag.<name>`). */
const TAG_KEY = /^[a-z0-9-]{1,32}$/

/** Tags offered as filters on every list, seen on a page or not. */
export const COMMON_TAGS: readonly string[] = ['owner', 'environment']

export const DRIFT_VALUES: readonly string[] = ['yes', 'no']

/** Keys of the built-in columns, which a schema field of the same name never shadows. */
const BUILTIN_KEYS = new Set(['name', 'status', 'wfrn', 'owner', 'created', 'updated', 'region', 'drift'])

/* ------------------------------------------------------------------ sort */

/** The type's own order when the state service has it, else the newest changes first. */
export function defaultSortOf(type: Pick<ConsoleType, 'defaultSort' | 'defaultSortDir'>): VirtualSort {
  return SORTABLE.has(type.defaultSort) ? { key: type.defaultSort, dir: type.defaultSortDir } : { key: 'updated', dir: 'desc' }
}

export function sortOrDefault(type: Pick<ConsoleType, 'defaultSort' | 'defaultSortDir'>, wanted: VirtualSort | undefined): VirtualSort {
  return wanted && SORTABLE.has(wanted.key) ? wanted : defaultSortOf(type)
}

/* --------------------------------------------------------------- filters */

export function tagProperty(key: string): string {
  return `tag.${key}`
}

/** The tag a filter property names (`tag.owner` → `owner`), or null. */
export function tagOfProperty(property: string | null): string | null {
  if (!property || !property.startsWith('tag.')) return null
  const key = property.slice(4)
  return TAG_KEY.test(key) ? key : null
}

function driftOf(value: string): boolean | null {
  const v = value.trim().toLowerCase()
  if (v === 'yes' || v === 'true') return true
  if (v === 'no' || v === 'false') return false
  return null
}

/** What the filter bar offers: Status, Drift, and a tag property per tag key seen, with the values seen. */
export function filterPropertiesOf(rows: readonly ConsoleResource[], moreTagKeys: Iterable<string> = []): FilterProperty[] {
  const values = new Map<string, Set<string>>()
  const add = (key: string) => {
    if (TAG_KEY.test(key) && !values.has(key)) values.set(key, new Set())
    return values.get(key)
  }
  for (const key of COMMON_TAGS) add(key)
  for (const key of moreTagKeys) add(key)
  for (const r of rows) {
    for (const [key, value] of Object.entries(r.tags ?? {})) {
      const set = add(key)
      if (set && value) set.add(value)
    }
  }
  const tags = [...values.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, set]): FilterProperty => ({ key: tagProperty(key), label: `Tag: ${key}`, operators: ['='], values: [...set].sort((a, b) => a.localeCompare(b)).slice(0, 50) }))
  return [{ key: 'status', label: 'Status', operators: ['='], values: RESOURCE_STATES }, { key: 'drift', label: 'Drift', operators: ['='], values: DRIFT_VALUES }, ...tags]
}

/**
 * The tokens the BFF can answer, cleaned: free text as it is, Status a lifecycle state, Drift
 * yes or no, a tag by its name, `=` only. A property holds one value, so a newer token on it
 * replaces the older one; anything else (an old link, a hand-made query) is dropped.
 */
export function normaliseTokens(tokens: readonly FilterToken[]): FilterToken[] {
  const out: FilterToken[] = []
  for (const t of tokens) {
    const value = t.value.trim()
    if (!value) continue
    let next: FilterToken | null = null
    if (t.property === null) next = { property: null, operator: ':', value }
    else if (t.operator !== '=') next = null
    else if (t.property === 'status') next = (RESOURCE_STATES as readonly string[]).includes(value) ? { property: 'status', operator: '=', value } : null
    else if (t.property === 'drift') {
      const d = driftOf(value)
      next = d === null ? null : { property: 'drift', operator: '=', value: d ? 'yes' : 'no' }
    } else if (tagOfProperty(t.property)) next = { property: t.property, operator: '=', value }
    if (!next) continue
    const token = next
    if (token.property !== null) {
      const before = out.findIndex((o) => o.property === token.property)
      if (before >= 0) out.splice(before, 1)
    }
    out.push(token)
  }
  return out
}

/** The tokens as the list's query parameters: free text joined into `q`, then `state`, `drift` and `tag.<name>`. */
export function serverFiltersOf(tokens: readonly FilterToken[]): ResourceFilters {
  const out: ResourceFilters = {}
  const words: string[] = []
  for (const t of normaliseTokens(tokens)) {
    if (t.property === null) words.push(t.value)
    else if (t.property === 'status') out.state = t.value as ResourceState
    else if (t.property === 'drift') out.drift = t.value === 'yes'
    else {
      const tag = tagOfProperty(t.property)
      if (tag) out[`tag.${tag}`] = t.value
    }
  }
  if (words.length) out.q = words.join(' ')
  return out
}

/* ---------------------------------------------------------------- counts */

/** "1,234", or "1,000+" when the server stopped counting at its cap. */
export function countLabel(n: number, cappedAt?: number | null): string {
  return cappedAt && n >= cappedAt ? `${formatNumber(cappedAt)}+` : formatNumber(n)
}

/* --------------------------------------------------------------- columns */

export type ListColumnKind = ColumnKind | 'region' | 'drift' | 'tag'

export interface ListColumn {
  key: string
  label: string
  kind: ListColumnKind
  /** the attribute a `field` column reads */
  field?: string
  /** the tag a `tag` column reads */
  tag?: string
  schema?: Schema
}

function isScalar(prop: Schema | undefined): boolean {
  if (!prop) return false
  const t = Array.isArray(prop.type) ? prop.type.find((x) => x !== 'null') : prop.type
  return t === 'string' || t === 'number' || t === 'integer' || t === 'boolean' || (!t && !!prop.enum)
}

/** Every column a person may choose for the type, the type's own first, in a stable order. */
export function listColumnsOf(type: Pick<ConsoleType, 'columns' | 'schema' | 'sensitive'>, tagKeys: Iterable<string> = []): ListColumn[] {
  const out: ListColumn[] = type.columns.map((c) => ({ key: c.key, label: c.label, kind: c.kind, field: c.field, schema: c.schema }))
  const has = (key: string) => out.some((c) => c.key === key)
  for (const c of [
    { key: 'updated', label: 'Updated', kind: 'updated' },
    { key: 'region', label: 'Region', kind: 'region' },
    { key: 'drift', label: 'Drift', kind: 'drift' },
  ] satisfies ListColumn[]) {
    if (!has(c.key)) out.push(c)
  }
  const sensitive = new Set(type.sensitive)
  for (const [name, prop] of Object.entries(type.schema.properties ?? {})) {
    if (sensitive.has(name) || BUILTIN_KEYS.has(name) || has(name) || !isScalar(prop)) continue
    out.push({ key: name, label: prop.title ?? titleCase(name), kind: 'field', field: name, schema: prop })
  }
  for (const tag of [...new Set(tagKeys)].sort((a, b) => a.localeCompare(b))) {
    const key = `tag:${tag}`
    if (tag === 'owner' || !TAG_KEY.test(tag) || has(key)) continue
    out.push({ key, label: `Tag: ${tag}`, kind: 'tag', tag })
  }
  return out
}

/** The columns shown: the person's choice, else the type's; the name always, first. */
export function visibleColumns(all: readonly ListColumn[], defaults: readonly string[], chosen: readonly string[] | null): ListColumn[] {
  const want = new Set(chosen && chosen.length ? chosen : defaults)
  want.add('name')
  const shown = all.filter((c) => want.has(c.key))
  const name = shown.findIndex((c) => c.key === 'name')
  if (name > 0) shown.unshift(...shown.splice(name, 1))
  return shown
}

/** The choice to keep: null when it is the type's own set, so a later change to the type shows. */
export function columnChoice(all: readonly ListColumn[], defaults: readonly string[], on: ReadonlySet<string>): string[] | null {
  const keys = all.filter((c) => c.key === 'name' || on.has(c.key)).map((c) => c.key)
  const same = keys.length === new Set([...defaults, 'name']).size && keys.every((k) => k === 'name' || defaults.includes(k))
  return same ? null : keys
}

/** The tag keys a choice of columns names (`tag:team` → `team`). */
export function tagKeysOfColumns(keys: readonly string[] | null): string[] {
  return (keys ?? []).filter((k) => k.startsWith('tag:')).map((k) => k.slice(4))
}

/** What a resource is called: its name, else the id part of its wfrn. */
export function nameOf(r: Pick<ConsoleResource, 'name' | 'wfrn'>): string {
  return r.name || idOfWfrn(r.wfrn) || r.wfrn
}

/** A cell's value, as the CSV and the sort read it. */
export function cellOf(r: ConsoleResource, c: ListColumn): unknown {
  switch (c.kind) {
    case 'name':
      return nameOf(r)
    case 'status':
      return r.state
    case 'wfrn':
      return r.wfrn
    case 'owner':
      return r.tags?.owner
    case 'created':
      return r.createdAt
    case 'updated':
      return r.updatedAt
    case 'region':
      return r.region
    case 'drift':
      return r.drift === undefined ? undefined : r.drift ? 'yes' : 'no'
    case 'tag':
      return c.tag ? r.tags?.[c.tag] : undefined
    default:
      return c.field ? r.attributes?.[c.field] : undefined
  }
}

/** The rows as CSV rows, the shown columns' labels first. */
export function csvRowsOf(rows: readonly ConsoleResource[], columns: readonly ListColumn[]): unknown[][] {
  return [columns.map((c) => c.label), ...rows.map((r) => columns.map((c) => cellOf(r, c)))]
}

/* ----------------------------------------------------------------- state */

/** A list state with every default filled in, for comparing two of them. */
export function effectiveState(state: ListState, type: Pick<ConsoleType, 'defaultSort' | 'defaultSortDir'>): ListState {
  return { tokens: normaliseTokens(state.tokens), sort: sortOrDefault(type, state.sort), pageSize: state.pageSize ?? DEFAULT_PAGE_SIZE, view: state.view }
}

/** The same list, whatever the defaults were written as. */
export function sameList(a: ListState, b: ListState, type: Pick<ConsoleType, 'defaultSort' | 'defaultSortDir'>): boolean {
  return sameListState(effectiveState(a, type), effectiveState(b, type))
}

/** The page's query for a state, defaults left out, so an untouched list has a bare path. */
export function listQueryOf(state: ListState, type: Pick<ConsoleType, 'defaultSort' | 'defaultSortDir'>): Record<string, string | undefined> {
  const tokens = normaliseTokens(state.tokens)
  const sort = state.sort && SORTABLE.has(state.sort.key) && encodeSort(state.sort) !== encodeSort(defaultSortOf(type)) ? state.sort : undefined
  const pageSize = state.pageSize && state.pageSize !== DEFAULT_PAGE_SIZE ? state.pageSize : undefined
  return queryFromListState({ tokens, sort, pageSize, view: state.view })
}
