// The contract under openapi/ is the hub's file that contracts.lock pins, unchanged: a copy that
// drifts from the recorded sha256 fails here (and in the Java build's ContractsLockTest).

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** The hub's contracts/<dir>/<file> is <dir>/<file> here. */
export function localCopy(hubPath) {
  return hubPath.replace(/^contracts\//, '');
}

test('every pinned contract is copied unchanged', async () => {
  const lock = await readFile(path.join(ROOT, 'contracts.lock'), 'utf8');
  assert.match(lock, /^hub: https:\/\/github\.com\/gonzalomunoz1024\/infrastructure-automation$/m);
  assert.match(lock, /^branch: \S+/m);
  assert.match(lock, /^commit: [0-9a-f]{40}$/m, 'the lock names the hub commit in full');
  const pinned = [...lock.matchAll(/^\s+(contracts\/\S+):\s*sha256:([0-9a-f]{64})\s*$/gm)];
  assert.equal(pinned.length, 1, 'the SDKs pin one contract, the BFF\'s');
  for (const [, hubPath, expected] of pinned) {
    const copy = path.join(ROOT, localCopy(hubPath));
    const actual = createHash('sha256').update(await readFile(copy)).digest('hex');
    assert.equal(actual, expected, `${localCopy(hubPath)} differs from ${hubPath} at the pinned hub commit`);
  }
});
