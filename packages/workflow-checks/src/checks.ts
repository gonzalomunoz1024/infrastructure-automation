/**
 * The live checks: the same rules run in the designer as the user edits, on the validate
 * endpoint, at publish and in CI, so a problem on the canvas is the problem CI would report.
 * Pure: the only lookups go through the {@link CheckContext}. Every rule mirrors
 * WorkflowChecks.java, with the same codes, levels, fixes and messages; the shared fixtures under
 * ./fixtures prove both sides agree.
 */
import { isTemplate, references, syntaxError, type Reference } from './expressions.ts';
import { asText, asTextOr, elements, fieldNames, get, has, isArray, isBlank, isObject, isTextual, size, toJson, type Maybe } from './json.ts';
import { allSteps, displayName, mcpExposed, parseDocument, withNode, withText, WORKFLOW_KIND, WorkflowParseError, type ParsedStep, type ParsedWorkflow } from './parse.ts';
import { actionApprovalRequired, actionEffect, actionIds, findAction, isDestructive, providerName } from './providers.ts';
import { describeRemoteRef, dotted, isClosed, mergeSchema, metaViolations, remoteRef, sameJson, schemaPaths } from './schemas.ts';
import { validateWorkflowDocument } from './workflow-schema.ts';
import { parseYamlText, YamlSyntaxError } from './yaml-text.ts';
import { STEP_TYPES } from './step-types.ts';
import { closest } from './suggest.ts';
import type { CheckContext, ContextSchemas, Fix, JsonObject, JsonSchema, JsonValue, Problem, ProblemLevel, ProviderDocument, ResourceTypeDocument } from './types.ts';

const NAME = /^[a-z][a-z0-9-]*$/;
const STEP_ID = /^[a-z][A-Za-z0-9]*$/;
const IDENTIFIER = /^[a-zA-Z_][a-zA-Z0-9_]*$/;
const SEMVER = /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/;
const SEMVER_RANGE = /^(\*|[~^]?\d+(\.\d+){0,2}(\.x)?|latest)$/;
const DURATION = /^(P(?!$)(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?|\d+(ms|s|m|h|d))$/;
const SECRET_KEY = /(password|passwd|secret|token|apikey|api_key|credential|privateKey)/i;
const METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']);
const BACKOFFS = new Set(['none', 'fixed', 'exponential']);
const TERRAFORM_ACTIONS = new Set(['plan', 'apply', 'destroy']);
const ON_FAILURE: readonly string[] = ['fail', 'continue'];
const API_PREFIX = /^\/api\/v[1-9][0-9]*\//;
const DATE = /^(\d{4})-(\d{2})-(\d{2})(T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2}))?$/;
/** An upload's digest as POST /playbooks:upload answers it: 64 lowercase hex digits. */
const DIGEST = /^[0-9a-f]{64}$/;

/** The roots an expression can always start from; an alias may not shadow them. */
export const ROOTS: readonly string[] = ['json', 'input', 'vars', 'job', 'env', 'error'];

/**
 * The first path segments under /api/v<n>/ that belong to the platform: the gateway's list,
 * ReservedNouns.java in the backend. The test reserved-nouns.test.ts keeps the two the same.
 */
export const RESERVED_NOUNS: readonly string[] = [
  'workflows',
  'jobs',
  'approvals',
  'providers',
  'releases',
  'audit',
  'resources',
  'resource-types',
  'credentials',
  'registry',
  'catalog',
  'assistant',
  'triggers',
  'change-requests',
  'templates',
  'service-accounts',
  'me',
  'mcp',
  'openapi.json',
  'openapi',
  'batches',
  'evidence',
  'ws',
  'actuator',
  'playbooks',
  'notifications',
  'schemas',
];

/** A context without a catalog: unknown providers, workflows and resource types are not reported. */
export const EMPTY_CONTEXT: CheckContext = { providers: {}, workflows: [], resourceTypes: [], knowsCatalog: false };

/**
 * A context over parsed provider documents, published workflow names and resource type names.
 * With `schemas` (the published workflows' inputs and outputs, the resource types' attribute
 * schemas) the contract of run steps and the fields a step returns are judged too.
 */
export function buildContext(
  providers: Iterable<ProviderDocument>,
  workflowNames: Iterable<string>,
  resourceTypes: Iterable<string>,
  schemas: ContextSchemas = {},
): CheckContext {
  const byName: Record<string, ProviderDocument> = {};
  for (const p of providers) {
    byName[providerName(p)] = p;
  }
  return { providers: byName, workflows: [...workflowNames], resourceTypes: [...resourceTypes], knowsCatalog: true, ...schemas };
}

/**
 * The schemas {@link buildContext} takes, read from published workflows and resource types the
 * way CheckContext.of reads them in Java: each workflow's `api.input` and output names, each
 * type's attribute schema.
 */
export function contextSchemas(workflows: Iterable<ParsedWorkflow>, resourceTypes: Iterable<ResourceTypeDocument>): Required<ContextSchemas> {
  const workflowInputs: Record<string, JsonSchema> = {};
  const workflowOutputs: Record<string, string[]> = {};
  const resourceTypeSchemas: Record<string, JsonSchema> = {};
  for (const w of workflows) {
    const input = w.api === null ? undefined : w.api.input;
    if (isObject(input)) {
      workflowInputs[w.metadata.name] = input as JsonSchema;
    }
    workflowOutputs[w.metadata.name] = w.workflow.outputs === null ? [] : Object.keys(w.workflow.outputs);
  }
  for (const t of resourceTypes) {
    const schema = get(t, 'schema');
    if (isObject(schema)) {
      resourceTypeSchemas[asText(get(get(t, 'metadata'), 'name'))] = schema as JsonSchema;
    }
  }
  return { workflowInputs, workflowOutputs, resourceTypeSchemas };
}

/**
 * Check YAML text: a syntax error, a key written twice among them, is one `yaml.invalid` problem;
 * otherwise the document is checked as {@link check} says.
 */
export function checkYaml(yaml: string, ctx: CheckContext): Problem[] {
  let doc: unknown;
  try {
    doc = parseYamlText(yaml);
  } catch (e) {
    if (e instanceof YamlSyntaxError) {
      return [problem('error', 'yaml.invalid', null, null, e.message)];
    }
    throw e;
  }
  return check(doc, ctx);
}

/**
 * Check a document (the YAML already read into plain values) in three lines: the Workflow schema
 * first (schemas/workflow.schema.json: one `schema.invalid` per violation, and nothing else when
 * there is one), then the parser (a structure error is one `yaml.invalid`), then the rules. A file
 * of another kind goes straight to the parser, which names its kind.
 */
export function check(doc: unknown, ctx: CheckContext): Problem[] {
  if (isObject(doc) && !(typeof doc['kind'] === 'string' && doc['kind'] !== WORKFLOW_KIND)) {
    const schema = validateWorkflowDocument(doc);
    if (schema.length > 0) {
      return schema;
    }
  }
  let def: ParsedWorkflow;
  try {
    def = parseDocument(doc);
  } catch (e) {
    if (e instanceof WorkflowParseError) {
      return [problem('error', 'yaml.invalid', null, null, e.message)];
    }
    throw e;
  }
  return checkParsed(def, ctx);
}

