/**
 * The shapes the checks speak: a {@link Problem} is what the editor, the validate endpoint and CI
 * all show, and the document types are the YAML files as parsed (nothing more than the structure
 * described in docs/config-as-code.md). They mirror the Java records under
 * backend/src/main/java/com/iaf/platform.
 */

/** Any value a YAML or JSON document can hold. */
export type JsonValue = null | boolean | number | string | JsonValue[] | JsonObject;

export interface JsonObject {
  [key: string]: JsonValue | undefined;
}

export type ProblemLevel = 'error' | 'warning';

export type FixKind = 'replace' | 'insert-approval' | 'connect';

/** A one-click fix the editor can apply. */
export interface Fix {
  label: string;
  kind: FixKind;
  /** The replacement text for a `replace` fix. */
  value?: string;
}

/** One finding of a check. Codes are stable identifiers the editor keys on; messages are for people. */
export interface Problem {
  level: ProblemLevel;
  code: string;
  /** The step id, when the problem sits on a step. */
  step?: string;
  /** A dotted path to the field, relative to the step (or to the document when there is no step). */
  field?: string;
  message: string;
  fix?: Fix;
}

// ------------------------------------------------------------------ kind: Workflow

export interface WorkflowMetadata {
  name?: string;
  title?: string;
  noun?: string;
  verb?: string;
  persona?: string;
  owner?: string;
  folder?: string;
  tags?: string[] | string;
  version?: string;
  description?: string;
}

/** A JSON Schema, kept loose: the checks only read `type`, `required`, `properties` and `x-resource`. */
export interface JsonSchema extends JsonObject {
  type?: string;
  required?: string[];
  properties?: { [name: string]: JsonSchema };
  'x-resource'?: { type?: string; orCreateWith?: string };
}

export interface RolloutPolicyDocument {
  waves?: number[];
  maxParallel?: number;
  stopIfFailedPercent?: number;
}

export interface BatchPolicyDocument {
  max?: number;
  rollout?: RolloutPolicyDocument;
}

export interface ApiDocument {
  method?: string;
  path?: string;
  input?: JsonSchema;
  /** `body`: the JSON Schema of a succeeded job's output (the keys of `workflow.outputs`); OpenAPI publishes it. */
  response?: { status?: number; body?: JsonSchema };
  preview?: boolean;
  batch?: BatchPolicyDocument;
  /** The operation still answers, with Deprecation, Sunset and Link headers; dates are YYYY-MM-DD or ISO date-times. */
  deprecated?: { since: string; sunset: string; replacement?: string; message?: string };
}

export interface McpDocument {
  expose?: boolean;
  description?: string;
}

export interface ApprovalPolicyDocument {
  required?: boolean;
  group?: string;
  timeout?: string;
}

export interface PolicyDocument {
  approval?: ApprovalPolicyDocument;
  rbac?: string[];
  change?: { required?: boolean };
}

/** `- alert: { match, input }`, `- event: { topic, input }` or `{ type, ... }`. */
export type TriggerDocument = JsonObject;

export interface RolloutDocument {
  strategy?: string;
  stages?: number[];
}

export interface FlowDocument {
  do?: StepDocument[];
  catch?: { do?: StepDocument[] };
  outputs?: { [name: string]: string };
}

export interface WorkflowDocument {
  apiVersion?: string;
  kind?: string;
  metadata?: WorkflowMetadata;
  api?: ApiDocument;
  mcp?: McpDocument;
  policy?: PolicyDocument;
  triggers?: TriggerDocument[];
  rollout?: RolloutDocument;
  workflow?: FlowDocument;
}

/** One entry of a `do` list: `- <stepId>: { ...body }`. */
export interface StepDocument {
  [stepId: string]: StepBody;
}

export type CallKind = 'provider' | 'http' | 'ansible' | 'terraform' | 'idp' | 'activity';

export interface SwitchCaseDocument {
  case?: { when?: string; then?: string; do?: StepDocument[] };
  default?: string | { then?: string; do?: StepDocument[] };
}

