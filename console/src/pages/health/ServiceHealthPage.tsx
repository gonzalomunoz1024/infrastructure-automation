import { PageHeader, Skeleton, cx } from '@iaf/ui'
import { ErrorState, ExternalLink, Section, StatusBadge, When } from '@iaf/console-kit'
import type { PageProps } from '../../app/router'
import { INCIDENT_WORDS, STATUS_WORDS, budgetText, healthApi, objectiveWords, percentText, readingWords, safeHref, summaryWords, type HealthService, type Incident, type ServiceHealth, type Slo, type SloWindow } from '../../lib/health'
import { useQuery } from '../../lib/queries'

/*
 * Service health (WP 9.20): the open incidents in words first, then each service the console
 * depends on, the platform first, with its status and its objectives for availability and
 * latency over the last 24 hours and 7 days, and its availability day by day. The BFF reads it
 * from the platform's SLO telemetry; the footer's platform dot links here. A link an incident
 * carries is followed only when it is https or a path of the console.
 */

const DAY_FORMAT = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' })

export default function ServiceHealthPage(_props: PageProps) {
  const q = useQuery('console-health', () => healthApi.get(), { refetchInterval: 60_000 })
  const h = q.data
  return (
    <>
      <PageHeader
        title="Service health"
        subtitle={
          h ? (
            <>
              {summaryWords(h.services)} Checked <When at={h.checkedAt} />.
            </>
          ) : (
            'Whether the platform and the console’s services meet their objectives.'
          )
        }
      />
      {q.error && !h ? <ErrorState error={q.error} title="Could not read the services’ health" onRetry={() => void q.refetch()} /> : !h ? <Skeleton className="h-64 w-full" /> : <Body health={h} />}
    </>
  )
}

function Body({ health }: { health: ServiceHealth }) {
  const open = health.incidents.filter((i) => i.status !== 'resolved')
  const resolved = health.incidents.filter((i) => i.status === 'resolved')
  const titleOf = (id: string) => health.services.find((s) => s.serviceId === id)?.title ?? id
  return (
    <div className="space-y-8">
      <Section title="Incidents now" description="What is not working as it should, and what the team has said about it.">
        {open.length === 0 ? (
          <p className="rounded-lg border border-stroke-light px-4 py-3 text-sm text-secondary">No open incidents.</p>
        ) : (
          <ul className="space-y-3">
            {open.map((i) => (
              <IncidentCard key={i.incidentId} incident={i} service={titleOf(i.serviceId)} />
            ))}
          </ul>
        )}
      </Section>

      <Section title="Services" description="Each service’s objectives: the share of requests that must succeed, and the share that must answer within a time.">
        <ul className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {health.services.map((s) => (
            <ServiceCard key={s.serviceId} service={s} />
          ))}
        </ul>
      </Section>

      {resolved.length > 0 && (
        <Section title="Resolved in the last 7 days">
          <ul className="space-y-3">
            {resolved.map((i) => (
              <IncidentCard key={i.incidentId} incident={i} service={titleOf(i.serviceId)} />
            ))}
          </ul>
        </Section>
      )}
    </div>
  )
}

function IncidentCard({ incident, service }: { incident: Incident; service: string }) {
  const href = safeHref(incident.link)
  const latest = incident.updates[0]
  return (
    <li className={cx('rounded-lg border p-4', incident.status === 'resolved' ? 'border-stroke-light' : 'border-warning/40 bg-warning-tint')} data-incident={incident.incidentId}>
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge status={incident.status === 'resolved' ? 'succeeded' : 'waiting'} label={INCIDENT_WORDS[incident.status]} size="sm" />
        <h3 className="text-sm font-semibold text-primary">{incident.title}</h3>
      </div>
      <p className="mt-1 text-caption text-secondary">
        {service}, {incident.impact === 'major' ? 'major' : 'minor'} impact. Started <When at={incident.startedAt} />
        {incident.resolvedAt && (
          <>
            , resolved <When at={incident.resolvedAt} />
          </>
        )}
        .
      </p>
      {latest && <p className="mt-2 text-sm text-primary">{latest.text}</p>}
      {incident.updates.length > 1 && (
        <details className="mt-2 text-caption text-secondary">
          <summary className="cursor-pointer text-primary">Earlier updates</summary>
          <ol className="mt-1 space-y-1">
            {incident.updates.slice(1).map((u) => (
              <li key={u.at}>
                <When at={u.at} />, {INCIDENT_WORDS[u.status].toLowerCase()}: {u.text}
              </li>
            ))}
          </ol>
        </details>
      )}
      {href && (
        <ExternalLink href={href} className="mt-2 inline-block text-caption font-medium">
          More from the team
        </ExternalLink>
      )}
    </li>
  )
}

const WINDOWS: SloWindow[] = ['24h', '7d']

function ServiceCard({ service }: { service: HealthService }) {
  const words = STATUS_WORDS[service.status]
  const availability = service.slos.find((s) => s.kind === 'availability')
  return (
    <li className="min-w-0 rounded-lg border border-stroke-light bg-surface p-4" data-service={service.serviceId} data-status={service.status}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-subheadline font-semibold text-primary">{service.title}</h3>
        <StatusBadge status={words.badge} label={words.label} />
      </div>
      <p className="mt-1 text-caption text-secondary">{service.description}</p>
      <dl className="mt-3 space-y-3">
        {service.slos.map((slo) => (
          <SloRow key={slo.kind} slo={slo} />
        ))}
      </dl>
      {availability && <Days service={service} objective={availability.objective} />}
    </li>
  )
}

function SloRow({ slo }: { slo: Slo }) {
  return (
    <div>
      <dt className="text-caption font-medium text-primary">
        {slo.kind === 'availability' ? 'Availability' : 'Latency'}: {objectiveWords(slo)}
      </dt>
      {WINDOWS.map((w) => {
        const r = slo.windows[w]
        return (
          <dd key={w} className="mt-1 flex items-start gap-2 text-caption text-secondary" data-slo={`${slo.kind}-${w}`} data-met={r.met}>
            <StatusBadge size="sm" status={r.met ? 'succeeded' : 'failed'} label={r.met ? 'Met' : 'Missed'} className="shrink-0" />
            <span className="min-w-0">
              <span className="sr-only">{readingWords(slo, w)}</span>
              <span aria-hidden="true">
                {w === '24h' ? '24 hours' : '7 days'}: {percentText(r.value)}
                {slo.kind === 'latency' && r.p95Ms !== undefined ? `, 95th percentile ${Math.round(r.p95Ms)} ms` : ''}, {budgetText(r.budgetLeft)}
              </span>
            </span>
          </dd>
        )
      })}
    </div>
  )
}

/** The availability of each of the last 7 days, as small bars, each said in words. */
function Days({ service, objective }: { service: HealthService; objective: number }) {
  return (
    <div className="mt-3">
      <p className="text-caption text-secondary">Availability by day, UTC</p>
      <ol className="mt-1 grid grid-cols-7 gap-1" aria-label={`${service.title}, availability by day`}>
        {service.days.map((d) => {
          const met = d.availability >= objective
          const label = `${DAY_FORMAT.format(new Date(`${d.date}T00:00:00Z`))}: ${percentText(d.availability)}, ${met ? 'met' : 'below'} the objective`
          return (
            <li key={d.date} title={label} className="min-w-0">
              <span aria-hidden="true" className={cx('block h-6 rounded-sm', met ? 'bg-healthy' : 'bg-warning')} />
              <span className="sr-only">{label}</span>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
