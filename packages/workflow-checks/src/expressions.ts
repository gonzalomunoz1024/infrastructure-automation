/**
 * The platform's expression language: n8n-style `{{ $json.vm.id }}` templates with a small,
 * deterministic grammar (member access, comparisons, boolean logic, arithmetic, a few string and
 * array methods, `v => ...` functions for map/filter/some/every/find). A port of the Java
 * ExpressionEngine, node for node, so the editor previews a mapping exactly as the interpreter
 * resolves it and the checks see the same references.
 *
 * A string that is exactly one expression yields the typed value; a string with text around
 * expressions yields text.
 */
import { asDouble, asInt, asText as jsonText, elements, fieldNames, isArray, isContainer, isObject, parseJavaDouble, size, toJson } from './json.ts';
import type { JsonObject, JsonValue } from './types.ts';

export type ReferenceKind = 'json' | 'step' | 'input' | 'alias' | 'vars' | 'job' | 'env' | 'error';

/**
 * A data reference found in a template, for the checks: `$json.vm.id` is `{ kind: 'json', path:
 * 'vm.id' }`; `$('Check VM exists').json.vm.id` is `{ kind: 'step', step: 'Check VM exists',
 * path: 'vm.id' }`; `$vm.name` is `{ kind: 'alias', step: 'vm', path: 'name' }`.
 */
export interface Reference {
  kind: ReferenceKind;
  /** The step name or id of a `step` reference, the alias of an `alias` reference. */
  step?: string;
  /** The dotted path below the root; empty for the root itself. `.length` is not part of it. */
  path: string;
}

/** A template that cannot be parsed or evaluated. */
export class ExpressionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ExpressionError';
  }
}

/**
 * What an expression can see. `json` is the previous step's output, `input` the job input,
 * `steps` any step's output by name or id, `aliases` the outputs saved with `output: { as }`.
 */
export interface ExpressionContext {
  input?: JsonValue;
  json?: JsonValue;
  steps?: Record<string, JsonValue>;
  aliases?: Record<string, JsonValue>;
  vars?: JsonValue;
  job?: JsonValue;
  env?: JsonValue;
  error?: JsonValue;
}

export const EMPTY_EXPRESSION_CONTEXT: ExpressionContext = {};

/** Whether a string holds an expression at all. */
export function isTemplate(s: string | null | undefined): boolean {
  return typeof s === 'string' && s.includes('{{');
}

/** Evaluate a template string. Non-template strings come back as text unchanged. */
export function evaluate(template: string | null | undefined, ctx: ExpressionContext): JsonValue {
  if (template === null || template === undefined) {
    return null;
  }
  if (!template.includes('{{')) {
    return template;
  }
  const scope = new Scope(ctx, new Map());
  const t = template.trim();
  if (t.startsWith('{{') && t.endsWith('}}') && t.indexOf('{{', 2) < 0) {
    return nullSafe(new Parser(t.substring(2, t.length - 2)).parseAll().eval(scope));
  }
  let out = '';
  let i = 0;
  while (i < template.length) {
    const open = template.indexOf('{{', i);
    if (open < 0) {
      out += template.substring(i);
      break;
    }
    const close = template.indexOf('}}', open + 2);
    if (close < 0) {
      throw new ExpressionError(`Unclosed {{ in "${template}"`);
    }
    out += template.substring(i, open);
    out += asText(new Parser(template.substring(open + 2, close)).parseAll().eval(scope));
    i = close + 2;
  }
  return out;
}

/** Resolve every template string inside a value, recursively. */
export function resolve(value: JsonValue | undefined, ctx: ExpressionContext): JsonValue {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value === 'string') {
    return evaluate(value, ctx);
  }
  if (isArray(value)) {
    return value.map((v) => resolve(v, ctx));
  }
  if (isObject(value)) {
    const out: JsonObject = {};
    for (const k of fieldNames(value)) {
      out[k] = resolve(value[k], ctx);
    }
    return out;
  }
  return value;
}

/** Evaluate a template and judge it as a boolean (asserts, switch cases). */
export function test(template: string | null | undefined, ctx: ExpressionContext): boolean {
  return truthy(evaluate(template, ctx));
}

/** Every data reference in a template, for the checks. Parse errors yield no references. */
export function references(template: string | null | undefined): Reference[] {
  const refs: Reference[] = [];
  if (template === null || template === undefined || !template.includes('{{')) {
    return refs;
  }
  let i = 0;
  for (;;) {
    const open = template.indexOf('{{', i);
    if (open < 0) {
      break;
    }
    const close = template.indexOf('}}', open + 2);
    if (close < 0) {
      break;
    }
    try {
      new Parser(template.substring(open + 2, close)).parseAll().collect(refs);
    } catch (e) {
      if (!(e instanceof ExpressionError)) {
        throw e;
      }
      // a broken expression is reported by the syntax check, not here
    }
    i = close + 2;
  }
  return refs;
}

