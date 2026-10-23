import { useState } from 'react'
import { Icon, SkeletonRows, cx, plural, useToast } from '@iaf/ui'
import { StatusBadge, When } from '@iaf/console-kit'
import { useAccessRequestQueue } from '../../app/access'
import { ExplainedError, useCommand } from '../../app/commands'
import { useConsole } from '../../app/console'
import { Link, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { idOfWfrn, singularOf } from '../../lib/descriptors'
import { SEVERITY_WORDS, decisionKeys, driftApi, driftInWords, findingDecision, revertStateOf } from '../../lib/drift'
import { MOBILE_VIEWS, approvalHeadline, mobileApi, mobileViewOf, typeOfWfrn, type MobileView } from '../../lib/mobile'
import { isOpenStatus, usePoll } from '../../lib/poll'
import { invalidate, useQuery, type QueryResult } from '../../lib/queries'
import { roleLabel } from '../../lib/session'
import { useSubmissionKey } from '../../lib/submission'
import type { AccessRequest, BffApproval, BffJobSummary, Finding, Page } from '../../lib/types'
import { DecisionButton } from '../resource/DriftDecisions'
import { DecideBlock, MobileBar, MobileCard } from './parts'

/*
 * The phone view (WP 9.18, `#/m`): what waits for the person, the account's drift and their own
 * requests, one section at a time under a tab bar, drawn for 360 px first with touch targets of
 * at least 48 px. Approvals are decided with a comment through the same calls as the request
 * page and the access requests page; a drift finding is reverted in one tap, as on the Drift
 * page, and its request followed from here. The console's manifest makes it installable; nothing
 * here is cached for offline use.
 */

const FOLLOW_MS = 2_500
const SEVERITY_STATUS: Record<Finding['severity'], string> = { critical: 'failed', warning: 'waiting', info: 'queued' }

export default function MobilePage({ route }: PageProps) {
  const view = mobileViewOf(route.query.view)
  const { account, region, session } = useConsole()
  const scope = `${account.id}/${region.name}`
  const approvals = useQuery(['console-approvals', scope, session.user.userId], () => mobileApi.approvals({ status: 'pending', limit: 50 }), { refetchInterval: 15_000 })
  const access = useAccessRequestQueue()
  const findings = useQuery(['console-findings', 'mobile', scope], () => driftApi.findings.list({ status: 'open', limit: 50 }))
  const requests = useQuery(['jobs', 'mobile', scope, session.user.userId], () => bff.jobs.list({ requester: session.user.userId, limit: 20 }))

  const mine = (approvals.data?.items ?? []).filter((a) => a.canDecide)
  const counts: Record<MobileView, number> = {
    approvals: mine.length + access.toDecide.length,
    drift: findings.data?.items.length ?? 0,
    requests: (requests.data?.items ?? []).filter((j) => isOpenStatus(j.status)).length,
  }
  const titles: Record<MobileView, string> = { approvals: 'Waiting for you', drift: 'Drift', requests: 'Your requests' }

  return (
    <div className="mx-auto min-h-dvh w-full max-w-xl bg-surface-secondary pb-[env(safe-area-inset-bottom)]" data-mobile-view={view}>
      <MobileBar title={titles[view]} />
      <nav aria-label="Sections" className="sticky top-[4.25rem] z-10 grid grid-cols-3 gap-1 border-b border-stroke-light bg-surface px-2 py-1.5">
        {MOBILE_VIEWS.map((v) => (
          <Link
            key={v.id}
            to={pathFor('mobile', {}, { view: v.id === 'approvals' ? undefined : v.id })}
            replace
            aria-current={v.id === view ? 'page' : undefined}
            className={cx('flex min-h-12 flex-col items-center justify-center rounded-lg text-caption font-medium', v.id === view ? 'bg-accent-tint text-primary' : 'text-secondary hover:bg-surface-secondary')}
          >
            <span className="flex items-center gap-1.5">
              <Icon name={v.icon} size={16} />
              {v.label}
            </span>
            <span className="text-caption2 text-secondary">{counts[v.id] ? `${counts[v.id]} ${v.id === 'requests' ? 'open' : v.id === 'drift' ? 'open' : 'waiting'}` : 'none'}</span>
          </Link>
        ))}
      </nav>
      <main id="main" tabIndex={-1} className="px-4 py-4 outline-none">
        {view === 'approvals' && <ApprovalsView approvals={approvals} access={access} />}
        {view === 'drift' && <DriftView findings={findings} />}
        {view === 'requests' && <RequestsView requests={requests} />}
      </main>
    </div>
  )
}

/* ----------------------------------------------------------------------------
   Approvals: the requests' approvals and the access requests that name the person
   ------------------------------------------------------------------------- */

function ApprovalsView({ approvals, access }: { approvals: QueryResult<Page<BffApproval>>; access: ReturnType<typeof useAccessRequestQueue> }) {
  const items = approvals.data?.items ?? []
  const mine = items.filter((a) => a.canDecide)
  const others = items.filter((a) => !a.canDecide)
  if (approvals.loading || access.loading) return <SkeletonRows n={3} rowClassName="h-28" />
  if (approvals.error) return <ExplainedError error={approvals.error} what="Read the approvals waiting for you" onRetry={() => void approvals.refetch()} compact />
  const none = !mine.length && !access.toDecide.length
  return (
    <div className="space-y-6">
      <section aria-labelledby="m-changes">
        <h2 id="m-changes" className="mb-2 text-sm font-semibold text-primary">
          Changes to approve
        </h2>
        {mine.length ? (
          <ul className="space-y-3" aria-label="Changes to approve">
            {mine.map((a) => (
              <JobApprovalCard key={a.approvalId} approval={a} onDone={() => void approvals.refetch()} />
            ))}
          </ul>
        ) : (
          <p className="rounded-xl border border-dashed border-stroke-light bg-surface px-4 py-3 text-sm text-secondary">{none ? 'Nothing waits for you. You will see a change here when its request asks your group to approve it.' : 'No change waits for you.'}</p>
        )}
      </section>
      <section aria-labelledby="m-access">
        <h2 id="m-access" className="mb-2 text-sm font-semibold text-primary">
          Access requests
        </h2>
        {access.toDecide.length ? (
          <ul className="space-y-3" aria-label="Access requests to decide">
            {access.toDecide.map((r) => (
              <AccessRequestCard key={r.requestId} request={r} onDone={() => void access.refetch()} />
            ))}
          </ul>
        ) : (
          <p className="rounded-xl border border-dashed border-stroke-light bg-surface px-4 py-3 text-sm text-secondary">No access request names you.</p>
        )}
      </section>
      {others.length > 0 && (
        <section aria-labelledby="m-others">
          <h2 id="m-others" className="mb-2 text-sm font-semibold text-primary">
            Your requests waiting for others
          </h2>
          <ul className="space-y-2">
            {others.map((a) => (
              <OtherApproval key={a.approvalId} approval={a} />
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function JobApprovalCard({ approval: a, onDone }: { approval: BffApproval; onDone: () => void }) {
  const { types } = useConsole()
  const toast = useToast()
  const cmd = useCommand()
  const keyFor = useSubmissionKey()
  const headline = approvalHeadline(a, types)
  const decide = async (decision: 'approve' | 'reject', comment: string | undefined) => {
    const body = { decision, ...(comment ? { comment } : {}) }
    const done = await cmd.run(decision === 'approve' ? 'Approve' : 'Reject', () => bff.jobs.decideApprovals(a.jobId ?? '', body, keyFor({ approval: a.approvalId, ...body })), { invalidates: ['jobs', 'console-approvals'] })
    if (done) {
      toast.success(decision === 'approve' ? 'Approved' : 'Rejected', decision === 'approve' ? `${headline} runs now.` : `${headline} will not run.`)
      onDone()
    }
  }
  return (
    <MobileCard data-approval={a.approvalId}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-base font-semibold text-primary">{headline}</p>
          <p className="mt-0.5 text-caption text-secondary">
            {a.group ? `For ${a.group}` : 'Waiting for an approver'}
            {a.requestedAt && (
              <>
                {' · asked '}
                <When at={a.requestedAt} />
              </>
            )}
          </p>
        </div>
        <StatusBadge size="sm" status="waiting" label="Waiting" />
      </div>
      {a.jobId && (
        <Link to={pathFor('mobile-request', { id: a.jobId })} className="mt-2 inline-flex min-h-11 items-center gap-1 text-sm font-medium text-accent">
          See the request
          <Icon name="chevron-right" size={14} />
        </Link>
      )}
      <DecideBlock subject={headline} idPrefix={`m-approval-${a.approvalId}`} busy={cmd.busy} rejectMessage="The request ends at its approval step and nothing runs." onDecide={decide} />
    </MobileCard>
  )
}

function OtherApproval({ approval: a }: { approval: BffApproval }) {
  const { types } = useConsole()
  return (
    <li>
      <Link to={pathFor('mobile-request', { id: a.jobId ?? '' })} className="flex min-h-12 items-center gap-3 rounded-xl border border-stroke-light bg-surface px-4 py-2 text-sm">
        <span className="min-w-0 flex-1 truncate text-primary">{approvalHeadline(a, types)}</span>
        <span className="shrink-0 text-caption text-secondary">{a.group ? `Waits for ${a.group}` : 'Waiting'}</span>
        <Icon name="chevron-right" size={14} className="shrink-0 text-tertiary" />
      </Link>
    </li>
  )
}

function AccessRequestCard({ request: r, onDone }: { request: AccessRequest; onDone: () => void }) {
  const toast = useToast()
  const cmd = useCommand()
  const who = r.requester.name ?? r.requester.email ?? 'Someone'
  const headline = `${who} asks for ${roleLabel(r.role)}`
  const decide = async (decision: 'approve' | 'reject', comment: string | undefined) => {
    const done = await cmd.run(decision === 'approve' ? 'Approve the access request' : 'Reject the access request', () => bff.accessRequests.decide(r.requestId, { decision, ...(comment ? { comment } : {}) }), { invalidates: ['access-requests'] })
    if (done) {
      toast.success(decision === 'approve' ? 'Approved' : 'Rejected', decision === 'approve' ? `${who} now holds ${roleLabel(r.role)}.` : `${who} keeps the access they had.`)
      onDone()
    }
  }
  return (
    <MobileCard data-access-request={r.requestId}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-base font-semibold text-primary">{headline}</p>
          <p className="mt-0.5 text-caption text-secondary">
            To run <span className="font-mono">{r.action}</span>
            {' · asked '}
            <When at={r.requestedAt} />
          </p>
        </div>
        <StatusBadge size="sm" status="waiting" label="Waiting" />
      </div>
      {r.reason && <p className="mt-2 text-sm text-primary">“{r.reason}”</p>}
      <DecideBlock subject={headline} idPrefix={`m-access-${r.requestId}`} busy={cmd.busy} rejectMessage={`${who} keeps the access they have now.`} onDecide={decide} />
    </MobileCard>
  )
}

/* ----------------------------------------------------------------------------
   Drift: the open findings, each reverted in one tap
   ------------------------------------------------------------------------- */

function DriftView({ findings }: { findings: QueryResult<Page<Finding>> }) {
  const items = findings.data?.items ?? []
  usePoll(items.some((f) => f.revertStatus === 'running'), () => invalidate('console-findings'), FOLLOW_MS)
  if (findings.loading) return <SkeletonRows n={3} rowClassName="h-28" />
  if (findings.error) return <ExplainedError error={findings.error} what="Read the drift findings" onRetry={() => void findings.refetch()} compact />
  if (!items.length) return <p className="rounded-xl border border-dashed border-stroke-light bg-surface px-4 py-3 text-sm text-secondary">Nothing has drifted: every watched resource matches what was asked for.</p>
  return (
    <section aria-label="Open drift findings">
      <p className="mb-3 text-sm text-secondary">{plural(items.length, 'resource')} differ from what was asked for.</p>
      <ul className="space-y-3">
        {items.map((f) => (
          <FindingCard key={f.findingId} finding={f} />
        ))}
      </ul>
    </section>
  )
}

function FindingCard({ finding: f }: { finding: Finding }) {
  const { types, decide } = useConsole()
  const toast = useToast()
  const cmd = useCommand()
  const [jobId, setJobId] = useState<string | null>(null)
  const type = typeOfWfrn(types, f.wfrn)
  const id = idOfWfrn(f.wfrn) ?? f.wfrn
  const decision = findingDecision(type ? decide(type, 'update') : { allowed: false }, f, 'revert')
  const state = revertStateOf(f)
  const running = state === 'running'
  const follow = f.revertJobId ?? jobId
  const whyId = `m-finding-${f.findingId}-why`
  const revert = async () => {
    const res = await cmd.run('Revert to what was asked for', () => driftApi.findings.revert(f.findingId), { invalidates: type ? decisionKeys(type) : ['console-findings'] })
    if (res) {
      setJobId(res.jobId)
      toast.success('Reverting', `${id} is being put back to what was asked for.`)
    }
  }
  return (
    <MobileCard data-finding={f.findingId}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-base font-semibold text-primary">{id}</p>
          <p className="text-caption text-secondary">
            {type ? singularOf(type.title) : 'Resource'} · found <When at={f.openedAt} />
          </p>
        </div>
        <StatusBadge size="sm" status={SEVERITY_STATUS[f.severity]} label={SEVERITY_WORDS[f.severity]} />
      </div>
      <p className="mt-2 text-sm text-primary">{driftInWords(f, type)}.</p>
      {running ? (
        <div className="mt-3 flex min-h-12 items-center justify-between gap-3 rounded-lg bg-accent-tint px-3 text-sm" data-revert="running" role="status">
          <span className="font-medium text-primary">Reverting</span>
          {follow && (
            <Link to={pathFor('mobile-request', { id: follow })} className="flex min-h-11 items-center font-medium text-accent">
              Follow the request
            </Link>
          )}
        </div>
      ) : (
        <>
          {state === 'failed' && <p className="mt-2 text-caption text-critical">The last revert did not finish; you can try again.</p>}
          <DecisionButton decision={decision} verb="revert" variant="primary" size="md" icon="refresh" loading={cmd.busy} onClick={() => void revert()} describedBy={decision.why ? whyId : undefined} className="mt-3 h-12 w-full justify-center text-base">
            Revert to what was asked for
          </DecisionButton>
          {decision.why && (
            <p id={whyId} className="mt-2 text-caption text-secondary">
              {decision.why}
            </p>
          )}
        </>
      )}
      {type && (
        <Link to={`${pathFor('resource', { service: type.service, type: type.name, wfrn: f.wfrn })}?tab=drift`} className="mt-1 inline-flex min-h-11 items-center gap-1 text-sm text-accent">
          Open in the full console
        </Link>
      )}
    </MobileCard>
  )
}

/* ----------------------------------------------------------------------------
   Requests: the person's own, with their status
   ------------------------------------------------------------------------- */

function RequestsView({ requests }: { requests: QueryResult<Page<BffJobSummary>> }) {
  const items = requests.data?.items ?? []
  usePoll(items.some((j) => isOpenStatus(j.status)), () => void requests.refetch(), FOLLOW_MS)
  if (requests.loading) return <SkeletonRows n={4} rowClassName="h-16" />
  if (requests.error) return <ExplainedError error={requests.error} what="Read your requests" onRetry={() => void requests.refetch()} compact />
  if (!items.length) return <p className="rounded-xl border border-dashed border-stroke-light bg-surface px-4 py-3 text-sm text-secondary">You have asked for nothing in this account yet.</p>
  return (
    <ul className="space-y-2" aria-label="Your requests">
      {items.map((j) => (
        <li key={j.jobId}>
          <Link to={pathFor('mobile-request', { id: j.jobId })} className="flex min-h-14 items-center gap-3 rounded-xl border border-stroke-light bg-surface px-4 py-2" data-job={j.jobId}>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-primary">{j.title ?? j.workflow}</span>
              <span className="block text-caption text-secondary">{j.startedAt ? <When at={j.startedAt} /> : 'Not started'}</span>
            </span>
            <StatusBadge size="sm" status={j.status} />
            <Icon name="chevron-right" size={14} className="shrink-0 text-tertiary" />
          </Link>
        </li>
      ))}
    </ul>
  )
}
