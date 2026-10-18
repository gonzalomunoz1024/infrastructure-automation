/**
 * The JSON Schema helpers the checks need, mirroring Schemas.java: an override block merged into
 * a schema, the dotted paths a schema describes, whether a schema lists every field, a reference
 * out of a schema (never fetched, refused as schema.remoteRef) and why a schema is not one the
 * validator can use (api.input.invalid).
 */
import { asBoolean, asText, fieldNames, get, has, isArray, isObject } from './json.ts';
import type { JsonObject, JsonValue } from './types.ts';

/** Deep-merge an override block into a schema (overrides win, nothing is removed). */
export function mergeSchema(schema: unknown, override: unknown): JsonObject {
  const out: JsonObject = isObject(schema) ? (structuredClone(schema) as JsonObject) : {};
  if (!isObject(override)) {
    return out;
  }
  for (const key of fieldNames(override)) {
    const existing = out[key];
    const value = override[key] as JsonValue;
    out[key] = isObject(existing) && isObject(value) ? mergeSchema(existing, value) : value;
  }
  return out;
}

/** Every dotted leaf (and container) path a schema describes: `vm.id`, `vm.name`, `items`, `items[].id`. */
export function schemaPaths(schema: unknown): string[] {
  const out: string[] = [];
  collectPaths(schema, '', out, 0);
  return out;
}

function collectPaths(schema: unknown, prefix: string, out: string[], depth: number): void {
  if (!isObject(schema) || depth > 8) {
    return;
  }
  const props = get(schema, 'properties');
  if (isObject(props)) {
    for (const key of fieldNames(props)) {
      const p = prefix.length === 0 ? key : `${prefix}.${key}`;
      out.push(p);
      collectPaths(props[key], p, out, depth + 1);
    }
  }
  const items = get(schema, 'items');
  if (isObject(items)) {
    collectPaths(items, prefix.length === 0 ? '[]' : `${prefix}[]`, out, depth + 1);
  }
}

/** A schema that lists every field: an object with properties and no additional ones. */
export function isClosed(schema: unknown): schema is JsonObject {
  if (!isObject(schema)) {
    return false;
  }
  if (has(schema, 'type') && asText(get(schema, 'type')) !== 'object') {
    return false;
  }
  const extra = get(schema, 'additionalProperties');
  if (extra !== undefined && (isObject(extra) || asBoolean(extra, false))) {
    return false;
  }
  const props = get(schema, 'properties');
  return isObject(props) && fieldNames(props).length > 0;
}

/** Jackson's `JsonNode.equals`: the same value, objects compared without regard to field order. */
export function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) {
    return true;
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => sameJson(x, b[i]));
  }
  if (isObject(a) && isObject(b)) {
    const ka = fieldNames(a);
    const kb = fieldNames(b);
    return ka.length === kb.length && ka.every((k) => has(b, k) && sameJson(a[k], b[k]));
  }
  return false;
}

// ------------------------------------------------------------------ is it a schema

/** A violation of a schema: where (a JSON pointer, `''` for the root) and the validator's message. */
export interface Violation {
  readonly pointer: string;
  readonly message: string;
}

/** A reference out of a schema: where it is (a JSON pointer into the schema) and the IRI it names. */
export interface RemoteRef {
  readonly pointer: string;
  readonly ref: string;
}

