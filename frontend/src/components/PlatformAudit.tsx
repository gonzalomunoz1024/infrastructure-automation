/**
 * The platform audit stream as the Audit page shows it: the commands that are not about a job
 * (drafts, change requests, publishes, provider changes, credentials by name, service accounts,
 * release decisions, builder sessions, retired resources), each with its event, the subject it
 * changed and a link to that subject's page, and the chain's verification.
 */
import { Icon, JsonView, StatusDot, Tag, cx, type Tone } from '@iaf/ui'
import { pathFor } from '../app/router'
import type { AuditVerification, EvidenceEntry } from '../lib/types'
import { TextLink } from './common'

/** The aggregates the stream names, as the filter offers them. */
export const AGGREGATES: { value: string; label: string }[] = [
  { value: 'workflow', label: 'Workflows' },
  { value: 'change-request', label: 'Change requests' },
  { value: 'provider', label: 'Providers' },
  { value: 'release', label: 'Releases' },
  { value: 'credential', label: 'Credentials' },
  { value: 'service-account', label: 'Service accounts' },
  { value: 'assistant-session', label: 'Builder sessions' },
  { value: 'resource', label: 'Resources' },
]

const str = (v: unknown): string | undefined => (typeof v === 'string' && v !== '' ? v : typeof v === 'number' ? String(v) : undefined)

/** What a platform entry is about: its event type and the aggregate it changed. */
export function platformEvent(e: Pick<EvidenceEntry, 'data' | 'kind'>): { event: string; aggregate?: string; aggregateId?: string } {
  const d = e.data ?? {}
  return { event: str(d.event) ?? e.kind, aggregate: str(d.aggregate), aggregateId: str(d.aggregateId) }
}

/** The page of the thing an entry changed, when the shell has one. */
export function subjectHref(aggregate: string | undefined, id: string | undefined): string | null {
  if (!id) return null
  switch (aggregate) {
    case 'workflow':
      return pathFor('workflow', { name: id })
    case 'provider':
      return pathFor('provider', { name: id })
    case 'change-request':
      return pathFor('change-request', { id })
    case 'release':
      return pathFor('release', { id })
    case 'resource':
      return pathFor('resource', { id })
    case 'job':
      return pathFor('job', { id })
    case 'credential':
      return pathFor('credentials')
    case 'service-account':
      return pathFor('settings')
    case 'assistant-session':
      return pathFor('assistant')
    default:
      return null
  }
}

const VERBS: Record<string, string> = {
  'draft-saved': 'draft saved',
  deleted: 'deleted',
  published: 'published',
  opened: 'opened',
  merged: 'merged',
  closed: 'closed',
  'change-requested': 'change requested',
  updated: 'updated',
  stored: 'stored',
  created: 'created',
  promoted: 'promoted',
  paused: 'paused',
  resumed: 'resumed',
  'rolled-back': 'rolled back',
  started: 'started',
  retired: 'retired',
  sealed: 'sealed',
}

/** One line for an entry: the subject, what happened to it, and the version or reason the event carries. */
export function platformSummary(e: Pick<EvidenceEntry, 'data' | 'kind' | 'actor'>): string {
  const d = e.data ?? {}
  const { event, aggregate, aggregateId } = platformEvent(e)
  const [noun, verbKey] = event.includes('.') ? [event.slice(0, event.indexOf('.')), event.slice(event.indexOf('.') + 1)] : [aggregate ?? event, '']
  const verb = VERBS[verbKey] ?? verbKey.replace(/-/g, ' ')
  const name = str(d.name) ?? aggregateId
  const version = str(d.version) ?? (str(d.to) ? `${str(d.from) ?? 'new'} → ${str(d.to)}` : undefined)
  const kind = noun.replace(/-/g, ' ')
  const subject = [kind === 'workflow' || kind === 'provider' ? name : `${kind} ${name ?? ''}`.trim(), version].filter(Boolean).join(' ')
  const tail = [str(d.strategy) && `(${str(d.strategy)})`, str(d.reason) && `· ${str(d.reason)}`, str(d.type) && kind === 'credential' && `(${str(d.type)})`].filter(Boolean).join(' ')
  return [subject, verb, tail].filter(Boolean).join(' ').replace(/\s+/g, ' ').trim()
}

function eventTone(event: string): Tone {
  if (/rolled-back|deleted|closed/.test(event)) return 'critical'
  if (/paused|change-requested|opened/.test(event)) return 'warning'
  if (/published|merged|promoted|created|stored/.test(event)) return 'healthy'
  return 'neutral'
}

/** The event as a capsule, worded like the stream names it (`workflow.published`). */
export function PlatformEventTag({ event }: { event: string }) {
  return (
    <Tag size="sm" tone={eventTone(event)} mono>
      {event}
    </Tag>
  )
}

/** The subject of an entry, linked to its page when there is one. */
export function PlatformSubject({ entry }: { entry: EvidenceEntry }) {
  const { aggregate, aggregateId } = platformEvent(entry)
  const href = subjectHref(aggregate, aggregateId)
  if (!aggregateId) return <span className="text-tertiary">—</span>
  return (
    <span className="inline-flex min-w-0 max-w-full items-center gap-1.5">
      {aggregate && (
        <Tag size="sm" outline>
          {aggregate}
        </Tag>
      )}
      {href ? (
        <TextLink to={href} mono onClick={(e) => e.stopPropagation()}>
          {aggregateId}
        </TextLink>
      ) : (
        <span className="truncate font-mono text-caption text-primary">{aggregateId}</span>
      )}
    </span>
  )
}

/** The chain's verification, one line: intact or broken where, and how the seal fared. */
export function PlatformVerification({ verification, total, className }: { verification: AuditVerification; total: number; className?: string }) {
  const v = verification
  const sealText = v.seal === 'verified' ? 'the checkpoint signature verifies' : v.seal === 'mismatch' ? 'the checkpoint signature does not match' : v.seal === 'unverifiable' ? 'the checkpoint signature cannot be verified here' : 'no checkpoint yet'
  return (
    <div className={cx('flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border px-4 py-2.5 text-sm', v.valid ? 'border-healthy/30 bg-healthy-tint' : 'border-critical/30 bg-critical-tint', className)} role="status">
      <StatusDot kind={v.valid ? 'healthy' : 'critical'} />
      <span className={cx('font-medium', v.valid ? 'text-healthy' : 'text-critical')}>{v.valid ? 'Chain intact' : `Chain broken${v.brokenAt !== undefined ? ` at entry ${v.brokenAt}` : ''}`}</span>
      <span className="text-secondary">
        {v.entries} of {total} entries verify against their hashes · {sealText}
      </span>
      {v.seal === 'mismatch' && <Icon name="alert-triangle" size={14} className="text-critical" />}
    </div>
  )
}

/** The data of one entry with its hashes, for the details drawer. */
export function PlatformEntryDetails({ entry }: { entry: EvidenceEntry }) {
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-1 gap-3 text-caption sm:grid-cols-2">
        <div className="min-w-0">
          <dt className="text-tertiary">Hash</dt>
          <dd className="break-all font-mono text-primary">{entry.hash}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-tertiary">Previous</dt>
          <dd className="break-all font-mono text-secondary">{entry.previous || '— (first entry)'}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-tertiary">Actor</dt>
          <dd className="text-primary">{entry.actor ?? '—'}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-tertiary">At</dt>
          <dd className="font-mono text-primary">{entry.at}</dd>
        </div>
      </dl>
      <JsonView value={entry.data} name="data" rawToggle defaultExpanded={2} maxHeight="max-h-[50vh]" />
    </div>
  )
}
