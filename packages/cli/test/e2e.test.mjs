// End to end: the built CLI (dist/bin.js), run as a person would, against the stub BFF in real
// time. The stub asks for 1 s between polls and approves the sign-in at the second one.

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, describe, test } from 'node:test';
import { startStubBff } from '../../../test/support/stub-bff.mjs';

const BIN = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist/bin.js');

describe('the built console CLI', () => {
  let bff;
  let dir;

  before(async () => {
    bff = await startStubBff({ interval: 1, autoApproveAfter: 1 });
    dir = await mkdtemp(path.join(os.tmpdir(), 'console-e2e-'));
  });
  after(async () => {
    await bff.close();
    await rm(dir, { recursive: true, force: true });
  });

  function consoleCli(args, { stdin, timeoutMs = 20_000 } = {}) {
    return new Promise((resolve, reject) => {
      const env = { ...process.env, CONSOLE_CONFIG_DIR: dir };
      for (const k of ['CONSOLE_ENDPOINT', 'CONSOLE_ACCOUNT', 'CONSOLE_REGION', 'CONSOLE_OUTPUT', 'DISPLAY', 'WAYLAND_DISPLAY']) delete env[k];
      const child = spawn(process.execPath, [BIN, ...args], { env, stdio: ['pipe', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      child.stdout.on('data', (d) => (stdout += d));
      child.stderr.on('data', (d) => (stderr += d));
      const timer = setTimeout(() => {
        child.kill();
        reject(new Error(`console ${args.join(' ')} did not end:\n${stderr}`));
      }, timeoutMs);
      child.on('close', (code) => {
        clearTimeout(timer);
        resolve({ code, stdout, stderr });
      });
      child.stdin.end(stdin ?? '');
    });
  }

  test('sign in, work, sign out', async () => {
    const started = Date.now();
    const login = await consoleCli(['--endpoint', bff.url, 'login']);
    assert.equal(login.code, 0, login.stderr);
    assert.match(login.stderr, /enter the code [BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}/);
    assert.ok(Date.now() - started >= 2000, 'it waited the interval before each poll');
    const signedIn = JSON.parse(login.stdout);
    assert.equal(signedIn.user.email, 'alice@example.com');
    assert.equal((await stat(path.join(dir, 'credentials.json'))).mode & 0o777, 0o600);

    assert.equal((await consoleCli(['configure', 'set', 'endpoint', bff.url])).code, 0);
    assert.equal((await stat(path.join(dir, 'config.json'))).mode & 0o777, 0o600);

    const who = await consoleCli(['whoami']);
    assert.equal(who.code, 0, who.stderr);
    assert.equal(JSON.parse(who.stdout).account.accountId, '111111111111');

    // piped: JSON; every page with --all
    const jobs = await consoleCli(['jobs', 'list', '--all', '--limit', '2']);
    assert.equal(jobs.code, 0, jobs.stderr);
    assert.deepEqual(
      JSON.parse(jobs.stdout).items.map((j) => j.jobId),
      ['job-1', 'job-2', 'job-3', 'job-4', 'job-5'],
    );

    const table = await consoleCli(['resources', 'list', 'vm', '--tag.env', 'prod', '--limit', '3', '-o', 'table']);
    assert.equal(table.code, 0, table.stderr);
    assert.match(table.stdout, /^WFRN +TYPE +SERVICE +NAME +STATE/);
    assert.match(table.stdout, /^more: --after \S+ for the next page, or --all for every page$/m);

    const yaml = await consoleCli(['jobs', 'get', 'job-3', '-o', 'yaml']);
    assert.match(yaml.stdout, /^jobId: job-3$/m);

    const create = await consoleCli(['commands', 'create-resource', 'vm', '--body-file', '-'], { stdin: '{"input":{"name":"web-9"}}' });
    assert.equal(create.code, 0, create.stderr);
    assert.match(JSON.parse(create.stdout).jobId, /^job-/);
    const write = bff.requests.find((r) => r.method === 'POST' && r.path === '/console/v1/r/vm');
    assert.match(write.headers['idempotency-key'], /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.match(write.headers['user-agent'], /^console-cli\/0\.1\.0/);

    const csv = await consoleCli(['cost', 'export-chargeback', '--month', '2026-09']);
    assert.equal(csv.stdout, 'month,costCentre,amount\n2026-09,cc-1,12.50\n');

    const events = await consoleCli(['events', 'stream']);
    assert.deepEqual(
      events.stdout.trim().split('\n').map((l) => JSON.parse(l).event),
      ['job', 'resource'],
    );

    const denied = await consoleCli(['commands', 'run-verb', 'vm', 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', 'stop', '--body', '{"input":{}}']);
    assert.equal(denied.code, 1);
    assert.match(denied.stderr, /403 iam\.denied: Your roles in Payments do not allow vm:stop\.\n  denied by console-iam: vm:stop on wfrn:\S+; role viewer; policy pol-viewer; statement read-only\n  you may ask for access \(the operator role allows it\)/);

    const usage = await consoleCli(['jobs', 'get']);
    assert.equal(usage.code, 2);
    assert.match(usage.stderr, /needs <jobId>/);

    // an access token the BFF no longer takes: refreshed, the rotated refresh token kept
    const before = JSON.parse(await readFile(path.join(dir, 'credentials.json'), 'utf8')).sessions[bff.url];
    bff.expireAccessTokens();
    const again = await consoleCli(['jobs', 'list']);
    assert.equal(again.code, 0, again.stderr);
    const afterRefresh = JSON.parse(await readFile(path.join(dir, 'credentials.json'), 'utf8')).sessions[bff.url];
    assert.notEqual(afterRefresh.refreshToken, before.refreshToken);

    const logout = await consoleCli(['logout']);
    assert.equal(logout.code, 0, logout.stderr);
    assert.equal(JSON.parse(logout.stdout).revoked, true);
    const gone = await consoleCli(['whoami']);
    assert.equal(gone.code, 1);
    assert.match(gone.stderr, /not signed in/);
  });

  test('help and version', async () => {
    const help = await consoleCli(['--help']);
    assert.equal(help.code, 0);
    assert.match(help.stdout, /^Groups, one per tag of the console's API:$/m);
    assert.equal((await consoleCli(['--version'])).stdout, 'console 0.1.0\n');
  });
});
