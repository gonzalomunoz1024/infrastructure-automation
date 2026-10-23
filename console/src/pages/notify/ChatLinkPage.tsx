import { useState } from 'react'
import { Button, Card, EmptyState, PageHeader, SkeletonRows, useToast } from '@iaf/ui'
import { ConfirmDialog, ErrorState, When } from '@iaf/console-kit'
import { failureOf } from '../../app/notifications'
import { Link, navigate, pathFor, type PageProps } from '../../app/router'
import { useConsole } from '../../app/console'
import { isApiError } from '../../lib/api'
import { CHANNEL_WORDS, notificationsApi, readChatLinkToken, type ChatLink } from '../../lib/notifications'
import { invalidate, useQuery } from '../../lib/queries'

/*
 * Linking Slack or Teams to the person signed in (WP 7.5, hub decision 31). `/console link` in
 * the chat app answers with a link to `#/chat-link?token=`: the page says what the token links,
 * asks once, and posts it (POST /console/v1/chat-links). From then on the chat app's commands act
 * as this person through the console's own IAM, and their notifications can go there. The linked
 * chats are listed below, each unlinked after one question. Linking is the person's own business,
 * so any role may do it; only a browser session may, which this page is.
 */

const KEY = ['chat-links']

/** The words for a refused link: an old or foreign token, or a chat identity someone else holds. */
function refusalOf(e: unknown): { title: string; message: string } {
  if (isApiError(e)) {
    if (e.error === 'chat-link.taken') return { title: 'Linked to someone else', message: 'This chat identity already acts as another person in the console. Ask them to unlink it first, or use your own chat account.' }
    if (e.error === 'chat-link.expired') return { title: 'This link has expired', message: 'A link is good for ten minutes. Type /console link in the chat app again for a new one.' }
    if (e.error === 'chat-link.invalid' || e.status === 400) return { title: 'This link is not valid', message: 'It was not made by the console, or it was cut short. Type /console link in the chat app again and open the whole link.' }
    if (e.error === 'browser.only') return { title: 'Only in the browser', message: 'Open the link in your browser, signed in to the console.' }
  }
  return { title: 'Could not link the chat', message: failureOf(e) }
}

