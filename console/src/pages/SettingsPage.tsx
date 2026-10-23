import { Button, Card, CardRow, Segmented, Select, useTheme, useToast, type ThemeMode } from '@iaf/ui'
import { useConsole } from '../app/console'
import { navigate, pathFor, type PageProps } from '../app/router'
import { DEMO_ACCOUNTS, formatAccountId, setAccount } from '../lib/accounts'
import { clearDescriptorCache } from '../lib/api'
import { DEV_IDENTITIES, identityOf, setCurrentUser, useCurrentUser } from '../lib/identity'
import { clearQueryCache } from '../lib/queries'
import { clearRecent } from '../lib/recent'
import { setRegionName } from '../lib/regions'

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
  const { me, account, region, regions, refetch } = useConsole()
  const user = useCurrentUser()
  const identity = identityOf(user)

  const clearCaches = () => {
    clearDescriptorCache()
    clearQueryCache()
    clearRecent()
    refetch()
    toast.success('Cleared', 'The cached descriptors, the recent pages and everything loaded in this session were dropped and fetched again.')
  }

  return (
    <>
      <div className="mb-6">
        <h1 className="text-title1 font-semibold tracking-tight text-primary">Settings</h1>
        <p className="mt-1 text-sm text-secondary">What the console remembers in this browser.</p>
      </div>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Appearance">
          <CardRow label="Theme" description="System follows your operating system.">
            <Segmented<ThemeMode> value={mode} onChange={setMode} options={THEME_OPTIONS} ariaLabel="Theme" size="sm" />
          </CardRow>
        </Card>

        <Card title="Identity" subtitle="Who the console acts as">
          <CardRow label={me?.name ?? 'Not signed in'} description={me ? `${me.id}${me.roles?.length ? ` · ${me.roles.join(', ')}` : ''}${me.teams?.length ? ` · ${me.teams.join(', ')}` : ''}` : 'The platform has not answered the identity call.'}>
            <Button size="sm" variant="ghost" onClick={() => navigate(pathFor('signin', {}, { switch: 1 }))}>
              Switch
            </Button>
          </CardRow>
          {import.meta.env.DEV && (
            <CardRow label="Development identity" description="Sent as the X-User header; the sign-in page lists the same people.">
              <Select
                size="sm"
                // Atlas's Select names its trigger with aria-label alone, so the value goes in the name too (WCAG 2.5.3)
                ariaLabel={identity ? `Development identity: ${identity.name}` : 'Development identity'}
                value={user ?? ''}
                options={DEV_IDENTITIES.map((d) => ({ value: d.id, label: d.name, hint: d.roles.join(', ') }))}
                onChange={(id) => {
                  clearQueryCache()
                  setCurrentUser(id)
                }}
              />
            </CardRow>
          )}
          <CardRow label="Sign out" description="Forgets the identity in this browser.">
            <Button
              size="sm"
              variant="danger"
              onClick={() => {
                clearQueryCache()
                setCurrentUser(null)
                navigate(pathFor('signin'))
              }}
            >
              Sign out
            </Button>
          </CardRow>
        </Card>

        <Card title="Scope" subtitle="The account and region every page is read in">
          <CardRow label="Account" description="Demo accounts until the platform serves them (phase 2).">
            <Select size="sm" ariaLabel={`Account: ${account.name}`} value={account.id} options={DEMO_ACCOUNTS.map((a) => ({ value: a.id, label: a.name, hint: formatAccountId(a.id) }))} onChange={setAccount} mono />
          </CardRow>
          <CardRow label="Region" description={regions.length <= 1 ? 'The platform serves one region.' : 'From GET /api/v1/regions.'}>
            {regions.length <= 1 ? <span className="text-sm text-secondary">{region.title}</span> : <Select size="sm" ariaLabel={`Region: ${region.title}`} value={region.name} options={regions.map((r) => ({ value: r.name, label: r.title, hint: r.name }))} onChange={setRegionName} />}
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