/** The rule set over an already parsed workflow: the document's schema is not judged again. */
export function checkParsed(def: ParsedWorkflow, ctx: CheckContext): Problem[] {
  const c = new Checker(def, ctx);
  c.run();
  return c.problems;
}

export function hasErrors(problems: readonly Problem[]): boolean {
  return problems.some((p) => p.level === 'error');
}

/**
 * api.input as the gateway, the MCP server and the catalog form will use it: a schema that refers
 * outside itself is `schema.remoteRef` (nothing is fetched, so the reference never resolves), else
 * one the validator does not compile is `api.input.invalid`, one problem per location with the
 * validator's message. Empty for a usable schema. Mirrors WorkflowChecks.inputSchemaProblems.
 */
export function inputSchemaProblems(input: unknown): Problem[] {
  const remote = remoteRefProblem(input, 'api.input');
  if (remote !== null) {
    return [remote];
  }
  return metaViolations(input).map((v) => {
    const where = dotted(v.pointer);
    return problem('error', 'api.input.invalid', null, `api.input${where}`, `api.input is not a valid JSON Schema: ${where === '' ? '' : `${where.substring(1)}: `}${v.message}`);
  });
}

/** A `$ref` out of a schema the file carries (`field`): `schema.remoteRef` at the reference. */
function remoteRefProblem(schema: unknown, field: string): Problem | null {
  const r = remoteRef(schema);
  return r === null ? null : problem('error', 'schema.remoteRef', null, `${field}${dotted(r.pointer)}`, describeRemoteRef(r, field));
}

function problem(level: ProblemLevel, code: string, step: string | null, field: string | null, message: string, fix?: Fix | null): Problem {
  return {
    level,
    code,
    ...(step !== null ? { step } : {}),
    ...(field !== null ? { field } : {}),
    message,
    ...(fix ? { fix } : {}),
  };
}

export function replaceFix(label: string, value: string): Fix {
  return { label, kind: 'replace', value };
}

export function insertApprovalFix(label: string): Fix {
  return { label, kind: 'insert-approval' };
}

export function connectFix(label: string): Fix {
  return { label, kind: 'connect' };
}

/** A `YYYY-MM-DD` date (midnight UTC) or an ISO date-time with its offset, in epoch seconds; null when it is neither. */
function epochSecond(s: string | null): number | null {
  if (s === null) {
    return null;
  }
  const t = s.trim();
  const m = DATE.exec(t);
  if (m === null) {
    return null;
  }
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const day = new Date(0);
  day.setUTCFullYear(y, mo - 1, d);
  if (day.getUTCFullYear() !== y || day.getUTCMonth() !== mo - 1 || day.getUTCDate() !== d) {
    return null;
  }
  if (m[4] === undefined) {
    return day.getTime() / 1000;
  }
  const ms = Date.parse(t);
  return Number.isNaN(ms) ? null : Math.floor(ms / 1000);
}

/** Steps that hold other steps: the containers (fork, for, try) and a switch, whose `do` cases are branches; WorkflowChecks.java names the same four. */
function isContainerStep(s: ParsedStep): boolean {
  return STEP_TYPES[s.type].isContainer || s.type === 'switch';
}

/** What an expression can see at one point of the flow. */
class Scope {
  /** ids and names of steps that ran before */
  stepNames = new Set<string>();
  /** output.as, catch.as, for.each */
  aliases = new Set<string>();
  /** set keys */
  vars = new Set<string>();
  hasPredecessor = false;
  inCatch = false;
  /** an approval step ran before */
  approved = false;
  /** the schema of $json, null when unknown */
  previous: JsonObject | null = null;

  copy(): Scope {
    const s = new Scope();
    s.stepNames = new Set(this.stepNames);
    s.aliases = new Set(this.aliases);
    s.vars = new Set(this.vars);
    s.hasPredecessor = this.hasPredecessor;
    s.inCatch = this.inCatch;
    s.approved = this.approved;
    s.previous = this.previous;
    return s;
  }

  merge(other: Scope): void {
    other.stepNames.forEach((n) => this.stepNames.add(n));
    other.aliases.forEach((a) => this.aliases.add(a));
    other.vars.forEach((v) => this.vars.add(v));
    this.approved = this.approved || other.approved;
  }
}

class Checker {
  readonly def: ParsedWorkflow;
  readonly ctx: CheckContext;
  readonly problems: Problem[] = [];
  readonly byId = new Map<string, ParsedStep>();
  readonly allNames = new Set<string>();
  readonly inputFields = new Set<string>();
  /** step id and name -> output schema (null: unknown) */
  readonly stepOutputs = new Map<string, JsonObject | null>();
  /** output.as -> output schema (null: unknown) */
  readonly aliasOutputs = new Map<string, JsonObject | null>();
  readonly policyApproval: boolean;

  constructor(def: ParsedWorkflow, ctx: CheckContext) {
    this.def = def;
    this.ctx = ctx;
    this.policyApproval = def.policy !== null && def.policy.approval !== null && def.policy.approval.required;
  }

  run(): void {
    this.metadata();
    this.api();
    this.deprecation();
    this.responseBody();
    this.policy();
    this.rollout();
    this.steps();
    this.triggers();
  }

  // ------------------------------------------------------------ definition level

  metadata(): void {
    const m = this.def.metadata;
    if (!NAME.test(m.name)) {
      this.error('name.format', null, 'metadata.name', 'The name is the file name and the URL: lowercase letters, digits and dashes, like vm-restart');
    }
    if (m.title === null || isBlank(m.title)) {
      this.warning('metadata.title', null, 'metadata.title', 'Give the workflow a title; the catalog and the MCP tool show it');
    }
    const needsNounVerb = mcpExposed(this.def) || this.def.api === null || this.def.api.path === null;
    if (m.noun === null || isBlank(m.noun) || m.verb === null || isBlank(m.verb)) {
      if (needsNounVerb) {
        this.error('metadata.nounVerb', null, 'metadata.noun', 'noun and verb are required: they name the endpoint and the MCP tool (vm + restart -> vm_restart)');
      } else {
        this.warning('metadata.nounVerb', null, 'metadata.noun', 'Set noun and verb so the catalog can group this workflow');
      }
    }
    if (m.owner === null || isBlank(m.owner)) {
      this.error('metadata.owner', null, 'metadata.owner', 'owner is the team that may edit and publish this workflow');
    } else if (!NAME.test(m.owner)) {
      this.error('metadata.owner', null, 'metadata.owner', 'owner is a team name and a folder: lowercase letters, digits and dashes, like team-compute');
    }
    if (m.version !== null && !SEMVER.test(m.version)) {
      this.error('version.semver', null, 'metadata.version', 'version must be semantic, like 1.2.0');
    }
  }

