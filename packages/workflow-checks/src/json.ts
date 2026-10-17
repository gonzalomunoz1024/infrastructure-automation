/**
 * Reading loosely typed documents the way the Java backend reads Jackson trees, so both sides
 * coerce the same way: a missing field is `undefined` (Jackson's MissingNode or a null from
 * `get`), an explicit null is `null`, and `asText`/`asInt`/`asBoolean` follow Jackson's rules.
 * Every reader takes `unknown`, since documents arrive straight from a YAML parser.
 */
import type { JsonObject, JsonValue } from './types.ts';

/** A value that may be absent. */
export type Maybe = JsonValue | undefined;

export function isObject(v: unknown): v is JsonObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function isArray(v: unknown): v is JsonValue[] {
  return Array.isArray(v);
}

export function isContainer(v: unknown): v is JsonObject | JsonValue[] {
  return typeof v === 'object' && v !== null;
}

export function isTextual(v: unknown): v is string {
  return typeof v === 'string';
}

export function isNumber(v: unknown): v is number {
  return typeof v === 'number';
}

/** Jackson's `isValueNode`: not a container and not missing. */
export function isValue(v: unknown): boolean {
  return v !== undefined && !isContainer(v);
}

/** `node.get(key)`: the field, or undefined when the value is not an object or has no such field. */
export function get(v: unknown, key: string): Maybe {
  if (!isObject(v) || !Object.hasOwn(v, key)) {
    return undefined;
  }
  return v[key];
}

/** `node.has(key)` */
export function has(v: unknown, key: string): boolean {
  return get(v, key) !== undefined;
}

/** The field names of an object, in document order; empty for anything else. */
export function fieldNames(v: unknown): string[] {
  if (!isObject(v)) {
    return [];
  }
  return Object.keys(v).filter((k) => v[k] !== undefined);
}

/** The elements of an array, the values of an object (Jackson iterates both), else nothing. */
export function elements(v: unknown): JsonValue[] {
  if (isArray(v)) {
    return v.map((x) => (x === undefined ? null : x));
  }
  if (isObject(v)) {
    return fieldNames(v).map((k) => v[k] as JsonValue);
  }
  return [];
}

/** `node.size()`: array length or field count, 0 for anything else. */
export function size(v: unknown): number {
  if (isArray(v)) {
    return v.length;
  }
  if (isObject(v)) {
    return fieldNames(v).length;
  }
  return 0;
}

/** Java's `String.isBlank()`. */
export function isBlank(s: string): boolean {
  return s.trim().length === 0;
}

/** How Jackson prints a number: whole numbers without a decimal point. */
export function numberText(n: number): string {
  return String(n);
}

/**
 * `node.asText()`: text as is, numbers and booleans spelled out, `null` for an explicit null,
 * empty for a missing value or a container.
 */
export function asText(v: unknown): string {
  if (v === undefined) {
    return '';
  }
  if (v === null) {
    return 'null';
  }
  if (typeof v === 'string') {
    return v;
  }
  if (typeof v === 'number') {
    return numberText(v);
  }
  if (typeof v === 'boolean') {
    return v ? 'true' : 'false';
  }
  return '';
}

/** `node.path(field).asText(dflt)`: the default for a missing or null value. */
export function asTextOr<T>(v: unknown, dflt: T): string | T {
  return v === undefined || v === null ? dflt : asText(v);
}

/** The `text(node, field)` helper of the Java readers: null when absent or null, else `asText`. */
export function text(v: unknown, field: string): string | null {
  const x = get(v, field);
  return x === undefined || x === null ? null : asText(x);
}

/** `Double.parseDouble`: null where Java throws NumberFormatException. */
export function parseJavaDouble(s: string): number | null {
  const t = s.trim();
  if (t.length === 0) {
    return null;
  }
  if (/^[+-]?NaN$/.test(t)) {
    return NaN;
  }
  if (/^[+-]?Infinity$/.test(t)) {
    return t.startsWith('-') ? -Infinity : Infinity;
  }
  if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[fFdD]?$/.test(t)) {
    return Number(t.replace(/[fFdD]$/, ''));
  }
  return null;
}

/** `node.asDouble()`: numbers as is, booleans 1 or 0, numeric text parsed, everything else the default. */
export function asDouble(v: unknown, dflt = 0): number {
  if (typeof v === 'number') {
    return v;
  }
  if (typeof v === 'boolean') {
    return v ? 1 : 0;
  }
  if (typeof v === 'string') {
    const n = parseJavaDouble(v);
    return n === null ? dflt : n;
  }
  return dflt;
}

/** Java's `(int) d` and `(long) d`: truncation toward zero, NaN to 0. */
function truncate(n: number): number {
  return Number.isNaN(n) ? 0 : Math.trunc(n);
}

/** `node.asInt(dflt)` / `asLong(dflt)`. */
export function asInt(v: unknown, dflt = 0): number {
  if (typeof v === 'number') {
    return truncate(v);
  }
  if (typeof v === 'boolean') {
    return v ? 1 : 0;
  }
  if (typeof v === 'string') {
    const n = parseJavaDouble(v);
    return n === null ? dflt : truncate(n);
  }
  return dflt;
}

/** `node.asBoolean(dflt)`: booleans as is, numbers by non-zero, the words true and false, else the default. */
export function asBoolean(v: unknown, dflt: boolean): boolean {
  if (typeof v === 'boolean') {
    return v;
  }
  if (typeof v === 'number') {
    return v !== 0;
  }
  if (typeof v === 'string') {
    const t = v.trim();
    if (t === 'true') {
      return true;
    }
    if (t === 'false') {
      return false;
    }
  }
  return dflt;
}

/** A list of strings from a YAML list or a comma separated string. */
export function strings(v: unknown): string[] {
  if (isArray(v)) {
    return v.map((x) => asText(x));
  }
  if (typeof v === 'string') {
    return v
      .split(',')
      .filter((s) => !isBlank(s))
      .map((s) => s.trim());
  }
  return [];
}

/** A list of integers from a non-empty YAML list, else the default. */
export function ints(v: unknown, dflt: number[]): number[] {
  if (!isArray(v) || v.length === 0) {
    return dflt;
  }
  return v.map((x) => asInt(x, 0));
}

/** Jackson's `toString()` of a node: compact JSON. */
export function toJson(v: unknown): string {
  const s = JSON.stringify(v);
  return s === undefined ? '' : s;
}
