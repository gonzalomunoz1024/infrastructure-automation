/**
 * Reads a `kind: Workflow` document into the shape the checks walk, the way WorkflowYaml.java
 * does: strict about structure (every step needs exactly one step key, with the same messages)
 * and lenient about the rest; the checks report semantics.
 */
import { asBoolean, asDouble, asInt, asText, asTextOr, fieldNames, get, has, ints, isArray, isBlank, isObject, size, strings, text, type Maybe } from './json.ts';
import { CALL_KINDS, STEP_TYPES, type StepType } from './step-types.ts';
import type { JsonObject, JsonValue } from './types.ts';

export const API_VERSION = 'iaf.platform/v1';
export const WORKFLOW_KIND = 'Workflow';

/** The keys every step may carry besides its one step key. */
export const COMMON_KEYS: readonly string[] = ['name', 'with', 'output', 'assert', 'retry', 'timeout', 'pauseOnFailure', 'position', 'notes', 'compensate'];

/** A document that is not a workflow at all: the structural errors of WorkflowYaml.java. */
export class WorkflowParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'WorkflowParseError';
  }
}

export interface ParsedOutput {
  as: string;
}

export interface ParsedRetry {
  maxAttempts: number;
  backoff: string;
}

export interface ParsedPosition {
  x: number;
  y: number;
}

/** One branch of a switch: `then` jumps to a step id (or `end`); `steps` (`do:` in YAML) runs a nested branch. */
export interface ParsedSwitchCase {
  when: string | null;
  then: string | null;
  isDefault: boolean;
  steps: ParsedStep[] | null;
}

export interface ParsedCatch {
  as: string | null;
  steps: ParsedStep[];
}

/**
 * One step. `with` holds the step's own parameters as the YAML spells them (null when absent);
 * container steps carry their children in `branches` (fork), `cases` (switch), `children` (for,
 * try) and `catchBlock` (try).
 */
export interface ParsedStep {
  id: string;
  name: string | null;
  type: StepType;
  with: JsonValue;
  output: ParsedOutput | null;
  assert: string | null;
  retry: ParsedRetry | null;
  timeout: string | null;
  pauseOnFailure: boolean;
  position: ParsedPosition | null;
  notes: string | null;
  branches: ParsedStep[][] | null;
  cases: ParsedSwitchCase[] | null;
  children: ParsedStep[] | null;
  catchBlock: ParsedCatch | null;
  compensate: ParsedStep[] | null;
}

export interface ParsedMetadata {
  name: string;
  title: string | null;
  noun: string | null;
  verb: string | null;
  persona: string | null;
  owner: string | null;
  folder: string | null;
  tags: string[];
  version: string | null;
  description: string | null;
}

export interface ParsedRolloutPolicy {
  waves: number[];
  maxParallel: number;
  stopIfFailedPercent: number;
}

export interface ParsedBatch {
  max: number;
  rollout: ParsedRolloutPolicy;
}

export interface ParsedApi {
  method: string | null;
  path: string | null;
  /** The input schema as written; undefined when absent, null when written as null. */
  input: Maybe;
  responseStatus: number;
  preview: boolean;
  batch: ParsedBatch | null;
  /** `api.deprecated`; left out when absent or false. */
  deprecated?: ParsedDeprecation;
}

/** `api.deprecated: { since, sunset, replacement?, message? }`, dates as written. */
export interface ParsedDeprecation {
  since: string | null;
  sunset: string | null;
  replacement: string | null;
  message: string | null;
}

export interface ParsedMcp {
  expose: boolean;
  description: string | null;
}

export interface ParsedApproval {
  required: boolean;
  group: string | null;
  timeout: string | null;
}

export interface ParsedPolicy {
  approval: ParsedApproval | null;
  rbac: string[];
  changeRequired: boolean;
}

export interface ParsedTrigger {
  type: string;
  spec: JsonValue;
}

export interface ParsedRollout {
  strategy: string;
  stages: number[];
}

export interface ParsedFlow {
  steps: ParsedStep[];
  catchFlow: ParsedFlow | null;
  outputs: Record<string, string> | null;
}

export interface ParsedWorkflow {
  apiVersion: string;
  metadata: ParsedMetadata;
  api: ParsedApi | null;
  mcp: ParsedMcp | null;
  policy: ParsedPolicy | null;
  triggers: ParsedTrigger[];
  rollout: ParsedRollout | null;
  workflow: ParsedFlow;
}

