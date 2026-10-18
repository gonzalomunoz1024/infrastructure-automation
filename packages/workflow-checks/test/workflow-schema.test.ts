/**
 * The Workflow file's JSON Schema as the first check: the shared message table both twins answer
 * the same way (WorkflowSchemaTest.java reads it too), the samples, the validator's keyword set,
 * the meta-check of api.input and references out of a schema.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { check, checkYaml, inputSchemaProblems, metaViolations, remoteRef, stepOf, validateWorkflowDocument, VALIDATOR_KEYWORDS, WORKFLOW_SCHEMA, type Problem } from '../src/index.ts';
import { FIXTURES, ofKind, repoContext } from './repo.ts';

interface Case {
  readonly name: string;
  readonly yaml: string;
  readonly problems: Problem[];
}

const table = JSON.parse(readFileSync(join(FIXTURES, 'schema-messages.json'), 'utf8')) as { cases: Case[] };
const ctx = repoContext();

describe('the shared message table', () => {
  for (const c of table.cases) {
    it(c.name, () => {
      expect(checkYaml(c.yaml, ctx)).toEqual(c.problems);
    });
  }
});

describe('the Workflow schema', () => {
  it('passes every sample workflow as written', () => {
    const files = ofKind('Workflow');
    expect(files.length).toBeGreaterThanOrEqual(9);
    expect(files.flatMap((f) => validateWorkflowDocument(f.doc).map((p) => `${f.path}: ${p.field ?? ''} ${p.message}`))).toEqual([]);
  });

  it('uses only keywords the validator judges', () => {
    const used = new Set<string>();
    collectKeywords(WORKFLOW_SCHEMA, used, false);
    expect([...used].filter((k) => !VALIDATOR_KEYWORDS.includes(k))).toEqual([]);
  });

  it('comes first and the parser second', () => {
    // a step with two step keys matches the schema; the parser refuses it
    const twoKeys = check({ kind: 'Workflow', metadata: { name: 'x' }, workflow: { do: [{ a: { call: 'provider', run: {} } }] } }, ctx);
    expect(twoKeys).toEqual([{ level: 'error', code: 'yaml.invalid', message: "Step 'a' has two step keys: call and run" }]);
    // a file of another kind is named by the parser
    expect(check({ kind: 'Provider', metadata: { name: 'x' }, spec: {} }, ctx)).toEqual([{ level: 'error', code: 'yaml.invalid', message: 'kind must be Workflow, was Provider' }]);
    // and a schema violation stops there: no rule speaks about a file whose structure is wrong
    const problems = checkYaml(readFileSync(join(FIXTURES, 'bad-step-key.yaml'), 'utf8'), ctx);
    expect(problems.map((p) => `${p.code} ${p.step ?? ''} ${p.field ?? ''}`)).toEqual(['schema.invalid getVm /workflow/do/0/getVm/loop']);
  });

  it('locates a problem in the innermost step', () => {
    expect(stepOf('')).toBeNull();
    expect(stepOf('/metadata/foo')).toBeNull();
    expect(stepOf('/workflow/do/0')).toBeNull();
    expect(stepOf('/workflow/do/0/getVm/loop')).toBe('getVm');
    expect(stepOf('/workflow/do/1/both/fork/branches/0/2/left/retry/maxAttempts')).toBe('left');
    expect(stepOf('/workflow/do/0/each/for/do/0/inner/wait')).toBe('inner');
    expect(stepOf('/workflow/do/0/guard/try/catch/do/1/report/notify')).toBe('report');
    expect(stepOf('/workflow/catch/do/0/cleanUp/call')).toBe('cleanUp');
    expect(stepOf('/workflow/do/0/make/compensate/0/undo/call')).toBe('undo');
    expect(stepOf('/workflow/do/0/pick/switch/0/case/do/0/a~1b/call')).toBe('a/b');
  });
});

describe('api.input as a schema', () => {
  it('a pattern that does not compile is located at the pattern', () => {
    const problems = inputSchemaProblems({ type: 'object', properties: { a: { type: 'string', pattern: '([' } } });
    expect(problems.map((p) => `${p.code} ${p.field ?? ''}`)).toEqual(['api.input.invalid api.input.properties.a.pattern']);
  });

  it('a usable schema has no violations, also in an older dialect', () => {
    expect(metaViolations({ type: 'object', properties: { a: { type: ['string', 'null'], enum: ['x', null] } }, required: ['a'] })).toEqual([]);
    expect(metaViolations({ $schema: 'http://json-schema.org/draft-07/schema#', type: 'object', properties: { a: { type: 'array', items: [{ type: 'string' }] } } })).toEqual([]);
    expect(metaViolations({ type: 'object', properties: { a: { type: 'array', items: [{ type: 'string' }] } } })).toEqual([
      { pointer: '/properties/a/items', message: 'array found, [object, boolean] expected' },
    ]);
  });

  it('a reference out of the schema is found wherever it is, but not in data', () => {
    expect(remoteRef({ type: 'object', properties: { a: { $ref: '#/$defs/a' } }, $defs: { a: { type: 'string' } } })).toBeNull();
    expect(remoteRef({ allOf: [{ $ref: 'other.json' }] })).toEqual({ pointer: '/allOf/0/$ref', ref: 'other.json' });
    expect(remoteRef({ properties: { $ref: { $dynamicRef: 'https://example.com/s' } } })).toEqual({ pointer: '/properties/$ref/$dynamicRef', ref: 'https://example.com/s' });
    expect(remoteRef({ enum: [{ $ref: 'https://example.com/s' }], default: { $ref: 'https://example.com/s' } })).toBeNull();
  });

  it('a reference out of api.input is the one problem about it', () => {
    const problems = inputSchemaProblems({ type: 'object', properties: { a: { $ref: 'https://169.254.169.254/latest/meta-data', type: 5 } } });
    expect(problems.map((p) => `${p.code} ${p.field ?? ''}`)).toEqual(['schema.remoteRef api.input.properties.a.$ref']);
  });
});

/** Every key in a schema position of the schema document (skipping property names and data). */
function collectKeywords(node: unknown, out: Set<string>, names: boolean): void {
  if (Array.isArray(node)) {
    node.forEach((n) => collectKeywords(n, out, false));
    return;
  }
  if (typeof node !== 'object' || node === null) {
    return;
  }
  for (const [key, value] of Object.entries(node)) {
    if (names) {
      collectKeywords(value, out, false);
      continue;
    }
    out.add(key);
    if (key === 'enum' || key === 'const') {
      continue;
    }
    collectKeywords(value, out, key === 'properties' || key === '$defs');
  }
}
