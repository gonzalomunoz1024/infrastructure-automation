import { useMemo, useState } from 'react'
import { Button, EmptyState, PageHeader, ProgressBar, Segmented, SkeletonRows, relativeTime, type ProgressTone } from '@iaf/ui'
import { ErrorState, Section, StatusBadge } from '@iaf/console-kit'
import { useConsole } from '../../app/console'
import { ActionButton } from '../../app/Refusal'
import { navigate, pathFor, type PageProps } from '../../app/router'
import { bff } from '../../lib/bff'
import { ALLOWED } from '../../lib/permissions'
import { useQuery } from '../../lib/queries'
import { approverWords, cooldownUntil, decidedByWords, quotaTitle, quotasApi, requestStatusOf, ruleCovering, ruleSentence, type ConsoleQuotaRequest, type QuotaRequestVerb } from '../../lib/quotas'
import type { Quota, QuotaRequestStatus } from '../../lib/types'
import { personLabel, useIamWrite } from '../iam/common'
import { DecideQuotaDialog } from './DecideQuotaDialog'
import { QUOTA_REQUESTS_KEY, QUOTA_RULES_KEY, RequestIncreaseDialog } from './RequestIncreaseDialog'

/*
 * Service Quotas (WP 7.2, 7.6), like AWS's: every quota of the account with what is used, the
 * limit and where it comes from, and the rule that raises it at once (or that the platform team
 * decides); a request for more from any quota, which a rule approves on the spot or sends to the
 * platform team; and the account's requests with their status, which the platform team decides
 * here (never their own). `?request=<quota key>` opens the request dialog, so a refused create
 * links straight to it; `?status=` filters the requests. Each write follows the BFF's `can` on
 * the request, with the refusal in words when it is not offered.
 */

const n = (v: number) => v.toLocaleString('en-US')

type Filter = 'all' | QuotaRequestStatus
const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Waiting' },
  { value: 'approved', label: 'Approved' },
  { value: 'rejected', label: 'Rejected' },
  { value: 'withdrawn', label: 'Withdrawn' },
]

function toneOf(q: Quota): ProgressTone {
  if (q.limit <= 0) return 'accent'
  const ratio = q.used / q.limit
  return ratio >= 1 ? 'critical' : ratio >= 0.8 ? 'warning' : 'accent'
}

function sourceWords(q: Quota): string {
  return q.source === 'platform' ? 'Set by the platform for the account' : q.source === 'override' ? 'Raised for this account' : 'The default for every account'
}

/** Why the BFF does not offer a verb on a request, in its words, as a sentence. */
function notOffered(r: ConsoleQuotaRequest, verb: QuotaRequestVerb): string {
  const reason = r.refusals?.[verb]?.reason ?? 'Not offered on this request'
  return /[.!?]$/.test(reason) ? reason : `${reason}.`
}

