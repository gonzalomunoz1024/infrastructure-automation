import { useState, type ReactNode } from 'react'
import { Button, EmptyState, Icon, JsonView, SkeletonRows } from '@iaf/ui'
import { CodeView, DotTag, ExternalLink, Facts, Section, StatusBadge, When, formatField, formatValue, formatValueText, groupFields, mutableFields, titleCase, xConsoleOf } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ExplainedError } from '../../app/commands'
import { LaterButton } from '../../app/Phase'
import { ActionButton, useRefusal } from '../../app/Refusal'
import { Link, navigate, pathFor, useRoute } from '../../app/router'
import { isApiError } from '../../lib/api'
import { bff } from '../../lib/bff'
import { codeSamplesFor } from '../../lib/code'
import { singularOf, type ConsoleType } from '../../lib/descriptors'
import { useQuery } from '../../lib/queries'
import { verbsOf } from '../../lib/verbs'
import type { ActivityEntry, ConsoleResourceDetail, Resource, Schema } from '../../lib/types'
import { DriftTab } from './DriftTab'
import { EDIT_NOT_OFFERED, editWaitNote } from './EditDrawer'
import { HeldButton } from './HeldButton'
import { MonitoringTab } from './MonitoringTab'
import { LineageView } from './LineageView'
import { TimeTravel } from './TimeTravel'
import { explainLine } from '../../lib/plain'
import { EVENT_TYPE_WORDS, eventSummary, isStateEventType, stateApi } from '../../lib/state'
import type { ChainCheck, ResourceEvent } from '../../lib/trail'
import { actorName, usePeople } from '../state/common'

/*
 * The tabs of a resource page. Each reads what it needs: Configuration the record (with the
 * fields the drift service found different marked), Drift the findings, Activity the state service's
 * events with time travel over them (./TimeTravel) and a check of their chain, Relationships the
 * lineage (./LineageView), Tags and Permissions the console's view, JSON the record and the same
 * record as code. Everything here reads the BFF.
 */

export function tabId(label: string): string {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '-')
}

export interface TabProps {
  type: ConsoleType
  wfrn: string
  /** the record as the platform holds it, when it answered */
  record: Resource | undefined
  /** the console's view of it, when the BFF answered */
  detail: ConsoleResourceDetail | undefined
  /** why the console's view is missing */
  viewError: unknown
}

export function TabContent({ label, ...props }: TabProps & { label: string }) {
  const { type } = props
  switch (tabId(label)) {
    case 'configuration':
      return <Configuration {...props} />
    case 'drift':
      return <DriftTab {...props} />
    case 'activity':
      return <ActivityTab {...props} />
    case 'relationships':
      return <LineageView type={type} wfrn={props.wfrn} />
    case 'monitoring':
      return <MonitoringTab {...props} />
    case 'tags':
      return <TagsTab {...props} />
    case 'permissions':
      return <PermissionsTab {...props} />
    case 'json':
      return <JsonTab {...props} />
    default:
      return <EmptyState icon="document" title={`Nothing to show under ${label} yet`} message={`${type.owner} lists this section for ${type.title.toLowerCase()}, but the console has nothing to put in it yet.`} />
  }
}

/** The console's view is needed and missing: still loading, or the reason it is not there. */
function ViewMissing({ viewError, what }: { viewError: unknown; what: string }) {
  if (viewError) return <ExplainedError error={viewError} what={what} compact />
  return <SkeletonRows n={2} />
}

/* ---------------------------------------------------------------------------- Configuration */

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

export type DriftStatus = 'drifted' | 'in-sync' | 'unknown'

/** What the page says about drift, from the BFF's fields: drifted when it says so or a finding is open, in sync once a scan has read the resource, else not checked yet. */
export function driftStatusOf(detail: Pick<ConsoleResourceDetail, 'drift' | 'finding' | 'observedAt'> | undefined): DriftStatus {
  if (!detail) return 'unknown'
  if (detail.drift || detail.finding?.status === 'open') return 'drifted'
  return detail.observedAt ? 'in-sync' : 'unknown'
}

