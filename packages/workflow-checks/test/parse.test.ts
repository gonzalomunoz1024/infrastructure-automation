/** The structural errors and the shapes of parse.ts, as WorkflowYaml.java reads a file. */
import { describe, expect, it } from 'vitest';
import { allSteps, flatten, kindOf, parseDocument, WorkflowParseError, withNode, withText } from '../src/index.ts';

const minimal = (workflow: unknown) => ({ apiVersion: 'iaf.platform/v1', kind: 'Workflow', metadata: { name: 'x' }, workflow });
const steps = (list: unknown) => parseDocument(minimal({ do: list })).workflow.steps;
const fails = (doc: unknown, message: string) => {
  expect(() => parseDocument(doc)).toThrow(WorkflowParseError);
  expect(() => parseDocument(doc)).toThrow(message);
};

describe('structural errors', () => {
  it('the document', () => {
    fails(null, 'A workflow file is a YAML mapping');
    fails('text', 'A workflow file is a YAML mapping');
    fails([], 'A workflow file is a YAML mapping');
    fails({ kind: 'Provider', metadata: { name: 'x' } }, 'kind must be Workflow, was Provider');
    fails({ kind: 'Workflow' }, 'metadata.name is required');
    fails({ kind: 'Workflow', metadata: { name: '  ' } }, 'metadata.name is required');
    fails({ kind: 'Workflow', metadata: 'oops' }, 'metadata.name is required');
  });

  it('the step lists', () => {
    fails(minimal({ do: { a: {} } }), 'workflow.do must be a list of steps');
    fails(minimal({ do: ['a'] }), "Each entry of workflow.do is '- <stepId>: { ... }'");
    fails(minimal({ do: [{ a: {}, b: {} }] }), "Each entry of workflow.do is '- <stepId>: { ... }'");
    fails(minimal({ do: [{ a: 'text' }] }), "Step 'a' must be a mapping");
    fails(minimal({ do: [], catch: { do: 7 } }), 'workflow.catch.do must be a list of steps');
    fails(minimal({ do: [{ a: { set: { x: 1 }, compensate: 'no' } }] }), 'a.compensate must be a list of steps');
    fails(minimal({ do: [{ a: { for: { each: 'i', in: '{{ [] }}', do: 'no' } } }] }), 'a.do must be a list of steps');
    fails(minimal({ do: [{ a: { try: { do: [], catch: { do: 'no' } } } }] }), 'a.catch.do must be a list of steps');
    fails(minimal({ do: [{ a: { switch: [{ case: { when: 'x', do: 'no' } }] } }] }), 'a.switch[0].do must be a list of steps');
    fails(minimal({ do: [{ a: { switch: [{ default: { do: 'no' } }] } }] }), 'a.switch[default].do must be a list of steps');
    fails(minimal({ do: [{ a: { fork: { branches: ['no'] } } }] }), 'a.branches[0] must be a list of steps');
  });

  it('the step keys', () => {
    fails(minimal({ do: [{ a: { call: 'provider', run: {} } }] }), "Step 'a' has two step keys: call and run");
    fails(minimal({ do: [{ a: { set: { x: 1 }, notify: {} } }] }), "Step 'a' has two step keys: set and notify");
    fails(minimal({ do: [{ a: { name: 'A', with: {} } }] }), "Step 'a' has no step key (call, run, approval, switch, fork, for, try, wait, set, raise, use, register, notify, script)");
    fails(minimal({ do: [{ a: { call: 'smtp' } }] }), "Step 'a': call must be one of [provider, http, ansible, terraform, idp, activity], was 'smtp'");
    fails(minimal({ do: [{ a: { switch: { case: {} } } }] }), "Step 'a': switch must be a list of cases");
    fails(minimal({ do: [{ a: { switch: [{ when: 'x' }] } }] }), "Step 'a': each switch entry is 'case: { when, then | do }' or 'default: ...'");
    fails(minimal({ do: [{ a: { fork: { branches: 'no' } } }] }), "Step 'a': fork needs branches: [[...], [...]]");
    fails(minimal({ do: [{ a: { fork: [] } }] }), "Step 'a': fork needs branches: [[...], [...]]");
    fails(minimal({ do: [{ a: { loop: {} } }] }), "Step 'a': unknown key 'loop'");
  });
});

