// The hub's reference implementation of SPEC.md, in plain JavaScript (Node 22, no dependencies).
//
// It exists to check fixtures.json: `npm run check` runs every fixture through it, so a fixture that
// contradicts the specification is caught here before the Java library (console-libs) and the
// TypeScript package (console-web) both fail on it. It is not a published library; the twins are.
//
// Usage as a program: node contracts/wfrn/reference.mjs contracts/wfrn/fixtures.json

import { readFileSync } from 'node:fs';
import { argv, exit } from 'node:process';
import { fileURLToPath } from 'node:url';

export const MAX_LENGTH = 1024;

const SEGMENT_NAMES = ['partition', 'service', 'region', 'account', 'type'];
const SEGMENT = {
  partition: /^[a-z0-9-]{1,32}$/,
  service: /^[a-z0-9-]{2,32}$/,
  region: /^(|[a-z0-9-]{2,32})$/,
  account: /^(|[0-9]{12})$/,
  type: /^[a-z0-9-]{1,64}$/,
};
const MAX_SEGMENT = { partition: 32, service: 32, region: 32, account: 12, type: 64 };
const ID_SEGMENT = /^[a-z0-9-]{1,128}$/;
const PATTERN_ALPHABET = /^[a-z0-9*?-]+$/;
const ULID = /^[0-7][0-9a-hjkmnp-tv-z]{25}$/;

/** A wfrn rule that failed; `code` is one of the codes of SPEC.md. */
export class WfrnError extends Error {
  constructor(code, message) {
    super(message ?? code);
    this.name = 'WfrnError';
    this.code = code;
  }
}

const hasWildcard = (s) => s.includes('*') || s.includes('?');

function segmentOk(name, value, pattern) {
  if (pattern && hasWildcard(value)) {
    return value.length >= 1 && value.length <= MAX_SEGMENT[name] && PATTERN_ALPHABET.test(value);
  }
  return SEGMENT[name].test(value);
}

function idSegmentOk(value, pattern) {
  if (pattern && hasWildcard(value)) {
    return value.length >= 1 && value.length <= 128 && PATTERN_ALPHABET.test(value);
  }
  return ID_SEGMENT.test(value);
}

/** Python's str.split(sep, n): at most n splits, the rest stays in the last part. */
function splitAtMost(s, sep, n) {
  const parts = [];
  let rest = s;
  for (let i = 0; i < n; i++) {
    const at = rest.indexOf(sep);
    if (at < 0) break;
    parts.push(rest.slice(0, at));
    rest = rest.slice(at + sep.length);
  }
  parts.push(rest);
  return parts;
}

/**
 * Parses a name (or, with `pattern`, a pattern). Returns the six parts, or null for a pattern that
 * stops early at a segment ending in `*`. Throws a WfrnError with the first failing rule.
 */
export function parse(s, pattern = false) {
  if (typeof s !== 'string') throw new WfrnError('wfrn.prefix', 'a wfrn is a string');
  if (s.length > MAX_LENGTH) throw new WfrnError('wfrn.length', `longer than ${MAX_LENGTH} characters`);
  if (/[A-Z]/.test(s)) throw new WfrnError('wfrn.case', 'contains an uppercase letter');
  if (!pattern && hasWildcard(s)) throw new WfrnError('wfrn.wildcard', 'a stored name contains * or ?');
  if (!s.startsWith('wfrn:')) throw new WfrnError('wfrn.prefix', 'does not start with wfrn:');
  const parts = splitAtMost(s, ':', 5);
  if (parts.length < 6) {
    // a prefix pattern stops early at a segment that ends in *
    if (!(pattern && parts[parts.length - 1].endsWith('*'))) {
      throw new WfrnError('wfrn.segments', 'fewer than six colon-separated parts');
    }
    for (let i = 1; i < parts.length; i++) {
      const name = SEGMENT_NAMES[i - 1];
      if (!segmentOk(name, parts[i], true)) throw new WfrnError('wfrn.' + name, `${name} breaks its rule`);
    }
    return null;
  }
  const rest = parts[5];
  let type;
  let idPart;
  const slash = rest.indexOf('/');
  if (slash < 0) {
    if (pattern && rest.endsWith('*')) {
      type = rest;
      idPart = null;
    } else {
      throw new WfrnError('wfrn.segments', 'the last part has no /');
    }
  } else {
    type = rest.slice(0, slash);
    idPart = rest.slice(slash + 1);
  }
  const segments = { partition: parts[1], service: parts[2], region: parts[3], account: parts[4], type };
  for (const name of SEGMENT_NAMES) {
    if (!segmentOk(name, segments[name], pattern)) throw new WfrnError('wfrn.' + name, `${name} breaks its rule`);
  }
  let id;
  if (idPart === null) {
    id = '';
  } else {
    if (idPart === '') throw new WfrnError('wfrn.id', 'the id is empty');
    for (const piece of idPart.split('/')) {
      if (!idSegmentOk(piece, pattern)) throw new WfrnError('wfrn.id', 'a path segment of the id breaks its rule');
    }
    id = idPart;
  }
  return { ...segments, id };
}

/** The only way to build a name: formats the parts and refuses them with the first failing rule. */
export function format(parts) {
  const p = parts ?? {};
  const s = `wfrn:${p.partition ?? ''}:${p.service ?? ''}:${p.region ?? ''}:${p.account ?? ''}:${p.type ?? ''}/${p.id ?? ''}`;
  parse(s);
  return s;
}

