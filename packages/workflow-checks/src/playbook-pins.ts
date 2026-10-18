/**
 * The git playbooks the content pins, for the ansible-lint pass: each
 * `playbook: { git: { repo, path }, commit }` in a workflow, and each action `playbook` of an
 * ansible provider whose `source` is `{ git: { repo }, commit }`, grouped by repository and
 * commit. What cannot be linted that way is listed with the reason: a git playbook without its
 * full commit (publishing pins it), an upload (checked when it was uploaded), a remote that is not
 * https, a path that leaves the repository.
 */

export interface PlaybookPin {
  /** The repository as the content writes it, e.g. `github.com/acme/ops`. */
  repo: string;
  /** What git fetches: `https://host/path`. */
  url: string;
  /** The full commit id. */
  commit: string;
  /** The playbooks to lint, relative to the repository root, sorted. */
  playbooks: string[];
  /** Where the content pins it: `<file> @<step or action>`, sorted. */
  from: string[];
}

export interface PlaybookSkip {
  from: string;
  reason: string;
}

export interface PlaybookPins {
  pins: PlaybookPin[];
  skipped: PlaybookSkip[];
}

/** A content file as the CLI reads it. */
export interface PinSource {
  path: string;
  kind: string | null;
  doc: unknown;
}

const COMMIT = /^[0-9a-f]{40}$/;

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function text(v: unknown): string | null {
  return typeof v === 'string' && v.trim().length > 0 ? v.trim() : null;
}

/**
 * The URL git fetches for a repository: `https://` as written, `host/path` as https; anything
 * else (ssh, `git@host:path`, plain http, a local path) is null. `file://` only when allowed (tests).
 */
export function remoteUrl(repo: string, allowFileUrls = false): string | null {
  const r = repo.trim();
  if (/^https:\/\/[^/@\s]+\/\S+$/i.test(r)) {
    return r;
  }
  if (allowFileUrls && /^file:\/\/\/\S+$/.test(r)) {
    return r;
  }
  if (r.includes('://') || r.includes('@')) {
    return null;
  }
  // host/path or host:port/path; a scp-like host:path does not match
  return /^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(:\d+)?\/\S+$/.test(r) ? `https://${r}` : null;
}

/** A path inside the repository: relative, forward slashes, no `..`. */
function insideRepo(path: string): boolean {
  return !path.startsWith('/') && !path.includes('\\') && !path.split('/').includes('..');
}

export function playbookPins(files: readonly PinSource[], options: { allowFileUrls?: boolean } = {}): PlaybookPins {
  const groups = new Map<string, { repo: string; url: string; commit: string; playbooks: Set<string>; from: Set<string> }>();
  const skipped: PlaybookSkip[] = [];

  const add = (repo: string, commit: unknown, path: string, from: string): void => {
    const sha = text(commit);
    if (sha === null) {
      skipped.push({ from, reason: `${repo} ${path} is not pinned to a commit (publishing pins it)` });
      return;
    }
    if (!COMMIT.test(sha)) {
      skipped.push({ from, reason: `${repo} ${path}: '${sha}' is not a full commit id` });
      return;
    }
    const url = remoteUrl(repo, options.allowFileUrls === true);
    if (url === null) {
      skipped.push({ from, reason: `${repo} is not an https remote` });
      return;
    }
    if (!insideRepo(path)) {
      skipped.push({ from, reason: `${path} leaves the repository` });
      return;
    }
    const key = `${url}@${sha}`;
    let g = groups.get(key);
    if (g === undefined) {
      g = { repo, url, commit: sha, playbooks: new Set(), from: new Set() };
      groups.set(key, g);
    }
    g.playbooks.add(path);
    g.from.add(from);
  };

  const walk = (node: unknown, file: string, step: string | null): void => {
    if (Array.isArray(node)) {
      node.forEach((n) => walk(n, file, step));
      return;
    }
    if (!isObject(node)) {
      return;
    }
    const playbook = node['playbook'];
    if (isObject(playbook)) {
      const from = step === null ? file : `${file} @${step}`;
      const git = playbook['git'];
      if (isObject(git)) {
        const repo = text(git['repo']);
        const path = text(git['path']);
        if (repo !== null && path !== null) {
          add(repo, playbook['commit'], path, from);
        }
      } else if (isObject(playbook['upload'])) {
        // POST /playbooks:upload unpacks, scans for secrets and lints an upload before it is kept; its sha256 pins it
        skipped.push({ from, reason: 'an uploaded playbook is checked when it is uploaded' });
      }
    }
    for (const [key, value] of Object.entries(node)) {
      // a step is `<id>: { call | script | ... }`; its id names where a pin comes from
      walk(value, file, isObject(value) && 'call' in value ? key : step);
    }
  };

  for (const f of files) {
    if (f.kind === 'Workflow') {
      walk(f.doc, f.path, null);
    } else if (f.kind === 'Provider' && isObject(f.doc)) {
      const spec = f.doc['spec'];
      if (!isObject(spec) || spec['type'] !== 'ansible' || !isObject(spec['source'])) {
        continue;
      }
      const source = spec['source'];
      const git = source['git'];
      const repo = isObject(git) ? text(git['repo']) : null;
      if (repo === null) {
        continue;
      }
      const actions = Array.isArray(spec['actions']) ? spec['actions'] : [];
      for (const a of actions) {
        const path = isObject(a) ? text(a['playbook']) : null;
        if (path !== null) {
          add(repo, source['commit'], path, `${f.path} @${text((a as Record<string, unknown>)['id']) ?? path}`);
        }
      }
    }
  }

  const pins = [...groups.values()]
    .map((g) => ({ repo: g.repo, url: g.url, commit: g.commit, playbooks: [...g.playbooks].sort(), from: [...g.from].sort() }))
    .sort((a, b) => (a.url === b.url ? a.commit.localeCompare(b.commit) : a.url.localeCompare(b.url)));
  return { pins, skipped };
}
