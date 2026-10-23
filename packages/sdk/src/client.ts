import { defaultSleep, joinUrl } from './auth.js';
import { toTokenProvider, type Credentials, type TokenProvider } from './credentials.js';
import { ConsoleApiError, retryAfterSeconds } from './errors.js';
import { OPERATIONS, type EventStreamOperationId, type PagedOperationId } from './generated/operations.js';
import type { OperationInfo } from './operation-info.js';
import { parseServerSentEvents, type ServerSentEvent } from './sse.js';
import type { CallArgs, CallOptions, DynamicCallInput, OperationId, PageItem, ResponseData } from './types.js';

export interface ConsoleClientOptions {
  /** The BFF's address, such as `https://console.example.com`; a path prefix is kept. */
  endpoint: string;
  /** A bearer access token, or a provider that renews it (see `refreshingCredentials`). */
  credentials?: Credentials;
  /** `X-Console-Account` on every call (else the API session's account). */
  account?: string;
  /** `X-Console-Region` on every call (else the API session's region). */
  region?: string;
  /** Headers for every call; the console shell sends its `X-Csrf-Token` this way. */
  headers?: Record<string, string>;
  userAgent?: string;
  fetch?: typeof fetch;
  /** Retries of a 429 or 503, for idempotent calls only (3). */
  retries?: number;
  /** The longest wait a retry honours; a longer `Retry-After` is not retried (60 s). */
  maxRetryDelayMs?: number;
  /** Makes an `Idempotency-Key` for a write that has none (a random UUID). */
  idempotencyKey?: () => string;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  /** The time, in milliseconds since the epoch, for a `Retry-After` that is a date. */
  now?: () => number;
}

/** A success: its status, its headers and its body (parsed JSON, text, or `undefined`). */
export interface ConsoleResponse<T> {
  status: number;
  headers: Headers;
  data: T;
}

/** A call by method and path, for what the operations do not cover. */
export interface RequestOptions extends CallOptions {
  query?: DynamicCallInput['query'];
  body?: unknown;
  /** `application/json` (the default) or `application/x-www-form-urlencoded`. */
  contentType?: string;
  /** May the call be retried after a 429 or 503: GET, HEAD, PUT and DELETE are, others not unless set. */
  idempotent?: boolean;
  accept?: string;
}

/** The client called with operation ids known only at run time (the command core does). */
export interface DynamicConsoleClient {
  readonly endpoint: string;
  send(operationId: string, input?: DynamicCallInput): Promise<ConsoleResponse<unknown>>;
  pages(operationId: string, input?: DynamicCallInput): AsyncGenerator<unknown>;
  events(operationId: string, input?: DynamicCallInput): AsyncGenerator<ServerSentEvent>;
}

const WRITES = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const IDEMPOTENT = new Set(['GET', 'HEAD', 'OPTIONS', 'PUT', 'DELETE']);
const FORM = 'application/x-www-form-urlencoded';

/**
 * The BFF's API as one client. Every call carries the bearer token (renewed once after a 401
 * `invalid_token`, then retried), the account and region when set, and on a write an
 * `Idempotency-Key`. A 429 or 503 of an idempotent call is retried up to 3 times, after
 * `Retry-After`. A refusal throws a ConsoleApiError with the BFF's error, problems and Denial.
 */
export class ConsoleClient {
  readonly endpoint: string;
  private readonly tokens?: TokenProvider;
  private readonly options: ConsoleClientOptions;
  private readonly doFetch: typeof fetch;
  private readonly sleep: (ms: number, signal?: AbortSignal) => Promise<void>;

  constructor(options: ConsoleClientOptions) {
    if (!options.endpoint) throw new TypeError('createConsoleClient needs the endpoint, the BFF\'s address');
    this.endpoint = options.endpoint.replace(/\/+$/, '');
    this.options = options;
    this.tokens = toTokenProvider(options.credentials);
    this.doFetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.sleep = options.sleep ?? defaultSleep;
  }

  /** Calls one operation and answers its body: `await client.call('getJob', { path: { jobId } })`. */
  async call<Op extends OperationId>(operationId: Op, ...args: CallArgs<Op>): Promise<ResponseData<Op>> {
    return (await this.send(operationId, ...args)).data;
  }

  /** Calls one operation and answers its status, headers and body. */
  async send<Op extends OperationId>(operationId: Op, ...args: CallArgs<Op>): Promise<ConsoleResponse<ResponseData<Op>>> {
    const res = await this.operationResponse(operationId, (args[0] ?? {}) as DynamicCallInput, 'application/json');
    return { status: res.status, headers: res.headers, data: (await readBody(res)) as ResponseData<Op> };
  }

