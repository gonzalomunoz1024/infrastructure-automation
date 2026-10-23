import { useEffect, useMemo } from 'react'
import { EmptyState, Icon, IconButton, JsonView, PageHeader, SkeletonRows, Tabs, Tag, cx } from '@iaf/ui'
import { CopyableId, ErrorState, ExternalLink, Facts, LifecycleBadge, ResourceLineage, Section, StatusBadge, When, WfrnText, formatField, formatValue, groupFields, titleCase, xConsoleOf } from '@iaf/console-kit'
import { useConsole, useRouteType } from '../app/console'
import { LaterButton, PHASES } from '../app/Phase'
import { Link, navigate, pathFor, resourcePath, type PageProps, type Route } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { api } from '../lib/api'
import { idOfWfrn, wfrnOf, type ConsoleType } from '../lib/descriptors'
import { useQuery } from '../lib/queries'
import { pushRecent } from '../lib/recent'
import type { Resource, Schema } from '../lib/types'
import { MissingType } from './parts'

/*
 * One resource: its facts, then the tabs the descriptor lists. Configuration shows the schema's
 * fields in their groups; Relationships draws the lineage; JSON shows the record as the platform
 * holds it. Drift, Tags and Permissions say which phase brings them.
 */

export default function ResourceDetailPage({ route }: PageProps) {
  const { loading, error, refetch } = useConsole()
  const type = useRouteType(route.params.service, route.params.type)
  if (!type) return <MissingType name={route.params.type} service={route.params.service} loading={loading} error={error} refetch={refetch} />
  return <ResourceDetail key={route.params.wfrn} type={type} route={route} />
}

function tabId(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '-')
}

function ResourceDetail({ type, route }: { type: ConsoleType; route: Route }) {
  const { wfrnContext } = useConsole()
  const wfrnParam = route.params.wfrn
  const id = idOfWfrn(wfrnParam) ?? wfrnParam
  const q = useQuery(['resource', type.plural, id], () => api.resourceOf(type.plural, id))
  const resource = q.data
  const named = resource ? wfrnOf(resource, type, wfrnContext) : null
  const tabs = useMemo(() => type.tabs.map((label) => ({ id: tabId(label), label })), [type.tabs])
  const tab = tabs.find((t) => t.id === route.query.tab) ?? tabs[0]
  const setTab = (next: string) => navigate(pathFor('resource', route.params, { tab: next === tabs[0]?.id ? undefined : next }, { accountId: route.accountId }), { replace: true })

  useEffect(() => {
    if (!resource) return
    const w = wfrnOf(resource, type, wfrnContext)
    pushRecent({ kind: 'resource', href: resourcePath(type.service, type.name, w.wfrn), label: resource.name ?? resource.id, hint: `${type.title} · ${resource.status}` })
  }, [resource, type, wfrnContext])

  if (q.loading) return <SkeletonRows n={4} />
  if (q.error && !resource) return <ErrorState error={q.error} title={`Could not load this ${type.title.toLowerCase()}`} onRetry={q.refetch} />
  if (!resource || !named) return <EmptyState icon={type.icon} title="Nothing here" message="The platform answered without a resource." />

  const actions = type.actions.filter((a) => a.verb !== 'create').slice(0, 3)

  return (
    <>
      <PageHeader
        eyebrow={type.title}
        title={resource.name ?? resource.id}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <WfrnText wfrn={named.wfrn} size="md" max={0} />
            {named.derived && (
              <Tag size="sm" outline title="Derived by the console from the account and region in the header, until the platform mints it">
                derived
              </Tag>
            )}
          </span>
        }
        actions={
          <>
            <IconButton label="Refresh" onClick={() => void q.refetch()}>
              <Icon name="refresh" size={16} className={cx(q.fetching && 'animate-spin')} />
            </IconButton>
            {actions.map((a) => (
              <LaterButton key={a.workflow} phase={3} variant={a.destructive ? 'danger' : 'secondary'} icon={a.destructive ? 'trash' : undefined}>
                {a.title}
              </LaterButton>
            ))}
          </>
        }
      />

      <Facts
        columns={4}
        className="mb-8"
        items={[
          { label: 'Status', value: <LifecycleBadge status={resource.status} lifecycle={type.lifecycle[resource.status]} /> },
          { label: 'Owner', value: resource.owner },
          { label: 'Visibility', value: resource.visibility || <span className="text-tertiary">not set</span> },
          { label: 'Created', value: <When at={resource.createdAt} /> },
          { label: 'Updated', value: <When at={resource.updatedAt} /> },
          {
            label: 'Created by',
            value: (
              <span className="flex flex-wrap items-center gap-x-1.5">
                <span className="font-mono">{resource.createdBy.workflow}</span>
                {resource.createdBy.version && <span className="text-tertiary">v{resource.createdBy.version}</span>}
                <Link to={pathFor('request', { id: resource.createdBy.jobId })} className="font-mono text-accent transition-opacity hover:opacity-70">
                  {resource.createdBy.jobId}
                </Link>
              </span>
            ),
          },
          { label: 'Account', value: <span className="font-mono">{formatAccountId(resource.accountId ?? wfrnContext.account)}</span> },
          { label: 'Region', value: resource.region ?? wfrnContext.region },
          ...(resource.providerId ? [{ label: 'Provider id', value: <CopyableId id={resource.providerId} /> }] : []),
        ]}
      />

      <Tabs<string> tabs={tabs} value={tab?.id ?? ''} onChange={setTab} ariaLabel={`${type.title} sections`} className="mb-5" />
      <div role="tabpanel" aria-label={tab?.label}>
        <TabContent label={tab?.label ?? ''} type={type} resource={resource} wfrn={named.wfrn} />
      </div>
    </>
  )
}

