import type { JsonWebKey } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/*
 * Where the e2e stack answers (docker-compose.yml publishes every port on 127.0.0.1) and what
 * scripts/up.sh left in .work/ for the run: the dev keys, the platform token, the seed.
 */

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
export const WORK = process.env.E2E_WORK ?? join(ROOT, '.work')
export const JOURNEY_WORK = join(WORK, 'journey')

/** The console as the browser reaches it: console-web's nginx, which proxies /auth and /console to the BFF. */
export const CONSOLE_URL = process.env.E2E_CONSOLE_URL ?? 'http://localhost:3000'

/** The console-web commit with the cost panel (services.lock: console-web-cost), for step 5. */
export const COST_WEB_URL = process.env.E2E_COST_WEB_URL ?? 'http://localhost:3001'

export const URLS = {
  bff: 'http://127.0.0.1:18081',
  state: 'http://127.0.0.1:18082',
  identity: 'http://127.0.0.1:18083',
  drift: 'http://127.0.0.1:18084',
  indexer: 'http://127.0.0.1:18085',
  cost: 'http://127.0.0.1:18086',
  platform: 'http://127.0.0.1:18090',
  opa: 'http://127.0.0.1:8181',
} as const

/** The people of the run, as Dex knows them (config/dex/config.yaml). */
export interface Person {
  key: 'gonzalo' | 'dana'
  email: string
  name: string
}

export const GONZALO: Person = { key: 'gonzalo', email: 'gonzalo@example.com', name: 'Gonzalo Munoz' }
export const DANA: Person = { key: 'dana', email: 'dana@example.com', name: 'Dana Reyes' }

/** DEV ONLY: Dex's static password for both people; the stack lives for one run. */
export const DEV_PASSWORD = 'console-e2e-dev-only'

/** The account global-setup.ts seeds, and who is in it. */
export interface Seed {
  accountId: string
  accountName: string
  owningTeam: string
  costCentre: string
  approverGroup: string
  danaUserId: string
}

const seedFile = join(JOURNEY_WORK, 'seed.json')

export function writeSeed(seed: Seed): void {
  mkdirSync(JOURNEY_WORK, { recursive: true })
  writeFileSync(seedFile, JSON.stringify(seed, null, 2) + '\n')
}

export function readSeed(): Seed {
  if (!existsSync(seedFile)) throw new Error(`${seedFile} is missing: global-setup.ts seeds the account first`)
  return JSON.parse(readFileSync(seedFile, 'utf8')) as Seed
}

/** A person's signed-in browser state, saved after their sign-in through Dex. */
export function storageStateOf(person: Person): string {
  mkdirSync(JOURNEY_WORK, { recursive: true })
  return join(JOURNEY_WORK, `${person.key}.storage.json`)
}

/** The console-internal service account's token, which platform-fake logged at boot (scripts/up.sh). */
export function platformToken(): string {
  const file = join(WORK, 'platform-internal-token')
  if (!existsSync(file)) throw new Error(`${file} is missing: scripts/up.sh writes it`)
  return readFileSync(file, 'utf8').trim()
}

/** The e2e harness's own client key, registered with the BFF as console-e2e (scripts/keys.mjs). */
export function harnessKey(): JsonWebKey & { kid: string } {
  return JSON.parse(readFileSync(join(WORK, 'keys', 'console-e2e.private.jwk.json'), 'utf8'))
}