const REF_KEYWORDS = new Set(['$ref', '$dynamicRef', '$recursiveRef']);
/** Keywords whose value is data, not a schema: a `$ref` key inside one refers to nothing. */
const DATA_KEYWORDS = new Set(['enum', 'const', 'default', 'examples', 'example']);
/** Keywords whose value maps names to schemas: the names are not keywords. */
const SCHEMA_MAPS = new Set(['properties', 'patternProperties', '$defs', 'definitions', 'dependentSchemas']);
/** Keywords whose value is one schema (in 2020-12 `items` is one too). */
const SCHEMA_KEYWORDS = new Set([
  'additionalProperties',
  'items',
  'additionalItems',
  'contains',
  'not',
  'if',
  'then',
  'else',
  'propertyNames',
  'unevaluatedItems',
  'unevaluatedProperties',
  'contentSchema',
]);
const SCHEMA_ARRAYS = new Set(['allOf', 'anyOf', 'oneOf', 'prefixItems']);
const NON_NEGATIVE_INTEGERS = new Set(['maxLength', 'minLength', 'maxItems', 'minItems', 'maxContains', 'minContains', 'maxProperties', 'minProperties']);
const NUMBERS = new Set(['maximum', 'minimum', 'exclusiveMaximum', 'exclusiveMinimum', 'multipleOf']);
const STRINGS = new Set([
  'pattern',
  'format',
  'title',
  'description',
  '$comment',
  '$id',
  '$schema',
  '$anchor',
  '$dynamicAnchor',
  '$ref',
  '$dynamicRef',
  'contentEncoding',
  'contentMediaType',
]);
const BOOLEANS = new Set(['uniqueItems', 'deprecated', 'readOnly', 'writeOnly']);
const SIMPLE_TYPES: readonly string[] = ['array', 'boolean', 'integer', 'null', 'number', 'object', 'string'];
/** The dialects before 2020-12, where `items` may also be a list of schemas. */
const OLDER_DIALECT = /json-schema\.org\/(draft-0[4-7]|draft\/2019-09)\//;

/** `what` refers to the IRI, said for people; nothing is fetched. */
export function describeRemoteRef(r: RemoteRef, what: string): string {
  return `${what} refers to ${r.ref}, outside itself: only references into the same schema (#/...) resolve, and nothing is fetched`;
}

/**
 * The first reference out of a schema: a `$ref` (or `$dynamicRef`, `$recursiveRef`) that is not a
 * `#` pointer into the same document. Values of data keywords (`enum`, `const`, `default`,
 * `examples`) are not looked into. Null when the schema refers to nothing outside itself.
 */
export function remoteRef(schema: unknown): RemoteRef | null {
  return findRemoteRef(schema, '', false, 0);
}

function findRemoteRef(node: unknown, pointer: string, names: boolean, depth: number): RemoteRef | null {
  if (depth > 64) {
    return null;
  }
  if (isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      const r = findRemoteRef(node[i], `${pointer}/${i}`, false, depth + 1);
      if (r !== null) {
        return r;
      }
    }
    return null;
  }
  if (!isObject(node)) {
    return null;
  }
  for (const key of fieldNames(node)) {
    const v = node[key];
    const at = `${pointer}/${escapePointer(key)}`;
    let r: RemoteRef | null = null;
    if (names) {
      r = findRemoteRef(v, at, false, depth + 1);
    } else if (REF_KEYWORDS.has(key)) {
      if (typeof v === 'string' && !v.startsWith('#')) {
        return { pointer: at, ref: v };
      }
    } else if (!DATA_KEYWORDS.has(key)) {
      r = findRemoteRef(v, at, SCHEMA_MAPS.has(key), depth + 1);
    }
    if (r !== null) {
      return r;
    }
  }
  return null;
}

interface Candidate {
  readonly message: string;
  /** a type mismatch, which gives way to any other message at the same location */
  readonly type: boolean;
}

/**
 * Why a schema is not one the validator can use, mirroring Schemas.metaViolations: its violations of
 * the JSON Schema meta-schema, one per location with the message the backend's validator gives;
 * else a pattern that does not compile or a `#` reference to nothing. Empty for a usable schema.
 * The meta-check covers the vocabulary keywords (types, subschemas, numbers, string arrays); the
 * backend validates against the full meta-schema and compiles the schema as well, so it can find
 * more. A reference out of the schema is {@link remoteRef}'s finding: rule it out first.
 */
export function metaViolations(schema: unknown): Violation[] {
  const found = new Map<string, Candidate[]>();
  const older = isObject(schema) && typeof schema['$schema'] === 'string' && OLDER_DIALECT.test(schema['$schema']);
  meta(schema, '', older, found, 0);
  if (found.size > 0) {
    return [...found.entries()]
      .sort(([a], [b]) => compare(a, b))
      .map(([pointer, candidates]) => ({ pointer, message: preferred(candidates) }));
  }
  return localViolations(schema);
}

function preferred(candidates: readonly Candidate[]): string {
  const sorted = [...candidates].sort((a, b) => (a.type === b.type ? compare(a.message, b.message) : a.type ? 1 : -1));
  return (sorted[0] as Candidate).message;
}

/** Java's String.compareTo: UTF-16 code units. */
function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function note(found: Map<string, Candidate[]>, pointer: string, message: string, type = false): void {
  const list = found.get(pointer) ?? [];
  list.push({ message, type });
  found.set(pointer, list);
}