  api(): void {
    const a = this.def.api;
    if (a === null) {
      return;
    }
    if (a.method !== null && !METHODS.has(a.method.toUpperCase())) {
      this.error('api.method', null, 'api.method', 'method must be one of GET, POST, PUT, PATCH, DELETE');
    }
    if (a.path !== null) {
      const prefix = API_PREFIX.exec(a.path);
      if (prefix === null) {
        this.error('api.path', null, 'api.path', 'Generated paths live under /api/v<n>/, like /api/v1/ or /api/v2/');
      } else {
        const first = a.path.substring(prefix[0].length).split(/[/:?]/)[0] as string;
        if (RESERVED_NOUNS.includes(first)) {
          this.error('api.reservedPath', null, 'api.path', `${prefix[0]}${first} belongs to the platform; pick another noun`);
        }
      }
    }
    const input = a.input;
    if (input !== undefined) {
      if (!isObject(input) || (has(input, 'type') && asText(get(input, 'type')) !== 'object')) {
        this.error('api.input', null, 'api.input', 'api.input is a JSON Schema of type object');
      } else {
        this.problems.push(...inputSchemaProblems(input));
        const properties = get(input, 'properties');
        fieldNames(properties).forEach((f) => this.inputFields.add(f));
        for (const r of elements(get(input, 'required'))) {
          if (!this.inputFields.has(asText(r))) {
            this.error('api.inputRequired', null, 'api.input.required', `Required field '${asText(r)}' is not among the properties`);
          }
        }
        for (const key of fieldNames(properties)) {
          const xr = get(get(properties, key), 'x-resource');
          if (isObject(xr)) {
            const type = asTextOr(get(xr, 'type'), null);
            if (type === null) {
              this.error('api.resourceType', null, `api.input.${key}`, 'x-resource needs a type');
            } else if (this.ctx.knowsCatalog && !this.ctx.resourceTypes.includes(type)) {
              this.error('resource.unknownType', null, `api.input.${key}`, `Unknown resource type '${type}'`, this.suggest(type, this.ctx.resourceTypes));
            }
            const create = asTextOr(get(xr, 'orCreateWith'), null);
            if (create !== null && this.ctx.knowsCatalog && !this.ctx.workflows.includes(create) && create !== this.def.metadata.name) {
              this.error('run.unknownWorkflow', null, `api.input.${key}`, `orCreateWith names a workflow that is not published: '${create}'`, this.suggest(create, this.ctx.workflows));
            }
          }
        }
      }
    }
    if (a.batch !== null) {
      if (a.batch.max < 1) {
        this.error('batch.max', null, 'api.batch.max', 'batch.max must be at least 1');
      }
      this.ascending(a.batch.rollout.waves, 'batch.waves', 'api.batch.rollout.waves', false);
      if (a.batch.rollout.maxParallel < 1) {
        this.error('batch.maxParallel', null, 'api.batch.rollout.maxParallel', 'maxParallel must be at least 1');
      }
      const stop = a.batch.rollout.stopIfFailedPercent;
      if (stop < 0 || stop > 100) {
        this.error('batch.stop', null, 'api.batch.rollout.stopIfFailedPercent', 'stopIfFailedPercent is a percentage');
      }
    }
  }

  /**
   * `api.deprecated: { since, sunset, replacement?, message? }`: both dates required (`YYYY-MM-DD`
   * or an ISO date-time), the sunset after the deprecation, and the replacement a path under
   * `/api/v<n>/` or an http(s) URL other than this operation.
   */
  deprecation(): void {
    const d = this.def.api?.deprecated;
    if (d === undefined) {
      return;
    }
    const since = epochSecond(d.since);
    const sunset = epochSecond(d.sunset);
    if (since === null) {
      this.error('deprecated.since', null, 'api.deprecated.since', 'since is the date the operation was deprecated, like 2026-11-01');
    }
    if (sunset === null) {
      this.error('deprecated.sunset', null, 'api.deprecated.sunset', 'sunset is the date the operation goes away, like 2027-05-01');
    } else if (since !== null && sunset <= since) {
      this.error('deprecated.sunset', null, 'api.deprecated.sunset', 'sunset must come after since');
    }
    const r = d.replacement;
    if (r !== null) {
      const url = r.startsWith('https://') || r.startsWith('http://');
      if (!url && !API_PREFIX.test(r)) {
        this.error('deprecated.replacement', null, 'api.deprecated.replacement', "replacement is the successor's path, like /api/v2/vms/{vmId}:restart, or a URL");
      } else if (this.def.api !== null && r === this.def.api.path) {
        this.error('deprecated.replacement', null, 'api.deprecated.replacement', 'replacement names this operation itself');
      }
    }
  }

  /**
   * `api.response.body`: the JSON Schema of the job's output, of type object. The output holds
   * exactly the keys of `workflow.outputs`, so a property the outputs never set is a warning.
   */
  responseBody(): void {
    const body = this.def.api?.responseBody;
    if (body === undefined) {
      return;
    }
    if (!isObject(body) || (has(body, 'type') && asText(get(body, 'type')) !== 'object')) {
      this.error('api.responseBody', null, 'api.response.body', "api.response.body is a JSON Schema of the job's output, of type object");
      return;
    }
    const remote = remoteRefProblem(body, 'api.response.body');
    if (remote !== null) {
      this.problems.push(remote);
    }
    const outputs = Object.keys(this.def.workflow.outputs ?? {});
    for (const name of fieldNames(get(body, 'properties'))) {
      if (!outputs.includes(name)) {
        this.warning(
          'api.responseOutput',
          null,
          `api.response.body.${name}`,
          outputs.length === 0
            ? `The job has no output: workflow.outputs sets nothing, so '${name}' never comes back`
            : `workflow.outputs does not set '${name}' (it sets ${outputs.join(', ')})`,
        );
      }
    }
  }

  policy(): void {
    const p = this.def.policy;
    if (p === null || p.rbac.length === 0) {
      this.error('policy.missing', null, p === null ? 'policy' : 'policy.rbac', 'Every workflow says who may run it: add a policy block with rbac, the roles allowed to invoke it (for example [ops])');
      if (p === null) {
        return;
      }
    }
    if (p.approval !== null && p.approval.required && (p.approval.group === null || isBlank(p.approval.group))) {
      this.error('approval.group', null, 'policy.approval.group', 'A required approval needs a group of approvers');
    }
    if (p.approval !== null && p.approval.timeout !== null && !DURATION.test(p.approval.timeout)) {
      this.error('duration.format', null, 'policy.approval.timeout', 'timeout is a duration like 4h, 30m or PT4H');
    }
  }

  rollout(): void {
    const r = this.def.rollout;
    if (r === null) {
      return;
    }
    if (r.strategy !== 'canary' && r.strategy !== 'all') {
      this.error('rollout.strategy', null, 'rollout.strategy', 'strategy is canary or all');
    }
    if (r.strategy === 'canary') {
      this.ascending(r.stages, 'rollout.stages', 'rollout.stages', true);
    }
  }

  ascending(values: number[] | null, code: string, field: string, endsAt100: boolean): void {
    if (values === null || values.length === 0) {
      this.error(code, null, field, `${field} needs at least one value`);
      return;
    }
    let prev = 0;
    for (const v of values) {
      if (v <= prev || (v > 100 && endsAt100)) {
        this.error(code, null, field, `${field} must be ascending percentages${endsAt100 ? ' ending at 100' : ''}`);
        return;
      }
      prev = v;
    }
    if (endsAt100 && prev !== 100) {
      this.error(code, null, field, `${field} must end at 100 so the release completes`);
    }
  }