/** References inside every string of a value, recursively. */
export function referencesIn(value: JsonValue | undefined): Reference[] {
  const refs: Reference[] = [];
  walk(value, refs);
  return refs;
}

function walk(v: JsonValue | undefined, refs: Reference[]): void {
  if (typeof v === 'string') {
    refs.push(...references(v));
  } else if (isArray(v)) {
    v.forEach((c) => walk(c, refs));
  } else if (isObject(v)) {
    fieldNames(v).forEach((k) => walk(v[k], refs));
  }
}

/** Syntax check of every expression in a template; the first error message, or null when fine. */
export function syntaxError(template: string | null | undefined): string | null {
  if (template === null || template === undefined || !template.includes('{{')) {
    return null;
  }
  let i = 0;
  for (;;) {
    const open = template.indexOf('{{', i);
    if (open < 0) {
      return null;
    }
    const close = template.indexOf('}}', open + 2);
    if (close < 0) {
      return 'Unclosed {{';
    }
    try {
      new Parser(template.substring(open + 2, close)).parseAll();
    } catch (e) {
      if (e instanceof ExpressionError) {
        return e.message;
      }
      throw e;
    }
    i = close + 2;
  }
}

// ---------------------------------------------------------------- values

export function truthy(v: JsonValue | undefined): boolean {
  if (v === undefined || v === null) {
    return false;
  }
  if (typeof v === 'boolean') {
    return v;
  }
  if (typeof v === 'number') {
    return v !== 0;
  }
  if (typeof v === 'string') {
    return v.length > 0;
  }
  return true;
}

/** Text of a value the way a template renders it: nothing for null, JSON for containers. */
export function asText(v: JsonValue | undefined): string {
  if (v === undefined || v === null) {
    return '';
  }
  if (isContainer(v)) {
    return toJson(v);
  }
  return jsonText(v);
}

function nullSafe(v: JsonValue | undefined): JsonValue {
  return v === undefined ? null : v;
}

/** Whole numbers stay integers (the Java engine turns them into longs), the rest are doubles. */
function number(d: number): number {
  if (d === 0) {
    return 0;
  }
  return d;
}

function equal(x: JsonValue | undefined, y: JsonValue | undefined): boolean {
  const a = nullSafe(x);
  const b = nullSafe(y);
  if (typeof a === 'number' && typeof b === 'number') {
    return a === b;
  }
  if (!isContainer(a) && !isContainer(b) && !((a === null) !== (b === null))) {
    return a === null || asText(a) === asText(b);
  }
  return deepEqual(a, b);
}

function deepEqual(a: JsonValue, b: JsonValue): boolean {
  if (isArray(a) && isArray(b)) {
    return a.length === b.length && a.every((v, i) => deepEqual(nullSafe(v), nullSafe(b[i])));
  }
  if (isObject(a) && isObject(b)) {
    const ka = fieldNames(a);
    const kb = fieldNames(b);
    return ka.length === kb.length && ka.every((k) => kb.includes(k) && deepEqual(nullSafe(a[k]), nullSafe(b[k])));
  }
  if (isContainer(a) || isContainer(b)) {
    return false;
  }
  return a === b;
}

function compare(x: JsonValue | undefined, y: JsonValue | undefined, op: string): number {
  const a = nullSafe(x);
  const b = nullSafe(y);
  if (typeof a === 'number' && typeof b === 'number') {
    return a < b ? -1 : a > b ? 1 : 0;
  }
  if (a === null || b === null) {
    throw new ExpressionError(`Cannot compare null with ${op}`);
  }
  const s = asText(a);
  const t = asText(b);
  return s < t ? -1 : s > t ? 1 : 0;
}

function getField(t: JsonValue, field: string): JsonValue {
  if (isObject(t) && Object.hasOwn(t, field)) {
    return nullSafe(t[field]);
  }
  return null;
}

// ---------------------------------------------------------------- evaluation scope

class Scope {
  readonly ctx: ExpressionContext;
  readonly locals: ReadonlyMap<string, JsonValue>;

  constructor(ctx: ExpressionContext, locals: ReadonlyMap<string, JsonValue>) {
    this.ctx = ctx;
    this.locals = locals;
  }

  /** The same scope with one function parameter bound. */
  bind(name: string, value: JsonValue | undefined): Scope {
    const next = new Map(this.locals);
    next.set(name, nullSafe(value));
    return new Scope(this.ctx, next);
  }

  local(name: string): JsonValue | undefined {
    return this.locals.get(name);
  }

