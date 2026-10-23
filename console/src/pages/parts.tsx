import { EmptyState, SkeletonRows, longDate, shortDate } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { Link, pathFor } from '../app/router'

/* Small pieces the pages share. */

/** What a page shows when the route names a type the descriptors do not know. */
export function MissingType({ name, service, loading, error, refetch }: { name: string; service: string; loading: boolean; error: unknown; refetch: () => void }) {
  if (loading) return <SkeletonRows n={4} />
  if (error) return <ErrorState error={error} title="Could not load the resource types" onRetry={refetch} />
  return (
    <EmptyState
      icon="list"
      title={`No "${name}" type in ${service}`}
      message="The platform serves no resource type by that name in this service. It may have been renamed or retired."
      action={
        <Link to={pathFor('home')} className="text-sm font-medium text-accent transition-opacity hover:opacity-70">
          Console home
        </Link>
      }
    />
  )
}

/** A date for a table cell: short, with the full date on hover. */
export function Stamp({ at }: { at?: string | null }) {
  if (!at) return <span className="text-tertiary">not set</span>
  return (
    <time dateTime={at} title={longDate(at)} className="tabular-nums">
      {shortDate(at)}
    </time>
  )
}
