import { useMemo, useState } from 'react'
import { Button, Icon, PageHeader, Skeleton, SkeletonText, Table, Tabs, Tag, Textarea, Field, plural, type TableColumn } from '@iaf/ui'
import { CopyableId, ErrorState, Facts, Section, When } from '@iaf/console-kit'
import { mayDecide, useAccessRequestQueue } from '../../app/access'
import { useConsole } from '../../app/console'
import { Link, href, navigate, pathFor, type PageProps } from '../../app/router'
import { bff, loadSession } from '../../lib/bff'
import { layerDescription, layerLabel, splitAction, verbTitle } from '../../lib/permissions'
import { invalidate, useQuery } from '../../lib/queries'
import { roleLabel } from '../../lib/session'
import type { AccessRequest } from '../../lib/types'
import { RequestStatusTag, RoleTags, personLabel } from './common'

/*
 * Access requests: what Request access sends from a refusal. Everyone follows their own here.
 * Whoever reads the account's queue (admins, auditors, the approver groups: access-request:list)
 * also sees everything in the account, and what waits for them: a request names its approvers
 * (the admins and the members of groups granted access-request:decide), and an approval puts the
 * requester in the group that grants the role they asked for.
 */

type Tab = 'mine' | 'decide' | 'account'
const TABS: readonly Tab[] = ['decide', 'mine', 'account']

export default function IamAccessRequestsPage({ route }: PageProps) {
  const { account, me } = useConsole()
  const queue = useAccessRequestQueue()
  const wanted = TABS.includes(route.query.tab as Tab) ? (route.query.tab as Tab) : undefined
  const [picked, setPicked] = useState<Tab | null>(null)
  // the queue's tabs only for those who read it; everyone else has their own requests. Until the
  // queue has answered, nothing is asked, so the page does not load one tab and then another
  const known = queue.readable !== null || !!queue.error
  // where the page opens, once the queue is known: what waits for this person, else their own
  const [landing, setLanding] = useState<Tab | null>(null)
  if (landing === null && known) setLanding(queue.readable && queue.toDecide.length ? 'decide' : 'mine')
  const tab: Tab = queue.readable ? (picked ?? wanted ?? landing ?? 'decide') : 'mine'
  const list = useQuery(
    ['access-requests', 'list', account.id, tab, me.id],
    () => bff.accessRequests.list(tab === 'mine' ? { requester: me.id, limit: 200 } : tab === 'decide' ? { status: 'pending', limit: 200 } : { limit: 200 }),
    { enabled: known },
  )
  const rows = useMemo(() => {
    const items = list.data?.items ?? []
    return [...(tab === 'decide' ? items.filter((r) => mayDecide(r, me.id)) : items)].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt))
  }, [list.data, tab, me.id])

  const columns: TableColumn<AccessRequest>[] = [
    {
      key: 'requester',
      header: 'Requester',
      sortable: true,
      accessor: (r) => personLabel(r.requester),
      render: (r) => (
        <span className="min-w-0">
          <span className="block truncate font-medium text-primary">{personLabel(r.requester)}</span>
          <span className="block truncate font-mono text-caption2 text-tertiary">{r.requestId}</span>
        </span>
      ),
    },
    { key: 'role', header: 'Asks for', sortable: true, accessor: (r) => roleLabel(r.role), render: (r) => <RoleTags roles={[r.role]} link />, width: '7rem' },
    { key: 'action', header: 'To', accessor: (r) => r.action, render: (r) => <span className="font-mono text-caption text-primary">{r.action}</span>, width: '11rem' },
    { key: 'reason', header: 'Reason', accessor: (r) => r.reason ?? '', render: (r) => <span className="line-clamp-2 text-caption text-secondary">{r.reason ?? '—'}</span> },
    { key: 'status', header: 'Status', sortable: true, accessor: (r) => r.status, render: (r) => <RequestStatusTag status={r.status} />, width: '7rem' },
    { key: 'requestedAt', header: 'Requested', sortable: true, accessor: (r) => r.requestedAt, render: (r) => <When at={r.requestedAt} className="text-caption" />, width: '8rem' },
    { key: 'decidedBy', header: 'Decided by', accessor: (r) => r.decidedBy ?? '', render: (r) => (r.decidedBy ? <span className="font-mono text-caption">{r.decidedBy}</span> : <span className="text-caption text-tertiary">—</span>), width: '8rem' },
  ]

  return (
    <>
      <PageHeader title="Access requests" subtitle={queue.readable ? `What people asked for in ${account.name}, and what waits for you to decide.` : 'What you asked for from a refusal, and how it was decided.'}>
        {queue.readable && <Tabs<Tab> ariaLabel="Access requests" value={tab} onChange={setPicked} tabs={[{ id: 'decide', label: 'To decide', icon: 'approval', count: queue.toDecide.length }, { id: 'mine', label: 'Mine', icon: 'user' }, { id: 'account', label: 'Everything in the account', icon: 'list' }]} />}
      </PageHeader>
      {list.error ? (
        <ErrorState error={list.error} title="Could not load the access requests" onRetry={() => void list.refetch()} />
      ) : (
        <Table<AccessRequest> caption="Access requests" columns={columns} rows={rows} rowKey={(r) => r.requestId} loading={!known || list.loading} onRowClick={(r) => navigate(pathFor('iam-request', { id: r.requestId }))} emptyIcon="approval" emptyTitle={tab === 'decide' ? 'Nothing to decide' : tab === 'mine' ? 'You have not asked for anything' : 'No access requests yet'} emptyMessage={tab === 'mine' ? 'When an action is refused, Request access in the refusal sends one to the account’s approvers.' : tab === 'decide' ? 'Requests that name you among their approvers land here.' : 'Requests land here when someone presses Request access in a refusal.'} />
      )}
    </>
  )
}

