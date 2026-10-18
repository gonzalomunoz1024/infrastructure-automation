/**
 * The step types of the TypeScript twin are the backend's: keys, YAML keys, categories and the
 * container and activity sets as StepType.java has them, titles and descriptions as
 * StepTypeSchemas.java words them (GET /registry/step-types), read from the backend's sources so
 * neither side can drift from the other.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { STEP_TYPE_KEYS, STEP_TYPES, type StepType } from '../src/index.ts';
import { REPO_ROOT } from './repo.ts';

const DOMAIN = join(REPO_ROOT, 'backend/src/main/java/com/iaf/platform/workflows/domain');
const STEP_TYPE = readFileSync(join(DOMAIN, 'StepType.java'), 'utf8');
const SCHEMAS = readFileSync(join(DOMAIN, 'StepTypeSchemas.java'), 'utf8');

interface JavaConstant {
  constant: string;
  key: string;
  yamlKey: string;
  category: string;
}

/** `PROVIDER("provider", "call", "Providers"),` and its siblings. */
function constants(): JavaConstant[] {
  return [...STEP_TYPE.matchAll(/^\s*([A-Z]+)\("([^"]+)", "([^"]+)", "([^"]+)"\)[,;]/gm)].map((m) => ({
    constant: m[1] ?? '',
    key: m[2] ?? '',
    yamlKey: m[3] ?? '',
    category: m[4] ?? '',
  }));
}

/** The body of one method, from its signature to the first `}` on a line of its own. */
function body(source: string, signature: string): string {
  const start = source.indexOf(signature);
  if (start < 0) {
    throw new Error(`no ${signature}`);
  }
  const end = source.indexOf('\n    }', start);
  return source.substring(start, end);
}

/** The constants a predicate names (`this == FORK || this == FOR`). */
function named(method: string): Set<string> {
  return new Set([...body(STEP_TYPE, method).matchAll(/this == ([A-Z]+)/g)].map((m) => m[1] ?? ''));
}

/** `case PROVIDER -> "Provider action";` per constant of a switch over StepType. */
function cases(signature: string): Map<string, string> {
  const out = new Map<string, string>();
  for (const m of body(SCHEMAS, signature).matchAll(/case ([A-Z]+) -> "((?:[^"\\]|\\.)*)";/g)) {
    out.set(m[1] ?? '', JSON.parse(`"${m[2] ?? ''}"`) as string);
  }
  return out;
}

const java = constants();
const keyOf = new Map(java.map((c) => [c.constant, c.key as StepType]));

describe('step types', () => {
  it('are the constants of StepType.java, in its order, with its YAML keys and categories', () => {
    expect(java.length).toBe(19);
    expect(STEP_TYPE_KEYS).toEqual(java.map((c) => c.key));
    for (const c of java) {
      const info = STEP_TYPES[c.key as StepType];
      expect({ key: info.key, yamlKey: info.yamlKey, category: info.category, isCall: info.isCall }, c.constant).toEqual({
        key: c.key,
        yamlKey: c.yamlKey,
        category: c.category,
        isCall: c.yamlKey === 'call',
      });
    }
  });

  it('call fork, for and try containers, as StepType.isContainer does', () => {
    const containers = [...named('public boolean isContainer()')].map((c) => keyOf.get(c));
    expect(containers.sort()).toEqual(['for', 'fork', 'try']);
    expect(STEP_TYPE_KEYS.filter((k) => STEP_TYPES[k].isContainer).sort()).toEqual(containers);
  });

  it('call the integration steps, notify, script, register and use activities, as StepType.isActivity does', () => {
    expect(body(STEP_TYPE, 'public boolean isActivity()')).toContain('isCall() ||');
    const activities = new Set(java.filter((c) => c.yamlKey === 'call').map((c) => c.key));
    for (const c of named('public boolean isActivity()')) {
      activities.add(keyOf.get(c) as StepType);
    }
    expect(STEP_TYPE_KEYS.filter((k) => STEP_TYPES[k].isActivity).sort()).toEqual([...activities].sort());
  });

  it('have the titles and descriptions of StepTypeSchemas.java', () => {
    const titles = cases('public static String title(StepType t)');
    const descriptions = cases('public static String description(StepType t)');
    expect(titles.size).toBe(java.length);
    expect(descriptions.size).toBe(java.length);
    for (const c of java) {
      expect(STEP_TYPES[c.key as StepType].title, c.constant).toBe(titles.get(c.constant));
      expect(STEP_TYPES[c.key as StepType].description, c.constant).toBe(descriptions.get(c.constant));
    }
    expect(STEP_TYPES.script.description).toContain('JavaScript, Python, Bash or Go');
  });
});