export const DEFAULT_ROLLOUT_POLICY: ParsedRolloutPolicy = { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 };
export const DEFAULT_BATCH_MAX = 500;
export const CANARY_STAGES: number[] = [10, 50, 100];

/** The `kind` of a content document, or null. */
export function kindOf(doc: unknown): string | null {
  return asTextOr(get(doc, 'kind'), null);
}

/** Parse a workflow document (the YAML already read into plain values). Throws {@link WorkflowParseError}. */
export function parseDocument(doc: unknown): ParsedWorkflow {
  const root = doc;
  if (!isObject(root)) {
    throw new WorkflowParseError('A workflow file is a YAML mapping');
  }
  const kind = asTextOr(get(root, 'kind'), null);
  if (kind !== null && kind !== WORKFLOW_KIND) {
    throw new WorkflowParseError(`kind must be Workflow, was ${kind}`);
  }
  const md = get(root, 'metadata');
  const name = asTextOr(get(md, 'name'), null);
  if (name === null || isBlank(name)) {
    throw new WorkflowParseError('metadata.name is required');
  }
  const metadata: ParsedMetadata = {
    name,
    title: text(md, 'title'),
    noun: text(md, 'noun'),
    verb: text(md, 'verb'),
    persona: text(md, 'persona'),
    owner: text(md, 'owner'),
    folder: text(md, 'folder'),
    tags: strings(get(md, 'tags')),
    version: text(md, 'version'),
    description: text(md, 'description'),
  };

  const a = get(root, 'api');
  let api: ParsedApi | null = null;
  if (isObject(a)) {
    const b = get(a, 'batch');
    let batch: ParsedBatch | null = null;
    if (isObject(b)) {
      const r = get(b, 'rollout');
      const rollout: ParsedRolloutPolicy = isObject(r)
        ? {
            waves: ints(get(r, 'waves'), DEFAULT_ROLLOUT_POLICY.waves),
            maxParallel: asInt(get(r, 'maxParallel'), DEFAULT_ROLLOUT_POLICY.maxParallel),
            stopIfFailedPercent: asInt(get(r, 'stopIfFailedPercent'), DEFAULT_ROLLOUT_POLICY.stopIfFailedPercent),
          }
        : DEFAULT_ROLLOUT_POLICY;
      batch = { max: asInt(get(b, 'max'), DEFAULT_BATCH_MAX), rollout };
    }
    api = {
      method: text(a, 'method'),
      path: text(a, 'path'),
      input: get(a, 'input'),
      responseStatus: asInt(get(get(a, 'response'), 'status'), 202),
      preview: asBoolean(get(a, 'preview'), false),
      batch,
      deprecated: parseDeprecation(get(a, 'deprecated')),
    };
  }

  const m = get(root, 'mcp');
  const mcp: ParsedMcp | null = isObject(m) ? { expose: asBoolean(get(m, 'expose'), false), description: text(m, 'description') } : null;

  const p = get(root, 'policy');
  let policy: ParsedPolicy | null = null;
  if (isObject(p)) {
    const ap = get(p, 'approval');
    const approval: ParsedApproval | null = isObject(ap)
      ? { required: asBoolean(get(ap, 'required'), true), group: text(ap, 'group'), timeout: text(ap, 'timeout') }
      : null;
    policy = { approval, rbac: strings(get(p, 'rbac')), changeRequired: asBoolean(get(get(p, 'change'), 'required'), false) };
  }

  const triggers: ParsedTrigger[] = [];
  const t = get(root, 'triggers');
  if (isArray(t)) {
    for (const tr of t) {
      if (isObject(tr) && size(tr) === 1) {
        const type = fieldNames(tr)[0] as string;
        triggers.push({ type, spec: tr[type] ?? null });
      } else if (isObject(tr) && has(tr, 'type')) {
        triggers.push({ type: asText(get(tr, 'type')), spec: tr });
      }
    }
  }

  const r = get(root, 'rollout');
  const rollout: ParsedRollout | null = isObject(r)
    ? { strategy: asTextOr(get(r, 'strategy'), 'canary'), stages: ints(get(r, 'stages'), CANARY_STAGES) }
    : null;

  const w = get(root, 'workflow');
  const workflow = parseFlow(isObject(w) ? w : {});
  return { apiVersion: asTextOr(get(root, 'apiVersion'), API_VERSION), metadata, api, mcp, policy, triggers, rollout, workflow };
}

