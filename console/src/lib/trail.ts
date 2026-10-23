import { sha256Tagged, utf8 } from './sha256'
import type { ResourceState } from './types'

/*
 * The activity trail's shapes and its checks (hub contracts/openapi/console-state-service.yaml,
 * "The trail"; served through the BFF by decision 34). Every event is a link in its resource's
 * hash chain: `hash` is the SHA-256 of its stored form and `previous` the hash of the event before
 * it. Each period of the account's events is sealed in a digest that counts them, hashes all of
 * their hashes into `root`, carries the previous digest's hash and is signed with ES256.
 *
 * The checks here are the ones an auditor runs on an evidence pack away from the console: every
 * event's hash rebuilt from its stored form, every link to the event before, every digest's count,
 * root and link to the digest before, and every signature against the public keys. The mock seals
 * its trail with the same functions, so the two agree by construction and the tests hold both.
 */

export const STATE_EVENT_TYPES = ['ResourceRequested', 'ProvisioningStarted', 'ProvisioningSucceeded', 'ProvisioningFailed', 'UpdateRequested', 'Observed', 'DriftDetected', 'DriftResolved', 'Tagged', 'DeleteRequested', 'Deleted', 'Imported', 'RetryRequested', 'Transferred'] as const
export type StateEventType = (typeof STATE_EVENT_TYPES)[number]

/** One entry of a resource's log: the P-01 envelope with the console's fields in `data`. */
export interface ResourceEvent {
  specVersion: '1.0'
  dataVersion: number
  id: string
  key: string
  seq?: number
  type: StateEventType
  at: string
  aggregate: 'resource'
  aggregateId: string
  actor?: string
  onBehalfOf?: { id: string; name?: string; email?: string }
  context: { accountId: string; region: string; wfrn: string; consoleRequestId?: string }
  traceparent?: string
  data: { version: number; state: ResourceState; resourceType?: string; jobId?: string; [field: string]: unknown }
}

/** An event's place in its resource's chain; both absent on events written before the chain began. */
export interface ChainLink {
  previous?: string
  hash?: string
}

/** `GET /console/v1/activity`: an event with its link. */
export interface TrailEntry extends ResourceEvent {
  chain: ChainLink
}

/** An event as the state service stores it and the evidence pack carries it: the envelope with `previous` and `hash`. */
export type StoredEvent = ResourceEvent & ChainLink

/** `GET /console/v1/r/{type}/{wfrn}/activity:verify`: what the check of one resource's chain found. */
export interface ChainCheck {
  valid: boolean
  entries: number
  chained: number
  brokenAt: number | null
  reason: string | null
  head: string | null
}

export interface TrailDigest {
  id: string
  accountId: string
  region: string
  from: string
  to: string
  count: number
  firstEvent?: string
  lastEvent?: string
  root: string
  previous?: string
  hash: string
  keyId: string
  signature: string
  signedAt: string
}

export interface TrailKey {
  kty: 'EC'
  crv: 'P-256'
  kid: string
  alg?: 'ES256'
  use?: 'sig'
  x: string
  y: string
}

export interface EvidencePack {
  accountId: string
  region: string
  from: string
  to: string
  generatedAt: string
  canonicalization: string
  events: StoredEvent[]
  digests: TrailDigest[]
  keys: TrailKey[]
  verification: { valid: boolean; events: number; digests: number; unsealed: number; problems: { subject: string; reason: string }[] }
  jobs: { jobId: string; evidence: string }[]
}

/** How the bytes that were hashed are rebuilt; the evidence pack carries these words. */
export const CANONICALIZATION =
  'An event is hashed as its stored form without its `hash` field (its `previous` included), written as JSON with every object\'s keys sorted by code point, no whitespace, and numbers and strings as JSON.stringify writes them, then encoded as UTF-8; the hash is `sha256:` and the hex SHA-256 of those bytes. A digest\'s `root` is the SHA-256 of each of its events\' hashes followed by a newline, in time order (`at`, then `<wfrn>#<version>`); its `hash` is the SHA-256 of the same JSON form of its `id`, `accountId`, `region`, `from`, `to`, `count`, `firstEvent`, `lastEvent`, `root` and `previous`; its `signature` is ES256 (r and s, base64url) over the UTF-8 text of its `hash`, by the key `keyId`.'

/** JSON with every object's keys sorted, no whitespace: the form the trail hashes. */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null'
  if (Array.isArray(value)) return `[${value.map((v) => (v === undefined ? 'null' : canonicalJson(v))).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(',')}}`
}

/** The hash of an event's stored form (its `previous` included, its `hash` left out). */
export function eventHash(stored: StoredEvent | (ResourceEvent & { previous?: string })): string {
  const { hash: _hash, ...rest } = stored as StoredEvent
  return sha256Tagged(canonicalJson(rest))
}

