#!/usr/bin/env node
// Checks every contract in the hub (`npm run check`): the OpenAPI and AsyncAPI files, the JSON
// schemas, the type overlays and the region, the list of used platform operations, the event
// catalog against the plan, and the shared wfrn fixtures against the JavaScript reference.
// Exits 1 on any failure; warnings are printed and do not fail the run.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { Validator as OpenApiValidator } from '@seriousme/openapi-schema-validator';
import { Parser as AsyncApiParser } from '@asyncapi/parser';
import { runFixtures } from '../contracts/wfrn/reference.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const rel = (p) => relative(root, p);
let failures = 0;
let warnings = 0;
const ok = (what) => console.log(`ok    ${what}`);
const fail = (what, detail) => {
  failures++;
  console.log(`FAIL  ${what}`);
  if (detail) console.log(indent(detail));
};
const warn = (what, detail) => {
  warnings++;
  console.log(`warn  ${what}`);
  if (detail) console.log(indent(detail));
};
const indent = (s) => String(s).split('\n').map((l) => '      ' + l).join('\n');
const readYaml = (p) => YAML.parse(readFileSync(p, 'utf8'));
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const filesUnder = (dir, suffix) => {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) out.push(...filesUnder(p, suffix));
    else if (p.endsWith(suffix)) out.push(p);
  }
  return out.sort();
};

// A local JSON pointer resolver for `#/...` references inside one document.
const resolveRef = (doc, ref) => {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) return undefined;
  let cur = doc;
  for (const seg of ref.slice(2).split('/')) {
    cur = cur?.[seg.replace(/~1/g, '/').replace(/~0/g, '~')];
    if (cur === undefined) return undefined;
  }
  return cur;
};
const collectRefs = (node, out = new Set()) => {
  if (!node || typeof node !== 'object') return out;
  if (Array.isArray(node)) {
    node.forEach((n) => collectRefs(n, out));
    return out;
  }
  for (const [k, v] of Object.entries(node)) {
    if (k === '$ref' && typeof v === 'string') out.add(v);
    else collectRefs(v, out);
  }
  return out;
};

const PLATFORM_CHANGE = /^P-(0[1-9]|1[0-9]|2[0-2])$/;
const CAMEL = /^[a-z][a-zA-Z0-9]*$/;
const BUILT_IN_COLUMNS = new Set(['name', 'status', 'wfrn', 'owner', 'created', 'updated']);

// ---------------------------------------------------------------------------------------------
// 1. OpenAPI documents: the platform target and every service draft
// ---------------------------------------------------------------------------------------------
async function checkOpenApi() {
  const target = join(root, 'contracts/platform/openapi-target.yaml');
  const services = filesUnder(join(root, 'contracts/openapi'), '.yaml');
  const docs = new Map();
  for (const file of [target, ...services]) {
    let doc;
    try {
      doc = readYaml(file);
    } catch (e) {
      fail(`${rel(file)} parses as YAML`, e.message);
      continue;
    }
    const validator = new OpenApiValidator();
    const result = await validator.validate(doc);
    if (!result.valid) {
      fail(`${rel(file)} is valid OpenAPI 3.1`, JSON.stringify(result.errors.slice(0, 5), null, 1));
      continue;
    }
    const unresolved = [...collectRefs(doc)].filter((r) => resolveRef(doc, r) === undefined);
    if (unresolved.length) fail(`${rel(file)}: every $ref resolves`, unresolved.join('\n'));
    const ids = new Map();
    const problems = [];
    const declaredTags = new Set((doc.tags ?? []).map((t) => t.name));
    for (const [path, item] of Object.entries(doc.paths ?? {})) {
      for (const [method, op] of Object.entries(item)) {
        if (method === 'parameters' || typeof op !== 'object') continue;
        const where = `${method.toUpperCase()} ${path}`;
        if (!op.operationId) problems.push(`${where}: no operationId`);
        else if (!CAMEL.test(op.operationId)) problems.push(`${where}: operationId ${op.operationId} is not camelCase`);
        else if (ids.has(op.operationId)) problems.push(`${where}: operationId ${op.operationId} is also ${ids.get(op.operationId)}`);
        else ids.set(op.operationId, where);
        if (!Array.isArray(op.tags) || !op.tags.length) problems.push(`${where}: no tags`);
        else for (const t of op.tags) if (!declaredTags.has(t)) problems.push(`${where}: tag ${t} is not declared`);
        if (file === target) {
          if (!Array.isArray(op['x-scopes'])) problems.push(`${where}: no x-scopes`);
          if (!Array.isArray(op['x-platform-change'])) problems.push(`${where}: no x-platform-change`);
          else for (const c of op['x-platform-change']) if (!PLATFORM_CHANGE.test(c)) problems.push(`${where}: x-platform-change ${c} is not a platform change`);
        }
      }
    }
    if (file === target) {
      // every annotation anywhere in the document names a real platform change
      const walk = (node, at) => {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) return node.forEach((n, i) => walk(n, `${at}/${i}`));
        for (const [k, v] of Object.entries(node)) {
          if (k === 'x-platform-change') {
            if (!Array.isArray(v)) problems.push(`${at}: x-platform-change is not a list`);
            else for (const c of v) if (!PLATFORM_CHANGE.test(c)) problems.push(`${at}: x-platform-change ${c} is not a platform change`);
          } else walk(v, `${at}/${k}`);
        }
      };
      walk(doc.components, '#/components');
    } else {
      if (doc['x-status'] !== 'draft') problems.push('x-status is not draft');
      if (!doc.paths?.['/actuator/health']?.get) problems.push('no GET /actuator/health');
      const text = readFileSync(file, 'utf8');
      for (const rule of ['404', 'items, next', 'correlationId']) if (!text.includes(rule)) problems.push(`does not mention ${rule} (account scope, paging or error shape)`);
    }
    if (problems.length) fail(`${rel(file)}: operation metadata`, problems.join('\n'));
    else ok(`${rel(file)}: valid OpenAPI 3.1, ${ids.size} operations`);
    docs.set(file, doc);
  }
  return docs.get(target);
}

