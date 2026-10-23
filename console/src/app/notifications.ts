import { useCallback, useEffect, useState } from 'react'
import { isApiError } from '../lib/api'
import { bff } from '../lib/bff'
import { LIVE_EVENTS_URL, notificationsApi, routeOfLink, type LiveEvent, type Notification } from '../lib/notifications'
import { invalidate, useQuery } from '../lib/queries'
import { openStream } from '../lib/sse'
import { useConsole } from './console'

/*
 * The person's notifications as the shell reads them: the bell's newest few and the unread
 * count, the live stream that brings new ones (`notification` events on GET /console/v1/events,
 * the BFF's stream for this tab), marking read, and where each one leads. Every reader keys its
 * query under `notifications`, so a new event or a read refetches them all together.
 */

export const NOTIFICATIONS_KEY = 'notifications'

/** The bell's view: the newest ten and the unread count, kept fresh by the stream (and a slow poll, should it drop). */
export function useNotificationFeed() {
  const { session } = useConsole()
  return useQuery([NOTIFICATIONS_KEY, 'bell', session.user.userId], () => notificationsApi.list({ limit: 10 }), { refetchInterval: 120_000 })
}

/**
 * Opens the live stream once per person and refetches the notifications when one arrives;
 * answers the newest that arrived, for a polite announcement.
 */
export function useNotificationStream(): Notification | null {
  const { session } = useConsole()
  const userId = session.user.userId
  const [latest, setLatest] = useState<Notification | null>(null)
  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const stream = openStream<LiveEvent<Notification>>(
      LIVE_EVENTS_URL,
      (event) => {
        if (!event || event.type !== 'notification' || !event.data) return
        setLatest(event.data)
        invalidate(NOTIFICATIONS_KEY)
      },
      { events: ['notification'] },
    )
    return () => stream.close()
  }, [userId])
  return latest
}

/** Marks notifications read and refetches every reader. */
export async function markRead(body: { ids: string[] } | { all: true }): Promise<number> {
  const answer = await notificationsApi.markRead(body)
  invalidate(NOTIFICATIONS_KEY)
  return answer.unread
}

/** Where a notification leads in this console, as a hash path, or null. */
export function useNotificationRoute(): (n: Notification) => string | null {
  const { types, account } = useConsole()
  return useCallback((n: Notification) => routeOfLink(n, types, account.id), [types, account.id])
}

/** The names of the accounts the person belongs to, for a notification of another account. */
export function useAccountNames(): (accountId: string) => string {
  const { session } = useConsole()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts())
  const items = accounts.data?.items
  return useCallback((accountId: string) => items?.find((a) => a.accountId === accountId)?.name ?? (accountId === session.account.accountId ? session.account.name : accountId), [items, session.account])
}

/** A failed notification call in words. */
export function failureOf(e: unknown): string {
  if (!isApiError(e)) return String(e)
  const detail = typeof e.body?.detail === 'string' ? e.body.detail : undefined
  return detail ?? e.error
}
