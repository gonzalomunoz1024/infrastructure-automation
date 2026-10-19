/**
 * The health band of each published provider (GET /providers), for the dot on the canvas's
 * provider steps: one request for the whole canvas, read again when a provider step mounts a
 * minute or more later. The UI renders the server's band and never judges one; a canvas whose
 * providers did not answer shows no dots.
 */
import { useSyncExternalStore } from 'react'
import { api } from '../../lib/api'
import type { HealthStatus } from '../../lib/types'

const TTL_MS = 60_000

let bands: ReadonlyMap<string, HealthStatus> | null = null
let loadedAt = 0
let loading: Promise<void> | null = null
const listeners = new Set<() => void>()

/** Reads the bands once, or again when they are older than a minute; a failed read keeps what was there. */
export function loadProviderHealth(): Promise<void> {
  if (loading) return loading
  if (loadedAt > 0 && Date.now() - loadedAt < TTL_MS) return Promise.resolve()
  loading = api
    .providers()
    .then(
      (list) => {
        bands = new Map(list.map((p) => [p.name, p.health?.status ?? 'unknown']))
      },
      () => {
        bands ??= new Map()
      },
    )
    .finally(() => {
      loadedAt = Date.now()
      loading = null
      listeners.forEach((l) => l())
    })
  return loading
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  void loadProviderHealth()
  return () => {
    listeners.delete(listener)
  }
}

const snapshot = () => bands

/** The band of a published provider, or null while it is not known (not loaded, not published, or not answered). */
export function useProviderHealth(provider: string): HealthStatus | null {
  const map = useSyncExternalStore(subscribe, snapshot, snapshot)
  return map?.get(provider) ?? null
}

/** For tests: the bands as if GET /providers had answered them (null forgets them). */
export function setProviderHealthForTests(map: ReadonlyMap<string, HealthStatus> | null): void {
  bands = map
  loadedAt = map ? Date.now() : 0
}