  step(name: string): JsonValue | undefined {
    const steps = this.ctx.steps;
    return steps !== undefined && Object.hasOwn(steps, name) ? nullSafe(steps[name]) : undefined;
  }

  alias(name: string): JsonValue | undefined {
    const aliases = this.ctx.aliases;
    return aliases !== undefined && Object.hasOwn(aliases, name) ? nullSafe(aliases[name]) : undefined;
  }
}

// ---------------------------------------------------------------- AST

interface Node {
  eval(scope: Scope): JsonValue;
  collect(refs: Reference[]): void;
}

class Literal implements Node {
  readonly value: JsonValue;

  constructor(value: JsonValue) {
    this.value = value;
  }

  eval(): JsonValue {
    return this.value;
  }

  collect(): void {
    // a literal references nothing
  }
}

/** `$json`, `$input`, `$('step')`, `$alias`. */
class Root implements Node {
  readonly name: string | null;
  readonly stepName: string | null;

  constructor(name: string | null, stepName: string | null) {
    this.name = name;
    this.stepName = stepName;
  }

  eval(scope: Scope): JsonValue {
    if (this.stepName !== null) {
      const out = scope.step(this.stepName);
      if (out === undefined) {
        throw new ExpressionError(`Unknown step '${this.stepName}'`);
      }
      return { json: out, item: { json: out } };
    }
    const name = this.name ?? '';
    switch (name) {
      case 'json':
        return nullSafe(scope.ctx.json);
      case 'input':
        return nullSafe(scope.ctx.input);
      case 'vars':
        return nullSafe(scope.ctx.vars);
      case 'job':
        return nullSafe(scope.ctx.job);
      case 'env':
        return nullSafe(scope.ctx.env);
      case 'error':
        return nullSafe(scope.ctx.error);
      default: {
        const a = scope.alias(name);
        if (a === undefined) {
          throw new ExpressionError(`Unknown reference $${name}`);
        }
        return a;
      }
    }
  }

  kind(): ReferenceKind {
    if (this.stepName !== null) {
      return 'step';
    }
    switch (this.name) {
      case 'json':
        return 'json';
      case 'input':
        return 'input';
      case 'vars':
        return 'vars';
      case 'job':
        return 'job';
      case 'env':
        return 'env';
      case 'error':
        return 'error';
      default:
        return 'alias';
    }
  }

  collect(): void {
    // a bare root is not a data reference; only member chains are collected
  }
}

class Member implements Node {
  readonly target: Node;
  readonly field: string;

  constructor(target: Node, field: string) {
    this.target = target;
    this.field = field;
  }

  eval(scope: Scope): JsonValue {
    const t = nullSafe(this.target.eval(scope));
    if (this.field === 'length') {
      if (isArray(t)) {
        return t.length;
      }
      if (typeof t === 'string') {
        return t.length;
      }
      if (isObject(t)) {
        return size(t);
      }
      return null;
    }
    return getField(t, this.field);
  }

  collect(refs: Reference[]): void {
    const r = this.path();
    if (r !== null) {
      refs.push(r);
    } else {
      this.target.collect(refs);
    }
  }

  /** The dotted path from a root, or null when the target is not a plain member chain. */
  path(): Reference | null {
    const parts: string[] = [this.field];
    let n: Node = this.target;
    while (n instanceof Member) {
      parts.unshift(n.field);
      n = n.target;
    }
    if (!(n instanceof Root)) {
      return null;
    }
    // for $('step').json.a.b drop the leading json / item.json
    if (n.stepName !== null) {
      if (parts.length > 0 && parts[0] === 'item') {
        parts.shift();
      }
      if (parts.length > 0 && parts[0] === 'json') {
        parts.shift();
      }
    }
    let p = parts.join('.');
    if (parts.length > 0 && parts[parts.length - 1] === 'length') {
      p = parts.slice(0, parts.length - 1).join('.');
    }
    const kind = n.kind();
    const ref: Reference = { kind, path: p };
    if (n.stepName !== null) {
      ref.step = n.stepName;
    } else if (kind === 'alias') {
      ref.step = n.name ?? '';
    }
    return ref;
  }
}

class Index implements Node {
  readonly target: Node;
  readonly index: Node;

  constructor(target: Node, index: Node) {
    this.target = target;
    this.index = index;
  }

  eval(scope: Scope): JsonValue {
    const t = nullSafe(this.target.eval(scope));
    const i = nullSafe(this.index.eval(scope));
    if (isArray(t) && typeof i === 'number') {
      return nullSafe(t[asInt(i)]);
    }
    if (isObject(t)) {
      return getField(t, asText(i));
    }
    return null;
  }

  collect(refs: Reference[]): void {
    this.target.collect(refs);
    this.index.collect(refs);
  }
}