/** `<wfrn>#<version>`: how a digest and a problem name an event. */
export function eventRef(e: Pick<ResourceEvent, 'aggregateId' | 'data'>): string {
  return `${e.aggregateId}#${e.data.version}`
}

/** Time order, then `<wfrn>#<version>`: the order a digest's root is built in. */
export function byTrailOrder(a: ResourceEvent, b: ResourceEvent): number {
  return a.at.localeCompare(b.at) || eventRef(a).localeCompare(eventRef(b))
}

export function digestRoot(hashes: string[]): string {
  return sha256Tagged(hashes.map((h) => `${h}\n`).join(''))
}

export function digestHash(d: Pick<TrailDigest, 'id' | 'accountId' | 'region' | 'from' | 'to' | 'count' | 'firstEvent' | 'lastEvent' | 'root' | 'previous'>): string {
  const { id, accountId, region, from, to, count, firstEvent, lastEvent, root, previous } = d
  return sha256Tagged(canonicalJson({ id, accountId, region, from, to, count, firstEvent, lastEvent, root, previous }))
}

/** Links a resource's events, oldest first: each gets the hash of the one before as `previous` and its own `hash`. */
export function chainEvents(events: ResourceEvent[]): StoredEvent[] {
  let previous: string | undefined
  return events.map((e) => {
    const linked: ResourceEvent & { previous?: string } = previous ? { ...e, previous } : { ...e }
    const hash = eventHash(linked)
    previous = hash
    return { ...linked, hash }
  })
}

/** The stored form as the trail lists it: the envelope, with the link under `chain`. */
export function toTrailEntry(stored: StoredEvent): TrailEntry {
  const { previous, hash, ...event } = stored
  return { ...event, chain: { ...(previous ? { previous } : {}), ...(hash ? { hash } : {}) } }
}

/* ----------------------------------------------------------------------------
   Checking a resource's chain, and a whole evidence pack
   ------------------------------------------------------------------------- */

export type ProblemKind = 'event.altered' | 'event.unlinked' | 'event.missing' | 'event.unchained' | 'digest.count' | 'digest.root' | 'digest.altered' | 'digest.unlinked' | 'digest.signature' | 'digest.key'

export interface ChainProblem {
  kind: ProblemKind
  /** the digest id or the event's `<wfrn>#<version>` */
  subject: string
  wfrn?: string
  version?: number
  at?: string
  type?: string
  digestId?: string
}

/** A resource's log checked as `events:verify` does: no version missing, each hash rebuilt, each link to the event before. */
export function checkChain(events: StoredEvent[]): ChainCheck {
  const sorted = [...events].sort((a, b) => a.data.version - b.data.version)
  let head: string | null = null
  let chained = 0
  let seenChained = false
  for (let i = 0; i < sorted.length; i++) {
    const e = sorted[i]
    const fail = (reason: string): ChainCheck => ({ valid: false, entries: sorted.length, chained, brokenAt: e.data.version, reason, head })
    if (e.data.version !== i + 1) return fail(`version ${i + 1} is missing`)
    if (!e.hash) {
      if (seenChained) return fail('an event after the chain began has no hash')
      continue
    }
    if (eventHash(e) !== e.hash) return fail('its stored form does not match its hash')
    if (seenChained && e.previous !== head) return fail('it does not link to the event before it')
    seenChained = true
    chained++
    head = e.hash
  }
  return { valid: true, entries: sorted.length, chained, brokenAt: null, reason: null, head }
}

export interface PackCheck {
  valid: boolean
  events: number
  resources: number
  digests: number
  /** events of periods no digest covers yet */
  unsealed: number
  signatures: { checked: number; valid: number }
  problems: ChainProblem[]
}

function problemOfEvent(kind: ProblemKind, e: StoredEvent): ChainProblem {
  return { kind, subject: eventRef(e), wfrn: e.aggregateId, version: e.data.version, at: e.at, type: e.type }
}

