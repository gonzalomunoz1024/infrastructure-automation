// Signing in to the BFF with the OAuth 2.0 device authorization grant (RFC 8628), refreshing with
// rotating refresh tokens, and revoking (RFC 7009), as decision 31.1 of the hub has them.
//
//   POST /auth/device  client_id                                   -> the device and user codes
//   POST /auth/token   grant_type=urn:ietf:params:oauth:grant-type:device_code, device_code, client_id
//   POST /auth/token   grant_type=refresh_token, refresh_token, client_id
//   POST /auth/revoke  token, token_type_hint=refresh_token
//
// All three are form-encoded; /auth/token answers RFC 6749 §5.2 errors `{ error, error_description }`.
// The device endpoints are not in the pinned contract yet: this follows the RFC and the decision.

import { OAuthError } from './errors.js';

export const DEVICE_CODE_GRANT = 'urn:ietf:params:oauth:grant-type:device_code';

/** The client ids the BFF accepts at /auth/device. */
export type ConsoleClientId = 'console-cli' | 'console-sdk';

/** What `POST /auth/device` answers, with the moment the codes expire. */
export interface DeviceAuthorization {
  deviceCode: string;
  /** Eight letters written `XXXX-XXXX`, for the person to type at `verificationUri`. */
  userCode: string;
  verificationUri: string;
  /** `verificationUri` with the code in it, for a link or a QR code. */
  verificationUriComplete?: string;
  /** Seconds the codes live (600). */
  expiresIn: number;
  /** Seconds to wait between polls (5); `slow_down` adds 5. */
  interval: number;
  /** When the codes expire, in milliseconds since the epoch. */
  expiresAt: number;
}

/** An access token and the refresh token that renews it. */
export interface TokenSet {
  accessToken: string;
  tokenType: string;
  /** The refresh token; it rotates on every refresh, and a rotated one presented again ends the API session. */
  refreshToken?: string;
  /** When the access token expires, in milliseconds since the epoch. */
  expiresAt?: number;
  /** When the refresh token expires, in milliseconds since the epoch. */
  refreshExpiresAt?: number;
  scope?: string;
  /** The client the tokens were issued to: a refresh must name it. */
  clientId?: string;
}

export interface AuthOptions {
  /** The BFF's address, such as `https://console.example.com`. */
  endpoint: string;
  clientId?: ConsoleClientId | (string & {});
  fetch?: typeof fetch;
  /** Sent as `User-Agent` where the platform allows it; the approval page shows it. */
  userAgent?: string;
  now?: () => number;
  signal?: AbortSignal;
}

export interface PollOptions extends AuthOptions {
  authorization: DeviceAuthorization;
  /** Waits; a test makes time pass at once. */
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  /** Told after each poll that is still pending, with the interval in force. */
  onPending?: (state: { polls: number; intervalMs: number; slowDown: boolean }) => void;
}

/** RFC 8628 §3.1: asks the BFF for a device code and a user code. */
export async function startDeviceAuthorization(options: AuthOptions & { scope?: string }): Promise<DeviceAuthorization> {
  const now = options.now ?? Date.now;
  const form: Record<string, string> = { client_id: options.clientId ?? 'console-sdk' };
  if (options.scope) form.scope = options.scope;
  const res = await postForm(options, '/auth/device', form);
  const body = await readJson(res);
  if (!res.ok) throw oauthError(res.status, body);
  const b = body as Record<string, unknown>;
  if (typeof b.device_code !== 'string' || typeof b.user_code !== 'string' || typeof b.verification_uri !== 'string') {
    throw new OAuthError(res.status, 'invalid_response', 'the device authorization answer has no device_code, user_code or verification_uri');
  }
  const expiresIn = positiveNumber(b.expires_in) ?? 600;
  return {
    deviceCode: b.device_code,
    userCode: b.user_code,
    verificationUri: b.verification_uri,
    verificationUriComplete: typeof b.verification_uri_complete === 'string' ? b.verification_uri_complete : undefined,
    expiresIn,
    interval: positiveNumber(b.interval) ?? 5,
    expiresAt: now() + expiresIn * 1000,
  };
}

/**
 * RFC 8628 §3.4 and §3.5: polls the token endpoint until the person approves or denies the grant,
 * or it expires. Waits `interval` seconds before each poll; `slow_down` adds 5 seconds to the
 * interval for this and every later poll; a connection failure or a 5xx doubles it. Throws an
 * OAuthError: `access_denied`, `expired_token`, or what else the BFF answered.
 */
