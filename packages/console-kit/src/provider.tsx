import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import type { Lineage, Resource, ResourceType } from './types'

/*
 * What the forked components used to import from the platform's api.ts and router, injected
 * instead: the data functions (resource types, the resources of a type, a resource's lineage)
 * and the links (where a resource, a job or a workflow lives in the app around the kit). Without
 * a provider the kit still renders: lists are empty and links follow the platform's own hash
 * routes, so a component can be shown alone or tested without an app.
 */

export type ResourceRef = Pick<Resource, 'id' | 'type' | 'name' | 'status'> & Partial<Pick<Resource, 'wfrn'>>

export interface ConsoleKitData {
  /** the resource types, for the picker's plural and title lookups */
  resourceTypes: () => Promise<Pick<ResourceType, 'name' | 'plural'>[]>
  /** the resources of one type (its name), for the picker */
  resourcesOf: (type: string) => Promise<ResourceRef[]>
  /** the lineage of a resource (by id) */
  lineage: (id: string) => Promise<Lineage>
}

export interface ConsoleKitLinks {
  /** the href of a resource's page (LineageGraph nodes, PreviewCard's affected resources) */
  resource: (ref: { id: string; type?: string; wfrn?: string }) => string
  /** the href of a job's page (StepTimeline's child jobs) */
  job: (id: string) => string
  /** the href that starts a workflow (the picker's "create one with …"); null hides the link */
  workflow: (name: string) => string | null
}

export interface ConsoleKitValue {
  data: ConsoleKitData
  links: ConsoleKitLinks
}

const enc = encodeURIComponent

export const DEFAULT_LINKS: ConsoleKitLinks = {
  resource: (ref) => `#/resources/${enc(ref.id)}`,
  job: (id) => `#/jobs/${enc(id)}`,
  workflow: (name) => `#/catalog/${enc(name)}`,
}

export const EMPTY_DATA: ConsoleKitData = {
  resourceTypes: async () => [],
  resourcesOf: async () => [],
  lineage: async (id) => {
    throw new Error(`No lineage source is configured for ${id}`)
  },
}

const Ctx = createContext<ConsoleKitValue>({ data: EMPTY_DATA, links: DEFAULT_LINKS })

export interface ConsoleKitProviderProps {
  data?: Partial<ConsoleKitData>
  links?: Partial<ConsoleKitLinks>
  children: ReactNode
}

/** Gives the kit its data functions and links. Anything left out keeps the default above. */
export function ConsoleKitProvider({ data, links, children }: ConsoleKitProviderProps) {
  const parent = useContext(Ctx)
  const value: ConsoleKitValue = {
    data: { ...parent.data, ...data },
    links: { ...parent.links, ...links },
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useConsoleKit(): ConsoleKitValue {
  return useContext(Ctx)
}

export interface AsyncState<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => void
}

type Settled<T> = { key: string; tick: number; data: T | undefined; error: unknown }

/**
 * A small async hook for the kit's own fetches (the picker's lists, a lineage): no cache, one
 * request in flight per key, the latest answer wins, the last answer for the same key kept on
 * screen while a reload runs. Apps keep their own query layer.
 */
export function useAsync<T>(key: string, fn: () => Promise<T>, enabled = true): AsyncState<T> {
  const fnRef = useRef(fn)
  useEffect(() => {
    fnRef.current = fn
  })
  const [tick, setTick] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)

  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    fnRef.current().then(
      (data) => {
        if (!cancelled) setSettled({ key, tick, data, error: null })
      },
      (error: unknown) => {
        if (!cancelled) setSettled({ key, tick, data: undefined, error })
      },
    )
    return () => {
      cancelled = true
    }
  }, [key, enabled, tick])

  const current = settled && settled.key === key && settled.tick === tick ? settled : null
  const previous = settled && settled.key === key ? settled : null
  return {
    data: enabled ? (current ?? previous)?.data : undefined,
    error: current?.error ?? null,
    loading: enabled && !current,
    reload: () => setTick((t) => t + 1),
  }
}