export default function ChatLinkPage({ route }: PageProps) {
  const { session } = useConsole()
  const toast = useToast()
  const token = route.query.token
  const claims = token ? readChatLinkToken(token) : null
  const links = useQuery([...KEY, session.user.userId], () => notificationsApi.chatLinks())
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<{ title: string; message: string } | null>(null)
  const [unlinking, setUnlinking] = useState<ChatLink | null>(null)

  const clearToken = () => navigate(pathFor('chat-link', {}, undefined, { accountId: route.accountId }), { replace: true })
  const refresh = () => {
    invalidate(KEY.join('/'))
    invalidate('notification-settings')
  }

  const link = async () => {
    if (!token) return
    setBusy(true)
    setFailed(null)
    try {
      const linked = await notificationsApi.link(token)
      toast.success(`${CHANNEL_WORDS[linked.platform]} is linked`, `${linked.chatUserName ?? linked.chatUserId} now acts as you, and can receive your notifications.`)
      refresh()
      clearToken()
    } catch (e) {
      setFailed(refusalOf(e))
    } finally {
      setBusy(false)
    }
  }

  const platformName = claims?.platform ? CHANNEL_WORDS[claims.platform] : 'a chat app'
  const items = links.data?.items ?? []

  return (
    <>
      <PageHeader title="Linked chats" subtitle="The Slack and Teams identities that act as you through the console’s own permissions, and can receive your notifications." />
      <div className="space-y-6">
        {token && (
          <Card title={`Link ${platformName} to ${session.user.name ?? session.user.userId}`} subtitle="Only link a chat account that is yours" data-chat-link-confirm>
            <div className="space-y-3 text-sm">
              {claims ? (
                <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-3">
                  <div>
                    <dt className="text-caption text-secondary">Chat user</dt>
                    <dd className="break-words text-primary">{claims.chatUserName ?? claims.chatUserId ?? 'not named'}</dd>
                  </div>
                  <div>
                    <dt className="text-caption text-secondary">{claims.platform === 'teams' ? 'Tenant' : 'Workspace'}</dt>
                    <dd className="break-words font-mono text-primary">{claims.workspaceId ?? 'not named'}</dd>
                  </div>
                  {claims.expiresAt && (
                    <div>
                      <dt className="text-caption text-secondary">Link good until</dt>
                      <dd className="text-primary">
                        <When at={claims.expiresAt} />
                      </dd>
                    </div>
                  )}
                </dl>
              ) : (
                <p className="text-secondary">The console cannot read who this link names; linking checks it.</p>
              )}
              <p className="text-secondary">Once linked, its commands run as you, with your role in each account, and a delete still asks you to confirm here in the console.</p>
              {failed && (
                <div role="alert" className="rounded-lg border border-critical/40 bg-critical-tint px-3 py-2" data-chat-link-failed>
                  <p className="font-medium text-primary">{failed.title}</p>
                  <p className="text-caption text-secondary">{failed.message}</p>
                </div>
              )}
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" icon="link" loading={busy} onClick={() => void link()}>
                  Link {platformName}
                </Button>
                <Button variant="ghost" onClick={clearToken}>
                  Not now
                </Button>
              </div>
            </div>
          </Card>
        )}

        <Card title="Your linked chats" subtitle="Unlinking stops its commands acting as you and its notifications">
          <div>
            {links.error ? (
              <ErrorState compact error={links.error} title="Could not load your linked chats" onRetry={() => void links.refetch()} />
            ) : links.loading && !links.data ? (
              <SkeletonRows n={2} rowClassName="h-10" />
            ) : items.length === 0 ? (
              <EmptyState compact icon="link" title="No chats linked" message="In Slack or Teams, type /console link and open the link it answers with." />
            ) : (
              <ul aria-label="Linked chats" className="divide-y divide-stroke-light">
                {items.map((l) => (
                  <li key={l.id} data-chat-link={l.platform} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-primary">
                        {CHANNEL_WORDS[l.platform]}: {l.chatUserName ?? l.chatUserId}
                      </p>
                      <p className="break-words text-caption text-tertiary">
                        {l.platform === 'teams' ? 'Tenant' : 'Workspace'} <span className="font-mono">{l.workspaceId}</span>, linked <When at={l.linkedAt} />
                      </p>
                    </div>
                    <Button size="sm" variant="ghost" icon="close" onClick={() => setUnlinking(l)}>
                      Unlink<span className="sr-only"> {CHANNEL_WORDS[l.platform]}</span>
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Card>

        <Card title="How to link one">
          <ol className="list-decimal space-y-1 pl-5 text-sm text-secondary">
            <li>
              In Slack or Teams, type <code className="font-mono text-primary">/console link</code>.
            </li>
            <li>The app answers with a link to this page, good for ten minutes. Open it in this browser.</li>
            <li>Check the chat user is you, and choose Link.</li>
          </ol>
          <p className="mt-3 text-caption text-tertiary">
            Which notifications go to a linked chat is in your <Link to={pathFor('notification-settings')} className="font-medium text-accent hover:underline">notification settings</Link>.
          </p>
        </Card>
      </div>
      {unlinking && (
        <ConfirmDialog
          title={`Unlink ${CHANNEL_WORDS[unlinking.platform]}?`}
          message={`${unlinking.chatUserName ?? unlinking.chatUserId} stops acting as you, and ${CHANNEL_WORDS[unlinking.platform]} stops receiving your notifications, until you link it again.`}
          confirmLabel="Unlink"
          tone="danger"
          icon="link"
          onClose={() => setUnlinking(null)}
          onConfirm={async () => {
            await notificationsApi.unlink(unlinking.id)
            toast.success(`${CHANNEL_WORDS[unlinking.platform]} is unlinked`, 'Link it again any time with /console link.')
            refresh()
          }}
        />
      )}
    </>
  )
}
