import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { ConsoleKitProvider, type ConsoleKitData, type ConsoleKitLinks } from '@iaf/console-kit'
import { api } from '../lib/api'
import { useAccount, type Account } from '../lib/accounts'
import { useCurrentUser } from '../lib/identity'
import { useQuery } from '../lib/queries'
import { GLOBAL_REGION, regionOf, useRegionName } from '../lib/regions'
import { composeTypes, findType, groupServices, partsOfWfrn, wfrnOf, type ConsoleService, type ConsoleType, type WfrnContext } from '../lib/descriptors'
import type { Me, Region } from '../lib/types'
import { pathFor, resourcePath } from './router'

/*
 * What every page needs: the composed types and services, the regions and the chosen one, the
 * chosen account, who is signed in, and the kit's provider wired to the console's API and routes.
 */

export interface ConsoleContext {
  types: ConsoleType[]
  services: ConsoleService[]
  regions: Region[]
  region: Region
  account: Account
  me: Me | undefined
  /** the descriptors are loading for the first time */
  loading: boolean
  /** the descriptors could not be loaded (and nothing was cached) */
  error: unknown
  /** the platform did not answer the identity call */
  offline: boolean
  wfrnContext: WfrnContext
  refetch: () => void
}

const Ctx = createContext<ConsoleContext | null>(null)

export function ConsoleProvider({ children }: { children: ReactNode }) {
  const user = useCurrentUser()
  const scope = user ?? 'anonymous'
  const me = useQuery(['me', scope], () => api.me())
  const types = useQuery(['resource-types', scope], () => api.resourceTypes())
  const catalog = useQuery(['catalog', scope], () => api.catalog())
  const regions = useQuery(['regions', scope], () => api.regions())
  const account = useAccount()
  const regionName = useRegionName()

  const composed = useMemo(() => composeTypes(types.data ?? [], catalog.data ?? []), [types.data, catalog.data])
  const services = useMemo(() => groupServices(composed), [composed])
  const regionList = useMemo(() => regions.data ?? [GLOBAL_REGION], [regions.data])
  const region = regionOf(regionName, regionList)
  const wfrnContext = useMemo<WfrnContext>(() => ({ partition: region.partition, region: region.name, account: account.id }), [region.partition, region.name, account.id])

  const value = useMemo<ConsoleContext>(
    () => ({
      types: composed,
      services,
      regions: regionList,
      region,
      account,
      me: me.data,
      loading: types.loading || catalog.loading,
      error: types.error ?? catalog.error,
      offline: !!me.error && !me.data,
      wfrnContext,
      refetch: () => {
        void types.refetch()
        void catalog.refetch()
        void regions.refetch()
      },
    }),
    [composed, services, regionList, region, account, me.data, me.error, types, catalog, regions, wfrnContext],
  )

  const kitData = useMemo<Partial<ConsoleKitData>>(
    () => ({
      resourceTypes: async () => composed.map((t) => ({ name: t.name, plural: t.plural })),
      resourcesOf: async (type: string) => {
        const t = composed.find((x) => x.name === type)
        if (!t) return []
        const page = await api.resourcesOf(t.plural, { limit: 200 })
        return page.items.map((r) => ({ id: r.id, type: r.type, name: r.name, status: r.status, wfrn: r.wfrn }))
      },
      lineage: (id: string) => api.lineage(id),
    }),
    [composed],
  )
  const kitLinks = useMemo<Partial<ConsoleKitLinks>>(
    () => ({
      resource: (ref) => {
        const t = (ref.type && composed.find((x) => x.name === ref.type)) || composed.find((x) => ref.id.startsWith(`${x.name}-`))
        if (!t) return `#${pathFor('home')}`
        const wfrn = ref.wfrn ?? wfrnOf({ id: ref.id, type: t.name, status: '', attributes: {}, owner: '', visibility: '', createdBy: { jobId: '', workflow: '' }, builtFrom: [], createdAt: '' }, t, wfrnContext).wfrn
        return `#${resourcePath(t.service, t.name, wfrn)}`
      },
      job: (id) => `#${pathFor('request', { id })}`,
      // there is no catalog page in the console yet: the picker says what creates one, without a link
      workflow: () => null,
    }),
    [composed, wfrnContext],
  )

  return (
    <Ctx.Provider value={value}>
      <ConsoleKitProvider data={kitData} links={kitLinks}>
        {children}
      </ConsoleKitProvider>
    </Ctx.Provider>
  )
}

export function useConsole(): ConsoleContext {
  const v = useContext(Ctx)
  if (!v) throw new Error('useConsole needs a ConsoleProvider above it')
  return v
}

/** The type a route names, if the descriptors know it. */
export function useRouteType(service: string | undefined, type: string | undefined): ConsoleType | undefined {
  const { types } = useConsole()
  return service && type ? findType(types, service, type) : undefined
}

/** The page of the resource a wfrn names, when the descriptors know its type. */
export function pathOfWfrn(wfrn: string | undefined | null, types: ConsoleType[]): string | null {
  if (!wfrn) return null
  const parts = partsOfWfrn(wfrn)
  if (!parts) return null
  const t = findType(types, parts.service, parts.type)
  return t ? resourcePath(t.service, t.name, wfrn) : null
}
