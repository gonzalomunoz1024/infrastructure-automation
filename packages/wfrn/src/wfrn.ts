/*
 * The wfrn resource name, as SPEC.md beside this package defines it (copied from the console-root
 * hub at the commit in contracts.lock):
 *
 *   wfrn:<partition>:<service>:<region>:<account>:<type>/<id>
 *
 * Parsing is strict: a name is either canonical or invalid, and every failure names the first
 * broken rule with one of the codes below, so a message reads the same whichever twin judged it.
 */

export type WfrnErrorCode =
  | 'wfrn.length'
  | 'wfrn.case'
  | 'wfrn.wildcard'
  | 'wfrn.prefix'
  | 'wfrn.segments'
  | 'wfrn.partition'
  | 'wfrn.service'
  | 'wfrn.region'
  | 'wfrn.account'
  | 'wfrn.type'
  | 'wfrn.id'

export const WFRN_ERROR_CODES: readonly WfrnErrorCode[] = [
  'wfrn.length',
  'wfrn.case',
  'wfrn.wildcard',
  'wfrn.prefix',
  'wfrn.segments',
  'wfrn.partition',
  'wfrn.service',
  'wfrn.region',
  'wfrn.account',
  'wfrn.type',
  'wfrn.id',
]

/** What each code means, in plain words, for a message shown to a person. */
export const WFRN_MESSAGES: Record<WfrnErrorCode, string> = {
  'wfrn.length': 'a wfrn is at most 1,024 characters',
  'wfrn.case': 'a wfrn is all lowercase',
  'wfrn.wildcard': 'a stored name cannot contain * or ?; wildcards belong in patterns only',
  'wfrn.prefix': 'a wfrn starts with "wfrn:"',
  'wfrn.segments': 'a wfrn has six colon-separated parts, the last one "<type>/<id>"',
  'wfrn.partition': 'the partition is 1 to 32 characters of a-z, 0-9 or -',
  'wfrn.service': 'the service is 2 to 32 characters of a-z, 0-9 or -',
  'wfrn.region': 'the region is 2 to 32 characters of a-z, 0-9 or -, or empty for account-level things',
  'wfrn.account': 'the account is exactly 12 digits, or empty for organization-level things',
  'wfrn.type': 'the type is 1 to 64 characters of a-z, 0-9 or -',
  'wfrn.id': 'the id is one or more path segments of 1 to 128 characters of a-z, 0-9 or -, joined by /',
}

export class WfrnError extends Error {
  readonly code: WfrnErrorCode
  /** the text that was judged, when there was one */
  readonly input?: string

  constructor(code: WfrnErrorCode, input?: string) {
    super(input === undefined ? WFRN_MESSAGES[code] : `${WFRN_MESSAGES[code]}: ${JSON.stringify(input)}`)
    this.name = 'WfrnError'
    this.code = code
    this.input = input
  }
}

export function isWfrnError(e: unknown): e is WfrnError {
  return e instanceof WfrnError
}

export interface WfrnParts {
  partition: string
  service: string
  /** empty for account-level things that live in no region */
  region: string
  /** twelve digits, or empty for organization-level things */
  account: string
  type: string
  /** the part after the first `/`; may itself hold `/` for nested resources */
  id: string
}

export const MAX_LENGTH = 1024

type SegmentName = 'partition' | 'service' | 'region' | 'account' | 'type'

const SEGMENT_NAMES: readonly SegmentName[] = ['partition', 'service', 'region', 'account', 'type']

const SEGMENT: Record<SegmentName, RegExp> = {
  partition: /^[a-z0-9-]{1,32}$/,
  service: /^[a-z0-9-]{2,32}$/,
  region: /^(|[a-z0-9-]{2,32})$/,
  account: /^(|[0-9]{12})$/,
  type: /^[a-z0-9-]{1,64}$/,
}

const MAX_SEGMENT: Record<SegmentName, number> = { partition: 32, service: 32, region: 32, account: 12, type: 64 }