/** Everything in a pack that can be checked without the signatures: hashes, links, versions, counts and roots. */
export function checkPackHashes(pack: Pick<EvidencePack, 'events' | 'digests'>): Omit<PackCheck, 'signatures'> {
  const problems: ChainProblem[] = []
  const byResource = new Map<string, StoredEvent[]>()
  for (const e of pack.events) byResource.set(e.aggregateId, [...(byResource.get(e.aggregateId) ?? []), e])
  for (const events of byResource.values()) {
    const sorted = [...events].sort((a, b) => a.data.version - b.data.version)
    for (let i = 0; i < sorted.length; i++) {
      const e = sorted[i]
      const before = sorted[i - 1]
      if (before && e.data.version !== before.data.version + 1) problems.push({ ...problemOfEvent('event.missing', e), version: before.data.version + 1, subject: `${e.aggregateId}#${before.data.version + 1}` })
      if (!e.hash) {
        if (before?.hash) problems.push(problemOfEvent('event.unchained', e))
        continue
      }
      if (eventHash(e) !== e.hash) problems.push(problemOfEvent('event.altered', e))
      // the window's first event of a resource links to one outside it: that link is the service's to check
      else if (before?.hash && e.previous !== before.hash) problems.push(problemOfEvent('event.unlinked', e))
    }
  }
  const ordered = [...pack.events].sort(byTrailOrder)
  const digests = [...pack.digests].sort((a, b) => a.from.localeCompare(b.from))
  let covered = 0
  for (let i = 0; i < digests.length; i++) {
    const d = digests[i]
    const inside = ordered.filter((e) => e.context.accountId === d.accountId && e.at >= d.from && e.at < d.to)
    covered += inside.length
    const sealed = { kind: 'digest.count' as const, subject: d.id, digestId: d.id, at: d.from }
    if (inside.length !== d.count) problems.push(sealed)
    else if (digestRoot(inside.map((e) => e.hash ?? '')) !== d.root || (inside.length > 0 && (eventRef(inside[0]) !== d.firstEvent || eventRef(inside[inside.length - 1]) !== d.lastEvent))) problems.push({ ...sealed, kind: 'digest.root' })
    if (digestHash(d) !== d.hash) problems.push({ ...sealed, kind: 'digest.altered' })
    const before = digests[i - 1]
    if (before && d.previous !== before.hash) problems.push({ ...sealed, kind: 'digest.unlinked' })
  }
  return { valid: problems.length === 0, events: pack.events.length, resources: byResource.size, digests: digests.length, unsealed: Math.max(0, pack.events.length - covered), problems }
}

function fromBase64url(s: string): Uint8Array<ArrayBuffer> {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  return Uint8Array.from(bin, (c) => c.charCodeAt(0))
}

/** Each digest's ES256 signature checked with Web Crypto against the key it names. */
export async function checkSignatures(digests: TrailDigest[], keys: TrailKey[]): Promise<{ checked: number; valid: number; problems: ChainProblem[] }> {
  const subtle = typeof crypto !== 'undefined' ? crypto.subtle : undefined
  const problems: ChainProblem[] = []
  if (!subtle) return { checked: 0, valid: 0, problems }
  const imported = new Map<string, CryptoKey | null>()
  let valid = 0
  for (const d of digests) {
    if (!imported.has(d.keyId)) {
      const jwk = keys.find((k) => k.kid === d.keyId)
      imported.set(d.keyId, jwk ? await subtle.importKey('jwk', { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']).catch(() => null) : null)
    }
    const key = imported.get(d.keyId)
    if (!key) {
      problems.push({ kind: 'digest.key', subject: d.id, digestId: d.id, at: d.from })
      continue
    }
    const ok = await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, fromBase64url(d.signature), utf8(d.hash)).catch(() => false)
    if (ok) valid++
    else problems.push({ kind: 'digest.signature', subject: d.id, digestId: d.id, at: d.from })
  }
  return { checked: digests.length, valid, problems }
}

/** The whole pack, checked as an auditor would: hashes, links, digests and signatures. */
export async function checkPack(pack: EvidencePack): Promise<PackCheck> {
  const hashes = checkPackHashes(pack)
  const signatures = await checkSignatures(pack.digests, pack.keys)
  const problems = [...hashes.problems, ...signatures.problems]
  return { ...hashes, valid: problems.length === 0, problems, signatures: { checked: signatures.checked, valid: signatures.valid } }
}

/* ----------------------------------------------------------------------------
   The words
   ------------------------------------------------------------------------- */

const PROBLEM_WORDS: Record<ProblemKind, string> = {
  'event.altered': 'its contents no longer match the hash written with it, so it was changed after it was recorded',
  'event.unlinked': 'it does not point to the event before it, so an event was put in or taken out',
  'event.missing': 'this version is missing from the trail, so an event was taken out',
  'event.unchained': 'it has no hash although the events before it do',
  'digest.count': 'the sealed hour does not hold the number of events its seal counts',
  'digest.root': 'the events of the sealed period do not add up to the root its seal carries',
  'digest.altered': 'the seal itself was changed after it was signed',
  'digest.unlinked': 'the seal does not point to the seal before it',
  'digest.signature': 'its signature does not match the published key',
  'digest.key': 'it names a signing key the trail does not publish',
}

/** What a problem means, in a sentence a person can act on. */
export function problemWords(p: ChainProblem): string {
  return PROBLEM_WORDS[p.kind]
}

/** The period a digest seals, in words: "the day of 12 Oct 2026" or "12 Oct 2026, 09:00 to 10:00". */
export function periodWords(d: Pick<TrailDigest, 'from' | 'to'>): string {
  const from = new Date(d.from)
  const to = new Date(d.to)
  const day = (x: Date) => x.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
  if (to.getTime() - from.getTime() === 24 * 3600_000) return `${day(from)} (UTC)`
  const time = (x: Date) => x.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' })
  return `${day(from)}, ${time(from)} to ${time(to)} UTC`
}
