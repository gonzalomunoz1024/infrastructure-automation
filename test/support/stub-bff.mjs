// A stub of the console BFF for the tests: the device authorization grant as RFC 8628 and the
// hub's decision 31.1 have it, rotating refresh tokens with reuse detection, revocation, bearer
// checks on /console/v1, and a few API routes. The real BFF is never started.
//
//   const bff = await startStubBff({ clock });   // clock.now() in ms; tests may make time pass
//   bff.approve(userCode); bff.route('GET', '/console/v1/jobs', (req) => ({ status: 429 }));
//   bff.requests  every request seen: { method, path, query, headers, form, body }

import { createHash, randomBytes } from 'node:crypto';
import http from 'node:http';

const USER_CODE_LETTERS = 'BCDFGHJKLMNPQRSTVWXZ';
const DEVICE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code';
const CLIENTS = new Set(['console-cli', 'console-sdk']);

const sha256 = (s) => createHash('sha256').update(s).digest('hex');
const b64url = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url');

export const ALICE = {
  subject: 'idp|alice',
  user: { userId: 'u-alice', email: 'alice@example.com', name: 'Alice Example', groups: ['platform-team'] },
  accounts: [
    { accountId: '111111111111', name: 'Payments', defaultRegion: 'eu-west-1', roles: ['operator'] },
    { accountId: '222222222222', name: 'Sandbox', defaultRegion: 'us-east-1', roles: ['viewer'] },
  ],
};

