/**
 * The in-app inbox: the bell in the header with the unread count, and the panel under it that
 * lists what was addressed to the caller or one of their teams, newest first. One click opens
 * the job, change request, release or provider a notification is about and marks it read;
 * "Mark all read" goes through the unread ones. The count is polled every 30 s.
 */
import { useCallback, useRef, useState } from 'react'
import { Badge, Button, EmptyState, Icon, IconButton, PopoverPanel, Spinner, StatusDot, Tooltip, cx, relativeTime, useDismiss, type DotKind } from '@iaf/ui'
import { Link, pathFor, resolve } from './router'
import { api, errorMessage, useCurrentUser } from '../lib/api'
import { invalidate, useQuery } from '../lib/queries'
import type { Notification, NotificationSeverity } from '../lib/types'

export const NOTIFICATIONS_POLL_MS = 30_000

const LABELS: { test: RegExp; label: string }[] = [
  { test: /^\/jobs\//, label: 'Open job' },
  { test: /^\/change-requests\//, label: 'Open change request' },
  { test: /^\/releases\//, label: 'Open release' },
  { test: /^\/providers\//, label: 'Open provider' },
  { test: /^\/approvals/, label: 'Open approvals' },
  { test: /^\/workflows\//, label: 'Open workflow' },
]

/** Where a notification takes you: its `link` (a path in the UI) when the router knows it, else the job, provider or workflow it names. */
export function notificationTarget(n: Pick<Notification, 'link' | 'jobId' | 'provider' | 'workflow'>): { to: string; label: string } | null {
  const raw = n.link?.trim() ?? ''
  const path = raw.startsWith('#') ? raw.slice(1) : raw
  if (path.startsWith('/') && resolve(path.split('?')[0]).def) {
    return { to: path, label: LABELS.find((l) => l.test.test(path))?.label ?? 'Open' }
  }
  if (n.jobId) return { to: pathFor('job', { id: n.jobId }), label: 'Open job' }
  if (n.provider) return { to: pathFor('provider', { name: n.provider }), label: 'Open provider' }
  if (n.workflow) return { to: pathFor('workflow', { name: n.workflow }), label: 'Open workflow' }
  return null
}

export function severityDot(severity: NotificationSeverity | string): DotKind {
  return severity === 'critical' ? 'critical' : severity === 'warning' ? 'warning' : 'neutral'
}

const KIND_LABEL: Record<string, string> = { notify: 'message', 'provider.alert': 'provider alert', 'approval.requested': 'approval', 'approval.escalated': 'escalation', 'review.requested': 'review' }

export interface NotificationListProps {
  items: Notification[]
  /** mark one read; the panel refetches afterwards */
  onRead: (n: Notification) => void
  /** a link was followed: the panel closes */
  onOpen?: () => void
}

/** The notifications themselves, newest first, each with where it leads and a way to mark it read. */
export function NotificationList({ items, onRead, onOpen }: NotificationListProps) {
  if (items.length === 0) return <EmptyState compact icon="bell" title="Nothing for you" message="Approvals waiting on your group, change requests your team reviews, provider alerts and messages from workflows land here." />
  return (
    <ul className="divide-y divide-stroke-light" aria-label="Notifications">
      {items.map((n) => {
        const target = notificationTarget(n)
        return (
          <li key={n.id} data-read={n.read} className={cx('px-4 py-3', !n.read && 'bg-accent-tint/30')}>
            <div className="flex items-start gap-3">
              <StatusDot kind={severityDot(n.severity)} className="mt-1.5" />
              <div className="min-w-0 flex-1">
                <p className={cx('text-sm text-primary', !n.read && 'font-medium')}>{n.title}</p>
                {n.message && <p className="mt-0.5 break-words text-caption text-secondary">{n.message}</p>}
                <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-caption text-tertiary">
                  <span title={n.at}>{relativeTime(n.at)}</span>
                  <span>{KIND_LABEL[n.kind] ?? n.kind}</span>
                  {n.channel && <span className="font-mono">{n.channel}</span>}
                  {target && (
                    <Link
                      to={target.to}
                      className="font-medium text-accent hover:underline"
                      onClick={() => {
                        if (!n.read) onRead(n)
                        onOpen?.()
                      }}
                    >
                      {target.label}
                    </Link>
                  )}
                </p>
              </div>
              {!n.read && (
                <IconButton label={`Mark read: ${n.title}`} size="sm" onClick={() => onRead(n)} className="-mt-0.5">
                  <Icon name="check" size={14} />
                </IconButton>
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}

/** The bell in the header: the unread count, and the inbox panel under it. */
export function NotificationsBell() {
  const user = useCurrentUser()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(ref, open, close)

  const unread = useQuery(['notifications', 'unread', user ?? 'default'], () => api.notifications(true), { refetchInterval: NOTIFICATIONS_POLL_MS })
  const inbox = useQuery(['notifications', 'all', user ?? 'default'], () => api.notifications(), { enabled: open, refetchInterval: open ? NOTIFICATIONS_POLL_MS : undefined })
  const count = unread.data?.unread ?? 0
  const items = inbox.data?.items ?? []

  const markRead = async (n: Notification) => {
    setError(null)
    try {
      await api.markNotificationRead(n.id)
      invalidate('notifications')
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  const markAll = async () => {
    const pending = (items.length > 0 ? items : (unread.data?.items ?? [])).filter((n) => !n.read)
    if (pending.length === 0) return
    setBusy(true)
    setError(null)
    try {
      for (const n of pending) await api.markNotificationRead(n.id)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
      invalidate('notifications')
    }
  }

  return (
    <div ref={ref} className="relative">
      <Tooltip label={count > 0 ? `${count} unread` : 'Notifications'} side="bottom">
        {/* the Tooltip says what the native title would */}
        <IconButton
          label={count > 0 ? `Notifications: ${count} unread` : 'Notifications'}
          title={undefined}
          active={open}
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-haspopup="dialog"
          data-testid="notifications-bell"
          className="relative"
        >
          <Icon name="bell" size={17} />
          {count > 0 && <Badge count={count} tone="critical" className="absolute -right-0.5 -top-0.5" />}
        </IconButton>
      </Tooltip>
      {open && (
        <PopoverPanel role="dialog" ariaLabel="Notifications" className="absolute right-0 top-full mt-2 w-[min(24rem,calc(100vw-2rem))] origin-top-right">
          <div className="flex items-center gap-2 border-b border-stroke-light px-4 py-2.5">
            <h2 className="text-footnote font-semibold text-primary">Notifications</h2>
            {count > 0 && <Badge count={count} tone="accent" />}
            <span className="flex-1" />
            <Button size="sm" variant="ghost" icon="check" loading={busy} disabled={count === 0} onClick={() => void markAll()}>
              Mark all read
            </Button>
          </div>
          <div className="max-h-[min(70vh,28rem)] overflow-y-auto">
            {error && (
              <p className="px-4 py-2 text-caption text-critical" role="alert">
                {error}
              </p>
            )}
            {inbox.loading ? (
              <div className="flex items-center justify-center py-8" role="status" aria-label="Loading notifications">
                <Spinner />
              </div>
            ) : inbox.error && items.length === 0 ? (
              <p className="px-4 py-6 text-center text-caption text-tertiary">Could not load the inbox: {errorMessage(inbox.error)}</p>
            ) : (
              <NotificationList items={items} onRead={(n) => void markRead(n)} onOpen={close} />
            )}
          </div>
        </PopoverPanel>
      )}
    </div>
  )
}
