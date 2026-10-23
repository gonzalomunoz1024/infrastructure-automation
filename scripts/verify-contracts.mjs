#!/usr/bin/env node
// Checks that every contract copied from the hub still matches the sha256 recorded in contracts.lock.
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(new URL('..', import.meta.url).pathname)
const lock = JSON.parse(readFileSync(resolve(root, 'contracts.lock'), 'utf8'))
let failed = 0
for (const [file, entry] of Object.entries(lock.files)) {
  const actual = createHash('sha256').update(readFileSync(resolve(root, file))).digest('hex')
  if (actual !== entry.sha256) {
    failed++
    console.error(`${file}: sha256 ${actual} does not match contracts.lock (${entry.sha256}); copied from ${lock.hub} ${entry.source} at ${lock.commit}`)
  } else {
    console.log(`${file}: matches ${lock.hub} ${entry.source} at ${lock.commit.slice(0, 7)}`)
  }
}
if (failed) process.exit(1)
