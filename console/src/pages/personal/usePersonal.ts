import { useCallback, useEffect, useMemo, useState } from 'react'
import { useToast } from '@iaf/ui'
import { useConsole } from '../../app/console'
import { isApiError } from '../../lib/api'
import { personalApi, pinnedOf, withPinned, type Favourite, type VisitKind } from '../../lib/personal'
import { loadPreferences, usePreferenceWriter, usePreferences } from '../../lib/preferences'
import { invalidate, useQuery } from '../../lib/queries'

/*
 * The hooks the pages share for recent visits, favourite resources and pinned types: a page
 * records its visit when it opens, the home's panels read what the BFF keeps, and a star or a pin
 * changes at once on screen while the call goes out (and goes back if the call fails).
 */

export const RECENT_KEY = (accountId: string, userId: string) => ['personal', 'recent', accountId, userId] as const
export const FAVOURITES_KEY = (accountId: string, userId: string) => ['personal', 'favourites', accountId, userId] as const

/** Records a visit when a type's list or a resource's page opens, once per place and title; a failure is never shown. */
export function useRecordVisit(kind: VisitKind, key: string | undefined, title: string | undefined) {
  const { account } = useConsole()
  useEffect(() => {
    if (!key || !title) return
    personalApi
      .recordVisit({ kind, key, title })
      .then(() => invalidate('personal/recent'))
      .catch(() => undefined)
  }, [kind, key, title, account.id])
}

export function useRecentVisits() {
  const { account, session } = useConsole()
  return useQuery(RECENT_KEY(account.id, session.user.userId), () => personalApi.recent())
}

export interface FavouritesApi {
  items: Favourite[]
  loading: boolean
  error: unknown
  refetch: () => Promise<void>
  isFavourite: (wfrn: string) => boolean
  toggle: (f: Pick<Favourite, 'wfrn' | 'type' | 'name'>) => void
}

/** The account's favourites, read once by the page that shows stars, with the star's change applied at once. */
export function useFavourites(): FavouritesApi {
  const { account, session } = useConsole()
  const toast = useToast()
  const q = useQuery(FAVOURITES_KEY(account.id, session.user.userId), () => personalApi.favourites())
  // what a star says while its call is out: wfrn → on
  const [pending, setPending] = useState<Record<string, boolean>>({})
  const stored = useMemo(() => new Set((q.data?.items ?? []).map((f) => f.wfrn)), [q.data])
  const isFavourite = useCallback((wfrn: string) => pending[wfrn] ?? stored.has(wfrn), [pending, stored])
  const toggle = useCallback(
    (f: Pick<Favourite, 'wfrn' | 'type' | 'name'>) => {
      const on = !(pending[f.wfrn] ?? stored.has(f.wfrn))
      setPending((p) => ({ ...p, [f.wfrn]: on }))
      const call = on ? personalApi.addFavourite(f.wfrn, { type: f.type, name: f.name }) : personalApi.removeFavourite(f.wfrn)
      call
        .then(() => q.refetch())
        .catch((e: unknown) => {
          const full = isApiError(e) && e.status === 409
          toast.error(on ? `Could not add ${f.name} to your favourites` : `Could not remove ${f.name} from your favourites`, full ? 'You keep 100 favourites in an account. Remove one from the console home first.' : isApiError(e) ? e.error : String(e))
        })
        .finally(() =>
          setPending((p) => {
            const next = { ...p }
            delete next[f.wfrn]
            return next
          }),
        )
    },
    [pending, stored, q, toast],
  )
  return { items: q.data?.items ?? [], loading: q.loading, error: q.error, refetch: q.refetch, isFavourite, toggle }
}

/** The person's pinned types, and pinning one: a preferences change, which keeps its local copy until the BFF answers. */
export function usePinnedTypes(): { pinned: string[]; isPinned: (typeKey: string) => boolean; setPinned: (typeKey: string, on: boolean) => void } {
  const { session } = useConsole()
  const owner = session.user.userId
  const prefs = usePreferences(owner)
  const write = usePreferenceWriter()
  useEffect(() => {
    void loadPreferences(owner)
  }, [owner])
  const pinned = useMemo(() => pinnedOf(prefs), [prefs])
  return { pinned, isPinned: (k) => pinned.includes(k), setPinned: (k, on) => write(withPinned(k, on)) }
}
