import { useEffect } from 'react'
import { Card, EmptyState, Icon, PageHeader, SkeletonRows, Stat, plural, type IconName } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { useConsole } from '../app/console'
import { GettingStarted } from '../app/GettingStarted'
import { ActionButton } from '../app/Refusal'
import { Link, navigate, pathFor, type PageProps } from '../app/router'
import { bff } from '../lib/bff'
import { findService, purposeOf, singularOf, type ConsoleType } from '../lib/descriptors'
import { encodeTokens } from '../lib/listState'
import { useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'
import { countLabel } from '../lib/resourceList'
import type { ResourceState } from '../lib/types'

/*
 * A service's landing page: one card per resource type with how many there are in each lifecycle
 * state (the BFF's counts, "1,000+" past its cap), each count a link to the list filtered to it;
 * or, when there are none yet, what the type is for and how to make the first. Beside them, the
 * service's getting-started tour, until the person dismisses it.
 */

export default function ServicePage({ route }: PageProps) {
  const { services, loading, error, refetch } = useConsole()
  const service = findService(services, route.params.service)
  const serviceId = service?.id
  const serviceTitle = service?.title

  useEffect(() => {
    if (serviceId && serviceTitle) pushRecent({ kind: 'service', href: pathFor('service', { service: serviceId }), label: serviceTitle, hint: 'Service' })
  }, [serviceId, serviceTitle])

  if (!service) {
    if (loading) return <SkeletonRows n={3} rowClassName="h-28" />
    if (error) return <ErrorState error={error} onRetry={refetch} title="Could not load the services" />
    return (
      <EmptyState
        icon="grid"
        title="Unknown service"
        message={`The platform serves no service called "${route.params.service}".`}
        action={
          <Link to={pathFor('home')} className="text-sm font-medium text-accent transition-opacity hover:opacity-70">
            Console home
          </Link>
        }
      />
    )
  }

  return (
    <>
      <PageHeader eyebrow="Service" title={service.title} subtitle={`${plural(service.types.length, 'resource type')}, run by ${service.owners.join(' and ')}.`} />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
        <div className="min-w-0 space-y-6">
          {service.types.map((t) => (
            <TypeOverview key={t.name} type={t} />
          ))}
        </div>
        <GettingStarted service={service} className="lg:sticky lg:top-6" />
      </div>
    </>
  )
}

/** The tiles, in the order people look for them; the quiet states only when something is in them. */
const TILES: { state: ResourceState; label: string; icon: IconName; always?: boolean }[] = [
  { state: 'active', label: 'Active', icon: 'check', always: true },
  { state: 'pending', label: 'Pending', icon: 'clock', always: true },
  { state: 'failed', label: 'Failed', icon: 'alert-triangle', always: true },
  { state: 'updating', label: 'Updating', icon: 'refresh' },
  { state: 'deleting', label: 'Deleting', icon: 'trash' },
  { state: 'deleted', label: 'Deleted', icon: 'trash' },
]

function TypeOverview({ type }: { type: ConsoleType }) {
  const { account, region, decide } = useConsole()
  const counts = useQuery(['console-resource-counts', account.id, region.name, type.name, {}], () => bff.resources.counts(type.name))
  const c = counts.data
  const listHref = pathFor('type', { service: type.service, type: type.name })
  const filtered = (property: string, value: string) => pathFor('type', { service: type.service, type: type.name }, { f: encodeTokens([{ property, operator: '=', value }]) })
  const purpose = purposeOf(type)
  const lower = type.title.toLowerCase()
  const noun = singularOf(type.title).toLowerCase()
  const createTitle = type.create?.title ?? `Create ${noun}`

  return (
    <Card
      title={
        <Link to={listHref} className="flex items-center gap-2 rounded-md transition-opacity hover:opacity-70">
          <span className="flex size-7 items-center justify-center rounded-md bg-accent-tint text-accent">
            <Icon name={type.icon} size={15} />
          </span>
          {type.title}
        </Link>
      }
      subtitle={purpose}
      actions={
        <Link to={listHref} className="flex items-center gap-1 text-caption font-medium text-accent transition-opacity hover:opacity-70">
          View all
          <Icon name="arrow-right" size={12} />
        </Link>
      }
    >
      {counts.loading ? (
        <SkeletonRows n={1} rowClassName="h-20" />
      ) : counts.error || !c ? (
        <ErrorState compact error={counts.error} onRetry={counts.refetch} title={`Could not count the ${lower}`} />
      ) : c.total === 0 ? (
        <EmptyState
          compact
          icon={type.icon}
          title={`No ${lower} yet`}
          message={`${purpose} The first one appears here.`}
          action={
            type.create ? (
              <ActionButton decision={decide(type, 'create')} what={createTitle} verb="create" size="sm" variant="primary" icon="plus" onClick={() => navigate(pathFor('create', { service: type.service, type: type.name }))}>
                Create your first one
              </ActionButton>
            ) : undefined
          }
        />
      ) : (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-label={`${type.title} by state`}>
          <CountTile to={listHref} label="Total" value={countLabel(c.total, c.cappedAt)} icon={type.icon} />
          {TILES.filter((t) => t.always || (c.byState[t.state] ?? 0) > 0).map((t) => (
            <CountTile key={t.state} to={filtered('status', t.state)} label={t.label} value={countLabel(c.byState[t.state] ?? 0, c.cappedAt)} icon={t.icon} />
          ))}
          {c.drift > 0 && <CountTile to={filtered('drift', 'yes')} label="Drifted" value={countLabel(c.drift, c.cappedAt)} icon="pulse" />}
        </ul>
      )}
    </Card>
  )
}

function CountTile({ to, label, value, icon }: { to: string; label: string; value: string; icon: IconName }) {
  return (
    <li>
      <Link to={to} className="group block rounded-md">
        <Stat label={label} value={value} icon={icon} className="ring-1 ring-transparent transition-shadow group-hover:ring-accent/50" />
      </Link>
    </li>
  )
}
