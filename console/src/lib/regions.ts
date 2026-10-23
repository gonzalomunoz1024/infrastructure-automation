import { createStore } from './store'
import type { Region } from './types'

/*
 * Regions (P-07): GET /api/v1/regions lists them; phase 0 platforms answer one, "global", and
 * the header then shows it without a menu. The response is read tolerantly: a list or {items},
 * each either flat or a file with metadata and spec.
 */

export const GLOBAL_REGION: Region = { name: 'global', title: 'Global', partition: 'wf', status: 'available' }

type Loose = Record<string, unknown>

function asRegion(raw: unknown): Region | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Loose
  const meta = (r.metadata && typeof r.metadata === 'object' ? r.metadata : {}) as Loose
  const spec = (r.spec && typeof r.spec === 'object' ? r.spec : {}) as Loose
  const name = typeof meta.name === 'string' ? meta.name : typeof r.name === 'string' ? r.name : null
  if (!name) return null
  const title = typeof meta.title === 'string' ? meta.title : typeof r.title === 'string' ? r.title : name.charAt(0).toUpperCase() + name.slice(1)
  const partition = typeof spec.partition === 'string' ? spec.partition : typeof r.partition === 'string' ? r.partition : 'wf'
  const status = typeof spec.status === 'string' ? spec.status : typeof r.status === 'string' ? r.status : undefined
  const envs = Array.isArray(spec.environments) ? spec.environments : Array.isArray(r.environments) ? r.environments : undefined
  return { name, title, partition, status, environments: envs?.filter((e): e is string => typeof e === 'string') }
}

/** The regions of any shape the platform may answer; the global region when it answers nothing. */
export function parseRegions(body: unknown): Region[] {
  const list = Array.isArray(body) ? body : body && typeof body === 'object' && Array.isArray((body as Loose).items) ? ((body as Loose).items as unknown[]) : []
  const regions = list.map(asRegion).filter((r): r is Region => r !== null)
  return regions.length ? regions : [GLOBAL_REGION]
}

export const REGION_STORAGE_KEY = 'console-region'

const store = createStore<string>(REGION_STORAGE_KEY, GLOBAL_REGION.name, (raw) => (typeof raw === 'string' && raw ? raw : null))

export function getRegionName(): string {
  return store.get()
}

export function setRegionName(name: string) {
  store.set(name)
}

/** The chosen region's name, reactive. The caller resolves it against the list the platform answered. */
export const useRegionName = store.use

export function regionOf(name: string, regions: Region[]): Region {
  return regions.find((r) => r.name === name) ?? regions[0] ?? GLOBAL_REGION
}
