import { describe, expect, it } from 'vitest'
import type { FilterToken } from '@iaf/console-kit'
import { decodeSort, decodeToken, decodeTokens, encodeSort, encodeTokens, listStateFromQuery, queryFromListState, sameListState, stateToView, viewToState } from './listState'

const tokens: FilterToken[] = [
  { property: 'status', operator: '=', value: 'active' },
  { property: 'cpu', operator: '>=', value: '4' },
  { property: null, operator: ':', value: 'web; "east"' },
  { property: 'tag.owner', operator: '!=', value: 'team-data' },
]

describe('list state in the query', () => {
  it('round-trips tokens through one query value', () => {
    const text = encodeTokens(tokens)
    expect(text).toBe('status=active;cpu>=4;~web%3B%20%22east%22;tag.owner!=team-data')
    expect(decodeTokens(text)).toEqual(tokens)
  })

  it('prefers the longer operator and refuses what it cannot read', () => {
    expect(decodeToken('cpu>=4')).toEqual({ property: 'cpu', operator: '>=', value: '4' })
    expect(decodeToken('cpu>4')).toEqual({ property: 'cpu', operator: '>', value: '4' })
    expect(decodeToken('name!:web')).toEqual({ property: 'name', operator: '!:', value: 'web' })
    expect(decodeToken('nonsense')).toBeNull()
    expect(decodeToken('=active')).toBeNull()
    expect(decodeToken('a b=c')).toBeNull()
    expect(decodeToken('~')).toBeNull()
    expect(decodeTokens('status=active;;junk;~x')).toEqual([
      { property: 'status', operator: '=', value: 'active' },
      { property: null, operator: ':', value: 'x' },
    ])
  })

  it('writes the sort as the BFF reads it', () => {
    expect(encodeSort({ key: 'created', dir: 'desc' })).toBe('-created')
    expect(encodeSort({ key: 'name', dir: 'asc' })).toBe('name')
    expect(decodeSort('-created')).toEqual({ key: 'created', dir: 'desc' })
    expect(decodeSort('name')).toEqual({ key: 'name', dir: 'asc' })
    expect(decodeSort('')).toBeUndefined()
    expect(decodeSort('-')).toBeUndefined()
  })

  it('reads a query and writes one without empty keys', () => {
    const state = listStateFromQuery({ f: 'status=active', sort: '-created', size: '100', view: 'v1' }, [25, 50, 100])
    expect(state).toEqual({ tokens: [{ property: 'status', operator: '=', value: 'active' }], sort: { key: 'created', dir: 'desc' }, pageSize: 100, view: 'v1' })
    expect(listStateFromQuery({ size: '7' }, [25, 50]).pageSize).toBeUndefined()
    expect(queryFromListState({ tokens: [] })).toEqual({ f: undefined, sort: undefined, size: undefined, view: undefined })
    expect(queryFromListState(state)).toEqual({ f: 'status=active', sort: '-created', size: '100', view: 'v1' })
  })

  it('compares states by value, whatever the token order', () => {
    expect(sameListState({ tokens: [tokens[0], tokens[1]] }, { tokens: [tokens[1], tokens[0]] })).toBe(true)
    expect(sameListState({ tokens, sort: { key: 'name', dir: 'asc' } }, { tokens })).toBe(false)
    expect(sameListState({ tokens: [], pageSize: 50 }, { tokens: [], pageSize: 100 })).toBe(false)
  })

  it('turns a state into a saved view and back', () => {
    const view = stateToView({ tokens, sort: { key: 'created', dir: 'desc' }, pageSize: 100 }, 'Big active', 'v_1', '2026-10-23T08:00:00Z')
    expect(view.filters).toHaveLength(4)
    expect(view.sort).toBe('-created')
    const back = viewToState(view)
    expect(back.tokens).toEqual(tokens)
    expect(back.sort).toEqual({ key: 'created', dir: 'desc' })
    expect(back.pageSize).toBe(100)
    expect(back.view).toBe('v_1')
    // an operator the kit does not know falls back to equality
    expect(viewToState({ ...view, filters: [{ property: 'status', operator: '~', value: 'x' }] }).tokens[0]).toEqual({ property: 'status', operator: '=', value: 'x' })
  })
})
