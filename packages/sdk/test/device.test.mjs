// The device authorization grant (RFC 8628) against a stub that follows it: the stub and the
// client share a clock, so the stub sees exactly when each poll lands.

import assert from 'node:assert/strict';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  DEVICE_CODE_GRANT,
  OAuthError,
  createConsoleClient,
  deviceLogin,
  pollDeviceAuthorization,
  refreshTokens,
  revokeToken,
  startDeviceAuthorization,
} from '@iaf/console-sdk';
import { fakeClock, startStubBff } from '../../../test/support/stub-bff.mjs';

const polls = (bff) => bff.requests.filter((r) => r.path === '/auth/token' && r.form?.grant_type === DEVICE_CODE_GRANT);

describe('the device authorization grant', () => {
  let bff;
  let clock;

  before(async () => {
    clock = fakeClock();
    bff = await startStubBff({ clock });
  });
  after(() => bff.close());
  beforeEach(() => {
    bff.reset();
    clock.waits.length = 0;
  });

  const options = (extra = {}) => ({ endpoint: bff.url, clientId: 'console-cli', now: clock.now, sleep: clock.sleep, ...extra });

  test('the device authorization answers the codes, the pages and the interval', async () => {
    const a = await startDeviceAuthorization(options({ userAgent: 'console-cli/0.1.0 (test)' }));
    assert.match(a.userCode, /^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$/);
    assert.equal(Buffer.from(a.deviceCode, 'base64url').length, 32);
    assert.equal(a.verificationUri, `${bff.url}/#/device`);
    assert.equal(a.verificationUriComplete, `${bff.url}/#/device?code=${a.userCode}`);
    assert.equal(a.expiresIn, 600);
    assert.equal(a.interval, 5);
    assert.equal(a.expiresAt, clock.now() + 600_000);
    const [request] = bff.requests;
    assert.equal(request.headers['content-type'], 'application/x-www-form-urlencoded');
    assert.deepEqual(request.form, { client_id: 'console-cli' });
    assert.equal(request.headers['user-agent'], 'console-cli/0.1.0 (test)');
  });

  test('another client id is invalid_client', async () => {
    await assert.rejects(startDeviceAuthorization(options({ clientId: 'console-web' })), (e) => e instanceof OAuthError && e.status === 400 && e.error === 'invalid_client');
  });

  test('polls every interval while pending, and answers the tokens once approved', async () => {
    const authorization = await startDeviceAuthorization(options());
    let pending = 0;
    const tokens = await pollDeviceAuthorization(
      options({
        authorization,
        onPending: () => {
          if (++pending === 2) bff.approve(authorization.userCode);
        },
      }),
    );
    assert.deepEqual(clock.waits, [5000, 5000, 5000]);
    assert.equal(polls(bff).length, 3);
    for (const poll of polls(bff)) {
      assert.deepEqual(poll.form, { grant_type: DEVICE_CODE_GRANT, device_code: authorization.deviceCode, client_id: 'console-cli' });
    }
    assert.equal(tokens.tokenType, 'Bearer');
    assert.equal(tokens.scope, 'console');
    assert.equal(tokens.clientId, 'console-cli');
    assert.equal(tokens.expiresAt, clock.now() + 900_000);
    assert.ok(tokens.refreshToken && tokens.refreshExpiresAt > tokens.expiresAt);
    // the stub saw no poll sooner than the interval
    assert.ok(![...bff.grants.values()].some((g) => g.slowDowns));
  });

  test('slow_down adds 5 seconds to the interval for this and every later poll', async () => {
    const authorization = await startDeviceAuthorization(options());
    let n = 0;
    bff.route('POST', '/auth/token', () => (++n === 2 ? { status: 400, body: { error: 'slow_down' } } : undefined));
    let pending = 0;
    await pollDeviceAuthorization(
      options({
        authorization,
        onPending: (state) => {
          if (!state.slowDown && ++pending === 2) bff.approve(authorization.userCode);
        },
      }),
    );
    // pending, slow_down, pending, approved
    assert.deepEqual(clock.waits, [5000, 5000, 10000, 10000]);
  });

  test('a poll sooner than the interval is answered slow_down by the BFF', async () => {
    const authorization = await startDeviceAuthorization(options());
    clock.t += 2000;
    const res = await fetch(`${bff.url}/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: DEVICE_CODE_GRANT, device_code: authorization.deviceCode, client_id: 'console-cli' }),
    });
    assert.equal(res.status, 400);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await res.json(), { error: 'slow_down' });
  });

  test('access_denied ends the polling', async () => {
    const authorization = await startDeviceAuthorization(options());
    await assert.rejects(
      pollDeviceAuthorization(options({ authorization, onPending: () => bff.deny(authorization.userCode) })),
      (e) => e instanceof OAuthError && e.error === 'access_denied',
    );
    assert.equal(polls(bff).length, 2);
  });

  test('expired_token ends the polling when the code is never approved', async () => {
    const authorization = await startDeviceAuthorization(options());
    await assert.rejects(pollDeviceAuthorization(options({ authorization })), (e) => e instanceof OAuthError && e.error === 'expired_token');
    // 600 s at 5 s a poll: the last poll lands at the expiry, and none after it
    assert.equal(polls(bff).length, 120);
    assert.ok(clock.now() >= authorization.expiresAt);
  });

  test('a device code is redeemed once', async () => {
    const authorization = await startDeviceAuthorization(options());
    bff.approve(authorization.userCode);
    await pollDeviceAuthorization(options({ authorization }));
    await assert.rejects(pollDeviceAuthorization(options({ authorization })), (e) => e.error === 'invalid_grant');
  });

  test('a connection failure doubles the interval', async () => {
    const authorization = await startDeviceAuthorization(options());
    bff.approve(authorization.userCode);
    let n = 0;
    const flaky = (url, init) => (++n === 1 ? Promise.reject(new TypeError('fetch failed')) : fetch(url, init));
    await pollDeviceAuthorization(options({ authorization, fetch: flaky }));
    assert.deepEqual(clock.waits, [5000, 10000]);
  });

  test('deviceLogin shows the codes, then polls', async () => {
    let shown;
    const tokens = await deviceLogin(
      options({
        onCode: (a) => {
          shown = a;
          bff.approve(a.userCode);
        },
      }),
    );
    assert.ok(shown.userCode && tokens.accessToken);
  });

  describe('refresh and revocation', () => {
    async function signedIn() {
      const authorization = await startDeviceAuthorization(options());
      bff.approve(authorization.userCode);
      return pollDeviceAuthorization(options({ authorization }));
    }

    test('a refresh answers new tokens and the old refresh token stops working', async () => {
      const first = await signedIn();
      const second = await refreshTokens(options({ refreshToken: first.refreshToken }));
      assert.notEqual(second.accessToken, first.accessToken);
      assert.notEqual(second.refreshToken, first.refreshToken);
      const refresh = bff.requests.at(-1);
      assert.deepEqual(refresh.form, { grant_type: 'refresh_token', refresh_token: first.refreshToken, client_id: 'console-cli' });
      const third = await refreshTokens(options({ refreshToken: second.refreshToken }));
      assert.ok(third.accessToken);
    });

    test('a rotated refresh token presented again revokes the whole API session', async () => {
      const first = await signedIn();
      const second = await refreshTokens(options({ refreshToken: first.refreshToken }));
      await assert.rejects(refreshTokens(options({ refreshToken: first.refreshToken })), (e) => e instanceof OAuthError && e.error === 'invalid_grant');
      await assert.rejects(refreshTokens(options({ refreshToken: second.refreshToken })), (e) => e.error === 'invalid_grant');
      const client = createConsoleClient({ endpoint: bff.url, credentials: second.accessToken });
      await assert.rejects(client.call('getSession'), (e) => e.status === 401 && e.error === 'invalid_token');
    });

    test('revoking ends the API session', async () => {
      const tokens = await signedIn();
      await revokeToken(options({ token: tokens.refreshToken }));
      const revoke = bff.requests.at(-1);
      assert.equal(revoke.path, '/auth/revoke');
      assert.deepEqual(revoke.form, { token: tokens.refreshToken, token_type_hint: 'refresh_token', client_id: 'console-cli' });
      const client = createConsoleClient({ endpoint: bff.url, credentials: tokens.accessToken });
      await assert.rejects(client.call('getSession'), (e) => e.status === 401);
      await assert.rejects(refreshTokens(options({ refreshToken: tokens.refreshToken })), (e) => e.error === 'invalid_grant');
    });
  });
});
