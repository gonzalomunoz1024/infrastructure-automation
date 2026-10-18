/**
 * The JSON Schema of a Workflow file (`schemas/workflow.schema.json`) and the validator the checks
 * judge a document with before the parser reads it, mirroring WorkflowSchema.java: one
 * `schema.invalid` problem per violation, its field the JSON pointer of the value, its step the
 * innermost step the pointer leads into. The validator knows exactly the keywords the schema uses
 * ({@link VALIDATOR_KEYWORDS}, kept so by a test); the backend validates with a full JSON Schema
 * 2020-12 validator and the same file (a byte-identical copy in its resources), and both word a
 * violation the same way.
 */
import schemaDocument from '../schemas/workflow.schema.json' with { type: 'json' };
import { fieldNames, get, has, isArray, isObject } from './json.ts';
import { escapePointer, jsonTypeName, sameJson, type Violation } from './schemas.ts';
import type { JsonObject, Problem } from './types.ts';

/** The Workflow file's JSON Schema, as `GET /api/v1/schemas/workflow.json` serves it. */
export const WORKFLOW_SCHEMA: JsonObject = schemaDocument as JsonObject;

/** The keywords {@link schemaViolations} judges; annotations ($schema, title, description) are read and ignored. */
export const VALIDATOR_KEYWORDS: readonly string[] = [
  '$schema',
  '$defs',
  '$ref',
  'title',
  'description',
  'type',
  'enum',
  'const',
  'properties',
  'additionalProperties',
  'required',
  'items',
  'minProperties',
  'maxProperties',
];

/**
 * One `schema.invalid` error per violation of the Workflow schema, sorted by pointer: the field is
 * the JSON pointer of the value (`/workflow/do/0/getVm/loop`), the step the innermost step it is inside.
 */
export function validateWorkflowDocument(doc: unknown): Problem[] {
  return schemaViolations(WORKFLOW_SCHEMA, doc).map((v) => {
    const step = stepOf(v.pointer);
    return {
      level: 'error',
      code: 'schema.invalid',
      ...(step !== null ? { step } : {}),
      ...(v.pointer !== '' ? { field: v.pointer } : {}),
      message: v.message,
    };
  });
}

/**
 * The violations of a schema written with {@link VALIDATOR_KEYWORDS}, worded as the backend's
 * validator words them, sorted by pointer then message. An unknown property is located at the
 * property (`/metadata/foo`); every message carries the location of the value it judges, the
 * object for an unknown or missing property.
 */
export function schemaViolations(schema: JsonObject, data: unknown): Violation[] {
  const out = new Map<string, Violation>();
  visit(schema, schema, data, '', out, 0);
  return [...out.values()].sort((a, b) => compare(a.pointer, b.pointer) || compare(a.message, b.message));
}

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function add(out: Map<string, Violation>, pointer: string, location: string, error: string): void {
  const message = location === '' ? error : `${location}: ${error}`;
  out.set(`${pointer}\u0000${message}`, { pointer, message });
}

function visit(root: JsonObject, schema: unknown, value: unknown, at: string, out: Map<string, Violation>, depth: number): void {
  if (!isObject(schema) || depth > 128) {
    return;
  }
  const ref = get(schema, '$ref');
  if (typeof ref === 'string') {
    visit(root, resolve(root, ref), value, at, out, depth + 1);
  }
  const type = get(schema, 'type');
  if (type !== undefined && !typeMatches(type, value)) {
    add(out, at, at, `${jsonTypeName(value)} found, ${isArray(type) ? `[${type.join(', ')}]` : String(type)} expected`);
  }
  const values = get(schema, 'enum');
  if (isArray(values) && !values.some((e) => sameJson(e, value))) {
    add(out, at, at, `does not have a value in the enumeration [${values.map((e) => JSON.stringify(e)).join(', ')}]`);
  }
  if (has(schema, 'const')) {
    const c = get(schema, 'const');
    if (!sameJson(c, value)) {
      add(out, at, at, `must be the constant value '${typeof c === 'string' ? c : JSON.stringify(c)}'`);
    }
  }
  if (isObject(value)) {
    const names = fieldNames(value);
    for (const r of isArray(get(schema, 'required')) ? (get(schema, 'required') as unknown[]) : []) {
      if (typeof r === 'string' && !has(value, r)) {
        add(out, at, at, `required property '${r}' not found`);
      }
    }
    const min = get(schema, 'minProperties');
    if (typeof min === 'number' && names.length < min) {
      add(out, at, at, `must have at least ${min} properties`);
    }
    const max = get(schema, 'maxProperties');
    if (typeof max === 'number' && names.length > max) {
      add(out, at, at, `must have at most ${max} properties`);
    }
    const properties = get(schema, 'properties');
    const extra = get(schema, 'additionalProperties');
    for (const name of names) {
      const child = `${at}/${escapePointer(name)}`;
      if (isObject(properties) && has(properties, name)) {
        visit(root, properties[name], value[name], child, out, depth + 1);
      } else if (extra === false) {
        add(out, child, at, `property '${name}' is not defined in the schema and the schema does not allow additional properties`);
      } else if (isObject(extra)) {
        visit(root, extra, value[name], child, out, depth + 1);
      }
    }
  }
  const items = get(schema, 'items');
  if (isArray(value) && items !== undefined) {
    value.forEach((v, i) => visit(root, items, v, `${at}/${i}`, out, depth + 1));
  }
}

function typeMatches(type: unknown, value: unknown): boolean {
  if (isArray(type)) {
    return type.some((t) => typeMatches(t, value));
  }
  const actual = jsonTypeName(value);
  return actual === type || (type === 'number' && actual === 'integer');
}

/** A `#/$defs/name` reference within the schema. */
function resolve(root: JsonObject, ref: string): unknown {
  let at: unknown = root;
  for (const segment of ref.replace(/^#\/?/, '').split('/').filter((s) => s !== '')) {
    at = get(at, segment.replaceAll('~1', '/').replaceAll('~0', '~'));
  }
  return at;
}

/**
 * The id of the innermost step a pointer leads into (`/workflow/do/0/getVm/retry` is `getVm`, a
 * step inside a fork branch, a loop or a catch likewise), or null.
 */
export function stepOf(pointer: string): string | null {
  const seg = pointer.split('/');
  const isIndex = (s: string | undefined): boolean => s !== undefined && /^\d+$/.test(s);
  let step: string | null = null;
  for (let i = 1; i < seg.length; i++) {
    if ((seg[i] === 'do' || seg[i] === 'compensate') && i + 2 < seg.length && isIndex(seg[i + 1])) {
      step = unescape(seg[i + 2] as string);
    } else if (seg[i] === 'branches' && i + 3 < seg.length && isIndex(seg[i + 1]) && isIndex(seg[i + 2])) {
      step = unescape(seg[i + 3] as string);
    }
  }
  return step;
}

function unescape(segment: string): string {
  return segment.replaceAll('~1', '/').replaceAll('~0', '~');
}
