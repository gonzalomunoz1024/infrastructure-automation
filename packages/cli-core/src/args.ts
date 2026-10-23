/** A mistake in the command line: the message says what to change. */
export class UsageError extends Error {
  override readonly name = 'UsageError';
}

export type FlagType = 'string' | 'integer' | 'number' | 'boolean';
export type FlagValue = string | number | boolean | (string | number | boolean)[];

export interface FlagDef {
  /** The long name without dashes: `job-id`; for a pattern flag, how help writes it: `tag.<name>`. */
  name: string;
  short?: string;
  type: FlagType;
  /** It may be given more than once; its value is then a list. */
  array?: boolean;
  enum?: readonly string[];
  /** A flag named by a pattern (`--tag.env`): each name it matches is a flag of its own. */
  namePattern?: RegExp;
}

export interface ParsedArgs {
  positionals: string[];
  /** By the flag's name; a pattern flag by the name given (`tag.env`). */
  flags: Map<string, FlagValue>;
}

/**
 * Parses `--name value`, `--name=value`, `-o value`, boolean `--all`, `--all=false` and `--no-all`,
 * and `--` before positional arguments that start with a dash. A value may itself start with a
 * dash (`--sort -created`).
 */
export function parseArgs(argv: readonly string[], defs: readonly FlagDef[]): ParsedArgs {
  const long = new Map(defs.filter((d) => !d.namePattern).map((d) => [d.name, d]));
  const short = new Map(defs.filter((d) => d.short).map((d) => [d.short!, d]));
  const patterns = defs.filter((d) => d.namePattern);
  const positionals: string[] = [];
  const flags = new Map<string, FlagValue>();

  const lookup = (name: string): { def: FlagDef; key: string } | undefined => {
    const def = long.get(name);
    if (def) return { def, key: def.name };
    const p = patterns.find((d) => d.namePattern!.test(name));
    return p ? { def: p, key: name } : undefined;
  };

  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]!;
    if (token === '--') {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (!isFlag(token)) {
      positionals.push(token);
      continue;
    }
    const isLong = token.startsWith('--');
    const body = token.slice(isLong ? 2 : 1);
    const eq = body.indexOf('=');
    const name = eq < 0 ? body : body.slice(0, eq);
    const inline = eq < 0 ? undefined : body.slice(eq + 1);
    const shown = `${isLong ? '--' : '-'}${name}`;

    let found = isLong ? lookup(name) : short.has(name) ? { def: short.get(name)!, key: short.get(name)!.name } : undefined;
    let negated = false;
    if (!found && isLong && name.startsWith('no-')) {
      const positive = lookup(name.slice(3));
      if (positive?.def.type === 'boolean') {
        found = positive;
        negated = true;
      }
    }
    if (!found) {
      const names = [...long.keys(), ...patterns.map((d) => d.name)];
      throw new UsageError(`unknown flag ${shown}${suggest(name, names, (s) => `--${s}`)}`);
    }
    const { def, key } = found;

    let value: string | number | boolean;
    if (def.type === 'boolean') {
      if (negated && inline !== undefined) throw new UsageError(`${shown} takes no value`);
      value = inline === undefined ? !negated : parseBoolean(inline, shown);
    } else {
      let raw = inline;
      if (raw === undefined) {
        if (i + 1 >= argv.length) throw new UsageError(`${shown} needs a value`);
        raw = argv[++i]!;
      }
      value = coerce(raw, def, shown);
    }

    if (def.array) {
      const list = (flags.get(key) as (string | number | boolean)[] | undefined) ?? [];
      list.push(value);
      flags.set(key, list);
    } else {
      if (flags.has(key)) throw new UsageError(`${shown} is given twice`);
      flags.set(key, value);
    }
  }
  return { positionals, flags };
}

/** A token is a flag when it starts with a dash and is not a lone dash or a negative number. */
export function isFlag(token: string): boolean {
  return token.length > 1 && token.startsWith('-') && !/^-\d/.test(token);
}

function parseBoolean(raw: string, shown: string): boolean {
  if (/^(true|yes|1)$/i.test(raw)) return true;
  if (/^(false|no|0)$/i.test(raw)) return false;
  throw new UsageError(`${shown} is true or false, not ${raw}`);
}

function coerce(raw: string, def: FlagDef, shown: string): string | number {
  if (def.enum && !def.enum.includes(raw)) {
    throw new UsageError(`${shown} is one of ${def.enum.join(', ')}, not ${raw}${suggest(raw, def.enum, (s) => s)}`);
  }
  if (def.type === 'integer') {
    if (!/^-?\d+$/.test(raw)) throw new UsageError(`${shown} takes a whole number, not ${raw}`);
    return Number(raw);
  }
  if (def.type === 'number') {
    const n = Number(raw);
    if (raw.trim() === '' || !Number.isFinite(n)) throw new UsageError(`${shown} takes a number, not ${raw}`);
    return n;
  }
  return raw;
}

/** `; did you mean --job-id?` for a near miss, else nothing. */
export function suggest(given: string, candidates: Iterable<string>, show: (s: string) => string): string {
  let best: string | undefined;
  let bestDistance = Infinity;
  for (const c of candidates) {
    const d = c.startsWith(given) && given.length >= 3 ? 1 : distance(given, c);
    if (d < bestDistance) {
      best = c;
      bestDistance = d;
    }
  }
  const limit = Math.max(1, Math.min(3, Math.floor(given.length / 3)));
  return best !== undefined && bestDistance <= limit ? `; did you mean ${show(best)}?` : '';
}

/** Edits between two words, a swap of neighbours counting as one (optimal string alignment). */
function distance(a: string, b: string): number {
  const d: number[][] = Array.from({ length: a.length + 1 }, (_, i) => Array.from({ length: b.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)));
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let best = Math.min(d[i - 1]![j]! + 1, d[i]![j - 1]! + 1, d[i - 1]![j - 1]! + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) best = Math.min(best, d[i - 2]![j - 2]! + 1);
      d[i]![j] = best;
    }
  }
  return d[a.length]![b.length]!;
}