// ---------------------------------------------------------------------------------------------
// 2. AsyncAPI documents
// ---------------------------------------------------------------------------------------------
async function checkAsyncApi() {
  const files = [join(root, 'contracts/platform/asyncapi-target.yaml'), ...filesUnder(join(root, 'contracts/asyncapi'), '.yaml')];
  const parser = new AsyncApiParser();
  const parsed = new Map();
  for (const file of files) {
    const { document, diagnostics } = await parser.parse(readFileSync(file, 'utf8'));
    const errors = diagnostics.filter((d) => d.severity === 0);
    const warns = diagnostics.filter((d) => d.severity === 1);
    const format = (d) => `${d.code}: ${d.message}${d.path?.length ? ' at ' + d.path.join('/') : ''}`;
    if (!document || errors.length) {
      fail(`${rel(file)} is valid AsyncAPI 3`, errors.map(format).join('\n'));
      continue;
    }
    if (warns.length) warn(`${rel(file)}: parser warnings`, warns.map(format).join('\n'));
    ok(`${rel(file)}: valid AsyncAPI 3, ${document.components().messages().length} messages, ${document.channels().length} channels`);
    parsed.set(file, readYaml(file));
  }
  return parsed.get(files[0]);
}

// ---------------------------------------------------------------------------------------------
// 3. JSON schemas, overlays, the region, provider actions and schema fields
// ---------------------------------------------------------------------------------------------
function checkSchemasAndOverlays() {
  const ajv = new Ajv2020({ strictSchema: true, strictTypes: 'log', strictTuples: true, strictRequired: false, allErrors: true, allowUnionTypes: true });
  addFormats(ajv);
  const schemaDir = join(root, 'contracts/schemas');
  const schemas = {};
  for (const file of filesUnder(schemaDir, '.schema.json')) {
    const schema = readJson(file);
    if (!schema.$id) {
      fail(`${rel(file)} has an $id`);
      continue;
    }
    if (!ajv.validateSchema(schema)) {
      fail(`${rel(file)} is a valid JSON Schema (draft 2020-12)`, ajv.errorsText(ajv.errors));
      continue;
    }
    ajv.addSchema(schema);
    schemas[file.split('/').pop()] = schema;
  }
  const validatorFor = (name) => {
    const schema = schemas[name];
    if (!schema) {
      fail(`contracts/schemas/${name} exists`);
      return null;
    }
    try {
      const v = ajv.getSchema(schema.$id) ?? ajv.compile(schema);
      ok(`contracts/schemas/${name} compiles`);
      return v;
    } catch (e) {
      fail(`contracts/schemas/${name} compiles`, e.message);
      return null;
    }
  };
  const vType = validatorFor('resource-type.schema.json');
  const vOverlay = validatorFor('type-overlay.schema.json');
  const vRegion = validatorFor('region.schema.json');
  const vFixtures = validatorFor('wfrn-fixtures.schema.json');

  // the pinned platform content
  const sampleTypes = {};
  for (const file of filesUnder(join(root, 'contracts/platform/sample-content/resource-types'), '.yaml')) {
    const doc = readYaml(file);
    sampleTypes[doc.metadata?.name] = doc;
    if (vType && !vType(doc)) fail(`${rel(file)} validates against resource-type.schema.json`, ajv.errorsText(vType.errors));
  }
  if (vType) ok(`${Object.keys(sampleTypes).length} pinned resource types validate against resource-type.schema.json`);
  const providers = {};
  const priceLists = {};
  for (const file of filesUnder(join(root, 'contracts/platform/sample-content/providers'), '.yaml')) {
    const doc = readYaml(file);
    if (doc.kind === 'PriceList') {
      priceLists[doc.metadata?.name] = { file, prices: doc.spec?.prices ?? [] };
      continue;
    }
    const actions = {};
    for (const a of doc.spec?.actions ?? []) if (a?.id) actions[a.id] = a;
    providers[doc.metadata?.name] = { file, actions };
  }

  // the overlays, each merged over its pinned type
  const merge = (a, b) => {
    if (Array.isArray(a) || Array.isArray(b) || typeof a !== 'object' || typeof b !== 'object' || !a || !b) return b;
    const o = { ...a };
    for (const k of Object.keys(b)) o[k] = k in o ? merge(o[k], b[k]) : b[k];
    return o;
  };
  for (const file of filesUnder(join(root, 'type-overlays'), '.yaml').filter((f) => !f.includes('/regions/'))) {
    const overlay = readYaml(file);
    if (vOverlay && !vOverlay(overlay)) {
      fail(`${rel(file)} validates against type-overlay.schema.json`, ajv.errorsText(vOverlay.errors));
      continue;
    }
    const name = overlay.metadata?.name;
    const type = sampleTypes[name];
    if (!type) {
      fail(`${rel(file)} overlays a pinned resource type`, `no contracts/platform/sample-content/resource-types/${name}.yaml`);
      continue;
    }
    const { apiVersion, kind, metadata, ...rest } = overlay;
    const merged = merge(type, { metadata: { service: metadata.service }, ...rest });
    if (vType && !vType(merged)) {
      fail(`${rel(file)} merged over ${name}.yaml validates against resource-type.schema.json`, ajv.errorsText(vType.errors));
      continue;
    }
    const fields = new Set(Object.keys(merged.schema?.properties ?? {}));
    const problems = [];
    for (const col of merged.console?.list?.columns ?? []) if (!fields.has(col) && !BUILT_IN_COLUMNS.has(col)) problems.push(`list column ${col} is not a field of the merged schema`);
    const sort = merged.console?.list?.defaultSort?.replace(/^-/, '');
    if (sort && !fields.has(sort) && !BUILT_IN_COLUMNS.has(sort)) problems.push(`defaultSort ${sort} is not a field of the merged schema`);
    const observe = merged.observe;
    if (observe) {
      for (const field of Object.keys(observe.map ?? {})) if (!fields.has(field)) problems.push(`observe.map field ${field} is not a field of the merged schema`);
      for (const field of observe.ignore ?? []) if (!fields.has(field)) problems.push(`observe.ignore field ${field} is not a field of the merged schema`);
      const provider = providers[observe.provider];
      if (!provider) problems.push(`observe.provider ${observe.provider} is not a pinned provider`);
      else {
        const actions = [['observe.action', observe.action], ['observe.discover.action', observe.discover?.action], ['observe.list.action', observe.list?.action], ['observe.metrics.action', observe.metrics?.action]];
        for (const [what, id] of actions) {
          if (!id) continue;
          const action = provider.actions[id];
          if (!action) problems.push(`${what} ${id} is not an action of ${observe.provider} (${rel(provider.file)})`);
          else if (action.effect !== 'read') problems.push(`${what} ${id} has effect ${action.effect}, not read`);
        }
      }
    }
    const cost = merged.cost;
    if (cost) {
      // P-22: the binding names a price that exists, with the same `by`, on a field of the type
      const list = priceLists[cost.priceList];
      if (!list) problems.push(`cost.priceList ${cost.priceList} is not a pinned kind: PriceList`);
      else {
        const price = list.prices.find((p) => p.provider === cost.provider && p.action === cost.action);
        if (!price) problems.push(`cost: ${cost.priceList} has no price for ${cost.provider} ${cost.action} (${rel(list.file)})`);
        else if ((price.by ?? null) !== (cost.by ?? null)) problems.push(`cost.by ${cost.by ?? '(none)'} is not the price's by ${price.by ?? '(none)'}`);
      }
      if (!providers[cost.provider]?.actions[cost.action]) problems.push(`cost.action ${cost.action} is not an action of ${cost.provider}`);
      if (cost.by && !fields.has(cost.by)) problems.push(`cost.by ${cost.by} is not a field of the merged schema`);
    }
    if (problems.length) fail(`${rel(file)}: columns, observe fields, price binding and provider actions`, problems.join('\n'));
    else ok(`${rel(file)} validates, merges over ${name}.yaml and names real fields, read actions and prices`);
  }
  for (const file of filesUnder(join(root, 'type-overlays/regions'), '.yaml')) {
    const region = readYaml(file);
    if (vRegion && !vRegion(region)) fail(`${rel(file)} validates against region.schema.json`, ajv.errorsText(vRegion.errors));
    else if (vRegion) ok(`${rel(file)} validates against region.schema.json`);
  }
  return { vFixtures, errorsText: () => ajv.errorsText(vFixtures?.errors) };
}

