import { describe, expect, it } from 'vitest'
import { GLOBAL_REGION, parseRegions, regionOf } from './regions'

describe('parseRegions', () => {
  it('reads region files with metadata and spec', () => {
    expect(parseRegions([{ metadata: { name: 'global', title: 'Global' }, spec: { partition: 'wf', status: 'available', environments: ['dev', 'prod', 7] } }])).toEqual([{ name: 'global', title: 'Global', partition: 'wf', status: 'available', environments: ['dev', 'prod'] }])
  })

  it('reads flat regions and an items envelope', () => {
    expect(parseRegions({ items: [{ name: 'eu-west', title: 'Europe' }, { name: 'us-east' }] })).toEqual([
      { name: 'eu-west', title: 'Europe', partition: 'wf', status: undefined, environments: undefined },
      { name: 'us-east', title: 'Us-east', partition: 'wf', status: undefined, environments: undefined },
    ])
  })

  it('answers the global region when the platform answers nothing usable', () => {
    expect(parseRegions(null)).toEqual([GLOBAL_REGION])
    expect(parseRegions([])).toEqual([GLOBAL_REGION])
    expect(parseRegions([{ spec: {} }, 'x'])).toEqual([GLOBAL_REGION])
  })
})

describe('regionOf', () => {
  it('finds the chosen region, else the first, else global', () => {
    const list = parseRegions([{ name: 'a', title: 'A' }, { name: 'b', title: 'B' }])
    expect(regionOf('b', list).title).toBe('B')
    expect(regionOf('zzz', list).title).toBe('A')
    expect(regionOf('zzz', []).name).toBe('global')
  })
})