const ITERATING_METHODS = new Set(['map', 'filter', 'some', 'every', 'find', 'findIndex']);

class Call implements Node {
  readonly target: Node;
  readonly method: string;
  readonly args: Node[];

  constructor(target: Node, method: string, args: Node[]) {
    this.target = target;
    this.method = method;
    this.args = args;
  }

  eval(scope: Scope): JsonValue {
    const t = nullSafe(this.target.eval(scope));
    const a = this.args.map((n) => (n instanceof Lambda ? null : nullSafe(n.eval(scope))));
    const arg = (i: number): JsonValue => (i < a.length ? (a[i] as JsonValue) : null);
    const method = this.method;
    switch (method) {
      case 'toUpperCase':
        return asText(t).toUpperCase();
      case 'toLowerCase':
        return asText(t).toLowerCase();
      case 'trim':
        return asText(t).trim();
      case 'toString':
        return asText(t);
      case 'toNumber': {
        const n = parseJavaDouble(asText(t));
        return n === null ? null : number(n);
      }
      case 'includes':
      case 'contains': {
        if (isArray(t)) {
          return t.some((v) => equal(v, arg(0)));
        }
        return asText(t).includes(asText(arg(0)));
      }
      case 'startsWith':
        return asText(t).startsWith(asText(arg(0)));
      case 'endsWith':
        return asText(t).endsWith(asText(arg(0)));
      case 'split':
        return split(asText(t), asText(arg(0)));
      case 'join':
        return elements(t)
          .map((v) => asText(v))
          .join(a.length === 0 ? ',' : asText(arg(0)));
      case 'first':
        return isArray(t) && t.length > 0 ? nullSafe(t[0]) : null;
      case 'last':
        return isArray(t) && t.length > 0 ? nullSafe(t[t.length - 1]) : null;
      case 'keys':
        return fieldNames(t);
      case 'isEmpty':
        return !truthy(t) || (isContainer(t) && size(t) === 0);
      case 'isNotEmpty':
        return truthy(t) && !(isContainer(t) && size(t) === 0);
      case 'replace': {
        const to = asText(arg(1));
        return asText(t).replaceAll(asText(arg(0)), () => to);
      }
      case 'substring':
      case 'slice': {
        const from = a.length === 0 ? 0 : asInt(arg(0));
        if (isArray(t)) {
          const n = t.length;
          const start = from < 0 ? Math.max(0, n + from) : Math.min(from, n);
          const second = asInt(arg(1));
          const end = a.length > 1 ? (second < 0 ? Math.max(0, n + second) : Math.min(second, n)) : n;
          const out: JsonValue[] = [];
          for (let i = start; i < end; i++) {
            out.push(nullSafe(t[i]));
          }
          return out;
        }
        const s = asText(t);
        const start = Math.max(0, Math.min(from < 0 ? s.length + from : from, s.length));
        const second = asInt(arg(1));
        const end = a.length > 1 ? Math.max(start, Math.min(second < 0 ? s.length + second : second, s.length)) : s.length;
        return s.substring(start, end);
      }
      case 'concat': {
        if (isArray(t)) {
          const out: JsonValue[] = t.map((v) => nullSafe(v));
          for (const x of a) {
            if (isArray(x)) {
              out.push(...x.map((v) => nullSafe(v)));
            } else {
              out.push(x);
            }
          }
          return out;
        }
        return asText(t) + a.map((x) => asText(x)).join('');
      }
      case 'indexOf': {
        if (isArray(t)) {
          return t.findIndex((v) => equal(v, arg(0)));
        }
        return asText(t).indexOf(asText(arg(0)));
      }
      case 'padStart': {
        const s = asText(t);
        const width = asInt(arg(0));
        const pad = a.length > 1 ? asText(arg(1)) : ' ';
        let sb = '';
        while (sb.length + s.length < width && pad.length > 0) {
          sb += pad;
        }
        return sb.substring(0, Math.max(0, width - s.length)) + s;
      }
      case 'toFixed': {
        const digits = Math.min(100, Math.max(0, a.length === 0 ? 0 : asInt(arg(0))));
        return asDouble(t).toFixed(digits);
      }
      case 'sum': {
        let sum = 0;
        for (const v of elements(t)) {
          sum += asDouble(v);
        }
        return number(sum);
      }
      case 'min':
      case 'max':
        return best(elements(t), method === 'min');
      case 'unique': {
        const out: JsonValue[] = [];
        for (const v of elements(t)) {
          if (!out.some((o) => equal(o, v))) {
            out.push(v);
          }
        }
        return out;
      }
      case 'reverse': {
        if (isArray(t)) {
          return t.map((v) => nullSafe(v)).reverse();
        }
        return new Array<JsonValue>(size(t)).fill(null);
      }
      case 'sort': {
        const items = elements(t);
        items.sort((x, y) => compare(x, y, 'sort'));
        return items;
      }
      default:
        if (ITERATING_METHODS.has(method)) {
          return this.iterate(scope, t, method);
        }
        throw new ExpressionError(`Unknown method .${method}()`);
    }
  }