const ID_SEGMENT = /^[a-z0-9-]{1,128}$/
const PATTERN_ALPHABET = /^[a-z0-9*?-]*$/

/** The 26-character lowercase Crockford base32 ULID the state service mints into every id. */
export const ULID_PATTERN = /^[0-7][0-9a-hjkmnp-tv-z]{25}$/

export function isUlid(s: string): boolean {
  return ULID_PATTERN.test(s)
}

const hasWildcard = (s: string) => s.includes('*') || s.includes('?')
/** ASCII uppercase only; the rule is about the alphabet, not about locale folding. */
const hasUppercase = (s: string) => /[A-Z]/.test(s)

/** A segment of a pattern: the alphabet and the maximum length when it holds a wildcard, the full rule otherwise. */
function patternSegmentOk(name: SegmentName, value: string): boolean {
  if (hasWildcard(value)) return value.length >= 1 && value.length <= MAX_SEGMENT[name] && PATTERN_ALPHABET.test(value)
  return SEGMENT[name].test(value)
}

function patternIdSegmentOk(value: string): boolean {
  if (hasWildcard(value)) return value.length >= 1 && value.length <= 128 && PATTERN_ALPHABET.test(value)
  return ID_SEGMENT.test(value)
}

/** `s` split on `:` at most `max` times; the last part keeps the rest (Python's `split(':', max)`). */
function splitColons(s: string, max: number): string[] {
  const parts: string[] = []
  let rest = s
  while (parts.length < max) {
    const i = rest.indexOf(':')
    if (i < 0) break
    parts.push(rest.slice(0, i))
    rest = rest.slice(i + 1)
  }
  parts.push(rest)
  return parts
}

/**
 * Judges a name, or a pattern when `pattern` is true. The order of checks is the order of the code
 * table: length, case and wildcard over the whole string, then the prefix, the segment count, then
 * each segment left to right. A valid prefix pattern (`wfrn:wf:*`) has no parts to give back.
 */
function judge(input: string, pattern: boolean): WfrnParts | null {
  if (input.length > MAX_LENGTH) throw new WfrnError('wfrn.length')
  if (hasUppercase(input)) throw new WfrnError('wfrn.case', input)
  if (!pattern && hasWildcard(input)) throw new WfrnError('wfrn.wildcard', input)
  if (!input.startsWith('wfrn:')) throw new WfrnError('wfrn.prefix', input)
  const parts = splitColons(input, 5)
  if (parts.length < 6) {
    // a pattern may stop early at a segment that ends in *: the segments before it are checked
    if (!(pattern && parts[parts.length - 1].endsWith('*'))) throw new WfrnError('wfrn.segments', input)
    parts.slice(1).forEach((value, i) => {
      const name = SEGMENT_NAMES[i]
      if (!patternSegmentOk(name, value)) throw new WfrnError(`wfrn.${name}`, input)
    })
    return null
  }
  const rest = parts[5]
  let type: string
  let idPart: string | null
  const slash = rest.indexOf('/')
  if (slash < 0) {
    if (pattern && rest.endsWith('*')) {
      type = rest
      idPart = null
    } else {
      throw new WfrnError('wfrn.segments', input)
    }
  } else {
    type = rest.slice(0, slash)
    idPart = rest.slice(slash + 1)
  }
  const segments: Record<SegmentName, string> = { partition: parts[1], service: parts[2], region: parts[3], account: parts[4], type }
  for (const name of SEGMENT_NAMES) {
    const ok = pattern ? patternSegmentOk(name, segments[name]) : SEGMENT[name].test(segments[name])
    if (!ok) throw new WfrnError(`wfrn.${name}`, input)
  }
  let id = ''
  if (idPart !== null) {
    if (idPart === '') throw new WfrnError('wfrn.id', input)
    for (const segment of idPart.split('/')) {
      const ok = pattern ? patternIdSegmentOk(segment) : ID_SEGMENT.test(segment)
      if (!ok) throw new WfrnError('wfrn.id', input)
    }
    id = idPart
  }
  return { ...segments, id }
}

