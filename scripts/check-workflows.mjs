#!/usr/bin/env node
/**
 * The config-as-code check: `npm run check:workflows [root]` checks every workflow under
 * <root>/workflows (default: this repository) with the providers and resource types of the same
 * root as the catalog. It runs packages/workflow-checks/cli.ts, TypeScript that Node 22 strips
 * the types from itself, so no build step is needed. Exit 1 on any error; warnings only print.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cli = fileURLToPath(new URL('../packages/workflow-checks/cli.ts', import.meta.url));
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