  private iterate(scope: Scope, t: JsonValue, method: string): JsonValue {
    const fn = this.args[0];
    if (!(fn instanceof Lambda)) {
      throw new ExpressionError(`.${method}() needs a function, like items.${method}(v => v.id)`);
    }
    const out: JsonValue[] = [];
    let i = 0;
    for (const v of elements(t)) {
      const r = fn.apply(scope, v, i);
      switch (method) {
        case 'map':
          out.push(nullSafe(r));
          break;
        case 'filter':
          if (truthy(r)) {
            out.push(v);
          }
          break;
        case 'some':
          if (truthy(r)) {
            return true;
          }
          break;
        case 'every':
          if (!truthy(r)) {
            return false;
          }
          break;
        case 'find':
          if (truthy(r)) {
            return v;
          }
          break;
        default:
          if (truthy(r)) {
            return i;
          }
      }
      i++;
    }
    switch (method) {
      case 'some':
        return false;
      case 'every':
        return true;
      case 'find':
        return null;
      case 'findIndex':
        return -1;
      default:
        return out;
    }
  }

  collect(refs: Reference[]): void {
    this.target.collect(refs);
    this.args.forEach((n) => n.collect(refs));
  }
}

/** Java's `s.split(Pattern.quote(sep), -1)`. */
function split(s: string, sep: string): string[] {
  if (s.length === 0) {
    return [''];
  }
  if (sep.length === 0) {
    return [...s.split(''), ''];
  }
  return s.split(sep);
}

/** The smallest or largest value by its numeric reading; null elements are skipped over. */
function best(values: JsonValue[], min: boolean): JsonValue {
  let b: JsonValue = null;
  for (const v of values) {
    if (b === null || (min ? asDouble(v) < asDouble(b) : asDouble(v) > asDouble(b))) {
      b = v;
    }
  }
  return b;
}

/** `v => expr` or `(v, i) => expr`; the parameters shadow nothing, since every reference starts with $. */
class Lambda implements Node {
  readonly param: string;
  readonly indexParam: string | null;
  readonly body: Node;

  constructor(param: string, indexParam: string | null, body: Node) {
    this.param = param;
    this.indexParam = indexParam;
    this.body = body;
  }

  eval(): JsonValue {
    throw new ExpressionError('A function is only valid as an argument of map, filter, some, every or find');
  }

  apply(scope: Scope, value: JsonValue, index: number): JsonValue {
    let inner = scope.bind(this.param, value);
    if (this.indexParam !== null) {
      inner = inner.bind(this.indexParam, index);
    }
    return nullSafe(this.body.eval(inner));
  }

  collect(refs: Reference[]): void {
    this.body.collect(refs);
  }
}

/** A function parameter inside its body. */
class Param implements Node {
  readonly name: string;

  constructor(name: string) {
    this.name = name;
  }

  eval(scope: Scope): JsonValue {
    const v = scope.local(this.name);
    if (v === undefined) {
      throw new ExpressionError(`Unknown identifier '${this.name}' (references start with $)`);
    }
    return v;
  }

  collect(): void {
    // a parameter is not a data reference
  }
}

const FUNCTION_NAMES = ['range', 'max', 'min', 'number', 'string', 'keys', 'length'];

/** `$range(1, 3)`, `$max(a, b)`: a few pure functions. */
class Func implements Node {
  readonly name: string;
  readonly args: Node[];

  constructor(name: string, args: Node[]) {
    this.name = name;
    this.args = args;
  }

  eval(scope: Scope): JsonValue {
    const a = this.args.map((n) => nullSafe(n.eval(scope)));
    const arg = (i: number): JsonValue => (i < a.length ? (a[i] as JsonValue) : null);
    switch (this.name) {
      case 'range': {
        const from = a.length > 1 ? asInt(arg(0)) : 0;
        const to = a.length > 1 ? asInt(arg(1)) : a.length === 0 ? 0 : asInt(arg(0));
        if (to - from > 10_000) {
          throw new ExpressionError('$range is limited to 10000 items');
        }
        const out: JsonValue[] = [];
        for (let i = from; i <= to; i++) {
          out.push(i);
        }
        return out;
      }
      case 'max':
      case 'min':
        return best(a, this.name === 'min');
      case 'number': {
        if (a.length === 0) {
          return null;
        }
        const n = parseJavaDouble(asText(arg(0)));
        return n === null ? null : number(n);
      }
      case 'string':
        return a.length === 0 ? '' : asText(arg(0));
      case 'keys':
        return a.length === 0 ? [] : fieldNames(arg(0));
      default: {
        if (a.length === 0) {
          return 0;
        }
        const v = arg(0);
        return isContainer(v) ? size(v) : asText(v).length;
      }
    }
  }

