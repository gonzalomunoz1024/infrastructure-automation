// The CLI run in this process (`main` with its input and output given), against the stub BFF
// with a clock the tests control, so the device flow's waits pass at once.

import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { after, before, beforeEach, describe, test } from 'node:test';
import { main } from '../dist/main.js';
import { fakeClock, startStubBff } from '../../../test/support/stub-bff.mjs';

describe('console', () => {
  let bff;
  let clock;
  let dir;

  before(async () => {
    clock = fakeClock();
    bff = await startStubBff({ clock, autoApproveAfter: 1 });
  });
  after(() => bff.close());
  beforeEach(async () => {
    bff.reset();
    dir = await mkdtemp(path.join(os.tmpdir(), 'console-cli-'));
  });

  /** Runs one command line; answers its exit code and what it wrote. */
  async function run(argv, { tty = false, env = {}, stdin } = {}) {
    const out = [];
    const err = [];
    const opened = [];
    const code = await main(argv, {
      env: { CONSOLE_CONFIG_DIR: dir, CONSOLE_ENDPOINT: bff.url, ...env },
      stdout: { write: (s) => out.push(s), isTTY: tty },
      stderr: { write: (s) => err.push(s), isTTY: tty },
      readStdin: stdin === undefined ? undefined : async () => stdin,
      openBrowser: async (url) => {
        opened.push(url);
        return true;
      },
      sleep: clock.sleep,
      now: clock.now,
      platform: 'linux',
    });
    return { code, stdout: out.join(''), stderr: err.join(''), opened };
  }

  const credentials = async () => JSON.parse(await readFile(path.join(dir, 'credentials.json'), 'utf8')).sessions[bff.url];
  const refreshes = () => bff.requests.filter((r) => r.path === '/auth/token' && r.form?.grant_type === 'refresh_token');

  describe('login', () => {
    test('prints the code and the page, polls, keeps the tokens in a 0600 file', async () => {
      const r = await run(['login']);
      assert.equal(r.code, 0, r.stderr);
      const code = /enter the code ([A-Z]{4}-[A-Z]{4})/.exec(r.stderr)?.[1];
      assert.ok(code, r.stderr);
      assert.match(r.stderr, new RegExp(`^To sign in to ${bff.url}, open ${bff.url}/#/device$`, 'm'));
      assert.match(r.stderr, new RegExp(`^or open ${bff.url}/#/device\\?code=${code}$`, 'm'));
      assert.deepEqual(r.opened, [], 'no browser without a terminal');
      const answer = JSON.parse(r.stdout);
      assert.equal(answer.signedIn, true);
      assert.equal(answer.user.email, 'alice@example.com');
      assert.match(answer.apiSession, /^aps_/);
      assert.equal(answer.clientId, 'console-cli');
      const stored = await credentials();
      assert.equal(stored.clientId, 'console-cli');
      assert.ok(stored.accessToken && stored.refreshToken);
      assert.equal((await stat(path.join(dir, 'credentials.json'))).mode & 0o777, 0o600);
      assert.equal((await stat(dir)).mode & 0o777, 0o700);
      const device = bff.requests.find((q) => q.path === '/auth/device');
      assert.deepEqual(device.form, { client_id: 'console-cli' });
      assert.match(device.headers['user-agent'], /^console-cli\/0\.1\.0 \(linux; node /);
    });

    test('at a terminal with a display it opens the page with the code, unless --no-browser', async () => {
      const opened = await run(['login'], { tty: true, env: { DISPLAY: ':0' } });
      assert.equal(opened.code, 0, opened.stderr);
      assert.equal(opened.opened.length, 1);
      assert.match(opened.opened[0], /\/#\/device\?code=[A-Z]{4}-[A-Z]{4}$/);
      assert.match(opened.stdout, /^Signed in to http:\/\/127\.0\.0\.1:\d+ as alice@example\.com in Payments \(111111111111\), eu-west-1\.$/m);
      const not = await run(['login', '--no-browser'], { tty: true, env: { DISPLAY: ':0' } });
      assert.deepEqual(not.opened, []);
      const ssh = await run(['login'], { tty: true, env: { DISPLAY: ':0', SSH_CONNECTION: '1 2 3 4' } });
      assert.deepEqual(ssh.opened, []);
    });

    test('a new sign-in revokes the API session it replaces', async () => {
      await run(['login']);
      const first = await credentials();
      await run(['login']);
      const revoke = bff.requests.find((q) => q.path === '/auth/revoke');
      assert.equal(revoke.form.token, first.refreshToken);
    });

    test('a denied sign-in, an expired code', async () => {
      bff.route('POST', '/auth/token', (q) => (q.form.grant_type.endsWith('device_code') ? { status: 400, body: { error: 'access_denied' } } : undefined));
      const denied = await run(['login']);
      assert.equal(denied.code, 1);
      assert.match(denied.stderr, /^console: the sign-in was denied$/m);
      bff.reset();
      bff.route('POST', '/auth/token', (q) => (q.form.grant_type.endsWith('device_code') ? { status: 400, body: { error: 'expired_token' } } : undefined));
      const expired = await run(['login']);
      assert.equal(expired.code, 1);
      assert.match(expired.stderr, /the code expired before the sign-in was approved: run `console login` again/);
    });

    test('too many sign-ins from one address', async () => {
      bff.route('POST', '/auth/device', () => ({ status: 429, body: { error: 'slow_down' } }));
      const r = await run(['login']);
      assert.equal(r.code, 1);
      assert.match(r.stderr, /too many sign-ins from this address/);
    });
  });

  describe('signed in', () => {
    beforeEach(async () => {
      const r = await run(['login']);
      assert.equal(r.code, 0, r.stderr);
      bff.reset();
    });

    test('whoami: the person, the account and region, the API session', async () => {
      const r = await run(['whoami']);
      assert.equal(r.code, 0, r.stderr);
      const who = JSON.parse(r.stdout);
      assert.equal(who.endpoint, bff.url);
      assert.equal(who.user.email, 'alice@example.com');
      assert.equal(who.account.accountId, '111111111111');
      assert.equal(who.region, 'eu-west-1');
      assert.match(who.apiSession, /^aps_/);
      assert.equal(who.csrfToken, undefined);
      const table = await run(['whoami'], { tty: true });
      assert.match(table.stdout, /^user\.email +alice@example\.com$/m);
      const other = JSON.parse((await run(['whoami', '--account', '222222222222'])).stdout);
      assert.equal(other.account.name, 'Sandbox');
    });

    test('a command of the core, with the configured account and region', async () => {
      assert.equal((await run(['configure', 'set', 'account', '222222222222'])).code, 0);
      assert.equal((await run(['configure', 'set', 'region', 'us-east-1'])).code, 0);
      const r = await run(['jobs', 'list', '--limit', '2']);
      assert.equal(r.code, 0, r.stderr);
      assert.equal(JSON.parse(r.stdout).items.length, 2);
      const call = bff.requests.find((q) => q.path === '/console/v1/jobs');
      assert.equal(call.headers['x-console-account'], '222222222222');
      assert.equal(call.headers['x-console-region'], 'us-east-1');
      assert.match(call.headers.authorization, /^Bearer /);
      // the environment, then a flag, win over the configuration
      await run(['jobs', 'list'], { env: { CONSOLE_REGION: 'eu-west-1' } });
      await run(['jobs', 'list', '--region', 'eu-central-1'], { env: { CONSOLE_REGION: 'eu-west-1' } });
      const regions = bff.requests.filter((q) => q.path === '/console/v1/jobs').map((q) => q.headers['x-console-region']);
      assert.deepEqual(regions, ['us-east-1', 'eu-west-1', 'eu-central-1']);
    });

    test('an expired access token is refreshed and the rotated refresh token kept on disk', async () => {
      const before = await credentials();
      bff.expireAccessTokens();
      const r = await run(['jobs', 'list']);
      assert.equal(r.code, 0, r.stderr);
      const after = await credentials();
      assert.notEqual(after.refreshToken, before.refreshToken);
      assert.notEqual(after.accessToken, before.accessToken);
      assert.equal(refreshes().length, 1);
      assert.equal(refreshes()[0].form.refresh_token, before.refreshToken);
      assert.equal(refreshes()[0].form.client_id, 'console-cli');
    });

    test('commands that refresh at the same time refresh once (the lock), so the session survives', async () => {
      bff.expireAccessTokens();
      const results = await Promise.all(Array.from({ length: 4 }, () => run(['jobs', 'list'])));
      assert.deepEqual(results.map((r) => r.code), [0, 0, 0, 0], results.map((r) => r.stderr).join(''));
      assert.equal(refreshes().length, 1);
      assert.ok([...bff.sessions.values()].every((s) => !s.reuseDetected));
    });

    test('a refusal and a usage mistake', async () => {
      const denied = await run(['commands', 'run-verb', 'vm', 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', 'stop', '--body', '{"input":{}}']);
      assert.equal(denied.code, 1);
      assert.match(denied.stderr, /^console commands run-verb: 403 iam\.denied: /m);
      const wrong = await run(['jobs', 'get']);
      assert.equal(wrong.code, 2);
    });

    test('--body-file - reads standard input', async () => {
      const r = await run(['commands', 'create-resource', 'vm', '--body-file', '-'], { stdin: '{"input":{"name":"web-9"}}' });
      assert.equal(r.code, 0, r.stderr);
      assert.match(JSON.parse(r.stdout).wfrn, /vm\/web-9$/);
      assert.match(bff.requests.find((q) => q.method === 'POST').headers['idempotency-key'], /^[0-9a-f-]{36}$/);
    });

    test('logout revokes the API session and forgets its tokens', async () => {
      const tokens = await credentials();
      const r = await run(['logout']);
      assert.equal(r.code, 0, r.stderr);
      assert.deepEqual(JSON.parse(r.stdout), { endpoint: bff.url, signedOut: true, revoked: true });
      const revoke = bff.requests.find((q) => q.path === '/auth/revoke');
      assert.deepEqual(revoke.form, { token: tokens.refreshToken, token_type_hint: 'refresh_token', client_id: 'console-cli' });
      assert.equal(await credentials(), undefined);
      assert.ok([...bff.sessions.values()].some((s) => s.revokedAt !== undefined));
      const who = await run(['whoami']);
      assert.equal(who.code, 1);
      assert.match(who.stderr, /not signed in to .+: run `console login`/);
      assert.match((await run(['logout'], { tty: true })).stdout, /^Not signed in to /);
    });
  });

  describe('not signed in', () => {
    test('a command says how to sign in', async () => {
      const r = await run(['jobs', 'list']);
      assert.equal(r.code, 1);
      assert.match(r.stderr, /401 unauthorized\n  the sign-in has ended or is missing: run `console login`/);
    });
  });

  describe('configure', () => {
    test('set, get, unset and list, in a 0600 file', async () => {
      assert.equal((await run(['configure', 'set', 'endpoint', 'https://console.example.com/'])).code, 0);
      assert.equal((await run(['configure', 'set', 'output', 'yaml'])).code, 0);
      assert.equal((await run(['configure', 'get', 'endpoint'])).stdout, 'https://console.example.com\n');
      assert.equal((await stat(path.join(dir, 'config.json'))).mode & 0o777, 0o600);
      const list = JSON.parse((await run(['configure', 'list'])).stdout);
      assert.deepEqual(list, { endpoint: 'https://console.example.com', output: 'yaml', configDir: dir });
      assert.equal((await run(['configure', 'unset', 'output'])).code, 0);
      const get = await run(['configure', 'get', 'output']);
      assert.equal(get.code, 1);
      assert.equal(get.stdout, '');
    });

    test('values are checked', async () => {
      for (const [argv, message] of [
        [['configure', 'set', 'account', '123'], /an account is twelve digits/],
        [['configure', 'set', 'region', 'EU West'], /a region is 2 to 32/],
        [['configure', 'set', 'output', 'csv'], /output is one of json, table, yaml/],
        [['configure', 'set', 'endpoint', 'ftp://x'], /an http\(s\) address/],
        [['configure', 'set', 'endpoint', 'https://user:pw@x.example.com'], /must not carry a user name or password/],
        [['configure', 'set', 'colour', 'blue'], /takes a key: account, region, output, endpoint, not colour/],
        [['configure', 'set', 'region'], /takes one value/],
        [['configure', 'frobnicate'], /configure takes set, get, unset or list/],
      ]) {
        const r = await run(argv);
        assert.equal(r.code, 2, argv.join(' '));
        assert.match(r.stderr, message);
      }
    });
  });

  describe('output', () => {
    beforeEach(async () => {
      await run(['login']);
    });

    test('JSON when piped, a table at a terminal, the configured format there, and -o over all', async () => {
      const piped = (await run(['jobs', 'list'])).stdout;
      assert.doesNotThrow(() => JSON.parse(piped));
      assert.match((await run(['jobs', 'list'], { tty: true })).stdout, /^JOB_ID +WORKFLOW +TITLE +STATUS/);
      await run(['configure', 'set', 'output', 'yaml']);
      assert.match((await run(['jobs', 'list'], { tty: true })).stdout, /^items:\n  - jobId: job-1$/m);
      const stillPiped = (await run(['jobs', 'list'])).stdout;
      assert.doesNotThrow(() => JSON.parse(stillPiped), 'piped output stays JSON');
      assert.match((await run(['jobs', 'list'], { env: { CONSOLE_OUTPUT: 'table' } })).stdout, /^JOB_ID/);
      assert.match((await run(['jobs', 'list', '-o', 'table'])).stdout, /^JOB_ID/);
      const bad = await run(['jobs', 'list'], { env: { CONSOLE_OUTPUT: 'xml' } });
      assert.equal(bad.code, 2);
    });
  });

  test('--endpoint, --version, help for the CLI\'s own commands', async () => {
    const closed = await new Promise((resolve) => {
      const server = net.createServer().listen(0, '127.0.0.1', () => {
        const { port } = server.address();
        server.close(() => resolve(`http://127.0.0.1:${port}`));
      });
    });
    const r = await run(['--endpoint', closed, 'health']);
    assert.equal(r.code, 1);
    assert.equal(r.stderr, `console health: could not reach ${closed} (ECONNREFUSED)\n`);
    assert.equal((await run(['--endpoint=nonsense', 'health'])).code, 2);
    assert.equal((await run(['--version'])).stdout, 'console 0.1.0\n');
    assert.match((await run(['login', '--help'])).stdout, /^Usage: console login \[--no-browser\]/);
    assert.match((await run(['help', 'configure'])).stdout, /console configure set <key> <value>/);
    assert.match((await run(['--help'])).stdout, /^More commands:\n  login /m);
  });
});
