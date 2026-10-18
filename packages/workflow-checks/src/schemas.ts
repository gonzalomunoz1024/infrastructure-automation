/**
 * The JSON Schema helpers the checks need, mirroring Schemas.java: an override block merged into
 * a schema, the dotted paths a schema describes, and whether a schema lists every field.
 */
import { asBoolean, asText, fieldNames, get, has, isObject } from './json.ts';
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