export async function startStubBff(options = {}) {
  const clock = options.clock ?? { now: () => Date.now() };
  const interval = options.interval ?? 5;
  const deviceExpiresIn = options.deviceExpiresIn ?? 600;
  const accessTtl = options.accessTtl ?? 900;
  const refreshTtl = options.refreshTtl ?? 12 * 3600;
  const autoApproveAfter = options.autoApproveAfter; // pending polls before the stub approves by itself
  const grants = new Map(); // sha256(device code) -> grant
  const sessions = new Map(); // id -> API session
  const accessTokens = new Map(); // token -> { sid, exp }
  const routes = [];
  const requests = [];
  const idempotency = new Map();
  let jobs = options.jobs ?? Array.from({ length: 5 }, (_, i) => job(i + 1));

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://stub');
    const raw = await readAll(req);
    const contentType = req.headers['content-type'] ?? '';
    const record = {
      method: req.method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      search: url.search,
      headers: req.headers,
      form: contentType.startsWith('application/x-www-form-urlencoded') ? Object.fromEntries(new URLSearchParams(raw)) : undefined,
      body: contentType.startsWith('application/json') && raw ? JSON.parse(raw) : raw || undefined,
      at: clock.now(),
    };
    requests.push(record);
    try {
      const answer = await dispatch(record, url);
      send(res, answer);
    } catch (e) {
      send(res, { status: 500, body: { error: 'stub.failed', detail: { message: String(e?.stack ?? e) } } });
    }
  });

  async function dispatch(r, url) {
    for (const route of routes) {
      if (route.method === r.method && (typeof route.path === 'string' ? route.path === r.path : route.path.test(r.path))) {
        const answer = await route.handler(r, { session: () => bearer(r), url });
        if (answer !== undefined) return answer;
      }
    }
    if (r.method === 'POST' && r.path === '/auth/device') return deviceAuthorization(r);
    if (r.method === 'POST' && r.path === '/auth/token') return token(r);
    if (r.method === 'POST' && r.path === '/auth/revoke') return revoke(r);
    if (r.method === 'GET' && r.path === '/actuator/health') return { status: 200, body: { status: 'UP' } };
    if (r.path.startsWith('/console/v1/')) {
      const who = bearer(r);
      if (who.refusal) return who.refusal;
      return api(r, who);
    }
    return { status: 404, body: { error: 'not-found' } };
  }

  // ---- RFC 8628 §3.1 and §3.2 -----------------------------------------------------------------
  function deviceAuthorization(r) {
    const clientId = r.form?.client_id;
    if (!CLIENTS.has(clientId)) return { status: 400, body: { error: 'invalid_client' } };
    const deviceCode = randomBytes(32).toString('base64url');
    const letters = Array.from(randomBytes(8), (b) => USER_CODE_LETTERS[b % USER_CODE_LETTERS.length]).join('');
    const userCode = `${letters.slice(0, 4)}-${letters.slice(4)}`;
    const now = clock.now();
    const grant = {
      userCode,
      clientId,
      status: 'pending',
      interval,
      issuedAt: now,
      lastPollAt: now,
      expiresAt: now + deviceExpiresIn * 1000,
      polls: 0,
      redeemed: false,
      userAgent: r.headers['user-agent'],
    };
    grants.set(sha256(deviceCode), grant);
    const verificationUri = options.verificationUri ?? `${base}/#/device`;
    return {
      status: 200,
      headers: { 'Cache-Control': 'no-store' },
      body: {
        device_code: deviceCode,
        user_code: userCode,
        verification_uri: verificationUri,
        verification_uri_complete: `${verificationUri}?code=${userCode}`,
        expires_in: deviceExpiresIn,
        interval,
      },
    };
  }

  // ---- RFC 8628 §3.4 and §3.5, refresh with rotation ---------------------------------------------
  function token(r) {
    const form = r.form ?? {};
    const oauth = (status, error, description) => ({ status, headers: { 'Cache-Control': 'no-store' }, body: { error, ...(description ? { error_description: description } : {}) } });
    if (form.grant_type === DEVICE_GRANT) {
      const grant = grants.get(sha256(form.device_code ?? ''));
      if (!grant) return oauth(400, 'invalid_grant', 'unknown device code');
      if (grant.clientId !== form.client_id) return oauth(401, 'invalid_client');
      const now = clock.now();
      if (grant.redeemed) return oauth(400, 'invalid_grant', 'the device code was redeemed');
      if (now >= grant.expiresAt) return oauth(400, 'expired_token');
      if (grant.status === 'denied') return oauth(400, 'access_denied');
      if (grant.status === 'pending') {
        // polling sooner than the interval: slow_down, and 5 s more for this grant
        if (now - grant.lastPollAt < grant.interval * 1000) {
          grant.interval += 5;
          grant.lastPollAt = now;
          grant.slowDowns = (grant.slowDowns ?? 0) + 1;
          return oauth(400, 'slow_down');
        }
        grant.lastPollAt = now;
        grant.polls++;
        if (autoApproveAfter !== undefined && grant.polls > autoApproveAfter) {
          grant.status = 'approved';
        } else {
          return oauth(400, 'authorization_pending');
        }
      }
      grant.redeemed = true;
      return { status: 200, headers: { 'Cache-Control': 'no-store' }, body: openSession(grant.clientId, grant.userAgent) };
    }
    if (form.grant_type === 'refresh_token') {
      const hash = sha256(form.refresh_token ?? '');
      const session = [...sessions.values()].find((s) => s.refreshHash === hash || s.rotated.has(hash));
      if (!session) return oauth(400, 'invalid_grant', 'unknown refresh token');
      if (session.rotated.has(hash)) {
        session.revokedAt = clock.now();
        session.reuseDetected = true;
        return oauth(400, 'invalid_grant', 'the refresh token was already used; the API session is revoked');
      }
      if (session.revokedAt !== undefined || clock.now() >= session.expiresAt) return oauth(400, 'invalid_grant', 'the API session has ended');
      if (session.clientId !== form.client_id) return oauth(401, 'invalid_client');
      session.rotated.add(session.refreshHash);
      session.refreshes++;
      return { status: 200, headers: { 'Cache-Control': 'no-store' }, body: issue(session) };
    }
    return oauth(400, 'unsupported_grant_type');
  }

  function revoke(r) {
    const hash = sha256(r.form?.token ?? '');
    for (const s of sessions.values()) {
      if (s.refreshHash === hash || s.rotated.has(hash)) s.revokedAt = clock.now();
    }
    const access = accessTokens.get(r.form?.token);
    if (access) sessions.get(access.sid).revokedAt = clock.now();
    return { status: 200, body: undefined };
  }

  function openSession(clientId, userAgent) {
    const id = `aps_${randomBytes(10).toString('hex').toUpperCase()}`;
    const now = clock.now();
    const session = {
      id,
      clientId,
      subject: ALICE.subject,
      createdAt: now,
      authTime: Math.floor(now / 1000),
      expiresAt: now + refreshTtl * 1000,
      rotated: new Set(),
      refreshes: 0,
      account: ALICE.accounts[0].accountId,
      region: ALICE.accounts[0].defaultRegion,
      userAgent,
    };
    sessions.set(id, session);
    return issue(session);
  }

  function issue(session) {
    const now = clock.now();
    const refreshToken = randomBytes(32).toString('base64url');
    session.refreshHash = sha256(refreshToken);
    const exp = Math.floor(now / 1000) + accessTtl;
    const claims = {
      iss: 'https://console.example.com',
      aud: 'console-api',
      sub: session.subject,
      sid: session.id,
      client_id: session.clientId,
      auth_time: session.authTime,
      iat: Math.floor(now / 1000),
      exp,
      jti: randomBytes(8).toString('hex'),
    };
    const accessToken = `${b64url({ alg: 'ES256', typ: 'at+jwt', kid: 'stub' })}.${b64url(claims)}.${randomBytes(16).toString('base64url')}`;
    accessTokens.set(accessToken, { sid: session.id, exp: exp * 1000 });
    return {
      access_token: accessToken,
      token_type: 'Bearer',
      expires_in: accessTtl,
      refresh_token: refreshToken,
      refresh_expires_in: Math.max(0, Math.floor((session.expiresAt - now) / 1000)),
      scope: 'console',
    };
  }

  // ---- bearer calls ------------------------------------------------------------------------------
  function bearer(r) {
    const header = r.headers.authorization ?? '';
    const invalid = { status: 401, headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' }, body: { error: 'invalid_token' } };
    if (!header.startsWith('Bearer ')) return { refusal: { status: 401, headers: { 'WWW-Authenticate': 'Bearer' }, body: { error: 'unauthorized' } } };
    const access = accessTokens.get(header.slice(7));
    if (!access || clock.now() >= access.exp) return { refusal: invalid };
    const session = sessions.get(access.sid);
    if (!session || session.revokedAt !== undefined || clock.now() >= session.expiresAt) return { refusal: invalid };
    const account = r.headers['x-console-account'] ?? session.account;
    const membership = ALICE.accounts.find((a) => a.accountId === account);
    if (!membership) {
      return {
        refusal: {
          status: 403,
          body: {
            error: 'account.not-member',
            denied: { layer: 'console-iam', action: 'console:use', resource: `account:${account}`, reason: `You are not a member of account ${account}.`, requestable: false },
          },
        },
      };
    }
    return { session, account: membership, region: r.headers['x-console-region'] ?? session.region };
  }

  function api(r, who) {
    const p = r.path;
    if (r.method === 'GET' && p === '/console/v1/session') {
      return {
        status: 200,
        body: {
          user: ALICE.user,
          account: who.account,
          region: who.region,
          csrfToken: 'not-for-bearer-callers',
          expiresAt: new Date(who.session.expiresAt).toISOString(),
        },
      };
    }
    if (r.method === 'GET' && p === '/console/v1/jobs') return page(jobs.filter((j) => !r.query.status || j.status === r.query.status), r.query);
    let m = /^\/console\/v1\/jobs\/([^/:]+)$/.exec(p);
    if (r.method === 'GET' && m) {
      const found = jobs.find((j) => j.jobId === decodeURIComponent(m[1]));
      return found ? { status: 200, body: { ...found, steps: [] } } : { status: 404, body: { error: 'job.not-found' } };
    }
    m = /^\/console\/v1\/jobs\/([^/:]+):cancel$/.exec(p);
    if (r.method === 'POST' && m) return { status: 202 };
    m = /^\/console\/v1\/r\/([^/:]+)$/.exec(p);
    if (r.method === 'GET' && m) {
      const tags = Object.fromEntries(Object.entries(r.query).filter(([k]) => k.startsWith('tag.')).map(([k, v]) => [k.slice(4), v]));
      const items = Array.from({ length: 7 }, (_, i) => ({
        wfrn: `wfrn:iaf:compute:eu-west-1:${who.account.accountId}:${m[1]}/web-${i + 1}`,
        type: m[1],
        service: 'compute',
        name: `web-${i + 1}`,
        state: i === 6 ? 'failed' : 'active',
        version: 1,
        attributes: { size: 'small' },
        tags: Object.keys(tags).length ? tags : { env: 'prod' },
      }));
      return page(items.filter((x) => !r.query.state || x.state === r.query.state), r.query);
    }
    if (r.method === 'POST' && m) {
      const key = r.headers['idempotency-key'];
      if (!key) return { status: 400, body: { error: 'idempotency-key.missing' } };
      if (!r.body?.input) return { status: 400, body: { error: 'validation.failed', problems: [{ code: 'required', field: '/input', message: 'input is required' }] } };
      if (!idempotency.has(key)) idempotency.set(key, { jobId: `job-${idempotency.size + 1}`, requestId: `req-${idempotency.size + 1}`, wfrn: `wfrn:iaf:compute:eu-west-1:${who.account.accountId}:${m[1]}/${r.body.input.name ?? 'new'}` });
      return { status: 202, body: idempotency.get(key) };
    }
    m = /^\/console\/v1\/r\/([^/:]+)\/([^/]+):([a-z-]+)$/.exec(p);
    if (r.method === 'POST' && m) {
      if (m[3] === 'delete') {
        return { status: 403, body: { error: 'step-up.required', detail: { stepUp: true, action: `${m[1]}:delete`, login: '/auth/login?stepUp=true', message: 'Deleting needs a fresh sign-in: run `console login` again' } } };
      }
      return {
        status: 403,
        body: {
          error: 'iam.denied',
          denied: {
            layer: 'console-iam',
            action: `${m[1]}:${m[3]}`,
            resource: decodeURIComponent(m[2]),
            reason: `Your roles in ${who.account.name} do not allow ${m[1]}:${m[3]}.`,
            role: 'viewer',
            policyId: 'pol-viewer',
            statement: 'read-only',
            requestable: true,
            suggestedRole: 'operator',
          },
        },
      };
    }
    if (r.method === 'GET' && p === '/console/v1/events') {
      return {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
        raw: 'retry: 3000\n\n: a comment\nid: c-1\nevent: job\ndata: {"jobId":"job-1","status":"running"}\n\nid: c-2\nevent: resource\ndata: {"wfrn":"wfrn:iaf:compute:eu-west-1:111111111111:vm/web-1",\ndata: "state":"active"}\n\n',
      };
    }
    if (r.method === 'GET' && p === '/console/v1/cost/chargeback.csv') {
      return { status: 200, headers: { 'Content-Type': 'text/csv' }, raw: `month,costCentre,amount\n${r.query.month},cc-1,12.50\n` };
    }
    return { status: 404, body: { error: 'not-found' } };
  }

  // ---------------------------------------------------------------------------------------------
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;

  return {
    url: base,
    requests,
    sessions,
    grants,
    clock,
    /** Approves the grant of a user code, as the person would on the device page. */
    approve(userCode) {
      grantOf(userCode).status = 'approved';
    },
    deny(userCode) {
      grantOf(userCode).status = 'denied';
    },
    /** Answers the requests matching `method` and `path` (a string or RegExp) first; `undefined` falls through. */
    route(method, path, handler) {
      routes.unshift({ method, path, handler });
    },
    /** Forgets every route a test added, and the requests seen. */
    reset() {
      routes.length = 0;
      requests.length = 0;
    },
    setJobs(list) {
      jobs = list;
    },
    /** Lets every access token expire at once. */
    expireAccessTokens() {
      for (const t of accessTokens.values()) t.exp = 0;
    },
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(resolve));
    },
  };

  function grantOf(userCode) {
    const grant = [...grants.values()].find((g) => g.userCode === userCode);
    if (!grant) throw new Error(`no grant for ${userCode}`);
    return grant;
  }
}