export async function pollDeviceAuthorization(options: PollOptions): Promise<TokenSet> {
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? defaultSleep;
  const { authorization } = options;
  const clientId = options.clientId ?? 'console-sdk';
  let intervalMs = Math.max(1, authorization.interval) * 1000;
  let polls = 0;
  // the BFF judges a poll that lands just past the expiry; no wait starts after it
  while (now() < authorization.expiresAt) {
    await sleep(intervalMs, options.signal);
    polls++;
    let res: Response;
    try {
      res = await postForm(options, '/auth/token', {
        grant_type: DEVICE_CODE_GRANT,
        device_code: authorization.deviceCode,
        client_id: clientId,
      });
    } catch (e) {
      if (options.signal?.aborted) throw e;
      intervalMs *= 2; // §3.5: back off on a connection failure
      continue;
    }
    const body = await readJson(res);
    if (res.ok) return tokenSet(body, clientId, now());
    const error = (body as { error?: unknown } | undefined)?.error;
    if (error === 'authorization_pending') {
      options.onPending?.({ polls, intervalMs, slowDown: false });
      continue;
    }
    if (error === 'slow_down') {
      intervalMs += 5000;
      options.onPending?.({ polls, intervalMs, slowDown: true });
      continue;
    }
    if (typeof error !== 'string' && res.status >= 500) {
      intervalMs *= 2;
      continue;
    }
    throw oauthError(res.status, body);
  }
  throw new OAuthError(400, 'expired_token', 'the code expired before the sign-in was approved');
}

/** Starts a device grant, hands its codes to `onCode` (to show them), and polls until it is decided. */
export async function deviceLogin(
  options: Omit<PollOptions, 'authorization'> & { scope?: string; onCode: (authorization: DeviceAuthorization) => void | Promise<void> },
): Promise<TokenSet> {
  const authorization = await startDeviceAuthorization(options);
  await options.onCode(authorization);
  return pollDeviceAuthorization({ ...options, authorization });
}

/**
 * Renews the access token with the refresh token. The BFF rotates the refresh token: keep the new
 * one before anything else, because presenting the old one again revokes the whole API session.
 */
export async function refreshTokens(options: AuthOptions & { refreshToken: string }): Promise<TokenSet> {
  const now = options.now ?? Date.now;
  const clientId = options.clientId ?? 'console-sdk';
  const res = await postForm(options, '/auth/token', {
    grant_type: 'refresh_token',
    refresh_token: options.refreshToken,
    client_id: clientId,
  });
  const body = await readJson(res);
  if (!res.ok) throw oauthError(res.status, body);
  return tokenSet(body, clientId, now());
}

/** RFC 7009: revokes the API session the refresh token belongs to. The BFF answers 200 always. */
export async function revokeToken(
  options: AuthOptions & { token: string; tokenTypeHint?: 'refresh_token' | 'access_token' },
): Promise<void> {
  const form: Record<string, string> = { token: options.token, token_type_hint: options.tokenTypeHint ?? 'refresh_token' };
  if (options.clientId) form.client_id = options.clientId;
  const res = await postForm(options, '/auth/revoke', form);
  const body = await readJson(res);
  if (!res.ok) throw oauthError(res.status, body);
}

// ---------------------------------------------------------------------------------------------

function postForm(options: AuthOptions, path: string, form: Record<string, string>): Promise<Response> {
  const doFetch = options.fetch ?? globalThis.fetch;
  const headers: Record<string, string> = {
    'Content-Type': 'application/x-www-form-urlencoded',
    Accept: 'application/json',
  };
  if (options.userAgent) headers['User-Agent'] = options.userAgent;
  return doFetch(joinUrl(options.endpoint, path), {
    method: 'POST',
    headers,
    body: new URLSearchParams(form).toString(),
    signal: options.signal,
    cache: 'no-store',
  });
}

export function joinUrl(endpoint: string, path: string): string {
  return endpoint.replace(/\/+$/, '') + path;
}

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text();
  if (text.trim() === '') return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function oauthError(status: number, body: unknown): OAuthError {
  const b = (typeof body === 'object' && body !== null ? body : {}) as { error?: unknown; error_description?: unknown };
  const error = typeof b.error === 'string' ? b.error : `http.${status}`;
  const description = typeof b.error_description === 'string' ? b.error_description : typeof body === 'string' ? body.slice(0, 200) : undefined;
  return new OAuthError(status, error, description);
}

function tokenSet(body: unknown, clientId: string, now: number): TokenSet {
  const b = (body ?? {}) as Record<string, unknown>;
  if (typeof b.access_token !== 'string') throw new OAuthError(200, 'invalid_response', 'the token answer has no access_token');
  const expiresIn = positiveNumber(b.expires_in);
  const refreshExpiresIn = positiveNumber(b.refresh_expires_in);
  return {
    accessToken: b.access_token,
    tokenType: typeof b.token_type === 'string' ? b.token_type : 'Bearer',
    refreshToken: typeof b.refresh_token === 'string' ? b.refresh_token : undefined,
    expiresAt: expiresIn === undefined ? undefined : now + expiresIn * 1000,
    refreshExpiresAt: refreshExpiresIn === undefined ? undefined : now + refreshExpiresIn * 1000,
    scope: typeof b.scope === 'string' ? b.scope : undefined,
    clientId,
  };
}

function positiveNumber(value: unknown): number | undefined {
  const n = typeof value === 'string' ? Number(value) : value;
  return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : undefined;
}

export function defaultSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}
