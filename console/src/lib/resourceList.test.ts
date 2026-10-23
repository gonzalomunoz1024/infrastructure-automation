import { describe, expect, it } from 'vitest'
import { buildMockData } from '../mock/data'
import { describeTypes } from '../mock/types'
import { composeTypes } from './descriptors'
import {
  DEFAULT_PAGE_SIZE,
  cellOf,
  columnChoice,
  countLabel,
  csvRowsOf,
  defaultSortOf,
  filterPropertiesOf,
  listColumnsOf,
  listQueryOf,
  nameOf,
  normaliseTokens,
  sameList,
  serverFiltersOf,
  sortOrDefault,
  tagKeysOfColumns,
  visibleColumns,
} from './resourceList'
import type { ConsoleResource } from './types'

const data = buildMockData()
const vm = composeTypes(describeTypes(data)).find((t) => t.name === 'vm')!

const row = (over: Partial<ConsoleResource> = {}): ConsoleResource => ({
  wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-demo01',
  type: 'vm',
  service: 'compute',
  name: 'web-01',
  region: 'global',
  state: 'active',
  version: 3,
  attributes: { cpu: 4, memoryGb: 16, environment: 'prod', image: 'image-demo01' },
  drift: false,
  tags: { owner: 'team-compute', environment: 'prod', team: 'shop' },
  createdAt: '2026-09-28T02:05:25Z',
  updatedAt: '2026-10-01T02:05:25Z',
  ...over,
})

describe('the filters the BFF answers', () => {
  it('offers Status, Drift and a property per tag, with the values seen', () => {
    const props = filterPropertiesOf([row(), row({ tags: { owner: 'team-data', 'Bad Key': 'x' } })], ['cost-centre'])
    expect(props.map((p) => p.key)).toEqual(['status', 'drift', 'tag.cost-centre', 'tag.environment', 'tag.owner', 'tag.team'])
    expect(props.every((p) => p.operators?.join() === '=')).toBe(true)
    expect(props.find((p) => p.key === 'status')?.values).toEqual(['pending', 'active', 'updating', 'deleting', 'deleted', 'failed'])
    expect(props.find((p) => p.key === 'tag.owner')?.values).toEqual(['team-compute', 'team-data'])
    expect(props.find((p) => p.key === 'tag.owner')?.label).toBe('Tag: owner')
  })

  it('keeps only what the BFF can filter, one value per property, the newest winning', () => {
    const tokens = normaliseTokens([
      { property: null, operator: ':', value: ' web ' },
      { property: 'status', operator: '=', value: 'failed' },
      { property: 'cpu', operator: '>=', value: '4' },
      { property: 'status', operator: '!=', value: 'active' },
      { property: 'status', operator: '=', value: 'ready' },
      { property: 'drift', operator: '=', value: 'true' },
      { property: 'tag.owner', operator: '=', value: 'team-compute' },
      { property: 'tag.Bad Key', operator: '=', value: 'x' },
      { property: 'status', operator: '=', value: 'active' },
      { property: null, operator: ':', value: '  ' },
    ])
    expect(tokens).toEqual([
      { property: null, operator: ':', value: 'web' },
      { property: 'drift', operator: '=', value: 'yes' },
      { property: 'tag.owner', operator: '=', value: 'team-compute' },
      { property: 'status', operator: '=', value: 'active' },
    ])
  })

  it('sends free text as q, then state, drift and tags', () => {
    expect(
      serverFiltersOf([
        { property: null, operator: ':', value: 'web' },
        { property: null, operator: ':', value: 'prod' },
        { property: 'status', operator: '=', value: 'active' },
        { property: 'drift', operator: '=', value: 'no' },
        { property: 'tag.environment', operator: '=', value: 'prod' },
      ]),
    ).toEqual({ q: 'web prod', state: 'active', drift: false, 'tag.environment': 'prod' })
    expect(serverFiltersOf([])).toEqual({})
  })
})

