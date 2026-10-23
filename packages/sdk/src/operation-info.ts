/** What the client needs to call one operation of the contract (see generated/operations.ts). */
export interface OperationInfo {
  readonly method: 'GET' | 'PUT' | 'POST' | 'PATCH' | 'DELETE';
  /** The path template, `{name}` for each path parameter. */
  readonly path: string;
  readonly tags: readonly string[];
  readonly params: readonly ParameterInfo[];
  readonly body?: { readonly required: boolean; readonly contentType: string };
  /** The operation takes an `Idempotency-Key`, so the BFF answers a repeated call once. */
  readonly idempotencyKey: boolean;
  /** The operation answers `{ items, next }` and takes `after`. */
  readonly paged: boolean;
  /** What a success answers: JSON, text (CSV), server-sent events, or nothing. */
  readonly response: 'json' | 'text' | 'events' | 'none';
}

export interface ParameterInfo {
  readonly name: string;
  readonly in: 'path' | 'query' | 'header';
  readonly required: boolean;
  readonly array?: boolean;
  /** For a parameter named by a pattern, such as `tag.<name>`: the regular expression its names match. */
  readonly namePattern?: string;
}
