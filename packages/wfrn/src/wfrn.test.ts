import { describe, expect, it } from 'vitest'
import fixtures from '../fixtures.json'
import { WfrnError, formatWfrn, isValidWfrn, isWfrnError, matchesWfrn, mintId, parseMintedId, parseWfrn, validateWfrnPattern, wfrnProblem, type WfrnParts } from './wfrn'

/*
 * Every entry of every list of the shared fixtures (contracts/wfrn/fixtures.json on the hub, pinned
 * in contracts.lock), each named by its `name`, so a failing case says which rule drifted.
 */

type Valid = { name: string; input: string; parts: WfrnParts }
type Invalid = { name: string; input: string; code: string }
type Format = { name: string; parts: WfrnParts; expected?: string; error?: string }
type Pattern = { name: string; pattern: string; matches: string[]; misses: string[] }
type InvalidPattern = { name: string; pattern: string; code: string }
type Minted = { name: string; type: string; ulid: string | null; id: string; parsed?: { type: string; ulid: string }; error?: string }

const codeOf = (fn: () => unknown): string => {
  try {
    fn()
  } catch (e) {
    if (isWfrnError(e)) return e.code
    throw e
  }
  return 'accepted'
}

describe('valid names', () => {
  it.each((fixtures.valid as Valid[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    expect(parseWfrn(f.input)).toEqual(f.parts)
    expect(formatWfrn(f.parts)).toBe(f.input)
    expect(isValidWfrn(f.input)).toBe(true)
    expect(wfrnProblem(f.input)).toBeNull()
  })
})

describe('invalid names', () => {
  it.each((fixtures.invalid as Invalid[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    expect(codeOf(() => parseWfrn(f.input))).toBe(f.code)
    expect(isValidWfrn(f.input)).toBe(false)
    expect(wfrnProblem(f.input)).toBe(f.code)
  })
})

describe('format', () => {
  it.each((fixtures.format as Format[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    if (f.error) expect(codeOf(() => formatWfrn(f.parts))).toBe(f.error)
    else expect(formatWfrn(f.parts)).toBe(f.expected)
  })
})

describe('patterns', () => {
  it.each((fixtures.patterns as Pattern[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    expect(() => validateWfrnPattern(f.pattern)).not.toThrow()
    for (const s of f.matches) expect(matchesWfrn(f.pattern, s), `should match ${s}`).toBe(true)
    for (const s of f.misses) expect(matchesWfrn(f.pattern, s), `should miss ${s}`).toBe(false)
  })
})

describe('invalid patterns', () => {
  it.each((fixtures.invalidPatterns as InvalidPattern[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    expect(codeOf(() => validateWfrnPattern(f.pattern))).toBe(f.code)
    expect(codeOf(() => matchesWfrn(f.pattern, 'wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya'))).toBe(f.code)
  })
})

describe('minted ids', () => {
  it.each((fixtures.mintedIds as Minted[]).map((f) => [f.name, f] as const))('%s', (_name, f) => {
    if (f.parsed) {
      expect(mintId(f.type, f.ulid as string)).toBe(f.id)
      expect(parseMintedId(f.id, f.type)).toEqual(f.parsed)
      // the type can also be read off the id itself
      expect(parseMintedId(f.id)).toEqual(f.parsed)
    } else {
      expect(codeOf(() => parseMintedId(f.id, f.type))).toBe(f.error)
    }
  })
})

describe('the error', () => {
  it('carries the code and quotes the input in its message', () => {
    try {
      parseWfrn('wfrn:wf:compute:global:12345:vm/x')
      expect.unreachable()
    } catch (e) {
      expect(e).toBeInstanceOf(WfrnError)
      expect((e as WfrnError).code).toBe('wfrn.account')
      expect((e as WfrnError).message).toContain('12 digits')
      expect((e as WfrnError).message).toContain('wfrn:wf:compute:global:12345:vm/x')
    }
  })

  it('does not quote a name that is only too long', () => {
    expect(codeOf(() => parseWfrn(`wfrn:wf:compute:global:123456789012:vm/${'a'.repeat(1100)}`))).toBe('wfrn.length')
  })

  it('matches a name that is not a wfrn as false, never a throw', () => {
    expect(matchesWfrn('wfrn:wf:*', 'arn:aws:ec2:us-east-1:123456789012:instance/i-0abc')).toBe(false)
    expect(matchesWfrn('wfrn:wf:*', '')).toBe(false)
  })
})

describe('the fixture file', () => {
  it('runs every list', () => {
    for (const list of ['valid', 'invalid', 'format', 'patterns', 'invalidPatterns', 'mintedIds'] as const) {
      expect(Array.isArray(fixtures[list]), list).toBe(true)
      expect(fixtures[list].length, list).toBeGreaterThan(0)
    }
  })
})