  collect(refs: Reference[]): void {
    this.args.forEach((n) => n.collect(refs));
  }
}

class Unary implements Node {
  readonly op: string;
  readonly operand: Node;

  constructor(op: string, operand: Node) {
    this.op = op;
    this.operand = operand;
  }

  eval(scope: Scope): JsonValue {
    const v = nullSafe(this.operand.eval(scope));
    switch (this.op) {
      case '!':
        return !truthy(v);
      case '-':
        return number(-asDouble(v));
      default:
        throw new ExpressionError(`Unknown operator ${this.op}`);
    }
  }

  collect(refs: Reference[]): void {
    this.operand.collect(refs);
  }
}

class Binary implements Node {
  readonly op: string;
  readonly left: Node;
  readonly right: Node;

  constructor(op: string, left: Node, right: Node) {
    this.op = op;
    this.left = left;
    this.right = right;
  }

  eval(scope: Scope): JsonValue {
    switch (this.op) {
      case '&&': {
        const l = this.left.eval(scope);
        return truthy(l) ? nullSafe(this.right.eval(scope)) : nullSafe(l);
      }
      case '||': {
        const l = this.left.eval(scope);
        return truthy(l) ? nullSafe(l) : nullSafe(this.right.eval(scope));
      }
      case '??': {
        const l = nullSafe(this.left.eval(scope));
        return l === null ? nullSafe(this.right.eval(scope)) : l;
      }
      default: {
        const l = nullSafe(this.left.eval(scope));
        const r = nullSafe(this.right.eval(scope));
        switch (this.op) {
          case '==':
            return equal(l, r);
          case '!=':
            return !equal(l, r);
          case '<':
            return compare(l, r, this.op) < 0;
          case '>':
            return compare(l, r, this.op) > 0;
          case '<=':
            return compare(l, r, this.op) <= 0;
          case '>=':
            return compare(l, r, this.op) >= 0;
          case '+':
            return typeof l === 'number' && typeof r === 'number' ? number(l + r) : asText(l) + asText(r);
          case '-':
            return number(asDouble(l) - asDouble(r));
          case '*':
            return number(asDouble(l) * asDouble(r));
          case '/':
            return number(asDouble(l) / asDouble(r));
          case '%':
            return number(asDouble(l) % asDouble(r));
          default:
            throw new ExpressionError(`Unknown operator ${this.op}`);
        }
      }
    }
  }

  collect(refs: Reference[]): void {
    this.left.collect(refs);
    this.right.collect(refs);
  }
}

class Ternary implements Node {
  readonly cond: Node;
  readonly yes: Node;
  readonly no: Node;

  constructor(cond: Node, yes: Node, no: Node) {
    this.cond = cond;
    this.yes = yes;
    this.no = no;
  }

  eval(scope: Scope): JsonValue {
    return truthy(this.cond.eval(scope)) ? nullSafe(this.yes.eval(scope)) : nullSafe(this.no.eval(scope));
  }

  collect(refs: Reference[]): void {
    this.cond.collect(refs);
    this.yes.collect(refs);
    this.no.collect(refs);
  }
}

class ObjectLiteral implements Node {
  readonly fields: Map<string, Node>;

  constructor(fields: Map<string, Node>) {
    this.fields = fields;
  }

  eval(scope: Scope): JsonValue {
    const out: JsonObject = {};
    for (const [k, v] of this.fields) {
      out[k] = nullSafe(v.eval(scope));
    }
    return out;
  }

  collect(refs: Reference[]): void {
    for (const n of this.fields.values()) {
      n.collect(refs);
    }
  }
}

class ArrayLiteral implements Node {
  readonly items: Node[];

  constructor(items: Node[]) {
    this.items = items;
  }

  eval(scope: Scope): JsonValue {
    return this.items.map((n) => nullSafe(n.eval(scope)));
  }

  collect(refs: Reference[]): void {
    this.items.forEach((n) => n.collect(refs));
  }
}

// ---------------------------------------------------------------- parser

const DIGIT = /[0-9]/;
const WORD = /[\p{L}\p{Nd}_]/u;
const WS = /\s/;

class Parser {
  private readonly src: string;
  private pos = 0;
  /** Function parameters in scope while their body is parsed. */
  private readonly params: string[] = [];

  constructor(src: string) {
    this.src = src;
  }

  parseAll(): Node {
    const n = this.parseTernary();
    this.skipWs();
    if (this.pos < this.src.length) {
      throw new ExpressionError(`Unexpected '${this.src.charAt(this.pos)}' at ${this.pos} in "${this.src.trim()}"`);
    }
    return n;
  }

