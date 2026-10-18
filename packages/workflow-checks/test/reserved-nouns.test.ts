/**
 * The reserved nouns the api.reservedPath check judges against are the gateway's: the list in
 * checks.ts is the one ReservedNouns.java keeps, read from the backend's source so neither side
 * can drift from the other.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { RESERVED_NOUNS } from '../src/index.ts';
import { REPO_ROOT } from './repo.ts';

const JAVA = join(REPO_ROOT, 'backend/src/main/java/com/iaf/platform/catalog/domain/ReservedNouns.java');

/** The quoted strings of the `NOUNS = Set.of(...)` initializer. */
function javaNouns(): string[] {
  const source = readFileSync(JAVA, 'utf8');
  const match = /NOUNS\s*=\s*Set\.of\(([\s\S]*?)\);/.exec(source);
  if (match === null) {
    throw new Error(`${JAVA} has no NOUNS = Set.of(...)`);
  }
  return [...(match[1] ?? '').matchAll(/"([^"]+)"/g)].map((m) => m[1] ?? '');
}

describe('reserved nouns', () => {
  it('are the nouns ReservedNouns.java reserves for the gateway', () => {
    const java = javaNouns();
    expect(java.length).toBeGreaterThan(20);
    expect([...RESERVED_NOUNS].sort()).toEqual([...java].sort());
    expect(new Set(RESERVED_NOUNS).size).toBe(RESERVED_NOUNS.length);
  });
});
