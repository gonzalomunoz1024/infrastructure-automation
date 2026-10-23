#!/usr/bin/env node
// Dev-only keys and secrets for one e2e run, made fresh every time and never committed.
//
//   node scripts/keys.mjs <out-dir>
//
// Writes into <out-dir>:
//   keys/<name>.private.jwk.json   an EC P-256 private JWK with a kid (BFF trust key, client keys)
//   keys/<name>.public.jwk.json    its public half
//   keys/drift.pk8.pem             the drift service's client key as PKCS#8 PEM (its key format)
//   secrets.json                   every value the compose services read, by name
//
// Hub decision 23: the BFF is the only issuer of internal JWTs; every other service proves who it
// is with its own registered key. Here each service gets its own key and the BFF registers the
// public halves; nothing is shared and nothing outlives the run.
import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const out = process.argv[2]
if (!out) {
  console.error('usage: node scripts/keys.mjs <out-dir>')
  process.exit(2)
}
const keysDir = join(out, 'keys')
mkdirSync(keysDir, { recursive: true })

const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)

function ecKey(name) {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const kid = `e2e-${name}-${stamp}`
  const priv = { ...privateKey.export({ format: 'jwk' }), kid, use: 'sig', alg: 'ES256' }
  const pub = { ...publicKey.export({ format: 'jwk' }), kid, use: 'sig', alg: 'ES256' }
  writeFileSync(join(keysDir, `${name}.private.jwk.json`), JSON.stringify(priv) + '\n', { mode: 0o644 })
  writeFileSync(join(keysDir, `${name}.public.jwk.json`), JSON.stringify(pub) + '\n', { mode: 0o644 })
  return { kid, priv, pub, pem: privateKey.export({ type: 'pkcs8', format: 'pem' }) }
}

// The BFF's trust key: signs internal JWTs and the P-02 on-behalf-of assertions the platform checks.
const bff = ecKey('bff-trust')
// One client key per console service that asks the BFF for service tokens, plus the e2e harness's.
const indexer = ecKey('console-search-indexer')
const drift = ecKey('console-drift-service')
const cost = ecKey('console-cost-service')
const e2e = ecKey('console-e2e')
// The state service's trail digests are signed with their own key.
const trail = ecKey('state-trail')
writeFileSync(join(keysDir, 'drift.pk8.pem'), drift.pem, { mode: 0o644 })

const secrets = {
  E2E_BFF_TRUST_KEY: JSON.stringify(bff.priv),
  E2E_BFF_TRUST_JWKS: JSON.stringify({ keys: [bff.pub] }),
  E2E_BFF_SESSION_SECRET: randomBytes(32).toString('base64'),
  E2E_INDEXER_CLIENT_KEY: JSON.stringify(indexer.priv),
  E2E_INDEXER_CLIENT_JWKS: JSON.stringify({ keys: [indexer.pub] }),
  E2E_COST_CLIENT_KEY: JSON.stringify(cost.priv),
  E2E_COST_CLIENT_JWKS: JSON.stringify({ keys: [cost.pub] }),
  E2E_DRIFT_CLIENT_KEY_ID: drift.kid,
  E2E_DRIFT_CLIENT_JWKS: JSON.stringify({ keys: [drift.pub] }),
  E2E_HARNESS_CLIENT_KEY: JSON.stringify(e2e.priv),
  E2E_HARNESS_CLIENT_JWKS: JSON.stringify({ keys: [e2e.pub] }),
  E2E_STATE_MASTER_KEY: randomBytes(32).toString('base64'),
  E2E_STATE_TRAIL_KEY: JSON.stringify(trail.priv),
  E2E_OPA_BUNDLE_TOKEN: randomBytes(24).toString('hex'),
}
writeFileSync(join(out, 'secrets.json'), JSON.stringify(secrets, null, 2) + '\n', { mode: 0o644 })
console.log(`dev keys written to ${keysDir} (kids end in ${stamp})`)
