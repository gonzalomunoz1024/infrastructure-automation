/** The CLI over a repository root: the report lines and the exit code. */
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { checkRepo, formatProblem, formatReport, main } from '../cli.ts';
import { REPO_ROOT } from './repo.ts';

const temp = mkdtempSync(join(tmpdir(), 'workflow-checks-'));
afterAll(() => rmSync(temp, { recursive: true, force: true }));

function write(path: string, text: string): void {
  mkdirSync(join(temp, path, '..'), { recursive: true });
  writeFileSync(join(temp, path), text);
}

describe('cli', () => {
  it('passes over the repository content', () => {
    const lines: string[] = [];
    expect(main([REPO_ROOT], (l) => lines.push(l))).toBe(0);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatch(/^Checked \d+ workflows against \d+ providers and \d+ resource types: 0 errors, 0 warnings\.$/);
  });

  it('fails on errors, not on warnings, and reports broken content files', () => {
    write('providers/team/p.yaml', 'kind: Provider\nmetadata: { name: p }\nspec:\n  type: http\n  actions:\n    - id: thing.get\n      effect: read\n');
    write('providers/team/broken.yaml', 'kind: Provider\nmetadata: { name: broken }\n');
    write('providers/team/syntax.yaml', 'kind: Provider\nmetadata: { name: syntax\n');
    write('resource-types/thing.yaml', 'kind: ResourceType\nmetadata: { name: thing }\n');
    write('resource-types/README.md', 'not yaml');
    write('workflows/team/warn.yaml', 'kind: Workflow\nmetadata: { name: warn, title: W, noun: thing, verb: get, owner: team }\nworkflow:\n  do:\n    - a:\n        call: provider\n        with: { provider: p, action: thing.get, input: { id: "{{ $vars.nope }}" } }\n');
    write('workflows/team/notes.yaml', 'just: notes\n');
    const warningsOnly = checkRepo(temp);
    expect(warningsOnly.workflows).toBe(1);
    expect(warningsOnly.providers).toBe(1);
    expect(warningsOnly.resourceTypes).toBe(1);
    expect(warningsOnly.errors).toBe(2);
    expect(warningsOnly.warnings).toBe(1);
    expect(formatReport(warningsOnly)).toEqual([
      'providers/team/broken.yaml: error yaml.invalid - spec is required',
      'providers/team/syntax.yaml: error yaml.invalid - YAML syntax: Flow map in block collection must be sufficiently indented and end with a } at line 3, column 1:',
      'workflows/team/warn.yaml: warning expr.unknownVar @a.input.id - No earlier set step defines $vars.nope',
      '',
      'Checked 1 workflow against 1 provider and 1 resource type: 2 errors, 1 warning.',
    ]);
    rmSync(join(temp, 'providers/team/broken.yaml'));
    rmSync(join(temp, 'providers/team/syntax.yaml'));
    const lines: string[] = [];
    expect(main([temp], (l) => lines.push(l))).toBe(0);
    expect(lines[lines.length - 1]).toBe('Checked 1 workflow against 1 provider and 1 resource type: 0 errors, 1 warning.');

    write('workflows/team/bad.yaml', 'kind: Workflow\nmetadata: { name: bad, title: B, noun: thing, verb: get, owner: team }\nworkflow:\n  do:\n    - a:\n        call: provider\n        with: { provider: q, action: thing.get }\n');
    const failing: string[] = [];
    expect(main([temp], (l) => failing.push(l))).toBe(1);
    expect(failing[0]).toBe("workflows/team/bad.yaml: error step.unknownProvider @a.provider - Unknown provider 'q' [Did you mean p?]");
  });

  it('formats a problem with its location', () => {
    expect(formatProblem('w.yaml', { level: 'error', code: 'x', message: 'm' })).toBe('w.yaml: error x - m');
    expect(formatProblem('w.yaml', { level: 'error', code: 'x', step: 's', message: 'm' })).toBe('w.yaml: error x @s - m');
    expect(formatProblem('w.yaml', { level: 'warning', code: 'x', field: 'metadata.name', message: 'm' })).toBe('w.yaml: warning x @metadata.name - m');
  });

  it('prints the usage', () => {
    const lines: string[] = [];
    expect(main(['--help'], (l) => lines.push(l))).toBe(0);
    expect(lines[0]).toBe('Usage: check-workflows [root]');
  });
});
