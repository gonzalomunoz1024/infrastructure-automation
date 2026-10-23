import { Button, Icon, Tag, ThemeToggle, useTheme, useToast } from '@iaf/ui'
import { BrandMark } from '../app/BrandMark'
import { phaseNote } from '../app/Phase'
import { navigate, type PageProps } from '../app/router'
import { DEV_IDENTITIES, setCurrentUser, useCurrentUser, identityOf } from '../lib/identity'
import { clearQueryCache } from '../lib/queries'

/*
 * The door. Phase 0 has no identity provider yet: the firm's single sign-on arrives with phase 1
 * (OIDC). Until then, development builds offer the identities the platform-fake knows, sent as
 * the X-User header the platform's own frontend uses.
 */

const SHOW_DEV_IDENTITIES = import.meta.env.DEV
const MOCK = import.meta.env.VITE_CONSOLE_MOCK === '1'

export default function SignInPage({ route }: PageProps) {
  const { mode, cycle } = useTheme()
  const toast = useToast()
  const current = useCurrentUser()
  const next = route.query.next || '/'

  const signInAs = (id: string) => {
    clearQueryCache()
    setCurrentUser(id)
    navigate(next, { replace: true })
  }

  const signOut = () => {
    clearQueryCache()
    setCurrentUser(null)
  }

  return (
    <div className="flex min-h-dvh flex-col bg-surface text-primary">
      <header className="flex h-14 items-center justify-between px-4 sm:px-6">
        <div className="flex items-center gap-2">
          <BrandMark />
          <span className="text-subheadline font-semibold tracking-tight">Console</span>
        </div>
        <ThemeToggle mode={mode} onCycle={cycle} />
      </header>
      <main id="main" className="flex flex-1 items-start justify-center px-4 py-10 sm:items-center sm:py-12">
        <div className="w-full max-w-md">
          <h1 className="text-title1 font-semibold tracking-tight">Sign in</h1>
          <p className="mt-2 text-sm text-secondary">One place to see the resources the platform manages for your team, and from phase 3 to change them.</p>

          <div className="mt-8 rounded-lg border border-stroke-light bg-surface-secondary p-5">
            <Button variant="primary" className="w-full justify-center" icon="shield" onClick={() => toast.toast({ title: 'Single sign-on is not wired yet', message: `${phaseNote(1)}: the console will sign you in with the firm's identity provider over OIDC.`, tone: 'neutral' })}>
              Continue with company SSO
            </Button>
            <p className="mt-3 text-caption text-tertiary">Sign-in through the firm's identity provider (OIDC) is {phaseNote(1).toLowerCase()}.</p>
          </div>

          {SHOW_DEV_IDENTITIES && (
            <section aria-labelledby="dev-identities" className="mt-8">
              <div className="mb-3 flex items-center justify-between gap-3">
                <h2 id="dev-identities" className="text-sm font-semibold">
                  Development identities
                </h2>
                <Tag size="sm" outline mono>
                  X-User
                </Tag>
              </div>
              <p className="mb-3 text-caption text-secondary">{MOCK ? 'Mock mode: the platform is answered from captured samples in this browser.' : 'Sent as the X-User header, the way the platform frontend does in development.'}</p>
              <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light">
                {DEV_IDENTITIES.map((d) => {
                  const active = d.id === current
                  return (
                    <li key={d.id}>
                      <button
                        type="button"
                        onClick={() => signInAs(d.id)}
                        aria-current={active ? 'true' : undefined}
                        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-secondary focus-visible:bg-surface-secondary"
                        data-identity={d.id}
                      >
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-accent-tint text-caption font-semibold text-accent" aria-hidden="true">
                          {d.name
                            .split(' ')
                            .map((p) => p[0])
                            .join('')}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium text-primary">
                            Continue as {d.name}
                            {active ? ' (signed in)' : ''}
                          </span>
                          <span className="block truncate text-caption text-tertiary">
                            {d.roles.join(', ')} · {d.teams.join(', ')} · <span className="font-mono">{d.id}</span>
                          </span>
                        </span>
                        <Icon name="arrow-right" size={16} className="shrink-0 text-tertiary" />
                      </button>
                    </li>
                  )
                })}
              </ul>
              {current && (
                <div className="mt-3 flex items-center justify-between gap-3 text-caption text-secondary">
                  <span>
                    Signed in as <span className="font-medium text-primary">{identityOf(current)?.name ?? current}</span>
                  </span>
                  <Button size="sm" variant="ghost" onClick={signOut}>
                    Sign out
                  </Button>
                </div>
              )}
            </section>
          )}
        </div>
      </main>
      <footer className="flex h-9 items-center justify-between px-4 text-caption text-tertiary sm:px-6">
        <span>
          Console <span className="font-mono">v{__APP_VERSION__}</span>
        </span>
        <span>
          contracts <span className="font-mono">{__CONTRACTS_COMMIT__}</span>
        </span>
      </footer>
    </div>
  )
}
