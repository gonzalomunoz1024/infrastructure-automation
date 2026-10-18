/**
 * The ansible-lint pass CI runs over every pinned playbook: each git source the content pins
 * (see src/playbook-pins.ts) is fetched at its commit into a temporary directory and its
 * playbooks are linted there, with the repository's own ansible-lint configuration. Playbooks
 * kept in the content repository itself (`playbooks/`) are linted in place. A source that cannot
 * be fetched (unreachable, private, a commit the remote does not have) is skipped with a notice,
 * like the unpinned and uploaded playbooks; a pinned playbook missing at its commit or a lint
 * failure fails the run. With GITLEAKS naming a gitleaks executable, the files of each fetched
 * source are also scanned for secrets, and a finding fails the run. `scripts/lint-playbooks.mjs`
 * at the repository root runs it.
 */
import { spawnSync, type SpawnSyncReturns } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { scan } from './cli.ts';
import { playbookPins, type PlaybookPin } from './src/playbook-pins.ts';

export interface LintOptions {
  root: string;
  /** The ansible-lint executable. */
  ansibleLint: string;
  git: string;
  /** gitleaks, to scan each fetched source for secrets; null skips the scan. */
  gitleaks: string | null;
  /** Tests only: accept `file://` repositories. */
  allowFileUrls: boolean;
  /** GitHub workflow commands for notices and errors (`::notice::`). */
  annotate: boolean;
  /** The environment of git and ansible-lint. */
  env: NodeJS.ProcessEnv;
  write: (line: string) => void;
}

export interface LintSummary {
  linted: number;
  sources: number;
  skipped: number;
  failed: number;
}

const FETCH_TIMEOUT_MS = 180_000;
const LINT_TIMEOUT_MS = 900_000;

function run(cmd: string, args: readonly string[], cwd: string, env: NodeJS.ProcessEnv, timeout: number, inherit = false): SpawnSyncReturns<string> {
  return spawnSync(cmd, args, { cwd, env, timeout, encoding: 'utf8', stdio: inherit ? ['ignore', 'inherit', 'inherit'] : ['ignore', 'pipe', 'pipe'] });
}

function lastLine(s: string | null | undefined): string {
  const lines = (s ?? '').split('\n').map((l) => l.trim()).filter((l) => l.length > 0);
  return lines.length === 0 ? '' : (lines[lines.length - 1] ?? '');
}

function listPlaybooks(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      out.push(...listPlaybooks(full));
    } else if (name.endsWith('.yml') || name.endsWith('.yaml')) {
      out.push(full);
    }
  }
  return out;
}

/** Fetch `pin.commit` of `pin.url` into `dir`; null when it worked, the reason otherwise. */
function fetchPin(pin: PlaybookPin, dir: string, o: LintOptions): string | null {
  const env = { ...o.env, GIT_TERMINAL_PROMPT: '0' };
  const steps: string[][] = [
    ['init', '-q', dir],
    ['-C', dir, 'fetch', '-q', '--depth', '1', '--no-tags', '--no-recurse-submodules', pin.url, pin.commit],
    ['-C', dir, '-c', 'advice.detachedHead=false', 'checkout', '-q', 'FETCH_HEAD'],
  ];
  for (const args of steps) {
    const r = run(o.git, args, o.root, env, FETCH_TIMEOUT_MS);
    if (r.error !== undefined) {
      return r.error.message;
    }
    if (r.status !== 0) {
      return lastLine(r.stderr) || `git ${args[args.indexOf(dir) + 1] ?? args[0]} exited with ${r.status}`;
    }
  }
  return null;
}

