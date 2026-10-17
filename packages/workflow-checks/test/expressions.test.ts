/** The expression engine, as ExpressionEngineTest.java and ExpressionEngineLambdaTest.java judge the Java one. */
import { describe, expect, it } from 'vitest';
import { evaluate, ExpressionError, isTemplate, references, referencesIn, resolve, syntaxError, test as judge, type ExpressionContext, type Reference } from '../src/index.ts';

const json = {
  exists: true,
  vm: { id: 'vm-30c7', name: 'web-01', powerState: 'poweredOn', cpus: 4 },
  items: [],
};
const ctx: ExpressionContext = {
  input: { vmId: 'vm-30c7', count: 3, tags: ['web', 'prod'] },
  json,
  steps: { 'Check VM exists': json, checkVm: json },
  aliases: { vm: json.vm },
  vars: { retries: 2 },
  job: { id: 'job_1', requester: 'gonzalo' },
  env: { name: 'dev' },
  error: { message: 'boom' },
};

describe('templates', () => {
  it('plain text is not evaluated', () => {
    expect(evaluate('hello', ctx)).toBe('hello');
    expect(isTemplate('hello')).toBe(false);
    expect(evaluate(null, ctx)).toBeNull();
  });

  it('a single expression keeps the type', () => {
    expect(evaluate('{{ $json.vm.cpus }}', ctx)).toBe(4);
    expect(evaluate('{{ $json.exists }}', ctx)).toBe(true);
    expect(evaluate('{{ $json.vm }}', ctx)).toEqual(json.vm);
  });

  it('a mixed template yields text', () => {
    expect(evaluate('Delete {{ $json.vm.name }}?', ctx)).toBe('Delete web-01?');
    expect(evaluate('{{ $input.vmId }} has {{ $json.vm.cpus }} cpus', ctx)).toBe('vm-30c7 has 4 cpus');
    expect(evaluate('{{ $json.vm }}!', ctx)).toBe('{"id":"vm-30c7","name":"web-01","powerState":"poweredOn","cpus":4}!');
    expect(() => evaluate('x {{ $json.vm', ctx)).toThrow(/Unclosed \{\{/);
  });

  it('reads named steps and aliases', () => {
    expect(evaluate("{{ $('Check VM exists').json.vm.id }}", ctx)).toBe('vm-30c7');
    expect(evaluate("{{ $('checkVm').item.json.vm.name }}", ctx)).toBe('web-01');
    expect(evaluate('{{ $vm.name }}', ctx)).toBe('web-01');
  });

  it('reads the context roots', () => {
    expect(evaluate('{{ $job.id }}', ctx)).toBe('job_1');
    expect(evaluate('{{ $env.name }}', ctx)).toBe('dev');
    expect(evaluate('{{ $vars.retries }}', ctx)).toBe(2);
    expect(evaluate('{{ $error.message }}', ctx)).toBe('boom');
  });
});

describe('operators', () => {
  it('compares, combines and computes', () => {
    expect(judge('{{ $json.items.length == 0 }}', ctx)).toBe(true);
    expect(judge('{{ $json.vm.cpus > 2 && $json.exists }}', ctx)).toBe(true);
    expect(judge('{{ !$json.exists || $json.vm.cpus >= 4 }}', ctx)).toBe(true);
    expect(judge("{{ $json.vm.powerState == 'poweredOn' }}", ctx)).toBe(true);
    expect(judge('{{ $json.vm.powerState != "poweredOn" }}', ctx)).toBe(false);
    expect(evaluate('{{ $json.vm.cpus * 2 + 1 }}', ctx)).toBe(9);
    expect(evaluate("{{ $json.missing ?? 'default' }}", ctx)).toBe('default');
    expect(evaluate("{{ $json.exists ? 'yes' : 'no' }}", ctx)).toBe('yes');
    expect(evaluate('{{ -$json.vm.cpus }}', ctx)).toBe(-4);
    expect(evaluate('{{ 7 % 3 }}', ctx)).toBe(1);
    expect(evaluate('{{ 7 / 2 }}', ctx)).toBe(3.5);
    expect(evaluate("{{ 'a' + 1 }}", ctx)).toBe('a1');
    expect(evaluate("{{ 'b' < 'a' }}", ctx)).toBe(false);
    expect(evaluate('{{ null == null }}', ctx)).toBe(true);
    expect(evaluate('{{ 1 == "1" }}', ctx)).toBe(true);
    expect(evaluate('{{ $json.exists && $json.vm.cpus }}', ctx)).toBe(4);
    expect(evaluate('{{ $json.nope || "fallback" }}', ctx)).toBe('fallback');
    expect(evaluate('{{ 2 <= 2 && 3 >= 4 }}', ctx)).toBe(false);
    expect(() => evaluate('{{ $json.nope > 1 }}', ctx)).toThrow(/Cannot compare null/);
  });

  it('ternaries nest and ?? binds looser than ||', () => {
    expect(evaluate("{{ $json.nope ? 'a' : $json.exists ? 'b' : 'c' }}", ctx)).toBe('b');
    expect(evaluate("{{ $json.nope ?? $json.nope2 ?? 'last' }}", ctx)).toBe('last');
    expect(evaluate('{{ ($json.vm.cpus + 1) * 2 }}', ctx)).toBe(10);
  });
});

describe('methods and indexing', () => {
  it('string and array methods', () => {
    expect(evaluate('{{ $json.vm.name.toUpperCase() }}', ctx)).toBe('WEB-01');
    expect(evaluate('{{ $input.tags[1] }}', ctx)).toBe('prod');
    expect(evaluate("{{ $input.tags.join('/') }}", ctx)).toBe('web/prod');
    expect(judge("{{ $input.tags.includes('web') }}", ctx)).toBe(true);
    expect(evaluate("{{ $json.vm.name.split('-').first() }}", ctx)).toBe('web');
    expect(evaluate("{{ $json.vm['id'] }}", ctx)).toBe('vm-30c7');
    expect((evaluate('{{ { id: $json.vm.id, n: 1 } }}', ctx) as { id: string }).id).toBe('vm-30c7');
    expect(evaluate('{{ [1, $json.vm.cpus] }}', ctx)).toEqual([1, 4]);
    expect(evaluate("{{ ' x '.trim() + 1 }}", ctx)).toBe('x1');
    expect(evaluate('{{ $json.vm.cpus.toString() }}', ctx)).toBe('4');
    expect(evaluate("{{ '12'.toNumber() + 1 }}", ctx)).toBe(13);
    expect(evaluate("{{ 'abc'.toNumber() }}", ctx)).toBeNull();
    expect(evaluate("{{ 'web-01'.startsWith('web') && 'web-01'.endsWith('01') }}", ctx)).toBe(true);
    expect(evaluate("{{ 'a,b'.contains(',') }}", ctx)).toBe(true);
    expect(evaluate("{{ 'a-b-c'.replace('-', '+') }}", ctx)).toBe('a+b+c');
    expect(evaluate("{{ 'abcdef'.substring(1, 3) }}", ctx)).toBe('bc');
    expect(evaluate("{{ 'abcdef'.slice(-2) }}", ctx)).toBe('ef');
    expect(evaluate('{{ [1, 2, 3, 4].slice(1, -1) }}', ctx)).toEqual([2, 3]);
    expect(evaluate("{{ 'ab'.concat('c', 'd') }}", ctx)).toBe('abcd');
    expect(evaluate("{{ 'abc'.indexOf('c') }}", ctx)).toBe(2);
    expect(evaluate("{{ ['a', 'b'].indexOf('b') }}", ctx)).toBe(1);
    expect(evaluate("{{ ['a', 'b'].indexOf('z') }}", ctx)).toBe(-1);
    expect(evaluate('{{ (1.005).toFixed(1) }}', ctx)).toBe('1.0');
    expect(evaluate('{{ $input.tags.last() }}', ctx)).toBe('prod');
    expect(evaluate('{{ $json.vm.keys() }}', ctx)).toEqual(['id', 'name', 'powerState', 'cpus']);
    expect(evaluate('{{ $json.items.isEmpty() }}', ctx)).toBe(true);
    expect(evaluate('{{ $input.tags.isNotEmpty() }}', ctx)).toBe(true);
    expect(evaluate("{{ ''.isEmpty() }}", ctx)).toBe(true);
    expect(evaluate('{{ [3, 1, 2].sort() }}', ctx)).toEqual([1, 2, 3]);
    expect(evaluate('{{ [3, 1, 2].reverse() }}', ctx)).toEqual([2, 1, 3]);
    expect(evaluate('{{ [1, 1, 2].unique() }}', ctx)).toEqual([1, 2]);
    expect(evaluate('{{ [1, 5, 2].min() }}', ctx)).toBe(1);
    expect(evaluate('{{ [1, 5, 2].max() }}', ctx)).toBe(5);
    expect(evaluate('{{ $json.vm.length }}', ctx)).toBe(4);
    expect(evaluate("{{ 'abc'.length }}", ctx)).toBe(3);
    expect(evaluate('{{ $json.vm.cpus.length }}', ctx)).toBeNull();
    expect(() => evaluate('{{ $json.vm.name.explode() }}', ctx)).toThrow(/Unknown method \.explode\(\)/);
  });

  it('missing fields are null, not errors', () => {
    expect(evaluate('{{ $json.vm.nope.deeper }}', ctx)).toBeNull();
    expect(evaluate('x={{ $json.nope }}', ctx)).toBe('x=');
    expect(evaluate('{{ $input.tags[7] }}', ctx)).toBeNull();
    expect(evaluate('{{ $json.vm.cpus.nope }}', ctx)).toBeNull();
  });

  it('unknown steps, references and bare identifiers fail', () => {
    expect(() => evaluate("{{ $('Nope').json.a }}", ctx)).toThrow(ExpressionError);
    expect(() => evaluate("{{ $('Nope').json.a }}", ctx)).toThrow(/Unknown step/);
    expect(() => evaluate('{{ $nope.a }}', ctx)).toThrow(/Unknown reference/);
    expect(() => evaluate('{{ vmId }}', ctx)).toThrow(/start with \$/);
  });
});

describe('syntax', () => {
  it('reports syntax errors', () => {
    expect(syntaxError('{{ $json.vm. }}')).toContain('Expected a name');
    expect(syntaxError('{{ $json.vm }')).toContain('Unclosed');
    expect(syntaxError('{{ ($json.a }}')).toContain("Expected ')'");
    expect(syntaxError('{{ $json.a }}')).toBeNull();
    expect(syntaxError('plain')).toBeNull();
    expect(syntaxError('{{ $input.vmId == }}')).toContain('Unexpected end of expression');
    expect(syntaxError('{{ $json.a ) }}')).toContain("Unexpected ')'");
    expect(syntaxError("{{ 'open }}")).toContain('Unterminated string');
    expect(syntaxError('{{ [1, 2 }}')).toContain("Expected ']'");
    expect(syntaxError('{{ { a: 1 }}')).toContain("Expected '}'");
    expect(syntaxError('{{ 1.2.3 }}')).toContain('Invalid number');
    expect(syntaxError('{{ $json.a }} and {{ $json.b. }}')).toContain('Expected a name');
  });

  it('collects references for the checks', () => {
    const refs = references("{{ $json.vm.id }} {{ $('Check VM exists').json.vm.name }} {{ $input.vmId }} {{ $json.items.length == 0 }}");
    expect(refs).toEqual<Reference[]>([
      { kind: 'json', path: 'vm.id' },
      { kind: 'step', step: 'Check VM exists', path: 'vm.name' },
      { kind: 'input', path: 'vmId' },
      { kind: 'json', path: 'items' },
    ]);
    expect(references('{{ $vm.name }}')).toEqual([{ kind: 'alias', step: 'vm', path: 'name' }]);
    expect(references('no template')).toEqual([]);
    expect(references('{{ $json }}')).toEqual([]);
    expect(references("{{ $('x').json }}")).toEqual([{ kind: 'step', step: 'x', path: '' }]);
    expect(references("{{ $('x').item.json.a.length }}")).toEqual([{ kind: 'step', step: 'x', path: 'a' }]);
    expect(references('{{ $vars.a[$input.i].b }}')).toEqual([
      { kind: 'vars', path: 'a' },
      { kind: 'input', path: 'i' },
    ]);
    expect(references('{{ $job.id + $env.name + $error.message }}')).toEqual([
      { kind: 'job', path: 'id' },
      { kind: 'env', path: 'name' },
      { kind: 'error', path: 'message' },
    ]);
    expect(references('{{ $json.a.toUpperCase() ?? $range(1, $input.n) }}')).toEqual([
      { kind: 'json', path: 'a' },
      { kind: 'input', path: 'n' },
    ]);
    expect(references('{{ broken( }} {{ $json.ok }}')).toEqual([{ kind: 'json', path: 'ok' }]);
  });

  it('collects references inside values', () => {
    expect(referencesIn({ id: '{{ $json.vm.id }}', nested: { n: ['{{ $input.vmId }}'] }, num: 3 })).toEqual([
      { kind: 'json', path: 'vm.id' },
      { kind: 'input', path: 'vmId' },
    ]);
  });

  it('resolves templates inside values', () => {
    const out = resolve({ id: '{{ $json.vm.id }}', nested: { n: '{{ $json.vm.cpus }}' }, list: ['{{ $input.vmId }}'], keep: 1 }, ctx);
    expect(out).toEqual({ id: 'vm-30c7', nested: { n: 4 }, list: ['vm-30c7'], keep: 1 });
  });
});

describe('functions', () => {
  const lambdaCtx: ExpressionContext = {
    input: { vmCount: 3, os: 'ubuntu', cve: 'CVE-2026-1234' },
    json: {
      items: [
        { outputs: { vmId: 'vm-1' }, cves: ['CVE-2026-1234'] },
        { outputs: { vmId: 'vm-2' }, cves: [] },
      ],
    },
    job: { startedAt: '2026-10-17T10:00:00Z' },
  };

  it('maps with an arrow function', () => {
    expect(evaluate('{{ $json.items.map(v => v.outputs.vmId) }}', lambdaCtx)).toEqual(['vm-1', 'vm-2']);
  });

  it('some, filter, find, every and findIndex', () => {
    expect(judge('{{ $json.items.some(p => p.cves.includes($input.cve)) }}', lambdaCtx)).toBe(true);
    expect(judge('{{ $json.items.every(p => p.cves.includes($input.cve)) }}', lambdaCtx)).toBe(false);
    expect(evaluate('{{ $json.items.filter(p => p.cves.length > 0).length }}', lambdaCtx)).toBe(1);
    expect(evaluate('{{ $json.items.find(p => p.cves.length == 0).outputs.vmId }}', lambdaCtx)).toBe('vm-2');
    expect(evaluate('{{ $json.items.findIndex((p, i) => i == 1) }}', lambdaCtx)).toBe(1);
    expect(evaluate('{{ $json.items.find(p => p.nope) }}', lambdaCtx)).toBeNull();
    expect(evaluate('{{ $json.items.findIndex(p => p.nope) }}', lambdaCtx)).toBe(-1);
    expect(() => evaluate('{{ $json.items.map(1) }}', lambdaCtx)).toThrow(/needs a function/);
    expect(() => evaluate('{{ $json.items.map(v => w) }}', lambdaCtx)).toThrow(ExpressionError);
  });

  it('range, concat, slice, padStart, sum and max', () => {
    expect(evaluate('{{ $range(1, $input.vmCount) }}', lambdaCtx)).toEqual([1, 2, 3]);
    expect(evaluate('{{ $range(2) }}', lambdaCtx)).toEqual([0, 1, 2]);
    expect(() => evaluate('{{ $range(1, 20000) }}', lambdaCtx)).toThrow(/limited to 10000/);
    expect(evaluate("{{ $json.items.map(v => v.outputs.vmId).concat(['db-1']) }}", lambdaCtx)).toEqual(['vm-1', 'vm-2', 'db-1']);
    expect(evaluate("{{ $input.os + '-cis-' + $job.startedAt.slice(0, 10) }}", lambdaCtx)).toBe('ubuntu-cis-2026-10-17');
    expect(evaluate("{{ '7'.padStart(3, '0') }}", lambdaCtx)).toBe('007');
    expect(evaluate("{{ '7'.padStart(3) }}", lambdaCtx)).toBe('  7');
    expect(evaluate('{{ [1, 2, 3].sum() }}', lambdaCtx)).toBe(6);
    expect(evaluate('{{ $max(1, 3, 2) }}', lambdaCtx)).toBe(3);
    expect(evaluate('{{ $min(1, 3, 2) }}', lambdaCtx)).toBe(1);
    expect(evaluate("{{ $number('12.5') + $length('abc') + $length([1, 2]) }}", lambdaCtx)).toBe(17.5);
    expect(evaluate('{{ $string(12) }}', lambdaCtx)).toBe('12');
    expect(evaluate('{{ $keys({ a: 1, b: 2 }) }}', lambdaCtx)).toEqual(['a', 'b']);
  });

  it('function parameters are not references', () => {
    const refs = references('{{ $json.items.map(v => v.outputs.vmId) }}');
    expect(refs).toEqual([{ kind: 'json', path: 'items' }]);
  });

  it('an unknown bare identifier still fails', () => {
    expect(syntaxError('{{ items.map(v => w.id) }}')).not.toBeNull();
    expect(syntaxError('{{ $json.items.map(v => w.id) }}')).not.toBeNull();
    expect(syntaxError('{{ $json.items.map(v => v.id) }}')).toBeNull();
  });
});