function escapeRegExp(c) {
  return c.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
}

/** Whether `name` matches `pattern`; throws for an invalid pattern, answers false for an invalid name. */
export function matches(pattern, name) {
  parse(pattern, true);
  try {
    parse(name);
  } catch (e) {
    if (e instanceof WfrnError) return false;
    throw e;
  }
  let rx = '^';
  for (const c of pattern) {
    if (c === '*') rx += '[\\s\\S]*';
    else if (c === '?') rx += '[\\s\\S]';
    else rx += escapeRegExp(c);
  }
  rx += '$';
  return new RegExp(rx).test(name);
}

/** `<type>-<ulid>`; the ULID is the state service's, this only formats. */
export function mintId(type, ulid) {
  return `${type}-${ulid}`;
}

/** Splits a minted id into its type and ULID, or refuses it. */
export function parseMintedId(type, id) {
  if (/[A-Z]/.test(id)) throw new WfrnError('wfrn.case', 'contains an uppercase letter');
  if (!id.startsWith(type + '-')) throw new WfrnError('wfrn.id', 'the prefix is not the type');
  const ulid = id.slice(type.length + 1);
  if (!ULID.test(ulid)) throw new WfrnError('wfrn.id', 'the last 26 characters are not a ULID');
  return { type, ulid };
}

const sameParts = (a, b) => JSON.stringify(a) === JSON.stringify(b);

function codeOf(fn) {
  try {
    fn();
    return null;
  } catch (e) {
    if (e instanceof WfrnError) return e.code;
    throw e;
  }
}

/** Runs every entry of every list of the fixtures; answers the failures as `{ list, name, detail }`. */
export function runFixtures(fixtures) {
  const failures = [];
  const fail = (list, name, detail) => failures.push({ list, name, detail });

  for (const v of fixtures.valid ?? []) {
    try {
      const parts = parse(v.input);
      if (!sameParts(parts, v.parts)) fail('valid', v.name, `parsed ${JSON.stringify(parts)}`);
      const back = format(v.parts);
      if (back !== v.input) fail('valid', v.name, `format gave ${back}`);
    } catch (e) {
      if (!(e instanceof WfrnError)) throw e;
      fail('valid', v.name, `refused with ${e.code}`);
    }
  }
  for (const v of fixtures.invalid ?? []) {
    const code = codeOf(() => parse(v.input));
    if (code === null) fail('invalid', v.name, 'parsed');
    else if (code !== v.code) fail('invalid', v.name, `refused with ${code}, expected ${v.code}`);
  }
  for (const v of fixtures.format ?? []) {
    let result;
    const code = codeOf(() => {
      result = format(v.parts);
    });
    if (v.error !== undefined) {
      if (code === null) fail('format', v.name, `formatted ${result}`);
      else if (code !== v.error) fail('format', v.name, `refused with ${code}, expected ${v.error}`);
    } else if (code !== null) {
      fail('format', v.name, `refused with ${code}`);
    } else if (result !== v.expected) {
      fail('format', v.name, `formatted ${result}`);
    }
  }
  for (const v of fixtures.patterns ?? []) {
    for (const m of v.matches ?? []) {
      const code = codeOf(() => {
        if (!matches(v.pattern, m)) fail('patterns', v.name, `should match ${m}`);
      });
      if (code !== null) fail('patterns', v.name, `pattern refused with ${code}`);
    }
    for (const m of v.misses ?? []) {
      const code = codeOf(() => {
        if (matches(v.pattern, m)) fail('patterns', v.name, `should miss ${m}`);
      });
      if (code !== null) fail('patterns', v.name, `pattern refused with ${code}`);
    }
  }
  for (const v of fixtures.invalidPatterns ?? []) {
    const code = codeOf(() => parse(v.pattern, true));
    if (code === null) fail('invalidPatterns', v.name, 'accepted');
    else if (code !== v.code) fail('invalidPatterns', v.name, `refused with ${code}, expected ${v.code}`);
  }
  for (const v of fixtures.mintedIds ?? []) {
    if (v.parsed !== undefined) {
      if (mintId(v.type, v.ulid) !== v.id) fail('mintedIds', v.name, `mintId gave ${mintId(v.type, v.ulid)}`);
      let parsed;
      const code = codeOf(() => {
        parsed = parseMintedId(v.type, v.id);
      });
      if (code !== null) fail('mintedIds', v.name, `refused with ${code}`);
      else if (!sameParts(parsed, v.parsed)) fail('mintedIds', v.name, `parsed ${JSON.stringify(parsed)}`);
    } else {
      const code = codeOf(() => parseMintedId(v.type, v.id));
      if (code === null) fail('mintedIds', v.name, 'accepted');
      else if (code !== v.error) fail('mintedIds', v.name, `refused with ${code}, expected ${v.error}`);
    }
  }
  return failures;
}

if (argv[1] && fileURLToPath(import.meta.url) === argv[1]) {
  const file = argv[2] ?? new URL('./fixtures.json', import.meta.url);
  const fixtures = JSON.parse(readFileSync(file, 'utf8'));
  const failures = runFixtures(fixtures);
  for (const f of failures) console.log(`FAIL ${f.list}: ${f.name}: ${f.detail}`);
  console.log(`wfrn fixtures: ${failures.length} failure(s)`);
  exit(failures.length === 0 ? 0 : 1);
}
