import { describe, expect, it } from 'vitest'
import { csvCell, csvFileName, toCsv } from './csv'

describe('csv', () => {
  it('quotes what needs quoting and leaves the rest', () => {
    expect(csvCell('web-1')).toBe('web-1')
    expect(csvCell('a, b')).toBe('"a, b"')
    expect(csvCell('say "hi"')).toBe('"say ""hi"""')
    expect(csvCell('two\nlines')).toBe('"two\nlines"')
    expect(csvCell(null)).toBe('')
    expect(csvCell(4)).toBe('4')
    expect(csvCell(true)).toBe('true')
    expect(csvCell({ a: 1 })).toBe('"{""a"":1}"')
  })

  it('defuses a cell a spreadsheet would run as a formula', () => {
    expect(csvCell('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`)
    expect(csvCell('+1')).toBe("'+1")
    expect(csvCell('-2')).toBe("'-2")
    expect(csvCell('@me')).toBe("'@me")
  })

  it('writes a header and CRLF rows', () => {
    expect(toCsv([['Name', 'CPU'], ['web-1', 2]])).toBe('Name,CPU\r\nweb-1,2\r\n')
  })

  it('names the file after the noun and the day', () => {
    expect(csvFileName('Virtual machines', new Date('2026-10-23T08:00:00Z'))).toBe('virtual-machines-2026-10-23.csv')
    expect(csvFileName('***', new Date('2026-10-23T08:00:00Z'))).toBe('export-2026-10-23.csv')
  })
})
