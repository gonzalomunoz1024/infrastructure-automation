import { useEffect } from 'react'
import { Card, EmptyState, Icon, PageHeader, SkeletonRows, Stat, formatNumber, plural } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { useConsole } from '../app/console'
import { LaterButton } from '../app/Phase'
import { Link, pathFor, type PageProps } from '../app/router'
import { api } from '../lib/api'
import { countByLifecycle, findService, purposeOf, type ConsoleType } from '../lib/descriptors'
import { useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'

/*
 * A service's landing page: one card per resource type with how many there are in each
 * lifecycle class, or, when there are none yet, what the type is for.
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
      <div className="space-y-6">
        {service.types.map((t) => (
          <TypeOverview key={t.name} type={t} />
        ))}
      </div>
    </>
  )
}

function TypeOverview({ type }: { type: ConsoleType }) {
  const list = useQuery(['resources', type.plural, 'overview'], () => api.resourcesOf(type.plural, { limit: 500 }))
  const items = list.data?.items ?? []
  const counts = countByLifecycle(items, type)
  const total = items.length
  const listHref = pathFor('type', { service: type.service, type: type.name })
  const purpose = purposeOf(type)
  const lower = type.title.toLowerCase()

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
      {list.loading ? (
        <SkeletonRows n={1} rowClassName="h-20" />
      ) : list.error ? (
        <ErrorState compact error={list.error} onRetry={list.refetch} title={`Could not count the ${lower}`} />
      ) : total === 0 ? (
        <EmptyState
          compact
          icon={type.icon}
          title={`No ${lower} yet`}
          message={`${purpose} The first one registered appears here.`}
          action={
            <LaterButton phase={3} size="sm" variant="primary" icon="plus">
              Create your first one
            </LaterButton>
          }
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Stat label="Total" value={formatNumber(total)} hint={list.data?.next ? 'the first 500 counted' : undefined} icon={type.icon} />
          <Stat label="Active" value={formatNumber(counts.active)} icon="check" />
          <Stat label="Pending" value={formatNumber(counts.pending)} icon="clock" />
          <Stat label="Failed" value={formatNumber(counts.failed)} icon="alert-triangle" />
          {counts.updating > 0 && <Stat label="Updating" value={formatNumber(counts.updating)} icon="refresh" />}
          {counts.deleting + counts.deleted > 0 && <Stat label="Deleting or deleted" value={formatNumber(counts.deleting + counts.deleted)} icon="trash" />}
          {counts.other > 0 && <Stat label="Other states" value={formatNumber(counts.other)} icon="help" />}
        </div>
      )}
    </Card>
  )
}
