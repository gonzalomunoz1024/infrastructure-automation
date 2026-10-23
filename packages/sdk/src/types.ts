// Types over the generated contract: what an operation takes and what it answers.

import type { components, operations, paths } from './generated/console-bff.js';

export type { components, operations, paths };

/** Every operation of the contract, by its operationId. */
export type OperationId = keyof operations;

export type Schemas = components['schemas'];
/** One schema of the contract: `Schema<'JobDetail'>`. */
export type Schema<K extends keyof Schemas> = Schemas[K];

/** The BFF's error body: `{ error, problems?, denied?, detail?, correlationId? }`. */
export type ErrorBody = Schemas['Error'];
/** Why an action is refused, from the layer that decided (every 403 carries one). */
export type Denial = Schemas['Denial'];
/** One problem of a 400, its field a JSON pointer into the body sent. */
export type Problem = Schemas['Problem'];

type Parameters<Op extends OperationId> = operations[Op]['parameters'];

/** The path parameters of an operation, `never` when it has none. */
export type PathParams<Op extends OperationId> = NonNullable<Parameters<Op>['path']>;
/** The query parameters of an operation, `never` when it has none. */
export type QueryParams<Op extends OperationId> = NonNullable<Parameters<Op>['query']>;

type RequestBodies<Op extends OperationId> = NonNullable<operations[Op]['requestBody']>;
/** The request body of an operation, `never` when it takes none. */
export type RequestBody<Op extends OperationId> = RequestBodies<Op> extends { content: infer C } ? C[keyof C] : never;
type BodyRequired<Op extends OperationId> = undefined extends operations[Op]['requestBody'] ? false : true;

type Responses<Op extends OperationId> = operations[Op]['responses'];
type SuccessStatus = 200 | 201 | 202 | 203 | 204 | 206 | 304;
type Content<R> = R extends { content: infer C }
  ? C extends { 'application/json': infer J }
    ? J
    : C extends Record<string, infer T>
      ? T
      : unknown
  : undefined;

/** What a success of the operation answers (`undefined` for 204 and 304). */
export type ResponseData<Op extends OperationId> = {
  [S in Extract<keyof Responses<Op>, SuccessStatus>]: Content<Responses<Op>[S]>;
}[Extract<keyof Responses<Op>, SuccessStatus>];

/** One item of a paged operation's `items`. */
export type PageItem<Op extends OperationId> = NonNullable<ResponseData<Op>> extends { items: readonly (infer I)[] } ? I : never;

// A parameter named by a pattern (`tag.<name>`) is sent as any name the pattern allows (`tag.env`).
type PatternNames<Q> = {
  [K in keyof Q as K extends `${infer Prefix}<${string}>` ? `${Prefix}${string}` : never]?: Q[K];
};

/** Options every call takes. */
export interface CallOptions {
  /** More headers for this call (they win over the client's). */
  headers?: Record<string, string>;
  /** `X-Console-Account` for this call: one of the person's accounts. */
  account?: string;
  /** `X-Console-Region` for this call. */
  region?: string;
  /** The `Idempotency-Key` of a POST, PUT, PATCH or DELETE (a UUID by default). */
  idempotencyKey?: string;
  /** Retries of a 429 or 503 for this call (the client's, 3, by default); 0 turns them off. */
  retries?: number;
  signal?: AbortSignal;
}

type PathInput<Op extends OperationId> = [PathParams<Op>] extends [never] ? { path?: undefined } : { path: PathParams<Op> };
type QueryInput<Op extends OperationId> = [QueryParams<Op>] extends [never]
  ? { query?: undefined }
  : {} extends QueryParams<Op>
    ? { query?: QueryParams<Op> & PatternNames<QueryParams<Op>> }
    : { query: QueryParams<Op> & PatternNames<QueryParams<Op>> };
type BodyInput<Op extends OperationId> = [RequestBody<Op>] extends [never]
  ? { body?: undefined }
  : BodyRequired<Op> extends true
    ? { body: RequestBody<Op> }
    : { body?: RequestBody<Op> };

/** What one call of the operation takes: its path and query parameters, its body, and the call's options. */
export type CallInput<Op extends OperationId> = PathInput<Op> & QueryInput<Op> & BodyInput<Op> & CallOptions;

type RequiredKeys<T> = { [K in keyof T]-?: {} extends Pick<T, K> ? never : K }[keyof T];
/** The input argument, optional when nothing in it is required. */
export type CallArgs<Op extends OperationId> = [RequiredKeys<CallInput<Op>>] extends [never]
  ? [input?: CallInput<Op>]
  : [input: CallInput<Op>];

/** A call built at run time (the command core's): parameters by name, unchecked. */
export interface DynamicCallInput extends CallOptions {
  path?: Record<string, string | number>;
  query?: Record<string, string | number | boolean | readonly (string | number | boolean)[] | null | undefined>;
  body?: unknown;
}