  triggers(): void {
    let i = 0;
    for (const t of this.def.triggers) {
      const field = `triggers[${i++}]`;
      switch (t.type) {
        case 'alert': {
          if (!isObject(get(t.spec, 'match'))) {
            this.error('trigger.match', null, field, 'An alert trigger needs match: { source, labels }');
          }
          const s = new Scope();
          s.aliases.add('alert');
          this.expressions(get(t.spec, 'input'), null, `${field}.input`, s);
          break;
        }
        case 'event': {
          if (asTextOr(get(t.spec, 'topic'), null) === null) {
            this.error('trigger.topic', null, field, 'An event trigger needs a topic');
          }
          const s = new Scope();
          s.aliases.add('event');
          this.expressions(get(t.spec, 'input'), null, `${field}.input`, s);
          break;
        }
        case 'schedule':
          this.error('trigger.schedule', null, field, 'Schedules are not supported; use an alert or the API');
          break;
        default:
          this.error('trigger.type', null, field, `Unknown trigger type '${t.type}' (alert, event)`);
      }
    }
  }

  // ------------------------------------------------------------ steps

  steps(): void {
    const all = allSteps(this.def);
    if (this.def.workflow.steps.length === 0) {
      this.error('workflow.empty', null, 'workflow.do', 'The workflow has no steps');
    }
    const seen = new Set<string>();
    for (const s of all) {
      if (seen.has(s.id)) {
        this.error('step.duplicateId', s.id, null, `Step id '${s.id}' is used twice`);
      } else {
        seen.add(s.id);
        this.byId.set(s.id, s);
      }
      if (!STEP_ID.test(s.id)) {
        this.warning('step.idFormat', s.id, null, 'Step ids are camelCase, like checkVmExists');
      }
      this.allNames.add(s.id);
      if (s.name !== null) {
        this.allNames.add(s.name);
      }
    }
    this.outputs(all);
    const scope = this.walk(this.def.workflow.steps, new Scope());
    const flow = this.def.workflow;
    if (flow.catchFlow !== null) {
      const c = scope.copy();
      c.inCatch = true;
      c.hasPredecessor = true;
      c.previous = null;
      this.walk(flow.catchFlow.steps, c);
    }
    if (flow.outputs !== null) {
      for (const [key, value] of Object.entries(flow.outputs)) {
        this.template(value, null, `workflow.outputs.${key}`, scope);
      }
    }
  }

  /** Walks a step list in order and returns the scope after it. */
  walk(steps: ParsedStep[], start: Scope): Scope {
    const scope = start.copy();
    const siblings = steps.map((s) => s.id);
    this.unreachable(steps);
    const targets = jumpTargets(steps);
    for (let i = 0; i < steps.length; i++) {
      const s = steps[i] as ParsedStep;
      if (targets.has(s.id)) {
        scope.previous = null; // reached from a switch too: $json is not one step's output
      }
      this.step(s, scope, siblings.slice(i + 1));
      // after the step
      scope.stepNames.add(s.id);
      if (s.name !== null) {
        scope.stepNames.add(s.name);
      }
      if (s.output !== null) {
        const alias = s.output.as;
        if (!IDENTIFIER.test(alias)) {
          this.error('output.alias', s.id, 'output.as', 'An alias is an identifier, like vm');
        } else if (ROOTS.includes(alias)) {
          this.error('output.alias', s.id, 'output.as', `'${alias}' is a built-in reference; pick another alias`);
        }
        scope.aliases.add(alias);
      }
      if (s.type === 'set' && s.with !== null) {
        fieldNames(s.with).forEach((v) => scope.vars.add(v));
      }
      if (s.type === 'approval') {
        scope.approved = true;
      }
      if (!isContainerStep(s)) {
        scope.hasPredecessor = true;
        scope.previous = this.stepOutputs.get(s.id) ?? null;
      } else {
        scope.previous = null;
      }
    }
    return scope;
  }

  step(s: ParsedStep, scope: Scope, later: string[]): void {
    this.common(s);
    switch (s.type) {
      case 'provider':
        this.provider(s, scope);
        break;
      case 'http':
        this.http(s, scope);
        break;
      case 'ansible':
        this.ansible(s);
        break;
      case 'terraform':
        this.terraform(s, scope);
        break;
      case 'idp':
        this.require(s, 'tile', 'step.missing', 'An IDP step names the tile to run');
        break;
      case 'activity':
        this.require(s, 'taskQueue', 'step.missing', "An activity step names the team's task queue");
        this.require(s, 'activity', 'step.missing', 'An activity step names the activity to run');
        break;
      case 'run':
        this.runStep(s);
        break;
      case 'approval':
        this.require(s, 'group', 'approval.group', 'An approval needs a group of approvers');
        if (withText(s, 'message') === null) {
          this.warning('approval.message', s.id, 'message', 'Tell the approver what they approve');
        }
        this.duration(withText(s, 'timeout'), s.id, 'timeout');
        break;
      case 'switch':
        this.switchStep(s, scope, later);
        break;
      case 'fork':
        this.fork(s, scope);
        break;
      case 'for':
        this.forStep(s, scope);
        break;
      case 'try':
        this.tryStep(s, scope);
        break;
      case 'wait': {
        const d = withText(s, 'duration');
        if (d === null && withText(s, 'until') === null) {
          this.error('wait.duration', s.id, null, 'wait needs a duration like PT5M or until: <expression>');
        } else if (d !== null && !DURATION.test(d)) {
          this.error('duration.format', s.id, 'duration', `'${d}' is not a duration; use PT5M, 30m or 2h`);
        }
        break;
      }
      case 'set':
        if (!isObject(s.with) || size(s.with) === 0) {
          this.error('set.empty', s.id, null, 'set needs at least one variable');
        }
        break;
      case 'raise':
        this.require(s, 'error', 'step.missing', 'raise needs an error name');
        break;
      case 'use':
        this.resourceType(s);
        if (withText(s, 'id') === null && withNode(s, 'selector') === undefined) {
          this.error('step.missing', s.id, 'id', 'use needs the id of the resource (or a selector)');
        }
        break;
      case 'register': {
        this.resourceType(s);
        const attrs = withNode(s, 'attributes');
        if (attrs !== undefined && !isObject(attrs)) {
          this.error('step.invalidJson', s.id, 'attributes', 'attributes is an object');
        }
        break;
      }
      case 'notify':
        this.require(s, 'channel', 'step.missing', 'notify needs a channel');
        this.require(s, 'message', 'step.missing', 'notify needs a message');
        break;
      case 'script': {
        const lang = withText(s, 'language');
        if (lang !== null && !['javascript', 'python', 'bash', 'go'].includes(lang)) {
          this.error('script.language', s.id, 'language', 'Scripts are javascript, python, bash or go');
        }
        this.require(s, 'code', 'step.missing', 'A script needs code');
        break;
      }
    }
    if (!isContainerStep(s)) {
      this.expressions(s.with, s.id, null, scope);
    }
    if (s.assert !== null) {
      if (!isTemplate(s.assert)) {
        this.warning('assert.literal', s.id, 'assert', 'assert is an expression like {{ $json.ok == true }}');
      } else {
        const after = scope.copy();
        after.hasPredecessor = true; // $json in assert is this step's own output
        after.previous = this.stepOutputs.get(s.id) ?? null;
        this.template(s.assert, s.id, 'assert', after);
      }
    }
    if (s.compensate !== null) {
      // compensation undoes this step after a later failure: it sees the step's own output
      // and may delete what the step created without a fresh approval
      const c = scope.copy();
      c.hasPredecessor = true;
      c.previous = this.stepOutputs.get(s.id) ?? null;
      c.approved = true;
      c.stepNames.add(s.id);
      if (s.name !== null) {
        c.stepNames.add(s.name);
      }
      if (s.output !== null) {
        c.aliases.add(s.output.as);
      }
      this.walk(s.compensate, c);
    }
  }

