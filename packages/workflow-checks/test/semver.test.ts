/** The range rules of Semver.java, which both twins use to pick the version a run step starts. */
import { describe, expect, it } from 'vitest';
import { parseVersion, satisfies } from '../src/semver.ts';

describe('semver', () => {
  it('reads the numbers before a pre-release suffix', () => {
    expect(parseVersion('2.3.1')).toEqual({ major: 2, minor: 3, patch: 1 });
    expect(parseVersion('2.3.1-rc.1')).toEqual({ major: 2, minor: 3, patch: 1 });
    expect(parseVersion('2')).toEqual({ major: 2, minor: 0, patch: 0 });
    expect(parseVersion('v1.x')).toEqual({ major: 1, minor: 0, patch: 0 });
    expect(parseVersion(null)).toEqual({ major: 0, minor: 0, patch: 0 });
  });

  it('takes the ranges run.version accepts as Semver.satisfies does', () => {
    const cases: [string, string | null, boolean][] = [
      ['1.4.0', null, true],
      ['1.4.0', '*', true],
      ['1.4.0', 'latest', true],
      ['1.4.0', '^1', true],
      ['1.4.0', '^1.5', false],
      ['2.0.1', '^1', false],
      ['1.2.9', '~1.2', true],
      ['1.3.0', '~1.2', false],
      ['1.2.3', '1.2.3', true],
      ['1.2.4', '1.2.3', false],
      ['1.9.9', '1', true],
      ['2.0.0', '1', false],
      ['1.2.7', '1.2', true],
      ['1.3.0', '1.2', false],
      ['1.2.7', '1.2.x', true],
    ];
    for (const [version, range, expected] of cases) {
      expect(satisfies(version, range), `${version} in ${String(range)}`).toBe(expected);
    }
  });
});
