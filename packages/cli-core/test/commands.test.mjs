import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, test } from 'node:test';
import { OPERATIONS } from '@iaf/console-sdk';
import { commandHelp, findCommand, groupHelp, GROUPS, topHelp, wrap } from '@iaf/console-cli-core';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const ctx = { program: 'console', commands: [{ name: 'login', summary: 'Sign in' }], globalFlags: [{ name: '--endpoint <url>', summary: "The console's address" }] };
const commands = GROUPS.flatMap((g) => g.commands.map((c) => ({ group: g.name, ...c })));

describe('the command table', () => {
  test('one command per operation, but the browser sign-in and the in-cluster tokens', () => {
    const expected = Object.entries(OPERATIONS)
      .filter(([, op]) => !op.path.startsWith('/auth/') && !op.path.startsWith('/internal/'))
      .map(([id]) => id)
      .sort();
    assert.deepEqual(commands.map((c) => c.operationId).sort(), expected);
    assert.ok(expected.length > 80);
    for (const c of commands) {
      assert.equal(c.method, OPERATIONS[c.operationId].method, c.operationId);
      assert.equal(c.path, OPERATIONS[c.operationId].path, c.operationId);
    }
  });

  test('grouped by tag, named in kebab case, each name once in its group', () => {
    for (const g of GROUPS) {
      assert.match(g.name, /^[a-z][a-z0-9-]*$/);
      assert.equal(new Set(g.commands.map((c) => c.name)).size, g.commands.length, g.name);
      for (const c of g.commands) assert.match(c.name, /^[a-z][a-z0-9-]*$/, `${g.name} ${c.name}`);
    }
  });

  test('the tag\'s noun leaves the name: resources list, jobs explain-failure', () => {
    const names = Object.fromEntries(commands.map((c) => [c.operationId, `${c.group} ${c.name}`]));
    assert.equal(names.listResources, 'resources list');
    assert.equal(names.getResource, 'resources get');
    assert.equal(names.listResourceActivity, 'resources list-activity');
    assert.equal(names.getJob, 'jobs get');
    assert.equal(names.cancelJob, 'jobs cancel');
    assert.equal(names.explainJobFailure, 'jobs explain-failure');
    assert.equal(names.listAccessRequests, 'access-requests list');
    assert.equal(names.decideAccessRequest, 'access-requests decide');
    assert.equal(names.iamAction, 'iam action');
    assert.equal(names.streamEvents, 'events stream');
    assert.equal(names.createResource, 'commands create-resource');
    assert.equal(names.listBudgetPeriods, 'budgets list-periods');
    assert.equal(findCommand('search', 'search').command.groupDefault, true);
  });

  test('path parameters are flags and positionals in path order; the client\'s own headers are not flags', () => {
    const { command } = findCommand('commands', 'run-verb');
    assert.deepEqual(command.positionals, ['type', 'wfrn', 'verb']);
    assert.deepEqual(command.flags.slice(0, 3).map((f) => f.flag), ['type', 'wfrn', 'verb']);
    for (const c of commands) {
      for (const f of c.flags) assert.ok(!['x-csrf-token', 'idempotency-key'].includes(f.flag), `${c.operationId} --${f.flag}`);
    }
    assert.deepEqual(findCommand('budgets', 'update').command.flags.map((f) => f.flag), ['budget-id', 'if-match']);
    assert.ok(findCommand('resources', 'list').command.flags.some((f) => f.flag === 'tag.<name>' && f.namePattern));
    assert.equal(findCommand('jobs', 'get').command.flags[0].flag, 'job-id');
  });

  test('paged lists and bodies are marked', () => {
    assert.equal(findCommand('jobs', 'list').command.paged, true);
    assert.equal(findCommand('jobs', 'get').command.paged, undefined);
    assert.deepEqual(findCommand('commands', 'create-resource').command.body, { required: true, contentType: 'application/json', schema: 'CommandInput' });
    assert.equal(findCommand('events', 'stream').command.response, 'events');
    assert.equal(findCommand('cost', 'export-chargeback').command.response, 'text');
  });
});

describe('help', () => {
  test('the top help names every group, the host\'s commands and every global flag', () => {
    const help = topHelp(GROUPS, ctx);
    for (const g of GROUPS) assert.match(help, new RegExp(`^  ${g.name} `, 'm'));
    for (const f of ['--output', '--account', '--region', '--all', '--body', '--body-file', '--idempotency-key', '--help', '--endpoint']) assert.ok(help.includes(f), f);
    assert.match(help, /^  login +Sign in$/m);
  });

  test('a group\'s help lists its commands', () => {
    const { group } = findCommand('jobs', 'list');
    const help = groupHelp(group, ctx);
    assert.match(help, /^console jobs: Jobs$/m);
    for (const c of group.commands) assert.match(help, new RegExp(`^  ${c.name} +${c.summary.replace(/[()]/g, '\\$&').slice(0, 20)}`, 'm'));
  });

  test('a command\'s help: usage, method and path, flags, body, paging', () => {
    const { group, command } = findCommand('resources', 'list');
    const help = commandHelp(group, command, ctx);
    assert.match(help, /^  console resources list <type> \[flags\]$/m);
    assert.match(help, /^  GET \/console\/v1\/r\/\{type\}  \(operation listResources\)$/m);
    assert.match(help, /^  <type>, --type <string> +A resource type name$/m);
    assert.match(help, /--state <string> +one of pending, active, updating, deleting, deleted, failed/);
    assert.match(help, /--limit <integer> +default 50/);
    assert.match(help, /--all +every page/);
    const create = findCommand('commands', 'create-resource');
    const createHelp = commandHelp(create.group, create.command, ctx);
    assert.match(createHelp, /console commands create-resource <type> --body <json> \[flags\]/);
    assert.match(createHelp, /--body <json>, --body-file <path> +the request body \(CommandInput\); required/);
    const cost = findCommand('cost', 'get');
    assert.match(commandHelp(cost.group, cost.command, ctx), /console cost get --from <string> --to <string> \[flags\]/);
  });

  test('wrap keeps paragraphs and the width', () => {
    const lines = wrap(`${'word '.repeat(40)}\n\nsecond paragraph`, 2, 40);
    assert.ok(lines.every((l) => l.length <= 40));
    assert.ok(lines.includes(''));
    assert.equal(lines.at(-1), '  second paragraph');
  });
});

describe('in a browser', () => {
  test('the command core and the SDK import nothing of Node', async () => {
    for (const pkg of ['packages/cli-core/src', 'packages/sdk/src']) {
      for (const file of await sources(path.join(ROOT, pkg))) {
        const text = await readFile(file, 'utf8');
        const where = path.relative(ROOT, file);
        assert.doesNotMatch(text, /from\s+['"]node:|require\(|import\(['"]node:/, `${where} imports Node`);
        assert.doesNotMatch(text, /\bprocess\.|\bBuffer\b|__dirname|\bglobal\./, `${where} uses a Node global`);
      }
    }
  });
});

async function sources(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await sources(full)));
    else if (entry.name.endsWith('.ts')) out.push(full);
  }
  return out;
}
