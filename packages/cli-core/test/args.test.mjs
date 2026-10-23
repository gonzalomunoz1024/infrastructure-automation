import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { isFlag, parseArgs, suggest, UsageError } from '@iaf/console-cli-core';

const defs = [
  { name: 'output', short: 'o', type: 'string', enum: ['json', 'table', 'yaml'] },
  { name: 'all', type: 'boolean' },
  { name: 'limit', type: 'integer' },
  { name: 'ratio', type: 'number' },
  { name: 'sort', type: 'string' },
  { name: 'type', type: 'string', array: true },
  { name: 'tag.<name>', type: 'string', namePattern: /^tag\.[a-z0-9-]{1,32}$/ },
  { name: 'help', short: 'h', type: 'boolean' },
];

const flags = (argv) => Object.fromEntries(parseArgs(argv, defs).flags);

describe('parseArgs', () => {
  test('long flags with a space or an equals sign, short flags, positionals', () => {
    const parsed = parseArgs(['jobs', '--limit', '5', 'list', '-o', 'yaml', '--sort=-created'], defs);
    assert.deepEqual(parsed.positionals, ['jobs', 'list']);
    assert.deepEqual(Object.fromEntries(parsed.flags), { limit: 5, output: 'yaml', sort: '-created' });
  });

  test('a value may start with a dash', () => {
    assert.deepEqual(flags(['--sort', '-created']), { sort: '-created' });
  });

  test('booleans: bare, =true|false, and --no-', () => {
    assert.deepEqual(flags(['--all']), { all: true });
    assert.deepEqual(flags(['--all=false']), { all: false });
    assert.deepEqual(flags(['--no-all']), { all: false });
    assert.deepEqual(flags(['-h']), { help: true });
    assert.throws(() => flags(['--all=maybe']), /--all is true or false, not maybe/);
    assert.throws(() => flags(['--no-all=true']), /--no-all takes no value/);
  });

  test('a boolean does not take the next word', () => {
    const parsed = parseArgs(['--all', 'list'], defs);
    assert.deepEqual(parsed.positionals, ['list']);
  });

  test('numbers are checked', () => {
    assert.deepEqual(flags(['--limit', '200', '--ratio', '0.5']), { limit: 200, ratio: 0.5 });
    assert.throws(() => flags(['--limit', 'many']), (e) => e instanceof UsageError && /--limit takes a whole number, not many/.test(e.message));
    assert.throws(() => flags(['--limit', '1.5']), /whole number/);
    assert.throws(() => flags(['--ratio', 'x']), /--ratio takes a number/);
  });

  test('an enum is checked, with a suggestion', () => {
    assert.throws(() => flags(['-o', 'tabel']), /--?o is one of json, table, yaml, not tabel; did you mean table\?/);
  });

  test('a repeatable flag collects its values; another is refused twice', () => {
    assert.deepEqual(flags(['--type', 'vm', '--type=db']), { type: ['vm', 'db'] });
    assert.throws(() => flags(['--limit', '1', '--limit', '2']), /--limit is given twice/);
  });

  test('a pattern flag takes each name it allows', () => {
    assert.deepEqual(flags(['--tag.env', 'prod', '--tag.team=payments']), { 'tag.env': 'prod', 'tag.team': 'payments' });
    assert.throws(() => flags(['--tag.Bad', 'x']), /unknown flag --tag\.Bad/);
  });

  test('unknown flags are refused, with a suggestion when one is near', () => {
    assert.throws(() => flags(['--limt', '3']), /unknown flag --limt; did you mean --limit\?/);
    assert.throws(() => flags(['--zzz']), (e) => e.message === 'unknown flag --zzz');
    assert.throws(() => flags(['-x']), /unknown flag -x/);
  });

  test('a flag without its value', () => {
    assert.throws(() => flags(['--sort']), /--sort needs a value/);
  });

  test('-- ends the flags; a lone dash and negative numbers are words', () => {
    const parsed = parseArgs(['--all', '--', '--not-a-flag', '-1'], defs);
    assert.deepEqual(parsed.positionals, ['--not-a-flag', '-1']);
    assert.equal(isFlag('-'), false);
    assert.equal(isFlag('-5'), false);
    assert.equal(isFlag('-o'), true);
  });

  test('suggestions count a swap of neighbours as one edit', () => {
    assert.equal(suggest('jbos', ['jobs', 'types'], (s) => s), '; did you mean jobs?');
    assert.equal(suggest('resource', ['resources', 'rightsizing'], (s) => s), '; did you mean resources?');
    assert.equal(suggest('xyz', ['jobs'], (s) => s), '');
  });
});
