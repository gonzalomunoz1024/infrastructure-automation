import { EmptyState, Icon, SkeletonRows, relativeTime } from '@iaf/ui'
import { ErrorState, Section } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { Link } from '../../app/router'
import { capitalise, findType, partsOfWfrn } from '../../lib/descriptors'
import { hrefOfVisit, type RecentVisit } from '../../lib/personal'
import { useRecentVisits } from './usePersonal'

/*
 * The console home's "Recently visited": the types and resources the person opened lately in this
 * account, newest first, as the BFF keeps them (`GET /console/v1/recent`), so they follow the
 * person to any browser. A panel of its own, so the home's layout can put it anywhere.
 */

/** What a visit is, under its title: a type's service, a resource's type. */
function hintOf(v: RecentVisit, typeTitle: (service: string, type: string) => string | undefined): string {
  if (v.kind === 'type') {
    const [service] = v.key.split('/')
    return `${capitalise(service ?? '')} · resource type`
  }
  const parts = partsOfWfrn(v.key)
  return parts ? (typeTitle(parts.service, parts.type) ?? parts.type) : 'Resource'
}

export function RecentPanel({ limit = 8 }: { limit?: number }) {
  const { types } = useConsole()
  const recent = useRecentVisits()
  const items = (recent.data?.items ?? []).filter((v) => hrefOfVisit(v)).slice(0, limit)
  const typeTitle = (service: string, type: string) => findType(types, service, type)?.title

  return (
    <Section title="Recently visited">
      {recent.loading ? (
        <SkeletonRows n={3} rowClassName="h-10" />
      ) : recent.error && !recent.data ? (
        <ErrorState compact error={recent.error} onRetry={recent.refetch} title="Could not load your recent visits" />
      ) : items.length === 0 ? (
        <EmptyState compact icon="history" title="Nothing visited yet" message="The lists and resources you open in this account appear here, newest first." />
      ) : (
        <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Recently visited" data-recent>
          {items.map((v) => (
            <li key={`${v.kind}:${v.key}`}>
              <Link to={hrefOfVisit(v)!} className="flex items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                <Icon name={v.kind === 'type' ? 'list' : 'resource'} size={15} className="shrink-0 text-tertiary" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-primary">{v.title}</span>
                  <span className="block truncate text-caption text-tertiary">{hintOf(v, typeTitle)}</span>
                </span>
                <time className="shrink-0 text-caption text-tertiary" dateTime={v.visitedAt}>
                  {relativeTime(v.visitedAt)}
                </time>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}