export function job(n, status = n % 2 ? 'running' : 'succeeded') {
  return { jobId: `job-${n}`, workflow: 'vm-create', title: `Create web-${n}`, status, requester: 'alice@example.com', startedAt: '2026-10-23T10:00:00Z' };
}

/** A `{ items, next }` page of `items`, by `limit` (50, at most 200) and `after` (an index here). */
export function page(items, query) {
  const limit = Math.min(200, Math.max(1, Number(query.limit ?? 50)));
  const start = query.after ? Number(Buffer.from(query.after, 'base64url').toString()) : 0;
  const slice = items.slice(start, start + limit);
  const next = start + limit < items.length ? Buffer.from(String(start + limit)).toString('base64url') : null;
  return { status: 200, body: { items: slice, next } };
}

function send(res, answer) {
  const headers = { ...(answer.headers ?? {}) };
  let payload = '';
  if (answer.raw !== undefined) {
    payload = answer.raw;
  } else if (answer.body !== undefined) {
    payload = JSON.stringify(answer.body);
    headers['Content-Type'] ??= 'application/json';
  }
  res.writeHead(answer.status ?? 200, headers);
  res.end(payload);
}

function readAll(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** A clock the tests move by hand; `sleep` makes the time pass at once and records each wait. */
export function fakeClock(start = Date.parse('2026-10-23T12:00:00Z')) {
  const clock = {
    t: start,
    waits: [],
    now: () => clock.t,
    sleep: async (ms) => {
      clock.waits.push(ms);
      clock.t += ms;
    },
  };
  return clock;
}