/** The body of a step: exactly one step key plus the common keys. */
export interface StepBody {
  name?: string;
  with?: JsonObject;
  call?: CallKind | 'approval';
  run?: { workflow?: string; version?: string; input?: JsonObject; onFailure?: 'fail' | 'continue' | string };
  approval?: { group?: string; message?: string; timeout?: string; escalation?: JsonObject };
  switch?: SwitchCaseDocument[];
  fork?: { branches?: StepDocument[][] };
  for?: { each?: string; in?: string; do?: StepDocument[] };
  try?: { do?: StepDocument[]; catch?: { as?: string; do?: StepDocument[] } };
  wait?: string | { duration?: string; until?: string };
  set?: { [name: string]: JsonValue | undefined };
  raise?: { error?: string; message?: string };
  use?: { type?: string; id?: string; selector?: JsonValue; waitUntil?: string; checkAccess?: boolean };
  register?: { type?: string; id?: string; state?: string; attributes?: JsonObject; builtFrom?: JsonValue };
  notify?: { channel?: string; message?: string };
  script?: { language?: string; code?: string; input?: JsonObject };
  output?: { as?: string };
  assert?: string;
  retry?: { maxAttempts?: number; backoff?: string };
  timeout?: string;
  pauseOnFailure?: boolean;
  position?: { x?: number; y?: number };
  notes?: string;
  compensate?: StepDocument[];
}

// ------------------------------------------------------------------ kind: Provider

export type ProviderType = 'openapi' | 'http' | 'ansible' | 'terraform' | 'idp' | 'sdk';

export interface ProviderActionDocument {
  id: string;
  title?: string;
  /** An operationId (openapi) or `{ method, path }` (http). */
  operation?: string | { method?: string; path?: string };
  method?: string;
  path?: string;
  /** read | create | change | delete; inferred from the HTTP method when absent. */
  effect?: string;
  /** safe | unsafe */
  retry?: string;
  /** required | none; a delete defaults to required. */
  approval?: string | boolean;
  plan?: string;
  verify?: { action?: string; expect?: string };
  input?: JsonSchema;
  output?: JsonSchema;
  sample?: JsonValue;
  example?: JsonValue;
  playbook?: string;
  vars?: JsonSchema;
  description?: string;
}

export interface ProviderSpecDocument {
  type?: ProviderType | string;
  spec?: { url?: string; sha256?: string; content?: string };
  endpoints?: { [environment: string]: string };
  auth?: { type?: string; credential?: string; header?: string };
  actions?: ProviderActionDocument[];
  overrides?: JsonObject;
  errors?: JsonObject[];
  slo?: { successRate?: number; p95Ms?: number };
  alerts?: { channel?: string };
  source?: JsonValue;
  module?: JsonValue;
  tile?: JsonValue;
  /** sdk: the team's worker; the queue defaults to provider-<name>, the heartbeat timeout to none. */
  worker?: { taskQueue?: string; heartbeatTimeout?: string };
  /** The older spelling of worker.taskQueue. */
  taskQueue?: string;
  executionEnvironment?: string;
}

export interface ProviderDocument {
  apiVersion?: string;
  kind?: string;
  metadata: { name: string; title?: string; owner?: string; version?: string; description?: string };
  spec: ProviderSpecDocument;
}

// ------------------------------------------------------------------ kind: ResourceType

export interface ResourceTypeDocument {
  apiVersion?: string;
  kind?: string;
  metadata: { name: string; plural?: string; owner?: string; description?: string };
  schema?: JsonSchema;
  states?: string[];
  producedBy?: string;
}

// ------------------------------------------------------------------ the catalog

/**
 * What the checks may look up: the providers, published workflows and resource types a
 * definition can refer to. With `knowsCatalog` false (an editor without the catalog loaded),
 * unknown names are not reported. The schemas are optional: without them the contract of run
 * steps and the fields a step returns are not judged.
 */
export interface CheckContext {
  providers: Record<string, ProviderDocument>;
  workflows: string[];
  resourceTypes: string[];
  knowsCatalog: boolean;
  /** Published workflows' input schemas (`api.input`) by name, for the contract of run steps. */
  workflowInputs?: Record<string, JsonSchema>;
  /** Published workflows' output names (the keys of `workflow.outputs`) by name. */
  workflowOutputs?: Record<string, string[]>;
  /** Resource types' attribute schemas by type. */
  resourceTypeSchemas?: Record<string, JsonSchema>;
  /**
   * The published workflows the MCP server gives each tool (`vm_restart`, the batch tool
   * `vm_restart_batch`), sorted, by tool name; without it `mcp.toolName` is not judged.
   */
  mcpTools?: Record<string, string[]>;
}

/** The optional schemas of a {@link CheckContext}, as `buildContext` takes them. */
export interface ContextSchemas {
  workflowInputs?: Record<string, JsonSchema>;
  workflowOutputs?: Record<string, string[]>;
  resourceTypeSchemas?: Record<string, JsonSchema>;
  mcpTools?: Record<string, string[]>;
}
