import type { FilterOperator, FilterToken, VirtualSort } from '@iaf/console-kit'
import type { SavedFilter, SavedView } from './types'

/*
 * What a list shows, carried in the page's query so that a filtered, sorted list is a link:
 *
 *   ?f=status%3Dactive;cpu%3E%3D4;~web   the filter tokens, one per `;`, each percent-encoded,
 *                                        free text with a `~` in front
 *   &sort=-created                        a column, `-` for descending (the BFF's own form)
 *   &size=100                             the page size
 *   &view=v_01J…                          the saved view the list came from
 *
 * A saved view keeps the same three things by value, so applying one is writing the query.
 */

export interface ListState {
  tokens: FilterToken[]
  sort?: VirtualSort
  pageSize?: number
  view?: string
}

export const LIST_QUERY_KEYS = ['f', 'sort', 'size', 'view'] as const

const OPERATORS: readonly FilterOperator[] = ['!=', '!:', '>=', '<=', '=', ':', '>', '<']
const PROPERTY = /^[A-Za-z0-9_.-]+$/

export function encodeToken(t: FilterToken): string {
  if (t.property === null) return `~${encodeURIComponent(t.value)}`
  return `${encodeURIComponent(t.property)}${t.operator}${encodeURIComponent(t.value)}`
}

export function decodeToken(text: string): FilterToken | null {
  if (!text) return null
  if (text.startsWith('~')) {
    const value = safeDecode(text.slice(1))
    return value ? { property: null, operator: ':', value } : null
  }
  for (let i = 1; i < text.length; i++) {
    for (const op of OPERATORS) {
      if (text.startsWith(op, i)) {
        const property = safeDecode(text.slice(0, i))
        const value = safeDecode(text.slice(i + op.length))
        if (!PROPERTY.test(property) || !value) return null
        return { property, operator: op, value }
      }
    }
  }
  return null
}

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s)
  } catch {
    return ''
  }
}

export function encodeTokens(tokens: readonly FilterToken[]): string {
  return tokens.map(encodeToken).join(';')
}

export function decodeTokens(text: string | undefined): FilterToken[] {
  if (!text) return []
  return text
    .split(';')
    .map(decodeToken)
    .filter((t): t is FilterToken => t !== null)
}

/** `-created` ⇄ { key: 'created', dir: 'desc' }. */
export function encodeSort(sort: VirtualSort | undefined): string | undefined {
  if (!sort) return undefined
  return `${sort.dir === 'desc' ? '-' : ''}${sort.key}`
}

export function decodeSort(text: string | undefined): VirtualSort | undefined {
  if (!text) return undefined
  const desc = text.startsWith('-')
  const key = desc ? text.slice(1) : text
  return PROPERTY.test(key) ? { key, dir: desc ? 'desc' : 'asc' } : undefined
}

export function listStateFromQuery(query: Record<string, string>, pageSizes: readonly number[]): ListState {
  const size = Number(query.size)
  return {
    tokens: decodeTokens(query.f),
    sort: decodeSort(query.sort),
    pageSize: pageSizes.includes(size) ? size : undefined,
    view: query.view || undefined,
  }
}

/** The query for a state; empty values are left out, so an untouched list has a bare path. */
export function queryFromListState(state: ListState): Record<string, string | undefined> {
  return {
    f: state.tokens.length ? encodeTokens(state.tokens) : undefined,
    sort: encodeSort(state.sort),
    size: state.pageSize ? String(state.pageSize) : undefined,
    view: state.view,
  }
}

/** The same filters, sort and page size, whatever their order. */
export function sameListState(a: ListState, b: ListState): boolean {
  return encodeSort(a.sort) === encodeSort(b.sort) && (a.pageSize ?? 0) === (b.pageSize ?? 0) && [...a.tokens].map(encodeToken).sort().join(';') === [...b.tokens].map(encodeToken).sort().join(';')
}

export function viewToState(view: SavedView): ListState {
  return { tokens: view.filters.map(filterToToken), sort: decodeSort(view.sort), pageSize: view.pageSize, view: view.id }
}

export function stateToView(state: ListState, name: string, id: string, createdAt: string): SavedView {
  return { id, name, filters: state.tokens.map(tokenToFilter), sort: encodeSort(state.sort), pageSize: state.pageSize, createdAt }
}

function filterToToken(f: SavedFilter): FilterToken {
  const operator = OPERATORS.includes(f.operator as FilterOperator) ? (f.operator as FilterOperator) : '='
  return { property: f.property, operator: f.property === null ? ':' : operator, value: f.value }
}

function tokenToFilter(t: FilterToken): SavedFilter {
  return { property: t.property, operator: t.operator, value: t.value }
}
