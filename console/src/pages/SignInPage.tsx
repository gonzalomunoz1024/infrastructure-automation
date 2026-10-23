import { useState } from 'react'
import { Button, Icon, Tag, ThemeToggle, useTheme } from '@iaf/ui'
import { errorMessage } from '@iaf/console-kit'
import { BrandMark } from '../app/BrandMark'
import { RoleTag } from '../app/RoleTag'
import { navigate, type PageProps } from '../app/router'
import { beginSignIn, bff, loadSession, signOut } from '../lib/bff'
import { useQuery } from '../lib/queries'
import { MOCK, useSessionState } from '../lib/session'

/*
 * The door. Sign-in goes through the firm's identity provider: the BFF runs the OIDC code flow
 * and comes back with the session cookie. In mock mode this page stands in for the identity
 * provider and lists the people it knows, so the shell and the journeys run with no backend.
 */

export default function SignInPage({ route }: PageProps) {
  const { mode, cycle } = useTheme()
  const state = useSessionState()
  const next = route.query.next || '/'
  const people = useQuery('demo-people', () => bff.people(), { enabled: MOCK })
  const [busy, setBusy] = useState<string | null>(null)
  const [failure, setFailure] = useState<unknown>(null)

  const continueAs = async (id?: string) => {
    setBusy(id ?? 'sso')
    setFailure(null)
    try {
      const left = await beginSignIn(next, id)
      if (!left) navigate(next, { replace: true })
    } catch (e) {
      setFailure(e)
      setBusy(null)
    }
  }

  const session = state.status === 'signed-in' ? state.session : null

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

          {session ? (
            <div className="mt-8 rounded-lg border border-stroke-light bg-surface-secondary p-5">
              <p className="text-sm">
                Signed in as <span className="font-medium">{session.user.name ?? session.user.email}</span> in {session.account.name}.
              </p>
              <div className="mt-3 flex items-center gap-2">
                <Button variant="primary" onClick={() => navigate(next, { replace: true })}>
                  Continue
                </Button>
                <Button variant="ghost" onClick={() => void signOut()}>
                  Sign out
                </Button>
              </div>
            </div>
          ) : MOCK ? (
            <section aria-labelledby="demo-idp" className="mt-8">
              <div className="mb-3 flex items-center justify-between gap-3">
                <h2 id="demo-idp" className="text-sm font-semibold">
                  Demo identity provider
                </h2>
                <Tag size="sm" outline mono>
                  mock
                </Tag>
              </div>
              <p className="mb-3 text-caption text-secondary">Mock mode: this page stands in for the firm's identity provider, and the platform is answered from captured samples in this browser. Pick who to continue as.</p>
              {people.error ? (
                <p role="alert" className="text-sm text-critical">
                  The demo identity provider did not answer: {errorMessage(people.error)}
                </p>
              ) : (
                <ul className="divide-y divide-stroke-light overflow-hidden rounded-lg border border-stroke-light" aria-label="People">
                  {(people.data?.items ?? []).map((p) => (
                    <li key={p.id}>
                      <button type="button" onClick={() => void continueAs(p.id)} disabled={busy !== null} className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-secondary focus-visible:bg-surface-secondary disabled:opacity-60" data-identity={p.id}>
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-accent-tint text-caption font-semibold text-accent" aria-hidden="true">
                          {p.name
                            .split(' ')
                            .map((w) => w[0])
                            .join('')}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium text-primary">Continue as {p.name}</span>
                          <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-caption text-tertiary">
                            {p.memberships.map((m) => (
                              <span key={m.accountId} className="inline-flex items-center gap-1">
                                <RoleTag role={m.roles[0] ?? 'member'} />
                                of {m.accountName}
                              </span>
                            ))}
                            {p.memberships.length === 0 && 'no account'}
                          </span>
                        </span>
                        <Icon name={busy === p.id ? 'clock' : 'arrow-right'} size={16} className="shrink-0 text-tertiary" />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ) : (
            <div className="mt-8 rounded-lg border border-stroke-light bg-surface-secondary p-5">
              <Button variant="primary" className="w-full justify-center" icon="shield" loading={busy === 'sso'} disabled={busy !== null} onClick={() => void continueAs()}>
                Continue with company SSO
              </Button>
              <p className="mt-3 text-caption text-tertiary">You sign in with the firm's identity provider (OIDC). The console never sees your password; it keeps a session cookie for eight hours.</p>
              {state.status === 'unreachable' && (
                <div role="alert" className="mt-4 rounded-md border border-warning/40 bg-warning-tint p-3 text-caption text-primary">
                  <p className="font-medium">The console's backend did not answer</p>
                  <p className="mt-0.5 text-secondary">{errorMessage(state.error)}. Start the BFF on :18081, or run the console with VITE_CONSOLE_MOCK=1.</p>
                  <Button size="sm" className="mt-2" icon="refresh" onClick={() => void loadSession()}>
                    Try again
                  </Button>
                </div>
              )}
            </div>
          )}

          {failure !== null && (
            <p role="alert" className="mt-4 text-sm text-critical">
              Could not sign you in: {errorMessage(failure)}
            </p>
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