  private parseTernary(): Node {
    const cond = this.parseNullish();
    this.skipWs();
    if (this.peek('?') && !this.peekStr('??')) {
      this.pos++;
      const yes = this.parseTernary();
      this.skipWs();
      this.expect(':');
      const no = this.parseTernary();
      return new Ternary(cond, yes, no);
    }
    return cond;
  }

  private parseNullish(): Node {
    let left = this.parseOr();
    while (this.take('??')) {
      left = new Binary('??', left, this.parseOr());
    }
    return left;
  }

  private parseOr(): Node {
    let left = this.parseAnd();
    while (this.take('||')) {
      left = new Binary('||', left, this.parseAnd());
    }
    return left;
  }

  private parseAnd(): Node {
    let left = this.parseEquality();
    while (this.take('&&')) {
      left = new Binary('&&', left, this.parseEquality());
    }
    return left;
  }

  private parseEquality(): Node {
    let left = this.parseComparison();
    for (;;) {
      if (this.take('==') || this.take('===')) {
        left = new Binary('==', left, this.parseComparison());
      } else if (this.take('!=') || this.take('!==')) {
        left = new Binary('!=', left, this.parseComparison());
      } else {
        return left;
      }
    }
  }

  private parseComparison(): Node {
    let left = this.parseAdditive();
    for (;;) {
      if (this.take('<=')) {
        left = new Binary('<=', left, this.parseAdditive());
      } else if (this.take('>=')) {
        left = new Binary('>=', left, this.parseAdditive());
      } else if (this.take('<')) {
        left = new Binary('<', left, this.parseAdditive());
      } else if (this.take('>')) {
        left = new Binary('>', left, this.parseAdditive());
      } else {
        return left;
      }
    }
  }

  private parseAdditive(): Node {
    let left = this.parseMultiplicative();
    for (;;) {
      if (this.take('+')) {
        left = new Binary('+', left, this.parseMultiplicative());
      } else if (this.take('-')) {
        left = new Binary('-', left, this.parseMultiplicative());
      } else {
        return left;
      }
    }
  }

  private parseMultiplicative(): Node {
    let left = this.parseUnary();
    for (;;) {
      if (this.take('*')) {
        left = new Binary('*', left, this.parseUnary());
      } else if (this.take('/')) {
        left = new Binary('/', left, this.parseUnary());
      } else if (this.take('%')) {
        left = new Binary('%', left, this.parseUnary());
      } else {
        return left;
      }
    }
  }

  private parseUnary(): Node {
    this.skipWs();
    if (this.take('!')) {
      return new Unary('!', this.parseUnary());
    }
    if (this.take('-')) {
      return new Unary('-', this.parseUnary());
    }
    return this.parsePostfix();
  }

  private parsePostfix(): Node {
    let n = this.parsePrimary();
    for (;;) {
      this.skipWs();
      if (this.peek('.')) {
        this.pos++;
        const name = this.ident();
        this.skipWs();
        if (this.peek('(')) {
          this.pos++;
          n = new Call(n, name, this.parseArgs());
        } else {
          n = new Member(n, name);
        }
      } else if (this.peek('[')) {
        this.pos++;
        const idx = this.parseTernary();
        this.skipWs();
        this.expect(']');
        n = new Index(n, idx);
      } else {
        return n;
      }
    }
  }

  private parsePrimary(): Node {
    this.skipWs();
    if (this.pos >= this.src.length) {
      throw new ExpressionError(`Unexpected end of expression "${this.src.trim()}"`);
    }
    const c = this.src.charAt(this.pos);
    if (c === '(') {
      this.pos++;
      const n = this.parseTernary();
      this.skipWs();
      this.expect(')');
      return n;
    }
    if (c === '{') {
      return this.parseObject();
    }
    if (c === '[') {
      return this.parseArray();
    }
    if (c === '"' || c === "'") {
      return new Literal(this.string());
    }
    if (DIGIT.test(c)) {
      const start = this.pos;
      while (this.pos < this.src.length && (DIGIT.test(this.src.charAt(this.pos)) || this.src.charAt(this.pos) === '.')) {
        this.pos++;
      }
      const digits = this.src.substring(start, this.pos);
      const value = parseJavaDouble(digits);
      if (value === null) {
        throw new ExpressionError(`Invalid number '${digits}' at ${start} in "${this.src.trim()}"`);
      }
      return new Literal(number(value));
    }
    if (c === '$') {
      this.pos++;
      this.skipWs();
      if (this.peek('(')) {
        this.pos++;
        this.skipWs();
        const step = this.string();
        this.skipWs();
        this.expect(')');
        return new Root(null, step);
      }
      const name = this.ident();
      this.skipWs();
      if (this.peek('(') && FUNCTION_NAMES.includes(name)) {
        this.pos++;
        return new Func(name, this.parseArgs());
      }
      return new Root(name, null);
    }
    const word = this.ident();
    switch (word) {
      case 'true':
        return new Literal(true);
      case 'false':
        return new Literal(false);
      case 'null':
      case 'undefined':
        return new Literal(null);
      default:
        if (this.params.includes(word)) {
          return new Param(word);
        }
        throw new ExpressionError(`Unknown identifier '${word}' (references start with $)`);
    }
  }