/** `api.deprecated`: absent or false is undefined; anything but a mapping is an empty block the checks report. */
export function parseDeprecation(d: Maybe): ParsedDeprecation | undefined {
  if (d === undefined || d === null || d === false) {
    return undefined;
  }
  if (!isObject(d)) {
    return { since: null, sunset: null, replacement: null, message: null };
  }
  return { since: text(d, 'since'), sunset: text(d, 'sunset'), replacement: text(d, 'replacement'), message: text(d, 'message') };
}

function parseFlow(w: JsonObject): ParsedFlow {
  const steps = parseSteps(get(w, 'do'), 'workflow.do');
  let catchFlow: ParsedFlow | null = null;
  const c = get(w, 'catch');
  if (isObject(c)) {
    catchFlow = { steps: parseSteps(get(c, 'do'), 'workflow.catch.do'), catchFlow: null, outputs: null };
  }
  let outputs: Record<string, string> | null = null;
  const o = get(w, 'outputs');
  if (isObject(o)) {
    outputs = {};
    for (const k of fieldNames(o)) {
      outputs[k] = asText(o[k]);
    }
  }
  return { steps, catchFlow, outputs };
}

/** A `do` list: every entry is `- <stepId>: { ... }`. */
export function parseSteps(list: unknown, where: string): ParsedStep[] {
  const out: ParsedStep[] = [];
  if (list === undefined || list === null) {
    return out;
  }
  if (!isArray(list)) {
    throw new WorkflowParseError(`${where} must be a list of steps`);
  }
  for (const item of list) {
    if (!isObject(item) || size(item) !== 1) {
      throw new WorkflowParseError(`Each entry of ${where} is '- <stepId>: { ... }'`);
    }
    const id = fieldNames(item)[0] as string;
    const body = item[id];
    if (!isObject(body)) {
      throw new WorkflowParseError(`Step '${id}' must be a mapping`);
    }
    out.push(parseStep(id, body));
  }
  return out;
}

function merge(primary: JsonObject, secondary: Maybe): JsonObject {
  if (!isObject(secondary)) {
    return primary;
  }
  return { ...secondary, ...primary };
}

