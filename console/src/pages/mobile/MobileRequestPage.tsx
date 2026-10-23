import { Icon, SkeletonRows, useToast } from '@iaf/ui'
import { StatusBadge, When } from '@iaf/console-kit'
import { ExplainedError, useCommand } from '../../app/commands'
import { Link, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { isOpenStatus, usePoll } from '../../lib/poll'
import { useQuery } from '../../lib/queries'
import { useSubmissionKey } from '../../lib/submission'
import { DecideBlock, MobileBar } from './parts'

/*
 * One request on the phone (`#/m/requests/:id`): its status in a word, its steps, the approvals
 * it waits on with Approve and Reject for the person who may decide them, and a link to the full
 * request page. It follows the request while it runs.
 */

const STEP_WORDS: Record<string, string> = { succeeded: 'Done', running: 'Running', failed: 'Failed', skipped: 'Skipped', waiting: 'Waiting', pending: 'Not yet', queued: 'Not yet' }

export default function MobileRequestPage({ route }: PageProps) {
  const id = route.params.id
  const toast = useToast()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const job = useQuery(['jobs', 'mobile-request', id], () => bff.jobs.get(id))
  const j = job.data
  usePoll(isOpenStatus(j?.status), () => void job.refetch())
  const back = { to: pathFor('mobile', {}, { view: 'requests' }), label: 'Back to your requests' }
  const mine = (j?.approvals ?? []).filter((a) => a.status === 'pending' && a.canDecide)
  const waiting = (j?.approvals ?? []).filter((a) => a.status === 'pending')

  const decide = async (decision: 'approve' | 'reject', comment: string | undefined) => {
    const body = { decision, ...(comment ? { comment } : {}) }
    const done = await cmd.run(decision === 'approve' ? 'Approve' : 'Reject', () => bff.jobs.decideApprovals(id, body, keyFor({ job: id, ...body })), { invalidates: ['jobs', 'console-approvals'] })
    if (done) {
      toast.success(decision === 'approve' ? 'Approved' : 'Rejected')
      void job.refetch()
    }
  }

  return (
    <div className="mx-auto min-h-dvh w-full max-w-xl bg-surface-secondary pb-[env(safe-area-inset-bottom)]">
      <MobileBar title="Request" back={back} />
      <main id="main" tabIndex={-1} className="space-y-4 px-4 py-4 outline-none">
        {job.loading ? (
          <SkeletonRows n={3} rowClassName="h-16" />
        ) : job.error || !j ? (
          <ExplainedError error={job.error} what="Read this request" onRetry={() => void job.refetch()} compact />
        ) : (
          <>
            <section className="rounded-xl border border-stroke-light bg-surface p-4" aria-labelledby="m-request-title">
              <h2 id="m-request-title" className="text-base font-semibold text-primary">
                {j.title ?? j.workflow}
              </h2>
              <div className="mt-2 flex flex-wrap items-center gap-2" data-job-status={j.status} aria-live="polite">
                <StatusBadge status={j.status} />
                {j.startedAt && (
                  <span className="text-caption text-secondary">
                    Started <When at={j.startedAt} />
                  </span>
                )}
              </div>
              {j.status === 'needs-attention' && <p className="mt-2 text-sm text-primary">A step failed and the request is paused. Open it in the full console to retry, skip or stop it.</p>}
            </section>

            {waiting.length > 0 && (
              <section className="rounded-xl border border-stroke-light bg-surface p-4" aria-labelledby="m-request-approvals">
                <h2 id="m-request-approvals" className="text-sm font-semibold text-primary">
                  Approval
                </h2>
                <p className="mt-1 text-sm text-secondary">Waits for {waiting[0].group ?? 'an approver'}.</p>
                {mine.length > 0 ? <DecideBlock subject={j.title ?? j.workflow} idPrefix={`m-request-${id}`} busy={cmd.busy} rejectMessage="The request ends at its approval step and nothing runs." onDecide={decide} /> : <p className="mt-2 text-caption text-secondary">Someone in that group decides it; it is not yours to decide.</p>}
              </section>
            )}

            {(j.steps?.length ?? 0) > 0 && (
              <section className="rounded-xl border border-stroke-light bg-surface p-4" aria-labelledby="m-request-steps">
                <h2 id="m-request-steps" className="mb-2 text-sm font-semibold text-primary">
                  Steps
                </h2>
                <ol className="divide-y divide-stroke-light">
                  {j.steps!.map((s) => (
                    <li key={s.id} className="flex min-h-11 items-center justify-between gap-3 py-2 text-sm">
                      <span className="min-w-0 truncate text-primary">{s.name ?? s.id}</span>
                      <span className="shrink-0 text-caption text-secondary">{STEP_WORDS[s.status] ?? s.status}</span>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            <Link to={pathFor('request', { id })} className="flex min-h-12 items-center justify-center gap-1.5 rounded-xl border border-stroke-light bg-surface text-sm font-medium text-accent">
              Open in the full console
              <Icon name="external" size={14} />
            </Link>
          </>
        )}
      </main>
    </div>
  )
}