function TabContent({ label, type, resource, wfrn }: { label: string; type: ConsoleType; resource: Resource; wfrn: string }) {
  switch (tabId(label)) {
    case 'configuration':
      return <Configuration type={type} resource={resource} />
    case 'drift':
      return <EmptyState icon="pulse" title={`Drift detection arrives with ${PHASES[3]}`} message="The console will compare what the platform recorded with what the provider reports, and offer to bring them back together." />
    case 'activity':
      return <Activity resource={resource} wfrn={wfrn} />
    case 'relationships':
      return (
        <Section title="Lineage" description="What this was built from, and what was built from it.">
          <ResourceLineage id={resource.id} />
        </Section>
      )
    case 'tags':
      return <EmptyState icon="tag" title="No tags yet" message={`Tagging resources arrives with ${PHASES[2]}.`} />
    case 'permissions':
      return <EmptyState icon="shield" title={`Permissions arrive with ${PHASES[2]}`} message={`Today the platform records one principal for this resource: its owner team, ${resource.owner}.`} />
    case 'json':
      return <JsonView value={resource} defaultExpanded={2} rawToggle maxHeight="max-h-[40rem]" />
    default:
      return <EmptyState icon="document" title={`Nothing to show under ${label} yet`} message="This section is listed by the type's descriptor but the console has nothing for it in phase 0." />
  }
}

function FieldLabel({ name, schema }: { name: string; schema: Schema | undefined }) {
  const x = xConsoleOf(schema)
  const label = schema?.title ?? titleCase(name)
  return (
    <span className="flex items-center gap-1.5">
      {label}
      {x.help && (
        <ExternalLink href={x.help} ariaLabel={`Help for ${label}`} className="text-tertiary transition-colors hover:text-accent">
          <Icon name="help" size={12} />
        </ExternalLink>
      )}
    </span>
  )
}

function Configuration({ type, resource }: { type: ConsoleType; resource: Resource }) {
  const props = type.schema.properties ?? {}
  // a form keeps ungrouped fields in place; a page reads better with them together, first
  const grouped = groupFields(type.schema)
  const ungrouped = grouped.filter((g) => g.group === null).flatMap((g) => g.names)
  const groups = [...(ungrouped.length ? [{ group: 'Attributes', names: ungrouped }] : []), ...grouped.filter((g) => g.group !== null)]
  const known = new Set(Object.keys(props))
  const extra = Object.keys(resource.attributes ?? {}).filter((k) => !known.has(k))
  if (groups.length === 0 && extra.length === 0) return <EmptyState compact icon="document" title="No attributes" message="This type has no schema fields and the resource carries no attributes." />
  return (
    <div className="space-y-8">
      {groups.map((g) => (
        <Section key={g.group ?? 'default'} title={g.group ?? 'Attributes'}>
          <Facts columns={3} items={g.names.map((name) => ({ label: <FieldLabel name={name} schema={props[name]} />, value: formatField(type.schema, name, resource.attributes?.[name]) }))} />
        </Section>
      ))}
      {extra.length > 0 && (
        <Section title="Other attributes" description="Recorded on the resource, not in its schema.">
          <Facts columns={3} items={extra.map((name) => ({ label: titleCase(name), value: formatValue(resource.attributes?.[name]) }))} />
        </Section>
      )}
    </div>
  )
}

function Activity({ resource, wfrn }: { resource: Resource; wfrn: string }) {
  const jobs = useQuery(['jobs', 'activity', resource.id], () => api.jobs({ limit: 200 }))
  const related = (jobs.data?.items ?? []).filter((j) => (j.wfrn === wfrn || j.id === resource.createdBy.jobId) && j.id !== resource.createdBy.jobId)
  return (
    <Section title="Activity" description="The requests that created or changed this resource.">
      <ol className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Activity">
        <li className="flex items-center gap-3 px-3 py-2.5 text-sm">
          <Icon name="sparkles" size={15} className="shrink-0 text-tertiary" />
          <span className="min-w-0 flex-1">
            <span className="block text-primary">
              Registered by <span className="font-mono">{resource.createdBy.workflow}</span>
            </span>
            <Link to={pathFor('request', { id: resource.createdBy.jobId })} className="block truncate font-mono text-caption text-accent transition-opacity hover:opacity-70">
              {resource.createdBy.jobId}
            </Link>
          </span>
          <When at={resource.createdAt} className="shrink-0 text-caption text-tertiary" />
        </li>
        {related.map((j) => (
          <li key={j.id}>
            <Link to={pathFor('request', { id: j.id })} className="flex items-center gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-surface-secondary">
              <Icon name="job" size={15} className="shrink-0 text-tertiary" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-primary">{j.title}</span>
                <span className="block truncate font-mono text-caption text-tertiary">
                  {j.id} · {j.requester}
                </span>
              </span>
              <StatusBadge status={j.status} size="sm" />
              <When at={j.startedAt} className="hidden shrink-0 text-caption text-tertiary sm:inline" />
            </Link>
          </li>
        ))}
      </ol>
      {!jobs.loading && related.length === 0 && <p className="mt-2 text-caption text-tertiary">No other request has touched this resource{jobs.error ? ' that the console could load' : ''}.</p>}
    </Section>
  )
}
