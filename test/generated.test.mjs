// The committed generated files are what the pinned contract gives: change the contract, run
// `npm run generate`, commit both.

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { generate, OUTPUTS } from '../scripts/generate.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('the generated files are current with the contract', async () => {
  const outputs = await generate();
  assert.deepEqual([...outputs.keys()].sort(), Object.values(OUTPUTS).sort());
  for (const [file, content] of outputs) {
    const committed = await readFile(path.join(ROOT, file), 'utf8');
    assert.ok(committed === content, `${file} is stale: run \`npm run generate\` and commit it`);
  }
});
