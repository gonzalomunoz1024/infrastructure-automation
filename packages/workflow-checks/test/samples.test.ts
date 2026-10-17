/** The repo's own sample content must pass the checks: it is what the demo and the docs show. */
import { describe, expect, it } from 'vitest';
import { checkRepo } from '../cli.ts';
import { check } from '../src/index.ts';
import { ofKind, repoContext, REPO_ROOT } from './repo.ts';

describe('sample content', () => {
  it('every sample workflow passes the checks', () => {
    const ctx = repoContext();
    const files = ofKind('Workflow');
    expect(files.length).toBeGreaterThanOrEqual(9);
    const report: string[] = [];
    for (const f of files) {
      for (const p of check(f.doc, ctx)) {
        report.push(`${f.path}: ${p.level} ${p.code}${p.step === undefined ? '' : ` @${p.step}`}${p.field === undefined ? '' : `.${p.field}`} - ${p.message}`);
      }
    }
    expect(report, 'problems in sample content').toEqual([]);
  });

  it('the CLI report over the repository is clean', () => {
    const report = checkRepo(REPO_ROOT);
    expect(report.workflows).toBeGreaterThanOrEqual(9);
    expect(report.providers).toBeGreaterThanOrEqual(8);
    expect(report.resourceTypes).toBeGreaterThanOrEqual(5);
    expect(report.files.flatMap((f) => f.problems)).toEqual([]);
    expect(report.errors).toBe(0);
    expect(report.warnings).toBe(0);
  });
});
