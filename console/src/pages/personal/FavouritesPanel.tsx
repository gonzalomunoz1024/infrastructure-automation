import { EmptyState, Icon, SkeletonRows } from '@iaf/ui'
import { ErrorState, Section } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link, pathFor } from '../../app/router'
import { capitalise, findType, partsOfWfrn } from '../../lib/descriptors'
import { hrefOfFavourite } from '../../lib/personal'
import { FavouriteStar, PinButton } from './FavouriteStar'
import { useFavourites, usePinnedTypes } from './usePersonal'

/*
 * The console home's "Favourites": the resource types the person pinned (their preferences'
 * `pinnedTypes`, so they follow the person into every account) and the resources they starred in
 * this account (`GET /console/v1/favourites`, newest first). Each can be unpinned or unstarred
 * here. A panel of its own, so the home's layout can put it anywhere.
 */

export function FavouritesPanel() {
  const { types } = useConsole()
  const favourites = useFavourites()
  const pins = usePinnedTypes()
  const pinnedTypes = pins.pinned.map((key) => {
    const [service, name] = key.split('/')
    return { key, type: service && name ? findType(types, service, name) : undefined }
  })
  const shownTypes = pinnedTypes.filter((p) => p.type)
  const empty = shownTypes.length === 0 && favourites.items.length === 0

  return (
    <Section title="Favourites">
      {favourites.loading && shownTypes.length === 0 ? (
        <SkeletonRows n={2} rowClassName="h-10" />
      ) : favourites.error && !favourites.items.length && shownTypes.length === 0 ? (
        <ErrorState compact error={favourites.error} onRetry={favourites.refetch} title="Could not load your favourites" />
      ) : empty ? (
        <EmptyState compact icon="sparkles" title="No favourites yet" message="Star a resource on its page or in a list, or pin a resource type on its list, to keep it here." />
      ) : (
        <div className="space-y-3">
          {shownTypes.length > 0 && (
            <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Pinned resource types" data-pinned-types>
              {shownTypes.map(({ key, type }) => (
                <li key={key} className="flex items-center gap-1 pr-1">
                  <Link to={pathFor('type', { service: type!.service, type: type!.name })} className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                    <Icon name={type!.icon} size={15} className="shrink-0 text-tertiary" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-primary">{type!.title}</span>
                      <span className="block truncate text-caption text-tertiary">{capitalise(type!.service)} · resource type</span>
                    </span>
                  </Link>
                  <PinButton on name={type!.title} size="sm" onToggle={() => pins.setPinned(key, false)} />
                </li>
              ))}
            </ul>
          )}
          {favourites.items.length > 0 && (
            <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Favourite resources" data-favourites>
              {favourites.items.map((f) => {
                const href = hrefOfFavourite(f)
                const parts = partsOfWfrn(f.wfrn)
                const type = parts ? findType(types, parts.service, f.type || parts.type) : undefined
                return (
                  <li key={f.wfrn} className="flex items-center gap-1 pr-1">
                    {href ? (
                      <Link to={href} className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                        <Icon name={type?.icon ?? 'resource'} size={15} className="shrink-0 text-tertiary" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-primary">{f.name}</span>
                          <span className="block truncate text-caption text-tertiary">{type?.title ?? f.type}</span>
                        </span>
                      </Link>
                    ) : (
                      <span className="min-w-0 flex-1 truncate px-3 py-2 text-sm text-primary">{f.name}</span>
                    )}
                    <FavouriteStar on={favourites.isFavourite(f.wfrn)} name={f.name} size="sm" onToggle={() => favourites.toggle(f)} />
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </Section>
  )
}
