#!/usr/bin/env node
// Checks every contract in the hub (`npm run check`): that every YAML entry has a value, the
// OpenAPI and AsyncAPI files, the JSON schemas, the type overlays and the region, the list of used
// platform operations, the event catalog against the plan, the shared wfrn fixtures against the
// JavaScript reference, and the blueprints against the pinned types and workflows.
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

const PLATFORM_CHANGE = /^P-(0[1-9]|1[0-9]|2[0-3])$/;
const CAMEL = /^[a-z][a-zA-Z0-9]*$/;
const BUILT_IN_COLUMNS = new Set(['name', 'status', 'wfrn', 'owner', 'created', 'updated']);

// ---------------------------------------------------------------------------------------------
// 0. Every YAML file: no entry without a value. In a flow mapping a comma ends a plain scalar, so
//    `{ type: string, description: The job, when there is one }` cuts the description at the comma
//    and makes `when there is one` a key with no value, which the validators let pass as an
//    unknown keyword. A description that holds a comma is quoted.
// ---------------------------------------------------------------------------------------------
const MAY_BE_NULL = new Set(['default', 'example', 'const']);
function checkEntriesHaveValues() {
  const files = [...filesUnder(join(root, 'contracts'), '.yaml'), ...filesUnder(join(root, 'type-overlays'), '.yaml'),
    ...filesUnder(join(root, 'blueprints'), '.yaml')];
  const problems = [];
  for (const file of files) {
    const lineCounter = new YAML.LineCounter();
    const walk = (node) => {
      if (YAML.isMap(node)) {
        for (const pair of node.items) {
          const key = YAML.isScalar(pair.key) ? String(pair.key.value) : String(pair.key);
          const empty = pair.value == null || (YAML.isScalar(pair.value) && pair.value.value === null);
          if (empty && !MAY_BE_NULL.has(key)) {
            const line = pair.key?.range ? `:${lineCounter.linePos(pair.key.range[0]).line}` : '';
            problems.push(`${rel(file)}${line}: '${key}' has no value (quote a description that holds a comma)`);
          }
          walk(pair.value);
        }
      } else if (YAML.isSeq(node)) node.items.forEach(walk);
    };
    for (const doc of YAML.parseAllDocuments(readFileSync(file, 'utf8'), { lineCounter })) walk(doc.contents);
  }
  if (problems.length) fail('every YAML entry has a value', problems.join('\n'));
  else ok(`every YAML entry has a value (${files.length} files)`);
}

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
  const vBlueprint = validatorFor('blueprint.schema.json');

  // the pinned platform content
  const sampleTypes = {};
  for (const file of filesUnder(join(root, 'contracts/platform/sample-content/resource-types'), '.yaml')) {
    const doc = readYaml(file);
    sampleTypes[doc.metadata?.name] = doc;
    if (vType && !vType(doc)) fail(`${rel(file)} validates against resource-type.schema.json`, ajv.errorsText(vType.errors));
  }
  if (vType) ok(`${Object.keys(sampleTypes).length} pinned resource types validate against resource-type.schema.json`);
  const providers = {};
  for (const file of filesUnder(join(root, 'contracts/platform/sample-content/providers'), '.yaml')) {
    const doc = readYaml(file);
    const actions = {};
    for (const a of doc.spec?.actions ?? []) if (a?.id) actions[a.id] = a;
    providers[doc.metadata?.name] = { file, actions };
  }

  // the overlays, each merged over its pinned type
  const mergedTypes = { ...sampleTypes };
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
    mergedTypes[name] = merged;
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
    if (problems.length) fail(`${rel(file)}: columns, observe fields and provider actions`, problems.join('\n'));
    else ok(`${rel(file)} validates, merges over ${name}.yaml and names real fields and read actions`);
  }
  for (const file of filesUnder(join(root, 'type-overlays/regions'), '.yaml')) {
    const region = readYaml(file);
    if (vRegion && !vRegion(region)) fail(`${rel(file)} validates against region.schema.json`, ajv.errorsText(vRegion.errors));
    else if (vRegion) ok(`${rel(file)} validates against region.schema.json`);
  }
  return { vFixtures, errorsText: () => ajv.errorsText(vFixtures?.errors), vBlueprint, blueprintErrors: () => ajv.errorsText(vBlueprint?.errors), mergedTypes };
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

