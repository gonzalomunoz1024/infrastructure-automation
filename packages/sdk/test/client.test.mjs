import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import { ConsoleApiError, createConsoleClient, OAuthError, refreshingCredentials, startDeviceAuthorization, pollDeviceAuthorization } from '@iaf/console-sdk';
import { fakeClock, job, startStubBff } from '../../../test/support/stub-bff.mjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Signs in at the stub as the device flow would, approving at once. */
async function signIn(bff, clock) {
  const authorization = await startDeviceAuthorization({ endpoint: bff.url, clientId: 'console-sdk', now: clock.now });
  bff.approve(authorization.userCode);
  return pollDeviceAuthorization({ endpoint: bff.url, clientId: 'console-sdk', authorization, now: clock.now, sleep: clock.sleep });
}

function apiCalls(bff, path) {
  return bff.requests.filter((r) => r.path === path);
}

function refreshes(bff) {
  return bff.requests.filter((r) => r.path === '/auth/token' && r.form?.grant_type === 'refresh_token');
}

describe('the client', () => {
  let bff;
  let clock;
  let tokens;

  before(async () => {
    clock = fakeClock();
    bff = await startStubBff({ clock });
  });
  after(() => bff.close());
  beforeEach(async () => {
    bff.reset();
    tokens = await signIn(bff, clock);
    bff.requests.length = 0;
  });

  function client(extra = {}) {
    let saved;
    const credentials = refreshingCredentials({ endpoint: bff.url, tokens, now: clock.now, onRefresh: (t) => (saved = t) });
    const c = createConsoleClient({ endpoint: bff.url, credentials, sleep: clock.sleep, now: clock.now, ...extra });
    return { c, credentials, saved: () => saved };
  }

  describe('bearer auth', () => {
    test('every call carries the access token, and the account and region when set', async () => {
      const { c } = client({ account: '111111111111', region: 'eu-west-1' });
      const session = await c.call('getSession');
      assert.equal(session.user.email, 'alice@example.com');
      const [call] = apiCalls(bff, '/console/v1/session');
      assert.equal(call.headers.authorization, `Bearer ${tokens.accessToken}`);
      assert.equal(call.headers['x-console-account'], '111111111111');
      assert.equal(call.headers['x-console-region'], 'eu-west-1');
      assert.equal(call.headers['idempotency-key'], undefined, 'a read carries no Idempotency-Key');
    });

    test('a call names its own account and region over the client\'s', async () => {
      const { c } = client({ account: '111111111111' });
      const session = await c.call('getSession', { account: '222222222222', region: 'us-east-1' });
      assert.equal(session.account.accountId, '222222222222');
      assert.equal(session.region, 'us-east-1');
    });

    test('a static token is sent as it is', async () => {
      const c = createConsoleClient({ endpoint: bff.url, credentials: tokens.accessToken });
      await c.call('getSession');
      assert.equal(apiCalls(bff, '/console/v1/session')[0].headers.authorization, `Bearer ${tokens.accessToken}`);
    });
  });

  describe('refresh', () => {
    test('a 401 invalid_token refreshes once and retries the call with the new token', async () => {
      const { c, saved } = client();
      bff.expireAccessTokens();
      const page = await c.call('listJobs');
      assert.equal(page.items.length, 5);
      assert.equal(refreshes(bff).length, 1);
      const calls = apiCalls(bff, '/console/v1/jobs');
      assert.equal(calls.length, 2);
      assert.notEqual(calls[0].headers.authorization, calls[1].headers.authorization);
      assert.equal(calls[1].headers.authorization, `Bearer ${saved().accessToken}`);
      assert.notEqual(saved().refreshToken, tokens.refreshToken, 'the refresh token rotated');
      assert.equal(refreshes(bff)[0].form.refresh_token, tokens.refreshToken);
      assert.equal(refreshes(bff)[0].form.client_id, 'console-sdk');
    });

    test('a second 401 after the refresh is not refreshed again', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/jobs', () => ({ status: 401, headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' }, body: { error: 'invalid_token' } }));
      await assert.rejects(c.call('listJobs'), (e) => e instanceof ConsoleApiError && e.status === 401 && e.error === 'invalid_token');
      assert.equal(refreshes(bff).length, 1);
      assert.equal(apiCalls(bff, '/console/v1/jobs').length, 2);
    });

    test('a 401 that is not invalid_token is not refreshed', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/preferences', () => ({ status: 401, headers: { 'WWW-Authenticate': 'Bearer' }, body: { error: 'unauthorized' } }));
      await assert.rejects(c.call('getPreferences'), (e) => e.status === 401 && e.error === 'unauthorized');
      assert.equal(refreshes(bff).length, 0);
    });

    test('calls that need a refresh at the same time share one, so the rotated token is never reused', async () => {
      const { c } = client();
      bff.expireAccessTokens();
      const pages = await Promise.all(Array.from({ length: 6 }, () => c.call('listJobs')));
      assert.ok(pages.every((p) => p.items.length === 5));
      assert.equal(refreshes(bff).length, 1);
      assert.ok([...bff.sessions.values()].every((s) => !s.reuseDetected));
    });

    test('presenting a rotated refresh token again ends the API session', async () => {
      const { c, saved } = client();
      bff.expireAccessTokens();
      await c.call('listJobs');
      const replay = createConsoleClient({
        endpoint: bff.url,
        credentials: refreshingCredentials({ endpoint: bff.url, tokens: { ...tokens, accessToken: 'stale' }, now: clock.now }),
      });
      await assert.rejects(replay.call('listJobs'), (e) => e instanceof ConsoleApiError && e.status === 401 && e.cause instanceof OAuthError && e.cause.error === 'invalid_grant');
      // the whole API session is revoked: the fresh tokens stop working too
      const fresh = createConsoleClient({ endpoint: bff.url, credentials: saved().accessToken });
      await assert.rejects(fresh.call('listJobs'), (e) => e.status === 401);
    });

    test('an access token about to expire is refreshed before the call', async () => {
      const { c } = client();
      clock.t += 899 * 1000; // within the minute before the 900 s expiry
      await c.call('listJobs');
      assert.equal(refreshes(bff).length, 1);
      assert.equal(apiCalls(bff, '/console/v1/jobs').length, 1, 'no 401 round trip');
    });
  });

  describe('idempotency keys', () => {
    test('every write carries a UUID, and a call may name its own', async () => {
      const { c } = client();
      await c.call('createResource', { path: { type: 'vm' }, body: { input: { name: 'web-9' } } });
      await c.call('cancelJob', { path: { jobId: 'job-1' } });
      await c.call('createResource', { path: { type: 'vm' }, body: { input: { name: 'web-9' } }, idempotencyKey: 'mine-1' });
      const [create, cancel, own] = bff.requests.filter((r) => r.method === 'POST' && r.path.startsWith('/console/v1/'));
      assert.match(create.headers['idempotency-key'], UUID);
      assert.match(cancel.headers['idempotency-key'], UUID);
      assert.notEqual(create.headers['idempotency-key'], cancel.headers['idempotency-key']);
      assert.equal(own.headers['idempotency-key'], 'mine-1');
    });

    test('PUT, PATCH and DELETE carry one too', async () => {
      const { c } = client();
      bff.route('PUT', '/console/v1/preferences', () => ({ status: 200, body: {} }));
      bff.route('PATCH', /^\/console\/v1\/r\/vm\/.+$/, () => ({ status: 202, body: { jobId: 'j', requestId: 'r' } }));
      bff.route('DELETE', '/console/v1/alarms/a-1', () => ({ status: 204 }));
      await c.call('putPreferences', { body: {} });
      await c.call('updateResource', { path: { type: 'vm', wfrn: 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1' }, body: { input: {} } });
      assert.equal(await c.call('deleteAlarm', { path: { alarmId: 'a-1' } }), undefined);
      for (const method of ['PUT', 'PATCH', 'DELETE']) {
        assert.match(bff.requests.find((r) => r.method === method).headers['idempotency-key'], UUID, method);
      }
    });

    test('a retried write and a write retried after a refresh keep their key', async () => {
      const { c } = client();
      let n = 0;
      bff.route('POST', '/console/v1/r/db', () => (++n === 1 ? { status: 429, headers: { 'Retry-After': '1' }, body: { error: 'quota.exceeded' } } : undefined));
      bff.expireAccessTokens();
      const accepted = await c.call('createResource', { path: { type: 'db' }, body: { input: { name: 'orders' } } });
      assert.equal(accepted.jobId.startsWith('job-'), true);
      const writes = apiCalls(bff, '/console/v1/r/db');
      assert.equal(writes.length, 3, 'a 401, a 429, then the answer');
      assert.equal(new Set(writes.map((w) => w.headers['idempotency-key'])).size, 1);
    });
  });

  describe('retries', () => {
    test('a read is retried after a 429 or a 503, waiting Retry-After', async () => {
      const { c } = client();
      const answers = [
        { status: 429, headers: { 'Retry-After': '2' }, body: { error: 'quota.exceeded' } },
        { status: 503, headers: { 'Retry-After': '1' }, body: { error: 'unavailable' } },
      ];
      bff.route('GET', '/console/v1/jobs', () => answers.shift());
      clock.waits.length = 0;
      const page = await c.call('listJobs');
      assert.equal(page.items.length, 5);
      assert.deepEqual(clock.waits, [2000, 1000]);
    });

    test('Retry-After may be a date', async () => {
      const { c } = client();
      const answers = [{ status: 503, headers: { 'Retry-After': new Date(clock.now() + 3000).toUTCString() }, body: { error: 'unavailable' } }];
      bff.route('GET', '/console/v1/jobs', () => answers.shift());
      clock.waits.length = 0;
      await c.call('listJobs');
      assert.equal(clock.waits.length, 1);
      assert.ok(clock.waits[0] >= 2000 && clock.waits[0] <= 3000, String(clock.waits[0]));
    });

    test('at most three retries, then the 429 is thrown with its Retry-After', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/approvals', () => ({ status: 429, headers: { 'Retry-After': '1' }, body: { error: 'quota.exceeded' } }));
      clock.waits.length = 0;
      await assert.rejects(c.call('listApprovals'), (e) => e instanceof ConsoleApiError && e.status === 429 && e.retryAfter === 1);
      assert.equal(apiCalls(bff, '/console/v1/approvals').length, 4);
      assert.deepEqual(clock.waits, [1000, 1000, 1000]);
    });

    test('without Retry-After the waits grow: 1, 2, 4 s', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/accounts', () => ({ status: 503, body: { error: 'unavailable' } }));
      clock.waits.length = 0;
      await assert.rejects(c.call('listAccounts'), (e) => e.status === 503);
      assert.deepEqual(clock.waits, [1000, 2000, 4000]);
    });

    test('a write that takes no Idempotency-Key is not retried', async () => {
      const { c } = client();
      bff.route('POST', '/console/v1/assistant/turns', () => ({ status: 429, headers: { 'Retry-After': '1' }, body: { error: 'assistant.rate-limited' } }));
      await assert.rejects(c.call('askAssistant', { body: { text: 'why did my job fail?' } }), (e) => e.status === 429);
      assert.equal(apiCalls(bff, '/console/v1/assistant/turns').length, 1);
    });

    test('a write that takes an Idempotency-Key is retried', async () => {
      const { c } = client();
      const answers = [{ status: 503, headers: { 'Retry-After': '1' }, body: { error: 'unavailable' } }];
      bff.route('POST', '/console/v1/r/cache', () => answers.shift());
      await c.call('createResource', { path: { type: 'cache' }, body: { input: { name: 'c' } } });
      assert.equal(apiCalls(bff, '/console/v1/r/cache').length, 2);
    });

    test('a Retry-After longer than the longest wait is not waited for', async () => {
      const { c } = client({ maxRetryDelayMs: 10_000 });
      bff.route('GET', '/console/v1/regions', () => ({ status: 429, headers: { 'Retry-After': '3600' }, body: { error: 'quota.exceeded' } }));
      await assert.rejects(c.call('listRegions'), (e) => e.status === 429 && e.retryAfter === 3600);
      assert.equal(apiCalls(bff, '/console/v1/regions').length, 1);
    });

    test('retries: 0 turns them off', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/types', () => ({ status: 503, body: { error: 'unavailable' } }));
      await assert.rejects(c.call('listTypes', { retries: 0 }), (e) => e.status === 503);
      assert.equal(apiCalls(bff, '/console/v1/types').length, 1);
    });
  });

  describe('paging', () => {
    test('paginate follows next until it is null', async () => {
      const { c } = client();
      const ids = [];
      for await (const j of c.paginate('listJobs', { query: { limit: 2 } })) ids.push(j.jobId);
      assert.deepEqual(ids, ['job-1', 'job-2', 'job-3', 'job-4', 'job-5']);
      const calls = apiCalls(bff, '/console/v1/jobs');
      assert.equal(calls.length, 3);
      assert.equal(calls[0].query.after, undefined);
      assert.ok(calls[1].query.after && calls[2].query.after);
      assert.ok(calls.every((call) => call.query.limit === '2'));
    });

    test('pages answers each page, query kept', async () => {
      const { c } = client();
      bff.setJobs(Array.from({ length: 9 }, (_, i) => job(i + 1)));
      try {
        const sizes = [];
        for await (const p of c.pages('listJobs', { query: { status: 'running', limit: 2 } })) sizes.push(p.items.length);
        assert.deepEqual(sizes, [2, 2, 1]);
        assert.ok(apiCalls(bff, '/console/v1/jobs').every((call) => call.query.status === 'running'));
      } finally {
        bff.setJobs(Array.from({ length: 5 }, (_, i) => job(i + 1)));
      }
    });

    test('a cursor answered twice stops the iteration', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/alarms', () => ({ status: 200, body: { items: [{ alarmId: 'a' }], next: 'same' } }));
      await assert.rejects(async () => {
        for await (const _ of c.paginate('listAlarms')) void _;
      }, /answered the cursor same twice/);
    });
  });

  describe('typed errors', () => {
    test('a 403 carries the Denial', async () => {
      const { c } = client();
      const wfrn = 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1';
      await assert.rejects(c.call('runVerb', { path: { type: 'vm', wfrn, verb: 'stop' }, body: { input: {} } }), (e) => {
        assert.ok(e instanceof ConsoleApiError);
        assert.equal(e.status, 403);
        assert.equal(e.error, 'iam.denied');
        assert.equal(e.denied.layer, 'console-iam');
        assert.equal(e.denied.action, 'vm:stop');
        assert.equal(e.denied.resource, wfrn);
        assert.equal(e.denied.suggestedRole, 'operator');
        assert.equal(e.requestable, true);
        assert.equal(e.stepUp, false);
        assert.match(e.message, /answered 403 iam\.denied: Your roles in Payments do not allow vm:stop/);
        return true;
      });
      // the wfrn travels percent-encoded
      assert.ok(bff.requests.at(-1).path.includes(encodeURIComponent(wfrn)));
    });

    test('a step-up refusal says so', async () => {
      const { c } = client();
      await assert.rejects(c.call('runVerb', { path: { type: 'vm', wfrn: 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', verb: 'delete' }, body: { input: {}, confirm: true } }), (e) => e.stepUp && e.detail.action === 'vm:delete');
    });

    test('a 400 carries its problems', async () => {
      const { c } = client();
      await assert.rejects(c.call('createResource', { path: { type: 'vm' }, body: {} }), (e) => {
        assert.equal(e.error, 'validation.failed');
        assert.deepEqual(e.problems, [{ code: 'required', field: '/input', message: 'input is required' }]);
        assert.match(e.message, /\/input: input is required/);
        return true;
      });
    });

    test('a body that is not JSON keeps its text', async () => {
      const { c } = client();
      bff.route('GET', '/console/v1/insights', () => ({ status: 502, headers: { 'Content-Type': 'text/html' }, raw: '<html>bad gateway</html>' }));
      await assert.rejects(c.call('getInsights', { retries: 0 }), (e) => e.status === 502 && e.error === 'http.502' && e.body === '<html>bad gateway</html>');
    });
  });

  describe('answers', () => {
    test('a pattern parameter, booleans and the query are sent as named', async () => {
      const { c } = client();
      const page = await c.call('listResources', { path: { type: 'vm' }, query: { 'tag.env': 'staging', drift: false, limit: 3 } });
      assert.equal(page.items.length, 3);
      assert.deepEqual(page.items[0].tags, { env: 'staging' });
      const [call] = apiCalls(bff, '/console/v1/r/vm');
      assert.deepEqual(call.query, { 'tag.env': 'staging', drift: 'false', limit: '3' });
    });

    test('CSV comes back as text, and a 202 without a body as undefined', async () => {
      const { c } = client();
      const csv = await c.call('exportChargeback', { query: { month: '2026-09' } });
      assert.equal(csv, 'month,costCentre,amount\n2026-09,cc-1,12.50\n');
      const res = await c.send('cancelJob', { path: { jobId: 'job-1' } });
      assert.equal(res.status, 202);
      assert.equal(res.data, undefined);
    });

    test('events come as they are sent, multi-line data joined', async () => {
      const { c } = client();
      const events = [];
      for await (const e of c.events('streamEvents', { query: { types: 'job,resource' } })) events.push(e);
      assert.deepEqual(
        events.map((e) => [e.event, e.id]),
        [
          ['job', 'c-1'],
          ['resource', 'c-2'],
        ],
      );
      assert.deepEqual(JSON.parse(events[1].data), { wfrn: 'wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1', state: 'active' });
      assert.equal(apiCalls(bff, '/console/v1/events')[0].headers.accept, 'text/event-stream');
    });

    test('request reaches any path', async () => {
      const { c } = client();
      const res = await c.request('GET', '/console/v1/jobs', { query: { limit: 1 } });
      assert.equal(res.status, 200);
      assert.equal(res.data.items.length, 1);
    });

    test('an unknown operation and a missing path parameter are refused before any call', async () => {
      const { c } = client();
      await assert.rejects(c.call('noSuchOperation'), /no operation noSuchOperation/);
      await assert.rejects(c.call('getJob', { path: {} }), /needs the path parameter jobId/);
    });
  });
});
