#!/usr/bin/env node
/**
 * The ansible-lint pass over every pinned playbook: `npm run lint:playbooks [root]` fetches each
 * git playbook source the content of <root> (default: this repository) pins, at its commit, and
 * runs ansible-lint on its playbooks; sources it cannot fetch are skipped with a notice. It runs
 * packages/workflow-checks/lint-playbooks.ts the way check-workflows.mjs runs the checks.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cli = fileURLToPath(new URL('../packages/workflow-checks/lint-playbooks.ts', import.meta.url));
const flags = ['--disable-warning=ExperimentalWarning'];
if (!process.features.typescript) {
  // Node 22 before 22.18 strips types only when asked
  flags.push('--experimental-strip-types');
}
const result = spawnSync(process.execPath, [...flags, cli, ...process.argv.slice(2)], { stdio: 'inherit' });
if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}
process.exit(result.status ?? 1);