/* ---------------------------------------------------------------- one request */

export function IamAccessRequestPage({ route }: PageProps) {
  const requestId = route.params.id
  const { account, me, admin, session } = useConsole()
  const request = useQuery(['access-requests', 'one', account.id, requestId], () => bff.accessRequests.get(requestId))
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState<'approve' | 'reject' | 'refresh' | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const back = { href: href(pathFor('iam-requests')), label: 'Access requests' }
  const r = request.data

  if (request.error) {
    return (
      <>
        <PageHeader back={back} title={requestId} />
        <ErrorState error={request.error} title="Could not load this access request" onRetry={() => void request.refetch()} />
      </>
    )
  }
  if (!r) {
    return (
      <>
        <PageHeader back={back} title={<Skeleton className="h-7 w-64" />} />
        <SkeletonText lines={4} />
      </>
    )
  }

  const mine = r.requester.userId === me.id
  const canDecide = mayDecide(r, me.id)
  const otherApprovers = (r.approvers ?? []).filter((a) => a !== me.id)
  const decide = async (decision: 'approve' | 'reject') => {
    setBusy(decision)
    setFailure(null)
    try {
      await bff.accessRequests.decide(r.requestId, { decision, comment: comment.trim() || undefined })
      for (const prefix of ['access-requests', 'iam', 'console-types']) invalidate(prefix)
    } catch (e) {
      setFailure(e)
    } finally {
      setBusy(null)
    }
  }
  const refresh = async () => {
    setBusy('refresh')
    await loadSession()
    invalidate()
    setBusy(null)
  }
  const { service, verb } = splitAction(r.action)
  const holdsNow = mine && r.status === 'approved' && (session.account.roles ?? []).includes(r.role)

  return (
    <>
      <PageHeader
        back={back}
        eyebrow="Access request"
        title={
          <span className="flex flex-wrap items-center gap-2">
            {personLabel(r.requester)} asks for <RoleTags roles={[r.role]} size="md" />
            <RequestStatusTag status={r.status} />
          </span>
        }
        subtitle={`To ${verbTitle(verb).toLowerCase()} in ${service}, refused by ${r.layer === 'platform' ? 'the platform' : 'console IAM'}.`}
      />
      <div className="space-y-8">
        <Section title="The request">
          <Facts
            columns={3}
            items={[
              { label: 'Request id', value: <CopyableId id={r.requestId} /> },
              { label: 'Requester', value: admin ? <Link to={pathFor('iam-user', { id: r.requester.userId })} className="text-accent hover:underline">{personLabel(r.requester)}</Link> : personLabel(r.requester) },
              { label: 'Role asked for', value: <RoleTags roles={[r.role]} link /> },
              { label: 'Action', value: <span className="font-mono text-caption">{r.action}</span> },
              { label: 'Resource', value: r.resource ? <span className="break-all font-mono text-caption">{r.resource}</span> : <span className="text-tertiary">any</span>, wide: true },
              { label: 'Refused by', value: <span title={layerDescription(r.layer ?? 'console-iam', account.name)}>{layerLabel(r.layer ?? 'console-iam')}</span> },
              { label: 'Requested', value: <When at={r.requestedAt} /> },
              { label: 'Approvers', value: r.approvers?.length ? <span className="font-mono text-caption">{r.approvers.join(', ')}</span> : <span className="text-tertiary">the account’s admins</span> },
              { label: 'Reason', value: r.reason ? <blockquote className="border-l-2 border-stroke pl-3 text-sm text-primary">{r.reason}</blockquote> : <span className="text-tertiary">none given</span>, wide: true },
            ]}
          />
        </Section>

        {r.status !== 'pending' && (
          <Section title="The decision">
            <Facts
              columns={3}
              items={[
                { label: 'Outcome', value: <RequestStatusTag status={r.status} /> },
                { label: 'Decided by', value: r.decidedBy ? <span className="font-mono text-caption">{r.decidedBy}</span> : <span className="text-tertiary">—</span> },
                { label: 'Decided', value: <When at={r.decidedAt} /> },
                { label: 'Comment', value: r.comment ? <blockquote className="border-l-2 border-stroke pl-3 text-sm text-primary">{r.comment}</blockquote> : <span className="text-tertiary">none</span>, wide: true },
              ]}
            />
            {mine && r.status === 'approved' && (
              <div role="status" className="mt-4 flex flex-wrap items-center gap-3 rounded-md border border-healthy/40 bg-healthy-tint p-3 text-sm text-primary">
                <Icon name="check" size={16} className="text-healthy" />
                {holdsNow ? (
                  <span>
                    You now hold <span className="font-medium">{roleLabel(r.role)}</span> in {account.name}.
                  </span>
                ) : (
                  <>
                    <span>
                      You were put in the group that grants <span className="font-medium">{roleLabel(r.role)}</span>. Refresh your session to use it.
                    </span>
                    <Button size="sm" icon="refresh" loading={busy === 'refresh'} onClick={() => void refresh()}>
                      Refresh my roles
                    </Button>
                  </>
                )}
              </div>
            )}
          </Section>
        )}

        {canDecide && (
          <Section title="Decide" description={`Approving puts ${personLabel(r.requester)} in the group that grants ${roleLabel(r.role)} in ${account.name}; everything that role allows follows.`}>
            <div className="max-w-xl space-y-4">
              <Field label="Comment" htmlFor="decide-comment" hint="Optional. The requester reads it.">
                <Textarea id="decide-comment" rows={3} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} />
              </Field>
              {failure !== null && (
                <p role="alert" className="text-sm text-critical">
                  Could not record the decision: {failure instanceof Error ? failure.message : String(failure)}
                </p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="primary" icon="check" data-decide="approve" loading={busy === 'approve'} disabled={busy !== null} onClick={() => void decide('approve')}>
                  Approve
                </Button>
                <Button variant="danger" icon="close" data-decide="reject" loading={busy === 'reject'} disabled={busy !== null} onClick={() => void decide('reject')}>
                  Reject
                </Button>
              </div>
            </div>
          </Section>
        )}
        {r.status === 'pending' && mine && (
          <p className="text-caption text-tertiary">
            <Tag size="sm" outline>
              note
            </Tag>{' '}
            Someone else decides your own request: {otherApprovers.length ? `${plural(otherApprovers.length, 'approver')} can (${otherApprovers.join(', ')}).` : 'nobody else may decide it yet; ask an admin.'}
          </p>
        )}
      </div>
    </>
  )
}
