/** The pinned playbooks of the content and the ansible-lint pass over them, with a local git repository and a fake ansible-lint. */
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { scan } from '../cli.ts';
import { main } from '../lint-playbooks.ts';
import { playbookPins, remoteUrl } from '../src/playbook-pins.ts';
import { REPO_ROOT } from './repo.ts';

const temp = mkdtempSync(join(tmpdir(), 'lint-playbooks-test-'));
afterAll(() => rmSync(temp, { recursive: true, force: true }));

function write(path: string, text: string): string {
  const full = join(temp, path);
  mkdirSync(join(full, '..'), { recursive: true });
  writeFileSync(full, text);
  return full;
}

function git(cwd: string, ...args: string[]): string {
  const r = spawnSync('git', ['-c', 'user.name=Test', '-c', 'user.email=test@example.com', '-c', 'commit.gpgsign=false', ...args], { cwd, encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error(`git ${args.join(' ')}: ${r.stderr}`);
  }
  return r.stdout.trim();
}

const workflow = (name: string, playbook: string): string => `kind: Workflow
metadata: { name: ${name}, title: T, noun: thing, verb: fix, owner: team }
workflow:
  do:
    - fixIt:
        call: ansible
        with:
          playbook: ${playbook}
`;

describe('playbookPins', () => {
  it('groups the pins of the repository content by source and commit', () => {
    const { pins, skipped } = playbookPins(scan(REPO_ROOT));

    expect(skipped).toEqual([]);
    expect(pins).toHaveLength(1);
    expect(pins[0]).toMatchObject({
      repo: 'github.com/acme/ops-playbooks',
      url: 'https://github.com/acme/ops-playbooks',
      commit: '4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d',
      playbooks: ['mongo/set-fcv.yml', 'patch/cve.yml', 'vm/restart.yml'],
    });
    expect(pins[0]?.from).toContain('workflows/team-compute/vm-restart.yaml @restart');
    expect(pins[0]?.from).toContain('providers/team-platform/ops-playbooks.yaml @vm.restart');
  });

  it('says why a playbook cannot be linted at a commit', () => {
    const doc = (playbook: unknown): unknown => ({ kind: 'Workflow', workflow: { do: [{ fixIt: { call: 'ansible', with: { playbook } } }] } });
    const files = [
      { path: 'a.yaml', kind: 'Workflow', doc: doc({ git: { repo: 'github.com/acme/x', ref: 'main', path: 'p.yml' } }) },
      { path: 'b.yaml', kind: 'Workflow', doc: doc({ git: { repo: 'github.com/acme/x', path: 'p.yml' }, commit: 'abc1234' }) },
      { path: 'c.yaml', kind: 'Workflow', doc: doc({ git: { repo: 'git@github.com:acme/x.git', path: 'p.yml' }, commit: 'a'.repeat(40) }) },
      { path: 'd.yaml', kind: 'Workflow', doc: doc({ git: { repo: 'github.com/acme/x', path: '../p.yml' }, commit: 'a'.repeat(40) }) },
      { path: 'e.yaml', kind: 'Workflow', doc: doc({ upload: { file: 'p.yml', sha256: 'b'.repeat(64) } }) },
      { path: 'f.yaml', kind: 'Workflow', doc: doc('ops/vm.restart') },
    ];

    const { pins, skipped } = playbookPins(files);

    expect(pins).toEqual([]);
    expect(skipped).toEqual([
      { from: 'a.yaml @fixIt', reason: 'github.com/acme/x p.yml is not pinned to a commit (publishing pins it)' },
      { from: 'b.yaml @fixIt', reason: "github.com/acme/x p.yml: 'abc1234' is not a full commit id" },
      { from: 'c.yaml @fixIt', reason: 'git@github.com:acme/x.git is not an https remote' },
      { from: 'd.yaml @fixIt', reason: '../p.yml leaves the repository' },
      { from: 'e.yaml @fixIt', reason: 'an uploaded playbook is checked when it is uploaded' },
    ]);
  });

  it('fetches https remotes only', () => {
    expect(remoteUrl('github.com/acme/ops')).toBe('https://github.com/acme/ops');
    expect(remoteUrl('git.example.com:8443/team/ops.git')).toBe('https://git.example.com:8443/team/ops.git');
    expect(remoteUrl('https://gitlab.example.com/team/ops.git')).toBe('https://gitlab.example.com/team/ops.git');
    expect(remoteUrl('http://github.com/acme/ops')).toBeNull();
    expect(remoteUrl('ssh://git@github.com/acme/ops')).toBeNull();
    expect(remoteUrl('git@github.com:acme/ops.git')).toBeNull();
    expect(remoteUrl('https://user:pw@github.com/acme/ops')).toBeNull();
    expect(remoteUrl('/srv/git/ops')).toBeNull();
    expect(remoteUrl('file:///srv/git/ops')).toBeNull();
    expect(remoteUrl('file:///srv/git/ops', true)).toBe('file:///srv/git/ops');
  });
});

describe('lint-playbooks', () => {
  let repo = '';
  let first = '';
  const lintLog = join(temp, 'lint.log');
  const fakeLint = join(temp, 'bin', 'ansible-lint');
  const fakeLeaks = join(temp, 'bin', 'gitleaks');
  const leaksLog = join(temp, 'gitleaks.log');
  const env = (exitCode = 0): NodeJS.ProcessEnv => ({ ...process.env, ANSIBLE_LINT: fakeLint, FAKE_LINT_EXIT: String(exitCode), GITHUB_ACTIONS: '', GITLEAKS: '' });

  beforeAll(() => {
    repo = join(temp, 'origin');
    mkdirSync(join(repo, 'site'), { recursive: true });
    git(repo, 'init', '-q', '-b', 'main');
    writeFileSync(join(repo, 'site', 'restart.yml'), '- hosts: all\n  tasks: []\n');
    git(repo, 'add', '.');
    git(repo, 'commit', '-q', '-m', 'first');
    first = git(repo, 'rev-parse', 'HEAD');
    writeFileSync(join(repo, 'site', 'later.yml'), '- hosts: all\n  tasks: []\n');
    git(repo, 'add', '.');
    git(repo, 'commit', '-q', '-m', 'second');
    write('bin/ansible-lint', `#!/bin/sh\nif [ "$1" = "--version" ]; then echo "ansible-lint 0.0.0"; exit 0; fi\necho "$(pwd) $*" >> '${lintLog}'\nexit "\${FAKE_LINT_EXIT:-0}"\n`);
    chmodSync(fakeLint, 0o755);
    write('bin/gitleaks', `#!/bin/sh\necho "$*" >> '${leaksLog}'\nexit "\${FAKE_LEAKS_EXIT:-0}"\n`);
    chmodSync(fakeLeaks, 0o755);
  });

  function content(name: string, path: string, commit: string, repoUrl = `file://${repo}`): string {
    const root = join(temp, name);
    mkdirSync(join(root, 'workflows'), { recursive: true });
    writeFileSync(join(root, 'workflows', 'w.yaml'), workflow('w', `{ git: { repo: "${repoUrl}", ref: main, path: ${path} }, commit: ${commit} }`));
    return root;
  }

  it('lints each pinned playbook in a checkout of its commit', () => {
    const root = content('pinned', 'site/restart.yml', first);
    const lines: string[] = [];

    expect(main([root, '--allow-file-urls'], (l) => lines.push(l), env())).toBe(0);

    const calls = readFileSync(lintLog, 'utf8').trim().split('\n');
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatch(/\/lint-playbooks-[^/]+\/0 site\/restart\.yml$/);
    expect(lines[lines.length - 1]).toBe('Linted 1 playbook from 1 source: 0 failures, 0 skipped.');
    expect(lines).toContain('notice: the pinned sources are not scanned for secrets: set GITLEAKS to a gitleaks executable');
    rmSync(lintLog);
  });

  it('fails when ansible-lint does or when the playbook is not at the pinned commit', () => {
    const lines: string[] = [];
    expect(main([content('failing', 'site/restart.yml', first), '--allow-file-urls'], (l) => lines.push(l), env(2))).toBe(1);
    expect(lines).toContain(`error: ansible-lint failed on ${`file://${repo}`}@${first.slice(0, 12)}`);

    const missing: string[] = [];
    expect(main([content('missing', 'site/later.yml', first), '--allow-file-urls'], (l) => missing.push(l), env())).toBe(1);
    expect(missing).toContain(`error: file://${repo}@${first.slice(0, 12)} has no site/later.yml (pinned by workflows/w.yaml @fixIt)`);
    rmSync(lintLog, { force: true });
  });

  it('scans each fetched source for secrets when GITLEAKS names gitleaks', () => {
    const root = content('secrets', 'site/restart.yml', first);
    const clean: string[] = [];

    expect(main([root, '--allow-file-urls'], (l) => clean.push(l), { ...env(), GITLEAKS: fakeLeaks })).toBe(0);
    expect(readFileSync(leaksLog, 'utf8').trim()).toMatch(/^dir --redact --verbose --no-banner --exit-code 1 \/.*\/lint-playbooks-[^/]+\/0$/);
    expect(clean.join('\n')).not.toContain('not scanned for secrets');

    const found: string[] = [];
    expect(main([root, '--allow-file-urls'], (l) => found.push(l), { ...env(), GITLEAKS: fakeLeaks, FAKE_LEAKS_EXIT: '1' })).toBe(1);
    expect(found).toContain(`error: gitleaks found secrets in file://${repo}@${first.slice(0, 12)} (pinned by workflows/w.yaml @fixIt)`);
    rmSync(lintLog, { force: true });
  });

  it('skips a source it cannot fetch with a notice and without ansible-lint', () => {
    const root = content('unreachable', 'site/restart.yml', 'c'.repeat(40), `file://${join(temp, 'nowhere')}`);
    const lines: string[] = [];

    expect(main([root, '--allow-file-urls'], (l) => lines.push(l), { ...env(), ANSIBLE_LINT: join(temp, 'not-installed') })).toBe(0);

    expect(lines.find((l) => l.startsWith('notice: skipped'))).toMatch(/^notice: skipped file:\/\/.*\/nowhere@cccccccccccc \(pinned by workflows\/w\.yaml @fixIt\): could not fetch it: /);
    expect(lines[lines.length - 1]).toBe('Linted 0 playbooks from 0 sources: 0 failures, 1 skipped.');
  });

  it('writes GitHub annotations in a workflow run and needs ansible-lint when there is something to lint', () => {
    const lines: string[] = [];
    const root = content('no-lint', 'site/restart.yml', first);

    expect(main([root, '--allow-file-urls'], (l) => lines.push(l), { ...env(), ANSIBLE_LINT: join(temp, 'not-installed'), GITHUB_ACTIONS: 'true' })).toBe(1);

    expect(lines).toContain(`::error title=lint-playbooks::${join(temp, 'not-installed')} is not installed (pip install ansible-lint)`);
  });
});