/** Lint every pinned and in-repository playbook; the summary, with what went wrong already written. */
export function lintPlaybooks(o: LintOptions): LintSummary {
  const notice = (m: string): void => o.write(o.annotate ? `::notice title=lint-playbooks::${m}` : `notice: ${m}`);
  const error = (m: string): void => o.write(o.annotate ? `::error title=lint-playbooks::${m}` : `error: ${m}`);
  const summary: LintSummary = { linted: 0, sources: 0, skipped: 0, failed: 0 };

  const { pins, skipped } = playbookPins(scan(o.root), { allowFileUrls: o.allowFileUrls });
  for (const s of skipped) {
    notice(`skipped ${s.from}: ${s.reason}`);
    summary.skipped++;
  }
  const local = existsSync(join(o.root, 'playbooks')) ? listPlaybooks(join(o.root, 'playbooks')).map((f) => relative(o.root, f)) : [];

  // asked only once there is something to lint: a run whose sources are all skipped does not need it
  let installed: boolean | null = null;
  const lint = (cwd: string, files: string[], what: string): void => {
    if (installed === null) {
      const version = run(o.ansibleLint, ['--version'], o.root, o.env, 60_000);
      installed = version.error === undefined && version.status === 0;
      if (!installed) {
        error(`${o.ansibleLint} is not installed (pip install ansible-lint)`);
        summary.failed++;
      }
    }
    if (!installed) {
      return;
    }
    o.write(`ansible-lint ${what}: ${files.join(' ')}`);
    const r = run(o.ansibleLint, files, cwd, o.env, LINT_TIMEOUT_MS, true);
    summary.linted += files.length;
    if (r.error !== undefined || r.status !== 0) {
      error(`ansible-lint failed on ${what}${r.error !== undefined ? `: ${r.error.message}` : ''}`);
      summary.failed++;
    }
  };

  const scanSecrets = (dir: string, what: string): void => {
    const r = run(o.gitleaks ?? 'gitleaks', ['dir', '--redact', '--verbose', '--no-banner', '--exit-code', '1', dir], o.root, o.env, LINT_TIMEOUT_MS, true);
    if (r.error !== undefined) {
      error(`gitleaks could not scan ${what}: ${r.error.message}`);
      summary.failed++;
    } else if (r.status !== 0) {
      error(`gitleaks found secrets in ${what}`);
      summary.failed++;
    }
  };

  if (local.length > 0) {
    summary.sources++;
    lint(o.root, local, 'playbooks/');
  }
  if (o.gitleaks === null && pins.length > 0) {
    notice('the pinned sources are not scanned for secrets: set GITLEAKS to a gitleaks executable');
  }
  const work = mkdtempSync(join(tmpdir(), 'lint-playbooks-'));
  try {
    pins.forEach((pin, i) => {
      const at = `${pin.repo}@${pin.commit.slice(0, 12)}`;
      const dir = join(work, String(i));
      const why = fetchPin(pin, dir, o);
      if (why !== null) {
        notice(`skipped ${at} (pinned by ${pin.from.join(', ')}): could not fetch it: ${why}`);
        summary.skipped++;
        return;
      }
      summary.sources++;
      if (o.gitleaks !== null) {
        scanSecrets(dir, `${at} (pinned by ${pin.from.join(', ')})`);
      }
      const missing = pin.playbooks.filter((p) => !existsSync(join(dir, p)));
      for (const p of missing) {
        error(`${at} has no ${p} (pinned by ${pin.from.join(', ')})`);
        summary.failed++;
      }
      const present = pin.playbooks.filter((p) => !missing.includes(p));
      if (present.length > 0) {
        lint(dir, present, at);
      }
    });
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
  return summary;
}

/** `lint-playbooks [root] [--allow-file-urls]`; ANSIBLE_LINT, GIT and GITLEAKS name the executables. The exit code. */
export function main(argv: readonly string[], write: (line: string) => void = (line) => console.log(line), env: NodeJS.ProcessEnv = process.env): number {
  if (argv.includes('-h') || argv.includes('--help')) {
    write('Usage: lint-playbooks [root] [--allow-file-urls]');
    write('Runs ansible-lint on every git playbook the content of <root> pins, at its commit, and on <root>/playbooks.');
    write('ANSIBLE_LINT, GIT and GITLEAKS name the executables; with GITLEAKS each pinned source is scanned for secrets.');
    return 0;
  }
  const args = argv.filter((a) => !a.startsWith('-'));
  const summary = lintPlaybooks({
    root: resolve(args[0] ?? process.cwd()),
    ansibleLint: env['ANSIBLE_LINT'] ?? 'ansible-lint',
    git: env['GIT'] ?? 'git',
    gitleaks: env['GITLEAKS'] !== undefined && env['GITLEAKS'].trim() !== '' ? env['GITLEAKS'] : null,
    allowFileUrls: argv.includes('--allow-file-urls'),
    annotate: env['GITHUB_ACTIONS'] === 'true',
    env,
    write,
  });
  const plural = (n: number, word: string): string => `${n} ${word}${n === 1 ? '' : 's'}`;
  write(`Linted ${plural(summary.linted, 'playbook')} from ${plural(summary.sources, 'source')}: ${plural(summary.failed, 'failure')}, ${summary.skipped} skipped.`);
  return summary.failed > 0 ? 1 : 0;
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  process.exitCode = main(process.argv.slice(2));
}
