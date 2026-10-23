import { refreshTokens, type TokenSet } from './auth.js';

/**
 * Where a client's bearer token comes from. `token()` is asked before every call; `refresh()` once
 * when the BFF answers 401 `invalid_token` for `rejected`, and the call is retried with what it
 * answers (`undefined`: no retry).
 */
export interface TokenProvider {
  token(): string | undefined | Promise<string | undefined>;
  refresh?(rejected: string): Promise<string | undefined>;
}

/** A static access token, or a provider. */
export type Credentials = string | TokenProvider;

/**
 * Where refreshing credentials keep their tokens. A store shared by several processes (the CLI's
 * credentials file) gives `exclusive`, so that only one of them refreshes at a time: the refresh
 * token rotates, and the BFF ends the whole API session when a rotated one comes back.
 */
export interface TokenStore {
  load(): TokenSet | undefined | Promise<TokenSet | undefined>;
  save(tokens: TokenSet): void | Promise<void>;
  exclusive?<T>(task: () => Promise<T>): Promise<T>;
}

export interface RefreshingCredentialsOptions {
  /** The BFF's address. */
  endpoint: string;
  /** The tokens to start from, kept in memory (or give `store`). */
  tokens?: TokenSet;
  /** Told the new tokens after each refresh, before they are used: keep the new refresh token. */
  onRefresh?: (tokens: TokenSet) => void | Promise<void>;
  store?: TokenStore;
  /** The client id for a refresh when the tokens do not name one (`console-sdk`). */
  clientId?: string;
  /** Refresh this long before the access token expires (60 s). */
  refreshBeforeMs?: number;
  fetch?: typeof fetch;
  userAgent?: string;
  now?: () => number;
}

/** A static access token, never refreshed. */
export function staticToken(token: string): TokenProvider {
  return { token: () => token };
}

/**
 * Credentials that renew themselves: shortly before the access token expires, and once after a
 * 401 `invalid_token`. Refreshes are single-flight: calls that need one at the same time share it.
 */
export function refreshingCredentials(options: RefreshingCredentialsOptions): TokenProvider & { current(): Promise<TokenSet | undefined> } {
  const now = options.now ?? Date.now;
  const before = options.refreshBeforeMs ?? 60_000;
  const store = options.store ?? memoryStore(options.tokens, options.onRefresh);
  const expiring = (t: TokenSet) => t.expiresAt !== undefined && now() >= t.expiresAt - before;
  let inflight: Promise<string | undefined> | undefined;

  const refresh = (rejected: string | undefined): Promise<string | undefined> => {
    inflight ??= (async () => {
      const run = async () => {
        const current = await store.load();
        if (!current) return undefined;
        // another call, or another process sharing the store, has refreshed already
        if (rejected !== undefined ? current.accessToken !== rejected : !expiring(current)) return current.accessToken;
        if (!current.refreshToken) return undefined;
        const next = await refreshTokens({
          endpoint: options.endpoint,
          clientId: current.clientId ?? options.clientId ?? 'console-sdk',
          refreshToken: current.refreshToken,
          fetch: options.fetch,
          userAgent: options.userAgent,
          now,
        });
        await store.save(next);
        return next.accessToken;
      };
      try {
        return store.exclusive ? await store.exclusive(run) : await run();
      } finally {
        inflight = undefined;
      }
    })();
    return inflight;
  };

  return {
    async token() {
      const current = await store.load();
      if (!current) return undefined;
      if (!expiring(current) || !current.refreshToken) return current.accessToken;
      try {
        return await refresh(undefined);
      } catch (e) {
        // the token still works for a moment: use it, and let a 401 refresh again
        if (current.expiresAt !== undefined && now() < current.expiresAt) return current.accessToken;
        throw e;
      }
    },
    refresh: (rejected) => refresh(rejected),
    current: async () => store.load(),
  };
}

function memoryStore(initial: TokenSet | undefined, onRefresh: RefreshingCredentialsOptions['onRefresh']): TokenStore {
  let tokens = initial;
  return {
    load: () => tokens,
    async save(next) {
      tokens = next;
      await onRefresh?.(next);
    },
  };
}

export function toTokenProvider(credentials: Credentials | undefined): TokenProvider | undefined {
  if (credentials === undefined) return undefined;
  return typeof credentials === 'string' ? staticToken(credentials) : credentials;
}