  /** Every page of a paged operation, following `next` until it is null. */
  async *pages<Op extends PagedOperationId>(operationId: Op, ...args: CallArgs<Op>): AsyncGenerator<NonNullable<ResponseData<Op>>> {
    const input = (args[0] ?? {}) as DynamicCallInput;
    let after = input.query?.after as string | undefined;
    const seen = new Set<string>();
    for (;;) {
      const res = await this.operationResponse(operationId, { ...input, query: { ...input.query, after } }, 'application/json');
      const page = (await readBody(res)) as NonNullable<ResponseData<Op>>;
      yield page;
      const next = (page as { next?: unknown } | undefined)?.next;
      if (typeof next !== 'string' || next === '') return;
      if (seen.has(next)) throw new Error(`${operationId} answered the cursor ${next} twice`);
      seen.add(next);
      after = next;
    }
  }

  /** Every item of a paged operation: `for await (const job of client.paginate('listJobs'))`. */
  async *paginate<Op extends PagedOperationId>(operationId: Op, ...args: CallArgs<Op>): AsyncGenerator<PageItem<Op>> {
    for await (const page of this.pages(operationId, ...args)) {
      yield* ((page as { items?: PageItem<Op>[] }).items ?? []);
    }
  }

  /** The server-sent events of a streaming operation (`streamEvents`), as they come. */
  async *events<Op extends EventStreamOperationId>(operationId: Op, ...args: CallArgs<Op>): AsyncGenerator<ServerSentEvent> {
    const res = await this.operationResponse(operationId, (args[0] ?? {}) as DynamicCallInput, 'text/event-stream');
    if (res.body) yield* parseServerSentEvents(res.body);
  }

  /** A call by method and path, relative to the endpoint: `client.request('GET', '/console/v1/jobs')`. */
  async request(method: string, path: string, options: RequestOptions = {}): Promise<ConsoleResponse<unknown>> {
    const m = method.toUpperCase();
    const url = new URL(joinUrl(this.endpoint, path.startsWith('/') ? path : `/${path}`));
    appendQuery(url, options.query);
    const res = await this.fetchWithPolicy(m, url.toString(), this.headersFor(m, options, options.accept ?? 'application/json', options.body !== undefined ? (options.contentType ?? 'application/json') : undefined), encodeBody(options.body, options.contentType), {
      idempotent: options.idempotent ?? IDEMPOTENT.has(m),
      retries: options.retries ?? this.options.retries ?? 3,
      signal: options.signal,
    });
    return { status: res.status, headers: res.headers, data: await readBody(res) };
  }

  private async operationResponse(operationId: string, input: DynamicCallInput, accept: string): Promise<Response> {
    const info = (OPERATIONS as Record<string, OperationInfo>)[operationId];
    if (!info) throw new TypeError(`the contract has no operation ${operationId}`);
    const url = new URL(joinUrl(this.endpoint, buildPath(operationId, info, input.path)));
    appendQuery(url, input.query);
    const contentType = input.body !== undefined ? (info.body?.contentType ?? 'application/json') : undefined;
    return this.fetchWithPolicy(info.method, url.toString(), this.headersFor(info.method, input, accept, contentType), encodeBody(input.body, contentType), {
      // a write that takes an Idempotency-Key is answered once however often it is sent
      idempotent: IDEMPOTENT.has(info.method) || info.idempotencyKey,
      retries: input.retries ?? this.options.retries ?? 3,
      signal: input.signal,
    });
  }

  private headersFor(method: string, call: CallOptions, accept: string, contentType: string | undefined): Headers {
    const headers = new Headers({ Accept: accept });
    if (this.options.userAgent) headers.set('User-Agent', this.options.userAgent);
    for (const [k, v] of Object.entries(this.options.headers ?? {})) headers.set(k, v);
    const account = call.account ?? this.options.account;
    const region = call.region ?? this.options.region;
    if (account) headers.set('X-Console-Account', account);
    if (region) headers.set('X-Console-Region', region);
    if (contentType) headers.set('Content-Type', contentType);
    for (const [k, v] of Object.entries(call.headers ?? {})) headers.set(k, v);
    if (WRITES.has(method)) {
      if (call.idempotencyKey) headers.set('Idempotency-Key', call.idempotencyKey);
      else if (!headers.has('Idempotency-Key')) headers.set('Idempotency-Key', (this.options.idempotencyKey ?? uuid)());
    }
    return headers;
  }