// ---------------------------------------------------------------------------------------------
// 4. used-operations.txt against the target's operation ids
// ---------------------------------------------------------------------------------------------
function checkUsedOperations(target) {
  const file = join(root, 'contracts/platform/used-operations.txt');
  const lines = readFileSync(file, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  const header = readFileSync(file, 'utf8').split('\n').slice(0, 5).join('\n');
  if (!/nightly/i.test(header)) warn(`${rel(file)} says in its header that the nightly check reads it`);
  const ids = new Set();
  for (const item of Object.values(target?.paths ?? {})) for (const [m, op] of Object.entries(item)) if (m !== 'parameters' && op?.operationId) ids.add(op.operationId);
  const missing = lines.filter((l) => !ids.has(l));
  const duplicates = lines.filter((l, i) => lines.indexOf(l) !== i);
  const unlisted = [...ids].filter((id) => !lines.includes(id));
  if (missing.length) fail(`${rel(file)}: every listed operation exists in openapi-target.yaml`, missing.join('\n'));
  else ok(`${rel(file)}: ${lines.length} operations, all in openapi-target.yaml`);
  if (duplicates.length) fail(`${rel(file)}: no duplicates`, duplicates.join('\n'));
  if (unlisted.length) warn(`${rel(file)}: target operations the console does not list`, unlisted.join('\n'));
  const sorted = [...lines].sort();
  if (lines.some((l, i) => l !== sorted[i])) warn(`${rel(file)} is sorted`);
}

// ---------------------------------------------------------------------------------------------
// 5. The event catalog against the plan
// ---------------------------------------------------------------------------------------------
function checkEventCatalog(asyncapi) {
  const readme = readFileSync(join(root, 'platform-changes/README.md'), 'utf8');
  const expected = new Set();
  const start = readme.indexOf('The first catalog:');
  if (start < 0) {
    fail('platform-changes/README.md names the first event catalog');
    return;
  }
  const paragraph = readme.slice(start, readme.indexOf('\n\n', start));
  for (const m of paragraph.matchAll(/`([a-z-]+\.[a-z-]+)`/g)) expected.add(m[1]);
  const auditRow = readme.split('\n').find((l) => /^\s*\|\s*`platform\.audit`/.test(l)) ?? '';
  for (const m of auditRow.matchAll(/`(audit\.[a-z-]+)`/g)) expected.add(m[1]);
  if (/`content\.published`/.test(readme)) expected.add('content.published');
  const actual = new Set();
  for (const [name, message] of Object.entries(asyncapi?.components?.messages ?? {})) {
    const type = resolveRef(asyncapi, message.payload?.$ref)?.allOf?.[1]?.properties?.type?.const ?? message.name;
    if (!type) fail(`asyncapi-target.yaml: message ${name} names its event type`);
    else actual.add(type);
    if (message.name && type && message.name !== type) fail(`asyncapi-target.yaml: message ${name} has name ${message.name} but type ${type}`);
  }
  const missing = [...expected].filter((t) => !actual.has(t));
  const extra = [...actual].filter((t) => !expected.has(t));
  if (missing.length) fail('asyncapi-target.yaml: every event type of the plan has a message', missing.join('\n'));
  if (extra.length) fail('asyncapi-target.yaml: no event type outside the plan', extra.join('\n'));
  if (!missing.length && !extra.length) ok(`asyncapi-target.yaml: ${actual.size} event types match the plan's catalog (P-01, P-19, platform.audit)`);
}

// ---------------------------------------------------------------------------------------------
// 6. The shared wfrn fixtures
// ---------------------------------------------------------------------------------------------
function checkFixtures({ vFixtures, errorsText }) {
  const file = join(root, 'contracts/wfrn/fixtures.json');
  const fixtures = readJson(file);
  if (vFixtures) {
    if (!vFixtures(fixtures)) fail(`${rel(file)} validates against wfrn-fixtures.schema.json`, errorsText());
    else ok(`${rel(file)} validates against wfrn-fixtures.schema.json`);
  }
  const results = runFixtures(fixtures);
  if (results.length) fail(`${rel(file)} passes contracts/wfrn/reference.mjs`, results.map((r) => `${r.list} ${r.name}: ${r.detail}`).join('\n'));
  else ok(`${rel(file)} passes contracts/wfrn/reference.mjs`);
}

const target = await checkOpenApi();
const asyncapi = await checkAsyncApi();
const fixtureValidator = checkSchemasAndOverlays();
checkUsedOperations(target);
checkEventCatalog(asyncapi);
checkFixtures(fixtureValidator);

console.log('');
console.log(`${failures} failure${failures === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'}`);
process.exit(failures ? 1 : 0);
