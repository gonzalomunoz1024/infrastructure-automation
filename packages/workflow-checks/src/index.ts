/**
 * @iaf/workflow-checks: the workflow checks and the expression engine, as TypeScript, for the
 * designer (live problems and expression previews) and for CI (`npm run check:workflows`). The
 * rules mirror the Java backend one to one; the fixtures under ./fixtures prove both agree.
 */
export type {
  ApiDocument,
  ApprovalPolicyDocument,
  BatchPolicyDocument,
  CallKind,
  CheckContext,
  ContextSchemas,
  Fix,
  FixKind,
  FlowDocument,
  JsonObject,
  JsonSchema,
  JsonValue,
  McpDocument,
  PolicyDocument,
  Problem,
  ProblemLevel,
  ProviderActionDocument,
  ProviderDocument,
  ProviderSpecDocument,
  ProviderType,
  ResourceTypeDocument,
  RolloutDocument,
  RolloutPolicyDocument,
  StepBody,
  StepDocument,
  SwitchCaseDocument,
  TriggerDocument,
  WorkflowDocument,
  WorkflowMetadata,
} from './types.ts';

export {
  buildContext,
  check,
  checkParsed,
  checkYaml,
  connectFix,
  contextSchemas,
  EMPTY_CONTEXT,
  hasErrors,
  inputSchemaProblems,
  insertApprovalFix,
  replaceFix,
  RESERVED_NOUNS,
  ROOTS,
} from './checks.ts';

export { describeRemoteRef, dotted, escapePointer, isClosed, jsonTypeName, mergeSchema, metaViolations, remoteRef, sameJson, schemaPaths } from './schemas.ts';
export type { RemoteRef, Violation } from './schemas.ts';

export { schemaViolations, stepOf, validateWorkflowDocument, VALIDATOR_KEYWORDS, WORKFLOW_SCHEMA } from './workflow-schema.ts';

export {
  allSteps,
  API_VERSION,
  CANARY_STAGES,
  COMMON_KEYS,
  DEFAULT_BATCH_MAX,
  DEFAULT_ROLLOUT_POLICY,
  displayName,
  findStep,
  flatten,
  kindOf,
  mcpExposed,
  parseDocument,
  parseStep,
  parseSteps,
  withNode,
  withText,
  WORKFLOW_KIND,
  WorkflowParseError,
} from './parse.ts';
export type {
  ParsedApi,
  ParsedApproval,
  ParsedBatch,
  ParsedCatch,
  ParsedFlow,
  ParsedMcp,
  ParsedMetadata,
  ParsedOutput,
  ParsedPolicy,
  ParsedPosition,
  ParsedRetry,
  ParsedRollout,
  ParsedRolloutPolicy,
  ParsedStep,
  ParsedSwitchCase,
  ParsedTrigger,
  ParsedWorkflow,
} from './parse.ts';

export {
  asText,
  EMPTY_EXPRESSION_CONTEXT,
  evaluate,
  ExpressionError,
  isTemplate,
  references,
  referencesIn,
  resolve,
  syntaxError,
  test,
  truthy,
} from './expressions.ts';
export type { ExpressionContext, Reference, ReferenceKind } from './expressions.ts';

export {
  actionApprovalRequired,
  actionEffect,
  actionIds,
  actionMethod,
  actionRetrySafe,
  ContentParseError,
  effectOf,
  effectOfHttpMethod,
  findAction,
  isDestructive,
  parseProvider,
  parseResourceType,
  PROVIDER_KIND,
  PROVIDER_TYPES,
  providerActions,
  providerName,
  RESOURCE_TYPE_KIND,
  resourceTypeName,
} from './providers.ts';
export type { Effect } from './providers.ts';

export { closest, levenshtein } from './suggest.ts';

export { CALL_KINDS, isStepType, STEP_TYPE_KEYS, STEP_TYPES } from './step-types.ts';
export { parseYamlText, YamlSyntaxError } from './yaml-text.ts';
export type { StepCategory, StepType, StepTypeInfo } from './step-types.ts';