/** The type name the backend's validator reports for a value: 1.0 is an integer, as there. */
export function jsonTypeName(v: unknown): string {
  if (v === null) {
    return 'null';
  }
  if (isArray(v)) {
    return 'array';
  }
  if (typeof v === 'number') {
    return Number.isInteger(v) ? 'integer' : 'number';
  }
  return typeof v === 'object' ? 'object' : typeof v;
}

function mismatch(found: Map<string, Candidate[]>, pointer: string, v: unknown, expected: string): void {
  note(found, pointer, `${jsonTypeName(v)} found, ${expected} expected`, true);
}

function meta(schema: unknown, at: string, older: boolean, found: Map<string, Candidate[]>, depth: number): void {
  if (depth > 64 || typeof schema === 'boolean') {
    return;
  }
  if (!isObject(schema)) {
    mismatch(found, at, schema, '[object, boolean]');
    return;
  }
  for (const key of fieldNames(schema)) {
    const v = schema[key];
    const p = `${at}/${escapePointer(key)}`;
    if (key === 'type') {
      typeKeyword(v, p, found);
    } else if (key === 'items' && older && isArray(v)) {
      v.forEach((s, i) => meta(s, `${p}/${i}`, older, found, depth + 1));
    } else if (SCHEMA_KEYWORDS.has(key)) {
      meta(v, p, older, found, depth + 1);
    } else if (SCHEMA_ARRAYS.has(key)) {
      if (!isArray(v)) {
        mismatch(found, p, v, 'array');
      } else {
        if (v.length === 0) {
          note(found, p, 'must have at least 1 items but found 0');
        }
        v.forEach((s, i) => meta(s, `${p}/${i}`, older, found, depth + 1));
      }
    } else if (SCHEMA_MAPS.has(key)) {
      if (!isObject(v)) {
        mismatch(found, p, v, 'object');
      } else {
        for (const name of fieldNames(v)) {
          meta(v[name], `${p}/${escapePointer(name)}`, older, found, depth + 1);
        }
      }
    } else if (key === 'required') {
      stringArray(v, p, found);
    } else if (key === 'dependentRequired') {
      if (!isObject(v)) {
        mismatch(found, p, v, 'object');
      } else {
        for (const name of fieldNames(v)) {
          stringArray(v[name], `${p}/${escapePointer(name)}`, found);
        }
      }
    } else if (NON_NEGATIVE_INTEGERS.has(key)) {
      if (jsonTypeName(v) !== 'integer') {
        mismatch(found, p, v, 'integer');
      } else if ((v as number) < 0) {
        note(found, p, 'must have a minimum value of 0');
      }
    } else if (NUMBERS.has(key)) {
      if (typeof v !== 'number') {
        mismatch(found, p, v, 'number');
      } else if (key === 'multipleOf' && v <= 0) {
        note(found, p, 'must have an exclusive minimum value of 0');
      }
    } else if (STRINGS.has(key)) {
      if (typeof v !== 'string') {
        mismatch(found, p, v, 'string');
      }
    } else if (BOOLEANS.has(key)) {
      if (typeof v !== 'boolean') {
        mismatch(found, p, v, 'boolean');
      }
    } else if (key === 'enum' || key === 'examples') {
      if (!isArray(v)) {
        mismatch(found, p, v, 'array');
      }
    }
  }
}

/** `type`: one of the simple types, or a non-empty list of distinct ones; the backend's validator words a miss as an enumeration. */
function typeKeyword(v: unknown, p: string, found: Map<string, Candidate[]>): void {
  const simple = (t: unknown): boolean => typeof t === 'string' && SIMPLE_TYPES.includes(t);
  if (simple(v) || (isArray(v) && v.length > 0 && v.every(simple) && new Set(v).size === v.length)) {
    return;
  }
  const message = `does not have a value in the enumeration [${SIMPLE_TYPES.map((t) => JSON.stringify(t)).join(', ')}]`;
  note(found, p, message);
  if (isArray(v)) {
    v.forEach((t, i) => {
      if (!simple(t)) {
        note(found, `${p}/${i}`, message);
      }
    });
  }
}

