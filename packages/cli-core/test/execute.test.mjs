import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { ConsoleApiError, OAuthError, createConsoleClient, pollDeviceAuthorization, startDeviceAuthorization } from '@iaf/console-sdk';
import { execute } from '@iaf/console-cli-core';
import { fakeClock, startStubBff } from '../../../test/support/stub-bff.mjs';

/** A client that records each call and answers what the test says. */
function fakeClient(answers = {}) {
  const calls = [];
  return {
    endpoint: 'https://console.example.com',
    calls,
    async send(operationId, input) {
      calls.push({ operationId, input });
      const answer = answers[operationId];
      if (answer instanceof Error) throw answer;
      return answer ?? { status: 200, headers: new Headers(), data: { ok: true } };
    },
    async *pages(operationId, input) {
      calls.push({ operationId, input, all: true });
      yield* answers[`${operationId}:pages`] ?? [];
    },
    async *events(operationId, input) {
      calls.push({ operationId, input, events: true });
      yield* answers[`${operationId}:events`] ?? [];
    },
  };
}

const apiError = (status, body, headers = {}) =>
  new ConsoleApiError({ status, method: 'POST', url: 'https://console.example.com/console/v1/x', headers: new Headers(headers), body });

describe('execute', () => {
  describe('arguments to calls', () => {
    test('a path parameter as a positional or a flag', async () => {
      const client = fakeClient();
      assert.equal((await execute(['jobs', 'get', 'job-1'], client)).exitCode, 0);
      assert.equal((await execute(['jobs', 'get', '--job-id', 'job-2'], client)).exitCode, 0);
      assert.deepEqual(client.calls.map((c) => [c.operationId, c.input.path]), [
        ['getJob', { jobId: 'job-1' }],
        ['getJob', { jobId: 'job-2' }],
      ]);
    });

    test('several path parameters in path order, some as flags', async () => {
      const client = fakeClient();
      await execute(['commands', 'run-verb', '--type', 'vm', 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', 'stop', '--body', '{"input":{}}'], client);
      assert.deepEqual(client.calls[0].input.path, { type: 'vm', wfrn: 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', verb: 'stop' });
    });

    test('query flags, typed; a pattern flag by each of its names', async () => {
      const client = fakeClient();
      await execute(['resources', 'list', 'vm', '--state', 'active', '--tag.env', 'prod', '--tag.team=payments', '--drift', '--limit', '5', '--sort', '-created'], client);
      assert.deepEqual(client.calls[0].input.query, { state: 'active', 'tag.env': 'prod', 'tag.team': 'payments', drift: true, limit: 5, sort: '-created' });
    });

    test('global flags before the command, and the account and region', async () => {
      const client = fakeClient();
      const r = await execute(['-o', 'yaml', '--account', '111111111111', 'jobs', '--region=eu-west-1', 'list'], client);
      assert.equal(r.exitCode, 0, r.stderr);
      assert.equal(r.stdout, 'ok: true\n');
      assert.equal(client.calls[0].input.account, '111111111111');
      assert.equal(client.calls[0].input.region, 'eu-west-1');
    });

    test('a command\'s own --region is its query parameter, not the session\'s', async () => {
      const client = fakeClient();
      await execute(['cost', 'get', '--from', '2026-09-01', '--to', '2026-09-30', '--region', 'eu-west-1', '--account', '111111111111'], client);
      assert.equal(client.calls[0].input.query.region, 'eu-west-1');
      assert.equal(client.calls[0].input.region, undefined);
      assert.equal(client.calls[0].input.account, '111111111111');
    });

    test('header flags', async () => {
      const client = fakeClient();
      await execute(['budgets', 'update', 'b-1', '--if-match', '"v3"', '--body', '{"name":"n","amount":{"amount":1,"currency":"EUR"}}'], client);
      assert.deepEqual(client.calls[0].input.headers, { 'If-Match': '"v3"' });
    });

    test('the body from --body or --body-file, and the idempotency key', async () => {
      const client = fakeClient();
      const files = { 'vm.json': '{"input":{"name":"web-9"}}' };
      const readFile = async (p) => {
        if (!(p in files)) throw new Error('ENOENT: no such file');
        return files[p];
      };
      await execute(['commands', 'create-resource', 'vm', '--body', '{"input":{"name":"web-8"}}', '--idempotency-key', 'k-1'], client);
      await execute(['commands', 'create-resource', 'vm', '--body-file', 'vm.json'], client, { readFile });
      assert.deepEqual(client.calls[0].input.body, { input: { name: 'web-8' } });
      assert.equal(client.calls[0].input.idempotencyKey, 'k-1');
      assert.deepEqual(client.calls[1].input.body, { input: { name: 'web-9' } });
      assert.equal(client.calls[1].input.idempotencyKey, undefined);
      const missing = await execute(['commands', 'create-resource', 'vm', '--body-file', 'nope.json'], client, { readFile });
      assert.equal(missing.exitCode, 2);
      assert.match(missing.stderr, /cannot read --body-file nope\.json: ENOENT/);
    });

    test('the group\'s own command: console search --q …', async () => {
      const client = fakeClient();
      await execute(['search', '--q', 'web', '--limit', '3'], client);
      await execute(['search', 'interpret', '--q', 'failed vms in prod'], client);
      await execute(['health'], client);
      assert.deepEqual(client.calls.map((c) => c.operationId), ['search', 'interpretSearch', 'health']);
      assert.deepEqual(client.calls[0].input.query, { q: 'web', limit: 3 });
    });
  });

  describe('mistakes are exit code 2', () => {
    const cases = [
      [['jobs', 'get'], /needs <jobId> \(or --job-id\): console jobs get <jobId> \[flags\]/],
      [['jobs', 'get', 'a', 'b'], /unexpected argument b/],
      [['search'], /needs --q/],
      [['commands', 'create-resource', 'vm'], /needs a body: --body <json> or --body-file <path>/],
      [['commands', 'create-resource', 'vm', '--body', '{nope'], /--body is not JSON/],
      [['commands', 'create-resource', 'vm', '--body', '{}', '--body-file', 'x'], /give --body or --body-file, not both/],
      [['commands', 'create-resource', 'vm', '--body-file', 'x'], /--body-file cannot be read here/],
      [['jobs', 'list', '--body', '{}'], /this command takes no body/],
      [['jobs', 'get', 'j', '--all'], /--all is for lists that answer pages/],
      [['jobs', 'list', '--idempotency-key', 'k'], /--idempotency-key is for writes/],
      [['jobs', 'list', '--status'], /--status needs a value/],
      [['approvals', 'list', '--status', 'open'], /--status is one of pending, approved, rejected, expired, not open/],
      [['iam', 'list', 'people'], /<collection> is one of users, groups, roles, policies, access-keys, quotas, not people/],
      [['jbos'], /unknown group jbos; did you mean jobs\?/],
      [['jobs', 'lst'], /jobs has no command lst; did you mean list\?/],
      [['--job-id', 'j', 'jobs', 'get'], /--job-id is not a global flag: give it after the command/],
      [['jobs', 'list', '-o', 'csv'], /-o is one of json, table, yaml, not csv/],
    ];
    for (const [argv, message] of cases) {
      test(argv.join(' '), async () => {
        const client = fakeClient();
        const r = await execute(argv, client);
        assert.equal(r.exitCode, 2);
        assert.match(r.stderr, message);
        assert.match(r.stderr, /Run `console( [a-z-]+)* --help` for usage\./);
        assert.equal(client.calls.length, 0, 'no call is made');
      });
    }
  });

  describe('help', () => {
    test('--help, help <group> <command>, and a group alone', async () => {
      const client = fakeClient();
      const top = await execute(['--help'], client);
      assert.equal(top.exitCode, 0);
      assert.match(top.stdout, /^Groups, one per tag/m);
      assert.match((await execute(['help', 'jobs', 'list'], client)).stdout, /^console jobs list: The account's jobs$/m);
      assert.match((await execute(['jobs', 'list', '-h'], client)).stdout, /^  console jobs list \[flags\]$/m);
      assert.match((await execute(['jobs'], client)).stdout, /^Commands:$/m);
      assert.match((await execute([], client)).stdout, /^Usage:$/m);
      assert.match((await execute(['search', '--help'], client)).stdout, /search \(the group's own\)/);
      assert.equal(client.calls.length, 0);
    });
  });

  describe('output', () => {
    const data = { items: [{ jobId: 'job-1', status: 'running' }], next: null };
    const answers = { listJobs: { status: 200, headers: new Headers(), data } };

    test('json by default, or as the host says, or as -o says', async () => {
      assert.equal((await execute(['jobs', 'list'], fakeClient(answers))).stdout, `${JSON.stringify(data, null, 2)}\n`);
      assert.equal((await execute(['jobs', 'list'], fakeClient(answers), { output: 'table' })).stdout, 'JOB_ID  STATUS\njob-1   running\n');
      assert.equal((await execute(['jobs', 'list', '-o', 'yaml'], fakeClient(answers), { output: 'table' })).stdout, 'items:\n  - jobId: job-1\n    status: running\nnext: null\n');
    });

    test('--all gathers every page into one', async () => {
      const client = fakeClient({
        'listJobs:pages': [
          { items: [{ jobId: 'job-1' }], next: 'a' },
          { items: [{ jobId: 'job-2' }], next: null },
        ],
      });
      const r = await execute(['jobs', 'list', '--all', '--limit', '1'], client);
      assert.deepEqual(JSON.parse(r.stdout), { items: [{ jobId: 'job-1' }, { jobId: 'job-2' }], next: null });
      assert.equal(client.calls[0].all, true);
      assert.equal(client.calls[0].input.query.limit, 1);
    });

    test('text as it came; nothing for no content, but its status in a table', async () => {
      const client = fakeClient({
        exportChargeback: { status: 200, headers: new Headers(), data: 'month,amount\n2026-09,1\n' },
        cancelJob: { status: 202, headers: new Headers(), data: undefined },
      });
      assert.equal((await execute(['cost', 'export-chargeback', '--month', '2026-09', '-o', 'yaml'], client)).stdout, 'month,amount\n2026-09,1\n');
      assert.equal((await execute(['jobs', 'cancel', 'job-1'], client)).stdout, '');
      assert.equal((await execute(['jobs', 'cancel', 'job-1', '-o', 'table'], client)).stdout, '202 Accepted\n');
    });

    test('events as they come, a JSON line each', async () => {
      const client = fakeClient({
        'streamEvents:events': [
          { event: 'job', id: 'c-1', data: '{"jobId":"job-1"}' },
          { event: 'message', data: 'not json' },
        ],
      });
      const chunks = [];
      const r = await execute(['events', 'stream', '--types', 'job'], client, { onOutput: (c) => chunks.push(c) });
      assert.equal(r.exitCode, 0);
      assert.equal(r.stdout, '');
      assert.deepEqual(chunks, ['{"event":"job","id":"c-1","data":{"jobId":"job-1"}}\n', '{"event":"message","data":"not json"}\n']);
    });
  });

  describe('refusals are exit code 1, said plainly', () => {
    test('a Denial: who decided, why, and how to ask for access', async () => {
      const denied = apiError(403, {
        error: 'iam.denied',
        denied: { layer: 'console-iam', action: 'vm:stop', resource: 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', reason: 'Viewers may not stop VMs.', role: 'viewer', policyId: 'pol-1', statement: 'read-only', requestable: true, suggestedRole: 'operator' },
        correlationId: 'corr-9',
      });
      const r = await execute(['commands', 'run-verb', 'vm', 'w', 'stop', '--body', '{"input":{}}'], fakeClient({ runVerb: denied }));
      assert.equal(r.exitCode, 1);
      assert.equal(
        r.stderr,
        [
          'console commands run-verb: 403 iam.denied: Viewers may not stop VMs.',
          '  denied by console-iam: vm:stop on wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1; role viewer; policy pol-1; statement read-only',
          `  you may ask for access (the operator role allows it): console access-requests create --body '{"action":"vm:stop","resource":"wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1","reason":"…"}'`,
          '  correlation id corr-9',
          '',
        ].join('\n'),
      );
    });

    test('a step-up asks for a fresh sign-in', async () => {
      const stepUp = apiError(403, { error: 'step-up.required', detail: { stepUp: true, action: 'vm:delete', message: 'Deleting needs a fresh sign-in' } });
      const r = await execute(['commands', 'run-verb', 'vm', 'w', 'delete', '--body', '{"input":{}}'], fakeClient({ runVerb: stepUp }));
      assert.match(r.stderr, /403 step-up\.required: Deleting needs a fresh sign-in\n  this needs a fresh sign-in: run `console login` again\n/);
    });

    test('a 401 asks to sign in, as the host says', async () => {
      const unauthorized = apiError(401, { error: 'invalid_token' });
      unauthorized.cause = new OAuthError(400, 'invalid_grant');
      const r = await execute(['jobs', 'list'], fakeClient({ listJobs: unauthorized }), { signInHint: 'reload the page to sign in' });
      assert.match(r.stderr, /401 invalid_token\n  the sign-in has ended or is missing \(invalid_grant\): reload the page to sign in\n/);
    });

    test('problems, and when to try again', async () => {
      const bad = apiError(400, { error: 'validation.failed', problems: [{ code: 'enum', field: '/input/size', message: 'is small or large' }] });
      assert.match((await execute(['commands', 'create-resource', 'vm', '--body', '{}'], fakeClient({ createResource: bad }))).stderr, /400 validation\.failed\n  \/input\/size: is small or large\n/);
      const busy = apiError(429, { error: 'quota.exceeded' }, { 'Retry-After': '30' });
      assert.match((await execute(['jobs', 'list'], fakeClient({ listJobs: busy }))).stderr, /429 quota\.exceeded\n  try again in 30 s\n/);
    });

    test('the console out of reach', async () => {
      const down = new TypeError('fetch failed', { cause: Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' }) });
      const r = await execute(['jobs', 'list'], fakeClient({ listJobs: down }));
      assert.equal(r.exitCode, 1);
      assert.equal(r.stderr, 'console jobs list: could not reach https://console.example.com (ECONNREFUSED)\n');
    });
  });

  describe('with the SDK\'s client, against the stub BFF', () => {
    let bff;
    let client;
    before(async () => {
      const clock = fakeClock();
      bff = await startStubBff({ clock });
      const authorization = await startDeviceAuthorization({ endpoint: bff.url, now: clock.now });
      bff.approve(authorization.userCode);
      const tokens = await pollDeviceAuthorization({ endpoint: bff.url, authorization, now: clock.now, sleep: clock.sleep });
      client = createConsoleClient({ endpoint: bff.url, credentials: tokens.accessToken });
    });
    after(() => bff.close());

    test('a list as a table, every page', async () => {
      const r = await execute(['resources', 'list', 'vm', '--tag.env', 'prod', '--state', 'active', '--limit', '2', '--all', '-o', 'table'], client);
      assert.equal(r.exitCode, 0, r.stderr);
      const lines = r.stdout.trimEnd().split('\n');
      assert.match(lines[0], /^WFRN +TYPE +SERVICE +NAME +STATE +VERSION +ATTRIBUTES +TAGS$/);
      assert.equal(lines.length, 7, 'a header and six active VMs');
      assert.equal(bff.requests.filter((q) => q.path === '/console/v1/r/vm').length, 3);
      assert.ok(bff.requests.every((q) => !q.path.startsWith('/console/') || q.query['tag.env'] === 'prod'));
    });

    test('a refusal as the BFF sent it', async () => {
      const r = await execute(['commands', 'run-verb', 'vm', 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', 'stop', '--body', '{"input":{}}'], client);
      assert.equal(r.exitCode, 1);
      assert.match(r.stderr, /^console commands run-verb: 403 iam\.denied: Your roles in Payments do not allow vm:stop\.$/m);
    });
  });
});
