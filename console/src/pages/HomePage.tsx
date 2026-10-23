import { Card, EmptyState, Icon, PageHeader, SkeletonRows, plural, relativeTime, type IconName } from '@iaf/ui'
import { ErrorState, Section, StatusBadge } from '@iaf/console-kit'
import { useConsole } from '../app/console'
import { PHASES } from '../app/Phase'
import { Link, pathFor, type PageProps } from '../app/router'
import { api } from '../lib/api'
import type { ConsoleService } from '../lib/descriptors'
import { useQuery } from '../lib/queries'
import { useRecent, type RecentItem } from '../lib/recent'
import { HomeCostWidget } from './cost/HomeCostWidget'
import { HomeInsightsWidget } from './insights/HomeInsightsWidget'

/*
 * The console home: the services, the places visited lately, favourites (phase 2) and the
 * latest requests.
 */

const RECENT_ICON: Record<RecentItem['kind'], IconName> = { service: 'grid', type: 'list', resource: 'resource', page: 'document' }

export default function HomePage(_props: PageProps) {
  const { services, loading, error, refetch, me, account, region } = useConsole()
  const recent = useRecent()
  const jobs = useQuery(['jobs', 'latest'], () => api.jobs({ limit: 5 }))

  return (
    <>
      <PageHeader title="Console home" subtitle={`${me ? `Signed in as ${me.name}. ` : ''}Everything the platform manages for ${account.name}, in ${region.title}.`} />
      <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
        <div className="space-y-8 lg:col-span-2">
          <Section title="Services" description="Pick a service to see its resource types.">
            {loading && services.length === 0 ? (
              <SkeletonRows n={2} rowClassName="h-28" />
            ) : error && services.length === 0 ? (
              <ErrorState error={error} onRetry={refetch} title="Could not load the services" />
            ) : services.length === 0 ? (
              <EmptyState icon="grid" title="No services yet" message="Services appear as the platform publishes resource types." />
            ) : (
              <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2" aria-label="Services">
                {services.map((s) => (
                  <li key={s.id}>
                    <ServiceCard service={s} />
                  </li>
                ))}
              </ul>
            )}
          </Section>
          <HomeCostWidget />
          <HomeInsightsWidget />
        </div>

        <div className="space-y-8">
          <Section title="Recently visited">
            {recent.length === 0 ? (
              <EmptyState compact icon="history" title="Nothing visited yet" message="Services, types and resources you open appear here, newest first." />
            ) : (
              <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Recently visited">
                {recent.map((r) => (
                  <li key={r.href}>
                    <Link to={r.href} className="flex items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                      <Icon name={RECENT_ICON[r.kind]} size={15} className="shrink-0 text-tertiary" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-primary">{r.label}</span>
                        {r.hint && <span className="block truncate text-caption text-tertiary">{r.hint}</span>}
                      </span>
                      <time className="shrink-0 text-caption text-tertiary" dateTime={new Date(r.at).toISOString()}>
                        {relativeTime(r.at)}
                      </time>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          <Section title="Favourites">
            <EmptyState compact icon="sparkles" title="No favourites yet" message={`Pinning services and resources arrives with ${PHASES[2]}.`} />
          </Section>

          <Section
            title="Latest requests"
            actions={
              <Link to={pathFor('requests')} className="text-caption font-medium text-accent transition-opacity hover:opacity-70">
                All requests
              </Link>
            }
          >
            {jobs.loading ? (
              <SkeletonRows n={3} rowClassName="h-10" />
            ) : jobs.error ? (
              <ErrorState compact error={jobs.error} onRetry={jobs.refetch} title="Could not load the requests" />
            ) : !jobs.data?.items.length ? (
              <EmptyState compact icon="job" title="No requests yet" message="Requests appear here as workflows run." />
            ) : (
              <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Latest requests">
                {jobs.data.items.map((j) => (
                  <li key={j.id}>
                    <Link to={pathFor('request', { id: j.id })} className="flex items-center gap-3 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-primary">{j.title}</span>
                        <span className="block truncate font-mono text-caption text-tertiary">
                          {j.id} · {j.requester}
                        </span>
                      </span>
                      <StatusBadge status={j.status} size="sm" />
                      <time className="hidden shrink-0 text-caption text-tertiary sm:inline" dateTime={j.startedAt}>
                        {relativeTime(j.startedAt)}
                      </time>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </div>
      </div>
    </>
  )
}

function ServiceCard({ service }: { service: ConsoleService }) {
  return (
    <Card
      className="h-full"
      title={
        <Link to={pathFor('service', { service: service.id })} className="flex items-center gap-2 rounded-md transition-opacity hover:opacity-70">
          <span className="flex size-7 items-center justify-center rounded-md bg-accent-tint text-accent">
            <Icon name={service.icon} size={15} />
          </span>
          {service.title}
        </Link>
      }
      subtitle={`${plural(service.types.length, 'resource type')} · ${service.owners.join(', ')}`}
    >
      <ul className="flex flex-wrap gap-1.5" aria-label={`${service.title} resource types`}>
        {service.types.map((t) => (
          <li key={t.name}>
            <Link to={pathFor('type', { service: service.id, type: t.name })} className="inline-flex items-center gap-1.5 rounded-full border border-stroke-light px-2.5 py-1 text-caption text-secondary transition-colors hover:bg-surface-secondary hover:text-primary">
              <Icon name={t.icon} size={12} />
              {t.title}
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  )
}