function stringArray(v: unknown, p: string, found: Map<string, Candidate[]>): void {
  if (!isArray(v)) {
    mismatch(found, p, v, 'array');
    return;
  }
  v.forEach((e, i) => {
    if (typeof e !== 'string') {
      mismatch(found, `${p}/${i}`, e, 'string');
    }
  });
  if (new Set(v.map((e) => JSON.stringify(e))).size !== v.length) {
    note(found, p, 'must have only unique items in the array');
  }
}

/** Patterns that do not compile and `#` references that point at nothing, in document order. */
function localViolations(schema: unknown): Violation[] {
  const out: Violation[] = [];
  const scoped = hasNestedId(schema);
  walk(schema, '', false, 0, (node, pointer) => {
    const pattern = node['pattern'];
    if (typeof pattern === 'string') {
      compilePattern(pattern, `${pointer}/pattern`, out);
    }
    const patternProperties = node['patternProperties'];
    if (isObject(patternProperties)) {
      for (const p of fieldNames(patternProperties)) {
        compilePattern(p, `${pointer}/patternProperties/${escapePointer(p)}`, out);
      }
    }
    const ref = node['$ref'];
    if (!scoped && typeof ref === 'string' && ref.startsWith('#') && !resolves(schema, ref)) {
      out.push({ pointer: `${pointer}/$ref`, message: `Reference ${ref} cannot be resolved` });
    }
  });
  return out;
}

/** Visit every subschema (objects in schema positions), skipping data keywords and map names. */
function walk(node: unknown, pointer: string, names: boolean, depth: number, visit: (schema: JsonObject, pointer: string) => void): void {
  if (depth > 64) {
    return;
  }
  if (isArray(node)) {
    node.forEach((v, i) => walk(v, `${pointer}/${i}`, false, depth + 1, visit));
    return;
  }
  if (!isObject(node)) {
    return;
  }
  if (!names) {
    visit(node, pointer);
  }
  for (const key of fieldNames(node)) {
    const at = `${pointer}/${escapePointer(key)}`;
    if (names) {
      walk(node[key], at, false, depth + 1, visit);
    } else if (!DATA_KEYWORDS.has(key) && !REF_KEYWORDS.has(key)) {
      walk(node[key], at, SCHEMA_MAPS.has(key), depth + 1, visit);
    }
  }
}

function compilePattern(regex: string, pointer: string, out: Violation[]): void {
  try {
    new RegExp(regex);
  } catch (e) {
    out.push({ pointer, message: `pattern does not compile: ${e instanceof Error ? e.message : String(e)}` });
  }
}

/** A nested `$id` moves where `#` references resolve; such schemas are left to the backend's validator. */
function hasNestedId(schema: unknown): boolean {
  let found = false;
  walk(schema, '', false, 0, (node, pointer) => {
    found = found || (pointer !== '' && has(node, '$id'));
  });
  return found;
}

/** Whether a `#` reference points into the schema: `#`, a JSON pointer `#/...` or an anchor `#name`. */
function resolves(root: unknown, ref: string): boolean {
  let fragment: string;
  try {
    fragment = decodeURIComponent(ref.substring(1));
  } catch {
    return false;
  }
  if (fragment === '') {
    return true;
  }
  if (!fragment.startsWith('/')) {
    let found = false;
    walk(root, '', false, 0, (node) => {
      found = found || node['$anchor'] === fragment || node['$dynamicAnchor'] === fragment;
    });
    return found;
  }
  let at: unknown = root;
  for (const segment of fragment.substring(1).split('/')) {
    const key = segment.replaceAll('~1', '/').replaceAll('~0', '~');
    if (isArray(at) && /^\d+$/.test(key)) {
      at = at[Number(key)];
    } else {
      at = get(at, key);
    }
    if (at === undefined) {
      return false;
    }
  }
  return true;
}

/** A JSON pointer segment: `~` and `/` escaped. */
export function escapePointer(key: string): string {
  return key.replaceAll('~', '~0').replaceAll('/', '~1');
}

/** A JSON pointer as a dotted suffix: `/properties/vmId/type` is `.properties.vmId.type`, the root is empty. */
export function dotted(pointer: string): string {
  if (pointer === '') {
    return '';
  }
  return pointer
    .substring(1)
    .split('/')
    .map((s) => `.${s.replaceAll('~1', '/').replaceAll('~0', '~')}`)
    .join('');
}