const DRIFT_WORDS: Record<DriftStatus, { tone: 'healthy' | 'warning' | 'neutral'; text: string }> = {
  drifted: { tone: 'warning', text: 'Drifted' },
  'in-sync': { tone: 'healthy', text: 'In sync' },
  unknown: { tone: 'neutral', text: 'Not checked yet' },
}

/** The Drift fact at the top of the page: the status, and when a scan last read the resource. */
export function DriftFact({ detail }: { detail: ConsoleResourceDetail }) {
  const words = DRIFT_WORDS[driftStatusOf(detail)]
  return (
    <span className="flex flex-wrap items-center gap-x-1.5">
      <DotTag tone={words.tone}>{words.text}</DotTag>
      {detail.observedAt && (
        <span className="text-caption text-tertiary">
          checked <When at={detail.observedAt} />
        </span>
      )}
    </span>
  )
}

/** The open drift findings: the one the detail carries, else the resource's findings, asked for only when the detail says it drifted. */
function useOpenFindings(type: ConsoleType, wfrn: string, detail: ConsoleResourceDetail | undefined) {
  const carried = detail?.finding?.status === 'open' ? detail.finding : undefined
  const ask = !carried && driftStatusOf(detail) === 'drifted'
  const list = useQuery(['console-drift', type.name, wfrn], () => bff.resources.drift(type.name, wfrn), { enabled: ask })
  const open = carried ? [carried] : (list.data?.items ?? []).filter((f) => f.status === 'open')
  return { open, loading: ask && list.loading, error: ask ? list.error : undefined, refetch: list.refetch }
}

/**
 * The Configuration tab's Edit: the Actions menu's Edit configuration as a link (`?action=edit`),
 * under the same decision; it waits while a request is in flight, and a type whose provider
 * publishes no update workflow says so instead.
 */
function EditButton({ type, detail }: { type: ConsoleType; detail: ConsoleResourceDetail | undefined }) {
  const { decide } = useConsole()
  const route = useRoute()
  const noun = singularOf(type.title).toLowerCase()
  if (!type.update) {
    return (
      <HeldButton reason={EDIT_NOT_OFFERED} variant="secondary" size="sm" icon="edit" verb="update">
        Edit
      </HeldButton>
    )
  }
  const decision = decide(type, 'update')
  if (!decision.allowed) {
    return (
      <ActionButton decision={decision} what={`Edit this ${noun}`} verb="update" size="sm" icon="edit">
        Edit
      </ActionButton>
    )
  }
  const wait = editWaitNote(detail, noun)
  if (wait) {
    return (
      <HeldButton reason={wait} variant="secondary" size="sm" icon="edit" verb="update">
        Edit
      </HeldButton>
    )
  }
  return (
    <Link to={pathFor('resource', route.params, { ...route.query, action: 'edit' }, { accountId: route.accountId })} replace data-verb="update" className="inline-flex select-none items-center justify-center gap-1.5 whitespace-nowrap rounded-md bg-surface-secondary px-3 py-1.5 text-caption font-medium text-primary transition-all duration-150 hover:bg-stroke-light active:scale-[0.97]">
      <Icon name="edit" size={13} />
      Edit
    </Link>
  )
}