describe('shapes', () => {
  it('reads the definition level', () => {
    const d = parseDocument({
      kind: 'Workflow',
      metadata: { name: 'vm-restart', title: 'VM restart', noun: 'vm', verb: 'restart', owner: 'team', tags: 'a, b', version: '1.2.0' },
      api: { method: 'POST', path: '/api/v1/vms', input: { type: 'object' }, response: { status: 200 }, preview: true, batch: { max: 10, rollout: { waves: [1, 2] } } },
      mcp: { expose: true, description: 'd' },
      policy: { approval: { group: 'g', timeout: '4h' }, rbac: ['ops'], change: { required: true } },
      triggers: [{ alert: { match: {} } }, { type: 'event', topic: 't' }, { bad: 1, worse: 2 }, 'nope'],
      rollout: { strategy: 'all' },
      workflow: { do: [], catch: { do: [{ tell: { notify: { channel: '#x', message: 'm' } } }] }, outputs: { a: '{{ 1 }}', n: 2 } },
    });
    expect(d.apiVersion).toBe('iaf.platform/v1');
    expect(d.metadata.tags).toEqual(['a', 'b']);
    expect(d.api).toEqual({ method: 'POST', path: '/api/v1/vms', input: { type: 'object' }, responseStatus: 200, preview: true, batch: { max: 10, rollout: { waves: [1, 2], maxParallel: 10, stopIfFailedPercent: 5 } } });
    expect(d.mcp).toEqual({ expose: true, description: 'd' });
    expect(d.policy).toEqual({ approval: { required: true, group: 'g', timeout: '4h' }, rbac: ['ops'], changeRequired: true });
    expect(d.triggers).toEqual([
      { type: 'alert', spec: { match: {} } },
      { type: 'event', spec: { type: 'event', topic: 't' } },
    ]);
    expect(d.rollout).toEqual({ strategy: 'all', stages: [10, 50, 100] });
    expect(d.workflow.steps).toEqual([]);
    expect(d.workflow.catchFlow?.steps.map((s) => s.id)).toEqual(['tell']);
    expect(d.workflow.outputs).toEqual({ a: '{{ 1 }}', n: '2' });
    expect(kindOf({ kind: 'Workflow' })).toBe('Workflow');
    expect(kindOf({})).toBeNull();
    expect(kindOf(null)).toBeNull();
  });

  it('defaults are what the Java reader applies', () => {
    const d = parseDocument({ kind: 'Workflow', metadata: { name: 'x' }, api: {}, rollout: {}, policy: {}, mcp: {} });
    expect(d.api).toEqual({ method: null, path: null, input: undefined, responseStatus: 202, preview: false, batch: null });
    expect(d.rollout).toEqual({ strategy: 'canary', stages: [10, 50, 100] });
    expect(d.policy).toEqual({ approval: null, rbac: [], changeRequired: false });
    expect(d.mcp).toEqual({ expose: false, description: null });
    expect(d.workflow).toEqual({ steps: [], catchFlow: null, outputs: null });
  });

  it('reads call, run and the simple step keys into with', () => {
    const [call, run, approval, wait, wait2] = steps([
      { call: { call: 'provider', with: { provider: 'p' }, name: 'Call', output: { as: 'out' }, assert: '{{ 1 }}', retry: { maxAttempts: '3' }, timeout: '2h', pauseOnFailure: false, attentionTimeout: 'PT1H', position: { x: 10, y: '20' }, notes: 'n', compensate: [] } },
      { run: { run: { workflow: 'w', input: {} }, with: { version: '^1', workflow: 'ignored' } } },
      { approval: { call: 'approval', with: { group: 'g' } } },
      { wait: { wait: 'PT5M' } },
      { wait2: { wait: { until: '{{ true }}' }, with: { extra: 1 } } },
    ]);
    expect(call).toEqual({
      id: 'call',
      name: 'Call',
      type: 'provider',
      with: { provider: 'p' },
      output: { as: 'out' },
      assert: '{{ 1 }}',
      retry: { maxAttempts: 3, backoff: 'none' },
      timeout: '2h',
      pauseOnFailure: false,
      attentionTimeout: 'PT1H',
      position: { x: 10, y: 20 },
      notes: 'n',
      branches: null,
      cases: null,
      children: null,
      catchBlock: null,
      compensate: [],
    });
    expect(run?.type).toBe('run');
    expect(run?.attentionTimeout).toBeNull();
    expect(run?.with).toEqual({ version: '^1', workflow: 'w', input: {} });
    expect(approval?.type).toBe('approval');
    expect(wait?.with).toEqual({ duration: 'PT5M' });
    expect(wait2?.with).toEqual({ extra: 1, until: '{{ true }}' });
    expect(withText(call!, 'provider')).toBe('p');
    expect(withText(call!, 'nope')).toBeNull();
    expect(withNode(call!, 'nope')).toBeUndefined();
  });

  it('reads switch, fork, for and try', () => {
    const [sw, fork, loop, guarded] = steps([
      {
        sw: {
          switch: [{ case: { when: '{{ 1 }}', then: 'x' } }, { case: { when: '{{ 2 }}', do: [{ a: { set: { x: 1 } } }] } }, { default: 'end' }, { default: { then: 'y' } }, { default: { do: [{ b: { set: { y: 1 } } }] } }],
        },
      },
      { fork: { fork: { branches: [[{ c: { set: { z: 1 } } }], []] } } },
      { loop: { for: { each: 'item', in: '{{ $json.items }}', do: [{ d: { set: { w: 1 } } }] } } },
      { guarded: { try: { do: [{ e: { set: { v: 1 } } }], catch: { do: [{ f: { set: { u: 1 } } }] } } } },
    ]);
    expect(sw?.cases).toEqual([
      { when: '{{ 1 }}', then: 'x', isDefault: false, steps: null },
      { when: '{{ 2 }}', then: null, isDefault: false, steps: [expect.objectContaining({ id: 'a' })] },
      { when: null, then: 'end', isDefault: true, steps: null },
      { when: null, then: 'y', isDefault: true, steps: null },
      { when: null, then: null, isDefault: true, steps: [expect.objectContaining({ id: 'b' })] },
    ]);
    expect(fork?.branches?.map((b) => b.map((s) => s.id))).toEqual([['c'], []]);
    expect(loop?.with).toEqual({ each: 'item', in: '{{ $json.items }}' });
    expect(loop?.children?.map((s) => s.id)).toEqual(['d']);
    expect(guarded?.children?.map((s) => s.id)).toEqual(['e']);
    expect(guarded?.catchBlock).toEqual({ as: 'error', steps: [expect.objectContaining({ id: 'f' })] });
    expect(steps([{ t: { try: { do: [] } } }])[0]?.catchBlock).toBeNull();
    expect(steps([{ l: { for: {} } }])[0]?.with).toEqual({ each: null, in: null });
  });

  it('flattens every nesting level in document order', () => {
    const d = parseDocument(
      minimal({
        do: [
          { sw: { switch: [{ case: { when: '{{ 1 }}', do: [{ a: { set: { x: 1 } } }] } }] } },
          { fork: { fork: { branches: [[{ b: { set: { x: 1 } } }], [{ c: { set: { x: 1 }, compensate: [{ undo: { set: { x: 0 } } }] } }]] } } },
          { loop: { for: { each: 'i', in: '{{ [] }}', do: [{ d: { try: { do: [{ e: { set: { x: 1 } } }], catch: { do: [{ f: { set: { x: 1 } } }] } } } }] } } },
        ],
        catch: { do: [{ g: { set: { x: 1 } } }] },
      }),
    );
    expect(allSteps(d).map((s) => s.id)).toEqual(['sw', 'a', 'fork', 'b', 'c', 'undo', 'loop', 'd', 'e', 'f', 'g']);
    expect(flatten(d.workflow.steps[2]!).map((s) => s.id)).toEqual(['loop', 'd', 'e', 'f']);
  });
});
