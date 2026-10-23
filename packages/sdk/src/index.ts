// @iaf/console-sdk: the console BFF's API for TypeScript, in Node and in browsers.

export {
  ConsoleClient,
  createConsoleClient,
  dynamic,
  type ConsoleClientOptions,
  type ConsoleResponse,
  type DynamicConsoleClient,
  type RequestOptions,
} from './client.js';
export {
  DEVICE_CODE_GRANT,
  deviceLogin,
  pollDeviceAuthorization,
  refreshTokens,
  revokeToken,
  startDeviceAuthorization,
  type AuthOptions,
  type ConsoleClientId,
  type DeviceAuthorization,
  type PollOptions,
  type TokenSet,
} from './auth.js';
export {
  refreshingCredentials,
  staticToken,
  type Credentials,
  type RefreshingCredentialsOptions,
  type TokenProvider,
  type TokenStore,
} from './credentials.js';
export { ConsoleApiError, OAuthError, isConsoleApiError, retryAfterSeconds } from './errors.js';
export { parseServerSentEvents, type ServerSentEvent } from './sse.js';
export { OPERATIONS, type EventStreamOperationId, type PagedOperationId } from './generated/operations.js';
export type { OperationInfo, ParameterInfo } from './operation-info.js';
export type {
  CallArgs,
  CallInput,
  CallOptions,
  Denial,
  DynamicCallInput,
  ErrorBody,
  OperationId,
  PageItem,
  PathParams,
  Problem,
  QueryParams,
  RequestBody,
  ResponseData,
  Schema,
  Schemas,
  components,
  operations,
  paths,
} from './types.js';

/** This SDK's version. */
export const VERSION = '0.1.0';
