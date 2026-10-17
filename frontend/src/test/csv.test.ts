import { describe, expect, it } from 'vitest'
import { csvToRows, emitCsv, parseCsv } from '../components/csv'

describe('parseCsv', () => {
  it('reads plain rows and skips blank lines', () => {
    expect(parseCsv('a,b\n1,2\n\n3,4\n')).toEqual([
      ['a', 'b'],
      ['1', '2'],
      ['3', '4'],
    ])
  })
  it('handles quotes, escaped quotes, commas and newlines inside cells', () => {
    expect(parseCsv('name,reason\r\n"web-1","needs a ""restart"", now"\r\nx,"two\nlines"')).toEqual([
      ['name', 'reason'],
      ['web-1', 'needs a "restart", now'],
      ['x', 'two\nlines'],
    ])
  })
  it('accepts tabs when the header has no comma', () => {
    expect(parseCsv('a\tb\n1\t2')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ])
  })
  it('drops a BOM', () => {
    expect(parseCsv('﻿a,b\n1,2')[0]).toEqual(['a', 'b'])
  })
})

describe('csvToRows', () => {
  it('keys rows by the header and leaves blank cells out', () => {
    expect(csvToRows('vmId,reason,environment\nvm-1,,prod\nvm-2,patch,')).toEqual({
      header: ['vmId', 'reason', 'environment'],
      rows: [
        { vmId: 'vm-1', environment: 'prod' },
        { vmId: 'vm-2', reason: 'patch' },
      ],
    })
  })
  it('is empty for an empty text', () => {
    expect(csvToRows('')).toEqual({ header: [], rows: [] })
  })
})

describe('emitCsv', () => {
  it('writes a header and quotes what needs quoting', () => {
    const text = emitCsv([
      { vmId: 'vm-1', reason: 'needs a "restart", now', n: 2, ok: true, tags: ['a'] },
      { vmId: 'vm-2' },
    ])
    expect(text).toBe('vmId,reason,n,ok,tags\nvm-1,"needs a ""restart"", now",2,true,"[""a""]"\nvm-2,,,,\n')
    expect(csvToRows(text).rows[0].reason).toBe('needs a "restart", now')
  })
  it('respects an explicit header', () => {
    expect(emitCsv([{ a: 1, b: 2 }], ['b'])).toBe('b\n2\n')
  })
})
