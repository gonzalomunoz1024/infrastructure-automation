/**
 * The conformance fixtures under ../fixtures: every rule the Java checks implement, this package
 * implements the same way, judged on the same files with the repository's content as the catalog.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { check, checkYaml, EMPTY_CONTEXT } from '../src/index.ts';
import { fixture, FIXTURES, repoContext } from './repo.ts';

const expected = JSON.parse(readFileSync(join(FIXTURES, 'expected.json'), 'utf8')) as Record<string, string[]>;
const ctx = repoContext();

describe('conformance fixtures', () => {
  for (const [file, codes] of Object.entries(expected)) {
    it(file, () => {
      const problems = checkYaml(fixture(file), ctx);
      const actual = problems.map((p) => `${p.level} ${p.code}`).sort();
      expect(actual, JSON.stringify(problems, null, 2)).toEqual([...codes].sort());
    });
  }

  it('every fixture file has an expectation', () => {
    const yamls = readdirSync(FIXTURES)
      .filter((n) => n.endsWith('.yaml'))
      .sort();
    expect(Object.keys(expected).sort()).toEqual(yamls);
  });
});

describe('fixes', () => {
  it('did-you-mean fixes carry the replacement', () => {
    const problems = checkYaml(fixture('unknown-provider.yaml'), ctx);
    expect(problems).toHaveLength(1);
    expect(problems[0]?.fix).toEqual({ label: 'Did you mean vsphere?', kind: 'replace', value: 'vsphere' });
    expect(problems[0]?.step).toBe('getVm');
    expect(problems[0]?.field).toBe('provider');
  });

  it('a delete without an approval offers an insert-approval fix', () => {
    const problems = checkYaml(fixture('delete-without-approval.yaml'), ctx);
    expect(problems.map((p) => p.code)).toEqual(['policy.approvalRequired']);
    expect(problems[0]?.fix?.kind).toBe('insert-approval');
  });

  it('$json in the first step offers a connect fix', () => {
    const problems = checkYaml(fixture('no-predecessor.yaml'), ctx);
    expect(problems[0]?.fix).toEqual({ label: 'Connect a step before this one', kind: 'connect' });
    expect(problems[0]?.field).toBe('input.id');
  });

  it('an unknown alias suggests a $-prefixed replacement', () => {
    const problems = check(
      {
        kind: 'Workflow',
        metadata: { name: 'x', title: 'X', noun: 'a', verb: 'b', owner: 'team' },
        workflow: {
          do: [
            { getVm: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get', input: { id: '1' } }, output: { as: 'vm' } } },
            { tell: { notify: { channel: '#ops', message: '{{ $vmm.name }}' } } },
          ],
        },
      },
      ctx,
    );
    expect(problems.map((p) => p.code)).toEqual(['expr.unknownReference']);
    expect(problems[0]?.fix).toEqual({ label: 'Did you mean vm?', kind: 'replace', value: '$vm' });
  });

  it('a plain secret offers a Vault reference', () => {
    const problems = checkYaml(fixture('plain-secret.yaml'), ctx);
    expect(problems.map((p) => p.fix?.value)).toEqual(['vault:ping/credential', 'vault:play/password']);
    expect(problems.map((p) => p.field)).toEqual(['credential', 'extraVars.password']);
  });
});

describe('catalog', () => {
  it('without a catalog unknown names are not reported', () => {
    const yaml = `apiVersion: iaf.platform/v1
kind: Workflow
metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
workflow:
  do:
    - getVm:
        call: provider
        with: { provider: whatever, action: anything, input: { id: "{{ $input.vmId }}" } }
    - child:
        run: { workflow: nope }
    - useIt:
        use: { type: nothing, id: x }
`;
    expect(checkYaml(yaml, EMPTY_CONTEXT)).toEqual([]);
    expect(checkYaml(yaml, ctx).map((p) => p.code)).toEqual(['step.unknownProvider', 'run.unknownWorkflow', 'resource.unknownType']);
  });

  it('a broken YAML file is one yaml.invalid problem', () => {
    const problems = checkYaml(fixture('invalid-yaml.yaml'), ctx);
    expect(problems).toHaveLength(1);
    expect(problems[0]?.code).toBe('yaml.invalid');
    expect(problems[0]?.message).toMatch(/^YAML syntax: /);
  });

  it('a structural error is one yaml.invalid problem with the parser message', () => {
    const problems = check({ kind: 'Workflow', metadata: { name: 'x' }, workflow: { do: [{ a: { call: 'provider', run: {} } }] } }, ctx);
    expect(problems).toEqual([{ level: 'error', code: 'yaml.invalid', message: "Step 'a' has two step keys: call and run" }]);
  });

  it('an empty document is missing its name', () => {
    expect(checkYaml('', ctx)).toEqual([{ level: 'error', code: 'yaml.invalid', message: 'metadata.name is required' }]);
    expect(check(null, ctx)).toEqual([{ level: 'error', code: 'yaml.invalid', message: 'A workflow file is a YAML mapping' }]);
  });
});

describe('rules beyond the fixtures', () => {
  const base = (steps: unknown[], extra: Record<string, unknown> = {}) => ({
    apiVersion: 'iaf.platform/v1',
    kind: 'Workflow',
    metadata: { name: 'vm-info', title: 'VM info', noun: 'vm', verb: 'read', owner: 'team-compute', version: '1.0.0' },
    ...extra,
    workflow: { do: steps },
  });
  const codes = (doc: unknown) => check(doc, ctx).map((p) => `${p.level} ${p.code}`);

  it('judges the metadata', () => {
    expect(codes({ kind: 'Workflow', metadata: { name: 'ok' }, workflow: { do: [{ a: { set: { x: 1 } } }] } })).toEqual([
      'warning metadata.title',
      'error metadata.nounVerb',
      'error metadata.owner',
    ]);
    expect(codes({ kind: 'Workflow', metadata: { name: 'ok', title: 'T', owner: 'o' }, api: { path: '/api/v1/things' }, workflow: { do: [{ a: { set: { x: 1 } } }] } })).toEqual([
      'warning metadata.nounVerb',
    ]);
  });

  it('judges the api block', () => {
    const problems = check(
      base([{ a: { set: { x: 1 } } }], {
        api: {
          method: 'FETCH',
          path: '/things',
          input: { type: 'object', required: ['nope'], properties: { image: { type: 'string', 'x-resource': { type: 'imge', orCreateWith: 'build-images' } }, bare: { 'x-resource': {} } } },
          batch: { max: 0, rollout: { waves: [10, 20, 30], maxParallel: 0, stopIfFailedPercent: 101 } },
        },
      }),
      ctx,
    );
    expect(problems.map((p) => `${p.code} @${p.field ?? ''}`)).toEqual([
      'api.method @api.method',
      'api.path @api.path',
      'api.inputRequired @api.input.required',
      'resource.unknownType @api.input.image',
      'run.unknownWorkflow @api.input.image',
      'api.resourceType @api.input.bare',
      'batch.max @api.batch.max',
      'batch.maxParallel @api.batch.rollout.maxParallel',
      'batch.stop @api.batch.rollout.stopIfFailedPercent',
    ]);
    expect(problems[3]?.fix?.value).toBe('image');
    expect(problems[4]?.fix?.value).toBe('build-vm-image');
    expect(codes(base([{ a: { set: { x: 1 } } }], { api: { input: 'nope' } }))).toEqual(['error api.input']);
    expect(codes(base([{ a: { set: { x: 1 } } }], { api: { input: { type: 'array' } } }))).toEqual(['error api.input']);
  });

  it('judges policy, rollout and triggers', () => {
    expect(codes(base([{ a: { set: { x: 1 } } }], { policy: { approval: { required: true, timeout: 'soon' } } }))).toEqual([
      'error approval.group',
      'error duration.format',
    ]);
    expect(codes(base([{ a: { set: { x: 1 } } }], { rollout: { strategy: 'big-bang' } }))).toEqual(['error rollout.strategy']);
    expect(codes(base([{ a: { set: { x: 1 } } }], { rollout: { strategy: 'canary', stages: [10, 50] } }))).toEqual(['error rollout.stages']);
    expect(codes(base([{ a: { set: { x: 1 } } }], { rollout: { strategy: 'canary', stages: [10, 150] } }))).toEqual(['error rollout.stages']);
    expect(codes(base([{ a: { set: { x: 1 } } }], { rollout: { strategy: 'all', stages: [10, 50] } }))).toEqual([]);
    expect(
      codes(
        base([{ a: { set: { x: 1 } } }], {
          triggers: [{ alert: { input: { vmId: '{{ $alert.labels.vm }}' } } }, { event: { topic: 'vm.created' } }, { schedule: { cron: '* * * * *' } }, { type: 'webhook' }, { alert: { match: { source: 'x' }, input: { id: '{{ $json.id }}' } } }],
        }),
      ),
    ).toEqual(['error trigger.match', 'warning trigger.schedule', 'error trigger.type', 'error expr.noPredecessor']);
  });

  it('judges each step kind', () => {
    const steps = [
      { ok: { set: { x: 1 } } },
      { h: { call: 'http', with: { method: 'FETCH', url: 'example.com' } } },
      { d: { call: 'http', with: { method: 'delete', url: 'https://example.com' } } },
      { i: { call: 'idp', with: {} } },
      { act: { call: 'activity', with: { input: {} } } },
      { r: { run: { workflow: 'vm-info', version: 'latest-ish', input: 'x' } } },
      { ap: { approval: { timeout: 'later' } } },
      { w: { wait: { until: '{{ $vars.x == 1 }}' } } },
      { w2: { wait: 'five minutes' } },
      { w3: { wait: {} } },
      { s: { set: {} } },
      { ra: { raise: {} } },
      { u: { use: { type: 'vm' } } },
      { re: { register: { type: 'vm', attributes: 'x' } } },
      { n: { notify: {} } },
      { sc: { script: { language: 'ruby' } } },
      { t: { call: 'terraform', with: { module: { source: 'git::x' }, action: 'destroy' } } },
      { t2: { call: 'terraform', with: { action: 'build' } } },
      { an: { call: 'ansible', with: { playbook: 'noslash' } } },
      { an2: { call: 'ansible', with: { playbook: { git: { repo: 'r' }, upload: {} }, extraVars: 'x' } } },
      { an3: { call: 'ansible', with: { playbook: {} } } },
      { an4: { call: 'ansible', with: { playbook: 7 } } },
      { an5: { call: 'ansible', with: {} } },
      { p: { call: 'provider', with: { input: 'x' } } },
      { p2: { call: 'provider', with: { provider: 'vsphere', action: 'vm.get' } } },
      { sw: { switch: [] } },
      { sw2: { switch: [{ case: { when: '', then: 'end' } }, { case: { when: 'plain', then: 'end' } }, { case: { when: '{{ 1 }}' } }, { default: 'end' }, { default: 'end' }] } },
      { f: { fork: { branches: [] } } },
      { f2: { fork: { branches: [[]] } } },
      { fo: { for: { each: 'not valid', in: '{{ [1] }}' } } },
      { tr: { try: {} } },
      { rt: { set: { y: 1 }, retry: { maxAttempts: 0, backoff: 'random' }, timeout: 'a while' } },
      { Bad_Id: { set: { z: 1 }, output: { as: 'json' } } },
      { al: { set: { z: 2 }, output: { as: 'not valid' } } },
      { as: { set: { z: 3 }, assert: 'yes' } },
    ];
    expect(codes(base(steps))).toEqual([
      'warning step.idFormat',
      'error api.method',
      'error http.url',
      'error policy.approvalRequired',
      'error step.missing',
      'error step.missing',
      'error step.missing',
      'error run.self',
      'error run.version',
      'error step.invalidJson',
      'error approval.group',
      'warning approval.message',
      'error duration.format',
      'error duration.format',
      'error wait.duration',
      'error set.empty',
      'error step.missing',
      'error step.missing',
      'error step.invalidJson',
      'error step.missing',
      'error step.missing',
      'error script.language',
      'error step.missing',
      'warning terraform.unpinned',
      'error terraform.module',
      'error terraform.action',
      'error ansible.source',
      'error ansible.git',
      'error ansible.git',
      'warning ansible.unpinned',
      'error ansible.upload',
      'error step.invalidJson',
      'error ansible.source',
      'error ansible.source',
      'error ansible.source',
      'error step.missingProvider',
      'error step.missingAction',
      'error step.invalidJson',
      'error step.missingInput',
      'error switch.empty',
      'error switch.when',
      'warning switch.when',
      'error switch.target',
      'error switch.defaults',
      'error fork.empty',
      'error fork.emptyBranch',
      'error for.each',
      'error for.empty',
      'error try.empty',
      'error duration.format',
      'error retry.attempts',
      'error retry.backoff',
      'error output.alias',
      'error output.alias',
      'warning assert.literal',
    ]);
  });

  it('keeps the scope of nested branches, loops and catch blocks', () => {
    const doc = base([
      { first: { set: { a: 1 } } },
      {
        pick: {
          switch: [{ case: { when: '{{ $vars.a == 1 }}', do: [{ inner: { set: { b: 2 }, output: { as: 'innerOut' } } }] } }, { default: { do: [{ other: { set: { c: 3 } } }] } }],
        },
      },
      { after: { notify: { channel: '#x', message: '{{ $vars.b }} {{ $vars.c }} {{ $innerOut.x }} {{ $(\'inner\').json.x }}' } } },
      {
        loop: {
          for: { each: 'item', in: '{{ $json.items }}', do: [{ tell: { notify: { channel: '#x', message: '{{ $item.id }}' } } }] },
        },
      },
      { afterLoop: { notify: { channel: '#x', message: '{{ $item.id }}' } } },
      {
        guarded: {
          try: {
            do: [{ risky: { set: { d: 4 } } }],
            catch: { as: 'oops', do: [{ handle: { notify: { channel: '#x', message: '{{ $oops.message }} {{ $error.message }} {{ $vars.d }}' } } }] },
          },
        },
      },
      { afterTry: { notify: { channel: '#x', message: '{{ $oops.message }}' } } },
    ]);
    const problems = check(doc, ctx);
    expect(problems.map((p) => `${p.code} @${p.step ?? ''}`)).toEqual(['expr.unknownReference @afterLoop', 'expr.unknownReference @afterTry']);
  });

  it('lets compensation delete without an approval and see the step output', () => {
    const doc = base([
      {
        create: {
          call: 'provider',
          with: { provider: 'vsphere', action: 'vm.create', input: { name: 'x', templateId: 't' } },
          output: { as: 'vm' },
          compensate: [{ undo: { call: 'provider', with: { provider: 'vsphere', action: 'vm.delete', input: { id: '{{ $vm.id }} {{ $json.id }}' } } } }],
        },
      },
    ]);
    expect(codes(doc)).toEqual([]);
  });

  it('judges the catch flow and the outputs with the final scope', () => {
    const doc = {
      ...base([{ one: { set: { a: 1 }, output: { as: 'first' } } }]),
      workflow: {
        do: [{ one: { set: { a: 1 }, output: { as: 'first' } } }],
        catch: { do: [{ tell: { notify: { channel: '#x', message: '{{ $error.message }} {{ $first.a }} {{ $json.x }}' } } }] },
        outputs: { a: '{{ $first.a }}', b: '{{ $(\'nope\').json.x }}', c: '{{ $error.x }}' },
      },
    };
    expect(check(doc, ctx).map((p) => `${p.code} @${p.field ?? ''}`)).toEqual(['expr.unknownStep @workflow.outputs.b', 'expr.errorOutsideCatch @workflow.outputs.c']);
  });

  it('reports a plain-text secret only outside Vault references and templates', () => {
    const doc = base([
      { h: { call: 'http', with: { url: 'https://x', credential: 'vault:a/b', headers: { apiKey: 'abc', 'X-Api-Key': 'no match', Token: '{{ $input.t }}', empty: '', list: [{ password: 'p' }] } } } },
    ]);
    expect(check(doc, ctx).map((p) => `${p.code} @${p.field ?? ''} -> ${p.fix?.value ?? ''}`)).toEqual([
      'credential.plain @headers.apiKey -> vault:h/apiKey',
      'credential.plain @headers.list[0].password -> vault:h/password',
    ]);
  });
});
