#!/usr/bin/env node
// Compares the platform's live OpenAPI document with the target contract, one row per operation
// the console uses (contracts/platform/used-operations.txt), and reports each as:
//   landed    the operation is there with every parameter, field and header the target names
//   pending   the operation, or a parameter, field or header annotated with x-platform-change, is
//             not there yet
//   diverged  the platform has something the target does not, or lacks something the target does
//             not annotate as a pending change, or a type differs
// The exit code is 1 only when something diverged. `npm run check:platform -- --from <file|url>`.
//
//   --from <file|url>     the live OpenAPI document (JSON or YAML file, or an http(s) URL)
//   --header 'N: v'       a header to send when fetching (repeatable), for example 'X-User: gonzalo'
//   --samples <dir>       a directory with resource-types.json, catalog.json and regions.json as
//                         fetched from the platform, for the field evidence of P-06, P-08 and P-07
//   --target <file>       the target contract (contracts/platform/openapi-target.yaml)
//   --used <file>         the used operations (contracts/platform/used-operations.txt)
//   --json <file>         also write the report as JSON
//   --verbose             print every note, not only the pending and diverged ones
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = parseArgs(process.argv.slice(2));
if (!args.from) {
  console.error('usage: platform-contract-check.mjs --from <file|url> [--header "X-User: gonzalo"] [--samples <dir>] [--json <file>] [--verbose]');
  process.exit(2);
}
const targetFile = args.target ?? join(root, 'contracts/platform/openapi-target.yaml');
const usedFile = args.used ?? join(root, 'contracts/platform/used-operations.txt');

const target = YAML.parse(readFileSync(targetFile, 'utf8'));
const live = await loadLive(args.from, args.header ?? []);
const used = readFileSync(usedFile, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));

const targetOps = indexOperations(target);
const liveOps = indexOperations(live);
const liveByRoute = new Map();
for (const op of liveOps.values()) liveByRoute.set(op.route, op);

const rows = [];
for (const id of used) {
  const t = targetOps.get(id);
  if (!t) {
    rows.push({ operationId: id, status: 'diverged', changes: [], notes: ['not in the target contract'] });
    continue;
  }
  rows.push(compareOperation(t, liveByRoute.get(t.route)));
}
if (args.samples) rows.push(...sampleEvidence(args.samples));

print(rows, { verbose: !!args.verbose });
const summary = { landed: 0, pending: 0, diverged: 0 };
for (const r of rows) summary[r.status]++;
if (args.json) {
  writeFileSync(args.json, JSON.stringify({ from: args.from, generatedAt: new Date().toISOString(), summary, operations: rows }, null, 2));
  console.log(`\nreport written to ${args.json}`);
}
console.log(`\nlanded ${summary.landed}, pending ${summary.pending}, diverged ${summary.diverged}`);
process.exit(summary.diverged ? 1 : 0);

// ---------------------------------------------------------------------------------------------

function parseArgs(argv) {
  const out = { header: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--verbose') out.verbose = true;
    else if (a.startsWith('--')) {
      const key = a.slice(2);
      const value = argv[++i];
      if (key === 'header') out.header.push(value);
      else out[key] = value;
    }
  }
  return out;
}

