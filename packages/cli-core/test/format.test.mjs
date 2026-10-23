import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { formatData, toTable, toYaml, yamlScalar } from '@iaf/console-cli-core';

const page = {
  items: [
    { jobId: 'job-1', status: 'running', startedAt: '2026-10-23T10:00:00Z', labels: { team: 'payments' } },
    { jobId: 'job-2', status: 'failed', failedStep: 'apply', tags: ['a', 'b'] },
  ],
  next: 'Mg',
  unread: 3,
};

describe('json', () => {
  test('indented, with a newline; nothing for no answer', () => {
    assert.equal(formatData({ a: [1, { b: null }] }, 'json'), '{\n  "a": [\n    1,\n    {\n      "b": null\n    }\n  ]\n}\n');
    assert.equal(formatData(undefined, 'json'), '');
  });
});

describe('yaml', () => {
  test('maps, lists, lists of maps and empty ones', () => {
    assert.equal(
      toYaml({ user: { email: 'alice@example.com', groups: ['a', 'b'] }, items: [{ id: 1, tags: [] }, { id: 2, nested: { x: true } }], empty: {}, none: null }),
      [
        'user:',
        '  email: alice@example.com',
        '  groups:',
        '    - a',
        '    - b',
        'items:',
        '  - id: 1',
        '    tags: []',
        '  - id: 2',
        '    nested:',
        '      x: true',
        'empty: {}',
        'none: null',
        '',
      ].join('\n'),
    );
  });

  test('a list of lists', () => {
    assert.equal(toYaml([[1, 2], 'x']), '- - 1\n  - 2\n- x\n');
  });

  test('strings that would read as something else are quoted', () => {
    for (const s of ['true', 'no', 'null', '~', '42', '1.5', '1e3', '2026-10-23', '', ' padded', 'a: b', 'wfrn:iaf:compute', '#tag', '-x', 'line\nbreak', "it's", '0x1f', '.inf']) {
      const y = yamlScalar(s);
      assert.ok(y.startsWith('"'), `${JSON.stringify(s)} is quoted: ${y}`);
      assert.equal(JSON.parse(y), s, 'the quoted form is the string');
    }
    for (const s of ['alice@example.com', 'web-1', 'vm/web-1', 'eu-west-1', 'Payments team', 'job_2']) {
      assert.equal(yamlScalar(s), s, `${s} stays plain`);
    }
    assert.equal(yamlScalar(3), '3');
    assert.equal(yamlScalar(false), 'false');
    assert.equal(yamlScalar(null), 'null');
  });

  test('keys are quoted the same way', () => {
    assert.equal(toYaml({ 'tag.env': 'prod', 'a b': 1, 'x:y': 2, true: 3 }), 'tag.env: prod\na b: 1\n"x:y": 2\n"true": 3\n');
  });
});

describe('table', () => {
  test('a page is rows and columns, then what else it says and how to go on', () => {
    assert.equal(
      toTable(page),
      [
        'JOB_ID  STATUS   STARTED_AT            LABELS               FAILED_STEP  TAGS',
        'job-1   running  2026-10-23T10:00:00Z  {"team":"payments"}',
        'job-2   failed                                              apply        a, b',
        'unread: 3',
        'more: --after Mg for the next page, or --all for every page',
        '',
      ].join('\n'),
    );
  });

  test('the last page says nothing of more', () => {
    assert.doesNotMatch(toTable({ items: [{ a: 1 }], next: null }), /more:/);
  });

  test('an empty list', () => {
    assert.equal(toTable({ items: [], next: null }), '(none)\n');
    assert.equal(toTable([]), '(none)\n');
  });

  test('a record is a key and a value per line, nested keys dotted', () => {
    assert.equal(
      toTable({ user: { email: 'alice@example.com', groups: ['a', 'b'] }, region: 'eu-west-1', roles: [{ r: 1 }] }),
      ['user.email   alice@example.com', 'user.groups  a, b', 'region       eu-west-1', 'roles        [{"r":1}]', ''].join('\n'),
    );
  });

  test('long cells are cut, line breaks flattened', () => {
    const out = toTable([{ text: `${'x'.repeat(60)}\nend` }]);
    const row = out.split('\n')[1];
    assert.equal(row.length, 48);
    assert.ok(row.endsWith('…'));
  });

  test('a scalar is itself', () => {
    assert.equal(toTable('hello'), 'hello\n');
    assert.equal(toTable(undefined), '');
  });
});
