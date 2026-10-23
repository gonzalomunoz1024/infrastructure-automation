import { useState } from 'react'
import { Button, EmptyState, PageHeader, Segmented, Select, SkeletonRows, useToast } from '@iaf/ui'
import { CursorPager, DotTag, ErrorState, When, useCursorPaging } from '@iaf/console-kit'
import { failureOf, markRead, useAccountNames, useNotificationRoute, NOTIFICATIONS_KEY } from '../../app/notifications'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { useConsole } from '../../app/console'
import { KIND_WORDS, NOTIFICATION_KINDS, SEVERITY_WORDS, isNotificationKind, notificationsApi, type Notification } from '../../lib/notifications'
import { useQuery } from '../../lib/queries'

/*
 * The person's notifications across their accounts (WP 7.2, 7.5), newest first, kept 90 days:
 * filtered by unread and by kind, both kept in the address, each leading to the resource,
 * request, access request or digest it is about and marked read on the way, or marked read
 * where it stands. New ones arrive on the live stream the bell keeps open.
 */

type Show = 'all' | 'unread'

export default function NotificationsPage({ route }: PageProps) {
  const { session } = useConsole()
  const toast = useToast()
  const routeOf = useNotificationRoute()
  const accountName = useAccountNames()
  const show: Show = route.query.unread === '1' ? 'unread' : 'all'
  const kind = isNotificationKind(route.query.kind) ? route.query.kind : undefined
  const paging = useCursorPaging()
  const [pageSize, setPageSize] = useState(25)
  const list = useQuery([NOTIFICATIONS_KEY, 'page', session.user.userId, show, kind ?? '', paging.after ?? '', pageSize], () => notificationsApi.list({ unread: show === 'unread' ? true : undefined, kind, after: paging.after, limit: pageSize }))
  const rows = list.data?.items ?? []
  const unread = list.data?.unread ?? 0

  const setQuery = (next: { unread?: string; kind?: string }) => {
    paging.reset()
    navigate(pathFor('notifications', {}, { ...route.query, ...next }, { accountId: route.accountId }), { replace: true })
  }
  const read = (ids: string[]) => markRead({ ids }).catch((e: unknown) => toast.error('Could not mark it read', failureOf(e)))
  const readAll = () =>
    markRead({ all: true })
      .then(() => toast.success('All read', 'Every notification is marked read.'))
      .catch((e: unknown) => toast.error('Could not mark them read', failureOf(e)))

  const follow = (n: Notification) => {
    if (!n.readAt) void read([n.id])
  }

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="Requests that end or pause, approvals, access requests, drift, quotas, budgets and the weekly digest, across your accounts. Kept for 90 days."
        actions={
          <span className="flex flex-wrap items-center gap-2">
            <Button size="sm" icon="check" onClick={() => void readAll()} disabled={unread === 0}>
              Mark all read
            </Button>
            <Button size="sm" variant="ghost" icon="settings" onClick={() => navigate(pathFor('notification-settings'))}>
              Settings
            </Button>
          </span>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Segmented<Show>
          ariaLabel="Show"
          size="sm"
          value={show}
          onChange={(v) => setQuery({ unread: v === 'unread' ? '1' : undefined })}
          options={[
            { value: 'all', label: 'All' },
            { value: 'unread', label: unread ? `Unread (${unread})` : 'Unread' },
          ]}
        />
        <Select size="sm" ariaLabel={`Kind: ${kind ? KIND_WORDS[kind].label : 'Every kind'}`} value={kind ?? ''} onChange={(v) => setQuery({ kind: v || undefined })} options={[{ value: '', label: 'Every kind' }, ...NOTIFICATION_KINDS.map((k) => ({ value: k, label: KIND_WORDS[k].label }))]} />
        <Link to={pathFor('digests')} className="text-caption font-medium text-accent transition-opacity hover:opacity-70">
          Weekly digests
        </Link>
      </div>
      {list.error ? (
        <ErrorState error={list.error} title="Could not load your notifications" onRetry={() => void list.refetch()} />
      ) : list.loading && rows.length === 0 ? (
        <SkeletonRows n={4} rowClassName="h-16" />
      ) : rows.length === 0 ? (
        <EmptyState icon="bell" title={show === 'unread' ? 'Nothing unread' : kind ? 'None of this kind' : 'No notifications yet'} message={show === 'unread' ? 'You have read everything. New ones appear here as they happen.' : 'Requests you start, approvals you give and the account’s weekly digest notify you here.'} />
      ) : (
        <ul aria-label="Notifications" className="divide-y divide-stroke-light overflow-hidden rounded-xl border border-stroke-light bg-surface" data-notifications>
          {rows.map((n) => {
            const to = routeOf(n)
            return (
              <li key={n.id} data-notification={n.id} data-unread={n.readAt ? undefined : 'true'} className="flex gap-3 px-4 py-3">
                <span aria-hidden="true" className={`mt-2 size-2 shrink-0 rounded-full ${n.readAt ? 'bg-transparent' : 'bg-accent'}`} />
                <div className="min-w-0 flex-1">
                  <p className={`text-sm text-primary ${n.readAt ? '' : 'font-semibold'}`}>
                    {!n.readAt && <span className="sr-only">Unread: </span>}
                    {to ? (
                      <Link to={to} onClick={() => follow(n)} className="underline-offset-2 hover:underline">
                        {n.title}
                      </Link>
                    ) : (
                      n.title
                    )}
                  </p>
                  {n.body && <p className="mt-0.5 text-caption text-secondary">{n.body}</p>}
                  <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-tertiary">
                    <DotTag size="sm" tone={SEVERITY_WORDS[n.severity].tone}>
                      {SEVERITY_WORDS[n.severity].text}
                    </DotTag>
                    <span>{KIND_WORDS[n.kind]?.label ?? n.kind}</span>
                    <span>{accountName(n.accountId)}</span>
                    <When at={n.createdAt} />
                  </p>
                </div>
                {!n.readAt && (
                  <Button size="sm" variant="ghost" onClick={() => void read([n.id])} className="shrink-0 self-start">
                    Mark read<span className="sr-only">: {n.title}</span>
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {rows.length > 0 && (
        <CursorPager
          className="mt-3"
          count={rows.length}
          hasNext={!!list.data?.next}
          hasPrevious={paging.hasPrevious}
          onNext={() => list.data?.next && paging.next(list.data.next)}
          onPrevious={paging.previous}
          page={paging.page}
          pageSize={pageSize}
          onPageSizeChange={(n) => {
            paging.reset()
            setPageSize(n)
          }}
          loading={list.fetching}
          noun="notification"
        />
      )}
    </>
  )
}
