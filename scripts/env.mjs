#!/usr/bin/env node
// Writes one env file per container from the run's secrets (scripts/keys.mjs) and, once
// platform-fake has logged it, the console-internal service account's token:
//
//   node scripts/env.mjs <work-dir> [platform-internal-token]
//
// Each container gets only what it needs: its own private key, the public keys it trusts, the
// platform token. Values are single-quoted so the compose parser keeps JSON as it is.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const work = process.argv[2]
const platformToken = process.argv[3] ?? ''
if (!work) {
  console.error('usage: node scripts/env.mjs <work-dir> [platform-internal-token]')
  process.exit(2)
}
const s = JSON.parse(readFileSync(join(work, 'secrets.json'), 'utf8'))
const platform = { E2E_PLATFORM_INTERNAL_TOKEN: platformToken }

const files = {
  'platform-fake': { E2E_BFF_TRUST_JWKS: s.E2E_BFF_TRUST_JWKS },
  'platform-feed-bridge': { PLATFORM_TOKEN: platformToken },
  opa: { E2E_OPA_BUNDLE_TOKEN: s.E2E_OPA_BUNDLE_TOKEN },
  identity: { E2E_OPA_BUNDLE_TOKEN: s.E2E_OPA_BUNDLE_TOKEN },
  state: { ...platform, E2E_STATE_MASTER_KEY: s.E2E_STATE_MASTER_KEY, E2E_STATE_TRAIL_KEY: s.E2E_STATE_TRAIL_KEY },
  bff: {
    ...platform,
    E2E_BFF_TRUST_KEY: s.E2E_BFF_TRUST_KEY,
    E2E_BFF_SESSION_SECRET: s.E2E_BFF_SESSION_SECRET,
    E2E_INDEXER_CLIENT_JWKS: s.E2E_INDEXER_CLIENT_JWKS,
    E2E_DRIFT_CLIENT_JWKS: s.E2E_DRIFT_CLIENT_JWKS,
    E2E_COST_CLIENT_JWKS: s.E2E_COST_CLIENT_JWKS,
    E2E_HARNESS_CLIENT_JWKS: s.E2E_HARNESS_CLIENT_JWKS,
  },
  indexer: { ...platform, E2E_INDEXER_CLIENT_KEY: s.E2E_INDEXER_CLIENT_KEY },
  drift: { ...platform, E2E_DRIFT_CLIENT_KEY_ID: s.E2E_DRIFT_CLIENT_KEY_ID },
  cost: { ...platform, E2E_COST_CLIENT_KEY: s.E2E_COST_CLIENT_KEY },
}

const dir = join(work, 'env')
mkdirSync(dir, { recursive: true })
for (const [name, vars] of Object.entries(files)) {
  const lines = ['# made by scripts/env.mjs for one e2e run: dev-only values, never committed']
  for (const [k, v] of Object.entries(vars)) {
    if (String(v).includes("'") || String(v).includes('\n')) throw new Error(`${k} cannot be written to an env file`)
    lines.push(`${k}='${v}'`)
  }
  writeFileSync(join(dir, `${name}.env`), lines.join('\n') + '\n', { mode: 0o644 })
}
console.log(`env files written to ${dir}${platformToken ? '' : ' (no platform token yet)'}`)
