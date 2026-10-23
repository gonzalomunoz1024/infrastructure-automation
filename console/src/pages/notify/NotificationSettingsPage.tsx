import { useMemo, useState } from 'react'
import { Button, Card, CardRow, PageHeader, SkeletonRows, Switch, useToast } from '@iaf/ui'
import { ErrorState } from '@iaf/console-kit'
import { failureOf } from '../../app/notifications'
import { Link, pathFor, type PageProps } from '../../app/router'
import { useConsole } from '../../app/console'
import { isApiError } from '../../lib/api'
import { CHANNEL_WORDS, KIND_WORDS, NOTIFICATION_CHANNELS, NOTIFICATION_KINDS, channelState, defaultChannels, effectiveRules, notificationsApi, rulesToSave, type NotificationChannel, type NotificationKind, type NotificationSettings } from '../../lib/notifications'
import { invalidate, useQuery } from '../../lib/queries'

/*
 * Where the person is notified of what (WP 7.5): the channels (email to the identity provider's
 * address, Slack and Teams once linked, each on or off), and for every kind of notification the
 * channels it goes to, the defaults filled in. Saved in one PUT against the version read; a 409
 * means another tab saved first, so the page reads them again. Each channel can be sent a test.
 * These are the person's own settings: anyone signed in may change theirs, as with preferences.
 */

const KEY = ['notification-settings']

type Draft = { email: boolean; slack: boolean; teams: boolean; rules: Record<NotificationKind, NotificationChannel[]> }

function draftOf(s: NotificationSettings): Draft {
  return { email: s.channels.email.enabled, slack: s.channels.slack.enabled, teams: s.channels.teams.enabled, rules: effectiveRules(s) }
}

const same = (a: Draft, b: Draft) => JSON.stringify(a) === JSON.stringify(b)