  /** Arguments after an opening parenthesis, each a value or a `v => expr` function. */
  private parseArgs(): Node[] {
    const args: Node[] = [];
    this.skipWs();
    if (!this.peek(')')) {
      do {
        this.skipWs();
        const fn = this.tryLambda();
        args.push(fn ?? this.parseTernary());
        this.skipWs();
      } while (this.take(','));
    }
    this.expect(')');
    return args;
  }

  private tryLambda(): Node | null {
    const save = this.pos;
    try {
      const names: string[] = [];
      if (this.take('(')) {
        do {
          names.push(this.ident());
          this.skipWs();
        } while (this.take(','));
        this.expect(')');
      } else {
        names.push(this.ident());
      }
      this.skipWs();
      if (!this.take('=>')) {
        this.pos = save;
        return null;
      }
      this.params.push(...names);
      try {
        const body = this.parseTernary();
        return new Lambda(names[0] as string, names.length > 1 ? (names[1] as string) : null, body);
      } finally {
        for (const name of names) {
          const i = this.params.indexOf(name);
          if (i >= 0) {
            this.params.splice(i, 1);
          }
        }
      }
    } catch (e) {
      if (!(e instanceof ExpressionError)) {
        throw e;
      }
      this.pos = save;
      return null;
    }
  }

  private parseObject(): Node {
    this.expect('{');
    const fields = new Map<string, Node>();
    this.skipWs();
    if (!this.peek('}')) {
      do {
        this.skipWs();
        const key = this.peek('"') || this.peek("'") ? this.string() : this.ident();
        this.skipWs();
        this.expect(':');
        fields.set(key, this.parseTernary());
        this.skipWs();
      } while (this.take(','));
    }
    this.skipWs();
    this.expect('}');
    return new ObjectLiteral(fields);
  }

  private parseArray(): Node {
    this.expect('[');
    const items: Node[] = [];
    this.skipWs();
    if (!this.peek(']')) {
      do {
        items.push(this.parseTernary());
        this.skipWs();
      } while (this.take(','));
    }
    this.skipWs();
    this.expect(']');
    return new ArrayLiteral(items);
  }

  private string(): string {
    const q = this.src.charAt(this.pos++);
    let sb = '';
    while (this.pos < this.src.length && this.src.charAt(this.pos) !== q) {
      const c = this.src.charAt(this.pos++);
      if (c === '\\' && this.pos < this.src.length) {
        sb += this.src.charAt(this.pos++);
      } else {
        sb += c;
      }
    }
    if (this.pos >= this.src.length) {
      throw new ExpressionError(`Unterminated string in "${this.src.trim()}"`);
    }
    this.pos++;
    return sb;
  }

  private ident(): string {
    this.skipWs();
    const start = this.pos;
    while (this.pos < this.src.length && WORD.test(this.src.charAt(this.pos))) {
      this.pos++;
    }
    if (start === this.pos) {
      throw new ExpressionError(`Expected a name at ${this.pos} in "${this.src.trim()}"`);
    }
    return this.src.substring(start, this.pos);
  }

  private skipWs(): void {
    while (this.pos < this.src.length && WS.test(this.src.charAt(this.pos))) {
      this.pos++;
    }
  }

  private peek(c: string): boolean {
    return this.pos < this.src.length && this.src.charAt(this.pos) === c;
  }

  private peekStr(s: string): boolean {
    return this.src.startsWith(s, this.pos);
  }

  private take(s: string): boolean {
    this.skipWs();
    if (this.src.startsWith(s, this.pos)) {
      // do not confuse "<=" with "<", "!=" with "!"
      if (s.length === 1 && this.pos + 1 < this.src.length) {
        const next = this.src.charAt(this.pos + 1);
        if ((s === '<' || s === '>' || s === '!') && next === '=') {
          return false;
        }
      }
      this.pos += s.length;
      return true;
    }
    return false;
  }

  private expect(c: string): void {
    this.skipWs();
    if (!this.peek(c)) {
      throw new ExpressionError(`Expected '${c}' at ${this.pos} in "${this.src.trim()}"`);
    }
    this.pos++;
  }
}
