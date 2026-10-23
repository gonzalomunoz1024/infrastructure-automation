import { Button, Card, CardRow, Segmented, Select, useTheme, useToast, type ThemeMode } from '@iaf/ui'
import { When } from '@iaf/console-kit'
import { useConsole } from '../app/console'
import { RoleTag } from '../app/RoleTag'
import { navigate, pathFor, type PageProps } from '../app/router'
import { formatAccountId } from '../lib/accounts'
import { clearDescriptorCache, isApiError } from '../lib/api'
import { bff, signOut, switchAccount, switchIdentity, switchRegion } from '../lib/bff'
import { clearQueryCache, useQuery } from '../lib/queries'
import { clearRecent } from '../lib/recent'

/*
 * Settings: appearance, who the console acts as, the account and region it is scoped to, and
 * what it keeps in this browser.
 */

const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
]

export default function SettingsPage(_props: PageProps) {
  const { mode, setMode } = useTheme()
  const toast = useToast()
  const { session, me, account, region, regions, refetch } = useConsole()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts())

  const clearCaches = () => {
    clearDescriptorCache()
    clearQueryCache()
    clearRecent()
    refetch()
    toast.success('Cleared', 'The cached descriptors, the recent pages and everything loaded in this session were dropped and fetched again.')
  }
  const failed = (what: string) => (e: unknown) => toast.error(what, isApiError(e) ? e.error : String(e))

  return (
    <>
      <div className="mb-6">
        <h1 className="text-title1 font-semibold tracking-tight text-primary">Settings</h1>
        <p className="mt-1 text-sm text-secondary">Who you are to the console, the scope every page is read in, and what the console remembers in this browser.</p>
      </div>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Appearance">
          <CardRow label="Theme" description="System follows your operating system.">
            <Segmented<ThemeMode> value={mode} onChange={setMode} options={THEME_OPTIONS} ariaLabel="Theme" size="sm" />
          </CardRow>
        </Card>

        <Card title="Identity" subtitle="From the firm's identity provider, through the console's session">
          <CardRow
            label={me.name}
            description={
              <span className="flex flex-wrap items-center gap-1.5">
                <span>{me.email}</span>
                <span className="font-mono">{me.id}</span>
                {(me.roles ?? []).map((r) => (
                  <RoleTag key={r} role={r} />
                ))}
              </span>
            }
          >
            <Button size="sm" variant="ghost" onClick={() => void switchIdentity()}>
              Switch identity
            </Button>
          </CardRow>
          <CardRow label="Session" description={<span>Signed in <When at={session.authTime} />, expires <When at={session.expiresAt} />.</span>} />
          <CardRow label="Sign out" description="Ends the session here and at the console's backend.">
            <Button size="sm" variant="danger" onClick={() => void signOut().then(() => navigate(pathFor('signin')))}>
              Sign out
            </Button>
          </CardRow>
        </Card>

        <Card title="Scope" subtitle="The account and region every page is read in">
          <CardRow label="Account" description="The accounts you belong to; your role in each comes from its groups.">
            <Select size="sm" ariaLabel={`Account: ${account.name}`} value={account.id} options={(accounts.data?.items ?? [session.account]).map((a) => ({ value: a.accountId, label: a.name, hint: `${formatAccountId(a.accountId)}${a.roles?.length ? ` · ${a.roles.join(', ')}` : ''}` }))} onChange={(id) => switchAccount(id).catch(failed('Could not switch account'))} mono />
          </CardRow>
          <CardRow label="Region" description={regions.length <= 1 ? 'The platform serves one region.' : 'From the regions the platform lists.'}>
            {regions.length <= 1 ? <span className="text-sm text-secondary">{region.title}</span> : <Select size="sm" ariaLabel={`Region: ${region.title}`} value={region.name} options={regions.map((r) => ({ value: r.name, label: r.title, hint: r.name }))} onChange={(name) => switchRegion(name).catch(failed('Could not switch region'))} />}
          </CardRow>
        </Card>

        <Card title="Notifications" subtitle="What the console tells you, and where">
          <CardRow label="Notification settings" description="Email, Slack and Teams for each kind of notification, and a test send.">
            <Button size="sm" icon="bell" onClick={() => navigate(pathFor('notification-settings'))}>
              Open notification settings
            </Button>
          </CardRow>
          <CardRow label="Linked chats" description="The Slack and Teams identities that act as you and receive your notifications.">
            <Button size="sm" icon="link" onClick={() => navigate(pathFor('chat-link'))}>
              Open linked chats
            </Button>
          </CardRow>
        </Card>

        <Card title="Data in this browser" subtitle="Nothing here leaves your browser">
          <CardRow label="Cached descriptors" description="Resource types, the catalog and the regions, kept with their ETags so the shell draws before the platform answers.">
            <Button size="sm" onClick={clearCaches} icon="refresh">
              Clear and reload
            </Button>
          </CardRow>
          <CardRow label="Build" description={`Console v${__APP_VERSION__}, contracts pinned to ${__CONTRACTS_COMMIT__}.`} />
        </Card>
      </div>
    </>
  )
}
