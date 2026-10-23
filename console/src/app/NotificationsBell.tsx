import { Button, EmptyState, relativeTime, useToast } from '@iaf/ui'
import { DotTag } from '@iaf/console-kit'
import { useAccessRequestQueue } from './access'
import { HeaderMenu, MenuHeading } from './Header'
import { failureOf, markRead, useAccountNames, useNotificationFeed, useNotificationRoute, useNotificationStream } from './notifications'
import { Link, navigate, pathFor } from './router'
import { KIND_WORDS, SEVERITY_WORDS, type Notification } from '../lib/notifications'
import { roleLabel } from '../lib/session'

/*
 * The header's bell: the access requests that name this person among their approvers (phase 1),
 * then their newest notifications, unread first marked, each leading where it is about and
 * marked read on the way. New ones arrive on the live stream and are announced politely; the
 * badge counts what waits for a decision and what is unread.
 */

/** The bell's name: what waits, in words, so a screen reader hears the count the badge shows. */
export function bellLabel(toDecide: number, unread: number): string {
  return ['Notifications', toDecide ? `${toDecide} to decide` : '', unread ? `${unread} unread` : ''].filter(Boolean).join(', ')
}

export function NotificationsBell() {
  const queue = useAccessRequestQueue()
  const feed = useNotificationFeed()
  const latest = useNotificationStream()
  const toast = useToast()
  const routeOf = useNotificationRoute()
  const accountName = useAccountNames()
  const items = queue.toDecide
  // an access request already listed to decide is not repeated as a notification
  const deciding = new Set(items.map((r) => `/access-requests/${r.requestId}`))
  const notes = (feed.data?.items ?? []).filter((n) => !(n.kind === 'access-request.created' && deciding.has(n.link)))
  const unread = feed.data?.unread ?? 0
  const label = bellLabel(items.length, unread)

  const open = (n: Notification, close: () => void) => {
    close()
    if (!n.readAt) void markRead({ ids: [n.id] }).catch(() => undefined)
    const to = routeOf(n)
    navigate(to ?? pathFor('notifications'))
  }
  const readAll = () => markRead({ all: true }).catch((e: unknown) => toast.error('Could not mark them read', failureOf(e)))

  return (
    <>
      <span role="status" aria-live="polite" className="sr-only">
        {latest ? `New notification: ${latest.title}` : ''}
      </span>
      <HeaderMenu label={label} icon="bell" role="dialog" width="w-80 max-w-[calc(100vw-1.5rem)]" badge={items.length + unread}>
        {(close) => (
          <div>
            {items.length > 0 && (
              <>
                <MenuHeading>Access requests to decide</MenuHeading>
                <ul aria-label="Access requests to decide">
                  {items.map((r) => (
                    <li key={r.requestId}>
                      <Link to={pathFor('iam-request', { id: r.requestId })} onClick={close} className="block px-3 py-2 text-sm transition-colors hover:bg-surface-secondary">
                        <span className="block text-primary">
                          {r.requester.name ?? r.requester.userId} asks for <span className="font-medium">{roleLabel(r.role)}</span>
                        </span>
                        <span className="block truncate font-mono text-caption text-tertiary">
                          {r.action} · {relativeTime(r.requestedAt)}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
                <Link to={pathFor('iam-requests')} onClick={close} className="block border-b border-stroke-light px-3 py-2 text-caption font-medium text-accent transition-opacity hover:opacity-70">
                  All access requests
                </Link>
              </>
            )}
            <div className="flex items-center justify-between gap-2 pr-2">
              <MenuHeading>{unread ? `Notifications, ${unread} unread` : 'Notifications'}</MenuHeading>
              {unread > 0 && (
                <Button size="sm" variant="ghost" onClick={() => void readAll()}>
                  Mark all read
                </Button>
              )}
            </div>
            {notes.length === 0 ? (
              <EmptyState compact icon="bell" title={feed.loading ? 'Loading' : 'Nothing new'} message="Requests that end or pause, approvals, drift and the weekly digest land here." />
            ) : (
              <ul aria-label="Notifications" className="max-h-80 overflow-y-auto">
                {notes.slice(0, 6).map((n) => (
                  <li key={n.id}>
                    <a
                      href={`#${routeOf(n) ?? pathFor('notifications')}`}
                      onClick={(e) => {
                        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
                        e.preventDefault()
                        open(n, close)
                      }}
                      data-notification={n.id}
                      data-unread={n.readAt ? undefined : 'true'}
                      className="flex gap-2 px-3 py-2 text-sm transition-colors hover:bg-surface-secondary"
                    >
                      <span aria-hidden="true" className={`mt-1.5 size-2 shrink-0 rounded-full ${n.readAt ? 'bg-transparent' : 'bg-accent'}`} />
                      <span className="min-w-0 flex-1">
                        <span className={`block text-primary ${n.readAt ? '' : 'font-medium'}`}>
                          {n.readAt ? '' : <span className="sr-only">Unread: </span>}
                          {n.title}
                        </span>
                        <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-caption text-tertiary">
                          {n.severity !== 'info' && (
                            <DotTag size="sm" tone={SEVERITY_WORDS[n.severity].tone}>
                              {SEVERITY_WORDS[n.severity].text}
                            </DotTag>
                          )}
                          <span>{KIND_WORDS[n.kind]?.label ?? n.kind}</span>
                          <span>{accountName(n.accountId)}</span>
                          <span>{relativeTime(n.createdAt)}</span>
                        </span>
                      </span>
                    </a>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-stroke-light px-3 py-2 text-caption font-medium">
              <Link to={pathFor('notifications')} onClick={close} className="text-accent transition-opacity hover:opacity-70">
                All notifications
              </Link>
              <Link to={pathFor('notification-settings')} onClick={close} className="text-accent transition-opacity hover:opacity-70">
                Notification settings
              </Link>
            </div>
          </div>
        )}
      </HeaderMenu>
    </>
  )
}