describe('sort and counts', () => {
  it('sorts by name, created or updated only, the type’s order first', () => {
    expect(defaultSortOf({ defaultSort: 'created', defaultSortDir: 'desc' })).toEqual({ key: 'created', dir: 'desc' })
    expect(defaultSortOf({ defaultSort: 'cpu', defaultSortDir: 'asc' })).toEqual({ key: 'updated', dir: 'desc' })
    expect(sortOrDefault(vm, { key: 'cpu', dir: 'asc' })).toEqual(defaultSortOf(vm))
    expect(sortOrDefault(vm, { key: 'name', dir: 'desc' })).toEqual({ key: 'name', dir: 'desc' })
  })

  it('reads a capped count as 1,000+', () => {
    expect(countLabel(36, 1000)).toBe('36')
    expect(countLabel(1000, 1000)).toBe('1,000+')
    expect(countLabel(1234, null)).toBe('1,234')
  })
})

describe('columns', () => {
  it('offers the type’s columns first, then updated, region, drift, the other fields and the tags', () => {
    const all = listColumnsOf(vm, ['team', 'owner', 'environment'])
    const keys = all.map((c) => c.key)
    expect(keys.slice(0, vm.columns.length)).toEqual(vm.columns.map((c) => c.key))
    expect(keys).toEqual(expect.arrayContaining(['updated', 'region', 'drift', 'image', 'tag:team', 'tag:environment']))
    expect(keys).not.toContain('tag:owner')
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('shows the person’s choice with the name always first, and keeps null for the type’s own set', () => {
    const all = listColumnsOf(vm)
    const defaults = vm.columns.map((c) => c.key)
    expect(visibleColumns(all, defaults, null).map((c) => c.key)).toEqual(defaults)
    expect(visibleColumns(all, defaults, ['drift', 'status']).map((c) => c.key)).toEqual(['name', 'status', 'drift'])
    expect(columnChoice(all, defaults, new Set(defaults))).toBeNull()
    expect(columnChoice(all, defaults, new Set(['status', 'drift']))).toEqual(['name', 'status', 'drift'])
    expect(tagKeysOfColumns(['name', 'tag:team', 'tag:cost-centre'])).toEqual(['team', 'cost-centre'])
  })

  it('reads each cell and writes the CSV rows with the shown labels', () => {
    const r = row({ drift: true })
    const all = listColumnsOf(vm, ['team'])
    const byKey = (k: string) => all.find((c) => c.key === k)!
    expect(cellOf(r, byKey('name'))).toBe('web-01')
    expect(cellOf(r, byKey('status'))).toBe('active')
    expect(cellOf(r, byKey('owner'))).toBe('team-compute')
    expect(cellOf(r, byKey('drift'))).toBe('yes')
    expect(cellOf(r, byKey('tag:team'))).toBe('shop')
    expect(cellOf(r, byKey('image'))).toBe('image-demo01')
    expect(nameOf({ name: '', wfrn: 'wfrn:wf:compute:global:123456789012:vm/vm-x' })).toBe('vm-x')
    expect(csvRowsOf([r], [byKey('name'), byKey('wfrn'), byKey('drift')])).toEqual([
      ['Name', 'wfrn', 'Drift'],
      ['web-01', r.wfrn, 'yes'],
    ])
  })
})

describe('the list in the page’s query', () => {
  it('leaves the defaults out, so an untouched list has a bare path', () => {
    expect(listQueryOf({ tokens: [], sort: defaultSortOf(vm), pageSize: DEFAULT_PAGE_SIZE }, vm)).toEqual({ f: undefined, sort: undefined, size: undefined, view: undefined })
    expect(listQueryOf({ tokens: [{ property: 'status', operator: '=', value: 'failed' }], sort: { key: 'name', dir: 'desc' }, pageSize: 100, view: 'v_1' }, vm)).toEqual({ f: 'status=failed', sort: '-name', size: '100', view: 'v_1' })
    // a sort the state service lacks never reaches the query
    expect(listQueryOf({ tokens: [], sort: { key: 'cpu', dir: 'asc' } }, vm).sort).toBeUndefined()
  })

  it('sees a view written with defaults and the list with them filled in as the same list', () => {
    const status = { property: 'status', operator: '=' as const, value: 'active' }
    expect(sameList({ tokens: [status] }, { tokens: [status], sort: defaultSortOf(vm), pageSize: DEFAULT_PAGE_SIZE }, vm)).toBe(true)
    expect(sameList({ tokens: [status] }, { tokens: [status], pageSize: 100 }, vm)).toBe(false)
  })
})
