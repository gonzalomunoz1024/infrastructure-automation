import type { Denial, ErrorBody, Problem } from './types.js';

/**
 * A call the BFF refused: its status and the BFF's `{ error, problems, detail, denied }`.
 * Every 403 but a missing CSRF token carries `denied`, a Denial; a step-up refusal is a 403 with
 * `detail.stepUp`.
 */
export class ConsoleApiError extends Error {
  override readonly name = 'ConsoleApiError';
  readonly status: number;
  /** The BFF's error code, such as `iam.denied` or `step-up.required` (`http.<status>` without a body). */
  readonly error: string;
  readonly problems: readonly Problem[];
  readonly denied?: Denial;
  readonly detail?: ErrorBody['detail'];
  readonly correlationId?: string;
  /** Seconds the BFF asked to wait (`Retry-After`), on a 429 or 503. */
  readonly retryAfter?: number;
  readonly method: string;
  readonly url: string;
  readonly headers: Headers;
  /** The body as the BFF sent it: the parsed JSON, or the text. */
  readonly body: unknown;

  constructor(init: { status: number; method: string; url: string; headers: Headers; body: unknown; cause?: unknown }) {
    const body = isErrorBody(init.body) ? init.body : undefined;
    const error = body?.error ?? `http.${init.status}`;
    super(describe(init.method, init.url, init.status, error, body, init.body), init.cause === undefined ? undefined : { cause: init.cause });
    this.status = init.status;
    this.error = error;
    this.problems = body?.problems ?? [];
    this.denied = body?.denied;
    this.detail = body?.detail;
    this.correlationId = body?.correlationId;
    this.retryAfter = retryAfterSeconds(init.headers.get('retry-after'));
    this.method = init.method;
    this.url = init.url;
    this.headers = init.headers;
    this.body = init.body;
  }

  /** The action needs a fresh sign-in (`console login` again, for a bearer caller). */
  get stepUp(): boolean {
    return this.detail?.stepUp === true || this.error === 'step-up.required';
  }

  /** The Denial allows an access request for the action. */
  get requestable(): boolean {
    return this.denied?.requestable === true;
  }
}

/** An answer of the BFF's OAuth endpoints (`/auth/device`, `/auth/token`): RFC 6749 §5.2. */
export class OAuthError extends Error {
  override readonly name = 'OAuthError';
  readonly status: number;
  /** `authorization_pending`, `slow_down`, `access_denied`, `expired_token`, `invalid_grant`, … */
  readonly error: string;
  readonly description?: string;

  constructor(status: number, error: string, description?: string, options?: { cause?: unknown }) {
    super(description ? `${error}: ${description}` : error, options);
    this.status = status;
    this.error = error;
    this.description = description;
  }
}

export function isConsoleApiError(value: unknown): value is ConsoleApiError {
  return value instanceof ConsoleApiError;
}

function isErrorBody(value: unknown): value is ErrorBody {
  return typeof value === 'object' && value !== null && typeof (value as { error?: unknown }).error === 'string';
}

function describe(method: string, url: string, status: number, error: string, body: ErrorBody | undefined, raw: unknown): string {
  let path = url;
  try {
    const u = new URL(url);
    path = u.pathname;
  } catch {
    // not a URL: keep it as it is
  }
  const problems = body?.problems?.map((p) => (p.field ? `${p.field}: ${p.message}` : p.message)).join('; ');
  const message = (body as { message?: unknown } | undefined)?.message;
  const text = typeof raw === 'string' && raw.length <= 200 ? raw.trim() : undefined;
  const why = [body?.denied?.reason, problems, message, text].find((s): s is string => typeof s === 'string' && s.length > 0);
  return `${method} ${path} answered ${status} ${error}${why ? `: ${why}` : ''}`;
}

/** `Retry-After` as seconds: a number of seconds, or an HTTP date. */
export function retryAfterSeconds(header: string | null, now: number = Date.now()): number | undefined {
  if (header === null || header.trim() === '') return undefined;
  const value = header.trim();
  if (/^\d+$/.test(value)) return Number(value);
  const date = Date.parse(value);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - now) / 1000));
}