  common(s: ParsedStep): void {
    this.duration(s.timeout, s.id, 'timeout');
    if (s.retry !== null) {
      if (s.retry.maxAttempts < 1) {
        this.error('retry.attempts', s.id, 'retry.maxAttempts', 'maxAttempts is at least 1');
      }
      if (!BACKOFFS.has(s.retry.backoff)) {
        this.error('retry.backoff', s.id, 'retry.backoff', 'backoff is none, fixed or exponential');
      }
    }
    this.secrets(s.with, s.id, '');
  }

  secrets(node: unknown, step: string | null, path: string): void {
    if (isObject(node)) {
      for (const key of fieldNames(node)) {
        const p = path.length === 0 ? key : `${path}.${key}`;
        const v = node[key];
        if (isTextual(v) && SECRET_KEY.test(key) && !v.startsWith('vault:') && !isTemplate(v) && !isBlank(v)) {
          this.error('credential.plain', step, p, 'Secrets never go in a workflow file; reference Vault with vault:<path>', replaceFix('Use a Vault reference', `vault:${step === null ? '' : `${step}/`}${key}`));
        } else {
          this.secrets(v, step, p);
        }
      }
    } else if (Array.isArray(node)) {
      let i = 0;
      for (const v of node) {
        this.secrets(v, step, `${path}[${i++}]`);
      }
    }
  }

  duration(value: string | null, step: string | null, field: string): void {
    if (value !== null && !DURATION.test(value)) {
      this.error('duration.format', step, field, `'${value}' is not a duration; use 30m, 4h or PT30M`);
    }
  }

  require(s: ParsedStep, field: string, code: string, message: string): void {
    const v = withNode(s, field);
    if (v === undefined || v === null || (isTextual(v) && isBlank(v))) {
      this.error(code, s.id, field, message);
    }
  }

  resourceType(s: ParsedStep): void {
    const type = withText(s, 'type');
    if (type === null) {
      this.error('step.missing', s.id, 'type', 'The resource type is required');
    } else if (this.ctx.knowsCatalog && !this.ctx.resourceTypes.includes(type)) {
      this.error('resource.unknownType', s.id, 'type', `Unknown resource type '${type}'`, this.suggest(type, this.ctx.resourceTypes));
    }
  }

  provider(s: ParsedStep, scope: Scope): void {
    const provider = withText(s, 'provider');
    const action = withText(s, 'action');
    if (provider === null) {
      this.error('step.missingProvider', s.id, 'provider', 'Pick the provider this step calls');
    }
    if (action === null) {
      this.error('step.missingAction', s.id, 'action', 'Pick the action this step calls');
    }
    const input = withNode(s, 'input');
    if (input !== undefined && input !== null && !isObject(input)) {
      this.error('step.invalidJson', s.id, 'input', "input is a JSON object of the action's fields");
    }
    if (provider === null || !this.ctx.knowsCatalog) {
      return;
    }
    const p = Object.hasOwn(this.ctx.providers, provider) ? this.ctx.providers[provider] : undefined;
    if (p === undefined) {
      this.error('step.unknownProvider', s.id, 'provider', `Unknown provider '${provider}'`, this.suggest(provider, Object.keys(this.ctx.providers)));
      return;
    }
    if (action === null) {
      return;
    }
    const a = findAction(p, action);
    if (a === undefined) {
      this.error('step.unknownAction', s.id, 'action', `Provider ${provider} has no action '${action}'`, this.suggest(action, actionIds(p)));
      return;
    }
    const schema = get(a, 'input');
    if (schema !== undefined && schema !== null && input !== undefined && isObject(input)) {
      for (const r of elements(get(schema, 'required'))) {
        if (!has(input, asText(r))) {
          this.error('step.missingInput', s.id, `input.${asText(r)}`, `Action ${action} needs '${asText(r)}'`);
        }
      }
      const known = fieldNames(get(schema, 'properties'));
      if (known.length > 0) {
        for (const f of fieldNames(input)) {
          if (!known.includes(f)) {
            this.warning('step.unknownInput', s.id, `input.${f}`, `Action ${action} does not take '${f}'`, this.suggest(f, known));
          }
        }
      }
    } else if (schema !== undefined && schema !== null && input === undefined && size(get(schema, 'required')) > 0) {
      this.error('step.missingInput', s.id, 'input', `Action ${action} needs ${toJson(get(schema, 'required'))}`);
    }
    const destructive = isDestructive(actionEffect(a));
    if ((destructive || actionApprovalRequired(a)) && !this.policyApproval && !scope.approved) {
      this.warning(
        'policy.approvalRequired',
        s.id,
        null,
        `Action ${action} ${destructive ? 'is destructive' : 'requires an approval'}; add an approval step before it or require one in policy`,
        insertApprovalFix('Insert an approval before this step'),
      );
    }
  }

  http(s: ParsedStep, scope: Scope): void {
    const method = withText(s, 'method');
    if (method !== null && !METHODS.has(method.toUpperCase())) {
      this.error('api.method', s.id, 'method', 'method must be one of GET, POST, PUT, PATCH, DELETE');
    }
    this.require(s, 'url', 'step.missing', 'An HTTP step needs a url');
    const url = withText(s, 'url');
    if (url !== null && !isTemplate(url) && !url.startsWith('http://') && !url.startsWith('https://')) {
      this.error('http.url', s.id, 'url', 'url starts with http:// or https://');
    }
    if (method !== null && method.toUpperCase() === 'DELETE' && !this.policyApproval && !scope.approved) {
      this.warning('policy.approvalRequired', s.id, null, 'An HTTP DELETE is destructive; add an approval step before it or require one in policy', insertApprovalFix('Insert an approval before this step'));
    }
  }

