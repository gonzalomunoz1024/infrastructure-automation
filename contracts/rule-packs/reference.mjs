// The reference for the declarative half of a rule pack (contracts/schemas/rule-pack.schema.json):
// how `types`, `when`, `check` and `message` judge one evaluation input. The drift service's
// embedded engine follows exactly these rules, and `npm run check` runs every rule's examples
// through this file and through the rule's Rego, which must agree.
//
// A value is read at a dotted path (`resource.tags.data-class`); every test but
// `present: false` is false where the value is missing (absent or null), so a rule never passes
// on data it does not have. Numbers compare by value; `matches` is a whole-string match.

export const PASS = 'pass';
export const FAIL = 'fail';
export const NOT_APPLICABLE = 'not-applicable';

export function valueAt(input, path) {
  let node = input;
  for (const segment of path.split('.')) {
    if (node === null || typeof node !== 'object' || Array.isArray(node) || !(segment in node)) return undefined;
    node = node[segment];
  }
  return node === null ? undefined : node;
}

const same = (a, b) => {
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  return JSON.stringify(a) === JSON.stringify(b);
};

export function holds(condition, input) {
  if ('all' in condition) return condition.all.every((c) => holds(c, input));
  if ('any' in condition) return condition.any.some((c) => holds(c, input));
  if ('not' in condition) return !holds(condition.not, input);
  const value = valueAt(input, condition.path);
  const missing = value === undefined;
  const [op] = Object.keys(condition).filter((k) => k !== 'path');
  const arg = condition[op];
  switch (op) {
    case 'present': {
      const there = !missing && value !== '';
      return arg ? there : !there;
    }
    case 'equals': return !missing && same(value, arg);
    case 'notEquals': return !missing && !same(value, arg);
    case 'in': return !missing && arg.some((a) => same(value, a));
    case 'notIn': return !missing && !arg.some((a) => same(value, a));
    case 'gte': return typeof value === 'number' && value >= arg;
    case 'gt': return typeof value === 'number' && value > arg;
    case 'lte': return typeof value === 'number' && value <= arg;
    case 'lt': return typeof value === 'number' && value < arg;
    case 'matches': return typeof value === 'string' && new RegExp(`^(?:${arg})$`).test(value);
    default: throw new Error(`unknown test ${op}`);
  }
}

export function applies(rule, input) {
  const type = valueAt(input, 'resource.type');
  if (!rule.types.includes('*') && !rule.types.includes(type)) return false;
  return rule.when ? holds(rule.when, input) : true;
}

export function judge(rule, input) {
  if (!applies(rule, input)) return NOT_APPLICABLE;
  return holds(rule.check, input) ? PASS : FAIL;
}

/** The rule's message with `{{ path }}` filled in from the input (an empty string where missing). */
export function message(rule, input) {
  return rule.message.replace(/\{\{\s*([A-Za-z0-9_.-]+)\s*}}/g, (_, path) => {
    const v = valueAt(input, path);
    return v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
  });
}

/** The Rego package a rule's policy must declare. */
export const regoPackage = (pack, rule) => `console.scorecards.${pack.replaceAll('-', '_')}.${rule.replaceAll('-', '_')}`;