/** The six parts of a canonical name; throws a WfrnError with the first failing rule's code. */
export function parseWfrn(input: string): WfrnParts {
  const parts = judge(input, false)
  // a strict parse always has parts: only a prefix pattern gives none
  if (!parts) throw new WfrnError('wfrn.segments', input)
  return parts
}

/** The one way to build a name: `format(parse(s)) === s` for every valid `s`, and invalid parts are refused with their code. */
export function formatWfrn(parts: WfrnParts): string {
  const s = `wfrn:${parts.partition}:${parts.service}:${parts.region}:${parts.account}:${parts.type}/${parts.id}`
  parseWfrn(s)
  return s
}

export function isValidWfrn(input: string): boolean {
  try {
    parseWfrn(input)
    return true
  } catch (e) {
    if (isWfrnError(e)) return false
    throw e
  }
}

/** The code a name breaks, or null when it is valid. */
export function wfrnProblem(input: string): WfrnErrorCode | null {
  try {
    parseWfrn(input)
    return null
  } catch (e) {
    if (isWfrnError(e)) return e.code
    throw e
  }
}

/** Judges a pattern (a name in which `*` and `?` are wildcards); throws a WfrnError when it breaks a rule. */
export function validateWfrnPattern(pattern: string): void {
  judge(pattern, true)
}

export function isValidWfrnPattern(pattern: string): boolean {
  try {
    validateWfrnPattern(pattern)
    return true
  } catch (e) {
    if (isWfrnError(e)) return false
    throw e
  }
}

const escapeRegExp = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * Whether `name` matches `pattern`: `*` is any run of characters and `?` exactly one, over the
 * whole string and across `:` and `/`, as in IAM. Throws for an invalid pattern; answers false,
 * never throws, for a name that is not a valid wfrn.
 */
export function matchesWfrn(pattern: string, name: string): boolean {
  validateWfrnPattern(pattern)
  if (!isValidWfrn(name)) return false
  let source = '^'
  for (const ch of pattern) {
    if (ch === '*') source += '[\\s\\S]*'
    else if (ch === '?') source += '[\\s\\S]'
    else source += escapeRegExp(ch)
  }
  source += '$'
  return new RegExp(source).test(name)
}

export interface MintedId {
  type: string
  ulid: string
}

/** `<type>-<ulid>`. The ULID is the state service's; this only formats. */
export function mintId(type: string, ulid: string): string {
  return `${type}-${ulid}`
}

/**
 * The type and ULID of a minted id. With `type`, the id must start with `<type>-`; without it the
 * type is whatever precedes the last 27 characters. Fails (`wfrn.case`, `wfrn.id`) on an id whose
 * last 26 characters are not a ULID or whose prefix is not the type.
 */
export function parseMintedId(id: string, type?: string): MintedId {
  if (hasUppercase(id)) throw new WfrnError('wfrn.case', id)
  let prefix: string
  let ulid: string
  if (type !== undefined) {
    if (!id.startsWith(`${type}-`)) throw new WfrnError('wfrn.id', id)
    prefix = type
    ulid = id.slice(type.length + 1)
  } else {
    if (id.length < 28 || id[id.length - 27] !== '-') throw new WfrnError('wfrn.id', id)
    prefix = id.slice(0, id.length - 27)
    ulid = id.slice(id.length - 26)
    if (!SEGMENT.type.test(prefix)) throw new WfrnError('wfrn.id', id)
  }
  if (!ULID_PATTERN.test(ulid)) throw new WfrnError('wfrn.id', id)
  return { type: prefix, ulid }
}

/** Whether an id is a platform-minted one (`<type>-<ulid>`) rather than an imported provider name. */
export function isMintedId(id: string, type?: string): boolean {
  try {
    parseMintedId(id, type)
    return true
  } catch (e) {
    if (isWfrnError(e)) return false
    throw e
  }
}