export default function ServiceQuotasPage({ route }: PageProps) {
  const { account, types, me } = useConsole()
  const write = useIamWrite()
  const filter: Filter = FILTERS.some((f) => f.value === route.query.status) ? (route.query.status as Filter) : 'all'
  const quotas = useQuery(['iam', 'quotas', account.id], () => quotasApi.list())
  const rules = useQuery(QUOTA_RULES_KEY, () => quotasApi.rules())
  const requests = useQuery(QUOTA_REQUESTS_KEY(account.id), () => quotasApi.requests({ limit: 200 }))
  const users = useQuery(['iam', 'users', account.id], () => bff.iam.users.list({ limit: 200 }))
  const [deciding, setDeciding] = useState<{ request: ConsoleQuotaRequest; decision: 'approve' | 'reject' } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const all = useMemo(() => requests.data?.items ?? [], [requests.data])
  const shown = filter === 'all' ? all : all.filter((r) => r.status === filter)
  const titleOf = (key: string) => quotaTitle(key, types)
  const nameOf = (userId: string) => {
    if (userId === me.id) return 'you'
    const u = users.data?.items.find((x) => x.userId === userId)
    return u ? personLabel(u) : userId
  }
  const Name = (userId: string) => {
    const name = nameOf(userId)
    return name === 'you' ? 'You' : name
  }

  const asking = route.query.request ? quotas.data?.items.find((q) => q.key === route.query.request) : undefined
  const setQuery = (next: Record<string, string | undefined>) => navigate(pathFor('service-quotas', {}, { ...route.query, ...next }, { accountId: route.accountId }), { replace: true })
  const waiting = all.filter((r) => r.status === 'open').length

  const withdraw = async (r: ConsoleQuotaRequest) => {
    setBusy(r.requestId)
    await write('Withdraw a quota request', () => quotasApi.withdraw(r.requestId).then(() => true), { title: 'Request withdrawn', message: `${titleOf(r.quotaKey)} keeps its limit of ${n(r.currentLimit)}.` })
    setBusy(null)
  }

  return (
    <>
      <PageHeader
        title="Service Quotas"
        subtitle={`What ${account.name} may hold and run, how much is used, and how a limit is raised: at once by the organization’s rules, or by ${approverWords(rules.data?.approver)}.`}
      />

      <div className="space-y-10">
        <Section title="Quotas" description="Each limit, what it is used by, and the rule behind it.">
          {quotas.loading ? (
            <SkeletonRows n={4} rowClassName="h-24" />
          ) : quotas.error ? (
            <ErrorState error={quotas.error} title="Could not load the quotas" onRetry={() => void quotas.refetch()} />
          ) : !quotas.data?.items.length ? (
            <EmptyState icon="chart" title="No quotas apply" message="This account has no limits to show." />
          ) : (
            <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Quotas of the account">
              {quotas.data.items.map((q) => {
                const title = titleOf(q.key)
                const rule = ruleCovering(rules.data, q.key)
                const until = rule ? cooldownUntil(rule, all, q.key) : undefined
                const pending = q.pendingRequestId ? all.find((r) => r.requestId === q.pendingRequestId) : undefined
                return (
                  <li key={q.key} className="grid grid-cols-1 gap-3 px-4 py-4 md:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_auto] md:items-center" data-quota={q.key}>
                    <div className="min-w-0">
                      <ProgressBar label={`${title}: ${n(q.used)} of ${n(q.limit)}${q.burst ? ` (bursts to ${n(q.burst)})` : ''}`} showLabel value={q.used} max={q.limit} tone={toneOf(q)} size="sm" />
                      <p className="mt-1 text-caption2 text-tertiary">{sourceWords(q)}</p>
                    </div>
                    <p className="min-w-0 text-caption text-secondary" data-rule={rule?.name ?? 'none'}>
                      {rule ? (
                        <>
                          <span className="font-medium text-primary">{rule.name}. </span>
                          {ruleSentence(rule)}
                          {until && ` It raised this quota lately: until ${new Date(until).toLocaleDateString('en-GB', { day: 'numeric', month: 'long' })}, ${approverWords(rules.data?.approver)} decides.`}
                        </>
                      ) : rules.data ? (
                        `No rule raises it at once: ${approverWords(rules.data.approver)} decides every increase.`
                      ) : null}
                    </p>
                    <div className="flex flex-wrap items-center gap-2 md:justify-end">
                      {q.pendingRequestId ? (
                        <span className="text-caption text-secondary" data-quota-pending={q.pendingRequestId}>
                          {pending ? `${n(pending.requestedLimit)} asked by ${nameOf(pending.requestedBy)}, waiting.` : 'An increase is waiting.'}
                        </span>
                      ) : (
                        <ActionButton decision={ALLOWED} what={`Request more ${title.toLowerCase()}`} size="sm" icon="plus" verb="request-increase" onClick={() => setQuery({ request: q.key })}>
                          Request increase
                        </ActionButton>
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </Section>

        <Section
          title="Requests"
          description={waiting ? `${waiting === 1 ? 'One request waits' : `${waiting} requests wait`} for ${approverWords(rules.data?.approver)}.` : 'Every increase asked for in this account, newest first.'}
          actions={<Segmented size="sm" ariaLabel="Show requests" value={filter} onChange={(v) => setQuery({ status: v === 'all' ? undefined : v })} options={FILTERS.map((f) => ({ value: f.value, label: f.label }))} />}
        >
          {requests.loading ? (
            <SkeletonRows n={3} rowClassName="h-20" />
          ) : requests.error ? (
            <ErrorState error={requests.error} title="Could not load the requests" onRetry={() => void requests.refetch()} />
          ) : shown.length === 0 ? (
            <EmptyState compact icon="history" title={filter === 'all' ? 'No requests yet' : 'None here'} message={filter === 'all' ? 'Ask for more from any quota above.' : 'No request of this account has this status.'} />
          ) : (
            <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="Quota requests">
              {shown.map((r) => {
                const title = titleOf(r.quotaKey)
                const status = requestStatusOf(r)
                const by = decidedByWords(r, nameOf)
                return (
                  <li key={r.requestId} className="space-y-2 px-4 py-4" data-request={r.requestId} data-request-status={r.status}>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="min-w-0 text-sm font-medium text-primary">
                        {title}: {n(r.currentLimit)} to {n(r.requestedLimit)}
                      </p>
                      <StatusBadge status={status.status} label={status.label} size="sm" />
                    </div>
                    <p className="text-caption text-secondary">
                      {Name(r.requestedBy)} asked {relativeTime(r.requestedAt)}: “{r.reason}”
                    </p>
                    {r.status !== 'open' && by && (
                      <p className="text-caption text-secondary" data-decided>
                        {r.status === 'approved' ? `Approved by ${by}` : r.status === 'rejected' ? `Rejected by ${by}` : `Withdrawn by ${by}`}
                        {r.decidedAt ? `, ${relativeTime(r.decidedAt)}` : ''}
                        {r.status === 'approved' && r.grantedLimit !== undefined && r.grantedLimit !== r.requestedLimit ? `; ${n(r.grantedLimit)} granted` : ''}
                        {r.comment ? `: “${r.comment}”` : '.'}
                      </p>
                    )}
                    {r.status === 'open' && (
                      <div className="flex flex-wrap items-center gap-2 pt-1">
                        {r.can?.includes('decide') ? (
                          <>
                            <Button size="sm" variant="primary" icon="check" data-verb="approve" onClick={() => setDeciding({ request: r, decision: 'approve' })}>
                              Approve
                            </Button>
                            <Button size="sm" icon="close" data-verb="reject" onClick={() => setDeciding({ request: r, decision: 'reject' })}>
                              Reject
                            </Button>
                          </>
                        ) : (
                          <p className="text-caption text-tertiary" data-not-offered="decide">
                            {notOffered(r, 'decide')}
                          </p>
                        )}
                        {r.can?.includes('withdraw') && (
                          <Button size="sm" variant="ghost" loading={busy === r.requestId} onClick={() => void withdraw(r)} aria-label={`Withdraw the request for more ${title.toLowerCase()}`}>
                            Withdraw
                          </Button>
                        )}
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </Section>
      </div>

      {asking && <RequestIncreaseDialog quota={asking} title={titleOf(asking.key)} onClose={() => setQuery({ request: undefined })} />}
      {deciding && <DecideQuotaDialog request={deciding.request} decision={deciding.decision} title={titleOf(deciding.request.quotaKey)} requester={Name(deciding.request.requestedBy)} onClose={() => setDeciding(null)} />}
    </>
  )
}