  private async fetchWithPolicy(
    method: string,
    url: string,
    headers: Headers,
    body: string | undefined,
    policy: { idempotent: boolean; retries: number; signal?: AbortSignal },
  ): Promise<Response> {
    let refreshed = false;
    let retries = 0;
    for (;;) {
      const token = await this.tokens?.token();
      const attempt = new Headers(headers);
      if (token) attempt.set('Authorization', `Bearer ${token}`);
      const res = await this.doFetch(url, { method, headers: attempt, body, signal: policy.signal });

      if (res.status === 401 && token && !refreshed && this.tokens?.refresh && (await isInvalidToken(res))) {
        refreshed = true;
        let next: string | undefined;
        try {
          next = await this.tokens.refresh(token);
        } catch (e) {
          throw await toError(method, url, res, e);
        }
        if (next) {
          await discard(res);
          continue;
        }
      }

      if ((res.status === 429 || res.status === 503) && policy.idempotent && retries < policy.retries) {
        const wait = this.retryDelay(res, retries);
        if (wait !== undefined) {
          retries++;
          await discard(res);
          await this.sleep(wait, policy.signal);
          continue;
        }
      }

      if (!res.ok && res.status !== 304) throw await toError(method, url, res);
      return res;
    }
  }

  private retryDelay(res: Response, retries: number): number | undefined {
    const max = this.options.maxRetryDelayMs ?? 60_000;
    const after = retryAfterSeconds(res.headers.get('retry-after'), (this.options.now ?? Date.now)());
    const ms = after !== undefined ? after * 1000 : Math.min(1000 * 2 ** retries, max);
    return ms <= max ? ms : undefined;
  }
}

/** A client of the BFF's API. */
export function createConsoleClient(options: ConsoleClientOptions): ConsoleClient {
  return new ConsoleClient(options);
}

/** The same client, for operation ids known only at run time. */
export function dynamic(client: ConsoleClient): DynamicConsoleClient {
  return client as unknown as DynamicConsoleClient;
}

// ---------------------------------------------------------------------------------------------

function buildPath(operationId: string, info: OperationInfo, values: DynamicCallInput['path']): string {
  return info.path.replace(/\{([^}]+)\}/g, (_, name: string) => {
    const value = values?.[name];
    if (value === undefined || value === null || value === '') {
      throw new TypeError(`${operationId} needs the path parameter ${name}`);
    }
    // a wfrn travels percent-encoded, its colons and slashes too
    return encodeURIComponent(String(value));
  });
}

function appendQuery(url: URL, query: DynamicCallInput['query']): void {
  for (const [name, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null) continue;
    for (const v of Array.isArray(value) ? value : [value]) url.searchParams.append(name, String(v));
  }
}

function encodeBody(body: unknown, contentType: string | undefined): string | undefined {
  if (body === undefined) return undefined;
  if (contentType === FORM) {
    const form = new URLSearchParams();
    for (const [k, v] of Object.entries(body as Record<string, unknown>)) {
      if (v !== undefined && v !== null) form.append(k, String(v));
    }
    return form.toString();
  }
  return JSON.stringify(body);
}

async function isInvalidToken(res: Response): Promise<boolean> {
  const challenge = res.headers.get('www-authenticate');
  if (challenge) return /\berror\s*=\s*"?invalid_token"?/i.test(challenge);
  try {
    const body = (await res.clone().json()) as { error?: unknown };
    return body?.error === 'invalid_token';
  } catch {
    return false;
  }
}

async function readBody(res: Response): Promise<unknown> {
  if (res.status === 204 || res.status === 205 || res.status === 304) {
    await discard(res);
    return undefined;
  }
  const text = await res.text();
  if (isJson(res.headers.get('content-type'))) return text.trim() === '' ? undefined : JSON.parse(text);
  return text === '' ? undefined : text;
}

async function toError(method: string, url: string, res: Response, cause?: unknown): Promise<ConsoleApiError> {
  let body: unknown;
  try {
    const text = await res.text();
    body = text;
    if (text.trim() !== '' && (isJson(res.headers.get('content-type')) || /^\s*[{[]/.test(text))) body = JSON.parse(text);
  } catch {
    // keep the text, or nothing
  }
  return new ConsoleApiError({ status: res.status, method, url, headers: res.headers, body, cause });
}

function isJson(contentType: string | null): boolean {
  return contentType !== null && /^application\/(?:[\w.-]+\+)?json\b/i.test(contentType.trim());
}

async function discard(res: Response): Promise<void> {
  try {
    await res.arrayBuffer();
  } catch {
    // already read, or the connection went away: nothing to free
  }
}

function uuid(): string {
  const c = globalThis.crypto;
  if (typeof c?.randomUUID === 'function') return c.randomUUID();
  const b = c.getRandomValues(new Uint8Array(16));
  b[6] = (b[6]! & 0x0f) | 0x40;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
