import { Icon, Spinner, cx, longDate, type IconName } from '@iaf/ui'
import { When } from '@iaf/console-kit'
import { Link, pathFor } from '../../app/router'
import type { ConsoleType } from '../../lib/descriptors'
import type { ConsoleResource } from '../../lib/types'
import { useJobExplanation } from '../explain/FailureExplainer'

/*
 * The line above a resource's facts while something is happening to it: a request in flight, one
 * waiting for its approver, one that failed, or the resource gone. It speaks to screen readers as
 * it changes (a polite live region), and the colour stays in the glyph, never in the words.
 */

type LastJob = NonNullable<ConsoleResource['lastJob']>

/** The words for what a request does, from the workflow it runs: "Create VM", "VM restart", else the job's own title. */
export function jobTitle(type: ConsoleType, job: Pick<LastJob, 'workflow' | 'title'>): string {
  if (type.create && job.workflow === type.create.workflow) return type.create.title
  const action = type.actions.find((a) => a.workflow === job.workflow)
  if (action) return action.title
  return job.title ?? 'The last change'
}

interface Look {
  icon: IconName | 'spinner'
  tint: string
  glyph: string
  title: string
  detail: string
}

function lookOf(type: ConsoleType, job: LastJob, name: string): Look | null {
  const what = jobTitle(type, job)
  switch (job.status) {
    case 'waiting-approval':
      return { icon: 'approval', tint: 'border-warning/40 bg-warning-tint/40', glyph: 'text-warning', title: `${what} is waiting for an approver`, detail: `It runs on ${name} as soon as someone in the approving group says yes.` }
    case 'queued':
    case 'running':
      return { icon: 'spinner', tint: 'border-accent/30 bg-accent-tint/50', glyph: 'text-accent', title: `${what} is running`, detail: `The page follows it and updates when it ends.` }
    case 'needs-attention':
      return { icon: 'alert-triangle', tint: 'border-warning/40 bg-warning-tint/40', glyph: 'text-warning', title: `${what} needs attention`, detail: 'A step stopped and waits for someone to decide how to go on.' }
    case 'failed':
      return { icon: 'alert-triangle', tint: 'border-critical/40 bg-critical-tint/40', glyph: 'text-critical', title: `${what} failed`, detail: 'Nothing else is running on it. The request says which step failed and why.' }
    default:
      return null
  }
}

export function JobBanner({ type, job, name, className }: { type: ConsoleType; job: LastJob | undefined; name: string; className?: string }) {
  const look = job ? lookOf(type, job, name) : null
  // a failed request says why in a line, and that the request page has a fix to try (phase 9)
  const why = useJobExplanation(job?.jobId ?? '', job?.status).data
  const detail = why ? `${why.cause.title}.${why.fix ? ' The request has a fix to try.' : ''}` : look?.detail
  return (
    <div role="status" aria-live="polite" className={className}>
      {job && look && (
        <div className={cx('flex flex-wrap items-start gap-3 rounded-lg border px-4 py-3 text-sm', look.tint)} data-job-status={job.status}>
          <span className={cx('mt-0.5 shrink-0', look.glyph)}>{look.icon === 'spinner' ? <Spinner size={16} /> : <Icon name={look.icon} size={16} />}</span>
          <div className="min-w-0 flex-1">
            <p className="font-medium text-primary">{look.title}</p>
            <p className="mt-0.5 text-caption text-secondary">
              {detail} Started <When at={job.startedAt} />.
            </p>
          </div>
          <Link to={pathFor('request', { id: job.jobId })} className="shrink-0 self-center text-caption font-medium text-accent transition-opacity hover:opacity-70">
            {why?.fix ? 'See the fix' : job.status === 'failed' ? 'See what went wrong' : 'Follow the request'}
            <span className="sr-only"> {job.jobId}</span>
          </Link>
        </div>
      )}
    </div>
  )
}

/** What a deleted resource's page says above its facts. */
/** What a 410 says about a deleted resource (hub Tombstone): the BFF nests it under `detail`, the state service answers it whole. */
export interface Tombstone {
  wfrn?: string
  deletedAt?: string
  tombstoneUntil?: string
  lastVersion?: number
}

export function tombstoneOf(body: unknown): Tombstone | undefined {
  if (!body || typeof body !== 'object') return undefined
  const b = body as Record<string, unknown>
  const t = (b.detail && typeof b.detail === 'object' ? b.detail : b) as Record<string, unknown>
  const text = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
  const out: Tombstone = { wfrn: text(t.wfrn), deletedAt: text(t.deletedAt), tombstoneUntil: text(t.tombstoneUntil), lastVersion: typeof t.lastVersion === 'number' ? t.lastVersion : undefined }
  return Object.values(out).some((v) => v !== undefined) ? out : undefined
}

export function DeletedBanner({ noun, at, until, className }: { noun: string; at?: string; until?: string; className?: string }) {
  return (
    <div className={cx('flex items-start gap-3 rounded-lg border border-stroke-light bg-surface-secondary px-4 py-3 text-sm', className)} data-deleted="true">
      <Icon name="trash" size={16} className="mt-0.5 shrink-0 text-tertiary" />
      <div className="min-w-0">
        <p className="font-medium text-primary">This {noun} was deleted{at ? <> <When at={at} /></> : null}</p>
        <p className="mt-0.5 text-caption text-secondary">
          {until ? (
            <>
              Its record and activity stay readable until <time dateTime={until}>{longDate(until)}</time>; nothing can run on it any more.
            </>
          ) : (
            'Its record and activity stay readable; nothing can run on it any more.'
          )}
        </p>
      </div>
    </div>
  )
}