  ansible(s: ParsedStep): void {
    const pb = withNode(s, 'playbook');
    if (pb === undefined || pb === null) {
      this.error('ansible.source', s.id, 'playbook', 'Pick where the playbook comes from: git, upload or library');
      return;
    }
    if (isTextual(pb)) {
      // playbook: <provider>/<playbook id> form, a library reference
      if (!pb.includes('/')) {
        this.error('ansible.source', s.id, 'playbook', 'A library playbook is <provider>/<playbook>, like ops-playbooks/vm.restart');
      }
    } else if (isObject(pb)) {
      const git = get(pb, 'git');
      const upload = get(pb, 'upload');
      const library = get(pb, 'library');
      if (git === undefined && upload === undefined && library === undefined) {
        this.error('ansible.source', s.id, 'playbook', 'playbook needs git, upload or library');
      }
      if (git !== undefined) {
        for (const f of ['repo', 'ref', 'path']) {
          if (asTextOr(get(git, f), null) === null) {
            this.error('ansible.git', s.id, `playbook.git.${f}`, 'git playbooks need repo, ref and path');
          }
        }
        if (asTextOr(get(pb, 'commit'), null) === null) {
          this.warning('ansible.unpinned', s.id, 'playbook.commit', 'Pin the playbook to a commit so every run uses the same code');
        }
      }
      if (upload !== undefined) {
        const sha256 = asTextOr(get(upload, 'sha256'), null);
        if (asTextOr(get(upload, 'file'), null) === null || sha256 === null) {
          this.error('ansible.upload', s.id, 'playbook.upload', 'Uploaded playbooks need file and sha256');
        } else if (!isTemplate(sha256) && !DIGEST.test(sha256)) {
          this.error('playbook.digest', s.id, 'playbook.upload.sha256', "sha256 is the upload's digest as POST /playbooks:upload answered it: 64 lowercase hex digits");
        }
      }
    } else {
      this.error('ansible.source', s.id, 'playbook', 'playbook is an object with git, upload or library');
    }
    if (s.retry !== null && s.retry.maxAttempts > 1) {
      this.warning('ansible.retry', s.id, 'retry.maxAttempts', 'Playbooks are not always safe to run twice; keep maxAttempts at 1 unless the playbook is idempotent');
    }
    const vars = withNode(s, 'extraVars');
    if (vars !== undefined && !isObject(vars)) {
      this.error('step.invalidJson', s.id, 'extraVars', 'extraVars is an object');
    }
  }

  terraform(s: ParsedStep, scope: Scope): void {
    const module = withNode(s, 'module');
    if (!isObject(module) || asTextOr(get(module, 'source'), null) === null) {
      this.error('terraform.module', s.id, 'module', 'A Terraform step needs module: { source, version }');
    } else if (asTextOr(get(module, 'version'), null) === null) {
      this.warning('terraform.unpinned', s.id, 'module.version', 'Pin the module version so every run uses the same code');
    }
    const action = withText(s, 'action');
    if (action === null || !TERRAFORM_ACTIONS.has(action)) {
      this.error('terraform.action', s.id, 'action', 'action is plan, apply or destroy');
    }
    if (action === 'destroy' && !this.policyApproval && !scope.approved) {
      this.warning('policy.approvalRequired', s.id, null, 'terraform destroy is destructive; add an approval step before it or require one in policy', insertApprovalFix('Insert an approval before this step'));
    }
  }

  runStep(s: ParsedStep): void {
    const wf = withText(s, 'workflow');
    if (wf === null) {
      this.error('step.missing', s.id, 'workflow', 'run needs the workflow to run');
      return;
    }
    if (wf === this.def.metadata.name) {
      this.error('run.self', s.id, 'workflow', 'A workflow cannot run itself');
    } else if (this.ctx.knowsCatalog && !this.ctx.workflows.includes(wf)) {
      this.error('run.unknownWorkflow', s.id, 'workflow', `'${wf}' is not a published workflow`, this.suggest(wf, this.ctx.workflows));
    }
    const version = withText(s, 'version');
    if (version !== null && !SEMVER_RANGE.test(version)) {
      this.error('run.version', s.id, 'version', 'version is a range like ^1, ~1.2 or 1.2.0');
    }
    const input = withNode(s, 'input');
    if (input !== undefined && !isObject(input)) {
      this.error('step.invalidJson', s.id, 'input', 'input is a JSON object');
    }
    const onFailure = withText(s, 'onFailure');
    if (onFailure !== null && !ON_FAILURE.includes(onFailure)) {
      this.error('run.onFailure', s.id, 'onFailure', 'onFailure is fail (the default) or continue', this.suggest(onFailure, ON_FAILURE));
    }
    this.contract(s, wf);
  }

  /**
   * The contract of a run step: its input against the published input schema of the workflow it
   * runs. A required field without a default must be given, a field the workflow does not take
   * is reported, and a literal must have the declared type.
   */
  contract(s: ParsedStep, wf: string): void {
    const schemas = this.ctx.workflowInputs;
    const schema = schemas !== undefined && Object.hasOwn(schemas, wf) ? schemas[wf] : undefined;
    const input = withNode(s, 'input');
    if (!isObject(schema) || (input !== undefined && input !== null && !isObject(input))) {
      return;
    }
    const props = get(schema, 'properties');
    for (const r of elements(get(schema, 'required'))) {
      const f = asText(r);
      if (!has(input, f) && !has(get(props, f), 'default')) {
        this.error('contract.missingInput', s.id, `input.${f}`, `Workflow ${wf} needs '${f}'`);
      }
    }
    if (!isObject(input) || !isObject(props) || size(props) === 0) {
      return;
    }
    const known = fieldNames(props);
    for (const key of fieldNames(input)) {
      const value = input[key] as JsonValue;
      const p = get(props, key);
      if (p === undefined) {
        this.warning('contract.unknownInput', s.id, `input.${key}`, `Workflow ${wf} does not take '${key}'`, this.suggest(key, known));
        continue;
      }
      const actual = literalType(value);
      if (actual === null || (orCreateWith(p) && isObject(value) && has(value, 'create'))) {
        continue;
      }
      const type = get(p, 'type');
      const values = get(p, 'enum');
      if (type !== undefined && !accepts(type, actual)) {
        this.error('contract.type', s.id, `input.${key}`, `'${key}' is ${typeText(type)} in workflow ${wf}, not ${actual}`);
      } else if (isArray(values) && !isObject(value) && !isArray(value) && !isTemplate(asText(value)) && !values.some((v) => sameJson(v, value))) {
        this.error('contract.type', s.id, `input.${key}`, `'${asText(value)}' is not one of ${toJson(values)} in workflow ${wf}`);
      }
    }
  }

  switchStep(s: ParsedStep, scope: Scope, later: string[]): void {
    if (s.cases === null || s.cases.length === 0) {
      this.error('switch.empty', s.id, null, 'A switch needs at least one case');
      return;
    }
    let defaults = 0;
    let i = 0;
    const after = scope.copy();
    for (const c of s.cases) {
      const field = `switch[${i++}]`;
      if (c.isDefault) {
        defaults++;
      } else if (c.when === null || isBlank(c.when)) {
        this.error('switch.when', s.id, `${field}.when`, 'Each case needs a when expression');
      } else if (!isTemplate(c.when)) {
        this.warning('switch.when', s.id, `${field}.when`, "when is an expression like {{ $input.size == 'large' }}");
      } else {
        this.template(c.when, s.id, `${field}.when`, scope);
      }
      if (c.steps !== null) {
        after.merge(this.walk(c.steps, scope));
      } else if (c.then === null) {
        this.error('switch.target', s.id, field, 'Each case needs then: <step id | end> or do: [steps]');
      } else if (c.then !== 'end' && !later.includes(c.then)) {
        if (this.byId.has(c.then)) {
          this.error('switch.backwardJump', s.id, `${field}.then`, `then must point to a later step at the same level, not '${c.then}'`);
        } else {
          this.error('switch.unknownTarget', s.id, `${field}.then`, `No step '${c.then}' after this switch`, this.suggest(c.then, later));
        }
      }
    }
    if (defaults > 1) {
      this.error('switch.defaults', s.id, null, 'Only one default branch');
    }
    scope.merge(after);
  }