async function loadLive(from, headers) {
  if (/^https?:\/\//.test(from)) {
    const h = {};
    for (const line of headers) {
      const i = line.indexOf(':');
      if (i > 0) h[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    const res = await fetch(from, { headers: h });
    if (!res.ok) throw new Error(`GET ${from} answered ${res.status}`);
    const text = await res.text();
    return text.trimStart().startsWith('{') ? JSON.parse(text) : YAML.parse(text);
  }
  const text = readFileSync(from, 'utf8');
  return from.endsWith('.json') || text.trimStart().startsWith('{') ? JSON.parse(text) : YAML.parse(text);
}

function deref(doc, node, seen = 0) {
  if (!node || typeof node !== 'object' || typeof node.$ref !== 'string' || seen > 20) return node;
  let cur = doc;
  for (const seg of node.$ref.replace(/^#\//, '').split('/')) cur = cur?.[seg.replace(/~1/g, '/').replace(/~0/g, '~')];
  if (cur === undefined) return node;
  const { $ref, ...rest } = node;
  return deref(doc, { ...cur, ...rest }, seen + 1);
}

function changesOf(node) {
  return Array.isArray(node?.['x-platform-change']) ? node['x-platform-change'] : [];
}

function indexOperations(doc) {
  const ops = new Map();
  for (const [path, item] of Object.entries(doc.paths ?? {})) {
    const pathParams = (item.parameters ?? []).map((p) => deref(doc, p));
    for (const [method, raw] of Object.entries(item)) {
      if (method === 'parameters' || typeof raw !== 'object') continue;
      const op = { doc, method: method.toUpperCase(), path, route: `${method.toUpperCase()} ${path.replace(/\{[^}]+\}/g, '{}')}`, raw };
      op.operationId = raw.operationId;
      op.changes = changesOf(raw);
      op.params = [];
      const seen = new Set();
      for (const p of [...(raw.parameters ?? []).map((p) => ({ raw: p, resolved: deref(doc, p) })), ...pathParams.map((p) => ({ raw: p, resolved: p }))]) {
        const key = `${p.resolved.in}:${p.resolved.name.toLowerCase()}`;
        if (seen.has(key)) continue;
        seen.add(key);
        op.params.push({ key, name: p.resolved.name, in: p.resolved.in, required: !!p.resolved.required, schema: p.resolved.schema, pattern: p.resolved['x-name-pattern'], changes: [...new Set([...changesOf(p.raw), ...changesOf(p.resolved)])] });
      }
      ops.set(raw.operationId ?? op.route, op);
    }
  }
  return ops;
}

function typeOf(doc, schema) {
  schema = deref(doc, schema);
  if (!schema || typeof schema !== 'object') return 'unknown';
  if (schema.type) return Array.isArray(schema.type) ? schema.type.filter((t) => t !== 'null').sort().join('|') : schema.type;
  if (schema.properties || schema.allOf || schema.additionalProperties) return 'object';
  if (schema.items) return 'array';
  if (schema.anyOf || schema.oneOf) return 'union';
  if (schema.const !== undefined) return typeof schema.const;
  if (Array.isArray(schema.enum) && schema.enum.length) return typeof schema.enum[0];
  return 'unknown';
}

function compatible(a, b) {
  return a === b || a === 'unknown' || b === 'unknown' || a === 'union' || b === 'union' || (a === 'number' && b === 'integer') || (a === 'integer' && b === 'number');
}

// The top-level properties of a schema, allOf members merged; each keeps its raw node for annotations.
function propertiesOf(doc, schema, seen = 0) {
  const resolved = deref(doc, schema);
  const out = {};
  if (!resolved || typeof resolved !== 'object' || seen > 10) return out;
  for (const member of resolved.allOf ?? []) Object.assign(out, propertiesOf(doc, member, seen + 1));
  for (const [name, node] of Object.entries(resolved.properties ?? {})) out[name] = node;
  return out;
}

function jsonSchemaOf(content) {
  return content?.['application/json']?.schema ?? content?.['text/event-stream']?.schema;
}

function compareSchemas(t, l, targetSchema, liveSchema, where, items) {
  const tResolved = deref(t.doc, targetSchema);
  const lResolved = deref(l.doc, liveSchema);
  // an array answer compares its items
  if (tResolved?.type === 'array' || lResolved?.type === 'array') {
    if (typeOf(t.doc, tResolved) !== typeOf(l.doc, lResolved)) {
      items.push({ level: 'diverged', text: `${where}: target is ${typeOf(t.doc, tResolved)}, platform is ${typeOf(l.doc, lResolved)}` });
      return;
    }
    return compareSchemas(t, l, tResolved.items, lResolved.items, `${where}[]`, items);
  }
  const tProps = propertiesOf(t.doc, targetSchema);
  const lProps = propertiesOf(l.doc, liveSchema);
  if (!Object.keys(tProps).length || !Object.keys(lProps).length) return;
  for (const [name, node] of Object.entries(lProps)) {
    if (!(name in tProps)) items.push({ level: 'diverged', text: `${where}.${name}: the platform has it, the target does not` });
    else if (!compatible(typeOf(t.doc, tProps[name]), typeOf(l.doc, node))) items.push({ level: 'diverged', text: `${where}.${name}: target is ${typeOf(t.doc, tProps[name])}, platform is ${typeOf(l.doc, node)}` });
  }
  for (const [name, node] of Object.entries(tProps)) {
    if (name in lProps) continue;
    const changes = changesOf(node);
    if (changes.length) items.push({ level: 'pending', text: `${where}.${name}: waits for ${changes.join(', ')}` });
    else items.push({ level: 'diverged', text: `${where}.${name}: the target has it, the platform does not, and no change is named` });
  }
}

function compareOperation(t, l) {
  const row = { operationId: t.operationId, platformOperationId: t.raw['x-platform-operation-id'], method: t.method, path: t.path, changes: t.changes, notes: [] };
  const items = [];
  if (!l) {
    items.push({
      level: 'pending',
      text: t.changes.length ? `absent; waits for ${t.changes.join(', ')}` : 'absent from the OpenAPI document today (the endpoint exists; P-13 documents it)',
    });
    return finish(row, items);
  }
  if (row.platformOperationId && l.operationId && row.platformOperationId !== l.operationId) items.push({ level: 'note', text: `platform operationId is ${l.operationId}, not ${row.platformOperationId}` });
  // parameters
  const liveParams = new Map(l.params.map((p) => [p.key, p]));
  for (const p of t.params) {
    let match = liveParams.get(p.key);
    if (!match && p.pattern) {
      const re = new RegExp(p.pattern, 'i');
      match = l.params.find((lp) => lp.in === p.in && re.test(lp.name));
    }
    if (!match) {
      if (p.changes.length) items.push({ level: 'pending', text: `${p.in} ${p.name}: waits for ${p.changes.join(', ')}` });
      else items.push({ level: 'diverged', text: `${p.in} ${p.name}: the target has it, the platform does not, and no change is named` });
      continue;
    }
    const tt = typeOf(t.doc, p.schema);
    const lt = typeOf(l.doc, match.schema);
    if (!compatible(tt, lt)) items.push({ level: 'diverged', text: `${p.in} ${p.name}: target is ${tt}, platform is ${lt}` });
    if (match.required && !p.required) items.push({ level: 'diverged', text: `${p.in} ${p.name}: the platform requires it, the target does not` });
  }
  const targetKeys = new Set(t.params.map((p) => p.key));
  const targetPatterns = t.params.filter((p) => p.pattern).map((p) => ({ in: p.in, re: new RegExp(p.pattern, 'i') }));
  for (const p of l.params) {
    if (targetKeys.has(p.key) || targetPatterns.some((tp) => tp.in === p.in && tp.re.test(p.name))) continue;
    items.push({ level: p.required ? 'diverged' : 'note', text: `${p.in} ${p.name}: the platform ${p.required ? 'requires' : 'takes'} it, the target does not name it` });
  }
  // request body
  const tBody = jsonSchemaOf(t.raw.requestBody?.content);
  const lBody = jsonSchemaOf(l.raw.requestBody?.content);
  if (tBody && !lBody) items.push({ level: t.changes.length ? 'pending' : 'diverged', text: t.changes.length ? `request body: waits for ${t.changes.join(', ')}` : 'request body: the target has one, the platform does not' });
  else if (!tBody && lBody) items.push({ level: 'diverged', text: 'request body: the platform has one, the target does not' });
  else if (tBody && lBody) compareSchemas(t, l, tBody, lBody, 'body', items);
  // responses
  for (const [status, tRes] of Object.entries(t.raw.responses ?? {})) {
    const tr = deref(t.doc, tRes);
    const lr = l.raw.responses?.[status] ? deref(l.doc, l.raw.responses[status]) : undefined;
    if (!lr) {
      if (status.startsWith('2')) items.push({ level: t.changes.length ? 'pending' : 'diverged', text: `response ${status}: the platform does not document it` });
      else items.push({ level: 'note', text: `response ${status}: the platform does not document it` });
      continue;
    }
    for (const [name, header] of Object.entries(tr.headers ?? {})) {
      const lh = Object.keys(lr.headers ?? {}).find((h) => h.toLowerCase() === name.toLowerCase());
      if (lh) continue;
      const changes = [...new Set([...changesOf(header), ...changesOf(deref(t.doc, header))])];
      items.push({ level: changes.length ? 'pending' : 'note', text: `response ${status} header ${name}: ${changes.length ? 'waits for ' + changes.join(', ') : 'the platform does not document it'}` });
    }
    if (status.startsWith('2')) {
      const ts = jsonSchemaOf(tr.content);
      const ls = jsonSchemaOf(lr.content);
      if (ts && ls) compareSchemas(t, l, ts, ls, `response ${status}`, items);
    }
  }
  for (const status of Object.keys(l.raw.responses ?? {})) if (!t.raw.responses?.[status]) items.push({ level: 'note', text: `response ${status}: the platform documents it, the target does not` });
  return finish(row, items);
}

function finish(row, items) {
  row.status = items.some((i) => i.level === 'diverged') ? 'diverged' : items.some((i) => i.level === 'pending') ? 'pending' : 'landed';
  row.notes = items.map((i) => `${i.level}: ${i.text}`);
  if (row.status === 'landed' && row.changes.length) row.notes.push(`note: the surface is there; what ${row.changes.join(', ')} changes in behaviour is not checked here`);
  return row;
}

function sampleEvidence(dir) {
  const rows = [];
  const load = (name) => {
    const p = join(dir, name);
    if (!existsSync(p)) return undefined;
    try {
      return JSON.parse(readFileSync(p, 'utf8'));
    } catch {
      return null;
    }
  };
  const evidence = (id, changes, found, missing, note) => {
    const status = missing.length ? 'pending' : 'landed';
    rows.push({ operationId: id, status, changes, notes: [...(note ? ['note: ' + note] : []), ...found.map((f) => `landed: ${f} is there`), ...missing.map((f) => `pending: ${f} is not there yet`)] });
  };
  const types = load('resource-types.json');
  if (Array.isArray(types) && types.length) {
    const fields = ['service', 'console', 'observe'];
    const found = fields.filter((f) => types.some((t) => t && f in t));
    evidence('samples:resource-types', ['P-06'], found, fields.filter((f) => !found.includes(f)), `${types.length} types read`);
    const hashed = types.some((t) => t && ('hash' in t || 'version' in t));
    evidence('samples:resource-types:published', ['P-19'], hashed ? ['hash or version'] : [], hashed ? [] : ['hash or version']);
  } else rows.push({ operationId: 'samples:resource-types', status: 'pending', changes: ['P-06'], notes: ['pending: resource-types.json is missing or not a list'] });
  const catalog = load('catalog.json');
  if (Array.isArray(catalog) && catalog.length) {
    const fields = ['subjectInput', 'destructive', 'approval', 'mutableFields', 'bulk', 'effect'];
    const found = fields.filter((f) => catalog.some((e) => e && f in e));
    evidence('samples:catalog', ['P-08'], found, fields.filter((f) => !found.includes(f)), `${catalog.length} entries read`);
  } else rows.push({ operationId: 'samples:catalog', status: 'pending', changes: ['P-08'], notes: ['pending: catalog.json is missing or not a list'] });
  const regions = load('regions.json');
  if (Array.isArray(regions) && regions.length) evidence('samples:regions', ['P-07'], ['GET /api/v1/regions'], [], `${regions.length} regions read`);
  else rows.push({ operationId: 'samples:regions', status: 'pending', changes: ['P-07'], notes: ['pending: GET /api/v1/regions answers nothing yet (404 today)'] });
  return rows;
}

function print(rows, { verbose }) {
  const groups = new Map();
  for (const r of rows) {
    const key = r.changes.length ? r.changes.join(', ') : 'today';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(r);
  }
  const keys = [...groups.keys()].sort((a, b) => (a === 'today' ? -1 : b === 'today' ? 1 : a.localeCompare(b)));
  const width = Math.max(...rows.map((r) => r.operationId.length));
  for (const key of keys) {
    const group = groups.get(key);
    const counts = { landed: 0, pending: 0, diverged: 0 };
    for (const r of group) counts[r.status]++;
    console.log(`\n== ${key}  (landed ${counts.landed}, pending ${counts.pending}, diverged ${counts.diverged}) ==`);
    for (const r of group) {
      const route = r.method ? `${r.method} ${r.path}` : '';
      console.log(`${r.status.padEnd(9)} ${r.operationId.padEnd(width)}  ${route}`);
      for (const n of r.notes) if (verbose || !n.startsWith('note:')) console.log(`          ${n}`);
    }
  }
}