/** One step body: exactly one step key plus the common keys. */
export function parseStep(id: string, body: JsonObject): ParsedStep {
  let type: StepType | null = null;
  let withValue: Maybe = get(body, 'with');
  let branches: ParsedStep[][] | null = null;
  let cases: ParsedSwitchCase[] | null = null;
  let children: ParsedStep[] | null = null;
  let catchBlock: ParsedCatch | null = null;

  for (const k of fieldNames(body)) {
    if (COMMON_KEYS.includes(k)) {
      continue;
    }
    if (type !== null) {
      throw new WorkflowParseError(`Step '${id}' has two step keys: ${STEP_TYPES[type].yamlKey} and ${k}`);
    }
    const v = body[k] as JsonValue;
    switch (k) {
      case 'call': {
        const kind = asText(v);
        if (!(CALL_KINDS as readonly string[]).includes(kind) && kind !== 'approval') {
          throw new WorkflowParseError(`Step '${id}': call must be one of [${CALL_KINDS.join(', ')}], was '${kind}'`);
        }
        type = kind as StepType;
        break;
      }
      case 'run':
      case 'approval':
      case 'set':
      case 'raise':
      case 'use':
      case 'register':
      case 'notify':
      case 'script':
        type = k;
        withValue = isObject(v) ? merge(v, withValue) : withValue;
        break;
      case 'switch': {
        type = 'switch';
        cases = [];
        if (!isArray(v)) {
          throw new WorkflowParseError(`Step '${id}': switch must be a list of cases`);
        }
        let ci = 0;
        for (const c of v) {
          const cs = get(c, 'case');
          if (cs !== undefined) {
            const branch = has(cs, 'do') ? parseSteps(get(cs, 'do'), `${id}.switch[${ci}].do`) : null;
            cases.push({ when: asTextOr(get(cs, 'when'), null), then: asTextOr(get(cs, 'then'), null), isDefault: false, steps: branch });
          } else if (has(c, 'default')) {
            const d = get(c, 'default');
            const branch = isObject(d) && has(d, 'do') ? parseSteps(get(d, 'do'), `${id}.switch[default].do`) : null;
            cases.push({ when: null, then: isObject(d) ? asTextOr(get(d, 'then'), null) : asText(d), isDefault: true, steps: branch });
          } else {
            throw new WorkflowParseError(`Step '${id}': each switch entry is 'case: { when, then | do }' or 'default: ...'`);
          }
          ci++;
        }
        break;
      }
      case 'fork': {
        type = 'fork';
        branches = [];
        const bs = get(v, 'branches');
        if (!isArray(bs)) {
          throw new WorkflowParseError(`Step '${id}': fork needs branches: [[...], [...]]`);
        }
        let i = 0;
        for (const b of bs) {
          branches.push(parseSteps(b, `${id}.branches[${i++}]`));
        }
        break;
      }
      case 'for': {
        type = 'for';
        const w: JsonObject = { each: get(v, 'each') ?? null, in: get(v, 'in') ?? null };
        withValue = merge(w, withValue);
        children = parseSteps(get(v, 'do'), `${id}.do`);
        break;
      }
      case 'try': {
        type = 'try';
        children = parseSteps(get(v, 'do'), `${id}.do`);
        const c = get(v, 'catch');
        if (isObject(c)) {
          catchBlock = { as: asTextOr(get(c, 'as'), 'error'), steps: parseSteps(get(c, 'do'), `${id}.catch.do`) };
        }
        break;
      }
      case 'wait': {
        type = 'wait';
        const w: JsonObject = isObject(v) ? { ...v } : { duration: asText(v) };
        withValue = merge(w, withValue);
        break;
      }
      default:
        throw new WorkflowParseError(`Step '${id}': unknown key '${k}'`);
    }
  }
  if (type === null) {
    throw new WorkflowParseError(`Step '${id}' has no step key (call, run, approval, switch, fork, for, try, wait, set, raise, use, register, notify, script)`);
  }
  const o = get(body, 'output');
  const output: ParsedOutput | null = isObject(o) && has(o, 'as') ? { as: asText(get(o, 'as')) } : null;
  const rt = get(body, 'retry');
  const retry: ParsedRetry | null = isObject(rt) ? { maxAttempts: asInt(get(rt, 'maxAttempts'), 1), backoff: asTextOr(get(rt, 'backoff'), 'none') } : null;
  const pos = get(body, 'position');
  const position: ParsedPosition | null = isObject(pos) ? { x: asDouble(get(pos, 'x')), y: asDouble(get(pos, 'y')) } : null;
  const compensate = has(body, 'compensate') ? parseSteps(get(body, 'compensate'), `${id}.compensate`) : null;
  return {
    id,
    name: text(body, 'name'),
    type,
    with: withValue === undefined ? null : withValue,
    output,
    assert: text(body, 'assert'),
    retry,
    timeout: text(body, 'timeout'),
    pauseOnFailure: asBoolean(get(body, 'pauseOnFailure'), true),
    position,
    notes: text(body, 'notes'),
    branches,
    cases,
    children,
    catchBlock,
    compensate,
  };
}

// ---------------------------------------------------------------- walking

/** The step and every step nested inside it, depth first. */
export function flatten(step: ParsedStep): ParsedStep[] {
  const out: ParsedStep[] = [step];
  step.branches?.forEach((b) => b.forEach((s) => out.push(...flatten(s))));
  step.cases?.forEach((c) => c.steps?.forEach((s) => out.push(...flatten(s))));
  step.children?.forEach((s) => out.push(...flatten(s)));
  step.catchBlock?.steps.forEach((s) => out.push(...flatten(s)));
  step.compensate?.forEach((s) => out.push(...flatten(s)));
  return out;
}

/** Every step at every depth, in document order, the catch flow included. */
export function allSteps(def: ParsedWorkflow): ParsedStep[] {
  const out: ParsedStep[] = [];
  def.workflow.steps.forEach((s) => out.push(...flatten(s)));
  def.workflow.catchFlow?.steps.forEach((s) => out.push(...flatten(s)));
  return out;
}

/** A step by id or name. */
export function findStep(def: ParsedWorkflow, idOrName: string): ParsedStep | undefined {
  return allSteps(def).find((s) => s.id === idOrName || s.name === idOrName);
}

export function displayName(step: ParsedStep): string {
  return step.name === null || isBlank(step.name) ? step.id : step.name;
}

/** A `with` field as text, or null when absent or null. */
export function withText(step: ParsedStep, field: string): string | null {
  return text(step.with, field);
}

/** A `with` field as written; undefined when absent. */
export function withNode(step: ParsedStep, field: string): Maybe {
  return get(step.with, field);
}

export function mcpExposed(def: ParsedWorkflow): boolean {
  return def.mcp !== null && def.mcp.expose;
}