  fork(s: ParsedStep, scope: Scope): void {
    if (s.branches === null || s.branches.length === 0) {
      this.error('fork.empty', s.id, null, 'A fork needs at least one branch');
      return;
    }
    const after = scope.copy();
    let i = 0;
    for (const branch of s.branches) {
      if (branch.length === 0) {
        this.error('fork.emptyBranch', s.id, `branches[${i}]`, `Branch ${i + 1} has no steps`);
      }
      after.merge(this.walk(branch, scope));
      i++;
    }
    scope.merge(after);
  }

  forStep(s: ParsedStep, scope: Scope): void {
    const each = withText(s, 'each');
    const inList = withText(s, 'in');
    if (each === null || !IDENTIFIER.test(each)) {
      this.error('for.each', s.id, 'each', 'each names the loop variable, like item');
    }
    if (inList === null) {
      this.error('for.in', s.id, 'in', 'in is the list to loop over, like {{ $json.items }}');
    } else {
      this.template(inList, s.id, 'in', scope);
    }
    if (s.children === null || s.children.length === 0) {
      this.error('for.empty', s.id, 'do', 'The loop body has no steps');
      return;
    }
    const inner = scope.copy();
    if (each !== null) {
      inner.aliases.add(each);
    }
    const after = this.walk(s.children, inner);
    if (each !== null) {
      after.aliases.delete(each);
    }
    scope.merge(after);
  }

  tryStep(s: ParsedStep, scope: Scope): void {
    if (s.children === null || s.children.length === 0) {
      this.error('try.empty', s.id, 'do', 'try has no steps');
      return;
    }
    const after = this.walk(s.children, scope);
    if (s.catchBlock === null) {
      this.warning('try.noCatch', s.id, 'catch', 'try without catch does nothing; add a catch or remove the try');
    } else {
      const c = after.copy();
      c.inCatch = true;
      c.hasPredecessor = true;
      c.previous = scope.previous; // the catch starts from what the try started from
      if (s.catchBlock.as !== null) {
        c.aliases.add(s.catchBlock.as);
      }
      const afterCatch = this.walk(s.catchBlock.steps, c);
      if (s.catchBlock.as !== null) {
        afterCatch.aliases.delete(s.catchBlock.as);
      }
      after.merge(afterCatch);
    }
    scope.merge(after);
  }

  // ------------------------------------------------------------ expressions

  expressions(node: unknown, step: string | null, path: string | null, scope: Scope): void {
    if (isTextual(node)) {
      this.template(node, step, path, scope);
    } else if (isObject(node)) {
      for (const key of fieldNames(node)) {
        this.expressions(node[key], step, path === null ? key : `${path}.${key}`, scope);
      }
    } else if (Array.isArray(node)) {
      let i = 0;
      for (const v of node) {
        this.expressions(v, step, `${path ?? ''}[${i++}]`, scope);
      }
    }
  }

  template(text: string, step: string | null, field: string | null, scope: Scope): void {
    if (!isTemplate(text)) {
      return;
    }
    const err = syntaxError(text);
    if (err !== null) {
      this.error('expr.syntax', step, field, err);
      return;
    }
    for (const r of references(text)) {
      switch (r.kind) {
        case 'json':
          if (!scope.hasPredecessor) {
            this.error('expr.noPredecessor', step, field, '$json is the previous step\'s output, and this is the first step; use $input or connect a step before it', connectFix('Connect a step before this one'));
          } else {
            this.returned(r, scope.previous, "The previous step's output", step, field);
          }
          break;
        case 'step': {
          const name = r.step ?? '';
          if (!scope.stepNames.has(name)) {
            if (this.allNames.has(name)) {
              this.error('expr.stepAfter', step, field, `'${name}' runs after this step, so its output is not available yet`);
            } else {
              this.error('expr.unknownStep', step, field, `No step named '${name}'`, this.suggest(name, scope.stepNames));
            }
          } else {
            this.returned(r, this.stepOutputs.get(name) ?? null, `The output of '${name}'`, step, field);
          }
          break;
        }
        case 'alias': {
          const name = r.step ?? '';
          if (!scope.aliases.has(name)) {
            const candidates = [...scope.aliases, ...ROOTS];
            const fix = this.suggest(name, candidates);
            this.error(
              'expr.unknownReference',
              step,
              field,
              `Unknown reference $${name}; save a step's output with output: { as: ${name} } or use $json, $input, $vars`,
              fix === null ? null : replaceFix(fix.label, `$${fix.value ?? ''}`),
            );
          } else {
            this.returned(r, this.aliasOutputs.get(name) ?? null, `$${name}`, step, field);
          }
          break;
        }
        case 'input': {
          const first = r.path.length === 0 ? null : (r.path.split('.')[0] as string).replace(/\[.*/, '');
          if (first !== null && this.inputFields.size > 0 && !this.inputFields.has(first)) {
            this.warning('expr.unknownInput', step, field, `The input has no field '${first}'`, this.suggest(first, this.inputFields));
          }
          break;
        }
        case 'vars': {
          const first = r.path.length === 0 ? null : (r.path.split('.')[0] as string);
          if (first !== null && !scope.vars.has(first)) {
            this.warning('expr.unknownVar', step, field, `No earlier set step defines $vars.${first}`, this.suggest(first, scope.vars));
          }
          break;
        }
        case 'error':
          if (!scope.inCatch) {
            this.warning('expr.errorOutsideCatch', step, field, '$error is only set inside catch');
          }
          break;
        default:
          break;
      }
    }
  }

  // ------------------------------------------------------------ what steps return

  /** The output schema of every step and alias, known before the walk; null where unknown. */
  outputs(all: ParsedStep[]): void {
    for (const s of all) {
      const schema = this.outputSchema(s);
      this.stepOutputs.set(s.id, schema);
      if (s.name !== null && !this.stepOutputs.has(s.name)) {
        this.stepOutputs.set(s.name, schema);
      }
      const as = s.output === null ? null : s.output.as;
      if (as !== null) {
        // two steps saving under one alias with different shapes: unknown
        const before = this.aliasOutputs.get(as);
        this.aliasOutputs.set(as, this.aliasOutputs.has(as) && !sameJson(before ?? null, schema) ? null : schema);
      }
    }
  }

  /** What a step returns, when its declaration says: a provider action's output, a resource, a child job. */
  outputSchema(s: ParsedStep): JsonObject | null {
    switch (s.type) {
      case 'provider': {
        const provider = withText(s, 'provider');
        const action = withText(s, 'action');
        if (provider === null || action === null || !Object.hasOwn(this.ctx.providers, provider)) {
          return null;
        }
        const p = this.ctx.providers[provider] as ProviderDocument;
        const a = findAction(p, action);
        const output = get(a, 'output');
        if (a === undefined || !isObject(output)) {
          return null;
        }
        // the effective output: the action's schema with the provider's override block on top
        const override = get(get(get(p, 'spec'), 'overrides'), action);
        return isObject(override) && has(override, 'response') ? mergeSchema(output, get(override, 'response')) : output;
      }
      case 'use':
      case 'register': {
        const type = withText(s, 'type');
        const schemas = this.ctx.resourceTypeSchemas;
        const schema = type !== null && schemas !== undefined && Object.hasOwn(schemas, type) ? schemas[type] : undefined;
        if (schema === undefined || schema === null) {
          return null;
        }
        const attributes: JsonObject = isClosed(schema) ? (structuredClone(schema) as JsonObject) : {};
        const written = s.type === 'register' ? withNode(s, 'attributes') : undefined;
        const props = get(attributes, 'properties');
        if (isObject(written) && isObject(props)) {
          for (const k of fieldNames(written)) {
            if (!has(props, k)) {
              props[k] = {};
            }
          }
        }
        return resourceShape(attributes);
      }
      case 'run': {
        const wf = withText(s, 'workflow');
        const outs = this.ctx.workflowOutputs;
        if (wf === null || outs === undefined || !Object.hasOwn(outs, wf)) {
          return null;
        }
        const outProps: JsonObject = {};
        for (const k of outs[wf] ?? []) {
          outProps[k] = {};
        }
        const errorProps: JsonObject = {};
        for (const k of ['step', 'message', 'class']) {
          errorProps[k] = { type: 'string' };
        }
        return {
          type: 'object',
          properties: {
            jobId: { type: 'string' },
            status: { type: 'string' },
            outputs: { type: 'object', properties: outProps },
            // the resources the child's register steps recorded
            resources: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, type: { type: 'string' } } } },
            error: { type: 'object', properties: errorProps },
          },
        };
      }
      default:
        return null;
    }
  }

  /**
   * An expression reads a field the producing step does not return: the first segment of the
   * path its declared output lacks, with the closest declared path as the fix (`vmId` reads as
   * `vm.id`).
   */
  returned(r: Reference, schema: JsonObject | null, what: string, step: string | null, field: string | null): void {
    if (schema === null || r.path.length === 0) {
      return;
    }
    const segments = r.path.split('.');
    let node: Maybe = schema;
    for (let i = 0; i < segments.length; i++) {
      if (!isClosed(node)) {
        return;
      }
      const segment = segments[i] as string;
      const next = get(get(node, 'properties'), segment);
      if (next === undefined) {
        const known = segments.slice(0, i).join('.');
        const wrong = known.length === 0 ? segment : `${known}.${segment}`;
        const rest = segments.slice(i + 1).join('.');
        const path = closestPath(wrong, schema);
        const fixed = path === undefined ? undefined : rest.length === 0 ? path : `${path}.${rest}`;
        this.warning('expr.unknownField', step, field, `${what} has no field '${wrong}'`, fixed === undefined ? null : replaceFix(`Did you mean ${fixed}?`, fixed));
        return;
      }
      node = next;
    }
  }

  // ------------------------------------------------------------ steps that never run

  /** Steps a switch in the same list always jumps past (every case has then and there is a default). */
  unreachable(steps: ParsedStep[]): void {
    const reachable = steps.map(() => false);
    if (steps.length > 0) {
      reachable[0] = true;
    }
    let blocker: string | null = null;
    for (let i = 0; i < steps.length; i++) {
      const s = steps[i] as ParsedStep;
      if (!reachable[i]) {
        if (blocker !== null) {
          this.warning('step.unreachable', s.id, null, `'${displayName(s)}' never runs: every case of ${blocker} jumps past it`);
        }
        continue;
      }
      let next = true;
      if (s.type === 'switch' && s.cases !== null && s.cases.length > 0) {
        let hasDefault = false;
        let falls = false;
        for (const c of s.cases) {
          hasDefault = hasDefault || c.isDefault;
          if (c.steps !== null || c.then === null) {
            falls = true;
          } else if (c.then !== 'end') {
            let target = -1;
            for (let k = i + 1; k < steps.length && target < 0; k++) {
              if ((steps[k] as ParsedStep).id === c.then) {
                target = k;
              }
            }
            if (target < 0) {
              falls = true; // a target that is not a later step is reported by the switch itself
            } else {
              reachable[target] = true;
            }
          }
        }
        next = falls || !hasDefault;
        if (!next) {
          blocker = s.id;
        }
      }
      if (next && i + 1 < steps.length) {
        reachable[i + 1] = true;
      }
    }
  }

  // ------------------------------------------------------------ helpers

  suggest(wrong: string, candidates: Iterable<string>): Fix | null {
    const c = closest(wrong, candidates);
    return c === undefined ? null : replaceFix(`Did you mean ${c}?`, c);
  }

  error(code: string, step: string | null, field: string | null, message: string, fix?: Fix | null): void {
    this.problems.push(problem('error', code, step, field, message, fix));
  }

  warning(code: string, step: string | null, field: string | null, message: string, fix?: Fix | null): void {
    this.problems.push(problem('warning', code, step, field, message, fix));
  }
}

