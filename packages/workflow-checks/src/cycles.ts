/**
 * Cycles of runs, as DependencyRules.java finds them for `run.cycle`: the run links of a workflow
 * (its `orCreateWith` inputs, then its run steps) that lead back to it through what the published
 * workflows start, each with the shortest loop, the next workflows taken in name order.
 */
import { asTextOr, get, isBlank, isObject } from './json.ts';
import { allSteps, withText, type ParsedWorkflow } from './parse.ts';

/** A workflow this one starts: through a run step (`at` is its id) or an `orCreateWith` input (`at` is the field). */
export interface RunLink {
  producer: string;
  via: 'run' | 'orCreateWith';
  at: string;
}

/** A run link that leads back to the workflow, and the loop it closes, from the workflow back to it. */
export interface Loop {
  link: RunLink;
  path: string[];
}

/** The workflows a workflow starts, in the order DependencyRules.of reads them: `orCreateWith` inputs in schema order, then run steps. */
export function runLinks(def: ParsedWorkflow): RunLink[] {
  const out: RunLink[] = [];
  const props = def.api === null ? undefined : get(def.api.input, 'properties');
  if (isObject(props)) {
    for (const [field, schema] of Object.entries(props)) {
      const xr = get(schema, 'x-resource');
      if (!isObject(xr)) {
        continue;
      }
      const type = asTextOr(get(xr, 'type'), null);
      const create = asTextOr(get(xr, 'orCreateWith'), null);
      if (type !== null && !isBlank(type) && create !== null && !isBlank(create)) {
        out.push({ producer: create, via: 'orCreateWith', at: field });
      }
    }
  }
  for (const s of allSteps(def)) {
    const wf = s.type === 'run' ? withText(s, 'workflow') : null;
    if (wf !== null && !isBlank(wf)) {
      out.push({ producer: wf, via: 'run', at: s.id });
    }
  }
  return out;
}

/** The workflows each of the given workflows starts, sorted, by name (DependencyRules.runs). */
export function workflowRuns(workflows: Iterable<ParsedWorkflow>): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const w of workflows) {
    for (const link of runLinks(w)) {
      const known = Object.hasOwn(out, w.metadata.name) ? (out[w.metadata.name] ?? []) : [];
      if (!known.includes(link.producer)) {
        out[w.metadata.name] = [...known, link.producer].sort();
      }
    }
  }
  return out;
}

/**
 * The run links of `def` that lead back to it through what `runs` says each workflow starts.
 * `def` is judged as written: its own links come from the file. A run step that starts the
 * workflow itself is left out (`run.self` says so); an `orCreateWith` of itself is a loop.
 */
export function loops(def: ParsedWorkflow, runs: (name: string) => readonly string[]): Loop[] {
  const name = def.metadata.name;
  const out: Loop[] = [];
  for (const link of runLinks(def)) {
    if (link.producer === name) {
      if (link.via === 'orCreateWith') {
        out.push({ link, path: [name, name] });
      }
      continue;
    }
    const path = shortestPath(link.producer, name, runs);
    if (path !== null) {
      out.push({ link, path: [name, ...path] });
    }
  }
  return out;
}

/** Breadth first from `from` to `to`, the next workflows in name order; the path names both ends. */
function shortestPath(from: string, to: string, runs: (name: string) => readonly string[]): string[] | null {
  const parent = new Map<string, string | null>([[from, null]]);
  const queue: string[] = [from];
  for (let node = queue.shift(); node !== undefined; node = queue.shift()) {
    if (node === to) {
      const path: string[] = [];
      for (let n: string | null | undefined = to; n !== null && n !== undefined; n = parent.get(n)) {
        path.unshift(n);
      }
      return path;
    }
    for (const next of [...runs(node)].sort()) {
      if (!parent.has(next)) {
        parent.set(next, node);
        queue.push(next);
      }
    }
  }
  return null;
}
