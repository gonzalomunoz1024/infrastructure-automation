/**
 * Semantic versions and the small range syntax `run.version` accepts (`^1`, `~1.2`, `1.2.0`,
 * `1.2`, `*`, `latest`), with the rules of Semver.java, so both twins pick the version a run
 * step's range starts the same way.
 */

interface Version {
  major: number;
  minor: number;
  patch: number;
}

/** Semver.parse: the numbers before any pre-release suffix, 0 where a part is missing or not a number. */
export function parseVersion(s: string | null | undefined): Version {
  if (s === null || s === undefined || s.trim() === '') {
    return { major: 0, minor: 0, patch: 0 };
  }
  const parts = split(s.split('-')[0] ?? '');
  return { major: num(parts[0]), minor: num(parts[1]), patch: num(parts[2]) };
}

/** Semver.satisfies: whether `version` is inside `range`; no range, `*` and `latest` take any version. */
export function satisfies(version: string, range: string | null | undefined): boolean {
  if (range === null || range === undefined || range.trim() === '' || range === '*' || range === 'latest') {
    return true;
  }
  const v = parseVersion(version);
  if (range.startsWith('^')) {
    const min = parseVersion(range.slice(1));
    return compare(v, min) >= 0 && v.major === min.major;
  }
  if (range.startsWith('~')) {
    const min = parseVersion(range.slice(1));
    return compare(v, min) >= 0 && v.major === min.major && v.minor === min.minor;
  }
  const parts = split(range);
  const exact = parseVersion(range);
  if (parts.length === 1) {
    return v.major === exact.major;
  }
  if (parts.length === 2 || range.endsWith('.x')) {
    return v.major === exact.major && v.minor === exact.minor;
  }
  return compare(v, exact) === 0;
}

function compare(a: Version, b: Version): number {
  if (a.major !== b.major) {
    return a.major - b.major;
  }
  if (a.minor !== b.minor) {
    return a.minor - b.minor;
  }
  return a.patch - b.patch;
}

/** String.split("\\.") in Java: trailing empty parts are dropped. */
function split(s: string): string[] {
  const parts = s.split('.');
  while (parts.length > 0 && parts[parts.length - 1] === '') {
    parts.pop();
  }
  return parts;
}

function num(p: string | undefined): number {
  const n = Number.parseInt((p ?? '').replace(/[^0-9]/g, ''), 10);
  return Number.isNaN(n) ? 0 : n;
}
