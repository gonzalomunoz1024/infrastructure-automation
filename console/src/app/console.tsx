import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { ConsoleKitProvider, type ConsoleKitData, type ConsoleKitLinks } from '@iaf/console-kit'
import { isApiError } from '../lib/api'
import { accountFromSummary, type Account } from '../lib/accounts'
import { bff } from '../lib/bff'
import { decide as decideIn, permissionsOf, type Decision, type PermissionMap } from '../lib/permissions'
import { useQuery } from '../lib/queries'
import { GLOBAL_REGION, parseRegions, regionOf } from '../lib/regions'
import { isAdmin, meOf, type Session } from '../lib/session'
import { composeTypes, findType, groupServices, partsOfWfrn, wfrnOf, type ConsoleService, type ConsoleType, type WfrnContext } from '../lib/descriptors'
import { lineageOf, pickerRowOf } from '../lib/kit'
import type { Me, Region } from '../lib/types'
import { pathFor, resourcePath } from './router'

/*
 * What every page needs: the session (who, which account, which region), the types composed from
 * the BFF's descriptors and their services, the regions, the decisions the BFF made about each
 * type's actions, and the kit's provider wired to the BFF and the console's routes.
 */

export interface ConsoleContext {
  session: Session
  types: ConsoleType[]
  services: ConsoleService[]
  regions: Region[]
  region: Region
  account: Account
  me: Me
  /** the signed-in person holds the admin role in the account */
  admin: boolean
  /** the BFF's decisions by type and verb */
  permissions: PermissionMap
  /** whether this person may run a verb on a type here; allowed until the BFF has said */
  decide: (type: Pick<ConsoleType, 'service' | 'name'>, verb: string) => Decision
  /** the descriptors are loading for the first time */
  loading: boolean
  /** the descriptors could not be loaded (and nothing was cached) */
  error: unknown
  /** the BFF did not answer */
  offline: boolean
  wfrnContext: WfrnContext
  refetch: () => void
}

const Ctx = createContext<ConsoleContext | null>(null)

export function ConsoleProvider({ session, children }: { session: Session; children: ReactNode }) {
  const scope = `${session.user.userId}/${session.account.accountId}/${session.region}`
  const types = useQuery(['console-types', scope], () => bff.types())
  const regions = useQuery(['regions', scope], async () => parseRegions((await bff.regions()).items))

  const composed = useMemo(() => composeTypes(types.data), [types.data])
  const services = useMemo(() => groupServices(composed), [composed])
  const regionList = useMemo(() => regions.data ?? [GLOBAL_REGION], [regions.data])
  const region = regionOf(session.region, regionList)
  const account = useMemo(() => accountFromSummary(session.account), [session.account])
  const me = useMemo(() => meOf(session), [session])
  const permissions = useMemo(() => permissionsOf(types.data), [types.data])
  const wfrnContext = useMemo<WfrnContext>(() => ({ partition: region.partition, region: region.name, account: account.id }), [region.partition, region.name, account.id])

  const value = useMemo<ConsoleContext>(
    () => ({
      session,
      types: composed,
      services,
      regions: regionList,
      region,
      account,
      me,
      admin: isAdmin(session),
      permissions,
      decide: (type, verb) => decideIn(permissions, type.service, type.name, verb),
      loading: types.loading,
      error: types.error,
      offline: !types.data && isApiError(types.error) && types.error.unreachable,
      wfrnContext,
      refetch: () => {
        void types.refetch()
        void regions.refetch()
      },
    }),
    [session, composed, services, regionList, region, account, me, permissions, types, regions, wfrnContext],
  )

  const kitData = useMemo<Partial<ConsoleKitData>>(
    () => ({
      resourceTypes: async () => composed.map((t) => ({ name: t.name, plural: t.plural })),
      resourcesOf: async (type: string) => {
        if (!composed.some((x) => x.name === type)) return []
        const page = await bff.resources.list(type, { limit: 200 })
        return page.items.map(pickerRowOf)
      },
      // the kit names a resource by its wfrn here: the BFF reads the lineage within the account
      lineage: async (wfrn: string) => {
        const parts = partsOfWfrn(wfrn)
        if (!parts) throw new Error(`${wfrn} is not a wfrn`)
        return lineageOf(await bff.resources.lineage(parts.type, wfrn))
      },
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
