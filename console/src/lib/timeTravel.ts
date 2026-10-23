import type { ResourceEvent } from './trail'
import type { ResourceState } from './types'

/*
 * Time travel (WP 9.8): a resource rebuilt from its events as it stood at any moment, and what
 * changed between two moments. The state service answers the same rebuild for `?at=` from its log;
 * the console replays the events it reads, so a slider can move through them without a call per
 * step, and the mock's `?at=` and revert plan use these same functions, so all three agree.
 */

export interface Snapshot {
  version: number
  /** when the resource reached this version */
  at: string
  state: ResourceState
  name?: string
  /** what it was asked to be */
  attributes: Record<string, unknown>
  /** the fields an update had asked for and not yet applied */
  changes?: Record<string, unknown>
  /** what the provider last reported */
  observed?: Record<string, unknown>
  observedAt?: string
  observedStatus?: string
  tags: Record<string, string>
  drift?: { findingId: string; fields: { path: string; expected?: unknown; observed?: unknown }[] }
  /** the job of the last event that named one */
  jobId?: string
}

const record = (v: unknown): Record<string, unknown> | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined)

/** One event applied to the snapshot before it (none before the first). */
export function applyEvent(before: Snapshot | null, e: ResourceEvent): Snapshot {
  const d = e.data
  const s: Snapshot = before ? { ...before, attributes: { ...before.attributes }, tags: { ...before.tags } } : { version: 0, at: e.at, state: d.state, attributes: {}, tags: {} }
  s.version = d.version
  s.at = e.at
  s.state = d.state
  if (d.jobId) s.jobId = d.jobId
  switch (e.type) {
    case 'ResourceRequested':
      s.attributes = { ...(record(d.input) ?? {}) }
      if (typeof d.name === 'string') s.name = d.name
      break
    case 'Imported':
      s.attributes = { ...(record(d.attributes) ?? {}) }
      if (typeof d.name === 'string') s.name = d.name
      break
    case 'UpdateRequested':
      s.changes = { ...(record(d.changes) ?? {}) }
      break
    case 'ProvisioningSucceeded':
      s.attributes = record(d.attributes) ? { ...record(d.attributes) } : { ...s.attributes, ...(s.changes ?? {}) }
      s.changes = undefined
      break
    case 'ProvisioningFailed':
      s.changes = undefined
      break
    case 'Observed':
      s.observed = record(d.observed) ? { ...record(d.observed) } : undefined
      s.observedAt = typeof d.observedAt === 'string' ? d.observedAt : e.at
      s.observedStatus = typeof d.status === 'string' ? d.status : undefined
      break
    case 'DriftDetected':
      s.drift = { findingId: String(d.findingId ?? ''), fields: Array.isArray(d.fields) ? (d.fields as NonNullable<Snapshot['drift']>['fields']) : [] }
      break
    case 'DriftResolved':
      s.drift = undefined
      break
    case 'Tagged': {
      const set = record(d.set) ?? {}
      for (const [k, v] of Object.entries(set)) if (typeof v === 'string') s.tags[k] = v
      if (Array.isArray(d.removed)) for (const k of d.removed) if (typeof k === 'string') delete s.tags[k]
      break
    }
    default:
      break
  }
  return s
}

/** The resource as it stood at `at` (an instant, inclusive), from its events: null before it existed. */
export function replayAt(events: ResourceEvent[], at: string | number): Snapshot | null {
  const limit = typeof at === 'number' ? at : Date.parse(at)
  let s: Snapshot | null = null
  for (const e of [...events].sort((a, b) => a.data.version - b.data.version)) {
    if (Date.parse(e.at) > limit) break
    s = applyEvent(s, e)
  }
  return s
}

/** The resource as it stood at a version. */
export function replayTo(events: ResourceEvent[], version: number): Snapshot | null {
  let s: Snapshot | null = null
  for (const e of [...events].sort((a, b) => a.data.version - b.data.version)) {
    if (e.data.version > version) break
    s = applyEvent(s, e)
  }
  return s
}

/* ----------------------------------------------------------------------------
   What changed between two points
   ------------------------------------------------------------------------- */

export type DiffSection = 'state' | 'desired' | 'observed' | 'tags'

export interface DiffRow {
  section: DiffSection
  /** a JSON pointer into the section, `/cpu`; empty for the state */
  path: string
  before: unknown
  after: unknown
}

const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)

function leaves(value: unknown, path = ''): Map<string, unknown> {
  const out = new Map<string, unknown>()
  const obj = record(value)
  if (!obj || Object.keys(obj).length === 0) {
    if (path) out.set(path, value)
    return out
  }
  for (const [k, v] of Object.entries(obj)) for (const [p, leaf] of leaves(v, `${path}/${k.replace(/~/g, '~0').replace(/\//g, '~1')}`)) out.set(p, leaf)
  return out
}

function diffSection(section: DiffSection, before: unknown, after: unknown): DiffRow[] {
  const a = leaves(before ?? {})
  const b = leaves(after ?? {})
  const paths = [...new Set([...a.keys(), ...b.keys()])].sort()
  return paths.filter((p) => !same(a.get(p), b.get(p))).map((p) => ({ section, path: p, before: a.get(p), after: b.get(p) }))
}

/** Every leaf that differs between two snapshots: the state, what was asked for, what the provider reported, the tags. */
export function diffSnapshots(before: Snapshot | null, after: Snapshot | null): DiffRow[] {
  const rows: DiffRow[] = []
  if ((before?.state ?? null) !== (after?.state ?? null)) rows.push({ section: 'state', path: '', before: before?.state, after: after?.state })
  rows.push(...diffSection('desired', before?.attributes, after?.attributes))
  rows.push(...diffSection('observed', before?.observed, after?.observed))
  rows.push(...diffSection('tags', before?.tags, after?.tags))
  return rows
}

/** The JSON merge patch (RFC 7386) that turns `now` into `then`: changed keys take their old value, keys that were not there are null. */
export function mergePatch(now: Record<string, unknown>, then: Record<string, unknown>): Record<string, unknown> {
  const patch: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(then)) {
    const was = now[k]
    if (record(v) && record(was)) {
      const inner = mergePatch(record(was)!, record(v)!)
      if (Object.keys(inner).length) patch[k] = inner
    } else if (!same(was, v)) patch[k] = v
  }
  for (const k of Object.keys(now)) if (!(k in then)) patch[k] = null
  return patch
}

/** The moments worth stopping at: one per event, oldest first. */
export function stopsOf(events: ResourceEvent[]): { version: number; at: string; type: ResourceEvent['type'] }[] {
  return [...events].sort((a, b) => a.data.version - b.data.version).map((e) => ({ version: e.data.version, at: e.at, type: e.type }))
}