function Configuration({ type, wfrn, record, detail, viewError }: TabProps) {
  const deleted = isApiError(viewError) && viewError.status === 410
  const props = type.schema.properties ?? {}
  const attributes = record?.attributes ?? detail?.attributes ?? {}
  const { open } = useOpenFindings(type, wfrn, detail)
  const differs = new Map<string, { expected: unknown; observed: unknown }>()
  for (const f of open) for (const d of f.fields) differs.set(d.path.replace(/^attributes\./, ''), d)
  // a form keeps ungrouped fields in place; a page reads better with them together, first
  const grouped = groupFields(type.schema)
  const ungrouped = grouped.filter((g) => g.group === null).flatMap((g) => g.names)
  const groups = [...(ungrouped.length ? [{ group: 'Attributes', names: ungrouped }] : []), ...grouped.filter((g) => g.group !== null)]
  const known = new Set(Object.keys(props))
  const extra = Object.keys(attributes).filter((k) => !known.has(k))
  const editable = !deleted && (!!type.update || mutableFields(type.schema).length > 0)
  if (groups.length === 0 && extra.length === 0) return <EmptyState compact icon="document" title="No attributes" message="This type has no schema fields and the resource carries no attributes." />
  const value = (name: string, shown: ReactNode) => {
    const d = differs.get(name)
    if (!d) return shown
    return (
      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
        {shown}
        <DotTag tone="warning" size="sm" title="Found by the drift service; see the Drift tab">
          {`Provider reports ${formatValueText(d.observed, xConsoleOf(props[name]))}`}
        </DotTag>
      </span>
    )
  }
  return (
    <div className="space-y-8">
      {groups.map((g, i) => (
        <Section
          key={g.group ?? 'default'}
          title={g.group ?? 'Attributes'}
          actions={
            i === 0 && editable ? <EditButton type={type} detail={detail} /> : undefined
          }
        >
          <Facts columns={3} items={g.names.map((name) => ({ label: <FieldLabel name={name} schema={props[name]} />, value: value(name, formatField(type.schema, name, attributes[name])) }))} />
        </Section>
      ))}
      {extra.length > 0 && (
        <Section title="Other attributes" description="Recorded on the resource, not in its schema.">
          <Facts columns={3} items={extra.map((name) => ({ label: titleCase(name), value: value(name, formatValue(attributes[name])) }))} />
        </Section>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------------------- Drift */

/* The tab's findings, decisions and history live in ./DriftTab. */

/* ---------------------------------------------------------------------------- Activity */

/** The first success of a resource's own create reads as its registration; every other event by its type's words. */
function eventWords(entry: ActivityEntry, first: boolean): { icon: 'sparkles' | 'tag' | 'pulse' | 'trash' | 'refresh' | 'history'; text: string } {
  const t = entry.type ?? ''
  if (t === 'ProvisioningSucceeded' && first) return { icon: 'sparkles', text: 'Registered in the inventory' }
  const text = isStateEventType(t) ? EVENT_TYPE_WORDS[t] : t || 'Event'
  const icon = t === 'Tagged' ? 'tag' : t.startsWith('Drift') || t === 'Observed' ? 'pulse' : t === 'Deleted' || t === 'DeleteRequested' ? 'trash' : t === 'ResourceRequested' ? 'sparkles' : 'refresh'
  return { icon, text }
}

function ActivityRow({ entry, first, people }: { entry: ActivityEntry; first: boolean; people: Map<string, string> }) {
  if (entry.kind === 'job' && entry.job) {
    const j = entry.job
    return (
      <li>
        <Link to={pathFor('request', { id: j.jobId })} className="flex items-center gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-surface-secondary">
          <Icon name="job" size={15} className="shrink-0 text-tertiary" />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-primary">{j.title ?? j.workflow}</span>
            <span className="block truncate font-mono text-caption text-tertiary">
              {j.jobId}
              {j.requester ? ` · ${actorName(j.requester, people)}` : ''}
            </span>
          </span>
          <StatusBadge status={j.status} size="sm" />
          <When at={entry.at} className="hidden shrink-0 text-caption text-tertiary sm:inline" />
        </Link>
      </li>
    )
  }
  const words = eventWords(entry, first)
  // an event a request caused names it: "Registered in the inventory, by Dana Reyes, through create-vm job_…"
  const data = entry.data && typeof entry.data === 'object' ? (entry.data as { jobId?: unknown; workflow?: unknown; version?: unknown }) : {}
  const jobId = typeof data.jobId === 'string' ? data.jobId : undefined
  const workflow = typeof data.workflow === 'string' ? data.workflow : undefined
  const summary = entry.type && isStateEventType(entry.type) ? eventSummary({ type: entry.type, data: entry.data as ResourceEvent['data'] }) : undefined
  return (
    <li className="flex items-center gap-3 px-3 py-2.5 text-sm">
      <Icon name={words.icon} size={15} className="shrink-0 text-tertiary" />
      <span className="min-w-0 flex-1">
        <span className="block text-primary">
          {words.text}
          {summary && <span className="text-secondary">: {summary}</span>}
        </span>
        {(entry.actor || jobId) && (
          <span className="block truncate text-caption text-tertiary">
            {entry.actor && <>by {actorName(entry.actor, people)}</>}
            {jobId && (
              <>
                {entry.actor ? ', ' : ''}through{workflow ? <> {workflow}</> : null}{' '}
                <Link to={pathFor('request', { id: jobId })} className="font-mono text-accent transition-opacity hover:opacity-70">
                  {jobId}
                </Link>
              </>
            )}
          </span>
        )}
      </span>
      <When at={entry.at} className="shrink-0 text-caption text-tertiary" />
    </li>
  )
}

/** Checks the resource's hash chain at the state service and says in words what it found. */
function ChainCheckButton({ type, wfrn, noun }: { type: ConsoleType; wfrn: string; noun: string }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ChainCheck | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const check = async () => {
    setBusy(true)
    setFailure(null)
    try {
      setResult(await stateApi.verify(type.name, wfrn))
    } catch (e) {
      setFailure(e)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex flex-col items-end gap-1">
      <Button size="sm" variant="secondary" icon="shield" loading={busy} onClick={() => void check()}>
        Check its chain
      </Button>
      <span role="status" aria-live="polite" className="max-w-md text-right text-caption" data-chain={result ? (result.valid ? 'holds' : 'broken') : 'none'}>
        {result &&
          (result.valid ? (
            <span className="text-primary">{`The chain holds: all ${result.entries} events of this ${noun} match their hashes and link in order.`}</span>
          ) : (
            <span className="text-critical">{`The chain breaks at version ${result.brokenAt}: ${result.reason}. The events before it hold.`}</span>
          ))}
        {failure !== null && <span className="text-critical">{explainLine(failure, 'Check the chain')}</span>}
      </span>
    </div>
  )
}

function ActivityTab({ type, wfrn, detail }: TabProps) {
  const q = useQuery(['console-activity', type.name, wfrn], () => bff.resources.activity(type.name, wfrn, { limit: 50 }))
  const people = usePeople()
  const noun = singularOf(type.title).toLowerCase()
  const items = q.data?.items ?? []
  // the oldest success is the create's: it reads as the registration
  const firstSuccess = [...items].reverse().find((e) => e.type === 'ProvisioningSucceeded')
  return (
    <div className="space-y-8">
      <TimeTravel type={type} wfrn={wfrn} detail={detail} />
      <Section title="Activity" description="What the state service recorded about this resource and the requests that changed it, newest first. Each event is chained to the one before it." actions={<ChainCheckButton type={type} wfrn={wfrn} noun={noun} />}>
        {q.error ? (
          <ExplainedError error={q.error} what="Load the activity of this resource" onRetry={() => void q.refetch()} compact />
        ) : q.loading ? (
          <SkeletonRows n={3} />
        ) : items.length === 0 ? (
          <EmptyState compact icon="history" title="No activity yet" message="Requests that create or change this resource, and the events the platform records about it, appear here." />
        ) : (
          <ol className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Activity">
            {items.map((e, i) => (
              <ActivityRow key={`${e.at}-${e.job?.jobId ?? e.type ?? ''}-${i}`} entry={e} first={e === firstSuccess} people={people} />
            ))}
          </ol>
        )}
      </Section>
    </div>
  )
}

/* ---------------------------------------------------------------------------- Tags */

function TagsTab({ type, detail, viewError }: TabProps) {
  const { decide } = useConsole()
  const route = useRoute()
  if (!detail) return <ViewMissing viewError={viewError} what="Load the tags of this resource" />
  const tags = Object.entries(detail.tags ?? {}).sort(([a], [b]) => a.localeCompare(b))
  // the page opens the Tags drawer on `?action=tags`, as its Actions menu does
  const manage = () => navigate(pathFor('resource', route.params, { ...route.query, action: 'tags' }, { accountId: route.accountId }), { replace: true })
  return (
    <Section
      title="Tags"
      description="Key and value pairs that travel with the resource and its events, for finding, grouping and paying for things together."
      actions={
        <ActionButton decision={decide(type, 'update')} what="Manage tags" verb="tag" size="sm" icon="tag" onClick={manage}>
          Manage tags
        </ActionButton>
      }
    >
      {tags.length === 0 ? (
        <EmptyState compact icon="tag" title="No tags yet" message="Add tags with Manage tags: a name and a value each, for finding, grouping and paying for things together." />
      ) : (
        <div className="overflow-x-auto rounded-lg border border-stroke-light">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Tags</caption>
            <thead className="text-caption text-tertiary">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium">
                  Key
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Value
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stroke-light">
              {tags.map(([k, v]) => (
                <tr key={k}>
                  <td className="px-4 py-2 font-mono text-caption text-primary">{k}</td>
                  <td className="px-4 py-2 text-primary">{v}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  )
}

/* ---------------------------------------------------------------------------- Permissions */

function PermissionsTab({ type, record, detail }: TabProps) {
  const { decide, account } = useConsole()
  const refusal = useRefusal()
  const noun = singularOf(type.title).toLowerCase()
  const rows = [{ verb: 'get', title: `View this ${noun}`, workflow: undefined as string | undefined }, ...verbsOf(type).map((a) => ({ verb: a.verb, title: a.title, workflow: a.workflow }))]
  return (
    <div className="space-y-8">
      <Section title="What you can do here" description={`As the account’s policies decide for you in ${account.name}, for this ${noun}.`}>
        <div className="overflow-x-auto rounded-lg border border-stroke-light">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Your access to this {noun}</caption>
            <thead className="text-caption text-tertiary">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium">
                  Action
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  Workflow
                </th>
                <th scope="col" className="px-4 py-2 font-medium">
                  You
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-stroke-light">
              {rows.map((r) => {
                const d = decide(type, r.verb)
                const here = detail ? detail.can.includes(r.verb) : undefined
                const allowed = d.allowed && here !== false
                return (
                  <tr key={r.verb} data-verb={r.verb} data-allowed={allowed}>
                    <td className="px-4 py-2 text-primary">{r.title}</td>
                    <td className="px-4 py-2 font-mono text-caption text-secondary">{r.workflow ?? 'read'}</td>
                    <td className="px-4 py-2">
                      <span className="flex flex-wrap items-center gap-2">
                        <DotTag tone={allowed ? 'healthy' : 'critical'} size="sm">
                          {allowed ? 'Allowed' : d.allowed ? 'Not on this resource' : 'Not allowed'}
                        </DotTag>
                        {!d.allowed && d.denied && (
                          <Button size="sm" variant="ghost" onClick={() => refusal.show({ denied: d.denied!, what: r.title })}>
                            {`Why not: ${r.title}`}
                          </Button>
                        )}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Section>
      <Section
        title="Who owns it"
        description="The platform records one principal per resource today; resource policies come later."
        actions={
          <LaterButton phase={7} size="sm" icon="shield">
            Edit resource policy
          </LaterButton>
        }
      >
        <Facts
          columns={3}
          items={[
            { label: 'Owner team', value: record?.owner ?? (typeof detail?.attributes.owner === 'string' ? detail.attributes.owner : undefined) },
            { label: 'Account', value: account.name },
            { label: 'Provider', value: type.owner },
          ]}
        />
      </Section>
    </div>
  )
}

/* ---------------------------------------------------------------------------- JSON */

function JsonTab({ type, wfrn, record, detail }: TabProps) {
  const { account, region } = useConsole()
  const value = record ?? detail
  return (
    <div className="space-y-8">
      <Section title="Record" description={record ? 'The resource as the platform holds it.' : 'The resource as the console holds it.'}>
        <JsonView value={value} defaultExpanded={2} rawToggle maxHeight="max-h-[40rem]" />
      </Section>
      <Section title="View as code" description="The same record from the console’s CLI, its API, or the platform’s own.">
        <CodeView samples={codeSamplesFor({ kind: 'record', type, wfrn, record: value, context: { accountId: account.id, region: region.name } })} ariaLabel="This record as code" />
      </Section>
    </div>
  )
}