// ---------------------------------------------------------------------------------------------
// 7. Blueprints (hub decision 33): each one against the schema, its pinned types and workflows.
//    A resource's input must be what the type's create workflow (its producedBy) takes: known
//    inputs, every required one given, literals the input accepts, and a reference only where its
//    value fits (a parameter's enum within the input's, a registry id of the type an x-resource
//    input names). References name declared parameters and resources, never a sensitive field;
//    the resources form no cycle; a resource teardown deletes has a delete workflow whose required
//    inputs the blueprint fills.
// ---------------------------------------------------------------------------------------------
const REFERENCE = /\$\{([^}]*)\}/g;
const WHOLE_REFERENCE = /^\$\{([^}]*)\}$/;
const STACK_FIELDS = new Set(['name', 'wfrn', 'accountId', 'region']);
const an = (word) => (/^[aeiou]/.test(word) ? 'an ' : 'a ') + word;
const RESOURCE_FIELDS = new Set(['wfrn', 'registryId', 'name']);
function checkBlueprints({ vBlueprint, blueprintErrors, mergedTypes }) {
  const files = filesUnder(join(root, 'blueprints'), '.yaml');
  if (!files.length) {
    warn('blueprints/ holds no blueprint');
    return;
  }
  const literalAjv = new Ajv2020({ strict: false, allErrors: true, allowUnionTypes: true });
  addFormats(literalAjv);
  const workflows = {};
  for (const file of filesUnder(join(root, 'contracts/platform/sample-content/workflows'), '.yaml')) {
    const doc = readYaml(file);
    if (doc?.metadata?.name) workflows[doc.metadata.name] = { ...doc.metadata, input: doc.api?.input ?? {}, subject: doc.api?.subject, file };
  }
  const plain = (schema) => {
    // the input schema without the console's hints, for a literal's check
    const out = {};
    for (const [k, v] of Object.entries(schema ?? {})) if (!k.startsWith('x-')) out[k] = v;
    return out;
  };
  const subjectOf = (wf) => {
    if (wf.subject) return wf.subject;
    const candidates = Object.entries(wf.input.properties ?? {}).filter(([, p]) => p?.['x-resource']?.type === wf.noun);
    return candidates.length === 1 ? candidates[0][0] : null;
  };
  for (const file of files) {
    let bp;
    try {
      bp = readYaml(file);
    } catch (e) {
      fail(`${rel(file)} parses as YAML`, e.message);
      continue;
    }
    if (vBlueprint && !vBlueprint(bp)) {
      fail(`${rel(file)} validates against blueprint.schema.json`, blueprintErrors());
      continue;
    }
    const problems = [];
    const base = file.split('/').pop().replace(/\.yaml$/, '');
    if (bp.metadata.name !== base) problems.push(`metadata.name ${bp.metadata.name} is not the file's name (${base})`);
    const params = bp.spec.parameters ?? {};
    const resources = bp.spec.resources;
    const used = new Set();

    // the parameters
    const fits = (param, value) => {
      if (param.type === 'integer') return Number.isInteger(value) && (param.minimum === undefined || value >= param.minimum) && (param.maximum === undefined || value <= param.maximum);
      if (param.type === 'boolean') return typeof value === 'boolean';
      return typeof value === 'string' && (!param.pattern || new RegExp(param.pattern).test(value));
    };
    for (const [name, param] of Object.entries(params)) {
      const at = `parameter ${name}`;
      if (param.pattern !== undefined) {
        if (param.type !== 'string') problems.push(`${at}: pattern is for a string`);
        try {
          new RegExp(param.pattern);
        } catch (e) {
          problems.push(`${at}: pattern does not compile (${e.message})`);
        }
      }
      if ((param.minimum !== undefined || param.maximum !== undefined) && param.type !== 'integer') problems.push(`${at}: minimum and maximum are for an integer`);
      if (param.minimum !== undefined && param.maximum !== undefined && param.minimum > param.maximum) problems.push(`${at}: minimum is above maximum`);
      if (param['x-resource']) {
        if (param.type !== 'string') problems.push(`${at}: x-resource is a registry id, a string`);
        if (!mergedTypes[param['x-resource'].type]) problems.push(`${at}: x-resource type ${param['x-resource'].type} is not a pinned resource type`);
      }
      for (const value of param.enum ?? []) if (!fits({ ...param, pattern: undefined }, value)) problems.push(`${at}: enum value ${JSON.stringify(value)} is not ${an(param.type)}`);
      if (param.default !== undefined) {
        if (!fits(param, param.default)) problems.push(`${at}: default ${JSON.stringify(param.default)} does not fit the parameter`);
        if (param.enum && !param.enum.includes(param.default)) problems.push(`${at}: default ${JSON.stringify(param.default)} is not in its enum`);
      }
    }

    // a reference, where it may appear; answers the referenced value's type and x-resource type
    const resolve = (expr, where, { self, allowResources = true, allowTeardown = false, edges }) => {
      const parts = expr.trim().split('.');
      const [root0, a, b, c] = parts;
      if (root0 === 'params' && parts.length === 2) {
        const param = params[a];
        if (!param) return problems.push(`${where}: \${${expr}} names no parameter`) && null;
        used.add(a);
        return { kind: 'param', param, type: param.type, xResource: param['x-resource']?.type };
      }
      if (root0 === 'stack' && parts.length === 2) {
        if (!STACK_FIELDS.has(a)) return problems.push(`${where}: \${${expr}}: a stack has ${[...STACK_FIELDS].join(', ')}`) && null;
        return { kind: 'stack', type: 'string' };
      }
      if (root0 === 'teardown' && parts.length === 2 && a === 'reason') {
        if (!allowTeardown) return problems.push(`${where}: \${${expr}} is only known to deleteInput`) && null;
        return { kind: 'teardown', type: 'string' };
      }
      if (root0 === 'resources' && parts.length >= 3) {
        if (!allowResources) return problems.push(`${where}: \${${expr}}: deleteInput may name parameters, the stack and the teardown, not resources`) && null;
        const target = resources[a];
        if (!target) return problems.push(`${where}: \${${expr}} names no resource`) && null;
        if (a === self) return problems.push(`${where}: \${${expr}} names the resource itself`) && null;
        edges?.add(a);
        if (parts.length === 3 && RESOURCE_FIELDS.has(b)) return { kind: 'resource', field: b, type: 'string', xResource: b === 'registryId' ? target.type : undefined };
        if (parts.length === 4 && b === 'attributes') {
          const field = mergedTypes[target.type]?.schema?.properties?.[c];
          if (!field) return problems.push(`${where}: \${${expr}}: ${c} is not a field of ${target.type}`) && null;
          if (field['x-console']?.sensitive) return problems.push(`${where}: \${${expr}}: ${target.type}.${c} is sensitive and never leaves its resource`) && null;
          return { kind: 'attribute', type: field.type };
        }
        return problems.push(`${where}: \${${expr}}: a resource has ${[...RESOURCE_FIELDS].join(', ')} and attributes.<field>`) && null;
      }
      return problems.push(`${where}: \${${expr}} is not a reference (params, resources, stack${allowTeardown ? ', teardown' : ''})`) && null;
    };
    const referencesIn = (value, where, opts) => {
      if (typeof value === 'string') {
        for (const m of value.matchAll(REFERENCE)) resolve(m[1], where, opts);
      } else if (Array.isArray(value)) value.forEach((v, i) => referencesIn(v, `${where}/${i}`, opts));
      else if (value && typeof value === 'object') for (const [k, v] of Object.entries(value)) referencesIn(v, `${where}/${k}`, opts);
    };
    // a value for one workflow input: a literal it accepts, or references whose values fit it
    const checkValue = (prop, value, where, opts) => {
      const whole = typeof value === 'string' ? value.match(WHOLE_REFERENCE) : null;
      if (whole) {
        const ref = resolve(whole[1], where, opts);
        if (!ref) return;
        if (prop.type && ref.type && prop.type !== ref.type) problems.push(`${where}: \${${whole[1]}} is ${an(ref.type)}, the input takes ${an(prop.type)}`);
        const wants = prop['x-resource']?.type;
        if (wants && ref.xResource !== wants) problems.push(`${where}: the input takes the registry id of ${an(wants)}; \${${whole[1]}} is not one`);
        if (ref.kind === 'param') {
          const param = ref.param;
          if (prop.enum) {
            if (!param.enum) problems.push(`${where}: the input takes one of ${prop.enum.join(', ')}; give parameter ${whole[1].split('.')[1]} an enum within it`);
            else for (const v of param.enum) if (!prop.enum.includes(v)) problems.push(`${where}: parameter value ${JSON.stringify(v)} is not one the input takes (${prop.enum.join(', ')})`);
          }
          if (prop.pattern && !param.enum && !param.pattern) problems.push(`${where}: the input has a pattern; give the parameter an enum or a pattern`);
          if (param.enum && prop.pattern) for (const v of param.enum) if (typeof v === 'string' && !new RegExp(prop.pattern).test(v)) problems.push(`${where}: parameter value ${JSON.stringify(v)} does not match the input's pattern`);
          if (prop.minimum !== undefined && !(param.minimum >= prop.minimum)) problems.push(`${where}: the input's minimum is ${prop.minimum}; give the parameter a minimum at least that`);
          if (prop.maximum !== undefined && !(param.maximum <= prop.maximum)) problems.push(`${where}: the input's maximum is ${prop.maximum}; give the parameter a maximum at most that`);
        }
        return;
      }
      if (typeof value === 'string' && REFERENCE.test(value)) {
        REFERENCE.lastIndex = 0;
        if (prop.type && prop.type !== 'string') problems.push(`${where}: text with references is a string, the input takes ${an(prop.type)}`);
        if (prop.enum) problems.push(`${where}: the input takes one of ${prop.enum.join(', ')}; text with references cannot promise one`);
        if (prop['x-resource']) problems.push(`${where}: the input takes a registry id; name it with one reference`);
        referencesIn(value, where, opts);
        return;
      }
      REFERENCE.lastIndex = 0;
      referencesIn(value, where, opts);
      const validate = literalAjv.compile(plain(prop));
      if (!validate(value)) problems.push(`${where}: ${JSON.stringify(value)} is not a value the input takes (${literalAjv.errorsText(validate.errors)})`);
    };
    const checkInputs = (wf, given, where, opts, skip) => {
      const props = wf.input.properties ?? {};
      for (const [key, value] of Object.entries(given ?? {})) {
        if (key === skip) {
          problems.push(`${where}/${key}: the console fills the subject input with the resource's registry id`);
          continue;
        }
        if (!props[key]) {
          problems.push(`${where}/${key}: not an input of ${wf.name} (${Object.keys(props).join(', ')})`);
          continue;
        }
        checkValue(props[key], value, `${where}/${key}`, opts);
      }
      for (const required of wf.input.required ?? []) {
        if (required === skip) continue;
        if (!(required in (given ?? {})) && props[required]?.default === undefined) problems.push(`${where}: ${wf.name} requires ${required}, which has no default`);
      }
    };

    // the resources
    const graph = {};
    for (const [id, res] of Object.entries(resources)) {
      const at = `resources/${id}`;
      const edges = new Set();
      graph[id] = edges;
      const type = mergedTypes[res.type];
      if (!type) {
        problems.push(`${at}: type ${res.type} is not a pinned resource type`);
        continue;
      }
      const create = workflows[type.producedBy];
      if (!create) problems.push(`${at}: ${res.type} is produced by ${type.producedBy}, which is not a pinned workflow`);
      else if (create.noun !== res.type) problems.push(`${at}: ${type.producedBy} makes ${an(create.noun)}, not ${an(res.type)}`);
      else checkInputs(create, res.input, `${at}/input`, { self: id, edges });
      for (const [tag, value] of Object.entries(res.tags ?? {})) referencesIn(value, `${at}/tags/${tag}`, { self: id, edges });
      for (const dep of res.dependsOn ?? []) {
        if (!resources[dep]) problems.push(`${at}: dependsOn ${dep} names no resource`);
        else if (dep === id) problems.push(`${at}: dependsOn names the resource itself`);
        else edges.add(dep);
      }
      if ((res.deletionPolicy ?? 'delete') === 'delete') {
        const deletes = Object.values(workflows).filter((w) => w.noun === res.type && w.verb === 'delete');
        if (!deletes.length) problems.push(`${at}: teardown deletes it, but ${res.type} has no delete workflow among the pinned ones; set deletionPolicy: retain`);
        else {
          if (deletes.length > 1) warn(`${rel(file)}: ${at}: ${res.type} has ${deletes.length} delete workflows; the console runs the catalog's`);
          const subject = subjectOf(deletes[0]);
          if (!subject) problems.push(`${at}: ${deletes[0].name} has no subject input (P-08) for the console to fill`);
          checkInputs(deletes[0], res.deleteInput, `${at}/deleteInput`, { self: id, allowResources: false, allowTeardown: true }, subject);
        }
      } else if (res.deleteInput) problems.push(`${at}: deleteInput is for a resource teardown deletes`);
    }

    // no cycle, and the waves the state service will start
    const waves = [];
    const placed = new Map();
    const visiting = new Set();
    const depth = (id, path) => {
      if (placed.has(id)) return placed.get(id);
      if (visiting.has(id)) {
        problems.push(`resources: a cycle ${[...path, id].join(' -> ')}`);
        return 0;
      }
      visiting.add(id);
      let d = 0;
      for (const dep of graph[id] ?? []) d = Math.max(d, depth(dep, [...path, id]) + 1);
      visiting.delete(id);
      placed.set(id, d);
      return d;
    };
    for (const id of Object.keys(graph)) depth(id, []);
    for (const [id, d] of placed) (waves[d] ??= []).push(id);

    // the outputs
    for (const [name, output] of Object.entries(bp.spec.outputs ?? {})) referencesIn(output.value, `outputs/${name}`, {});

    for (const name of Object.keys(params)) if (!used.has(name)) warn(`${rel(file)}: parameter ${name} is never used`);
    if (problems.length) fail(`${rel(file)}: inputs, references, order and teardown`, problems.join('\n'));
    else ok(`${rel(file)} validates; ${Object.keys(resources).length} resources in ${waves.length} waves (${waves.map((w) => w.join(' + ')).join(', then ')}), inputs fit their workflows`);
  }
}

checkEntriesHaveValues();
const target = await checkOpenApi();
const asyncapi = await checkAsyncApi();
const schemaResults = checkSchemasAndOverlays();
checkUsedOperations(target);
checkEventCatalog(asyncapi);
checkFixtures(schemaResults);
checkBlueprints(schemaResults);

console.log('');
console.log(`${failures} failure${failures === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'}`);
process.exit(failures ? 1 : 0);