export default function NotificationSettingsPage(_props: PageProps) {
  const { session } = useConsole()
  const toast = useToast()
  const settings = useQuery([...KEY, session.user.userId], () => notificationsApi.settings())
  const saved = settings.data
  const [draft, setDraft] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const [testing, setTesting] = useState<NotificationChannel | null>(null)

  // each new version read (the first, a save, a 409's reread) starts the draft again from it
  const base = useMemo(() => (saved ? draftOf(saved) : null), [saved])
  const [baseVersion, setBaseVersion] = useState<number | null>(null)
  if (saved && saved.version !== baseVersion) {
    setBaseVersion(saved.version)
    setDraft(draftOf(saved))
  }

  if (settings.error) return <ErrorState error={settings.error} title="Could not load your notification settings" onRetry={() => void settings.refetch()} />
  if (!saved || !draft || !base) return <SkeletonRows n={6} rowClassName="h-12" />

  const dirty = !same(draft, base)
  const live: Pick<NotificationSettings, 'channels'> = { channels: { email: { enabled: draft.email }, slack: { linked: saved.channels.slack.linked, enabled: draft.slack }, teams: { linked: saved.channels.teams.linked, enabled: draft.teams } } }
  const linked = { slack: saved.channels.slack.linked, teams: saved.channels.teams.linked }

  const toggle = (kind: NotificationKind, channel: NotificationChannel, on: boolean) =>
    setDraft((d) => (d ? { ...d, rules: { ...d.rules, [kind]: NOTIFICATION_CHANNELS.filter((c) => (c === channel ? on : d.rules[kind].includes(c))) } } : d))

  const save = async () => {
    setBusy(true)
    try {
      const body: NotificationSettings = { version: saved.version, channels: { email: { enabled: draft.email }, slack: { linked: linked.slack, enabled: draft.slack }, teams: { linked: linked.teams, enabled: draft.teams } }, rules: rulesToSave(draft.rules, linked) }
      await notificationsApi.putSettings(body)
      toast.success('Saved', 'Your notification settings apply from now on.')
      invalidate(KEY.join('/'))
    } catch (e) {
      if (isApiError(e) && e.status === 409) {
        toast.error('Your settings changed elsewhere', 'Another tab or browser saved them first. They are read again here; make your change once more.')
        setBaseVersion(null)
        invalidate(KEY.join('/'))
      } else toast.error('Could not save your settings', failureOf(e))
    } finally {
      setBusy(false)
    }
  }

  const test = async (channel: NotificationChannel) => {
    setTesting(channel)
    try {
      await notificationsApi.test(channel)
      toast.success('Test sent', channel === 'email' ? `A test message is on its way to ${saved.channels.email.address ?? 'your address'}. It can take a minute.` : `A test message is on its way to you in ${CHANNEL_WORDS[channel]}.`)
    } catch (e) {
      toast.error(`Could not send a test to ${CHANNEL_WORDS[channel]}`, failureOf(e))
    } finally {
      setTesting(null)
    }
  }

  const testButton = (channel: NotificationChannel) => {
    const st = channelState(saved, channel)
    return (
      <Button size="sm" variant="secondary" icon="send" loading={testing === channel} disabled={!st.on || testing !== null} onClick={() => void test(channel)} data-test-channel={channel}>
        Send a test<span className="sr-only"> to {CHANNEL_WORDS[channel]}</span>
      </Button>
    )
  }

  return (
    <>
      <PageHeader title="Notification settings" subtitle="Where the console tells you what happened. The console’s own feed always works; email, Slack and Teams carry what you choose below." />
      <div className="space-y-6">
        <Card title="Channels" subtitle="A test is sent with your saved settings">
          <CardRow label="Email" description={saved.channels.email.address ? `To ${saved.channels.email.address}, the address your identity provider gives. It cannot be changed here.` : 'To the address your identity provider gives.'}>
            <span className="flex flex-wrap items-center justify-end gap-2">
              <Switch label="Email" checked={draft.email} onChange={(v) => setDraft({ ...draft, email: v })} />
              {testButton('email')}
            </span>
          </CardRow>
          {(['slack', 'teams'] as const).map((p) => (
            <CardRow
              key={p}
              label={CHANNEL_WORDS[p]}
              description={
                saved.channels[p].linked ? (
                  <span>
                    Linked. <Link to={pathFor('chat-link')} className="font-medium text-accent hover:underline">Manage linked chats</Link>
                  </span>
                ) : (
                  <span>
                    Not linked. In {CHANNEL_WORDS[p]}, type <code className="font-mono">/console link</code> and open the link it answers with. <Link to={pathFor('chat-link')} className="font-medium text-accent hover:underline">Linked chats</Link>
                  </span>
                )
              }
            >
              <span className="flex flex-wrap items-center justify-end gap-2">
                <Switch label={CHANNEL_WORDS[p]} checked={draft[p]} disabled={!saved.channels[p].linked} onChange={(v) => setDraft({ ...draft, [p]: v })} />
                {testButton(p)}
              </span>
            </CardRow>
          ))}
        </Card>

        <Card title="What goes where" subtitle="A box that is greyed belongs to a channel that is off or not linked" flush>
          <table className="w-full table-fixed text-sm" aria-label="Channels for each kind of notification">
            <thead>
              <tr className="border-b border-stroke-light text-left text-caption text-secondary">
                <th scope="col" className="px-3 py-2 font-medium sm:px-4">
                  Notification
                </th>
                {NOTIFICATION_CHANNELS.map((c) => (
                  <th key={c} scope="col" className="w-12 px-1 py-2 text-center font-medium sm:w-20">
                    {CHANNEL_WORDS[c]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {NOTIFICATION_KINDS.map((k) => {
                const isDefault = draft.rules[k].join(',') === defaultChannels(k, linked).join(',')
                return (
                  <tr key={k} className="border-b border-stroke-light last:border-0" data-kind={k}>
                    <th scope="row" className="px-3 py-2 text-left font-normal sm:px-4">
                      <span className="block font-medium text-primary">{KIND_WORDS[k].label}</span>
                      <span className="block text-caption text-tertiary">
                        {KIND_WORDS[k].description}
                        {isDefault ? '' : '. Changed from the default'}
                      </span>
                    </th>
                    {NOTIFICATION_CHANNELS.map((c) => {
                      const st = channelState(live, c)
                      const checked = draft.rules[k].includes(c)
                      return (
                        <td key={c} className="px-1 py-2 text-center">
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={!st.on}
                            title={st.reason}
                            onChange={(e) => toggle(k, c, e.target.checked)}
                            aria-label={`${KIND_WORDS[k].label} by ${CHANNEL_WORDS[c]}${st.reason ? `, ${st.reason.toLowerCase()}` : ''}`}
                            className="size-4 accent-accent disabled:cursor-not-allowed disabled:opacity-40"
                          />
                        </td>
                      )
                    })}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </Card>

        <div className="flex flex-wrap items-center justify-end gap-2">
          <span role="status" className="mr-auto text-caption text-secondary">
            {dirty ? 'You have changes that are not saved.' : 'Everything is saved.'}
          </span>
          <Button variant="ghost" onClick={() => setDraft({ ...draft, rules: effectiveRules({ channels: saved.channels, rules: {} }) })}>
            Use the defaults
          </Button>
          <Button variant="secondary" disabled={!dirty || busy} onClick={() => setDraft(base)}>
            Discard
          </Button>
          <Button variant="primary" icon="check" disabled={!dirty} loading={busy} onClick={() => void save()}>
            Save settings
          </Button>
        </div>
      </div>
    </>
  )
}