/** A registry resource as use and register steps return it, with the type's attributes. */
function resourceShape(attributes: JsonObject): JsonObject {
  const props: JsonObject = {};
  for (const k of ['id', 'type', 'name', 'status']) {
    props[k] = { type: 'string' };
  }
  props['attributes'] = attributes;
  for (const k of ['owner', 'visibility']) {
    props[k] = { type: 'string' };
  }
  props['createdBy'] = { type: 'object', properties: { jobId: { type: 'string' }, workflow: { type: 'string' }, version: { type: 'string' } } };
  props['builtFrom'] = { type: 'array', items: { type: 'string' } };
  for (const k of ['createdAt', 'updatedAt']) {
    props[k] = { type: 'string' };
  }
  return { type: 'object', properties: props };
}

/** The declared path that spells the same once case and dots are ignored, else the closest one. */
function closestPath(wrong: string, schema: JsonObject): string | undefined {
  const paths = schemaPaths(schema).filter((p) => !p.includes('[]'));
  const target = flat(wrong);
  const same = paths.find((p) => flat(p) === target);
  return same ?? closest(wrong, paths);
}

function flat(path: string): string {
  return path.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** The steps of a list a switch in it jumps to. */
function jumpTargets(steps: ParsedStep[]): Set<string> {
  const out = new Set<string>();
  for (const s of steps) {
    if (s.type === 'switch' && s.cases !== null) {
      for (const c of s.cases) {
        if (c.steps === null && c.then !== null) {
          out.add(c.then);
        }
      }
    }
  }
  return out;
}

/** The JSON type a literal has, or null for an expression whose type shows only at run time. */
function literalType(v: JsonValue | undefined): string | null {
  if (v === undefined || v === null) {
    return 'null';
  }
  if (typeof v === 'string') {
    const t = v.trim();
    if (!isTemplate(t)) {
      return 'string';
    }
    // one expression alone yields its own type; text around expressions makes a string
    return t.startsWith('{{') && t.endsWith('}}') && t.indexOf('{{', 2) < 0 ? null : 'string';
  }
  if (typeof v === 'number') {
    return Number.isInteger(v) ? 'integer' : 'number';
  }
  if (typeof v === 'boolean') {
    return 'boolean';
  }
  return isArray(v) ? 'array' : 'object';
}

function accepts(type: JsonValue, actual: string): boolean {
  if (isArray(type)) {
    return type.some((t) => accepts(t, actual));
  }
  const expected = asText(type);
  return expected === actual || (expected === 'number' && actual === 'integer');
}

function typeText(type: JsonValue): string {
  return isArray(type) ? type.map((t) => asText(t)).join(' or ') : asText(type);
}

function orCreateWith(property: unknown): boolean {
  return isTextual(get(get(property, 'x-resource'), 'orCreateWith'));
}
