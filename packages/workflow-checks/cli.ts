/**
 * The config-as-code check CI runs: every workflow under workflows/, judged with the providers
 * and resource types of the same repository as the catalog. Exit 1 on any error; warnings are
 * printed but do not fail the run. `scripts/check-workflows.mjs` at the repository root runs it.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parse as parseYaml } from 'yaml';
import {
  buildContext,
  check,
  ContentParseError,
  kindOf,
  parseProvider,
  parseResourceType,
  PROVIDER_KIND,
  providerName,
  RESOURCE_TYPE_KIND,
  resourceTypeName,
  WORKFLOW_KIND,
  type CheckContext,
  type Problem,
  type ProviderDocument,
} from './src/index.ts';

/** The folders of a content repository that hold config as code. */
export const CONTENT_FOLDERS: readonly string[] = ['workflows', 'providers', 'resource-types'];

export interface ContentFile {
  /** The path relative to the root, with forward slashes. */
  path: string;
  /** The `kind` in the file, or null (also when the YAML is broken). */
  kind: string | null;
  doc: unknown;
  /** The YAML syntax error, when the file could not be read. */
  error: string | null;
}

export interface FileReport {
  path: string;
  kind: string | null;
  problems: Problem[];
}

export interface CheckReport {
  root: string;
  files: FileReport[];
  workflows: number;
  providers: number;
  resourceTypes: number;
  errors: number;
  warnings: number;
}

function listYaml(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      out.push(...listYaml(full));
    } else if (name.endsWith('.yaml') || name.endsWith('.yml')) {
      out.push(full);
    }
  }
  return out;
}

/** Every YAML file under the content folders, read and parsed; the kind in the file decides, not the folder. */
export function scan(root: string): ContentFile[] {
  const files: ContentFile[] = [];
  for (const folder of CONTENT_FOLDERS) {
    const dir = join(root, folder);
    let isDir: boolean;
    try {
      isDir = statSync(dir).isDirectory();
    } catch {
      isDir = false;
    }
    if (!isDir) {
      continue;
    }
    for (const full of listYaml(dir)) {
      const path = relative(root, full).split('\\').join('/');
      try {
        const doc: unknown = parseYaml(readFileSync(full, 'utf8'), { uniqueKeys: false }) ?? {};
        files.push({ path, kind: kindOf(doc), doc, error: null });
      } catch (e) {
        const message = e instanceof Error ? e.message : String(e);
        files.push({ path, kind: null, doc: null, error: `YAML syntax: ${message.split('\n')[0] ?? message}` });
      }
    }
  }
  return files;
}

function invalid(message: string): Problem {
  return { level: 'error', code: 'yaml.invalid', message };
}

/** Build the catalog from the providers, workflows and resource types of the repository and check every workflow. */
export function checkRepo(root: string): CheckReport {
  const files = scan(root);
  const reports: FileReport[] = [];
  const providers: ProviderDocument[] = [];
  const workflowNames = new Set<string>();
  const resourceTypes = new Set<string>();
  let providerCount = 0;
  let resourceTypeCount = 0;
  let workflowCount = 0;

  for (const f of files) {
    if (f.error !== null) {
      reports.push({ path: f.path, kind: null, problems: [invalid(f.error)] });
      continue;
    }
    try {
      if (f.kind === PROVIDER_KIND) {
        const p = parseProvider(f.doc);
        providers.push(p);
        providerCount++;
        if (providerName(p).length === 0) {
          reports.push({ path: f.path, kind: f.kind, problems: [invalid('metadata.name is required')] });
        }
      } else if (f.kind === RESOURCE_TYPE_KIND) {
        resourceTypes.add(resourceTypeName(parseResourceType(f.doc)));
        resourceTypeCount++;
      } else if (f.kind === WORKFLOW_KIND) {
        const md = (f.doc as { metadata?: { name?: unknown } }).metadata;
        if (typeof md?.name === 'string' && md.name.trim().length > 0) {
          workflowNames.add(md.name);
        }
      }
    } catch (e) {
      if (e instanceof ContentParseError) {
        reports.push({ path: f.path, kind: f.kind, problems: [invalid(e.message)] });
      } else {
        throw e;
      }
    }
  }

  const ctx: CheckContext = buildContext(providers, workflowNames, resourceTypes);
  for (const f of files) {
    if (f.error === null && f.kind === WORKFLOW_KIND) {
      workflowCount++;
      reports.push({ path: f.path, kind: f.kind, problems: check(f.doc, ctx) });
    }
  }
  reports.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

  let errors = 0;
  let warnings = 0;
  for (const r of reports) {
    for (const p of r.problems) {
      if (p.level === 'error') {
        errors++;
      } else {
        warnings++;
      }
    }
  }
  return { root, files: reports, workflows: workflowCount, providers: providerCount, resourceTypes: resourceTypeCount, errors, warnings };
}

/** `path: level code @step.field - message` */
export function formatProblem(path: string, p: Problem): string {
  const where = p.step !== undefined && p.field !== undefined ? ` @${p.step}.${p.field}` : p.step !== undefined ? ` @${p.step}` : p.field !== undefined ? ` @${p.field}` : '';
  const fix = p.fix !== undefined ? ` [${p.fix.label}]` : '';
  return `${path}: ${p.level} ${p.code}${where} - ${p.message}${fix}`;
}

/** The report as lines: the problems grouped by file, then a summary. */
export function formatReport(report: CheckReport): string[] {
  const lines: string[] = [];
  for (const f of report.files) {
    for (const p of f.problems) {
      lines.push(formatProblem(f.path, p));
    }
  }
  if (lines.length > 0) {
    lines.push('');
  }
  const plural = (n: number, word: string): string => `${n} ${word}${n === 1 ? '' : 's'}`;
  lines.push(
    `Checked ${plural(report.workflows, 'workflow')} against ${plural(report.providers, 'provider')} and ${plural(report.resourceTypes, 'resource type')}: ${plural(report.errors, 'error')}, ${plural(report.warnings, 'warning')}.`,
  );
  return lines;
}

/** Run the check over a repository root (the first argument, default the working directory); the exit code. */
export function main(argv: readonly string[], write: (line: string) => void = (line) => console.log(line)): number {
  const args = argv.filter((a) => !a.startsWith('-'));
  if (argv.includes('-h') || argv.includes('--help')) {
    write('Usage: check-workflows [root]');
    write('Checks every kind: Workflow file under <root>/workflows with the providers and resource types of <root> as the catalog.');
    return 0;
  }
  const root = resolve(args[0] ?? process.cwd());
  const report = checkRepo(root);
  for (const line of formatReport(report)) {
    write(line);
  }
  return report.errors > 0 ? 1 : 0;
}

const entry = process.argv[1];
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  process.exitCode = main(process.argv.slice(2));
}
